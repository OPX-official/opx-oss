#!/usr/bin/env python3
"""Prints the .deb files the base runtime needs, and nothing else.

``build-bootstraps.sh`` leaves every deb it built in ``output/``: the runtime packages, the
build-time-only helpers that produced them (perl for openssl, doxygen, ...) and every
``-dev``/``-static`` subpackage of each. Merging that whole directory — what this script
replaces — is what made the base payload ~287 MiB per ABI, roughly nine times the same
runtime in the bootstrap: it dragged in 112 MiB of static archives, the whole perl tree, the
documentation toolchain and tor.

The engine runs ``bash``, ``python`` and ``libusb``; what those need at run time is declared
in the debs' own ``Depends`` fields, so this resolves that closure from the metadata and
prints one path per package. What it leaves out is either build-time-only or already
installed by the bootstrap (the app extracts the bootstrap first and untars the payload over
it), so the two together still make a complete runtime.

Usage:
  select-debs.py --deb-dir OUT/output --arch aarch64 --roots bash,coreutils,python,libusb
"""

import argparse
import collections
import pathlib
import subprocess
import sys


def control(path: pathlib.Path, field: str) -> str:
    try:
        text = subprocess.check_output(
            ["dpkg-deb", "-f", str(path), field], text=True, stderr=subprocess.DEVNULL
        )
    except (OSError, subprocess.CalledProcessError):
        return ""
    return text.strip()


def dependencies(text: str) -> list[list[str]]:
    """``a (>= 1), b | c`` -> ``[['a'], ['b', 'c']]``.

    A group is a set of alternatives: any one of them satisfies the requirement. Version
    constraints and architecture qualifiers are dropped; the debs on disk are the only
    candidates, so the version in the constraint is not a choice we make here.
    """
    groups = []
    for chunk in text.split(","):
        alternatives = []
        for alternative in chunk.split("|"):
            name = alternative.split("(")[0].split(":")[0].strip()
            if name:
                alternatives.append(name)
        if alternatives:
            groups.append(alternatives)
    return groups


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--deb-dir", required=True, type=pathlib.Path)
    parser.add_argument("--arch", required=True)
    parser.add_argument("--roots", required=True,
                        help="comma separated packages the runtime is built from")
    args = parser.parse_args()

    if not args.deb_dir.is_dir():
        print(f"no such deb directory: {args.deb_dir}", file=sys.stderr)
        return 1

    provided: dict[str, list[pathlib.Path]] = collections.defaultdict(list)
    for deb in sorted(args.deb_dir.glob("*.deb")):
        if control(deb, "Architecture") != args.arch:
            continue
        name = control(deb, "Package")
        if not name:
            continue
        provided[name].append(deb)
        for virtual in control(deb, "Provides").split(","):
            virtual = virtual.split("(")[0].split(":")[0].strip()
            if virtual:
                provided.setdefault(virtual, []).append(deb)

    roots = [name.strip() for name in args.roots.split(",") if name.strip()]
    missing_roots = [name for name in roots if name not in provided]
    if missing_roots:
        print(f"these base packages were not built for {args.arch}: "
              + ", ".join(missing_roots), file=sys.stderr)
        return 1

    chosen: list[pathlib.Path] = []
    seen: set[str] = set()
    missing: dict[str, set[str]] = {}
    queue = collections.deque(roots)
    while queue:
        name = queue.popleft()
        if name in seen:
            continue
        seen.add(name)
        debs = provided.get(name)
        if not debs:
            continue
        deb = debs[0]
        chosen.append(deb)
        for group in dependencies(control(deb, "Depends")):
            if any(alternative in seen or alternative in provided for alternative in group):
                for alternative in group:
                    if alternative in provided:
                        queue.append(alternative)
                        break
                continue
            # Not built here: the package build system resolves some of these from its own
            # repository (the app helpers), and the bootstrap installs them on the device.
            for alternative in group:
                missing.setdefault(alternative, set()).add(name)

    for deb in chosen:
        print(deb)
    for name, wanted_by in sorted(missing.items()):
        print(f"  note: {name} (needed by {', '.join(sorted(wanted_by))}) is not in "
              f"{args.deb_dir}; the bootstrap provides it", file=sys.stderr)
    print(f"  resolved {len(chosen)} of {len(provided)} {args.arch} packages for "
          f"{', '.join(roots)}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
