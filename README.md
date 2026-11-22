# OPX OSS

> Termux + Termux:API + a wireless engine in one Android app, behind a guided GUI.
> Authorized testing only.

**Package:** `com.opx.oss` · **Version:** 0.1.0 · **Min SDK:** 23 (Android 6.0) · **Licence:** GPL-3.0

---

## What this is

Upstream ships Termux (the terminal) and Termux:API (the Android-API bridge) as two apps that must be
installed together and signed with the same key. OPX OSS merges them into one APK, adds the wifi4
engine (the working parts of [wifit3](https://github.com/derv82/wifit3), without its old TUI), and puts
a Stryker-style interface in front of it: cards, buttons and dialogs instead of memorised commands.

Everything is one app: the Terminal section is the shortcut into the in-app terminal, the same way
Stryker keeps its terminal behind a button. There is no second APK to install and nothing extra to
download.

Phase 1 ships the WiFi module only. Every other module stays visible but disabled until later phases.

## Architecture

```
┌──────────────────────────── OPX OSS (com.opx.oss) ────────────────────────────┐
│                                                                               │
│  GUI (Material 3, drawer)      Terminal section → TerminalActivity             │
│      WiFi · Home · Terminal · About        (Termux terminal-emulator/view)     │
│                    │                                  │                       │
│                    ▼                                  ▼                       │
│  WiFi bridge (JSON line protocol)          OpxService (foreground engine)      │
│                    │                        · installs the userland once        │
│                    │                        · owns every shell session         │
│                    │                        · ongoing notification + Exit       │
│                    └──────────────┬───────────────────┘                       │
│                                   ▼                                           │
│        Termux userland inside a rootfs the binaries expect:                   │
│        $PREFIX = /data/data/com.termux/files/usr  (faked by bundled proot)     │
│                                   │                                           │
│            python · wifi4 engine · apt/pkg · libusb · termux-api binaries      │
└───────────────────────────────────┬───────────────────────────────────────────┘
                                    │ USB host API
                                OTG USB Wi-Fi adapter
```

### Engine first, terminal second

`OpxService` is started by `OpxApp` (and again by `MainActivity`), so the bundled userland is
extracted and verified as soon as the app process starts — opening the terminal is not a
prerequisite for anything else. Modules never spawn their own shell: they call
`OpxService.runCommand(...)`, which waits for the runtime to be ready and executes a one-off command
inside the userland without creating a terminal session. The terminal is therefore optional, aimed
at advanced users. The service is a
foreground service with an ongoing notification that stays until its **Exit** action is tapped;
that is also the only thing that ends the shell sessions. Closing the terminal screen, pressing back
or swiping the task out of the recents list leaves the sessions running, exactly like Termux, and
the notification brings you straight back into them.

The session drawer inside the terminal lists every open shell and offers **New session**, so several
terminals can run at the same time (each one is a separate proot process).

### Why proot

Every Termux bootstrap binary and `.deb` has `/data/data/com.termux/files/usr` compiled in. A different
`applicationId` therefore cannot use the official bootstrap directly. OPX OSS ships a static `proot`
and runs the official bootstrap in a rootfs where that path really exists, so `apt`/`pkg` install,
update and `python` keep working — while the app itself stays `com.opx.oss`.

### USB pass-through on non-rooted devices

The engine obtains the adapter file descriptor through Android's USB host API: `termux-usb -r -E -e
"<callback>" <device>` → callback receives `TERMUX_USB_FD` → descriptor travels back over a unix
socket. Because Termux:API lives in the same APK (same UID), the existing broadcast path works
unchanged, and no root is required.

### ABI support

The userland is downloaded per architecture (`arm`, `aarch64`, `i686`, `x86_64`), so 32-bit devices are
first-class — no arm64-only chroot payload. The APK targets Android 6.0+ (minSdk 23) and is installed
on Android 14 without compatibility errors.

## Building

```bash
./gradlew assembleDebug
```

CI (`.github/workflows/build.yml`) builds debug + release APKs on every push, uploads them as
artifacts, and publishes a GitHub Release when a `v*` tag is pushed.

## Roadmap

| Phase | Deliverable |
|---|---|
| 1 | App shell: drawer, Material 3 screens, CI build/release **(done)** |
| 2 | Termux runtime: proot rootfs installer, engine service started with the app, headless command runner, multi-session terminal **(current)** |
| 2b | `termux-api` bridge in-process (started next: `TermuxApiReceiver`, Usb/Wifi APIs) |
| 3 | WiFi engine bridge (headless wifi4) + scan UI over USB pass-through |
| 4 | Capture / deauth / WPS campaigns, vault and cracking |
| 5 | Further Stryker-style modules (LAN, nmap, web scanning, …) |

## Licence & credits

GPL-3.0. Termux is © the Termux contributors (GPL-3.0); Termux:API likewise; the wireless engine is
based on wifit3 by derv82, with OPX extensions.

**Disclaimer:** use only on networks and devices you own or are explicitly authorized to test.
