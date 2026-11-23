#!/usr/bin/env bash
#
# Builds the OPX package archive. Every artifact the APK ships with comes from here, so
# the APK build never has to talk to a third-party repository (see tools/fetch-packages.sh).
#
#   out/pool/main/<letter>/<name>/<name>_<ver>_<arch>.deb    apt packages
#   out/payload/<arch>/<name>_<ver>_<arch>.tar.gz            the same tree, ready to untar
#                                                            into $PREFIX on device
#   out/base/bootstrap/bootstrap-<arch>.zip                  Termux bootstrap (mirrored)
#   out/index.json                                           the catalogue the APK consumes
#   out/dists/stable/main/binary-<arch>/Packages(.gz)        apt index (for `pkg` later)
#
# Our own packages are built from source in this repository (and from the wifi4 source
# checkout). The Termux base runtime is mirrored once from Termux's signed apt repository
# with every checksum recorded in out/base/base-lock.json: the app cannot compile a
# libc-level Python toolchain itself, and mirroring keeps a single pinned source.
#
# Environment:
#   WIFI4_SRC   path to the opx-wifi4 checkout        (default ../references/opx-wifi4)
#   OUT         output directory                      (default build/packages)
#   ARCHES      space separated ABIs                  (default all four)
#   WITH_BASE   1 = mirror the Termux base runtime    (default 1)
#   VERSION     package version                       (default UTC date)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$ROOT/build/packages}"
WIFI4_SRC="${WIFI4_SRC:-$ROOT/../references/opx-wifi4}"
ARCHES="${ARCHES:-aarch64 arm i686 x86_64}"
# Accept the GitHub Actions boolean ("true"/"false") as well as 1/0/yes/no.
case "${WITH_BASE:-1}" in
  1|true|TRUE|True|yes|YES) WITH_BASE=1 ;;
  *) WITH_BASE=0 ;;
esac
VERSION="${VERSION:-$(date -u +%Y.%m.%d)}"

PREFIX_REL="data/data/com.termux/files/usr"
TERMUX_REPO="https://packages.termux.dev/apt/termux-main"
BOOTSTRAP_BASE="https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.12-r1%2Bapt.android-7"

declare -A BOOTSTRAP_SHA256=(
  [aarch64]="ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"
  [arm]="a38f4d3b2f735f83be2bf54eff463e86dc32a3e2f9f861c1557c4378d249c018"
  [i686]="f5bc0b025b9f3b420b5fcaeefc064f888f5f22a0d6fd7090f4aac0c33eb3555b"
  [x86_64]="b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a"
)

log() { printf '\n== %s\n' "$*"; }

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mk_tree() {
  local tree
  tree="$(mktemp -d)"
  mkdir -p "$tree/$PREFIX_REL"
  printf '%s\n' "$tree"
}

build_deb() { # name ver arch tree description depends
  local name="$1" ver="$2" arch="$3" tree="$4" desc="$5" depends="${6:-}"
  local stage size out
  stage="$(mktemp -d)"
  cp -a "$tree/." "$stage/"
  mkdir -p "$stage/DEBIAN"
  size="$(du -sk "$stage" | cut -f1)"
  {
    printf 'Package: %s\n' "$name"
    printf 'Version: %s\n' "$ver"
    printf 'Architecture: %s\n' "$arch"
    printf 'Maintainer: OPX OSS <opx@opx.dev>\n'
    printf 'Installed-Size: %s\n' "$size"
    printf 'Section: utils\nPriority: optional\n'
    if [ -n "$depends" ]; then printf 'Depends: %s\n' "$depends"; fi
    printf 'Description: %s\n' "$desc"
  } > "$stage/DEBIAN/control"
  out="$OUT/pool/main/${name:0:1}/$name/${name}_${ver}_${arch}.deb"
  mkdir -p "$(dirname "$out")"
  dpkg-deb --root-owner-group --build "$stage" "$out" >/dev/null
  rm -rf "$stage"
  printf '  deb      %s\n' "${out#"$OUT"/}"
}

build_payload() { # name ver arch tree
  local name="$1" ver="$2" arch="$3" tree="$4"
  local dir="$OUT/payload/$arch"
  mkdir -p "$dir"
  tar -C "$tree" -czf "$dir/${name}_${ver}_${arch}.tar.gz" data
  printf '  payload  %s\n' "payload/$arch/${name}_${ver}_${arch}.tar.gz"
}

package() { # name arch tree description [depends]
  local name="$1" arch="$2" tree="$3" desc="$4" depends="${5:-}"
  build_deb "$name" "$VERSION" "$arch" "$tree" "$desc" "$depends"
  build_payload "$name" "$VERSION" "$arch" "$tree"
  rm -rf "$tree"
}

# --------------------------------------------------------------- our packages

build_opx_wifi4() { # $1 = arch label for the payload ("all")
  log "opx-wifi4 ${VERSION} (wifi4 engine, TUI removed)"
  if [ ! -d "$WIFI4_SRC/src/wifi4" ]; then
    echo "wifi4 source not found at $WIFI4_SRC" >&2
    exit 1
  fi

  local tree py
  tree="$(mk_tree)"
  py="$tree/$PREFIX_REL/lib/opx/python"
  mkdir -p "$py"

  cp -a "$WIFI4_SRC/src/wifi4" "$py/wifi4"
  find "$py/wifi4" -name '__pycache__' -type d -prune -exec rm -rf {} +

  # Drop the TUI and the desktop-only setup modules, and install the headless prompter the
  # engine uses instead of the modal dialogs. The script fails if anything still imports the
  # TUI, so a device without textual can never hit an ImportError.
  python3 "$ROOT/tools/patch-wifi4.py" "$py"

  # Pure-Python runtime dependencies, vendored so the device needs no pip and no network.
  python3 -m pip install --quiet --disable-pip-version-check --no-compile --target "$py" \
    pyusb platformdirs rich

  package opx-wifi4 "$1" "$tree" \
    "wifi4 wireless engine for OPX OSS (headless: the textual TUI is not shipped)"
}

build_opx_runtime() { # $1 = arch label
  log "opx-runtime ${VERSION} (engine entry point and OPX userland glue)"
  local tree
  tree="$(mk_tree)"

  install -d "$tree/$PREFIX_REL/bin" "$tree/$PREFIX_REL/lib/opx/python"
  cp "$ROOT/tools/engine/opx_engine.py" "$tree/$PREFIX_REL/lib/opx/python/opx_engine.py"
  cp "$ROOT/tools/engine/opx_engine" "$tree/$PREFIX_REL/bin/opx-engine"
  chmod 0755 "$tree/$PREFIX_REL/bin/opx-engine"

  package opx-runtime "$1" "$tree" \
    "OPX OSS runtime: the headless OPX engine entry point"
}

build_opx_usb() { # $1 = arch label
  log "opx-usb ${VERSION} (termux-usb client for the in-app USB host bridge)"
  local tree
  tree="$(mk_tree)"

  install -d "$tree/$PREFIX_REL/bin" "$tree/$PREFIX_REL/lib/opx/python"
  cp "$ROOT/tools/usb/termux-usb" "$tree/$PREFIX_REL/bin/termux-usb"
  cp "$ROOT/tools/usb/opx_usb.py" "$tree/$PREFIX_REL/lib/opx/python/opx_usb.py"
  chmod 0755 "$tree/$PREFIX_REL/bin/termux-usb"

  package opx-usb "$1" "$tree" \
    "termux-usb replacement that talks to the OPX OSS USB host bridge in-process"
}

# --------------------------------------------------------------- base mirror

mirror_base() {
  if [ "$WITH_BASE" != "1" ]; then
    log "mirror_base skipped (WITH_BASE=$WITH_BASE)"
    return
  fi
  log "Termux base runtime (mirrored, checksums recorded)"
  mkdir -p "$OUT/base/bootstrap"
  local arch url file
  for arch in $ARCHES; do
    file="$OUT/base/bootstrap/bootstrap-$arch.zip"
    if [ ! -f "$file" ]; then
      url="$BOOTSTRAP_BASE/bootstrap-$arch.zip"
      curl -fsSL --retry 3 -o "$file.tmp" "$url"
      mv "$file.tmp" "$file"
    fi
    printf '  bootstrap %s (%s)\n' "$arch" "$(sha256 "$file")"
  done

  python3 "$ROOT/tools/mirror-base.py" \
    --repo "$TERMUX_REPO" \
    --arch "$(echo $ARCHES | tr ' ' ',')" \
    --out "$OUT" \
    --version "$VERSION"
}

# --------------------------------------------------------------------- main

rm -rf "$OUT"
mkdir -p "$OUT"

build_opx_wifi4 all
build_opx_runtime all
build_opx_usb all
mirror_base

log "index"
python3 "$ROOT/tools/make-index.py" --out "$OUT"

log "done"
du -sh "$OUT"/* 2>/dev/null || true
