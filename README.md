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
| Engine protocol | `app/src/main/java/com/opx/oss/engine/NativeEngine.java` | `ping`, `status`, `config`, `devices`, `targets`, `probed`, `scan`/`stop_scan`, `attack` (`deauth`, `decloak`, `pmkid`, `wep`, `eviltwin`, `captive`), `stop_attack`, `attack_status`, `deauth_client`, `deauth_all`, `silence`, `chop`, `verify_psk`, `cpu_crack`/`crack`, `stop_crack`, `crack_status`, `captures`, `stop` |
| USB bridge | `UsbHostBridge.java`, `UsbIds`, `UsbAttachReceiver`, `UsbAutoConnect` | unchanged: abstract socket `opx.usb`, `list`/`open`, permission and detach handling, descriptor passed over `SCM_RIGHTS` as `OPX_USB_FD` |
| USB transport | `engine/UsbTransport.java`, `engine/UsbFd.java` | our own control/bulk transport over Android's host API |
| Crypto | `engine/crypto/*` | SHA-1, SHA-256, HMAC, PBKDF2, RC4, CRC-32, AES-128-CBC, WPA PMK/PTK/KCK/MIC/PMKID |
| 802.11 | `engine/dot11/*` | MAC/IE handling, frame builders, beacon and probe-response parser, EAPOL-Key and M1–M4, WEP samples, WSC attributes and messages |
| Attacks | `engine/attacks/*` | deauth, decloak, PMKID, WEP replay, evil twin with an optional captive portal |
| Crackers | `engine/crack/*` | WPA wordlist/mask search, PTW WEP recovery, WPS PIN formulas |
| WPS | `engine/wps/*` | the registrar half of a PIN exchange (M1–M7) |
| Scan and vault | `engine/{AccessPoint,ScanBook,ScanEngine,Vault,WepBook,ProbeBook}.java` | scan sink, per-BSSID merge, capture vault, `22000` hashline writer, probe and IV books |
| Adapters | `engine/chips/*` | contract, catalogue, firmware store; RTL8187 register, EEPROM, transmit and receive paths |

Everything above is checked against known answers when the engine starts; the result is
reported in the `ready` event as `runtime.selfcheck`.

**How close the port actually is — and where it knowingly differs — is written down in
[`AUDIT.md`](AUDIT.md).** Only the RTL8187 is brought up; the other families ship their
firmware, not a driver.

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

The build is not run automatically. When you ask for an APK, it is:

```bash
./gradlew assembleDebug assembleRelease
```

`.github/workflows/build.yml` holds the same steps for a manual run; it is not triggered here.

## Layout

```
app/            the Android app and the engine it carries
AUDIT.md        module-by-module fidelity of the port against the reference
docs/media/     screenshots and animations used by this README
PLAN.md         the rewrite plan and its live progress
```
