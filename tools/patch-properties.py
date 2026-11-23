#!/usr/bin/env python3
"""Forks the termux-packages build properties to the OPX OSS app identity.

OPX OSS is a native app whose runtime lives at ``/data/data/com.opx.oss/files/usr`` — its own
prefix, inside its own app directory. The Termux build system compiles every package against a
single compiled-in prefix derived from ``TERMUX_APP__PACKAGE_NAME`` in
``scripts/properties.sh``; changing that one variable (plus the API-app package name and
namespaces, which the same file blesses as fork-safe) recompiles the whole base runtime for our
prefix. Official packages compiled for the other app's prefix can never be mixed in.

This script applies those fork changes to a termux-packages checkout and fails if any
``com.termux`` string survives anywhere in properties.sh.
"""

import argparse
import pathlib
import sys

# The only variables that are fork-safe per properties.sh's own comments. LNAME/UNAME and the
# app display names are derived from TERMUX__NAME, so changing it changes those too.
REPLACES = [
    ('TERMUX__NAME="Termux"', 'TERMUX__NAME="OPX OSS"'),
    ('TERMUX_APP__PACKAGE_NAME="com.termux"', 'TERMUX_APP__PACKAGE_NAME="com.opx.oss"'),
    ('TERMUX_API_APP__PACKAGE_NAME="com.termux.api"', 'TERMUX_API_APP__PACKAGE_NAME="com.opx.oss.api"'),
    ('TERMUX_APP__NAMESPACE="com.termux"', 'TERMUX_APP__NAMESPACE="com.opx.oss"'),
    ('TERMUX_API_APP__NAMESPACE="com.termux.api"', 'TERMUX_API_APP__NAMESPACE="com.opx.oss.api"'),
    ('TERMUX_AM_APP__NAMESPACE="com.termux.termuxam"', 'TERMUX_AM_APP__NAMESPACE="com.opx.oss.termuxam"'),
    ('TERMUX_REPO_APP__PACKAGE_NAME="com.termux"', 'TERMUX_REPO_APP__PACKAGE_NAME="com.opx.oss"'),
    ('TERMUX_REPO_APP__DATA_DIR="/data/data/com.termux"', 'TERMUX_REPO_APP__DATA_DIR="/data/data/com.opx.oss"'),
    ('TERMUX_REPO__CORE_DIR="/data/data/com.termux/termux/core"', 'TERMUX_REPO__CORE_DIR="/data/data/com.opx.oss/termux/core"'),
    ('TERMUX_REPO__APPS_DIR="/data/data/com.termux/termux/app"', 'TERMUX_REPO__APPS_DIR="/data/data/com.opx.oss/termux/app"'),
    ('TERMUX_REPO__ROOTFS="/data/data/com.termux/files"', 'TERMUX_REPO__ROOTFS="/data/data/com.opx.oss/files"'),
    ('TERMUX_REPO__HOME="/data/data/com.termux/files/home"', 'TERMUX_REPO__HOME="/data/data/com.opx.oss/files/home"'),
    ('TERMUX_REPO__PREFIX="/data/data/com.termux/files/usr"', 'TERMUX_REPO__PREFIX="/data/data/com.opx.oss/files/usr"'),
    ('CGCT_DEFAULT_PREFIX="/data/data/com.termux/files/usr/glibc"', 'CGCT_DEFAULT_PREFIX="/data/data/com.opx.oss/files/usr/glibc"'),
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("checkout", type=pathlib.Path, help="a termux-packages checkout")
    args = parser.parse_args()

    target = args.checkout / "scripts" / "properties.sh"
    if not target.is_file():
        print(f"not a termux-packages checkout: {args.checkout}", file=sys.stderr)
        return 1

    text = target.read_text(encoding="utf-8")
    missing = []
    for old, new in REPLACES:
        if old in text:
            text = text.replace(old, new)
        elif new in text:
            continue
        else:
            missing.append(old)
    if missing:
        print("properties.sh does not contain the expected fork points:", file=sys.stderr)
        for one in missing:
            print("  " + one, file=sys.stderr)
        return 1

    # The file documents the defaults in comments (wiki links, "Default value: com.termux");
    # those are harmless. Only live assignments matter.
    offenders = [
        line.strip() for line in text.splitlines()
        if not line.lstrip().startswith("#")
        and __import__("re").match(r'[A-Z][A-Z0-9_]*="[^"]*com\.termux', line.strip())
    ]
    if offenders:
        print("properties.sh still assigns the foreign package name:", file=sys.stderr)
        for offender in offenders:
            print("  " + offender, file=sys.stderr)
        return 1

    target.write_text(text, encoding="utf-8")
    print("  forked scripts/properties.sh to com.opx.oss")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
