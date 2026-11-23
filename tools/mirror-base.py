#!/usr/bin/env python3
"""Mirrors the Termux base runtime that OPX OSS cannot compile itself.

The app ships its own userland, but a libc-level Python toolchain (python, openssl,
ncurses, libffi, ...) is built by the Termux project's cross-compilation setup. Instead of
re-downloading it in every APK build, this script resolves the dependency closure once,
records every checksum in ``base/base-lock.json`` and publishes a single merged payload
tree per ABI, so the APK build has exactly one pinned source of truth.

Payload layout matches a .deb's file tree, so the installer can untar it straight into the
prefix on device: ``data/data/com.termux/files/usr/...``.
"""

import argparse
import gzip
import hashlib
import io
import json
import lzma
import os
import pathlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request

ROOTS = ["python", "libusb", "proot", "libtalloc", "libandroid-shmem"]
PREFIX_REL = "data/data/com.termux/files/usr"


def fetch(url: str) -> bytes:
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                return response.read()
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2)
    raise RuntimeError(url)


def parse_packages(text: str) -> dict[str, dict]:
    packages = {}
    for stanza in text.split("\n\n"):
        fields = {}
        key = None
        for line in stanza.splitlines():
            if line.startswith(" ") and key:
                fields[key] += "\n" + line.strip()
                continue
            if ":" in line:
                key, _, value = line.partition(":")
                fields[key.strip()] = value.strip()
        if fields.get("Package"):
            packages[fields["Package"]] = fields
    return packages


def dependency_names(value: str) -> list[str]:
    names = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        # "libandroid-support (>= 28), ncurses" -> "libandroid-support", "ncurses";
        # alternatives ("a | b") resolve to the first branch.
        name = part.split("|")[0].strip().split("(")[0].strip().split(":")[0].strip()
        if name:
            names.append(name)
    return names


def resolve(roots: list[str], packages: dict[str, dict]) -> list[str]:
    ordered, seen, queue = [], set(), list(roots)
    while queue:
        name = queue.pop(0)
        if name in seen or name not in packages:
            continue
        seen.add(name)
        ordered.append(name)
        queue.extend(dependency_names(packages[name].get("Depends", "")))
    return ordered


def extract_deb(deb: pathlib.Path, destination: pathlib.Path) -> None:
    """Unpacks a Termux .deb into ``destination`` using dpkg when present."""
    if shutil.which("dpkg-deb"):
        subprocess.run(
            ["dpkg-deb", "-x", str(deb), str(destination)],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        return
    with tarfile.open(deb) as outer:
        for member in outer.getmembers():
            if member.name.startswith("data.tar"):
                payload = outer.extractfile(member)
                if payload is None:
                    raise RuntimeError(f"no data.tar in {deb}")
                with tarfile.open(fileobj=io.BytesIO(payload.read())) as inner:
                    inner.extractall(destination, filter="data")
                return
    raise RuntimeError(f"cannot unpack {deb}")


def mirror_arch(repo: str, arch: str, out: pathlib.Path, version: str, lock: list[dict]) -> None:
    index_url = f"{repo}/dists/stable/main/binary-{arch}/Packages"
    print(f"  {arch}: index {index_url}")
    packages = parse_packages(fetch(index_url).decode("utf-8", "replace"))
    wanted = resolve(ROOTS, packages)
    missing = [name for name in ROOTS if name not in packages]
    if missing:
        print(f"  {arch}: missing from the Termux index: {missing}", file=sys.stderr)

    payload_dir = out / "payload" / arch
    payload_dir.mkdir(parents=True, exist_ok=True)
    payload_file = payload_dir / f"opx-base_{version}_{arch}.tar.gz"

    work = pathlib.Path(tempfile.mkdtemp(prefix=f"opx-base-{arch}-"))
    tree = work / "tree"
    tree.mkdir()
    try:
        total = 0
        for name in wanted:
            entry = packages[name]
            filename = entry.get("Filename")
            checksum = entry.get("SHA256")
            if not filename or not checksum:
                continue
            deb = work / os.path.basename(filename)
            data = fetch(f"{repo}/{filename}")
            actual = hashlib.sha256(data).hexdigest()
            if actual != checksum:
                raise SystemExit(f"checksum mismatch for {name}: {actual} != {checksum}")
            deb.write_bytes(data)
            extract_deb(deb, tree)
            total += len(data)
            lock.append(
                {
                    "name": name,
                    "version": entry.get("Version", ""),
                    "arch": arch,
                    "filename": os.path.basename(filename),
                    "sha256": checksum,
                    "size": len(data),
                }
            )
            deb.unlink()

        prefix = tree / PREFIX_REL
        if not prefix.is_dir():
            raise SystemExit(f"{arch}: packages did not unpack into {PREFIX_REL}")
        with tarfile.open(payload_file, "w:gz") as archive:
            archive.add(tree / "data", arcname="data")
        print(
            f"  {arch}: {len(wanted)} packages, {total // 1024} KiB debs -> "
            f"payload/{arch}/{payload_file.name} ({payload_file.stat().st_size // 1024} KiB)"
        )
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default="https://packages.termux.dev/apt/termux-main")
    parser.add_argument("--arch", default="aarch64,arm,i686,x86_64")
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    lock: list[dict] = []
    for arch in [a for a in args.arch.split(",") if a]:
        mirror_arch(args.repo, arch, out, args.version, lock)

    target = out / "base" / "base-lock.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(
            {
                "source": args.repo,
                "mirrored": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "roots": ROOTS,
                "packages": lock,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"  base-lock.json: {len(lock)} packages")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
