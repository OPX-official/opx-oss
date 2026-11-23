# OPX OSS

> A wireless engine, its USB host bridge and a guided interface — one APK, no terminal.
> Authorized testing only.

**Package:** `com.opx.oss` · **Version:** 0.2.0-alpha.2 · **Min SDK:** 23 (Android 6.0) · **Licence:** GPL-3.0

---

## What this is

Upstream ships Termux (the terminal) and Termux:API (the Android-API bridge) as two apps that
must be installed together and signed with the same key. OPX OSS does not repeat that layout: the
working parts of both — the userland, the USB descriptor hand-off — live **inside this app**, and
the wifi4 engine drives the adapter behind a card-and-button interface.

There is **no terminal section, no terminal shortcut and no second APK**. The engine is started by
the app process (`OpxApp` → `OpxService`), installs its userland on first launch, and everything the
UI needs goes through one headless process spoken to with JSON lines.

## Architecture

```
┌────────────────────────────── OPX OSS (com.opx.oss) ─────────────────────────────┐
│  UI (Material 3, drawer)      WiFi · Home · About                                 │
│      WiFi screen ── scan list, badges, adapter status                             │
│                    │                                                             │
│                    ▼                                                             │
│  WifiBridge (JSON lines over stdin/stdout)                                       │
│                    │                                                             │
│  OpxService (foreground engine host)                                             │
│   · installs/verifies the packaged userland                                      │
│   · runs one-off commands (no shell sessions)                                    │
│   · keeps the ongoing notification with Exit                                     │
│                    │                                                             │
│  UsbHostBridge (abstract socket "opx.usb", SCM_RIGHTS fd)                        │
│                    ▲                                                             │
│        termux-usb shim ─┘            (replaces the Termux:API app)                │
│                    │                                                             │
│  userland under bundled proot:  python · pyusb · wifi4 engine (no TUI)           │
└────────────────────┬─────────────────────────────────────────────────────────────┘
                     │ USB host API
                 OTG USB Wi-Fi adapter
```

### No terminal, no second app

`Termux:API` used to receive a broadcast from `termux-usb`, ask Android for the USB permission and
pass the descriptor back over a Unix socket. OPX OSS keeps that exact design but the server lives in
this app (`UsbHostBridge`), so the workspace and the API share one UID:

```
wifi4 → termux-usb (shim) → abstract socket "opx.usb" → UsbHostBridge → UsbManager → fd
```

The shim is a drop-in `termux-usb`: `-l` lists devices, `-r -E -e <callback>` hands the descriptor to
the callback in `TERMUX_USB_FD`. wifi4 is therefore unmodified in how it talks to USB.

### Why proot

Every Termux bootstrap binary and `.deb` has `/data/data/com.termux/files/usr` compiled in. OPX OSS
ships a static `proot` and runs the official bootstrap in a rootfs where that path really exists, so
`apt`/`pkg` install, update and `python` keep working — while the app itself stays `com.opx.oss`.
The app targets SDK 28 on purpose: Android 10+ denies `execve()` inside the app home directory for
apps targeting 29+, which is where proot and the userland live.

### Engine, not TUI

The wifi4 source is shipped as a **library**: `tools/patch-wifi4.py` removes `wifi4/ui/`, the desktop
privileged setup modules and the console entry point, installs a headless prompter, and refuses to
build if any module still imports Textual or the removed TUI. `wifi4/engine` stays, and
`tools/engine/opx_engine.py` exposes it as a line protocol:

```
{"cmd":"ping"}                 → {"event":"ready","version":"0.1.0"}
{"cmd":"devices"}              → {"event":"devices","devices":[...]}
{"cmd":"scan","seconds":25}    → {"event":"network",...} … {"event":"scan_done","count":7}
{"cmd":"stop"}                 → {"event":"stopped"}
```

## The package archive

Binaries are **not** fetched from Termux's releases at APK build time. They come from the OPX package
archive, `OPX-official/opx-packages` (`gh-pages` branch):

| Path | What |
|---|---|
| `pool/main/…/*.deb` | our own packages, apt-compatible |
| `dists/stable/main/binary-<arch>/Packages(.gz)` | apt index |
| `payload/<arch>/…tar.gz` | the same trees, ready to untar into `$PREFIX` |
| `payload/all/…tar.gz` | architecture-independent packages (`opx-wifi4`, `opx-runtime`, `opx-usb`) |
| `base/bootstrap/bootstrap-<arch>.zip` | Termux bootstrap (mirrored once) |
| `index.json` | catalogue with SHA-256 for every artifact |

Two workflows keep this honest:

* **`Packages`** (`.github/workflows/packages.yml`) — builds every package from source in this
  repository (wifi4 from the pinned commit, the engine, the USB shim), mirrors the Termux base
  runtime with recorded checksums, signs the apt index and pushes the archive.
* **`Build`** (`.github/workflows/build.yml`) — runs `tools/fetch-packages.sh`, which downloads
  **only** from that archive with checksum verification, then builds the debug and release APKs and
  publishes a GitHub Release on a `v*` tag.

Building locally:

```bash
# packages (needs a wifi4 checkout)
WIFI4_SRC=../opx-wifi4 bash tools/build-packages.sh

# APK
bash tools/fetch-packages.sh && ./gradlew assembleDebug
```

## Roadmap

| Phase | Deliverable |
|---|---|
| 1 | App shell, Material 3 screens, CI build/release **(done)** |
| 2 | Proot userland, engine service started with the app, headless command runner **(done)** |
| 2b | Termux:API folded into the app: USB host bridge, `termux-usb` shim **(done)** |
| 3 | Headless wifi4 engine + WiFi scan UI **(current)** |
| 4 | Capture / deauth / WPS campaigns, vault and cracking |
| 5 | Further modules (LAN, nmap, web scanning, …) |

## Licence & credits

GPL-3.0. Termux is © the Termux contributors (GPL-3.0); Termux:API likewise; the wireless engine is
based on wifit3 by derv82, with OPX extensions.

**Disclaimer:** use only on networks and devices you own or are explicitly authorized to test.
