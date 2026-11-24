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
    {"cmd": "targets"}                  -> {"event": "targets", "targets": [{...,"attacks":[...]}]}
    {"cmd": "attack", "kind": "deauth|pmkid|wps", "bssid": ".."}
                                        -> {"event": "attack_started"}, attack_log/attack_progress,
                                           then {"event": "attack_done", "ok": .., "summary": ..}
    {"cmd": "stop_attack"}              -> the running campaign stops; attack_done follows
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
import struct
import sys
import time
import traceback

VERSION = "0.3.0"

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
        self.attack_task = None

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
        await self.connect()
        self.scanning = True
        seen = {}
        try:
            await self.array.start_hopping()
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline and self.scanning:
                for bssid, ap in pool_access_points(self.array).items():
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
            ("deauth", "wifi4.campaigns.deauth", "DeauthCampaign"),
            ("pmkid", "wifi4.campaigns.pmkid", "PmkidHarvestAttack"),
            ("wps", "wifi4.campaigns.pin", "WpsCampaign"),
        ):
            try:
                registry[kind] = getattr(importlib.import_module(module), name)
            except Exception as exc:  # noqa: BLE001 - reported per campaign, never fatal
                log(f"attack {kind} unavailable: {type(exc).__name__}: {exc}")
        return registry

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
        if self.array is None:
            emit("targets", count=0, targets=[],
                 reason="no adapter: plug in a USB Wi-Fi card, scan, then tap the network")
            return

        targets = []
        for bssid, ap in pool_access_points(self.array).items():
            attacks = []
            for kind, campaign_cls in classes.items():
                try:
                    if not campaign_cls.visible(ap):
                        continue
                    reason = campaign_cls.ineligible_reason(ap)
                    label = campaign_cls.button_label(ap)
                except Exception:  # noqa: BLE001 - one odd AP must not blank the whole list
                    continue
                attacks.append({
                    "kind": kind,
                    "label": plain(label or campaign_cls.idle_label),
                    "enabled": reason is None,
                    "reason": plain(reason or ""),
                })
            targets.append({
                "bssid": bssid,
                "ssid": ap.ssid or "",
                "hidden": bool(ap.is_hidden),
                "channel": ap.channel,
                "security": security_of(ap, getattr(ap, "encryption", None)),
                "wifi4_encryption": getattr(ap, "encryption", None),
                "clients": self.ap_clients(bssid),
                "handshakes": len(getattr(ap, "handshakes", {}) or {}),
                "attacks": attacks,
            })
        emit("targets", count=len(targets), targets=targets)

    async def attack(self, kind, bssid):
        """Runs one wifi4 campaign against one AP, streaming its own status to the app."""
        if self.campaign is not None and not self.campaign.done:
            emit("error", message="an attack is already running")
            return
        classes = self.campaign_classes()
        if not classes:
            emit("error", message="the wifi4 attack layer is missing from this runtime")
            return
        campaign_cls = classes.get(str(kind or ""))
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
            if not campaign_cls.visible(ap):
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
        emit("attack_started", kind=kind, label=plain(campaign_cls.button_label(ap)),
             bssid=ap.bssid, ssid=ap.ssid or "", channel=ap.channel)
        self.attack_task = asyncio.create_task(self._watch_campaign(campaign, kind))

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
            emit("attack_progress", kind=kind, lines=[line for line in lines if line], under=under)
            await asyncio.sleep(1.0)
        if campaign.stopped:
            await asyncio.sleep(0.1)
        emit("attack_done", kind=kind, stopped=bool(campaign.stopped),
             **self.campaign_outcome(campaign, kind))

    @staticmethod
    def campaign_outcome(campaign, kind):
        """What the card says when a campaign ends: one summary, one detail line."""
        if kind == "deauth":
            captured = bool(getattr(campaign, "captured", False))
            sent = getattr(campaign, "client_sent", 0) or 0
            acked = getattr(campaign, "client_acks", 0) or 0
            broadcast = getattr(campaign, "bcast_sent", 0) or 0
            return {
                "ok": captured,
                "summary": "handshake captured" if captured else "no new handshake captured",
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
            if pin:
                return {"ok": True, "summary": "WPS PIN found", "detail": str(pin)}
            tested = getattr(state, "tested", None)
            return {"ok": False, "summary": "WPS PIN not found", "detail": f"{tested} PINs tried"}
        return {"ok": False, "summary": "finished", "detail": ""}

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
        if self.campaign is not None and not self.campaign.done:
            self.campaign.request_stop()
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
            elif name == "targets":
                await engine.targets()
            elif name == "attack":
                await engine.attack(command.get("kind"), command.get("bssid"))
            elif name == "stop_attack":
                await engine.stop_attack()
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
