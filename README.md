# OPX OSS

> A wireless engine, its USB host bridge and a guided interface — one APK, no terminal.
> Authorized testing only.

**Package:** `com.opx.oss` · **Version:** 0.3.0-alpha.1 · **Min SDK:** 23 (Android 6.0) · **Licence:** GPL-3.0

---

## What this is

Upstream ships a terminal app and a separate Android-API bridge app that must be installed
together and signed with the same key. OPX OSS does not repeat that layout: it is one native
app whose runtime lives under its own private directory — `/data/data/com.opx.oss/files/usr`,
the "prefix" — exactly the way a native app's runtime is supposed to work. Every binary and
every package is compiled for that prefix. No virtualization layer, no other app's prefix, no
second APK.

There is **no terminal section, no terminal shortcut and no second APK**. The engine is started by
the app process (`OpxApp` → `OpxService`), installs its runtime on first launch, and everything the
UI needs goes through one headless process spoken to with JSON lines.

## Architecture

```
┌────────────────────────────── OPX OSS (com.opx.oss) ─────────────────────────────┐
│  UI (Material 3, drawer)      WiFi · Logs · Home · About                          │
│      WiFi screen ── scan list, badges, USB adapter status + copyable diagnosis    │
│      Logs screen ── everything the app logged, with Copy all                     │
│                    │                                                             │
│                    ▼                                                             │
│  WifiBridge (JSON lines over stdin/stdout, every line also logged)               │
│                    │                                                             │
│  OpxService (foreground engine host)                                             │
│   · installs/verifies the native runtime under com.opx.oss                       │
│   · runs one-off commands (no shell sessions)                                    │
│   · keeps the ongoing notification with Exit                                     │
│                    │                                                             │
│  UsbHostBridge (abstract socket "opx.usb", SCM_RIGHTS fd)                        │
│                    ▲                                                             │
│        opx-usb shim ────┘            (the app's own USB API, in-process)         │
│                    │                                                             │
│  native runtime at /data/data/com.opx.oss/files/usr:                            │
│    python · libusb · bash · coreutils · wifi4 engine (no TUI)                    │
└────────────────────┬─────────────────────────────────────────────────────────────┘
                     │ USB host API
                 OTG USB Wi-Fi adapter
```

### No terminal, no second app

`wifi4` shells out to a USB command for its adapter. OPX OSS ships that command as `opx-usb`,
a native script inside the app with the app's own name and environment (`OPX_USB_FD`): it talks
to the in-app USB bridge over the abstract socket `opx.usb`, which asks Android for the device
permission and returns the descriptor over SCM_RIGHTS — one UID, one APK:

```
wifi4 → opx-usb (native script) → abstract socket "opx.usb" → UsbHostBridge → UsbManager → fd
```

### Why not proot

The previous build mirrored another app's packages (compiled for that app's prefix)
and ran them through a bundled `proot` inside a fake rootfs. That is not how a native app works,
and it made the WiFi stack unreachable (`wifi4`'s USB backend selection keys off the prefix).
OPX OSS now compiles its own base runtime **from source for its own prefix** — see
`tools/build-base.sh` + `tools/patch-properties.py`, which fork the package build system's
app-identity variables (`TERMUX_APP__PACKAGE_NAME`, API-app package name, namespace) to
`com.opx.oss`. Every shipped artifact is audited for the foreign prefix (`tools/make-index.py`)
and the build fails if one is found.

The app targets SDK 28 on purpose: Android 10+ denies `execve()` inside the app home directory
for apps targeting 29+, which is where the runtime lives.

### Engine, not TUI

The wifi4 source is shipped as a **library**: `tools/patch-wifi4.py` removes `wifi4/ui/`, the
desktop privileged setup modules and the console entry point, installs a headless prompter,
rewrites every hardcoded path and message to OPX OSS's own identity, and refuses to build if
any module still imports Textual, the removed TUI or the foreign prefix. `wifi4/engine` stays,
and `tools/engine/opx_engine.py` exposes it as a line protocol:

```
{"cmd":"ping"}                 → {"event":"ready","version":"0.1.0"}
{"cmd":"devices"}              → {"event":"devices","devices":[...]}
{"cmd":"scan","seconds":25}    → {"event":"network",...} … {"event":"scan_done","count":7}
{"cmd":"stop"}                 → {"event":"stopped"}
```

## The package archive

Binaries are **not** fetched from another app's releases at APK build time. They come from the
OPX package archive, `OPX-official/opx-packages` (`gh-pages` branch, plus its `packages` release
for artifacts too large for a git branch):

| Path | What |
|---|---|
| `pool/main/…/*.deb` | packages, apt-compatible |
| `dists/stable/main/binary-<arch>/Packages(.gz)` | apt index |
| `payload/<arch>/…tar.gz` | the same trees, ready to untar into `$PREFIX` |
| `payload/all/…tar.gz` | architecture-independent packages (`opx-wifi4`, `opx-runtime`, `opx-usb`) |
| `base/bootstrap/bootstrap-<arch>.zip` | bootstrap built for this app's prefix (release asset, >100 MiB) |
| `payload/<arch>/opx-base_…tar.gz` | the base runtime payload (release asset, >100 MiB) |
| `index.json` | catalogue with SHA-256 and the exact URL for every artifact |

A git branch rejects any file over 100 MiB, so the bootstrap zips and the bare base payloads are
published as assets of the `packages` release, where a single asset may be 2 GiB. Their asset
names are the archive paths with `/` written as `__`, and `index.json` carries the URL of each
one, so a consumer never has to know where a given artifact is stored.

Two workflows keep this honest:

* **`Packages`** (`.github/workflows/packages.yml`) — builds everything from source: the base
  runtime in the package build system's docker builder with the app identity forked to
  `com.opx.oss`, plus wifi4 from a pinned commit, the engine and the USB shim. Every artifact
  is audited for the foreign prefix before publishing, and the build system's own app
  helpers (upstream `*-tools` packages that name the other app) are stripped out of the runtime
  with `tools/prune-foreign.py` before the payload is packed.
* **`Build`** (`.github/workflows/build.yml`) — builds the debug and release APKs without
  staging any runtime payloads, so the APK stays small; on first launch the app reads
  `index.json` from that archive, selects `arm` or `aarch64`, downloads with Range resume and
  verifies every SHA-256 before installing. It publishes a GitHub Release on a `v*` tag.

Building locally:

```bash
# packages (needs a wifi4 checkout)
WIFI4_SRC=../opx-wifi4 bash tools/build-packages.sh

# APK (small; the runtime is downloaded on first launch)
./gradlew assembleDebug
```

## Roadmap

| Phase | Deliverable |
|---|---|
| 1 | App shell, Material 3 screens, CI build/release **(done)** |
| 2 | Native runtime at the app's own prefix, engine service started with the app, headless command runner **(done)** |
| 2b | USB host bridge folded into the app: `opx-usb`, `UsbHostBridge` **(done)** |
| 3 | Headless wifi4 engine + WiFi scan UI + copyable logs **(current)** |
| 4 | Capture / deauth / WPS campaigns, vault and cracking |
| 5 | Further modules (LAN, nmap, web scanning, …) |

## Licence & credits

GPL-3.0. The wireless engine is based on wifit3 by derv82, with OPX extensions; the base
runtime is built from the open-source package set with OPX's own app identity. The interface
follows the open-source StrykerOSS design (same licence), including a few of its icon vectors.

**Disclaimer:** use only on networks and devices you own or are explicitly authorized to test.
