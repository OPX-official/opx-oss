#!/usr/bin/env bash
#
# Fetches every binary the APK ships with from the OPX package archive
# (OPX-official/opx-packages, gh-pages branch). Checksums come from the archive's own
# index.json.
#
#   app/src/main/assets/bootstrap/bootstrap-<arch>.zip   Termux bootstrap
#   app/src/main/assets/payload/<arch>/<name>.tar.gz     everything extracted into $PREFIX
#   app/src/main/assets/index.json                       the catalogue that was used
#
# Environment:
#   ARCHES      ABIs to fetch (default: the two phone ABIs; set all four for emulators)
#   PACKAGES_BASE_URL   archive root (default: the gh-pages raw URL)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
# Every phone OPX OSS targets is arm; x86/i686 only matter for emulators, where the adapter path
# cannot work anyway. The archive still carries all four (set ARCHES to add them to the APK).
ARCHES="${ARCHES:-aarch64 arm}"
PACKAGES_BASE_URL="${PACKAGES_BASE_URL:-https://raw.githubusercontent.com/OPX-official/opx-packages/gh-pages}"
INDEX_URL="$PACKAGES_BASE_URL/index.json"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

echo "== index $INDEX_URL"
curl -fsSL --retry 3 -o "$WORK/index.json" "$INDEX_URL"

# Start from an empty tree: the cache restores the previous run's assets, and a leftover file is
# never harmless. A stale .tar.gz is unpacked into a .tar by the APK packager (doubling the APK),
# and a payload for an ABI this build does not ship would ride along unnoticed.
rm -rf "$ASSETS/bootstrap" "$ASSETS/payload" "$ASSETS/index.json"

# artifact_url <kind> <arch>  -> one URL per line
artifact_url() {
  python3 - "$WORK/index.json" "$1" "$2" <<'PY'
import json, sys
index = json.load(open(sys.argv[1], encoding="utf-8"))
kind, arch = sys.argv[2], sys.argv[3]
for entry in index.get("packages", []):
    if entry.get("kind") != kind:
        continue
    # Exact match: architecture-independent packages are fetched once, by the "all" pass.
    if entry.get("arch") != arch:
        continue
    print(entry["url"], entry["sha256"], entry["path"])
PY
}

# AAPT rewrites an asset whose name ends in .gz ("x.tar.gz" lands in the APK as "x.tar"),
# so the packaged payloads use a suffix it leaves alone.
asset_name() {
  local name
  name="$(basename "$1")"
  case "$name" in
    *.tar.gz) printf '%s.targz\n' "${name%.tar.gz}" ;;
    *) printf '%s\n' "$name" ;;
  esac
}

# The APK may only ship a runtime compiled for this app's prefix. An archive built before the
# native switch still carries the other app's paths; fail here instead of shipping it.
assert_native() { # final-name file-to-scan
  local name="$1" scan="$2"
  local found=0
  case "$name" in
    *.targz|*.tar.gz)
      # Names AND file contents: a compiled binary only carries the prefix inside itself.
      # "|| true" keeps a SIGPIPE from tar (grep -q stops early) out of the pipefail status.
      if { tar -tzf "$scan" 2>/dev/null || true; tar -xzOf "$scan" 2>/dev/null || true; } \
          | grep -aq "com\.termux"; then found=1; fi
      ;;
    *.zip)
      if { unzip -Z1 "$scan" 2>/dev/null || true; unzip -p "$scan" 2>/dev/null || true; } \
          | grep -aq "com\.termux"; then found=1; fi
      ;;
  esac
  if [ "$found" = "1" ]; then
    echo "foreign prefix in $name: the archive predates the native runtime; run the Packages workflow" >&2
    exit 1
  fi
}

fetch_verified() { # url sha256 destination
  local url="$1" expected="$2" destination="$3"
  mkdir -p "$(dirname "$destination")"
  if [ -f "$destination" ] && [ "$(sha256 "$destination")" = "$expected" ]; then
    echo "  cached  ${destination#"$ASSETS"/}"
    return
  fi
  curl -fsSL --retry 3 -o "$destination.tmp" "$url"
  local actual
  actual="$(sha256 "$destination.tmp")"
  if [ "$actual" != "$expected" ]; then
    echo "checksum mismatch for $url: expected $expected, got $actual" >&2
    exit 1
  fi
  assert_native "$destination" "$destination.tmp"
  mv "$destination.tmp" "$destination"
  echo "  get     ${destination#"$ASSETS"/}"
}

for arch in $ARCHES; do
  echo "== $arch bootstrap"
  while read -r url checksum path; do
    [ -z "${url:-}" ] && continue
    fetch_verified "$url" "$checksum" "$ASSETS/bootstrap/$(basename "$path")"
  done < <(artifact_url bootstrap "$arch")

  echo "== $arch payloads"
  while read -r url checksum path; do
    [ -z "${url:-}" ] && continue
    fetch_verified "$url" "$checksum" "$ASSETS/payload/$arch/$(asset_name "$path")"
  done < <(artifact_url payload "$arch")
done

echo "== $arch-independent payloads"
while read -r url checksum path; do
  [ -z "${url:-}" ] && continue
  fetch_verified "$url" "$checksum" "$ASSETS/payload/all/$(asset_name "$path")"
done < <(artifact_url payload all)

cp "$WORK/index.json" "$ASSETS/index.json"

echo
echo "Runtime assets ready:"
du -sh "$ASSETS"/bootstrap "$ASSETS"/payload "$ASSETS"/index.json 2>/dev/null || true
