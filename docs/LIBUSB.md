# Native libraries

`libusb-1.0.so` is required by the embedded Python driver path: wifi4's drivers use PyUSB, and
`python/usb_shim.py` implements that surface on libusb, wrapping the usbfs fd that `UsbHostBridge`
already forwards over `opx.usb`.

Build it with `scripts/build-libusb.sh` (needs `ANDROID_NDK_HOME`). Expected layout:

    jniLibs/arm64-v8a/libusb-1.0.so
    jniLibs/armeabi-v7a/libusb-1.0.so
    jniLibs/x86_64/libusb-1.0.so

libusb is LGPL-2.1; keep it dynamically linked and ship its licence text with the APK.
