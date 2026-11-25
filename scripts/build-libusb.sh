#!/usr/bin/env bash
# Builds libusb-1.0.so for the ABIs the app ships, into app/src/main/jniLibs/.
# libusb is LGPL-2.1 and stays a dynamically linked .so (see app/src/main/jniLibs/README.md).
set -euo pipefail
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your NDK (r26+)}"
VERSION="${LIBUSB_VERSION:-1.0.27}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
curl -fsSL "https://github.com/libusb/libusb/releases/download/v${VERSION}/libusb-${VERSION}.tar.bz2" \
  | tar -xj -C "$WORK"
SRC="$WORK/libusb-${VERSION}"
# libusb ships an ndk-build recipe for Android; its soname must stay libusb-1.0.so because
# usb_shim.py loads exactly that name.
cd "$SRC/android"
"$ANDROID_NDK_HOME/ndk-build" \
  NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/libusb.mk NDK_APPLICATION_MK=jni/Application.mk \
  APP_ABI="arm64-v8a x86_64" APP_PLATFORM=android-24 USE_PC_NAME=1 \
  NDK_OUT="$WORK/obj" NDK_LIBS_OUT="$WORK/libs" -j"$(nproc)"
for abi in arm64-v8a x86_64; do
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$WORK/libs/$abi/libusb-1.0.so" "$ROOT/app/src/main/jniLibs/$abi/libusb-1.0.so"
  echo "built $abi"
done
