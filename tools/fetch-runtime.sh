#!/usr/bin/env bash
#
# Fetches the runtime binaries that the APK ships with, so that a fresh install
# never needs to download anything (exactly how Termux itself works: the
# bootstrap is bundled in the APK and only extracted on first launch).
#
#   * Termux bootstrap archives (one per ABI) from the official
#     termux/termux-packages releases - these are the same files and checksums
#     that termux-app's own build uses.
#   * The termux-main packages "proot", "libtalloc" and "libandroid-shmem":
#     the APK runs the official Termux bootstrap from /data/data/com.opx.oss,
#     whose path differs from the $PREFIX (/data/data/com.termux/files/usr)
#     that the bootstrap binaries are compiled for, so proot bind-mounts the
#     real prefix onto the expected guest path.
#
# Everything lands in app/src/main/assets/ and is git-ignored (it is build
# input, not source).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"

BOOTSTRAP_VERSION="2026.02.12-r1%2Bapt.android-7"
BOOTSTRAP_BASE="https://github.com/termux/termux-packages/releases/download/bootstrap-${BOOTSTRAP_VERSION}"
TERMUX_REPO="https://packages.termux.dev/apt/termux-main"

declare -A BOOTSTRAP_SHA256=(
  [aarch64]="ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"
  [arm]="a38f4d3b2f735f83be2bf54eff463e86dc32a3e2f9f861c1557c4378d249c018"
  [i686]="f5bc0b025b9f3b420b5fcaeefc064f888f5f22a0d6fd7090f4aac0c33eb3555b"
  [x86_64]="b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a"
)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

fetch_verified() {
  local url="$1" out="$2" expected="$3"
  if [ -f "$out" ] && [ "$(sha256 "$out")" = "$expected" ]; then
    echo "  cached  $(basename "$out")"
    return
  fi
  echo "  get     $url"
  curl -fsSL --retry 3 -o "$out.tmp" "$url"
  local actual
  actual="$(sha256 "$out.tmp")"
  if [ "$actual" != "$expected" ]; then
    echo "checksum mismatch for $url: expected $expected, got $actual" >&2
    rm -f "$out.tmp"
    exit 1
  fi
  mv "$out.tmp" "$out"
}

# Pulls Version/Filename/SHA256 for a package out of a termux-main Packages index.
package_field() {
  local arch="$1" pkg="$2" field="$3" index
  index="$WORK/Packages-$arch"
  if [ ! -f "$index" ]; then
    curl -fsSL --retry 3 -o "$index" "$TERMUX_REPO/dists/stable/main/binary-$arch/Packages"
  fi
  awk -v pkg="$pkg" -v field="$field" '
    BEGIN { RS = "" }
    $0 ~ ("(^|\n)Package: " pkg "(\n|$)") {
      n = split($0, lines, "\n")
      for (i = 1; i <= n; i++) if (index(lines[i], field ": ") == 1) {
        print substr(lines[i], length(field) + 3); exit
      }
    }' "$index"
}

mkdir -p "$ASSETS/bootstrap" "$ASSETS/proot"

echo "== Termux bootstrap archives =="
for arch in aarch64 arm i686 x86_64; do
  fetch_verified "$BOOTSTRAP_BASE/bootstrap-$arch.zip" \
    "$ASSETS/bootstrap/bootstrap-$arch.zip" "${BOOTSTRAP_SHA256[$arch]}"
done

echo "== proot + runtime libraries =="
for arch in aarch64 arm i686 x86_64; do
  echo "-> $arch"
  rm -rf "$WORK/$arch"
  mkdir -p "$WORK/$arch/deb" "$ASSETS/proot/$arch"

  for pkg in proot libtalloc libandroid-shmem; do
    filename="$(package_field "$arch" "$pkg" Filename)"
    checksum="$(package_field "$arch" "$pkg" SHA256)"
    if [ -z "$filename" ] || [ -z "$checksum" ]; then
      echo "could not resolve $pkg for $arch" >&2
      exit 1
    fi
    deb="$WORK/$arch/$(basename "$filename")"
    fetch_verified "$TERMUX_REPO/$filename" "$deb" "$checksum"
    dpkg-deb -x "$deb" "$WORK/$arch/deb"
  done

  prefix="$WORK/$arch/deb/data/data/com.termux/files/usr"
  cp "$prefix/bin/proot" "$ASSETS/proot/$arch/proot"
  cp "$prefix/libexec/proot/loader" "$ASSETS/proot/$arch/loader"
  cp "$prefix/lib/libtalloc.so.2" "$ASSETS/proot/$arch/libtalloc.so.2"
  shmem="$(ls "$prefix"/lib/libandroid-shmem.so* | head -1)"
  cp "$shmem" "$ASSETS/proot/$arch/libandroid-shmem.so"
  chmod 755 "$ASSETS/proot/$arch/proot" "$ASSETS/proot/$arch/loader"
done

echo
echo "Runtime assets ready:"
du -sh "$ASSETS/bootstrap" "$ASSETS/proot"
