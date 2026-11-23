#!/usr/bin/env python3
"""Make upstream's build-bootstraps.sh tolerant of packages that moved.

Termux's bootstrap script keeps a hardcoded list of packages and calls
build-package.sh on each one. Upstream reorganises that list regularly: bzip2, for
instance, is no longer its own package, it is a subpackage of libbz2 whose deb is
emitted by the libbz2 build. build-package.sh then aborts the whole bootstrap with
"No package bzip2 found in any of the enabled repositories", 45 minutes into a run,
for a package that is in fact present.

This fork inserts one resolver pass before the build loop:

* a package with its own directory is built as before;
* a package that only exists as <name>.subpackage.sh under some parent is skipped,
  because the parent build already produces its deb and extract_debs picks it up;
* anything else fails immediately with the package name, instead of building for
  half an hour and dying later.

Run from build-base.sh right after patch-properties.py, with the -a package list as the
second argument; the package list is then checked against the checkout before docker starts.
"""

import pathlib
import re
import sys

ANCHOR = '\t\t# Build packages.\n\t\tfor package_name in "${PACKAGES[@]}"; do\n'

RESOLVER = '''\t\t# Resolve the package list against this checkout: upstream moves packages around
\t\t# (bzip2 ships as libbz2's subpackage now), and build-package.sh aborts on a name
\t\t# that has no directory even though its deb is produced by a parent build.
\t\tRESOLVED_PACKAGES=()
\t\tfor package_name in "${PACKAGES[@]}"; do
\t\t\tif [[ -d "$TERMUX_SCRIPTDIR/packages/$package_name" || -d "$TERMUX_SCRIPTDIR/disabled-packages/$package_name" ]]; then
\t\t\t\tRESOLVED_PACKAGES+=("$package_name")
\t\t\telif compgen -G "$TERMUX_SCRIPTDIR/packages/*/$package_name.subpackage.sh" > /dev/null; then
\t\t\t\techo "[*] '$package_name' is a subpackage upstream; its parent build provides the deb"
\t\t\telse
\t\t\t\techo "[!] '$package_name' is not in this checkout of termux-packages (commit $(git -C "$TERMUX_SCRIPTDIR" rev-parse --short HEAD 2>/dev/null || echo unknown))" 1>&2
\t\t\t\treturn 1
\t\t\tfi
\t\t\tdone
\t\tunset package_name
\t\tPACKAGES=("${RESOLVED_PACKAGES[@]}")

'''


def resolve(root: pathlib.Path, extra: list[str]) -> list[str]:
    """The packages the bootstrap will be asked to build, checked against the checkout."""
    text = (root / "scripts" / "build-bootstraps.sh").read_text(encoding="utf-8")
    wanted = re.findall(r'PACKAGES\+=\("([^"$]+)"\)', text) + extra
    missing = []
    for name in wanted:
        if (root / "packages" / name).is_dir() or (root / "disabled-packages" / name).is_dir():
            continue
        if list((root / "packages").glob(f"*/{name}.subpackage.sh")):
            print(f"  {name}: subpackage upstream, provided by its parent build")
            continue
        missing.append(name)
    return missing


def main(root: str, extra: list[str]) -> int:
    directory = pathlib.Path(root)
    script = directory / "scripts" / "build-bootstraps.sh"
    if not script.is_file():
        print(f"patch-bootstraps: {script} not found", file=sys.stderr)
        return 1

    text = script.read_text(encoding="utf-8")
    if "RESOLVED_PACKAGES" in text:
        print("  build-bootstraps.sh already resolves packages")
    elif ANCHOR not in text:
        print("patch-bootstraps: the build loop anchor moved upstream", file=sys.stderr)
        return 1
    else:
        script.write_text(text.replace(ANCHOR, RESOLVER + ANCHOR, 1), encoding="utf-8")
        print("  patched scripts/build-bootstraps.sh (package resolver)")

    # Preflight in seconds what would otherwise fail inside docker after ~40 minutes.
    missing = resolve(directory, extra)
    if missing:
        print("patch-bootstraps: these packages are not in the checkout: " + ", ".join(missing),
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    arguments = sys.argv[1:]
    package_root = arguments[0] if arguments else "."
    extras = [p for p in arguments[1].split(",") if p] if len(arguments) > 1 else []
    sys.exit(main(package_root, extras))
