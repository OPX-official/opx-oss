#!/usr/bin/env python3
"""The OPX headless engine: the wifi4 backend with no TUI, behind a line protocol.

One JSON object per line on stdin, one JSON event per line on stdout. It is started by the
app's foreground service inside the app's own runtime prefix, so the WiFi section never needs
a terminal, a second APK or a shell prompt.

Commands
    {"cmd": "ping"}                     -> {"event": "ready", "version": ..., "runtime": {...}}
    {"cmd": "devices"}                  -> {"event": "devices", "devices": [...]}
    {"cmd": "status"}                   -> {"event": "status", "iface": ..., "connected": ...}
    {"cmd": "scan", "seconds": 15}      -> {"event": "network", ...} ... {"event": "scan_done"}
    {"cmd": "stop"}                     -> {"event": "stopped"}

A ``ready`` event is also emitted as soon as the process starts, without waiting for a ping:
the app logs it, and a runtime that cannot import wifi4 says exactly why here instead of
looking like an empty network list.

Every failure is reported as {"event": "error", "message": ...}; the engine never exits on a
bad command, so the app can keep the process warm across screens.
"""

import asyncio
import json
import os
import platform
import struct
import sys
import time
import traceback

VERSION = "0.3.0"


def emit(event, **fields):
    payload = {"event": event}
    payload.update(fields)
    sys.stdout.write(json.dumps(payload, ensure_ascii=False, default=str) + "\n")
    sys.stdout.flush()


def log(message):
    sys.stderr.write(f"opx-engine: {message}\n")
    sys.stderr.flush()


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

    async def ping(self):
        emit("ready", version=VERSION, native=bool(os.environ.get("PREFIX")),
             runtime=runtime_info())

    async def devices(self):
        try:
            from wifi4.device.manager import devices as list_devices
        except Exception as exc:  # noqa: BLE001
            emit("error", message=f"cannot import the wifi4 USB layer: {type(exc).__name__}: {exc}")
            return

        found = await asyncio.to_thread(list_devices)
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

    async def connect(self):
        if self.iface is not None:
            return self.iface
        from wifi4.device.manager import devices as list_devices, wlan_iface
        from wifi4.wlan.array import WlanArray

        found = await asyncio.to_thread(list_devices)
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
        log(f"interface {iface.name} connected ({found[0].description})")
        return iface

    async def status(self):
        if self.iface is None:
            emit("status", connected=False, iface=None, access_points=0, band=None,
                 runtime=runtime_info())
            return
        aps = self.array.access_points() if self.array else {}
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
        await self.connect()
        self.scanning = True
        seen = {}
        try:
            await self.array.start_hopping()
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline and self.scanning:
                for bssid, ap in self.array.access_points().items():
                    fields = ap_fields(ap)
                    key = fields["bssid"] or bssid
                    if seen.get(key) != fields:
                        seen[key] = fields
                        emit("network", **fields)
                await asyncio.sleep(0.5)
        finally:
            self.scanning = False
            try:
                await self.array.stop_hopping()
            except Exception:  # noqa: BLE001
                pass
        emit("scan_done", count=len(seen), seconds=seconds)

    async def scan(self, seconds=None):
        """Runs the scan as a task so `devices`, `status` and `stop` stay responsive."""
        if self.scan_task is not None and not self.scan_task.done():
            emit("error", message="a scan is already running")
            return
        self.scan_task = asyncio.create_task(self._scan(float(seconds or 20)))

        def finished(task):
            if task.cancelled():
                return
            error = task.exception()
            if error is not None:
                log(traceback.format_exception_only(type(error), error)[-1].strip())
                emit("error", message=f"{type(error).__name__}: {error}")
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

    async def stop(self):
        self.scanning = False
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
            elif name == "status":
                await engine.status()
            elif name == "scan":
                await engine.scan(command.get("seconds"))
            elif name == "stop_scan":
                await engine.stop_scan()
            elif name == "stop":
                await engine.stop()
                return 0
            else:
                emit("error", message=f"unknown command: {name}")
        except Exception as exc:  # noqa: BLE001
            log(traceback.format_exc())
            emit("error", message=f"{type(exc).__name__}: {exc}")
    await engine.stop()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(0)
