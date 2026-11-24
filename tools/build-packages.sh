#!/usr/bin/env bash
#
# Builds the OPX package archive. Every artifact the APK ships with comes from here, so
# the APK build never has to talk to a third-party repository (see tools/fetch-packages.sh).
#
#   out/pool/main/<letter>/<name>/<name>_<ver>_<arch>.deb    apt packages
#   out/payload/<arch>/<name>_<ver>_<arch>.tar.gz            the same tree, ready to untar
#                                                            into $PREFIX on device
#   out/base/bootstrap/bootstrap-<arch>.zip                  the OPX bootstrap (built, not mirrored)
#   out/index.json                                           the catalogue the APK consumes
#   out/dists/stable/main/binary-<arch>/Packages(.gz)        apt index (for `pkg` later)
#
# The base runtime (python, libusb, bash, coreutils) is compiled from source in CI inside the
# termux-packages docker builder with scripts/properties.sh forked to this app
# (tools/patch-properties.py + tools/build-base.sh): every binary gets
# /data/data/com.opx.oss/files/usr compiled in, which is exactly where the app installs it.
# Nothing is mirrored from another app's repository, and nothing foreign enters the APK.
#
# Environment:
#   WIFI4_SRC   path to the opx-wifi4 checkout        (default ../references/opx-wifi4)
#   OUT         output directory                      (default build/packages)
#   ARCHES      space separated ABIs                  (default aarch64 arm)
#   SCOPE       all    = base runtime + OPX packages  (default)
#               extras = only the OPX packages, published on top of the archive that is
#                        already there (an engine or shim fix, about four minutes instead
#                        of the base runtime's two hours)
#   SKIP_BASE   1 = same as SCOPE=extras               (kept for old invocations)
#   VERSION     package version                       (default UTC date)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$ROOT/build/packages}"
WIFI4_SRC="${WIFI4_SRC:-$ROOT/../references/opx-wifi4}"
ARCHES="${ARCHES:-aarch64 arm}"
SKIP_BASE="${SKIP_BASE:-0}"
SCOPE="${SCOPE:-all}"
VERSION="${VERSION:-$(date -u +%Y.%m.%d)}"

PREFIX_REL="data/data/com.opx.oss/files/usr"
# Any path under the other app's prefix must never appear in a shipped artifact.
FOREIGN="com.termux"

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

# Fail if any file in a staged tree is literally addressed at the foreign prefix.
audit_tree() {
  local tree="$1" label="$2"
  # -a: also scan binaries, where the prefix is embedded as an ELF string.
  if grep -ral "$FOREIGN" "$tree" 2>/dev/null | head -5 | grep -q .; then
    echo "foreign prefix ($FOREIGN) found in $label" >&2
    grep -ral "$FOREIGN" "$tree" 2>/dev/null | head -10 >&2
    exit 1
  fi
}

# Fail if a .deb contains a foreign path (contents listing, not just control).
audit_deb() {
  local deb="$1"
  if dpkg-deb --contents "$deb" 2>/dev/null | grep -q "$FOREIGN"; then
    echo "foreign prefix ($FOREIGN) found inside $deb" >&2
    dpkg-deb --contents "$deb" | grep "$FOREIGN" | head -5 >&2
    exit 1
  fi
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
  audit_deb "$out"
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
  audit_tree "$tree" "$name"
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

  # Drop the TUI and the desktop-only setup modules, rewrite the app identity to our prefix,
  # and install the headless prompter the engine uses instead of the modal dialogs. The script
  # fails if anything still imports the TUI or names the foreign prefix.
  python3 "$ROOT/tools/patch-wifi4.py" "$py"

  # The headless patch must never take wifi4's package data with it: the crack dialog's
  # default-password source is this file, and shipping without it silently weakens every
  # CPU/Hashcat job that leaves the switch on.
  local defaults="$py/wifi4/crack/assets/default_passwords.txt"
  if [ ! -s "$defaults" ] || [ "$(wc -l < "$defaults")" -lt 1000 ]; then
    echo "wifi4 default password list missing or truncated: $defaults" >&2
    exit 1
  fi
  for required in wifi4/campaigns/wep/campaign.py wifi4/engine/wep_status.py; do
    if [ ! -s "$py/$required" ]; then
      echo "wifi4 headless package is missing $required" >&2
      exit 1
    fi
  done

  # Pure-Python runtime dependencies, vendored so the device needs no pip and no network.
  python3 -m pip install --quiet --disable-pip-version-check --no-compile --target "$py" \
    pyusb platformdirs rich

  package opx-wifi4 "$1" "$tree" \
    "wifi4 wireless engine for OPX OSS (headless: the textual TUI is not shipped)"
}

build_opx_runtime() { # $1 = arch label
  log "opx-runtime ${VERSION} (engine entry point and OPX runtime glue)"
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
  log "opx-usb ${VERSION} (USB client for the in-app host bridge)"
  local tree
  tree="$(mk_tree)"

  install -d "$tree/$PREFIX_REL/bin" "$tree/$PREFIX_REL/lib/opx/python"
  cp "$ROOT/tools/usb/opx-usb" "$tree/$PREFIX_REL/bin/opx-usb"
  cp "$ROOT/tools/usb/opx_usb.py" "$tree/$PREFIX_REL/lib/opx/python/opx_usb.py"
  chmod 0755 "$tree/$PREFIX_REL/bin/opx-usb"

  package opx-usb "$1" "$tree" \
    "opx-usb command that talks to the OPX OSS USB host bridge in-process"
}

# --------------------------------------------------------------- base runtime

build_base() {
  if [ "$SCOPE" = "extras" ] || [ "$SKIP_BASE" = "1" ]; then
    # The archive already carries the base runtime; only the OPX packages are rebuilt, and
    # tools/merge-index.py puts the rest of the catalogue back at publish time.
    log "base build skipped (scope=$SCOPE)"
    return
  fi
  log "base runtime (built from source for /data/data/com.opx.oss/files/usr)"
  bash "$ROOT/tools/build-base.sh" --out "$OUT" --arches "$ARCHES" --version "$VERSION"
}

# --------------------------------------------------------------------- main

rm -rf "$OUT"
mkdir -p "$OUT"

build_opx_wifi4 all
build_opx_runtime all
build_opx_usb all
build_base

log "index"
python3 "$ROOT/tools/make-index.py" --out "$OUT"

log "done"
du -sh "$OUT"/* 2>/dev/null || true
