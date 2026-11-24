#!/usr/bin/env bash
#
# Fetches every binary the APK ships with from the OPX package archive
# (OPX-official/opx-packages: the gh-pages tree plus the `packages` release, which holds the
# artifacts too large for a git branch). Checksums come from the archive's own index.json, and
# every URL is taken from that index rather than guessed.
#
#   app/src/main/assets/bootstrap/bootstrap-<arch>.zip   runtime bootstrap
#   app/src/main/assets/payload/<arch>/<name>.tar.gz     everything extracted into $PREFIX
#   app/src/main/assets/index.json                       the catalogue that was used
#
# Environment:
#   ARCHES      ABIs to fetch (default: the two phone ABIs; set all four for emulators)
#   PACKAGES_BASE_URL   archive root holding index.json (default: the gh-pages raw URL)
#   PACKAGES_LOCAL_DIR  local directory containing pre-built packages (optional fallback)
#
# If PACKAGES_LOCAL_DIR is set and the archive fetch fails, this script will
# use the local directory instead, allowing builds to proceed without the
# PACKAGES_TOKEN secret.
#
# Usage:
#   PACKAGES_LOCAL_DIR=/path/to/local/packages ./tools/fetch-packages.sh
#   # or set PACKAGES_BASE_URL to point at a different archive location
#

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"

# Every phone OPX OSS targets is arm; x86/i686 only matter for emulators, where the adapter path
# cannot work anyway. The archive still carries all four (set ARCHES to add them to the APK).
ARCHES="${ARCHES:-aarch64 arm}"
DEFAULT_BASE_URL="https://raw.githubusercontent.com/OPX-official/opx-packages/gh-pages"
PACKAGES_BASE_URL="${PACKAGES_BASE_URL:-$DEFAULT_BASE_URL}"
PACKAGES_REPO="${PACKAGES_REPO:-OPX-official/opx-packages}"
PACKAGES_API_INDEX="https://api.github.com/repos/$PACKAGES_REPO/contents/index.json?ref=gh-pages"
PACKAGES_LOCAL_DIR="${PACKAGES_LOCAL_DIR:-}"
INDEX_URL="$PACKAGES_BASE_URL/index.json"
CACHE_BUST="${PACKAGES_CACHE_BUST:-$(date +%s)}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

cache_bust() { # url -> url with a per-run query, so a cached older body is not reused
  case "$1" in
    *\?*) printf '%s&cb=%s\n' "$1" "$CACHE_BUST" ;;
    *) printf '%s?cb=%s\n' "$1" "$CACHE_BUST" ;;
  esac
}

fetch_index() {
  local destination="$1"
  # raw.githubusercontent.com keeps a branch path cached even when the query changes, so a
  # freshly published index stays invisible for a while. The contents API is not cached that
  # way; the raw URL stays as the fallback for callers without a token.
  if [ -n "${GITHUB_TOKEN:-}" ] && [ "$PACKAGES_BASE_URL" = "$DEFAULT_BASE_URL" ]; then
    curl -fsSL --retry 3 \
      -H "Authorization: Bearer $GITHUB_TOKEN" \
      -H "Accept: application/vnd.github.raw" \
      -o "$destination" "$PACKAGES_API_INDEX" && return 0
  fi
  curl -fsSL --retry 3 -o "$destination" "$(cache_bust "$INDEX_URL")"
}

echo "== index $INDEX_URL"

# If a local dir is set, prefer it; otherwise fetch from the archive.
if [ -n "$PACKAGES_LOCAL_DIR" ]; then
  echo "== using local packages from $PACKAGES_LOCAL_DIR"
  cp -r "$PACKAGES_LOCAL_DIR/." "$ASSETS/" 2>/dev/null || true
  # If the local dir has an index.json, use it; otherwise try the archive.
  if [ -f "$ASSETS/index.json" ]; then
    echo "  using local index.json"
  else
    echo "== archive index not found locally, attempting archive fetch..."
    PACKAGES_LOCAL_DIR=""
  fi
fi

if [ -z "$PACKAGES_LOCAL_DIR" ] || [ ! -f "$ASSETS/index.json" ]; then
  fetch_index "$WORK/index.json"
  if [ ! -f "$WORK/index.json" ]; then
    echo "FATAL: could not fetch index.json from archive or local dir" >&2
    exit 1
  fi
  cp "$WORK/index.json" "$ASSETS/index.json"
fi

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
# native switch still carries the other app's paths; one built for no app would run on whatever
# prefix it was compiled with. Fail here instead of shipping either.
assert_native() { # final-name file-to-scan
  local name="$1" scan="$2"
  local found=0 require_ours=0
  # Only the base runtime and the bootstrap carry compiled binaries; the OPX packages are
  # scripts installed under the prefix and are checked by path instead.
  case "$name" in
    bootstrap-*.zip|opx-base_*) require_ours=1 ;;
  esac
  case "$name" in
    *.targz|*.tar.gz)
      # Names AND file contents: a compiled binary only carries the prefix inside itself.
      # "|| true" keeps a SIGPIPE from tar (grep -q stops early) out of the pipefail status.
      if { tar -tzf "$scan" 2>/dev/null || true; tar -xzOf "$scan" 2>/dev/null || true; } \
          | grep -aq "com\.termux"; then found=1; fi
      if [ "$require_ours" = "1" ] && [ "$found" = "0" ] \
          && ! { tar -xzOf "$scan" 2>/dev/null || true; } | grep -aq "com\.opx\.oss"; then
        found=2
      fi
      ;;
    *.zip)
      if { unzip -Z1 "$scan" 2>/dev/null || true; unzip -p "$scan" 2>/dev/null || true; } \
          | grep -aq "com\.termux"; then found=1; fi
      if [ "$require_ours" = "1" ] && [ "$found" = "0" ] \
          && ! { unzip -p "$scan" 2>/dev/null || true; } | grep -aq "com\.opx\.oss"; then
        found=2
      fi
      ;;
  esac
  if [ "$found" = "1" ]; then
    echo "foreign prefix in $name: the archive predates the native runtime; run the Packages workflow" >&2
    exit 1
  fi
  if [ "$found" = "2" ]; then
    echo "$name carries no com.opx.oss prefix: it was not built for this app; run the Packages workflow" >&2
    exit 1
  fi
}

fetch_verified() { # url sha256 destination
  local url="$(cache_bust "$1")" expected="$2" destination="$3"
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

# Every ABI the APK advertises has to be complete, or the failure only shows up on the phone:
# the APK installs fine and then has no runtime to untar for that device.
#   * the bootstrap must exist and its bin/bash must be an ELF of the right width, so a
#     cross-built or mislabelled archive cannot ship a 64-bit tree to 32-bit devices;
#   * the base payload (python, libusb, bash, coreutils) must exist for the arch.
assert_complete_arch() { # arch
  local arch="$1"
  local bootstrap="$ASSETS/bootstrap/bootstrap-$arch.zip"
  if [ ! -f "$bootstrap" ]; then
    echo "the archive carries no bootstrap-$arch.zip: the APK would have nothing to install on $arch devices" >&2
    exit 1
  fi
  python3 - "$bootstrap" "$arch" <<'PYELF'
import sys, zipfile

path, arch = sys.argv[1], sys.argv[2]
want = {"arm": 1, "i686": 1}.get(arch, 2)   # EI_CLASS: 1 = 32-bit, 2 = 64-bit
want_bits = 32 if want == 1 else 64
with zipfile.ZipFile(path) as z:
    if "bin/bash" not in z.namelist():
        sys.exit(f"{path}: no bin/bash in the bootstrap")
    head = z.read("bin/bash")[:5]
if head[:4] != b"\x7fELF":
    sys.exit(f"{path}: bin/bash is not an ELF binary")
if head[4] != want:
    have = 64 if head[4] == 2 else 32
    sys.exit(f"{path}: bin/bash is {have}-bit but {arch} is {want_bits}-bit")
print(f"  check   bootstrap-{arch}.zip ({want_bits}-bit ELF)")
PYELF
  if ! compgen -G "$ASSETS/payload/$arch/opx-base_*.targz" > /dev/null; then
    echo "the archive carries no base runtime payload for $arch (python, libusb, bash)" >&2
    exit 1
  fi
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

  assert_complete_arch "$arch"
done

echo "== $arch-independent payloads"
while read -r url checksum path; do
  [ -z "${url:-}" ] && continue
  fetch_verified "$url" "$checksum" "$ASSETS/payload/all/$(asset_name "$path")"
done < <(artifact_url payload all)

for required in opx-wifi4 opx-runtime opx-usb; do
  if ! compgen -G "$ASSETS/payload/all/${required}_*.targz" > /dev/null; then
    echo "the archive carries no $required payload: the engine would be missing from the APK" >&2
    exit 1
  fi
done

cp "$WORK/index.json" "$ASSETS/index.json"

echo
echo "Runtime assets ready:"
du -sh "$ASSETS"/bootstrap "$ASSETS"/payload "$ASSETS"/index.json 2>/dev/null || true
