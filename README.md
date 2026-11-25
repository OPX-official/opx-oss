# OPX OSS

A wireless engine, its USB host bridge and a guided interface — one APK, no terminal, no
companion app, no runtime to download.

The engine is **our own Java code inside the app**. There is no interpreter, no shell, no
package tree and no second process: the WiFi screen talks to an in-process engine over the same
line protocol it has always spoken.

## Status

The project is being rebuilt from scratch. [`PLAN.md`](PLAN.md) is the contract: every item
names the reference that must be studied before it is written and the class that replaces it,
and section 10 carries the live progress. Releases stay empty until the rewrite is finished;
each build is an artifact of its own workflow run.

## What is in the app today

| Layer | Where | State |
|---|---|---|
| Engine protocol | `app/src/main/java/com/opx/oss/engine/NativeEngine.java` | `ping`, `status`, `config`, `devices`, `targets`, `captures`, `crack_tools`, `attack_status`, `stop` |
| USB bridge | `UsbHostBridge.java`, `UsbIds`, `UsbAttachReceiver`, `UsbAutoConnect` | unchanged: abstract socket `opx.usb`, `list`/`open`, permission and detach handling, descriptor passed over `SCM_RIGHTS` as `OPX_USB_FD` |
| USB transport | `engine/UsbTransport.java`, `engine/UsbFd.java` | our own control/bulk transport over Android's host API |
| Crypto | `engine/crypto/*` | SHA-1, SHA-256, HMAC, PBKDF2, RC4, CRC-32, WPA PMK/PTK/KCK/MIC/PMKID |
| 802.11 | `engine/dot11/*` | MAC/IE handling, frame builders, beacon and probe-response parser, EAPOL-Key and M1–M4 |
| Scan and vault | `engine/{AccessPoint,ScanBook,ScanEngine,Vault}.java` | scan sink, per-BSSID merge, capture vault, `22000` hashline writer |
| Adapters | `engine/chips/*` | contract and catalogue; RTL8187 register, EEPROM, transmit and receive paths |

Everything above is checked against known answers when the engine starts; the result is
reported in the `ready` event as `runtime.selfcheck`.

## Bundled chip firmware

The APK carries **65 firmware images for 29 adapter families** in
`app/src/main/assets/firmware/`, each with its own licence file and a `manifest.txt` holding
its SHA-256 and size. `engine/chips/firmware/FirmwareStore.java` copies an image into the app's
files on first use and verifies that digest before handing it to a driver; a truncated or
replaced image is refused.

| Family | Chips |
|---|---|
| Atheros | AR9271 |
| Ralink / MediaTek | RT3070, RT3072, RT3572, RT5370, RT5372, RT5572, MT7601U, MT7610U/E, MT7662, MT7921AU, MT7925AU |
| Realtek | RTL8188EU, RTL8188FTV, RTL8192CU, RTL8192FU, RTL8812AU, RTL8814AU, RTL8821AU/CU, RTL8822BU/CU, RTL8922AU |

## Rules

- No external source tree, package archive or prebuilt library is shipped or executed.
- **Chip firmware is the one allowed exception:** a radio whose bring-up needs its vendor
  firmware blob (for example AR9271, RT5370, mt76 and RTL8xxxU adapters) uses that blob. It is
  the card's own firmware, not a library; every line around it is ours.
- The USB system — connection, socket, descriptor handling (`OPX_USB_FD`) and bridge logic — is
  kept intact.
- No old runtime's name, path or dependency appears anywhere in the shipped system.

## Build

```bash
./gradlew assembleDebug assembleRelease
```

CI (`.github/workflows/build.yml`) does the same on every push: JDK, Gradle, both APKs, and an
artifact per run. No container, no package manager, no per-architecture pipeline.

## Layout

```
app/                     the Android app and the engine it carries
docs/media/              screenshots and animations used by this README
tools/gen-usb-ids.py     regenerates the USB id catalogue
PLAN.md                  the rewrite plan and its live progress
```
