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

## Known gaps — fix pending

Measured 2026-11-25 against the vendored `wifi4/chips` tree: **379 unique `vid:pid` pairs across
22 families** are selectable, and only **RTL8188FTV** has been proven on a real phone so far
(bring-up, `scan`, 19 APs). The gaps below are why the rest cannot be claimed to work yet; none
of them is a driver bug, all are in our Android layer.

- **`usb_shim._Configuration.__getitem__` rejects `(interface, alternate)` tuples.** PyUSB
  supports `cfg[(0, 0)]`; ours does `self._interfaces[index]`, so it raises
  `TypeError: list indices must be integers or slices, not tuple`. Read at bring-up by
  `mt7601u/transport.py:101`, `mt76x0u/transport.py:138`, `mt76x2u/transport.py:211`,
  `rtl8192cu/transport.py:18`, `rtl8192fu/transport.py:19`, `rtl8187/tx.py:213`. Blocked
  families: **MT7601U, MT7610U, MT7612U, RTL8187L, RTL8192CU, RTL8192FU**.
- **`Device.speed` is missing.** `rtl8192cu/transport.py:38` (`is_high_speed()`) and
  `rtl8814au_dkms/driver.py:71` read it; libusb already knows the answer
  (`libusb_get_device_speed`), so expose it as a property instead of inventing a value.
- **`Device.clear_halt(ep)` is missing.** Used by `mt7921au/firmware.py:56-58` on vendor-interface
  claim, plus `rtl8188eus`, `ar9271_v2`, `rtl8822bu`, `rtl8814au_dkms` (those four guard with
  try/except, so today it only silently degrades).
- **`usb.util.find_descriptor` returns an endpoint-less stub.** `rtl8187/tx.py:214` asks for
  `bEndpointAddress=0x02` and reads `wMaxPacketSize`; the current stub has none, so RTL8187 TX
  cannot pad `URB_ZERO_PACKET`. It should search the real `_Interface`'s endpoints.
- **`_registry()` runs before `usb_shim.install()`.** `opx_driver_host.start()` resolves the
  family first, and `rt3572`/`rt5572` import `usb` at module level, so both packages fail to
  import and are dropped from the table: `148f:3572` and `148f:5572` (Panda PAU09, wifi4 grade B)
  answer `no driver claimed`. Install/register the shim modules before building the registry, or
  make those two `__init__.py` files lazy.
- **Ambiguous ids.** `2357:0137` is claimed by both `mt76x2u` and `rtl8822cu`; the later import
  wins, so which driver runs is arbitrary. The DKMS/mainline twins resolve correctly except
  RTL8814AU, where `rtw88_8814au` (mainline) wins although wifi4's default is the DKMS port.

Beyond the shim: wifi4's own matrix grades RT2500USB and RTL8814AU **D**, leaves RTL8192CU
**ungraded** and RT3572 **untested**, and notes that 4T4R cards (RTL8922AU) starve RX on USB 3 —
so even after the fixes, "every chipset" is not the same claim as "every chipset well".

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
