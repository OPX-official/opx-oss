#!/usr/bin/env python3
"""Turns the wifi4 source tree into the headless engine OPX OSS ships.

The wifi4 project is a backend plus a Textual TUI. The app keeps the backend only:

  * ``wifi4/ui/`` (the TUI) is removed, together with ``campaigns/wep/`` (legacy WEP campaign
    whose status lines go straight through the removed focus model) and the ``__main__``
    console entry point that launches the app.
  * the desktop-only privileged setup modules (udev/modprobe on Linux, WinUSB on Windows) are
    dropped: Android uses ``SetupTermux``, which needs no privileged step, and the other two
    are only imported lazily by ``Setup.for_platform()``.
  * the one backend module that lazily built the TUI prompter (``device/manager.py``) is
    pointed at the headless prompter this script installs as ``wifi4.engine.prompter``.
  * every hardcoded ``/data/data/com.termux`` path and every user-facing message naming the
    separate API app is rewritten to OPX OSS's own prefix and in-app USB bridge, so nothing
    in the shipped tree still mentions the other app.

Anything still importing the TUI afterwards is a hard error, so the package can never ship a
module that raises ImportError on a device without ``textual``.
"""

import argparse
import pathlib
import re
import sys

REMOVE = [
    "ui",
    "campaigns/wep",
    "__main__.py",
    "scripts",
    "setup/linux.py",
    "setup/windows.py",
    "setup/macos.py",
]

# Imports that must not survive in a shipped module: the TUI itself, Textual, or a desktop
# setup module that would raise on Android.
FORBIDDEN = [
    re.compile(r"(^|\s)from\s+wifi4\.ui"),
    re.compile(r"(^|\s)import\s+wifi4\.ui"),
    re.compile(r"(^|\s)from\s+textual"),
    re.compile(r"(^|\s)import\s+textual"),
    re.compile(r"(^|\s)from\s+wifi4\.setup\.(linux|windows|macos)"),
]

PLATFORM_PATCH = (
    '        if is_termux():\n'
    '            from wifi4.setup.termux import SetupTermux\n'
    '            return SetupTermux()\n'
    '        if sys.platform == "win32":\n'
    '            from wifi4.setup.windows import SetupWindows\n'
    '            return SetupWindows()\n'
    '        if sys.platform.startswith("linux"):\n'
    '            from wifi4.setup.linux import SetupLinux\n'
    '            return SetupLinux()\n'
    '        if sys.platform == "darwin":\n'
    '            from wifi4.setup.macos import SetupMacOS\n'
    '            return SetupMacOS()\n'
    '        return NoSetup()\n',
    '        if is_termux():\n'
    '            from wifi4.setup.termux import SetupTermux\n'
    '            return SetupTermux()\n'
    '        # OPX OSS is the Android build: the desktop privileged setup modules\n'
    '        # (WinUSB, udev/modprobe, macOS) are not packaged, so the step is a no-op.\n'
    '        return NoSetup()\n',
)

PROMPTER_PATCH = (
    "        if prompter is None:\n"
    "            from wifi4.ui.bringup_prompter import BringupPrompter\n"
    "            prompter = BringupPrompter(app)\n",
    "        if prompter is None:\n"
    "            from wifi4.engine.prompter import HeadlessPrompter\n"
    "            prompter = HeadlessPrompter(app)\n",
)

# Every occurrence of these exact strings is rewritten to OPX OSS's own app identity. Newline
# and quote variants are handled by applying to the raw text.
TEXT_PATCHES = [
    (
        '"/data/data/com.termux/files/usr"',
        '"/data/data/com.opx.oss/files/usr"',
    ),
    (
        '"/com.termux/files/usr"',
        '"/com.opx.oss/files/usr"',
    ),
    (
        '"/data/data/com.termux"',
        '"/data/data/com.opx.oss"',
    ),
    (
        "The Termux:API app must be installed (the termux-api package alone is not enough).",
        "The OPX OSS app provides the USB bridge in-process; no separate app is needed.",
    ),
    (
        'or the first attached device) and return its fd, or None.\n    The socket is bound before ``termux-usb`` starts, so a fast callback cannot race it."""',
        'or the first attached device) and return its fd, or None.\n    The socket is bound before ``termux-usb`` starts, so a fast callback cannot race it."""',
    ),
    (
        '"no USB device is visible; connect the adapter and install the "\n                       "Termux:API app"',
        '"no USB device is visible; connect the adapter and grant USB access in "\n                       "the OPX OSS app"',
    ),
    (
        '"termux-usb is unavailable; install the Termux:API app and its "\n                       "termux-api package"',
        '"termux-usb is unavailable; grant USB access in the OPX OSS app and retry"',
    ),
    (
        '"libusb could not be loaded. Install it in Termux and relaunch:\\n\\n"\n            "  pkg install libusb"',
        '"libusb could not be loaded. Reinstall the OPX OSS runtime:\\n\\n"\n            "  opx runtime repair"',
    ),
]

ENGINE_INIT = '''"""Headless OPX additions: the prompter the engine uses instead of the removed TUI."""
'''

HEADLESS_PROMPTER = '''"""A prompter with no user interface.

The bring-up engine was written against a Textual prompter (progress modal, replug dialog,
error screen). OPX OSS drives the same engine from the app, so every interaction that used to
open a modal becomes a no-op here and the outcome is reported over the engine's JSON stream
instead. Nothing raises, so a missing UI can never break a bring-up.
"""
from __future__ import annotations

import logging
from collections.abc import Callable

logger = logging.getLogger(__name__)


class HeadlessPrompter:
    def __init__(self, app=None) -> None:
        self._app = app
        self.last_error: tuple[str, str] | None = None

    async def open(self, title: str) -> None:
        logger.debug("bring-up: %s", title)

    def close(self) -> None:
        pass

    def status(self, message: str) -> None:
        logger.debug("bring-up status: %s", message)

    def status_progress(self, fraction: float, message: str) -> None:
        logger.debug("bring-up %.0f%%: %s", fraction * 100, message)

    def error(self, title: str, body: str) -> None:
        self.last_error = (title, body)
        logger.warning("%s: %s", title, body)

    async def run_with_terminal(self, run: Callable[[], int]) -> int | None:
        # No terminal exists in this app; privileged steps are not part of the Android path.
        return None

    async def ask(self, dialog):
        # Modal dialogs have no equivalent here: treat the answer as "dismissed".
        return None

    async def wait_replug(self, device_id):
        return None

    def begin_assistant(self, greeting, messages, *, intro_delay: float = 2.0) -> None:
        pass

    async def end_assistant(self, ok: bool) -> None:
        pass
'''


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("tree", type=pathlib.Path, help="the wifi4 package directory to patch")
    args = parser.parse_args()

    root = args.tree
    if not (root / "wifi4").is_dir():
        print(f"not a wifi4 package tree: {root}", file=sys.stderr)
        return 1

    for relative in REMOVE:
        target = root / "wifi4" / relative
        if target.is_dir():
            for child in sorted(target.rglob("*"), reverse=True):
                child.unlink() if child.is_file() else child.rmdir()
            target.rmdir()
            print(f"  removed wifi4/{relative}/")
        elif target.exists():
            target.unlink()
            print(f"  removed wifi4/{relative}")

    manager = root / "wifi4" / "device" / "manager.py"
    text = manager.read_text(encoding="utf-8")
    old, new = PROMPTER_PATCH
    if old in text:
        manager.write_text(text.replace(old, new), encoding="utf-8")
        print("  patched wifi4/device/manager.py (headless prompter)")
    elif new in text:
        print("  wifi4/device/manager.py already patched")
    else:
        print("manager.py does not have the expected prompter block", file=sys.stderr)
        return 1

    base = root / "wifi4" / "setup" / "base.py"
    text = base.read_text(encoding="utf-8")
    old, new = PLATFORM_PATCH
    if old in text:
        base.write_text(text.replace(old, new), encoding="utf-8")
        print("  patched wifi4/setup/base.py (Android-only platform setup)")
    elif new in text:
        print("  wifi4/setup/base.py already patched")
    else:
        print("base.py does not have the expected platform dispatch", file=sys.stderr)
        return 1

    engine = root / "wifi4" / "engine"
    engine.mkdir(parents=True, exist_ok=True)
    (engine / "__init__.py").write_text(ENGINE_INIT, encoding="utf-8")
    (engine / "prompter.py").write_text(HEADLESS_PROMPTER, encoding="utf-8")
    print("  added wifi4/engine/{__init__,prompter}.py")

    # Rewrite the app identity: every hardcoded path and message that names the other app.
    paths_changed = 0
    for path in sorted(root.rglob("*.py")):
        original = path.read_text(encoding="utf-8", errors="replace")
        text = original
        for old, new in TEXT_PATCHES:
            text = text.replace(old, new)
        if text != original:
            path.write_text(text, encoding="utf-8")
            paths_changed += 1
    print(f"  rewrote app identity in {paths_changed} file(s)")

    offenders = []
    for path in sorted(root.rglob("*.py")):
        for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if any(pattern.search(line) for pattern in FORBIDDEN):
                offenders.append(f"{path.relative_to(root)}:{number}: {line.strip()}")
            if "/data/data/com.termux" in line:
                offenders.append(f"{path.relative_to(root)}:{number}: leftover foreign prefix")
            if "Termux:API" in line:
                offenders.append(f"{path.relative_to(root)}:{number}: leftover API-app reference")
    if offenders:
        print("modules still reference the removed TUI or the other app's prefix:", file=sys.stderr)
        for offender in offenders:
            print("  " + offender, file=sys.stderr)
        return 1

    print("  no TUI/Textual references left, no foreign prefix left")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
