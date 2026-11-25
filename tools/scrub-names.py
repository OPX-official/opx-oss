#!/usr/bin/env python3
"""Removes the upstream build system's name from a built tree, archive or payload.

The base runtime is compiled by a forked termux-packages tree, and the packages it builds
carry that project's name in their own file names, their bodies and their dpkg metadata:
``bin/termux-open-url``, ``lib/libtermux-exec-ld-preload.so``, ``share/termux-keyring/``,
``var/lib/dpkg/info/termux-tools.list``, ``/home/builder/.termux-build/...`` debug paths.
``prune-foreign.py`` only removes the ``com.termux`` package string (the prefix this app
must never talk to); the bare word survives and this app must not ship it.

The rewrite is a same-length, case-preserving text replacement:

    termux -> opxoss      TERMUX -> OPXOSS      Termux -> Opxoss

Every occurrence is replaced, in file names and in file bytes alike, which is what keeps the
result working: a script that calls ``termux-open-url`` becomes a script that calls
``opxoss-open-url``, and the file it calls is renamed in the same pass. The length never
changes, so ELF offsets, string tables, ``.pyc`` headers and dpkg's path lists stay valid.
dpkg's own metadata is rewritten with the rest, so ``.list``, ``.md5sums``, ``.conffiles``
and ``status`` still describe the files that are actually in the artifact.

The one name the app also has to know about is the USB handshake variable compiled into
libusb (``TERMUX_USB_FD`` -> ``OPXOSS_USB_FD``); ``OpxRuntime.ensureUsbFdVariable`` patches
that string at runtime and is kept in step with this script.

GPG keyrings are deleted instead of rewritten: their bodies hold signed user IDs, so a byte
change would invalidate every key. They belong to the other project's archive and our apt
sources are pinned with ``trusted=yes``, so nothing here needs them; their entries leave the
owning package's ``.list``/``.md5sums`` with them.

Usage:
  scrub-names.py PATH...                    # trees, .zip and .tar.gz, in place
  scrub-names.py --check PATH...            # verify only; fails when a trace is left
  scrub-names.py --from termux --to opxoss  # the defaults
"""

import argparse
import gzip
import hashlib
import io
import pathlib
import shutil
import sys
import tarfile
import tempfile
import zipfile

SOURCE = "termux"
TARGET = "opxoss"
# dpkg records absolute paths under the app prefix; archive members are prefix-relative.
PREFIX = "data/data/com.opx.oss/files/usr"
DPKG_INFO = "var/lib/dpkg/info/"
DPKG_STATUS = "var/lib/dpkg/status"


def translate(text: str, source: str, target: str) -> str:
    """Case-preserving, same-length rename of every occurrence in one string."""
    needle = source.lower()
    out = []
    index = 0
    while index <= len(text) - len(needle):
        chunk = text[index:index + len(needle)]
        if chunk.lower() == needle:
            out.append("".join(
                target[offset].upper() if character.isupper() else target[offset]
                for offset, character in enumerate(chunk)
            ))
            index += len(needle)
        else:
            out.append(text[index])
            index += 1
    out.append(text[index:])
    return "".join(out)


def translate_bytes(data: bytes, source: str, target: str) -> tuple[bytes, int]:
    """Same-length byte rewrite, so no offset and no file size moves."""
    needle = source.encode()
    replacement = target.encode()
    lowered = needle.lower()
    out = bytearray()
    count = 0
    index = 0
    while index <= len(data) - len(needle):
        window = data[index:index + len(needle)]
        if window.lower() == lowered:
            for offset, byte in enumerate(window):
                chosen = bytes([replacement[offset]])
                if 97 <= byte <= 122:
                    out += chosen.lower()
                elif 65 <= byte <= 90:
                    out += chosen.upper()
                else:
                    out += chosen
            index += len(needle)
            count += 1
        else:
            out.append(data[index])
            index += 1
    out += data[index:]
    return bytes(out), count


def relative(path_field: str) -> str:
    """``/data/data/.../usr/bin/x`` or ``data/.../usr/bin/x`` -> ``bin/x``."""
    field = path_field.strip()
    marker = PREFIX + "/"
    if marker in field:
        return field.split(marker, 1)[1]
    return field.lstrip("/").removeprefix("./")


def _md5(data: bytes) -> str:
    return hashlib.md5(data, usedforsecurity=False).hexdigest()


def _is_keyring(name: str, data: bytes, source: str) -> bool:
    """A signed keyring that names the other project.

    OpenPGP packets carry the user IDs and are covered by self-signatures, so rewriting a
    byte inside one invalidates the key. These keys trust the other project's archive; this
    app pins its own source with trusted=yes, so the keyring is dropped, not edited.
    """
    return name.endswith(".gpg") and source in data.decode("latin-1").lower()


class Rewrite:
    """The result of one artifact's pass: what moved, what left, what changed."""

    def __init__(self, source: str, target: str) -> None:
        self.source = source
        self.target = target
        self.renamed: dict[str, str] = {}
        self.deleted: set[str] = set()
        self.changed: dict[str, str] = {}  # new relative path -> new md5

    def name(self, name: str) -> str:
        return translate(name, self.source, self.target) if self.source in name.lower() else name

    def dpkg_info(self, name: str, text: str) -> str:
        """One ``.list``/``.md5sums``/``.conffiles`` file, told the truth about the pass."""
        lines = []
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped:
                lines.append(line)
                continue
            if name.endswith(".md5sums") and "  " in line:
                digest, _, path_field = line.partition("  ")
            elif stripped.startswith("/") or stripped.startswith("data/"):
                digest, path_field = None, stripped
            else:
                lines.append(translate(line, self.source, self.target))
                continue
            was = relative(path_field)
            if was in self.deleted:
                continue
            now = self.renamed.get(was, was)
            if now != was:
                path_field = path_field.replace(was, now)
            if digest is not None and now in self.changed:
                digest = self.changed[now]
            lines.append(f"{digest}  {path_field}" if digest is not None else path_field)
        return "\n".join(lines) + ("\n" if text.endswith("\n") else "")

    def dpkg_status(self, text: str) -> str:
        lines = []
        for line in text.splitlines():
            if line.startswith(" ") and "/" in line:
                parts = line.split()
                if len(parts) == 2:
                    path_field, digest = parts
                    was = relative(path_field)
                    if was in self.deleted:
                        continue
                    now = self.renamed.get(was, was)
                    if now != was:
                        path_field = path_field.replace(was, now)
                    if now in self.changed:
                        digest = self.changed[now]
                    lines.append(f" {path_field} {digest}")
                    continue
            lines.append(translate(line, self.source, self.target))
        return "\n".join(lines) + ("\n" if text.endswith("\n") else "")


def scrub_tree(root: pathlib.Path, rewrite: Rewrite) -> int:
    touched = 0
    for path in sorted(root.rglob("*"), key=lambda item: len(item.parts), reverse=True):
        name = path.relative_to(root).as_posix()
        if path.is_symlink():
            target = path.readlink()
            new_name = rewrite.name(name)
            new_target = pathlib.Path(translate(str(target), rewrite.source, rewrite.target))
            if new_name != name or new_target != target:
                destination = root / new_name
                if destination.exists():
                    destination.unlink()
                path.unlink()
                destination.symlink_to(new_target)
                rewrite.renamed[name] = new_name
                touched += 1
            continue
        if path.is_dir():
            new_name = rewrite.name(name)
            if new_name != name:
                path.rename(root / new_name)
            continue
        data = path.read_bytes()
        if _is_keyring(path.name, data, rewrite.source):
            path.unlink()
            # dpkg's lists name the path this artifact ships, i.e. after the rename.
            rewrite.deleted.add(rewrite.name(name))
            touched += 1
            continue
        body, count = translate_bytes(data, rewrite.source, rewrite.target)
        new_name = rewrite.name(name)
        destination = root / new_name
        if count or new_name != name:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(body)
            if destination != path:
                path.unlink()
            rewrite.renamed[name] = new_name
            if count:
                rewrite.changed[new_name] = _md5(body)
            touched += 1
    info = root / DPKG_INFO
    if info.is_dir():
        for path in sorted(info.iterdir()):
            name = path.name
            text = path.read_text(encoding="utf-8", errors="surrogateescape")
            if name.endswith((".list", ".md5sums", ".conffiles")):
                text = rewrite.dpkg_info(name, text)
            else:
                text = translate(text, rewrite.source, rewrite.target)
            path.write_text(text, encoding="utf-8", errors="surrogateescape")
    status = root / DPKG_STATUS
    if status.is_file():
        status.write_text(rewrite.dpkg_status(status.read_text(encoding="utf-8",
                                                              errors="surrogateescape")),
                          encoding="utf-8", errors="surrogateescape")
    return touched


SYMLINK_LIST = "SYMLINKS.txt"


def _prune_symlinks(text: str, deleted: set[str]) -> str:
    """Drop the links whose target this pass removed, so extraction cannot leave a dangling one."""
    lines = []
    for line in text.splitlines():
        target = line.split("←", 1)[0].strip()
        if target and relative(target) in deleted:
            continue
        lines.append(line)
    return "\n".join(lines) + ("\n" if text.endswith("\n") else "")


def _scrub_members(members: list[tuple[zipfile.ZipInfo, bytes]],
                   rewrite: Rewrite) -> list[tuple[zipfile.ZipInfo, bytes]]:
    """The zip/in-memory form of scrub_tree: same rules, same metadata upkeep."""
    out: list[tuple[zipfile.ZipInfo, bytes]] = []
    for info, data in members:
        name = info.filename
        if name.endswith("/"):
            info.filename = rewrite.name(name)
            out.append((info, data))
            continue
        if _is_keyring(name, data, rewrite.source):
            rewrite.deleted.add(rewrite.name(name))
            continue
        body, count = translate_bytes(data, rewrite.source, rewrite.target)
        new_name = rewrite.name(name)
        if count or new_name != name:
            rewrite.renamed[name] = new_name
            if count:
                rewrite.changed[new_name] = _md5(body)
        info.filename = new_name
        out.append((info, body))

    for index, (info, data) in enumerate(out):
        name = info.filename
        body = None
        if name == DPKG_STATUS:
            body = rewrite.dpkg_status(data.decode("utf-8", errors="surrogateescape"))
        elif name == SYMLINK_LIST:
            body = _prune_symlinks(data.decode("utf-8", errors="surrogateescape"),
                                   rewrite.deleted)
        elif name.startswith(DPKG_INFO) and "/" not in name[len(DPKG_INFO):]:
            text = data.decode("utf-8", errors="surrogateescape")
            if name.endswith((".list", ".md5sums", ".conffiles")):
                body = rewrite.dpkg_info(name, text)
            else:
                body = translate(text, rewrite.source, rewrite.target)
        if body is not None:
            out[index] = (info, body.encode("utf-8", errors="surrogateescape"))
    return out


def scrub(path: pathlib.Path, source: str, target: str) -> int:
    rewrite = Rewrite(source, target)
    if path.is_dir():
        return scrub_tree(path, rewrite)
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            members = [(info, archive.read(info.filename)) for info in archive.infolist()]
        members = _scrub_members(members, rewrite)
        temporary = path.with_name(path.name + ".scrub")
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as archive:
            for info, data in members:
                archive.writestr(info, data)
        temporary.replace(path)
        return len(rewrite.renamed) + len(rewrite.deleted)
    if path.name.endswith((".tar.gz", ".tgz")):
        with tempfile.TemporaryDirectory(dir=path.parent) as tmp:
            tree = pathlib.Path(tmp) / "tree"
            tree.mkdir()
            with tarfile.open(path, "r:gz") as archive:
                if hasattr(tarfile, "data_filter"):
                    archive.extractall(tree, filter="tar")
                else:
                    archive.extractall(tree)
            count = scrub_tree(tree, rewrite)
            temporary = pathlib.Path(tmp) / "packed.tar.gz"
            with temporary.open("wb") as handle:
                with gzip.GzipFile(fileobj=handle, mode="wb", mtime=0, compresslevel=6) as gz:
                    with tarfile.open(fileobj=gz, mode="w") as archive:
                        for top in sorted(entry.name for entry in tree.iterdir()):
                            archive.add(tree / top, arcname=top)
            shutil.move(str(temporary), path)
            return count
    raise SystemExit(f"unsupported artifact: {path}")


def traces(path: pathlib.Path, source: str) -> list[str]:
    needle = source.encode()
    hits: list[str] = []

    def check(name: str, data: bytes) -> None:
        if source in name.lower():
            hits.append("name: " + name)
        if needle in data.lower():
            hits.append("body: " + name)

    if path.is_dir():
        for item in path.rglob("*"):
            name = item.relative_to(path).as_posix()
            if source in name.lower():
                hits.append("name: " + name)
            if item.is_file() and not item.is_symlink() and needle in item.read_bytes().lower():
                hits.append("body: " + name)
        return hits
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                check(name, b"" if name.endswith("/") else archive.read(name))
        return hits
    with tarfile.open(path, "r:gz") as archive:
        for member in archive:
            handle = archive.extractfile(member) if member.isfile() else None
            check(member.name, handle.read() if handle else b"")
    return hits


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=pathlib.Path)
    parser.add_argument("--from", dest="source", default=SOURCE)
    parser.add_argument("--to", dest="target", default=TARGET)
    parser.add_argument("--check", action="store_true",
                        help="only verify that nothing is left; changes nothing")
    args = parser.parse_args()
    if len(args.source) != len(args.target):
        print("scrub-names: --from and --to must be the same length", file=sys.stderr)
        return 2

    failed = False
    for path in args.paths:
        if not path.exists():
            print(f"no such path: {path}", file=sys.stderr)
            return 1
        if not args.check:
            print(f"  scrubbed {path.name} ({scrub(path, args.source, args.target)} entries)")
        hits = traces(path, args.source)
        if hits:
            failed = True
            print(f"{path}: {len(hits)} traces of '{args.source}' left", file=sys.stderr)
            for hit in hits[:10]:
                print("  " + hit, file=sys.stderr)
    if failed:
        return 1
    print(f"clean: no '{args.source}' in any artifact")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
