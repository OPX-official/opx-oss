#!/usr/bin/env python3
"""Merges a freshly built catalogue into the one that is already published.

Run by ``.github/workflows/packages.yml`` in its publish step, after the ``gh-pages`` branch
has been cloned.

Why this exists: an engine or shim fix changes only ``opx-runtime`` / ``opx-wifi4`` /
``opx-usb``, which take seconds, while the base runtime (bash, coreutils, python, libusb)
costs about two hours to recompile. A build with ``scope=extras`` therefore skips the base,
and this script puts its catalogue back together with the artifacts the archive already has.
Without it the APK build would see an index with no base payload and fail.

``scope=all`` replaces the catalogue with the one this run built, so an old version that the
archive still held does not linger. ``scope=extras`` keeps every published artifact this run
did not rebuild, and refuses to run against an archive that has no base runtime yet.

``--prune-page`` is the second half: it deletes files on the ``gh-pages`` checkout that the
merged catalogue no longer lists (an artifact an earlier build replaced), so the branch does
not grow with every version bump.
"""

import argparse
import importlib.util
import json
import pathlib
import sys
import time
import urllib.request


def _load_make_index():
    """The sibling ``make-index.py``; its name has a hyphen, so it is loaded by path."""
    path = pathlib.Path(__file__).resolve().parent / "make-index.py"
    spec = importlib.util.spec_from_file_location("opx_make_index", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


make_index = _load_make_index()

BASE_KINDS = ("bootstrap",)


def load_index(source: str) -> dict:
    """A published catalogue, from a path on disk or from its raw URL."""
    if source.startswith(("http://", "https://")):
        with urllib.request.urlopen(source, timeout=60) as response:  # noqa: S310
            return json.load(response)
    path = pathlib.Path(source)
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def identity(entry: dict) -> tuple:
    """What a package *is*, without the version that sits in its file name."""
    return (entry.get("kind"), entry.get("name"), entry.get("arch"))


def carries_base(index: dict) -> bool:
    for entry in index.get("packages", []):
        if entry.get("kind") in BASE_KINDS:
            return True
        if entry.get("kind") == "payload" and "opx-base" in entry.get("path", ""):
            return True
    return False


def merge(fresh: dict, published: dict, scope: str) -> list[dict]:
    fresh_entries = fresh.get("packages", [])
    if scope == "all":
        return sorted(fresh_entries, key=lambda entry: entry["path"])

    if not published or not published.get("packages"):
        raise SystemExit(
            "scope=extras needs a published catalogue to merge into, and the archive has "
            "none: run a full build (scope=all) first")
    if not carries_base(published):
        raise SystemExit(
            "the published catalogue carries no base runtime, so an extras build would "
            "publish an index the APK cannot install from: run a full build (scope=all) first")

    # A rebuilt package replaces the one it supersedes by name, arch and kind — not only by
    # file name, because the version is in the file name. Otherwise yesterday's
    # opx-runtime_2026.11.23_all.tar.gz would stay in the catalogue next to today's build.
    rebuilt = {identity(entry) for entry in fresh_entries}
    replaced = [entry for entry in published["packages"] if identity(entry) in rebuilt]
    merged = {entry["path"]: entry for entry in published["packages"]
              if identity(entry) not in rebuilt}
    for entry in fresh_entries:
        merged[entry["path"]] = entry
    print(f"  merged: {len(fresh_entries)} rebuilt, {len(merged) - len(fresh_entries)} kept "
          f"from the archive, {len(replaced)} superseded")
    return sorted(merged.values(), key=lambda entry: entry["path"])


def refresh_from_files(index: dict, out: pathlib.Path) -> int:
    """Re-reads the SHA-256 and size of every fresh entry from the artifact on disk.

    The catalogue is the APK build's integrity gate, so its hashes must describe the bytes
    that are about to be uploaded. A freshly built ``out/index.json`` can still carry another
    run's hashes for the same file names (both runs use the date as the version); trusting it
    would publish a catalogue whose checksums no download can match, and the APK build would
    refuse a perfectly good runtime.
    """
    changed = 0
    for entry in index.get("packages", []):
        path = out / entry.get("path", "")
        if not path.is_file():
            continue
        digest = make_index.sha256(path)
        size = path.stat().st_size
        if entry.get("sha256") != digest or entry.get("size") != size:
            entry["sha256"] = digest
            entry["size"] = size
            changed += 1
    if changed:
        index["generated"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    print(f"  refreshed {changed} checksum(s) from the built artifacts")
    return changed


def write_index(out: pathlib.Path, index: dict, entries: list[dict]) -> None:
    index = dict(index)
    index["packages"] = entries
    (out / "index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    # The apt indexes are rebuilt from the merged entries alone, so a base .deb that is not
    # on this runner still gets its Packages stanza.
    make_index.write_apt_index(out, entries)
    kinds = {}
    for entry in entries:
        kinds[entry["kind"]] = kinds.get(entry["kind"], 0) + 1
    print(f"  index.json: {len(entries)} artifacts {kinds}")


def seal_page(page: pathlib.Path) -> int:
    """Makes the catalogue on the checkout describe the files that are about to be committed.

    The publish step writes ``index.json`` into the checkout, then commits the whole tree. If
    anything between the build and the checkout leaves the catalogue describing another run's
    bytes, the branch would advertise checksums no download can match. Sealing reads the files
    in the checkout itself, so the committed catalogue and the committed artifacts agree by
    construction. Entries stored as release assets are left alone; they are not in the tree.
    """
    path = page / "index.json"
    if not path.is_file():
        print(f"no catalogue at {path}", file=sys.stderr)
        return 1
    index = json.loads(path.read_text(encoding="utf-8"))
    changed = 0
    missing = []
    for entry in index.get("packages", []):
        target = page / entry.get("path", "")
        if not target.is_file():
            if entry.get("storage") != "release":
                missing.append(entry.get("path", "?"))
            continue
        digest = make_index.sha256(target)
        size = target.stat().st_size
        if entry.get("sha256") != digest or entry.get("size") != size:
            entry["sha256"] = digest
            entry["size"] = size
            changed += 1
    if missing:
        print("catalogue lists branch files that are not in the checkout: "
              + ", ".join(missing), file=sys.stderr)
        return 1
    index["generated"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    path.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    make_index.write_apt_index(page, index["packages"])
    print(f"  sealed {changed} checksum(s) against the checkout")
    return 0


def prune_page(page: pathlib.Path, index: dict) -> None:
    """Deletes files on the checkout that the catalogue no longer lists."""
    keep = {entry["path"] for entry in index.get("packages", [])}
    removed = 0
    for path in sorted(page.rglob("*")):
        if not path.is_file() or path.is_symlink():
            continue
        relative = path.relative_to(page).as_posix()
        # The apt tree is generated beside the index, and README.md is the branch's front page.
        if relative.startswith((".git/", "dists/")) or relative in ("index.json", "README.md"):
            continue
        if relative not in keep:
            path.unlink()
            removed += 1
    print(f"  pruned {removed} file(s) the catalogue no longer lists")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=pathlib.Path)
    parser.add_argument("--published", help="path or URL of the catalogue already published")
    parser.add_argument("--scope", choices=("all", "extras"), default="all")
    parser.add_argument("--prune-page", type=pathlib.Path,
                        help="prune this gh-pages checkout against --out/index.json")
    parser.add_argument("--seal-page", type=pathlib.Path,
                        help="reseal this gh-pages checkout's catalogue from its own files")
    args = parser.parse_args()

    if args.seal_page is not None:
        return seal_page(args.seal_page.resolve())
    if args.out is None:
        parser.error("--out is required unless --seal-page is given")

    out = args.out.resolve()
    index_path = out / "index.json"
    if not index_path.is_file():
        print(f"no catalogue at {index_path}", file=sys.stderr)
        return 1
    fresh = json.loads(index_path.read_text(encoding="utf-8"))

    if args.prune_page is not None:
        prune_page(args.prune_page.resolve(), fresh)
        return 0

    refresh_from_files(fresh, out)
    published = load_index(args.published) if args.published else {}
    try:
        entries = merge(fresh, published, args.scope)
    except SystemExit as exc:
        print(str(exc), file=sys.stderr)
        return 1
    write_index(out, fresh, entries)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
