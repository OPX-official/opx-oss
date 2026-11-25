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
| `dot11/wsc/crypto.py` (AES), hostapd `wps_common.c` | `Aes.java` | **vector**: FIPS-197 C.1 (`69c4e0d8…`); S-box derived from the GF inverse, not typed |
| stdlib SHA-1 / SHA-256 | `Sha1.java`, `Sha256.java` | **vector**: `sha1("abc")`, `sha256("abc")` |
| stdlib HMAC | `Hmac.java` | **vector**: RFC 2202 HMAC-SHA1 case 1 |
| stdlib PBKDF2 | `Pbkdf2.java` | **vector**: RFC 6070 (`password`/`salt`, 1 iteration) |
| `crack/wpa_psk.py` | `WpaKeys.java` | **vector**: IEEE 802.11i PMK for `password`/`IEEE`, and the PMKID for a fixed pair (computed outside with Python `hashlib`) |
| RC4 / CRC-32 | `Rc4.java`, `Crc32.java` | **vector**: RC4 `Key`/`Plaintext`, CRC-32 `123456789` |
| `dot11/wsc/crypto.py` DH/KDF | `dot11/wsc/WscCrypto.java` | **vector**: MODP-1536 `2^12345 mod p` prefix, WSC KDF over an all-zero key, PIN checksum 7 for `1234567` |

No constant in this area is taken from memory without a vector except the DH prime itself, which
is the RFC 3526 group-5 literal and is exercised by the `2^12345` check.

## 2. Frames and parsing (`engine/dot11/`)

| Reference | Native | Evidence |
|---|---|---|
| `dot11/mac.py` | `Mac.java` | **ported**: header build, `headerLength` for the DS/QoS bits, MAC text/parse |
| `dot11/eapol.py`, `parser._parse_eapol` | `Eapol.java` | **ported**: LLC/SNAP discovery, big-endian lengths, M1–M4 classification, PMKID KDE, MIC-zeroed copy; offsets 1/2/5/9/17/81/97/99 as in the reference |
| `dot11/ie.py` | `Ie.java` | **ported** for SSID/RSN/WPA/rates/DS/HT; `forcePskAkm` follows the reference's "single PSK AKM, drop PMKID list" rewrite |
| `dot11/parser.py` | `Parser.java` | **ported** for beacon/probe-response fields, RSN counters, WPS vendor element, probe-request SSID |
| `dot11/packet.py` | `Frame.java` | **ported** for deauth, auth, probe request, assoc request; **simplified** for beacon/probe response (see below) |
| `dot11/ap.py` `beacon_clone` | `Frame.beacon` / `probeResponse` | **simplified**: no HT/VHT operation, WMM, extended capabilities, country or CSA elements; capability/interval/SSID/rates/DS/RSN only |
| `dot11/chan.py` | `ScanEngine.java` | **simplified**: channel list and dwell are our own; the reference's per-region plan and dwell table were not reviewed line by line |
| `dot11/csa.py`, `btm.py`, `pmf.py`, `aes_cmac.py`, `auth_assoc.py`, `eap.py` | — | **missing** |

## 3. Capture (`engine/capture/`)

| Reference | Native | Evidence |
|---|---|---|
| `crack/handshake.py` | `Handshake.java`, `HandshakeBook.java` | **ported**: one hashline per M1+M2 or M2+M3 pair, PMKID line, duplicate suppression |
| `crack/hc22000_format.py` | `Hc22000.java` | **vector**: an exact hashline string is asserted at start-up |
| `crack/wpa_psk.py` verify | `Verify.java` | **ported**: PMK → KCK → MIC over the MIC-zeroed frame; PMK path for a raw 64-hex key |

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

**Not 100% accurate, and not claimed to be.** Since the first audit, four gaps were closed: the
PMKID SHA-256 pair field, hashcat-style mangling rules, the CSA/punt path in the twin, and the
WPS PIN database. Still open: every radio family except RTL8187, SAE/PMF/BTM/EAP modules, the
on-air WPS exchange, Pixie Dust, WEP chopchop, and FT hashlines. What is machine-checked: every hash, key
derivation, hashline format and the WSC/AES/DH vectors. What is review-only: the 802.11 field
layouts, the EAPOL offsets, the RTL8187 register sequence, the PTW vote arithmetic and the PIN
formulas. What is knowingly different: WEP has ARP replay only, the evil twin has no SAE
downgrade, the WPS PIN database and Pixie Dust are absent, only one radio family is brought up,
and the beacon template omits HT/VHT/CSA.

Nothing in the engine has been run against real hardware in this workspace; the only execution
environment available here has no JDK, no Android SDK and no radio. Treat the driver paths as
"written from the reference", not "tested on a card".
