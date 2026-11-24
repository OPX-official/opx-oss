#!/usr/bin/env python3
"""Removes everything that names another app's package from a built tree or archive.

The base runtime is compiled by the package build system, which also installs its own
app-integration helpers: ``termux-tools`` (``pkg``, ``login``, ``chsh``, ``am``, ``dalvikvm``,
``termux-*``), ``termux-exec`` (the ld-preload library and its headers) and the shell snippets
that load the other app's properties. Their bodies name ``com.termux`` — either as a literal
file path or as the package they talk to — so this app must not ship them, and the build gate
(``tools/make-index.py``, ``tools/fetch-packages.sh``) would refuse the artifact.

Nothing this app needs lives in them: the runtime the engine uses is bash, coreutils, python,
libusb and apt, and apt reads the rewritten ``etc/apt/sources.list``.

Usage:
  prune-foreign.py --needle com.termux PATH...   # trees, .zip and .tar.gz in place
  prune-foreign.py --check  --needle com.termux PATH...
"""

import argparse
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

DEFAULT_NEEDLE = "com.termux"


def _matches(path: pathlib.Path, needle: bytes) -> bool:
    try:
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                if needle in chunk:
                    return True
    except OSError:
        return False
    return False


def _drop_empty_dirs(root: pathlib.Path) -> None:
    for path in sorted(root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if path.is_dir() and not path.is_symlink():
            try:
                path.rmdir()
            except OSError:
                pass


def prune_tree(root: pathlib.Path, needle: bytes) -> list[str]:
    removed = []
    for path in sorted(root.rglob("*"), reverse=True):
        if path.is_symlink() or not path.is_file():
            continue
        if _matches(path, needle):
            path.unlink()
            removed.append(path.relative_to(root).as_posix())
    _drop_empty_dirs(root)
    return removed


def prune_tar(path: pathlib.Path, needle: bytes) -> list[str]:
    """Unpack, drop the matching files, repack.

    tarfile member-by-member repacking is an order of magnitude slower than the system tar,
    and these payloads are hundreds of megabytes; extraction and repacking take seconds.
    """
    with tempfile.TemporaryDirectory(dir=path.parent) as tmp:
        tree = pathlib.Path(tmp) / "tree"
        tree.mkdir()
        subprocess.run(["tar", "-C", str(tree), "-xzf", str(path)], check=True)
        removed = prune_tree(tree, needle)
        tops = sorted(entry.name for entry in tree.iterdir())
        replacement = pathlib.Path(tmp) / "packed.tar.gz"
        with replacement.open("wb") as handle:
            pack = subprocess.Popen(["tar", "-C", str(tree), "-cf", "-", *tops],
                                    stdout=subprocess.PIPE)
            gzip = subprocess.Popen(["gzip", "-1"], stdin=pack.stdout, stdout=handle)
            if pack.stdout:
                pack.stdout.close()
            gzip.wait()
            if pack.wait() != 0 or gzip.returncode != 0:
                raise SystemExit(f"cannot repack {path}")
        replacement.replace(path)
    return removed


def prune_zip(path: pathlib.Path, needle: bytes) -> list[str]:
    with zipfile.ZipFile(path) as src:
        removed = [info.filename for info in src.infolist()
                   if not info.is_dir() and needle in src.read(info.filename)]
    if not removed:
        return []
    # The zip tool deletes members by rewriting only the central directory, so the bootstrap
    # does not have to be decompressed and recompressed member by member.
    if shutil.which("zip"):
        subprocess.run(["zip", "-q", "-d", str(path), *removed], check=True)
        return removed
    with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".pruned", delete=False) as tmp:
        replacement = pathlib.Path(tmp.name)
    try:
        with zipfile.ZipFile(path) as src, zipfile.ZipFile(replacement, "w") as dst:
            for info in src.infolist():
                data = src.read(info.filename)
                if not info.is_dir() and needle in data:
                    continue
                dst.writestr(info, data)
        replacement.replace(path)
    except BaseException:
        replacement.unlink(missing_ok=True)
        raise
    return removed


def remaining(path: pathlib.Path, needle: bytes) -> list[str]:
    if path.is_dir():
        hits = [p.relative_to(path).as_posix() for p in path.rglob("*")
                if p.is_file() and not p.is_symlink() and _matches(p, needle)]
        return hits
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            return [name for name in archive.namelist()
                    if not name.endswith("/") and needle in archive.read(name)]
    with tarfile.open(path, "r:gz") as tar:
        hits = []
        for member in tar:
            if member.isfile():
                handle = tar.extractfile(member)
                if handle and needle in handle.read():
                    hits.append(member.name)
        return hits


def prune(path: pathlib.Path, needle: bytes) -> list[str]:
    if path.is_dir():
        return prune_tree(path, needle)
    if path.suffix == ".zip":
        return prune_zip(path, needle)
    if path.name.endswith(".tar.gz") or path.suffix in (".tgz", ".targz"):
        return prune_tar(path, needle)
    raise SystemExit(f"unsupported artifact: {path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=pathlib.Path)
    parser.add_argument("--needle", default=DEFAULT_NEEDLE)
    parser.add_argument("--check", action="store_true",
                        help="only verify that nothing matches; removes nothing")
    args = parser.parse_args()
    needle = args.needle.encode()

    failed = False
    for path in args.paths:
        if not path.exists():
            print(f"no such path: {path}", file=sys.stderr)
            return 1
        if not args.check:
            removed = prune(path, needle)
            for name in removed:
                print(f"  drop    {path.name}: {name}")
            print(f"  pruned  {path} ({len(removed)} entries)")
        hits = remaining(path, needle)
        if hits:
            failed = True
            print(f"{path} still names {args.needle} in {len(hits)} files:", file=sys.stderr)
            for name in hits[:10]:
                print("  " + name, file=sys.stderr)
    if failed:
        return 1
    print(f"clean: no {args.needle} in any artifact")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
