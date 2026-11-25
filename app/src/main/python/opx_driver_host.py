"""Runs a wifi4 driver, unmodified, inside the APK.

The Java side opens the USB device (``UsbFdHandle``), hands over the usbfs fd, and calls the
functions below directly through Chaquopy. Driver selection uses wifi4's **own** package registry:
every ``wifi4/chips/<family>/__init__.py`` exposes ``SUPPORTED_IDS`` and ``import_driver()``, so the
31 families' id map is the reference's, not a copy maintained here.

wifi4's drivers are coroutines, and their receive reader keeps tasks alive for the whole session, so
one dedicated thread owns a single event loop and every call is posted to it.
"""

from __future__ import annotations

import asyncio
import base64
import importlib
import pkgutil
import sys
import threading
import traceback

import usb_shim

_driver = None
_device = None
_callback = None
_family = None
_loop = None
_thread = None
_lock = threading.Lock()


def _ensure_loop():
    global _loop, _thread
    if _loop is not None:
        return _loop
    loop = asyncio.new_event_loop()

    def serve():
        asyncio.set_event_loop(loop)
        loop.run_forever()

    thread = threading.Thread(target=serve, name="wifi4-driver", daemon=True)
    thread.start()
    _loop, _thread = loop, thread
    return loop


def _run(coroutine, timeout=60.0):
    future = asyncio.run_coroutine_threadsafe(coroutine, _ensure_loop())
    return future.result(timeout)


_REGISTRY = None


def _registry():
    """``{(vid, pid): (family, entry, import_driver)}`` from the vendored chip packages.

    Built once: the engine asks about every attached device on each ``devices``/``scan`` command,
    and importing 24 packages each time is wasted work.
    """
    global _REGISTRY
    if _REGISTRY is not None:
        return _REGISTRY
    import wifi4.chips as chip_packages

    table = {}
    for info in pkgutil.iter_modules(chip_packages.__path__):
        if not info.ispkg:
            continue
        try:
            module = importlib.import_module(f"wifi4.chips.{info.name}")
        except Exception:  # a family whose optional deps are absent must not break the rest
            continue
        for entry in getattr(module, "SUPPORTED_IDS", ()) or ():
            table[(entry.vid, entry.pid)] = (info.name, entry, module.import_driver)
    _REGISTRY = table
    return table


def has_driver(vendor, product):
    """True when a vendored wifi4 family claims this id pair."""
    return (int(vendor), int(product)) in _registry()


def families():
    """Every family key the registry covers, for diagnostics."""
    return sorted({value[0] for value in _registry().values()})


def start(fd, vendor, product, callback):
    """Wrap ``fd``, pick the family by id, bring the driver up and return its MAC."""
    global _driver, _device, _callback, _family
    with _lock:
        claim = _registry().get((int(vendor), int(product)))
        if claim is None:
            raise RuntimeError(f"no wifi4 driver for {vendor:04x}:{product:04x}")
        _family, entry, import_driver = claim
        _callback = callback
        _device = usb_shim.install(int(fd), int(vendor), int(product))
        driver_class = import_driver()
        _driver = driver_class.from_usb_device(_device, entry)
        _driver.register_rx_callback(_on_frame)
        _driver.register_disconnect_callback(_on_disconnect)
        _ensure_loop()
        _run(_connect(_driver))
        try:
            _run(_driver.enable_rx_acks())
        except Exception:
            # ACK tallying is optional; a chip that cannot stream ACKs still transmits.
            traceback.print_exc(file=sys.stderr)
        return getattr(_driver, "mac_address", None)


async def _connect(driver):
    return await driver.connect(_on_progress)


def _on_frame(packet):
    if _callback is None:
        return
    try:
        payload = bytes(getattr(packet, "raw", b"") or b"")
        _callback.onFrame(payload, int(getattr(packet, "rssi", 0) or 0))
    except Exception:
        traceback.print_exc(file=sys.stderr)


def _on_disconnect():
    if _callback is not None:
        try:
            _callback.onDisconnect()
        except Exception:
            traceback.print_exc(file=sys.stderr)


def _on_progress(percentage, message):
    if _callback is not None:
        try:
            _callback.onProgress(int(float(percentage)), str(message))
        except Exception:
            pass


def set_channel(channel, scan=False):
    if _driver is None:
        raise RuntimeError("no driver is open")
    return bool(_run(_driver.set_channel(int(channel), bool(scan))))


def inject(frame):
    if _driver is None:
        raise RuntimeError("no driver is open")
    return bool(_run(_driver.inject_frame(bytes(frame))))


def _mac_bytes():
    mac = getattr(_driver, "mac_address", None)
    if not mac:
        return None
    digits = mac.replace(":", "").replace("-", "")
    try:
        return bytes.fromhex(digits)
    except ValueError:
        return None


def acks():
    if _driver is None:
        return 0
    return int(_driver.acks_seen(_mac_bytes()))


def stop():
    global _driver, _device
    with _lock:
        if _driver is not None:
            try:
                _run(_driver.close(), timeout=15.0)
            except Exception:
                traceback.print_exc(file=sys.stderr)
            _driver = None
        if _device is not None:
            try:
                _device.close()
            except Exception:
                pass
            _device = None


def family():
    return _family


# -- optional line protocol, for running the host as a process (desktop tests) ------------

def run():
    import json

    def emit(payload):
        sys.stdout.write(json.dumps(payload) + "\n")
        sys.stdout.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
            op = request.get("op")
            if op == "start":
                mac = start(request["fd"], request["vid"], request["pid"], None)
                emit({"ok": True, "mac": mac, "family": family()})
            elif op == "channel":
                emit({"ok": set_channel(request["ch"], request.get("scan", False))})
            elif op == "tx":
                emit({"ok": inject(base64.b64decode(request["b64"]))})
            elif op == "acks":
                emit({"ok": True, "acks": acks()})
            elif op == "stop":
                stop()
                emit({"ok": True})
                return
            else:
                emit({"ok": False, "detail": f"unknown op {op!r}"})
        except Exception as error:  # noqa: BLE001 - reported, not swallowed
            emit({"event": "error", "stage": type(error).__name__, "detail": str(error)})


if __name__ == "__main__":
    run()
