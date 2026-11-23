"""OPX OSS: make the prefix's libusb visible to pyusb on Android.

ctypes.util.find_library asks ldconfig, gcc and ld, none of which exist inside an app
runtime, so pyusb reports "No backend available" even though libusb is installed in the
prefix. CPython's site module imports this file at startup because the app puts its
directory on PYTHONPATH.
"""

import ctypes.util
import os

_real_find_library = ctypes.util.find_library
_prefix = os.environ.get("PREFIX") or "/data/data/com.opx.oss/files/usr"
_sonames = ("libusb-1.0.so", "libusb-1.0.so.0")
_names = ("usb-1.0", "libusb-1.0", "usb")


def _find_library(name):
    found = _real_find_library(name)
    if found:
        return found
    if name in _names:
        for soname in _sonames:
            candidate = os.path.join(_prefix, "lib", soname)
            if os.path.exists(candidate):
                return candidate
    return None


ctypes.util.find_library = _find_library
