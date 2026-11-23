#!/usr/bin/env python3
"""The OPX headless engine: the wifi4 backend with no TUI, behind a line protocol.

One JSON object per line on stdin, one JSON event per line on stdout. It is started by the
app's foreground service inside the bundled userland, so the WiFi section never needs a
terminal, a second APK or a shell prompt.

Commands
    {"cmd": "ping"}                     -> {"event": "ready", "version": ...}
    {"cmd": "devices"}                  -> {"event": "devices", "devices": [...]}
    {"cmd": "status"}                   -> {"event": "status", "iface": ..., "connected": ...}
    {"cmd": "scan", "seconds": 15}      -> {"event": "network", ...} ... {"event": "scan_done"}
    {"cmd": "stop"}                     -> {"event": "stopped"}

Every failure is reported as {"event": "error", "message": ...}; the engine never exits on a
bad command, so the app can keep the process warm across screens.
"""

import asyncio
import json
import os
import sys
import time
import traceback

VERSION = "0.1.0"


def emit(event, **fields):
    payload = {"event": event}
    payload.update(fields)
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def log(message):
    sys.stderr.write(f"opx-engine: {message}\n")
    sys.stderr.flush()


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

    async def ping(self):
        emit("ready", version=VERSION, termux=bool(os.environ.get("PREFIX")))

    async def devices(self):
        from wifi4.device.manager import devices as list_devices

        found = await asyncio.to_thread(list_devices)
        emit(
            "devices",
            devices=[
                {
                    "chipset": getattr(d, "chipset", None),
                    "description": getattr(d, "description", None),
                    "vendor": getattr(d, "vendor", None),
                    "product": getattr(d, "product_name", None),
                    "bus": getattr(d, "bus", None),
                    "address": getattr(d, "address", None),
                }
                for d in found
            ],
        )

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
            emit("status", connected=False, iface=None, access_points=0)
            return
        emit(
            "status",
            connected=True,
            iface=self.iface.name,
            chipset=getattr(self.iface, "chipset", None),
            mac=getattr(self.iface, "mac_address", None),
            access_points=len(self.array.access_points()) if self.array else 0,
        )

    async def scan(self, seconds=None):
        seconds = float(seconds or 20)
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
            except Exception:
                pass
        emit("scan_done", count=len(seen), seconds=seconds)

    async def stop(self):
        self.scanning = False
        try:
            if self.array is not None:
                await self.array.close()
        except Exception:
            pass
        emit("stopped")
        return True


async def main():
    engine = Engine()
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
        except Exception as exc:
            log(traceback.format_exc())
            emit("error", message=f"{type(exc).__name__}: {exc}")
    await engine.stop()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(0)
