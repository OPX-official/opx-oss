#!/usr/bin/env bash
#
# Builds the OPX OSS base runtime (python, libusb, bash, coreutils) from source for the app's
# own prefix (/data/data/com.opx.oss/files/usr) inside the termux-packages docker builder.
#
# Why this exists: every package of the Termux build system has the prefix compiled in, via
# TERMUX_APP__PACKAGE_NAME in scripts/properties.sh. Forking that variable (plus the
# API-app package name and namespace, which the same file blesses as fork-safe) recompiles
# the whole base runtime for our prefix — the exact native model Termux itself uses when
# forked. Nothing is mirrored from another app's repository, and the build refuses to put
# the foreign prefix into any artifact.
#
# Usage: build-base.sh --out DIR --arches "aarch64 arm" --version X
#
# Outputs (under --out):
#   payload/<arch>/opx-base_<ver>_<arch>.tar.gz   the built runtime as one payload tar, paths
#                                                 under data/data/com.opx.oss/files/usr
#   base/bootstrap/bootstrap-<arch>.zip            the bootstrap for the same prefix
#   base/base-lock.json                            every artifact's sha256 + the build commit
#
# This is a long build (a clean docker toolchain per arch). It runs in the Packages workflow;
# SKIP_BASE=1 in build-packages.sh skips it for local packaging checks.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT=""
ARCHES="aarch64 arm"
VERSION="$(date -u +%Y.%m.%d)"
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --arches) ARCHES="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [ -z "$OUT" ]; then echo "--out is required" >&2; exit 2; fi

FOREIGN="com.termux"
PREFIX_REL="data/data/com.opx.oss/files/usr"
# Packages the engine needs: python (the engine), libusb (the adapter), bash+coreutils (the
# shell the app runs everything through).
BASE_PACKAGES="bash,coreutils,python,libusb"

log() { printf '\n== %s\n' "$*"; }

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --------------------------------------------------------------- checkout + fork

log "termux-packages checkout (pinned by the workflow)"
TP_SRC="${TP_SRC:-$WORK/termux-packages}"
if [ ! -d "$TP_SRC/scripts" ]; then
  # Fetch one commit, not a moving branch: a bootstrap run costs the better part of an hour and
  # must build the same tree every time. TERMUX_PACKAGES_COMMIT comes from packages.yml.
  git init --quiet "$TP_SRC"
  git -C "$TP_SRC" remote add origin https://github.com/termux/termux-packages
  pin="${TERMUX_PACKAGES_COMMIT:-master}"
  git -C "$TP_SRC" fetch --quiet --depth 1 origin "$pin" \
    || { echo "cannot fetch termux-packages $pin, falling back to master" >&2
         git -C "$TP_SRC" fetch --quiet --depth 1 origin master; }
  git -C "$TP_SRC" checkout --quiet FETCH_HEAD
fi
BUILD_COMMIT="$(git -C "$TP_SRC" rev-parse HEAD)"
log "termux-packages commit: $BUILD_COMMIT"

python3 "$ROOT/tools/patch-properties.py" "$TP_SRC"
python3 "$ROOT/tools/patch-bootstraps.py" "$TP_SRC" "$BASE_PACKAGES"

# --------------------------------------------------------------- build per arch

mkdir -p "$OUT/payload" "$OUT/base/bootstrap"
declare -A PYTHON_SUM=()

for arch in $ARCHES; do
  log "building base runtime for $arch (docker)"
  cd "$TP_SRC"
  # build-bootstraps.sh builds the base set plus -a packages for --architectures and emits a
  # bootstrap-<arch>.zip; the .debs it builds end up in the checkout directory.
  ./scripts/run-docker.sh ./scripts/build-bootstraps.sh --architectures "$arch" -a "$BASE_PACKAGES" \
    > "$WORK/build-$arch.log" 2>&1 || { tail -50 "$WORK/build-$arch.log"; exit 1; }

  # build-bootstraps.sh writes bootstrap-<arch>.zip into the checkout root and every built
  # .deb into output/.
  boot="$(ls "$TP_SRC"/bootstrap-"$arch".zip 2>/dev/null | head -1 || true)"
  if [ -z "$boot" ]; then
    echo "no bootstrap-$arch.zip produced" >&2; tail -50 "$WORK/build-$arch.log"; exit 1
  fi

  # The upstream bootstrap ships apt sources pointing at Termux's own archive. Rewrite them to
  # the OPX archive so apt/pkg inside this app can only ever install OPX-built packages.
  OUR_REPO="${OUR_REPO:-https://raw.githubusercontent.com/OPX-official/opx-packages/gh-pages/}"
  python3 - "$boot" "$OUR_REPO" <<'PYAPT'
import pathlib, sys, zipfile

boot = pathlib.Path(sys.argv[1])
repo = sys.argv[2].rstrip("/") + "/"
content = ("# OPX OSS package archive, built by this repository's Packages workflow.\n"
           f"deb [trusted=yes] {repo} stable main\n")
target = "etc/apt/sources.list"

tmp = boot.with_suffix(".rewrite")
with zipfile.ZipFile(boot) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
    if target not in src.namelist():
        sys.exit(f"{boot}: no {target} to rewrite")
    for info in src.infolist():
        data = src.read(info.filename)
        if info.filename == target:
            data = content.encode("utf-8")
        dst.writestr(info, data)
tmp.replace(boot)

with zipfile.ZipFile(boot) as check:
    for name in check.namelist():
        if not name.endswith((".list", ".conf", ".sources")) and name != target:
            continue
        text = check.read(name).decode("utf-8", "replace")
        if "termux.dev" in text or "termux-main" in text:
            sys.exit(f"{boot}:{name} still points at the other package archive")
print(f"  sources.list -> {repo}")
PYAPT

  # Merge the built .debs for this arch into one payload tar: that is what the app installs.
  merge="$WORK/merge-$arch"
  mkdir -p "$merge"
  debian_count=0
  for deb in "$TP_SRC"/output/*.deb; do
    [ -f "$deb" ] || continue
    case "$(basename "$deb")" in
      *"_$arch.deb") ;;
      *) continue ;;
    esac
    control_schema="$(dpkg-deb -f "$deb" Architecture 2>/dev/null || true)"
    [ "$control_schema" = "$arch" ] || continue
    debian_count=$((debian_count + 1))
    dpkg-deb --fsys-tarfile "$deb" | tar -xf - -C "$merge"
  done
  if [ "$debian_count" -eq 0 ]; then
    echo "no $arch debs found after the build" >&2; exit 1
  fi
  if [ -d "$merge/$FOREIGN" ] || grep -rqI "$FOREIGN" "$merge/$PREFIX_REL" 2>/dev/null | head -1 | grep -q .; then
    echo "foreign prefix ($FOREIGN) found in the built $arch tree" >&2
    grep -rIl "$FOREIGN" "$merge/$PREFIX_REL" 2>/dev/null | head -5 >&2
    exit 1
  fi

  payload="$OUT/payload/$arch/opx-base_${VERSION}_${arch}.tar.gz"
  mkdir -p "$(dirname "$payload")"
  tar -C "$merge" -czf "$payload" data
  cp "$boot" "$OUT/base/bootstrap/bootstrap-$arch.zip"
  printf '  payload  %s (%s debs)\n' "payload/$arch/opx-base_${VERSION}_${arch}.tar.gz" "$debian_count"
  printf '  bootstrap bootstrap-%s.zip\n' "$arch"
done

# --------------------------------------------------------------- lock

python3 - "$OUT" "$BUILD_COMMIT" "$VERSION" <<'PY'
import hashlib, json, pathlib, sys
out = pathlib.Path(sys.argv[1])
commit, version = sys.argv[2], sys.argv[3]
entries = []
for path in sorted(out.rglob("*")):
    if not path.is_file():
        continue
    relative = path.relative_to(out).as_posix()
    if relative.startswith(("payload/", "base/bootstrap/")):
        entries.append({
            "path": relative,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "size": path.stat().st_size,
        })
lock = {
    "schema": 1,
    "version": version,
    "prefix": "/data/data/com.opx.oss/files/usr",
    "termux_packages_commit": commit,
    "artifacts": entries,
}
(out / "base" / "base-lock.json").write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
print(f"  base-lock.json: {len(entries)} artifacts")
PY
