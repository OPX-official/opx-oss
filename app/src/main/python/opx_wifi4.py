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
import threading
import traceback

import opx_driver_host as host
import usb_shim

# wifi4's backend imports usb at module level; the shim has to exist before that, so the
# packages can be imported (and probed) before any card is open.
usb_shim.bootstrap()

logger = logging.getLogger("opx.wifi4")

_array = None
_iface = None
_campaign = None
_kind = None
_bssid = None
_lock = threading.RLock()

#: Every campaign this build exposes, by the name the app's protocol uses.
_CAMPAIGN_KEYS = ("deauth", "pmkid", "pin", "pbc", "decloak")


def _campaign_class(kind: str):
    """The campaign class for ``kind``; imported here so a broken family can't break the rest."""
    if kind == "deauth":
        from wifi4.campaigns.deauth import DeauthCampaign
        return DeauthCampaign
    if kind == "pmkid":
        from wifi4.campaigns.pmkid import PmkidCampaign
        return PmkidCampaign
    if kind == "pin":
        from wifi4.campaigns.pin import PinCampaign
        return PinCampaign
    if kind == "pbc":
        from wifi4.campaigns.pbc import PbcCampaign
        return PbcCampaign
    if kind == "decloak":
        from wifi4.campaigns.decloak import DecloakCampaign
        return DecloakCampaign
    raise ValueError(f"no wifi4 campaign called {kind!r}")


def _entry():
    """The registry row that claimed this card, for the interface's description fields."""
    entry = getattr(host, "_entry", None)
    if entry is None:
        claim = host._registry().get((int(host._vendor), int(host._product)))
        entry = claim[1] if claim is not None else None
    return entry


def attach():
    """Build wifi4's array around the card ``opx_driver_host`` already connected."""
    global _array, _iface
    with _lock:
        if _array is not None:
            return True
        if host._driver is None or host._device is None:
            raise RuntimeError("no card is open: open the adapter before asking wifi4 for targets")
        from wifi4.wlan.array import WlanArray
        from wifi4.wlan.interface import WlanInterface

        entry = _entry()
        iface = WlanInterface(
            host._driver,
            "wifi4",
            getattr(entry, "description", None) or "wifi4",
            vid=getattr(entry, "vid", None),
            pid=getattr(entry, "pid", None),
            dev=host._device,
            chipset=getattr(entry, "chipset", None),
            vendor=getattr(entry, "vendor", None),
            product_name=getattr(entry, "product_name", None),
        )
        array = WlanArray()
        array.attach(iface)
        # ``attach`` made wifi4's interface the driver's RX callback. Chain the host's own
        # callback in front of it, so a frame still reaches the Java engine (its scan book and
        # handshake capture) *and* wifi4's sink (which is where these targets come from).
        wifi4_sink = iface._on_frame_parsed

        def fan_out(packet):
            host._on_frame(packet)
            try:
                wifi4_sink(packet)
            except Exception:
                # A frame wifi4 cannot parse must not kill the driver's reader thread.
                traceback.print_exc()

        host._driver.register_rx_callback(fan_out)
        _array, _iface = array, iface
        return True


def start_scan(channels=None, interval=0.5):
    """Hop the card(s) across ``channels`` (wifi4's own spread hop) and fill its AP table."""
    attach()

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
    return {
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
        "chipset": getattr(ap, "chipset", None),
        "beacons": int(getattr(ap, "beacons", 0) or 0),
        "handshakes": len(getattr(ap, "handshakes", {}) or {}),
        "clients": len(clients),
        "clients_list": clients,
    }


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


def attack(kind, bssid=None, **options):
    """Run wifi4's ``kind`` campaign against ``bssid``; True when it took the radio."""
    global _campaign, _kind, _bssid
    attach()
    if kind not in _CAMPAIGN_KEYS:
        raise ValueError(f"no wifi4 campaign called {kind!r}")
    ap = None
    if bssid:
        ap = _array.access_points.get(str(bssid).lower())
        if ap is None:
            raise ValueError(f"wifi4 has not seen {bssid}")
    with _lock:
        if _campaign is not None and not _campaign.done:
            raise RuntimeError("a wifi4 campaign is already running")
        campaign = _campaign_class(kind)(_array, ap, **options)
        started = host._run(_launch(campaign), timeout=30.0)
        if started:
            _campaign, _kind, _bssid = campaign, kind, (str(bssid).lower() if bssid else None)
        return bool(started)


async def _launch(campaign):
    # run() creates the campaign's task, so it has to execute on the loop the array lives on.
    return campaign.run()


def stop_attack():
    global _campaign, _kind, _bssid
    campaign = _campaign
    if campaign is None:
        return False
    try:
        host._run(_stop(campaign), timeout=120.0)
    except Exception:
        # A campaign that refuses to unwind still gives the radio back: request_stop is sync.
        campaign.request_stop()
        traceback.print_exc()
    _campaign, _kind, _bssid = None, None, None
    return True


async def _stop(campaign):
    campaign.request_stop()
    await campaign.stop()
    return True


def attack_status():
    campaign = _campaign
    return json.dumps({
        "running": bool(campaign is not None and not campaign.done),
        "kind": _kind,
        "bssid": _bssid,
        "done": bool(campaign is None or campaign.done),
    })


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
