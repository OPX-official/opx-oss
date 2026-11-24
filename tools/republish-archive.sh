#!/usr/bin/env bash
# Republish the current archive with the foreign-named helpers removed, so the Build workflow
# can fetch a runtime that passes its prefix gate without waiting for a full base rebuild.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"
W="${W:-/tmp/republish}"
REL="https://github.com/OPX-official/opx-packages/releases/download/packages"
VERSION="2026.11.24"

rm -rf "$W"
mkdir -p "$W/assets" "$W/out/base/bootstrap" "$W/out/payload/aarch64" "$W/out/payload/arm"

echo "== download release assets"
for spec in \
  "base__bootstrap__bootstrap-aarch64.zip" \
  "base__bootstrap__bootstrap-arm.zip" \
  "payload__aarch64__opx-base_${VERSION}_aarch64.tar.gz" \
  "payload__arm__opx-base_${VERSION}_arm.tar.gz" ; do
  curl -fsSL --retry 3 -o "$W/assets/$spec" "$REL/$spec"
  ls -l "$W/assets/$spec"
done

echo "== clone gh-pages"
git clone --quiet --depth 1 --branch gh-pages \
  https://github.com/OPX-official/opx-packages.git "$W/page"

echo "== prune foreign helpers"
python3 "$ROOT/tools/prune-foreign.py" --needle com.termux "$W/assets"/*

echo "== stage out/"
rm -f "$W/page/base/bootstrap/"*.zip "$W/page/payload/aarch64/opx-base_"* "$W/page/payload/arm/opx-base_"* 2>/dev/null || true
cp -a "$W/page/pool" "$W/page/dists" "$W/out/"
cp -a "$W/page/payload/." "$W/out/payload/"
cp "$W/assets/base__bootstrap__bootstrap-aarch64.zip" "$W/out/base/bootstrap/bootstrap-aarch64.zip"
cp "$W/assets/base__bootstrap__bootstrap-arm.zip" "$W/out/base/bootstrap/bootstrap-arm.zip"
cp "$W/assets/payload__aarch64__opx-base_${VERSION}_aarch64.tar.gz" "$W/out/payload/aarch64/opx-base_${VERSION}_aarch64.tar.gz"
cp "$W/assets/payload__arm__opx-base_${VERSION}_arm.tar.gz" "$W/out/payload/arm/opx-base_${VERSION}_arm.tar.gz"

echo "== index"
python3 "$ROOT/tools/make-index.py" --out "$W/out"

echo "== upload release assets"
gh release upload packages "$W/assets"/* -R OPX-official/opx-packages --clobber

echo "== publish index.json + README to gh-pages"
cp "$W/out/index.json" "$W/page/index.json"
{
  echo "# OPX package archive"
  echo
  echo "Built from [OPX OSS](https://github.com/OPX-official/opx-oss) by its \`Packages\` workflow."
  echo "Republished: $(date -u +%Y-%m-%dT%H:%M:%SZ) (foreign-named helpers stripped)"
  echo
  echo "Artifacts too large for a git branch live in the \`packages\` release; \`index.json\` here"
  echo "points at them."
} > "$W/page/README.md"
git -C "$W/page" add -A
git -C "$W/page" -c user.name="OPX Packages" -c user.email="opx@opx.dev" \
  commit --quiet -m "packages: strip foreign-named helpers from the ${VERSION} runtime"
git -C "$W/page" push --quiet origin gh-pages
echo "== done: $(git -C "$W/page" rev-parse --short HEAD)"
