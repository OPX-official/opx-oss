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
import sys
import time
import traceback

VERSION = "0.2.0"


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
    }
    try:
        import wifi4

        info["wifi4"] = getattr(wifi4, "__version__", "unknown")
        info["wifi4_ok"] = True
    except Exception as exc:  # noqa: BLE001 - reported, never raised
        info["wifi4_ok"] = False
        info["wifi4_error"] = f"{type(exc).__name__}: {exc}"
    try:
        from wifi4.host import is_termux

        info["android_usb_backend"] = bool(is_termux())
    except Exception:  # noqa: BLE001
        info["android_usb_backend"] = None
    return info


def ap_fields(ap):
    """Best-effort mapping of an AccessPoint to what the UI draws."""
    def pick(*names):
        for name in names:
            value = getattr(ap, name, None)
            if value not in (None, ""):
                return value
        return None

    privacy = pick("privacy", "security", "encryption")
    return {
        "ssid": pick("ssid", "essid") or "",
        "bssid": pick("bssid", "mac") or "",
        "channel": pick("channel"),
        "signal": pick("rssi", "signal", "power", "dbm"),
        "privacy": str(privacy) if privacy is not None else None,
        "wps": bool(pick("wps", "wps_version") or False),
        "vendor": pick("vendor", "manuf"),
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
            from wifi4.device import termux_usb

            hint = termux_usb.last_error()
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
            emit("status", connected=False, iface=None, access_points=0, runtime=runtime_info())
            return
        emit(
            "status",
            connected=True,
            iface=self.iface.name,
            chipset=getattr(self.iface, "chipset", None),
            mac=getattr(self.iface, "mac_address", None),
            access_points=len(self.array.access_points()) if self.array else 0,
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
