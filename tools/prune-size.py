#!/usr/bin/env python3
"""Drops the parts of a built runtime that only a compiler, a linker or a reader needs.

The base payload and the bootstrap are trees a phone runs: nothing in them is ever compiled
against, linked against or read as documentation. Termux's build keeps the other half of
each package in place anyway — the headers, the static archives, the man pages, the docs and
the python stdlib's own test suite — and in this runtime that is 112 MiB of ``*.a`` (51 MiB
of it ``libpython3.14.a`` under python's ``config-*`` directory), 16 MiB of headers, 13 MiB
of man pages and 18 MiB of docs, none of which the engine or the app can reach.

Usage: prune-size.py PATH [PATH...]    # tree, .zip or .tar.gz, pruned in place
"""

import fnmatch
import pathlib
import shutil
import subprocess
import sys
import tempfile
import zipfile

# Whole directories that only exist for people building against the runtime.
DROP_DIRS = (
    "share/man",
    "share/doc",
    "share/info",
    "share/gtk-doc",
    "share/devhelp",
    "share/aclocal",
    "include",
    "libexec/installed-tests",
    "lib/cmake",
    "lib/pkgconfig",
)
# Link-time artifacts: ``.a`` is the static archive, ``.la`` is libtool's description of it.
DROP_FILE_GLOBS = ("*.a", "*.la")
# Inside lib/python3.*/: the stdlib's own test suite, the build config directory (it carries
# libpython3.14.a), and the modules that need tkinter, which this runtime does not ship.
DROP_PYTHON_GLOBS = ("test", "config-*", "idlelib", "lib2to3", "turtledemo", "ensurepip")

SYMLINK_LIST = "SYMLINKS.txt"


def _is_dropped_dir(name: str) -> bool:
    return any(name == drop or name.startswith(drop + "/") for drop in DROP_DIRS)


def should_drop(name: str) -> bool:
    """True for a path (relative to the prefix) that belongs to the build, not the runtime."""
    name = name.strip("/")
    if not name:
        return False
    if _is_dropped_dir(name):
        return True
    parts = name.split("/")
    if any(fnmatch.fnmatch(parts[-1], pattern) for pattern in DROP_FILE_GLOBS):
        return True
    for index, part in enumerate(parts[:-1]):
        if fnmatch.fnmatch(part, "python3*") and any(
            fnmatch.fnmatch(parts[index + 1], pattern) for pattern in DROP_PYTHON_GLOBS
        ):
            return True
    return False


def prune_tree(root: pathlib.Path) -> int:
    removed = 0
    for path in sorted(root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if path.is_symlink():
            continue
        relative = path.relative_to(root).as_posix()
        if path.is_dir():
            if _is_dropped_dir(relative):
                shutil.rmtree(path, ignore_errors=True)
                removed += 1
            continue
        if path.is_file() and should_drop(relative):
            path.unlink()
            removed += 1
    return removed


def _symlink_entries(archive: zipfile.ZipFile) -> set[str]:
    """Paths the bootstrap's SYMLINKS.txt refers to, in either direction.

    The app creates those symlinks while it extracts the archive, so a pruned file that one
    of them points at would turn a size fix into a failed first run.
    """
    if SYMLINK_LIST not in archive.namelist():
        return set()
    names: set[str] = set()
    for line in archive.read(SYMLINK_LIST).decode("utf-8", "replace").splitlines():
        for part in line.replace("←", "->").split("->"):
            part = part.strip().lstrip("./").strip("/")
            if part:
                names.add(part)
    return names


def prune_zip(path: pathlib.Path) -> int:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        protected = _symlink_entries(archive)
    drop = [name for name in names
            if name.rstrip("/") not in protected and should_drop(name)]
    if not drop:
        return 0
    if shutil.which("zip"):
        # Deleting members only rewrites the central directory, so a 130 MiB archive is not
        # decompressed and recompressed to lose 40 MiB of it. Batch the arguments: a
        # runtime's man pages alone are thousands of names.
        for start in range(0, len(drop), 400):
            subprocess.run(["zip", "-q", "-d", str(path), *drop[start:start + 400]],
                           check=True)
        return len(drop)
    with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".pruned", delete=False) as tmp:
        replacement = pathlib.Path(tmp.name)
    try:
        doomed = {name for name in drop}
        with zipfile.ZipFile(path) as src, zipfile.ZipFile(replacement, "w") as dst:
            for info in src.infolist():
                if info.filename not in doomed:
                    dst.writestr(info, src.read(info.filename))
        replacement.replace(path)
    except BaseException:
        replacement.unlink(missing_ok=True)
        raise
    return len(drop)


def prune_tar(path: pathlib.Path) -> int:
    with tempfile.TemporaryDirectory(dir=path.parent) as tmp:
        tree = pathlib.Path(tmp) / "tree"
        tree.mkdir()
        subprocess.run(["tar", "-C", str(tree), "-xzf", str(path)], check=True)
        removed = prune_tree(tree)
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


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    for argument in sys.argv[1:]:
        path = pathlib.Path(argument)
        if not path.exists():
            print(f"no such path: {path}", file=sys.stderr)
            return 1
        if path.is_dir():
            removed = prune_tree(path)
        elif path.suffix == ".zip":
            removed = prune_zip(path)
        elif path.name.endswith(".tar.gz") or path.suffix in (".tgz", ".targz"):
            removed = prune_tar(path)
        else:
            print(f"unsupported artifact: {path}", file=sys.stderr)
            return 1
        print(f"  prune   {path.name}: {removed} build-only entries dropped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
