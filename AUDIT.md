# Native rewrite — fidelity audit

This is the honest state of the port: what is proven identical, what is a faithful port with
documented simplifications, and what has no native counterpart yet. "100% accurate" is **not**
what this document says; read the verdict at the bottom before relying on any module.

Method: every native module was written from the reference tree under
`references/opx-wifi4/src/wifi4/` (not shipped in this repository), then checked in one of three
ways — pinned known-answer vectors that run at engine start (`engine/SelfCheck.java`),
side-by-side reading of the reference algorithm, or an offline simulation of the algorithm in
Python. Only the first gives machine-checked equality; the other two are review, and are marked
as such.

| Evidence | Meaning |
|---|---|
| **vector** | A published or independently computed answer is asserted on every engine start. |
| **ported** | Constants, offsets and control flow match the reference file named; no vector. |
| **simplified** | Same observable behaviour for the common case, fewer options than the reference. |
| **missing** | No native code yet. |

---

## 1. Crypto (`engine/crypto/`)

| Reference | Native | Evidence |
|---|---|---|
| `dot11/wsc/crypto.py` (AES), hostapd `wps_common.c` | `Aes.java` | **vector**: FIPS-197 C.1 (`69c4e0d8…`); S-box derived from the GF inverse, not typed. The S-box's affine step was missing the `^ inverse` term, so every AES result was wrong until the re-audit fixed it |
| hash padding (stdlib behaviour) | all hashes | **vector+run**: the length field of SHA-1/SHA-256 was written with `int` shifts (shift counts ≥ 32 wrap), and MD5's K table was cast straight from `double` (saturating above 2^31); both broke every digest until fixed. All vectors now pass under a real JVM run |
| stdlib SHA-1 / SHA-256 | `Sha1.java`, `Sha256.java` | **vector**: `sha1("abc")`, `sha256("abc")` |
| stdlib HMAC | `Hmac.java` | **vector**: RFC 2202 HMAC-SHA1 case 1, HMAC-MD5 case 2 (`750c783e…`) |
| stdlib MD5 | `Md5.java` | **vector**: RFC 1321 `md5("abc")` |
| `dot11/aes_cmac.py` | `Cmac.java` | **vector**: RFC 4493 examples 1 and 2 (empty and one-block messages) |
| stdlib PBKDF2 | `Pbkdf2.java` | **vector**: RFC 6070 (`password`/`salt`, 1 iteration) |
| `crack/wpa_psk.py`, `dot11/wpa.py` | `WpaKeys.java` | **vector**: IEEE 802.11i PMK for `password`/`IEEE`, and the PMKID for a fixed pair. **ported**: key descriptor versions 1/2/3 (HMAC-MD5, HMAC-SHA1, AES-CMAC), 384-bit KDF-SHA256 PTK, HMAC-SHA256 PMKID, 64-hex passphrase read as a raw PMK |
| RC4 / CRC-32 | `Rc4.java`, `Crc32.java` | **vector**: RC4 `Key`/`Plaintext`, CRC-32 `123456789` |
| `dot11/wsc/crypto.py` DH/KDF | `dot11/wsc/WscCrypto.java` | **vector**: MODP-1536 `2^12345 mod p` prefix, WSC KDF over an all-zero key (`20663a17…`, recomputed from the reference's own construction after the port was found to use a 2-byte byte-length instead of a 4-byte bit-length), PIN checksum 0 for `1234567` (→ `12345670`) |

No constant in this area is taken from memory without a vector except the DH prime itself, which
is the RFC 3526 group-5 literal and is exercised by the `2^12345` check.

## 2. Frames and parsing (`engine/dot11/`)

| Reference | Native | Evidence |
|---|---|---|
| `dot11/mac.py` | `Mac.java` | **ported**: header build, `headerLength` for the DS/QoS bits, MAC text/parse |
| `dot11/eapol.py`, `parser._parse_eapol` | `Eapol.java` | **ported**: LLC/SNAP discovery, big-endian lengths, M1–M4 classification, PMKID KDE, MIC-zeroed copy; offsets 1/2/5/9/17/81/97/99 as in the reference |
| `dot11/ie.py` | `Ie.java` | **ported** for SSID/RSN/WPA/rates/DS/HT; `forcePskAkm` follows the reference's "single PSK AKM, drop PMKID list" rewrite |
| `dot11/parser.py` | `Parser.java` | **ported**: mandatory-SSID validation (printable, ≤32), hidden SSID, channel DS→HT→VHT precedence, Extended Capabilities bit 84, full RSN suite/caps/PMKID/GMCS walk with malformed-element bail, legacy WPA element, WPS TLV subset (lock, state, config methods, device password id, registrar, version 1.0/2.0, manufacturer/model/device type), chipset OUI table, airodump-style encryption label, and WPA3-Enterprise classification |
| `dot11/packet.py` | `Frame.java` | **ported** for deauth (with the 0x013A ACK NAV on unicast), disassoc, auth, directed/broadcast probe request, assoc request; **simplified** for beacon/probe response (see below) |
| `dot11/ap.py` `beacon_clone` | `Frame.beacon` / `probeResponse` | **simplified**: no WMM, country, extended capabilities or VHT operation; capability/interval/SSID/rates/DS/RSN plus (for CSA) legacy CSA, ECSA and secondary-channel-offset |
| `dot11/csa.py` | `Ie.csa`/`Ie.ecsa`, `Frame.csaBeacon` | **ported** shape (legacy CSA only same-band, always ECSA, secondary offset 0); **simplified**: we build our own beacon instead of rewriting the captured one |
| `dot11/chan.py` | `Chan.java` | **ported**: operating class 81/82/115/118/121/125, DFS range, same-band test |
| `dot11/auth_assoc.py` | `Frame.assocRequest` | **simplified**: fixed capability 0x0411; no beacon-derived short-preamble/short-slot bits, no WPS-without-RSN privacy rule |
| `dot11/btm.py`, `pmf.py`, `eap.py` | — | **missing** |

## 3. Capture (`engine/capture/`)

| Reference | Native | Evidence |
|---|---|---|
| `crack/handshake.py` | `Handshake.java`, `HandshakeBook.java` | **ported**: keystone M2 (or non-zero-nonce M4) paired with an ANonce donor from M1/M3, message pairs 0x00/0x02/0x05/0x01, nonce-correction bit 0x80 on every line, zero-nonce and short-MIC rejection, per-pair duplicate suppression, PMKID line |
| `crack/hc22000_format.py` | `Hc22000.java` | **vector**: an exact hashline string is asserted at start-up |
| `crack/wpa_psk.py` verify | `Verify.java`, `CpuCracker.java` | **ported**: PMK → KCK → MIC for key descriptor versions 1/2/3 over the MIC-zeroed frame, per-SSID PMK derivation across targets, HMAC-SHA1 and HMAC-SHA256 PMKIDs, raw 64-hex PMK |

Fixed: `Handshake.sha256` now follows the AP's AKM list (`PSK-SHA256`), so an AKM-6 PMKID is
written with the `03` message-pair field the reference sets. FT lines (`WPA*03`/`WPA*04`) are
still not produced.

## 4. Chips (`engine/chips/`)

| Reference | Native | Evidence |
|---|---|---|
| `chips/rtl8187/{constants,transport,eeprom,chan,tx,rx,mac,rtl8225}.py` | `chips/rtl8187/*` | **ported** for register map, 93C46 EEPROM read, RF 8225Z2 init, TX descriptor (12 bytes) and RX trailer (16 bytes), synth table ch1–14; **simplified**: no per-rate retry, diversity, antenna calibration or RSSI correction beyond `-4 - ((27*agc)>>6)` |
| `chips/driver.py`, `chips/products.py` | `ChipCatalog.java` | **ported**: vendor/product table and family names, plus a firmware-folder column the reference keeps in each driver |
| every other family (`rt2x00`, `mt76`, `rtl8xxxu`, `rtl88xxau`, `ar9271`, `rtw88`) | firmware blobs only | **missing**: the images ship (`assets/firmware/`, 65 files, 29 families) and `FirmwareStore` verifies them, but no bring-up, firmware download or TX/RX path exists for these radios |

RTL8187 is the only radio that can scan or transmit. Everything else is listed as supported in
the catalogue because its firmware is present, not because it works.

## 5. Attacks and crackers

| Reference | Native | Evidence |
|---|---|---|
| `campaigns/deauth.py` | `DeauthCampaign.java` | **ported** shape: rounds × bursts, reason 7 |
| `campaigns/decloak.py` + probe book | `DecloakCampaign.java`, `ProbeBook.java` | **simplified**: deauth-driven probe collection, no directed-probe phase |
| `campaigns/pmkid.py` | `PmkidCampaign.java` | **ported**: RSN mirror, synthetic client, fresh MAC per round, M1 PMKID read |
| `campaigns/wep/*` | `WepCampaign.java`, `WepBook.java` | **simplified**: ARP replay only (no chopchop, fragmentation or fake-auth) |
| `crack/wep.py` `PtwCracker` | `crack/WepCracker.java` | **ported**: KSA-first-3, Klein votes, additive σ table, best-first fudge search (depth 16, 100k trials), 8 held-back samples with tolerance 1, key lengths 5/13. Offline simulation: a known 5-byte key is recovered on the first trial at 60,000 IVs, but not at 20,000 — the reference's `ready_threshold` of 10,000 is therefore optimistic for this port |
| `campaigns/eviltwin/*` | `EvilTwin.java` | **simplified**: beacon + probe response + optional portal; **CSA beacon and a broadcast deauth punt** are in (`csa_channel`, `punt`), but there is no SAE->PSK downgrade, no BTM and no two-interface election |
| captive portal (`scripts/`, templates) | `portal/PortalServer.java` | **simplified**: our own HTTP/1.1 server and page, no DNS hijack, no per-vendor templates |
| `crack/wpa_psk.py` search | `CpuCracker.java`, `Rules.java` | **simplified**: wordlist, mask and a single mangling rule per word (`l u c r d t $ ^ s`) — no rule chains, GPU or hashcat hand-off |
| `campaigns/wps/*`, `dot11/wsc/*` | `dot11/wsc/*`, `wps/WpsRegistrar.java`, `crack/WpsPins.java` | **ported** crypto and messages (vectors above), **ported** PIN candidates: the reference's database is now shipped as `assets/wps_pins.json` (605 OUI PINs, 235 OUI→algorithm rows, 7 models, generated from `wps_pindb.py`), plus the 9 formulas and defaults; **missing**: Pixie Dust, lockout tracking, and the on-air association/EAPOL exchange that would actually run it |

## 6. USB

`UsbHostBridge.java` is byte-identical to the commit this rewrite started from (checked with
`git diff`, empty). The native side is `UsbFd.java` (socket client, `SCM_RIGHTS`) and
`UsbTransport.java` (control/bulk). The reference used a Python USB stack, so there is nothing to
compare line by line — this layer is ours by design.

## 7. Coverage, in numbers

* Native engine: **6,697 lines** across 53 Java files. Reference core (`dot11`, `crack`,
  `campaigns`, `chips/rtl8187`, `chips/driver.py`, `chips/products.py`): **16,196 lines**.
* Reference tree as a whole: **288,678 lines** of Python, most of it UI, packaging and drivers
  that have no native counterpart.
* So roughly **41% by line count of the core**, and far less of the whole tree.

## Verdict

**Not 100% accurate, and not claimed to be.** Eight gaps were closed across the two audits: the
PMKID SHA-256 pair field, hashcat-style mangling rules, the CSA/punt path in the twin, the WPS
PIN database, the WEP ExtIV/cipher-length test, the full reference beacon parser (RSN/WPS/label),
the keystone-pair/message-pair/NC-bit hashline emission, and key descriptor versions 1-3 with the
SHA-256 KDF. Still open: every radio family except RTL8187, SAE/PMF/BTM/EAP modules, the on-air
WPS exchange, Pixie Dust, WEP chopchop/fragmentation, FT (WPA*03/WPA*04) hashlines, the
decloak sibling-name sweep and its auth/assoc phase, the twin's SAE downgrade, and the beacon
template's WMM/VHT/country elements. What is machine-checked: every hash, key derivation,
hashline format and the WSC/AES/DH/CMAC vectors. What is review-only: the 802.11 field layouts,
the EAPOL offsets, the RTL8187 register sequence, the PTW vote arithmetic and the PIN formulas.
What is knowingly different: WEP has ARP replay only, the WPS PIN database is applied offline and
Pixie Dust is absent, only one radio family is brought up, and the beacon template is built from
scratch rather than cloned from the AP's own beacon.

Nothing in the engine has been run against real hardware in this workspace; the only execution
environment available here has no JDK, no Android SDK and no radio. Treat the driver paths as
"written from the reference", not "tested on a card".

## 8. The USB/transport and driver audit (this round)

### Why this layer had not been audited

The first two rounds were scoped to what could be executed here: `dot11`, `crack`, `campaigns`.
Everything under `chips/` and `UsbTransport` is Android-side code, and this workspace has no
Android SDK — the JDK alone could not even resolve `android.hardware.usb`, so the driver package
was never compiled, let alone compared. The earlier audit therefore stopped at the chip contract
and said so in the verdict ("only one radio family is brought up"). This round closed that hole by
writing minimal `android.*` stubs (`/tmp/stubs`) and compiling the driver package against them.

### Reference read line by line this round

`chips/rtl8187/{probe,transport,tx,rx,eeprom,mac,rtl8225,rfkill,chan,driver,constants}.py`,
`chips/driver.py`, `chips/products.py`, `device/backend.py`, `device/manager.py`,
`device/watch.py`, `device/zerocd.py`, `chips/rx_reader.py`, and the `wlan/*` model files.

### Defects found and fixed

1. **CCK writes were landing in the OFDM table.** `writePhy` masked the data word to 16 bits, which
   deleted the CCK selector (bit 16) that `writePhyCck` had just set: every CCK PHY write went to
   the wrong table. This alone would leave the receiver uncalibrated on real silicon.
2. **Wrong AGC / gain / TX-power tables for z2** — the old z2 class carried a hand-typed AGC table
   (`0x9E…`) where the kernel uses the BCD `rtl8225_agc` (`0x5E…`), and a 3-column `gain_bg`
   instead of the kernel's 7×3 table indexed as `[4*3]`. All radio tables are now generated
   value-for-value from the reference: `Rtl8225Tables` holds BCD rxgain/agc/gain/threshold/
   tx-power and z2 rxgain/gain_bg/tx-power.
3. **No RF variant probe.** `detect_rf` (RF regs 8/9 → 0x588/0x700 = z2, else BCD) was missing;
   every card got the z2 sequence, so BCD cards (early AWUS036H) were programmed with the wrong
   init block. `Rtl8225` now probes and runs the BCD or z2 bring-up, with each variant's own
   TX-power formula (BCD: `cck/6`, `ofdm/6`, `%6`; z2: direct index plus `txpwr_base` nibbles).
4. **TX power was always zero.** `setChannel` passed `(0,0,0)`. The probe now reads the EEPROM's
   per-channel words (0x16-0x18, 0x3D-0x3E, 0x1B-0x1C) and `txpwr_base` (0x05), and every tune
   uses that channel's `hw_value` (cck low nibble, ofdm high).
5. **MAC was read from the wrong words.** The station address lives at word 0x07, not 1; an
   invalid EEPROM address (multicast/all-zero) now falls back to a random local address, as the
   kernel does.
6. **No rfkill gate.** `is_radio_enabled` (GPIO0 → GPIO1, mask 0x2, or 0x4 for 0x8197/0x8198 with
   EEPROM 0x3B) was absent, so bring-up continued with the switch off. Added, and open() now fails
   with a clear message.
7. **The card never entered monitor mode.** `start()` wrote the station-mode RX_CONF baseline and
   nothing else; mac80211 then issues a second `configure_filter` write that sets MONITOR/CTRL/FCS.
   Without it the chip filters to its own BSS and never hands up other APs' frames. Added
   `configureFilter` and call it after `start`.
8. **`isUp()` was inverted** (`usb != null && !running`): a freshly opened card reported down, and
   a card with a running reader reported up. Now reflects an actually brought-up card.
9. **Close sequence was a single RF stop.** Now the full `rtl8187_stop`: interrupt mask 0, clear
   TX/RX enable, radio stop, analogue block off (`ANAPARAM_OFF`), CONFIG4 VCOOFF.
10. **TX descriptor/alignment.** MOREFRAG (bit 17) is now expressible, and the terminating short
    packet decision uses the bulk-out endpoint's real `wMaxPacketSize` (`maxPacketOut()`) instead
    of a hard-coded 64.
11. **ACKs were discarded without a tally.** The silicon MAC answers ACKs itself, so the reader now
    counts 0xD4 frames (`acksSeen`/`resetAcks` on `WifiChip`) and does not pass them to the sink.

### What is still open after this round

* Only **RTL8187** of the reference's **31 driver families** exists natively, and only its
  8187L path (no 8187B descriptor/endpoint map). `ChipCatalog` advertises 22 USB ids but the
  engine can only bring up the RTL8187 ones — those entries must be treated as "recognised, not
  driven" until their drivers are ported.
* The reference's `products.py` is a **250-product** name catalogue (vendor → model enums); ours
  names 22 USB id pairs, and only the RTL8187 entry has a driver behind it.
* Not audited line by line yet: `wlan/{array,station,lease,channels,dedupe,packet_stats,sink}.py`,
  `chips/rx_reader.py` (the RX/tally schedule), `persist/*`, `id/*`, `portal`.
* Nothing here has run against a card: the driver is compiler-checked and reference-matched only.

## 9. Driver framework and firmware inventory

The chip layer now has the reference's framework pieces, so a new family only has to implement the
driver itself:

* `chips/Driver.java` — the `chips/driver.py` contract: 2.4/5 GHz/DFS channel plans,
  `FakeMacSupport` (UNIMPLEMENTED/NONE/FIXED_MAC/SPOOFABLE), staged `connect(Progress)`, scan-aware
  `setChannel(channel, scan)` and a disconnect callback. `WifiChip` stays the engine's minimal surface.
* `chips/BringUpError.java` — staged failure (`stage`, `detail`) instead of a bare IOException.
* `chips/ChipRegistry.java` — id pair → driver factory; unknown ids raise a `BringUpError` naming
  the family the catalogue says is missing, so an unsupported card is a clear message, not a hang.
* `chips/RxReader.java` — port of `chips/rx_reader.py`: pause/resume for hops, empty-read backoff,
  consecutive-error limit and a fatal/unplug callback. The RTL8187 reader now runs on it.

Firmware: every blob in the reference is mirrored under `app/src/main/assets/firmware/` — 67 files
covering 27 families (only `mt7610u_pcap_body.bin` was missing and has been added). No driver in the
reference needs a blob we do not have.

Driver inventory (reference families, files/lines of Python each):

| family | files | lines |
|---|---|---|
| rtl8187 (ported) | 12 | 3 499 |
| ar9271_v2 | 25 | 5 428 |
| mt7601u | 18 | 5 148 |
| mt76x0u | 17 | 6 963 |
| mt76x2u | 15 | 5 361 |
| mt7921au / mt7925au | 16 / 11 | 3 005 / 2 277 |
| rt2500usb | 10 | 2 274 |
| rt3070 / rt3572 / rt5370 / rt5372 / rt5572 | 15/14/14/14/16 | 3 302 / 7 070 / 3 176 / 3 071 / 7 076 |
| rtl8188eus / rtl8188eus_dkms / rtl8188ftv_dkms | 13/26/37 | 3 675 / 5 281 / 5 553 |
| rtl8192cu / rtl8192fu | 22 / 10 | 9 006 / 4 708 |
| rtl8812au / rtl8812au_dkms / rtl8814au_dkms | 20/20/28 | 5 319 / 3 031 / 9 614 |
| rtl8821au / rtl8821au_dkms / rtl8821cu_dkms | 18/22/29 | 4 285 / 3 347 / 11 696 |
| rtl8822bu / rtl8822bu_dkms / rtl8822cu | 19/29/21 | 24 113 / 11 762 / 6 658 |
| rtl8922au / rtw88_8814au / rtw88_base / rtl88xxau_base | 13/24/9/10 | 7 217 / 18 057 / 1 318 / 1 015 |

**30 of 31 families remain unported: 555 files, ~190 000 lines of Python.** That is a porting
programme, not a patch — each family needs its efuse/EEPROM map, power sequence, BB/RF init tables,
MCU/firmware handshake, TX ring and RX decode. `ChipCatalog` names 22 ids; only 0bda:8187 is driven.

## 10. Running wifi4's drivers unmodified (embedded Python)

Instead of porting 555 files, the app can run them: wifi4's drivers depend only on PyUSB
(`usb.core` / `usb.util`), and Android's only real barrier is that an app cannot enumerate
`/dev/bus/usb` itself. `UsbHostBridge` already forwards a usbfs fd over `opx.usb`, and libusb can
take that fd with `libusb_wrap_sys_device()`. So:

* `app/src/main/python/wifi4/` — chips, dot11, models, crack, errors, LICENSE vendored unchanged
  (615 files, all 31 families, firmware blobs included). Owner consent obtained.
* `app/src/main/python/usb_shim.py` — PyUSB surface on libusb: wrap, bulk/control transfers,
  claim/release, timeouts; installed as `usb.core`/`usb.util` before any driver imports.
* `app/src/main/python/opx_driver_host.py` — driver selection via wifi4's own registry (each
  `chips/<family>/__init__.py` exposes `SUPPORTED_IDS` + `import_driver()`), then connect, channel,
  inject, ACK tally, close. **Measured: 24 claiming packages, 381 vid:pid pairs.**
* `engine/UsbFdHandle.java` — opens the device without claiming the interface (libusb claims it).
* `engine/chips/PythonChip.java` — the Java `Driver`; Chaquopy called by reflection so the JVM build
  needs no Android runtime. Frames, progress and disconnect callbacks cross back into Java.
* `engine/chips/ChipRegistry.java` — Java families first, then `PythonChip.supports(vid,pid)`, so any
  vendored family opens through Python; unknown cards still raise a named `BringUpError`.
* `build.gradle` / `app/build.gradle` — Chaquopy 16.1.0, `buildPython` from `OPX_BUILD_PYTHON`
  (default `/usr/bin/python3`), ABIs arm64-v8a / armeabi-v7a / x86_64.
* `scripts/build-libusb.sh` + `app/src/main/jniLibs/README.md` — builds `libusb-1.0.so` per ABI
  (LGPL-2.1, dynamic). **The .so is not built here: no NDK in this workspace.**

Checked: Java (UsbTransport, UsbFdHandle, chips, rtl8187, dot11/Mac) compiles; both Python files
parse; the registry test loads 24 families / 381 ids. Not checked: a Gradle/Chaquopy build, the
libusb .so, and any real card. No APK was built or released.

Licence: the tree is GPL-3.0 while wifi4 is declared GPL-2.0-only. The owner consented to use, but
`wifi4/pyproject.toml` should be relicensed to `GPL-3.0-or-later` so the combined distribution is
consistent.
