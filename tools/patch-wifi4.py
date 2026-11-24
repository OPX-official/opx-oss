#!/usr/bin/env python3
"""Turns the wifi4 source tree into the headless engine OPX OSS ships.

The wifi4 project is a backend plus a Textual TUI. The app keeps the backend only:

  * ``wifi4/ui/`` (the TUI) is removed, and the one WEP status helper it owned is replaced
    with a headless version in ``wifi4/engine/wep_status.py``; the WEP campaign itself stays.
    The ``__main__`` console entry point that launches the app is removed too.
  * the desktop-only privileged setup modules (udev/modprobe on Linux, WinUSB on Windows) are
    dropped: Android uses ``SetupTermux``, which needs no privileged step, and the other two
    are only imported lazily by ``Setup.for_platform()``.
  * the one backend module that lazily built the TUI prompter (``device/manager.py``) is
    pointed at the headless prompter this script installs as ``wifi4.engine.prompter``.
  * every hardcoded ``/data/data/com.termux`` path and every user-facing message naming the
    separate API app is rewritten to OPX OSS's own prefix and in-app USB bridge, so nothing
    in the shipped tree still mentions the other app;
  * the Android half is renamed to OPX OSS's own names: the platform probe (``is_opx_oss``),
    the setup class (``SetupOpxOss``), the USB bridge module (``wifi4.device.opx_bridge``),
    the command it runs (``opx-usb``) and the descriptor variable (``OPX_USB_FD``). The tree
    is then checked for the old name and the patch fails if any is left.

Anything still importing the TUI afterwards is a hard error, so the package can never ship a
module that raises ImportError on a device without ``textual``.
"""

import argparse
import pathlib
import re
import sys

REMOVE = [
    "ui",
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
    '        if is_opx_oss():\n'
    '            from wifi4.setup.opx_oss import SetupOpxOss\n'
    '            return SetupOpxOss()\n'
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
    # The WEP campaign stays in the headless package, but its footer used the removed TUI's
    # focus model. Point that single call at the headless helper this script installs.
    (
        "from wifi4.ui.focus_model import wep_status_lines",
        "from wifi4.engine.wep_status import wep_status_lines",
    ),
]

# The Android half of the engine is OPX OSS's own all the way down: its probe, its setup class,
# its module, the command it runs and the variable that carries the descriptor. Anything left
# named after the other app is a build failure (see the final guard).
RENAMES = [
    ("wifi4.setup.termux", "wifi4.setup.opx_oss"),
    ("wifi4.device.termux_usb", "wifi4.device.opx_bridge"),
    ("SetupTermux", "SetupOpxOss"),
    ("TermuxBackend", "OpxUsbBackend"),
    ("is_termux", "is_opx_oss"),
    ("termux_usb", "opx_bridge"),
    ("TERMUX_USB_FD", "OPX_USB_FD"),
    ("TERMUX_USB_DEVICE", "OPX_USB_DEVICE"),
    ("TERMUX_VERSION", "OPX_OSS"),
    ("termux-usb", "opx-usb"),
    ("termux-api", "opx runtime"),
]

RENAMED_FILES = [
    ("wifi4/setup/termux.py", "wifi4/setup/opx_oss.py"),
    ("wifi4/device/termux_usb.py", "wifi4/device/opx_bridge.py"),
]

# Prose only: whole words, so an identifier such as ``TermuxBackend`` is handled by RENAMES and
# never turned into something with a space in it.
WORD_PATCH = re.compile(r"\bTermux\b")
LOWER_WORD_PATCH = re.compile(r"\btermux\b")

ENGINE_INIT = '''"""Headless OPX additions: the prompter the engine uses instead of the removed TUI."""
'''

HEADLESS_WEP = '''"""The WEP footer without the removed Textual focus model.

``WepCampaign.status_under_dash`` was the only shipped backend caller of the TUI's
``wep_status_lines``. The numbers come from wifi4's own WEP capture store, so the app can show
the usable-IV progress while the campaign is replaying.
"""
from __future__ import annotations

from wifi4.crack.wep import CRACK_READY_THRESHOLD


def wep_status_lines(ap, array, campaign, now: float) -> list[str]:
    samples = array.wep_store.crack_sample_count(ap.bssid) if array else 0
    usable = f"{samples:,}" if samples else "0"
    if samples < CRACK_READY_THRESHOLD:
        usable = f"{usable}/{CRACK_READY_THRESHOLD // 1000}k"
    lines = []
    if campaign is not None:
        state = getattr(getattr(campaign, "replay", None), "state", "")
        lines.append(f"Fake-Auth: {state or 'starting'}")
    lines.append(f"Usable IVs: {usable}")
    return lines
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
    (engine / "wep_status.py").write_text(HEADLESS_WEP, encoding="utf-8")
    print("  added wifi4/engine/{__init__,prompter,wep_status}.py")

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

    # Move the Android-side modules to their own names before rewriting references to them.
    for old_name, new_name in RENAMED_FILES:
        old_path = root / old_name
        new_path = root / new_name
        if old_path.is_file():
            old_path.rename(new_path)
            print(f"  renamed {old_name} -> {new_name}")

    # OPX OSS's own names for the whole Android path: no code in the shipped tree refers to
    # another app's command, module, environment variable or descriptor name.
    renamed_files = 0
    renames_applied = 0
    for path in sorted(root.rglob("*.py")):
        original = path.read_text(encoding="utf-8", errors="replace")
        text = original
        for old, new in RENAMES:
            renames_applied += text.count(old)
            text = text.replace(old, new)
        text = WORD_PATCH.sub("OPX OSS", text)
        text = LOWER_WORD_PATCH.sub("opx oss", text)
        if text != original:
            path.write_text(text, encoding="utf-8")
            renamed_files += 1
    print(f"  renamed the Android path in {renamed_files} file(s) ({renames_applied} names)")

    offenders = []
    for path in sorted(root.rglob("*.py")):
        for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            if any(pattern.search(line) for pattern in FORBIDDEN):
                offenders.append(f"{path.relative_to(root)}:{number}: {line.strip()}")
            if "/data/data/com.termux" in line:
                offenders.append(f"{path.relative_to(root)}:{number}: leftover foreign prefix")
            if "termux" in line.lower():
                offenders.append(f"{path.relative_to(root)}:{number}: leftover other-app name")
    if offenders:
        print("modules still reference the removed TUI or another app:", file=sys.stderr)
        for offender in offenders:
            print("  " + offender, file=sys.stderr)
        return 1

    print("  no TUI/Textual references left, nothing named after another app left")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
