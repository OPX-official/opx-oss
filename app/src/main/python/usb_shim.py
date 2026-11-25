"""Minimal ``usb.core`` / ``usb.util`` replacement for Android.

The wifi4 drivers import PyUSB and call ``usb.core.find(...)``, ``dev.read/write``,
``dev.ctrl_transfer`` and ``usb.util.claim_interface``. On Android an app cannot enumerate
``/dev/bus/usb`` itself, but it *can* get a usbfs handle from ``UsbManager``. That fd arrives here
over the ``opx.usb`` socket (SCM_RIGHTS), and libusb can take it over with
``libusb_wrap_sys_device()`` — after which PyUSB's semantics apply unchanged.

This module is installed as ``usb.core``/``usb.util`` before any driver is imported:

    import usb_shim; usb_shim.install(fd, vendor, product)

Requires ``libusb-1.0.so`` for the ABI, bundled in ``jniLibs`` (LGPL-2.1, dynamically linked).
"""

from __future__ import annotations

import ctypes
import ctypes.util
import errno
import os

_libusb = None
_CONTEXT = [None]


class USBError(IOError):
    def __init__(self, message, errno_=None):
        super().__init__(message)
        self.errno = errno_


class USBTimeoutError(USBError):
    pass


def _open_library():
    """Load ``libusb-1.0.so`` from wherever this platform keeps it.

    Android ignores ``LD_LIBRARY_PATH`` for app libraries, so the soname alone can fail; asking
    Android's linker through ``System.loadLibrary`` and, failing that, using the absolute path from
    ``nativeLibraryDir`` always finds the copy packaged in ``jniLibs``.
    """
    errors = []
    name = ctypes.util.find_library("usb-1.0")
    for candidate in ([name] if name else []) + ["libusb-1.0.so"]:
        try:
            return ctypes.CDLL(candidate)
        except OSError as error:
            errors.append(str(error))
    try:
        from java import jclass  # Chaquopy: only present inside the APK

        jclass("java.lang.System").loadLibrary("usb-1.0")
        return ctypes.CDLL("libusb-1.0.so")
    except Exception as error:  # noqa: BLE001 - the absolute path below is the last resort
        errors.append(str(error))
    try:
        from java import jclass

        application = jclass("com.chaquo.python.Python").getPlatform().getApplication()
        directory = application.getApplicationInfo().nativeLibraryDir
        return ctypes.CDLL(f"{directory}/libusb-1.0.so")
    except Exception as error:  # noqa: BLE001 - reported below
        errors.append(str(error))
    raise USBError("libusb-1.0.so could not be loaded: " + "; ".join(errors), errno.ENOENT)


def _lib():
    global _libusb
    if _libusb is not None:
        return _libusb
    lib = _open_library()
    if hasattr(lib, "libusb_init_context"):
        # Android has no /dev/bus/usb enumeration for apps: the only supported mode is
        # NO_DEVICE_DISCOVERY, after which the fd we were handed is the whole world.
        class _Option(ctypes.Structure):
            _fields_ = [("option", ctypes.c_int), ("value", ctypes.c_int)]

        lib.libusb_init_context.argtypes = [ctypes.POINTER(ctypes.c_void_p),
                                            ctypes.POINTER(_Option), ctypes.c_size_t]
        lib.libusb_init_context.restype = ctypes.c_int
        option = _Option(2, 0)  # LIBUSB_OPTION_NO_DEVICE_DISCOVERY
        context = ctypes.c_void_p()
        rc = lib.libusb_init_context(ctypes.byref(context), ctypes.byref(option), 1)
    else:
        lib.libusb_init.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
        lib.libusb_init.restype = ctypes.c_int
        context = ctypes.c_void_p()
        rc = lib.libusb_init(ctypes.byref(context))
        if rc == 0 and hasattr(lib, "libusb_set_option"):
            lib.libusb_set_option(context, 2)  # LIBUSB_OPTION_NO_DEVICE_DISCOVERY
    if rc < 0:
        raise USBError(f"libusb initialisation failed: {rc}", -rc)
    _CONTEXT[0] = context
    lib.libusb_wrap_sys_device.argtypes = [ctypes.c_void_p, ctypes.c_int,
                                           ctypes.POINTER(ctypes.c_void_p)]
    lib.libusb_wrap_sys_device.restype = ctypes.c_int
    lib.libusb_get_device.argtypes = [ctypes.c_void_p]
    lib.libusb_get_device.restype = ctypes.c_void_p
    lib.libusb_claim_interface.argtypes = [ctypes.c_void_p, ctypes.c_int]
    lib.libusb_claim_interface.restype = ctypes.c_int
    lib.libusb_release_interface.argtypes = [ctypes.c_void_p, ctypes.c_int]
    lib.libusb_release_interface.restype = ctypes.c_int
    lib.libusb_bulk_transfer.argtypes = [ctypes.c_void_p, ctypes.c_ubyte,
                                         ctypes.POINTER(ctypes.c_ubyte),
                                         ctypes.c_int, ctypes.POINTER(ctypes.c_int),
                                         ctypes.c_uint]
    lib.libusb_bulk_transfer.restype = ctypes.c_int
    lib.libusb_control_transfer.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_ubyte,
                                            ctypes.c_ushort, ctypes.c_ushort,
                                            ctypes.POINTER(ctypes.c_ubyte), ctypes.c_ushort,
                                            ctypes.c_uint]
    lib.libusb_control_transfer.restype = ctypes.c_int
    lib.libusb_close.argtypes = [ctypes.c_void_p]
    lib.libusb_exit.argtypes = [ctypes.c_void_p]
    _libusb = lib
    return lib


def _raise(code, what):
    if code < 0:
        raise USBError(f"{what} failed: libusb error {code}", -code)


class _Endpoint:
    def __init__(self, address, packet_size=64):
        self.bEndpointAddress = address
        self.wMaxPacketSize = packet_size


class Device:
    """The subset of PyUSB's ``Device`` the drivers touch."""

    def __init__(self, handle, vendor, product):
        self._handle = handle
        self.idVendor = vendor
        self.idProduct = product
        self._claimed = set()
        self._lib = _lib()

    # PyUSB surface -------------------------------------------------------
    def read(self, endpoint, size_or_buffer, timeout=None):
        lib = self._lib
        buf = (ctypes.c_ubyte * size_or_buffer)()
        transferred = ctypes.c_int(0)
        rc = lib.libusb_bulk_transfer(self._handle, endpoint, buf, size_or_buffer,
                                      ctypes.byref(transferred),
                                      0 if timeout is None else int(timeout))
        if rc == -7:  # LIBUSB_ERROR_TIMEOUT
            raise USBTimeoutError("bulk read timed out", errno.ETIMEDOUT)
        _raise(rc, "bulk read")
        return bytes(bytearray(buf[:transferred.value]))

    def write(self, endpoint, data, timeout=None):
        lib = self._lib
        payload = bytes(data)
        buf = (ctypes.c_ubyte * len(payload)).from_buffer_copy(payload)
        transferred = ctypes.c_int(0)
        rc = lib.libusb_bulk_transfer(self._handle, endpoint, buf, len(payload),
                                      ctypes.byref(transferred),
                                      0 if timeout is None else int(timeout))
        if rc == -7:
            raise USBTimeoutError("bulk write timed out", errno.ETIMEDOUT)
        _raise(rc, "bulk write")
        return transferred.value

    def ctrl_transfer(self, bmRequestType, bRequest, wValue=0, wIndex=0,
                      data_or_wLength=None, timeout=None):
        lib = self._lib
        if isinstance(data_or_wLength, int) or data_or_wLength is None:
            length = int(data_or_wLength or 0)
            buf = (ctypes.c_ubyte * max(length, 1))()
            rc = lib.libusb_control_transfer(
                self._handle, bmRequestType, bRequest, wValue, wIndex, buf, length,
                0 if timeout is None else int(timeout))
            _raise(rc, "control read")
            if length == 0:
                return 0
            return bytes(bytearray(buf[:length]))
        payload = bytes(data_or_wLength)
        buf = (ctypes.c_ubyte * max(len(payload), 1)).from_buffer_copy(payload or b"\x00")
        rc = lib.libusb_control_transfer(
            self._handle, bmRequestType, bRequest, wValue, wIndex, buf, len(payload),
            0 if timeout is None else int(timeout))
        _raise(rc, "control write")
        return rc

    def set_configuration(self, _configuration=None):
        return None

    def get_active_configuration(self):
        return _Configuration()

    def is_kernel_driver_active(self, _interface):
        return False

    def detach_kernel_driver(self, _interface):
        return None

    def reset(self):
        return None

    def close(self):
        if self._handle:
            self._lib.libusb_close(self._handle)
            self._handle = None


class _Configuration:
    def __init__(self):
        self.bConfigurationValue = 1

    def set(self):
        return None


class _Interface:
    def __init__(self, number):
        self.bInterfaceNumber = number


_MODULE = None


class _CoreModule:
    """Stands in for ``usb.core``: exposes the wrapped device via ``find``."""

    USBError = USBError
    USBTimeoutError = USBTimeoutError
    Device = Device

    def __init__(self):
        self.device = None

    def find(self, find_all=False, custom_match=None, **_kwargs):
        if self.device is None:
            raise USBError("no Android USB device was wrapped", errno.ENODEV)
        if custom_match is not None and not custom_match(self.device):
            return [] if find_all else None
        return [self.device] if find_all else self.device


class _UtilModule:
    # PyUSB's endpoint constants: the drivers describe endpoints with these, so they must exist
    # before any bulk endpoint can be found.
    ENDPOINT_DIR_MASK = 0x80
    ENDPOINT_IN = 0x80
    ENDPOINT_OUT = 0x00
    ENDPOINT_TYPE_MASK = 0x03
    ENDPOINT_TYPE_CTRL = 0
    ENDPOINT_TYPE_ISO = 1
    ENDPOINT_TYPE_BULK = 2
    ENDPOINT_TYPE_INTR = 3
    SPEED_LOW = 1
    SPEED_FULL = 2
    SPEED_HIGH = 3
    SPEED_SUPER = 4

    @staticmethod
    def endpoint_direction(address):
        return address & _UtilModule.ENDPOINT_DIR_MASK

    @staticmethod
    def endpoint_type(address):
        return address & _UtilModule.ENDPOINT_TYPE_MASK

    def claim_interface(self, device, interface):
        number = getattr(interface, "bInterfaceNumber", interface)
        if number in device._claimed:
            return
        _raise(device._lib.libusb_claim_interface(device._handle, number), "claim interface")
        device._claimed.add(number)

    def release_interface(self, device, interface):
        number = getattr(interface, "bInterfaceNumber", interface)
        if number not in device._claimed:
            return
        device._lib.libusb_release_interface(device._handle, number)
        device._claimed.discard(number)

    def dispose_resources(self, device):
        for number in list(device._claimed):
            device._lib.libusb_release_interface(device._handle, number)
            device._claimed.discard(number)

    def find_descriptor(self, device, bInterfaceNumber=0, **_kwargs):
        return _Interface(bInterfaceNumber)

    def endpoint_address(self, endpoint):
        return getattr(endpoint, "bEndpointAddress", endpoint)


def install(fd: int, vendor: int, product: int):
    """Wrap ``fd`` and register the shim as ``usb.core`` / ``usb.util``."""
    import sys

    lib = _lib()
    handle = ctypes.c_void_p()
    rc = lib.libusb_wrap_sys_device(_CONTEXT[0], int(fd), ctypes.byref(handle))
    # -5 (LIBUSB_ERROR_NOT_FOUND) can still leave a handle on some kernels; treat 0 as success.
    if rc != 0:
        raise USBError(f"libusb_wrap_sys_device failed: {rc}", -rc)

    core = _CoreModule()
    core.device = Device(handle, vendor, product)
    util = _UtilModule()

    core_module = type(sys)("usb.core")
    core_module.USBError = USBError
    core_module.USBTimeoutError = USBTimeoutError
    core_module.Device = Device
    core_module.Interface = _Interface
    core_module.find = core.find
    util_module = type(sys)("usb.util")
    for name in dir(util):
        if not name.startswith("_"):
            setattr(util_module, name, getattr(util, name))

    usb_pkg = type(sys)("usb")
    usb_pkg.core = core_module
    usb_pkg.util = util_module

    sys.modules["usb"] = usb_pkg
    sys.modules["usb.core"] = core_module
    sys.modules["usb.util"] = util_module
    global _MODULE
    _MODULE = core
    return core.device
