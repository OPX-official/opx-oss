#!/usr/bin/env python3
"""Writes ``index.json`` (the catalogue the APK build consumes) and the apt ``Packages``
indexes for the OPX package archive.

Run by ``tools/build-packages.sh`` after the artifacts are on disk.
"""

import argparse
import gzip
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import time

DEFAULT_BASE_URL = "https://raw.githubusercontent.com/OPX-official/opx-packages/gh-pages/"


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def deb_control(path: pathlib.Path) -> dict:
    """Control fields of a .deb, so the apt index is real and not guessed from the name."""
    try:
        text = subprocess.check_output(
            ["dpkg-deb", "-f", str(path)], text=True, stderr=subprocess.DEVNULL
        )
    except (OSError, subprocess.CalledProcessError):
        text = ""
    fields = {}
    key = None
    for line in text.splitlines():
        if line.startswith(" ") and key:
            fields[key] += "\n" + line
            continue
        if ":" in line:
            key, _, value = line.partition(":")
            fields[key.strip()] = value.strip()
    return fields


def split_name(path: pathlib.Path) -> tuple[str, str, str]:
    """``<name>_<version>_<arch>.ext`` -> (name, version, arch)."""
    stem = path.name
    for suffix in (".tar.gz", ".deb", ".zip"):
        if stem.endswith(suffix):
            stem = stem[: -len(suffix)]
            break
    parts = stem.split("_")
    if len(parts) < 3:
        return stem, "0", "all"
    return parts[0], parts[1], parts[2]


def collect(out: pathlib.Path, base_url: str) -> list[dict]:
    entries = []
    for path in sorted(out.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(out).as_posix()
        if relative.startswith(("dists/", "index.json")):
            continue
        if path.name.endswith((".sha256", ".tmp")):
            continue
        if relative.startswith("pool/"):
            kind = "deb"
        elif relative.startswith("payload/"):
            kind = "payload"
        elif relative.startswith("base/bootstrap/"):
            kind = "bootstrap"
        elif relative.startswith("base/"):
            kind = "payload"
        else:
            continue
        name, version, arch = split_name(path)
        entry = {
            "name": name,
            "version": version,
            "arch": arch,
            "kind": kind,
            "path": relative,
            "size": path.stat().st_size,
            "sha256": sha256(path),
            "url": base_url + relative,
        }
        if kind == "deb":
            control = deb_control(path)
            entry["name"] = control.get("Package", name)
            entry["version"] = control.get("Version", version)
            entry["arch"] = control.get("Architecture", arch)
            entry["depends"] = control.get("Depends", "")
            entry["description"] = control.get("Description", "")
        entries.append(entry)
    return entries


def write_apt_index(out: pathlib.Path, entries: list[dict]) -> None:
    for arch in sorted({e["arch"] for e in entries if e["kind"] == "deb"} | {"all"}):
        debs = [e for e in entries if e["kind"] == "deb" and e["arch"] in (arch, "all")]
        if not debs:
            continue
        stanzas = []
        for entry in debs:
            path = out / entry["path"]
            control = deb_control(path)
            lines = [
                f"Package: {entry['name']}",
                f"Version: {entry['version']}",
                f"Architecture: {entry['arch']}",
                f"Maintainer: {control.get('Maintainer', 'OPX OSS <opx@opx.dev>')}",
                f"Installed-Size: {control.get('Installed-Size', '0')}",
                "Section: utils",
                "Priority: optional",
            ]
            if entry.get("depends"):
                lines.append(f"Depends: {entry['depends']}")
            lines += [
                f"Filename: {entry['path']}",
                f"Size: {entry['size']}",
                f"SHA256: {entry['sha256']}",
                f"Description: {control.get('Description', entry['name'])}",
            ]
            stanzas.append("\n".join(lines))
        body = "\n\n".join(stanzas) + "\n"
        target = out / "dists/stable/main" / f"binary-{arch}"
        target.mkdir(parents=True, exist_ok=True)
        (target / "Packages").write_text(body, encoding="utf-8")
        with gzip.open(target / "Packages.gz", "wt", encoding="utf-8") as handle:
            handle.write(body)

    release = out / "dists/stable/Release"
    release.parent.mkdir(parents=True, exist_ok=True)
    release.write_text(
        "\n".join(
            [
                "Origin: OPX",
                "Label: OPX OSS",
                "Suite: stable",
                "Codename: stable",
                "Architectures: all aarch64 arm i686 x86_64",
                f"Date: {time.strftime('%a, %d %b %Y %H:%M:%S UTC', time.gmtime())}",
                "Description: OPX OSS package archive",
                "",
            ]
        ),
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    args = parser.parse_args()

    out = args.out.resolve()
    if not out.is_dir():
        print(f"no such directory: {out}", file=sys.stderr)
        return 1

    entries = collect(out, args.base_url)
    write_apt_index(out, entries)

    index = {
        "schema": 1,
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "base_url": args.base_url,
        "packages": entries,
    }
    (out / "index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    kinds = {}
    for entry in entries:
        kinds[entry["kind"]] = kinds.get(entry["kind"], 0) + 1
    print(f"  index.json: {len(entries)} artifacts {kinds}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
