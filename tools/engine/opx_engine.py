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
    {"cmd": "attack_status"}            -> {"event": "attack_status", "running": bool, ...}
    {"cmd": "stop_attack"}              -> the running campaign stops; attack_done follows
    {"cmd": "captures"}                 -> {"event": "captures", "captures": [...]}
    {"cmd": "crack_tools"}              -> {"event": "crack_tools", "tools": [...]}
    {"cmd": "crack", "capture": "..", "tool": "cpu_crack|hashcat"}
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

VERSION = "0.4.0"

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
        self.crack_task = None

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
                "wps": bool(getattr(ap, "wps", False)),
                "wps_locked": bool(getattr(ap, "wps_locked", False)),
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
        self.campaign_ap = ap
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
            return
        try:
            from wifi4.persist.save import save_handshake, save_pmkid
        except Exception as exc:  # noqa: BLE001
            log(f"cannot import the capture savers: {type(exc).__name__}: {exc}")
            return
        for client in list((getattr(ap, "handshakes", {}) or {}).keys()):
            for saver in (save_handshake, save_pmkid):
                try:
                    saver(ap, client)
                except Exception as exc:  # noqa: BLE001 - one odd frame must not stop the attack
                    log(f"{saver.__name__} failed: {type(exc).__name__}: {exc}")

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
        try:
            self.harvest(getattr(campaign, "ap", None))
        except Exception:  # noqa: BLE001
            pass
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
            stages.append(stage("assoc", "Associating with the AP", "done"))
            stages.append(stage("wps", "WPS exchange", "done" if pin else "active",
                                f"PIN {pin}" if pin else f"{tried:,} PINs tried"))
            stages.append(stage("pixie", "Pixie-dust computation",
                                "done" if pin else "pending"))
            metrics.append(metric("tried", "PINs TRIED", f"{tried:,}"))
            if pin:
                metrics.append(metric("pin", "PIN", pin))

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

    async def captures(self):
        """Lists the saved handshakes/PMKIDs the Handshakes screen shows."""
        try:
            entries = await asyncio.to_thread(self.capture_entries)
        except Exception as exc:  # noqa: BLE001
            detail = traceback.format_exc()
            log(detail)
            emit("error", message=f"{type(exc).__name__}: {exc}", detail=detail.strip())
            return
        cracked = sum(1 for entry in entries if entry["keys"])
        total_bytes = sum(entry["size"] for entry in entries)
        emit("captures", captures=entries, count=len(entries), cracked=cracked,
             bytes=total_bytes, directory=str(captures_dir()))

    def crack_tools(self):
        """Which wifi4 cracking tools this runtime can actually start.

        CPU crack ships with wifi4 (pure Python) and always works. Hashcat needs its own
        executable, which the app's runtime does not ship, so it is offered only when one is
        on PATH or configured - never as a button that cannot run.
        """
        tools = [{
            "tool": "cpu_crack",
            "label": "CPU Crack",
            "available": True,
            "reason": "",
            "description": "Built-in CPU cracker (WPA/WPA2/PMKID)",
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
        })
        return tools

    async def tool_list(self):
        emit("crack_tools", tools=self.crack_tools())

    async def crack(self, name, tool="cpu_crack", wordlists=None, default_passwords=True,
                    ssid_passwords=True):
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
                      "ap_ssid_passwords": bool(ssid_passwords)}
            tool_name = ToolName.HASHCAT
        else:
            from wifi4.vault.tools.cpu_crack import CpuCrackTool

            cracker = CpuCrackTool()
            config = {"wordlists": wordlists, "default_passwords": bool(default_passwords),
                      "ap_ssid_passwords": bool(ssid_passwords)}
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
