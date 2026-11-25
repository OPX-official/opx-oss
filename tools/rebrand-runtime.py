#!/usr/bin/env python3
"""Gives a built runtime tree OPX OSS's own banner instead of the upstream one.

The base runtime is compiled from another project's package tree, so a few of the files that
tree installs still advertise that project: ``etc/motd`` greets the user with its name and its
site, ``etc/motd-playstore`` tells them to move to another app store, and ``etc/termux/mirrors``
lists that project's package mirrors. None of them is read by the engine, apt or the shell
snippets this app keeps — apt reads the rewritten ``etc/apt/sources.list`` — but they are part
of what the app ships, so they say OPX OSS or they are not shipped.

Usage:
  rebrand-runtime.py PATH...   # an installed tree, or a bootstrap .zip, in place
"""

import argparse
import pathlib
import sys
import zipfile

MOTD = """Welcome to OPX OSS

Docs:      https://github.com/OPX-official/opx-oss
Packages:  https://github.com/OPX-official/opx-packages

Working with packages:

 - Search:  apt search <query>
 - Install: apt install <package>
 - Upgrade: apt update && apt upgrade

This runtime belongs to the OPX OSS app: the engine is started by the app, not from a terminal.
"""

# The other app's banner, and the mirror list that points at its archive. Neither is used here.
DROP_FILES = ("etc/motd-playstore",)
DROP_DIRS = ("etc/termux/mirrors",)
MOTD_PATH = "etc/motd"


def _branded(relative: str) -> bool:
    if relative in DROP_FILES:
        return True
    return any(relative == one or relative.startswith(one + "/") for one in DROP_DIRS)


def brand_tree(root: pathlib.Path) -> list[str]:
    """Writes our banner and drops the foreign one; returns what was removed."""
    removed = []
    for relative in DROP_FILES:
        path = root / relative
        if path.is_file():
            path.unlink()
            removed.append(relative)
    for relative in DROP_DIRS:
        path = root / relative
        if path.is_dir():
            count = sum(1 for child in path.rglob("*") if child.is_file())
            for child in sorted(path.rglob("*"), key=lambda p: len(p.parts), reverse=True):
                if child.is_file() or child.is_symlink():
                    child.unlink()
            for child in sorted(path.rglob("*"), key=lambda p: len(p.parts), reverse=True):
                if child.is_dir():
                    child.rmdir()
            path.rmdir()
            removed.append(f"{relative}/ ({count} files)")
    motd = root / MOTD_PATH
    motd.parent.mkdir(parents=True, exist_ok=True)
    motd.write_text(MOTD, encoding="utf-8")
    motd.chmod(0o644)
    return removed


def brand_zip(path: pathlib.Path) -> list[str]:
    """The same pass on a bootstrap archive: rebuild it without the foreign files."""
    with zipfile.ZipFile(path) as source:
        entries = source.infolist()
        kept = [info for info in entries if not _branded(info.filename)]
        dropped = len(entries) - len(kept)
        motd = next((info for info in kept if info.filename == MOTD_PATH), None)
        if motd is None:
            motd = zipfile.ZipInfo(MOTD_PATH, date_time=(2026, 1, 1, 0, 0, 0))
            kept.append(motd)
        replacement = path.with_suffix(path.suffix + ".rebrand")
        with zipfile.ZipFile(replacement, "w", zipfile.ZIP_DEFLATED) as target:
            for info in kept:
                if info.filename == MOTD_PATH:
                    data = MOTD.encode("utf-8")
                    mode = (info.external_attr >> 16) if info.external_attr else 0o644
                    info.external_attr = (0o100000 | (mode or 0o644)) << 16
                else:
                    data = source.read(info)
                target.writestr(info, data)
    replacement.replace(path)
    removed = [relative for relative in DROP_FILES
               if any(info.filename == relative for info in entries)]
    for relative in DROP_DIRS:
        if any(info.filename.startswith(relative + "/") for info in entries):
            removed.append(f"{relative}/")
    return removed + [f"{dropped} archive entries dropped"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=pathlib.Path)
    args = parser.parse_args()
    for path in args.paths:
        if path.is_dir():
            removed = brand_tree(path)
        elif path.is_file() and zipfile.is_zipfile(path):
            removed = brand_zip(path)
        else:
            print(f"not a tree or bootstrap archive: {path}", file=sys.stderr)
            return 1
        for one in removed:
            print(f"  drop   {one}")
        print(f"  brand  {path} ({MOTD_PATH})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
