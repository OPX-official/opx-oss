# OPX OSS — নেটিভ রিরাইট প্ল্যান

এই ফাইলটাই কাজের চুক্তি। প্রতিটি আইটেম আমাদের নিজের কোডে নতুন করে লেখা হবে, আর লেখার **আগে** সেই আইটেম আজ কোথায় কীভাবে কাজ করছে সেটা বাধ্যতামূলকভাবে পড়ে-বুঝে নেওয়া হবে — তারপর হুবহু একই লজিক ও আচরণ রেখে নিজের কোডে বানানো হবে।

---

## ০. লক্ষ্য

- অ্যাপ, ইঞ্জিন, স্ক্রিপ্ট, বাইনারি, লাইব্রেরি, USB স্ট্যাক — **সব** আমাদের নিজের হাতে লেখা কোডে।
- বাইরের কোনো সোর্স ডাউনলোড, প্রি-বিল্ট বাইনারি/লাইব্রেরি, প্যাকেজ-ট্রি, ফর্ক — কিছুই নেই।
- **ব্যতিক্রম (অনুমোদিত):** ওয়াই-ফাই চিপের **ক্লোজড ফার্মওয়্যার** (যেমন AR9271, RT5370, mt76, RTL8xxxU-র ব্রিং-আপে লাগা ভেন্ডর ব্লব) বাইরে থেকে ব্যবহার করা যাবে। এটা কার্ডের নিজের ফার্মওয়্যার, লাইব্রেরি নয়; বাকি সব (কোড, Kus, অ্যাপ, ইঞ্জিন, USB স্ট্যাক) নিজের হাতে লেখা।
- রানটাইম ডাউনলোড নেই: ইনস্টল করার পর অ্যাপ নিজেই নিজের সবকিছু চালায়।
- GitHub Actions-এ এক ধাপের Gradle বিল্ড, কয়েক মিনিটে APK রিলিজ।
- USB সিস্টেম (কানেকশন, সকেট, ফাইল ডেসক্রিপ্টর, ব্রিজ লজিক) **একদম অটুট**।
- পুরনো রানটাইমের নাম, পাথ বা নির্ভরতা শিপড সিস্টেমে শূন্য।

## ১. কড়া নিয়ম

1. পুরনো রানটাইমের নাম/পাথ/নির্ভরতা কোনো শিপড ফাইল, স্ক্রিপ্ট, ডক বা আউটপুটে থাকবে না।
2. USB সিস্টেমের কোনো অংশ ভাঙা, সরানো বা "উন্নত" করা যাবে না — কেবল তার নিচের স্তর নিজের কোডে বসবে।
3. কোনো পরামর্শ, বিকল্প-স্থাপত্য বা ফালতু বেস বিল্ড নেই — শুধু এই প্ল্যানের কাজ।
4. যেকোনো কোড লেখার আগে সংশ্লিষ্ট সারির "আগে কোথায় দেখব" কলামের ফাইলগুলো পড়া বাধ্যতামূলক। পড়া শেষ হলে কোডে সেই আচরণের (ফ্রেম অফসেট, কমান্ড নাম, ইভেন্ট ফিল্ড, ফাইল ফরম্যাট) হুবহু তালিকা মিলিয়ে নেওয়া হবে।
5. **ফার্মওয়্যার ব্যতিক্রম:** যে চিপ নিজের সোর্স ছাড়া চলে না এবং ব্রিং-আপে ভেন্ডর ফার্মওয়্যার ব্লব লাগে, তার ফার্মওয়্যার বাইরে থেকে আনা ও শিপ করা যাবে। ফার্মওয়্যার একটি ডিভাইস ব্লব — আমরা তার আশপাশের সব ড্রাইভার কোড নিজেরাই লিখব।

## ২. প্ল্যান পড়ার নিয়ম

প্রতিটি টেবিলে তিন ঘর:

| ঘর | মানে |
|---|---|
| **আইটেম** | যা বানানো হবে |
| **আগে কোথায় দেখব** | বানানোর আগে যে ফাইল/রেফারেন্স পড়ে বর্তমান আচরণ বুঝতে হবে |
| **নিজের কোডে কীভাবে** | আমাদের কোডে কোন ক্লাস/ফাইলে, কী লজিকে বানানো হবে |

"নিজের কোড" = অ্যাপের ভেতরের **Java (JDK 17, Android SDK)**। নতুন কোনো ভাষা, ইন্টারপ্রেটার বা রানটাইম যোগ হবে না; ইঞ্জিন অ্যাপের প্রসেসেই চলে।

পরিভাষা: **রেফারেন্স কপি** = workspace-এর `references/` ফোল্ডারে রাখা পুরনো সোর্সের পড়ার কপি (রিপোতে কমিট হয় না, শিপ হয় না)। **আজকের কোড** = এই রিপোর ভেতরের বর্তমান ফাইল।

---

## ৩. আজকের সিস্টেম কোথায় আছে (current state map)

| স্তর | আজকের ফাইল | কাজ |
|---|---|---|
| অ্যাপ UI/সার্ভিস | `app/src/main/java/com/opx/oss/{MainActivity,OpxService,HomeFragment,WifiFragment,SettingsFragment,HandshakesFragment,CredentialsFragment,LogsFragment}.java` | স্ক্রিন, ফোরগ্রাউন্ড সার্ভিস |
| ইঞ্জিন ব্রিজ | `.../WifiBridge.java` | প্রসেস স্পন, লাইন প্রোটোকল, ইভেন্ট থ্রেড |
| রানটাইম/ইনস্টল | `.../OpxRuntime.java`, `.../RuntimeDownloadManager.java`, `.../IntroActivity.java`, `.../InstallLogDialog.java` | প্রিফিক্স এক্সট্রাক্ট, প্যাকেজ ইনস্টল, ডাউনলোড |
| USB ব্রিজ | `.../UsbHostBridge.java`, `.../UsbIds.java`, `.../UsbAttachReceiver.java`, `.../UsbAutoConnect.java`, `.../UsbDialog.java` | `opx.usb` সকেট, পারমিশন, SCM_RIGHTS FD |
| ইঞ্জিন | `tools/engine/opx_engine.py` (+ `tools/engine/opx_engine`) | JSON লাইন প্রোটোকল, স্ক্যান/অ্যাটাক/ক্র্যাক |
| USB ক্লায়েন্ট শিম | `tools/usb/opx-usb`, `tools/usb/opx_usb.py` | সকেটে `open` → `OPX_USB_FD` |
| রেনাম/প্যাচ | `tools/patch-wifi4.py`, `tools/scrub-names.py`, `tools/rebrand-runtime.py` | নাম ম্যাপিং, FD env নাম |
| বেস/প্যাকেজ বিল্ড | `tools/build-base.sh`, `tools/build-packages.sh`, `tools/fetch-packages.sh`, `tools/select-debs.py`, `tools/prune-*.py`, `tools/patch-*.py`, `tools/make-index.py`, `tools/merge-index.py`, `tools/republish-archive.sh` | ২-ঘণ্টার প্যাকেজ পাইপলাইন, ইনডেক্স |
| CI | `.github/workflows/build.yml`, `.github/workflows/packages.yml` | APK বিল্ড, প্যাকেজ বিল্ড+রিলিজ |
| আর্টিফ্যাক্ট ফরম্যাট | `bootstrap-<arch>.zip` + `SYMLINKS.txt` + `payload/all/*.tar.gz` | অ্যাপের ইনস্টল ফরম্যাট |

ইঞ্জিনের আজকের পাবলিক চুক্তি (হুবহু রাখতে হবে): কমান্ড `ping`, `devices`, `status`, `config`, `scan`, `targets`, `attack` (kind: `deauth`, `pmkid`, `wps`, `wep`, `eviltwin`, `captive`, `decloak`, `known_decloak`, `verify`), `deauth_client`, `deauth_all`, `silence`, `chop`, `verify_psk`, `attack_status`, `stop_attack`, `captures`, `crack_tools`, `crack`, `stop_crack`, `stop`; ইভেন্ট `ready`, `devices`, `status`, `config`, `network`, `scan_done`, `capture_saved`, `targets`, `attack_started`, `attack_log`, `attack_progress`, `attack_done`, `psk_verified`, `attack_status`, `captures`, `crack_tools`, `crack_started`, `crack_progress`, `crack_done`, `stopped`, `error`।

---

## ৪. আইটেম-ভিত্তিক প্ল্যান

### ৪.১ USB স্ট্যাক — সবার আগে, কারণ এটা অটুট থাকতে হবে

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| USB সকেট ও কমান্ড | `app/.../UsbHostBridge.java` (ক্লাস ডক, `listen()`, `serve()`, `handle()`): `SOCKET_NAME="opx.usb"`, কমান্ড `list`/`open`, রিপ্লাই JSON ফিল্ড | **অপরিবর্তিত**। নতুন কোড এটার ওপর বসবে, ভেতরে ঢুকবে না |
| FD হ্যান্ডঅফ | একই ফাইলে `ParcelFileDescriptor.dup`, `setFileDescriptorsForSend` (SCM_RIGHTS), `fileDescriptor()` | **অপরিবর্তিত**। ইঞ্জিনও এই একই পথে ডেসক্রিপ্টর নেবে |
| পারমিশন ও ডিটাচ | একই ফাইলে `ACTION_PERMISSION`, `UsbAttachReceiver`, `UsbAutoConnect` | **অপরিবর্তিত** |
| ডিভাইস ক্যাটালগ | `.../UsbIds.java` + `res/raw/usb_ids.json`, `tools/gen-usb-ids.py` | ক্যাটালগ ফরম্যাট একই থাকবে; জেনারেটর নিজের স্ক্রিপ্ট হিসেবেই থাকবে |
| USB ট্রান্সপোর্ট (libusb-এর বদলে) | `tools/usb/opx_usb.py` (আজকের FD ক্লায়েন্ট: `OPX_USB_FD`, `OPX_USB_DEVICE`, `pass_fds`), `references/.../device/backend.py`, `.../device/manager.py` (ডিভাইস খোলা/হ্যান্ডেল ম্যানেজমেন্ট) | নতুন `app/.../engine/UsbTransport.java`: `UsbDeviceConnection.controlTransfer/bulkTransfer`, এন্ডপয়েন্ট ম্যাপ, টাইমআউট/রিট্রাই, হট-আনপ্লাগ ডিটেকশন। ডিভাইস খুলবে `UsbHostBridge`-এর ইন-প্রসেস এন্ট্রি দিয়ে — ঠিক যে পথে সকেট ক্লায়েন্ট পায় |
| `OPX_USB_FD` চুক্তি | `app/.../OpxRuntime.java` (`ensureUsbFdVariable`), `tools/usb/opx_usb.py`, `tools/patch-wifi4.py` | নাম ও সেমান্টিক্স অপরিবর্তিত: `open` যে FD দেয় সেটাই `OPX_USB_FD`; নতুন `engine/UsbFd.java` শুধু নাম/লগ/ডায়াগনস্টিক ধরে রাখে। libusb স্ট্রিং-প্যাচ ফাংশনটি কাজ শেষে বাদ |
| bulk I/O অপারেশন | `references/.../chips/driver.py`, `.../chips/rx_reader.py` | `UsbTransport`-এ রিডার থ্রেড + রিং বাফার, TX কিউ; প্রতি চিপ একই ইন্টারফেস ব্যবহার করবে |

### ৪.২ ইঞ্জিন কমান্ড/ইভেন্ট লেয়ার

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| লাইন প্রোটোকল সার্ভার | `tools/engine/opx_engine.py` — ফাইলের শুরুর কমান্ড-ডক, `emit()`, `plain()`, ডিসপ্যাচ অংশ | নতুন `app/.../engine/NativeEngine.java`: একই কমান্ড টেবিল, একই ইভেন্ট নাম/ফিল্ড, `ensure_ascii` মিলিয়ে JSON আউটপুট |
| কমান্ড ↔ UI চুক্তি | `app/.../WifiBridge.java` (send/readEvents), `WifiFragment.java`, `AttackMonitorDialog.java` | `WifiBridge` প্রসেস স্পন বাদ দিয়ে `NativeEngine`-এ ইন-প্রসেস কল করবে; পাবলিক API ও ইভেন্ট কলব্যাক অপরিবর্তিত |
| অ্যাডাপ্টার-ওয়েট/রিট্রাই | `opx_engine.py`-এর `ADAPTER_WAIT_SECONDS`, `DEVICES_WAIT_SECONDS`, `ATTACH_RETRY_DELAYS` | একই ধৈর্য/রিট্রাই মান, একই কারণ-মেসেজ |
| এরর রিপোর্টিং | `opx_engine.py` (`error` ইভেন্ট, ব্যর্থতায় প্রসেস বাঁচিয়ে রাখা) | `NativeEngine` কখনো মরে না; প্রতিটি ব্যর্থতা `error` ইভেন্ট |
| টার্গেট/অ্যাটাক যোগ্যতা | `opx_engine.py`-এর `targets` অংশ + `references/.../campaigns/campaign.py` (`can_run`-জাতীয় যোগ্যতা) | `TargetBook.java`: প্রতি AP-র জন্য যোগ্য অ্যাটাক তালিকা, একই নিয়মে |

### ৪.৩ ৮০২.১১ কোডেক

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| ফ্রেম বিল্ড/পার্স | `references/.../dot11/packet.py`, `parser.py`, `ie.py`, `mac.py` | `engine/dot11/{Frame,Parser,Ie,Mac}.java` — বাইট অর্ডার ও অফসেট হুবহু |
| beacon/probe | `.../dot11/ap.py`, `probe.py`, `chan.py`, `csa.py` | `engine/dot11/{Beacon,Probe,Channel,Csa}.java` |
| auth/assoc | `.../dot11/auth_assoc.py`, `btm.py`, `pmf.py` | `engine/dot11/{AuthAssoc,Btm,Pmf}.java` |
| deauth/disassoc | `.../dot11/deauth.py` | `engine/dot11/Deauth.java` (reason codes সহ) |
| WPA/RSN IE | `.../dot11/rsn.py`, `wpa.py`, `eapol.py`, `eap.py`, `aes_cmac.py` | `engine/dot11/{Rsn,Wpa,Eapol,Eap,AesCmac}.java` |
| WEP ফ্রেম | `.../dot11/wep/*` | `engine/dot11/wep/*.java` |
| WSC/WPS ফ্রেম | `.../dot11/wsc/*` | `engine/dot11/wsc/*.java` |

### ৪.৪ ক্রিপ্টো (নিজের হাতে, কোনো বাইরের লাইব্রেরি নয়)

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| SHA-1/MD5/HMAC/CRC32 | `references/.../dot11/aes_cmac.py`, `crack/wpa_psk.py` (কোথায় কী হ্যাশ হয়) | `engine/crypto/{Sha1,Md5,Hmac,Crc32}.java` |
| PBKDF2 + PRF/PTK | `.../crack/wpa_psk.py`, `.../dot11/eapol.py`, `.../crack/handshake.py` | `engine/crypto/{Pbkdf2,Ptk,WpaKey}.java` — ৪০৯৬ ইটারেশন, nonce/ANonce/SNonce/MAC অর্ডার হুবহু |
| RC4 / WEP কী স্ট্রিম | `.../crack/wep.py`, `.../dot11/wep/*` | `engine/crypto/Rc4.java`, `engine/crypto/WepKey.java` |
| WPS DH/AES | `.../dot11/wsc/*`, `campaigns/wps/*` | `engine/crypto/{Dh,Aes,WpsKdf}.java` (BigInteger-ভিত্তিক) |

### ৪.৫ চিপসেট ড্রাইভার

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| ড্রাইভার ইন্টারফেস | `references/.../chips/driver.py`, `chips/products.py`, `chips/rx_reader.py`, `chips/log_trace.py` | `engine/chips/WifiChip.java` (init, setChannel, tx, rxLoop, mac) |
| Atheros | `.../chips/ar9271_v2/*` | `engine/chips/Ar9271.java` |
| Ralink | `.../chips/rt5370`, `rt5372`, `rt3070`, `rt3572`, `rt5572`, `rt2500usb` | `engine/chips/Rt2x00.java` + প্রতি মডেলের প্রোফাইল |
| Realtek USB | `.../chips/rtl8188eus`, `rtl8192cu`, `rtl8812au`, `rtl8821au`, `rtl8822bu`, `rtl88xxau_base`, `rtw88_base` ইত্যাদি | `engine/chips/Rtl8xxxu.java` + প্রোফাইল টেবিল |
| MediaTek | `.../chips/mt7601u`, `mt76x0u`, `mt76x2u`, `mt7921au`, `mt7925au` | `engine/chips/Mt76.java` + প্রোফাইল |
| ZeroCD স্টাব | `.../device/zerocd.py`, `.../device/watch.py` | `engine/chips/ZeroCd.java` — স্টাব থেকে আসল ডিভাইসে স্যুইচ |
| চিপ ফার্মওয়্যার | `references/.../chips/ar9271_v2/{firmware,htc}.py`, `.../chips/mt76*/`, `.../chips/rtl8*` — কোন চিপে কোন ব্লব লাগে | `engine/chips/firmware/` — **অনুমোদিত ব্যতিক্রম:** ভেন্ডর ফার্মওয়্যার ব্লব (যেমন `htc_9271.fw`) বাইরে থেকে আনা ও `assets/`-এ শিপ করা যাবে; লোড/ভেরিফাই/রিসেটের কোড আমাদের নিজের |
| চিপ উদাহরণ-ট্রেস | `.../chips/log_trace.py` | ড্রাইভার রেজিস্টার সিকোয়েন্স টেস্ট-ট্রেস হিসেবে ব্যবহার |

### ৪.৬ স্ক্যান

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| চ্যানেল হপ + প্যাসিভ স্ক্যান | `references/.../wlan/*`, `.../dot11/chan.py` | `engine/ScanEngine.java` |
| AP মডেল ও ডিডুপ | `.../models/*` | `engine/AccessPoint.java`, `engine/ScanBook.java` |
| প্যাসিভ ক্যাপচার সেভ | `.../vault/*` | `engine/Vault.java` (নিচে ৪.৮) |

### ৪.৭ অ্যাটাক

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| ক্যাম্পেইন বেস | `.../campaigns/campaign.py`, `treelog.py` | `engine/attacks/Campaign.java` (start/stop/progress/log/done) |
| deauth | `.../campaigns/deauth.py` | `engine/attacks/DeauthCampaign.java` |
| PMKID | `.../campaigns/pmkid.py` | `engine/attacks/PmkidCampaign.java` |
| WPS PBC/PIN | `.../campaigns/pbc.py`, `pin.py`, `.../campaigns/wps/*` | `engine/attacks/{Pbc,Pin}Campaign.java` |
| WEP | `.../campaigns/wep/*`, `dot11/wep/*` | `engine/attacks/WepCampaign.java`, `engine/attacks/ChopChop.java` |
| decloak | `.../campaigns/decloak.py` | `engine/attacks/DecloakCampaign.java` |
| probe/known-decloak | `.../campaigns/probe/*` | `engine/attacks/ProbeCampaign.java` |
| verify | `.../campaigns/verify/*` | `engine/attacks/Verify.java` |
| evil twin | `.../campaigns/eviltwin/*` | `engine/attacks/EvilTwin.java` |
| captive portal | `.../campaigns/eviltwin/*` + `.../scripts/*` | `engine/attacks/CaptivePortal.java` (নিজের HTTP সার্ভার, টেমপ্লেট আমাদের) |

### ৪.৮ ভল্ট ও ক্যাপচার ফরম্যাট

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| ভল্ট স্ট্রাকচার | `references/.../vault/*`, `.../persist/*` | `engine/Vault.java` — অ্যাপের `filesDir/var`-এ, পুরনো লেআউট মেনে |
| `.hc22000` লেখা | `.../crack/hc22000_format.py`, `.../crack/handshake.py` | `engine/capture/Hc22000.java` — হেডার/ফিল্ড ক্রম হুবহু |
| ক্রেডেনশিয়াল স্টোর | `.../persist/*`, `app/.../CredentialsFragment.java`, `CredentialAdapter.java` | `engine/Credentials.java` + বিদ্যমান UI অপরিবর্তিত |
| ডিফল্ট/SSID পাসওয়ার্ড তালিকা | `.../crack/default_psks.py`, `passwords.py`, `.../crack/assets/*` | `engine/crack/Wordlists.java`-এ সরাসরি এমবেড (আলাদা ফাইল ডাউনলোড নেই) |

### ৪.৯ ক্র্যাকার

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| CPU ক্র্যাকার | `references/.../crack/wpa_psk.py`, `.../crack/handshake.py` | `engine/crack/CpuCracker.java` — প্রগ্রেস ইভেন্ট একই |
| `crack_tools` তালিকা | `tools/engine/opx_engine.py`-এর `crack_tools` অংশ, `app/.../CrackDialog.java` | এখন শুধু `cpu_crack`; hashcat কার্ড ও `.hc22000`-এক্সপোর্ট কল UI থেকে বাদ |
| WEP ক্র্যাক | `.../crack/wep.py` | `engine/crack/WepCracker.java` |

### ৪.১০ অ্যাপ (রানটাইম বাদ, ব্রিজ ইন-প্রসেস)

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| ফোরগ্রাউন্ড সার্ভিস | `app/.../OpxService.java` (স্টার্ট/স্টপ, নোটিফিকেশন, ইঞ্জিন লাইফসাইকেল) | রানটাইম ইনস্টল ধাপ বাদ; `NativeEngine` লাইফসাইকেল একই |
| ইনস্ট্রো/ইনস্টল ফ্লো | `IntroActivity.java`, `InstallLogDialog.java`, `RuntimeDownloadManager.java` | ইনস্টল-স্লাইড ও ডাউনলোড বাদ; প্রথম রানে সরাসরি ব্যবহারযোগ্য |
| হোম/সেটিংস | `HomeFragment.java`, `SettingsFragment.java` | প্রিফিক্স/রানটাইম সারি বাদ, USB ও ইঞ্জিন স্টেট থাকবে |
| WiFi/অ্যাটাক স্ক্রিন | `WifiFragment.java`, `AttackMonitorDialog.java`, `EvilTwinOptionsDialog.java`, `CaptivePortalDialog.java` | ইভেন্ট নাম অপরিবর্তিত, তাই কোড প্রায় অপরিবর্তিত |
| হ্যান্ডশেক/ক্রেডেনশিয়াল | `HandshakesFragment.java`, `HandshakeAdapter.java`, `CredentialsFragment.java` | ভল্ট নিজের কোডে, UI অপরিবর্তিত |
| লগ | `LogsFragment.java`, `AppLog.java` | অপরিবর্তিত |

### ৪.১১ ডিলিট/ট্রিম (নতুন কোড সবুজ হওয়ার পর, ধাপে ধাপে)

`OpxRuntime.java`, `RuntimeDownloadManager.java`, `InstallLogDialog.java`, `IntroActivity`-র ইনস্টল অংশ, `tools/engine/*`, `tools/usb/*`, `tools/build-base.sh`, `tools/build-packages.sh`, `tools/fetch-packages.sh`, `tools/select-debs.py`, `tools/prune-*.py`, `tools/patch-bootstraps.py`, `tools/patch-properties.py`, `tools/patch-wifi4.py`, `tools/rebrand-runtime.py`, `tools/make-index.py`, `tools/merge-index.py`, `tools/republish-archive.sh`, `.github/workflows/packages.yml`, `commons-compress` ডিপেন্ডেন্সি, `res/raw`-এর বুটস্ট্র্যাপ/পেলোড এন্ট্রি।

থাকবে: `tools/scrub-names.py` (গেট হিসেবে), `tools/gen-usb-ids.py`, `tools/build-packages.sh`-এর বদলে নতুন `tools/check-artifacts.sh`।

### ৪.১২ বিল্ড, CI ও রিলিজ

| আইটেম | আগে কোথায় দেখব | নিজের কোডে কীভাবে |
|---|---|---|
| APK বিল্ড | `.github/workflows/build.yml` (JDK, Gradle, keystore, versionCode) | অপরিবর্তিত থাকবে, শুধু ক্যাশে/স্টেপ পরিষ্কার |
| প্যাকেজ বিল্ড | `.github/workflows/packages.yml` | বাদ; তার জায়গায় `build.yml`-এ একই রানে `assembleRelease` + APK আপলোড |
| রিলিজ | `packages.yml`-এর release স্টেপ, `merge-index.py --verify-release` | নতুন `tools/release.sh`: APK + `sha256` ফাইল → `latest`/`v*` রিলিজ, রিলিজ রিড-ব্যাক যাচাই |
| নাম-গেট | `tools/scrub-names.py --check` | পুরো রেপো + APK-র ভেতরের `assets`/`res` + বিল্ড আউটপুটে চালানো হবে |
| স্ক্রিপ্ট বিল্ড | `tools/build-packages.sh` (ইনভেন্টরি অংশ) | `tools/check-artifacts.sh`: APK-তে ইঞ্জিন ক্লাস, USB সিম্বল, নিষিদ্ধ নাম — তিনটাই চেক |

---

## ৫. USB গার্ড — যা হুবহু অপরিবর্তিত থাকবে

`UsbHostBridge.java`-এ এই সিম্বল/আচরণগুলোর কোনোটা বদলাবে না; প্রতিটা ফেজের শেষে ডিফে এগুলো না-বদলানো যাচাই হবে:

- `SOCKET_NAME = "opx.usb"`, `LocalServerSocket` বাইন্ড ও ৫×১৫০ms রিট্রাই
- কমান্ড `list` ও `open`, রিপ্লাই JSON ফিল্ড, `error` রিপ্লাই
- `ParcelFileDescriptor.dup(...)` + `setFileDescriptorsForSend` (SCM_RIGHTS) — FD পাঠানোর পথ
- `UsbManager` পারমিশন রিকোয়েস্ট, `ACTION_PERMISSION`, `UsbAttachReceiver`, `UsbAutoConnect`
- `OPX_USB_FD` নাম ও অর্থ (ক্লায়েন্ট ওপেন করা ডেসক্রিপ্টর এই নামেই পায়)
- `UsbIds` ক্যাটালগ ও `usb_ids.json` ফরম্যাট
- ডিটাচে হ্যান্ডল রিলিজ, সকেট রিবাইন্ড, foreign-UID রিজেক্ট

নতুন যা যোগ হবে: `engine/UsbTransport.java`, `engine/UsbFd.java` — এরা উপরের পথ **ব্যবহার** করবে, বদলাবে না।

---

## ৬. ফেজ ও exit criteria

প্রতিটা ফেজ শেষে: `./gradlew assembleDebug` সবুজ, `tools/check-artifacts.sh` পাস, সংক্ষিপ্ত রিপোর্ট।

| ফেজ | কাজ | exit criteria |
|---|---|---|
| **M1** | `NativeEngine` + `UsbTransport` + `UsbFd` + `devices`/`status`/`config`/`ping`; `WifiBridge` ইন-প্রসেস | রানটাইম ছাড়া অ্যাপ চালু, `devices` আসল ডিভাইস দেখায়, USB গার্ড ডিফ ক্লিন |
| **M2** | `dot11` + `crypto` + `ScanEngine` + `targets` + `Vault` + `.hc22000` রাইটার | স্ক্যান তালিকা আগের মতোই, ক্যাপচার সেভ হয়, ফরম্যাট বাইট-মিল |
| **M3** | deauth/decloak/probe + `attack_*` ইভেন্ট + `captures` | অ্যাটাক মনিটর ডায়ালগ আগের মতোই চলে |
| **M4** | PMKID + `verify_psk` + `CpuCracker` + `crack_*` | হ্যান্ডশেক ক্র্যাক হয়, প্রগ্রেস ইভেন্ট একই |
| **M5** | WEP + WPS + evil twin + captive portal | পাঁচটি ক্যাম্পেইনই `attack_done` পর্যন্ত |
| **M6** | বাকি চিপসেট প্রোফাইল + রানটাইম কোড/ওয়ার্কফ্লো ডিলিট + README/ডক্স + রিলিজ | রেপোতে রানটাইম কোড শূন্য, `assembleRelease` + রিলিজ আপলোড সবুজ |

## ৭. প্রতি ফেজে যাচাই

1. `./gradlew assembleDebug` (এবং M6-এ `assembleRelease`) — exit status সরাসরি দেখা হবে, পাইপে লুকানো হবে না।
2. `python3 tools/scrub-names.py --check .` — শিপড ট্রি ও APK অ্যাসেটে নিষিদ্ধ নাম শূন্য।
3. `tools/check-artifacts.sh` — ইঞ্জিন ক্লাস, USB সিম্বল (`opx.usb`, `OPX_USB_FD`, `SCM_RIGHTS` পথ), ও নিষিদ্ধ নাম — তিনটাই।
4. `git diff` গার্ড: `UsbHostBridge.java`-এ উপরের সিম্বলগুলোর কোনো পরিবর্তন নেই।
5. ডিভাইসে (তুমি দেখবে): পারমিশন ডায়ালগ → `devices` → `open` → bulk I/O; লগে `OPX_USB_FD` লাইন।

## ৮. নাম-নীতি

- শিপড কোড, পাথ, স্ক্রিপ্ট, ডক, APK অ্যাসেট — কোথাও পুরনো রানটাইমের নাম/পাথ থাকবে না।
- রেফারেন্স কপি (`references/`) কেবল পড়ার জন্য, রিপোতে কমিট হয় না, বিল্ডে যায় না।
- কোনো নতুন স্ক্রিপ্ট/ফাইলের নামে বা কমেন্টেও পুরনো রানটাইমের উল্লেখ নেই; সব UK-ইংরেজি/বাংলা নিউট্রাল নাম।

## ৯. ঝুঁকি ও হ্যান্ডলিং

| ঝুঁকি | হ্যান্ডলিং |
|---|---|
| চিপসেট-ভেদে USB কন্ট্রোল সিকোয়েন্স আলাদা | প্রতি চিপ প্রোফাইল আলাদা ক্লাসে; `log_trace` ট্রেস দিয়ে সিকোয়েন্স মিলিয়ে টেস্ট |
| রেডিও ক্যালিব্রেশন | ইইপ্রম/কার্ড থেকেই পড়া হয় (RT8187) বা ফার্মওয়্যার-চালিত চিপে ড্রাইভারের রুটিন থেকে |
| চিপ ফার্মওয়্যার ব্লব | **অনুমোদিত:** ভেন্ডর ফার্মওয়্যার ব্লব বাইরে থেকে এনে APK-র `assets/`-এ শিপ করা হবে, লাইসেন্স সহ; অন্য কোনো বাইরের লাইব্রেরি/বাইনারি শিপ হবে না |
| ফ্রেম অফসেট ভুল হলে নীরব ব্যর্থতা | `dot11` টেস্ট ভেক্টর: পুরনো কোডের আউটপুট বাইট ধরে গোল্ডেন তুলনা |
| USB রিগ্রেশন | প্রতিটা ফেজে USB গার্ড ডিফ + ডিভাইস চেকলিস্ট |
| ভুল করে টার্গেট অ্যাটাক | যোগ্যতা `TargetBook`-এ, UI-তে নয় — আগের মতোই |

---

## ১০. অগ্রগতি (সবসময় হালনাগাদ, GitHub-এ পুশ করা থাকে)

### M1 — সম্পন্ন (ডিভাইস-টেস্ট বাদে)

- [x] **ক্লিন স্লেট:** চলন্ত `Packages` রান #43 (`45615454874`) বাতিল; সব ১২টি রিলিজ ও ট্যাগ (`latest`, `v0.2.0-alpha.*`, `v0.1.0-alpha.*`) মুছে ফেলা — এখন শূন্য।
- [x] **পুরনো বাইরের-নির্ভর পাইপলাইন বাদ:** `.github/workflows/packages.yml`, `tools/build-base.sh`, `tools/build-packages.sh`, `tools/fetch-packages.sh`, `tools/make-index.py`, `tools/merge-index.py`, `tools/select-debs.py`, `tools/patch-bootstraps.py`, `tools/patch-properties.py`, `tools/patch-wifi4.py`, `tools/prune-foreign.py`, `tools/prune-size.py`, `tools/rebrand-runtime.py`, `tools/republish-archive.sh`, `tools/scrub-names.py`, পুরনো GPU-ক্র্যাকার রেসিপি ফোল্ডার, `tools/engine/`, `tools/usb/`।
- [x] **`engine/UsbFd.java`** — `opx.usb` ক্লায়েন্ট: `list`/`open`, SCM_RIGHTS ডেসক্রিপ্টর গ্রহণ, `OPX_USB_FD` নাম ধরে রাখা।
- [x] **`engine/UsbTransport.java`** — নিজের USB ট্রান্সপোর্ট: `bulkIn`/`bulkOut`/`control`, ইন্টারফেস claim, পারমিশন-অপেক্ষা, close — libusb-এর কোনো অংশ নেই।
- [x] **`engine/NativeEngine.java`** — ইন-প্রসেস লাইন প্রোটোকল: `ready`/`ping`/`status`/`config`/`devices`/`targets`/`captures`/`attack_status`/`stop`/`error`, একক ওয়ার্কার থ্রেড।
- [x] **`WifiBridge` রিওয়্যার** — প্রসেস স্পন, stdin/stdout থ্রেড, shim-probe, রিস্টার্ট-লুপ বাদ; এখন `NativeEngine.start()` + `send()`, ইভেন্ট মেইন থ্রেডে, USB ব্রিজ প্রি-ফ্লাইট ও ডায়াগনস্টিক অপরিবর্তিত।
- [x] **ইনস্টল-গেট বাদ** — `OpxService.startEngine()` আর ডাউনলোড/আনপ্যাক/`probe` করে না, সরাসরি `STATE_READY`; `MainActivity` প্রথম রানেই সার্ভিস চালু করে, ইনস্ট্রো ডাউনলোড-স্ক্রিন স্কিপ।
- [x] **বিল্ড সবুজ (রিওয়্যার সহ):** রান **#45623741808** (`3203bfa`) সফল।

- [x] **বিল্ড সবুজ:** GitHub Actions `Build` রান **#45622543773** (`d81858e`) — নতুন ইঞ্জিন ক্লাস কম্পাইল ও APK তৈরি সফল; আগের দুটি কম্পাইল এরর (ফাইন্ড টাইপ, JSONException) ঠিক করা হয়েছে।
- [x] **রিলিজ বন্ধ:** `.github/workflows/build.yml` থেকে দুটি রিলিজ-স্টেপ বাদ, `latest` রিলিজ ও ট্যাগ মুছে ফেলা — প্রজেক্ট শেষ না হওয়া পর্যন্ত Releases খালি, শুধু রান-আর্টিফ্যাক্ট।
- [ ] ডিভাইসে `devices` → `open` → bulk I/O পরীক্ষা (ফোনে দেখতে হবে)।

### M2 — চলছে (আনুমানিক ৮৫%)

- [x] **ক্রিপ্টো নিজের হাতে:** `engine/crypto/{Sha1,Sha256,Hmac,Pbkdf2,Rc4,Crc32}.java` — কোনো বাইরের হ্যাশ লাইব্রেরি নেই।
- [x] **WPA কী-স্কেডুল:** `engine/crypto/WpaKeys.java` — PMK (PBKDF2 4096), PRF-512 → PTK/KCK, EAPOL MIC (HMAC-SHA1-128), PMKID — রেফারেন্স `crack/wpa_psk.py` ও `dot11/wpa.py` অনুযায়ী।
- [x] **Known-answer সেলফ-চেক:** `engine/SelfCheck.java` — SHA-1/SHA-256/HMAC/PBKDF2/RC4/CRC-32/WPA-PMK (IEEE ভেক্টর)/hc22000 লাইন; ইঞ্জিন শুরুতেই চলে, `ready.runtime.selfcheck`-এ ফল যায়।
- [x] **৮০২.১১ কোর:** `dot11/{Mac,Ie,Frame,Parser}.java` — হেডার, IE ওয়াক/বিল্ড, RSN পার্স, beacon/probe → `AccessPoint`, deauth/auth/probe/assoc বিল্ডার, `forcePskAkm`।
- [x] **স্ক্যান-সিঙ্ক:** `engine/{AccessPoint,ScanBook}.java` — BSSID-ভিত্তিক মার্জ, সিগন্যাল-সাজানো, অ্যাপের রো JSON।
- [x] **ভল্ট + 22000:** `engine/Vault.java`, `engine/capture/Hc22000.java` (PMKID ও EAPOL লাইন), `captures`/`crack_tools` ইন-প্রসেস ইভেন্ট।
- [x] **বিল্ড সবুজ:** রান **#45624833303** (`a051b26`)।
- [x] **EAPOL-Key পার্স:** `engine/dot11/Eapol.java` — LLC/SNAP খোঁজ, big-endian ফিল্ড, M1/M2/M3/M4 ক্লাসিফিকেশন, PMKID KDE, MIC-শূন্য কপি — রেফারেন্স `dot11/eapol.py` + `parser._parse_eapol` অফসেট হুবহু (1/2/5/9/17/81/97/99)।
- [x] **হ্যান্ডশেক স্টেট:** `engine/capture/{Handshake,HandshakeBook}.java` — M1+M2 ও M2+M3 জোড়া একবারই লাইন দেয় (ডুপ্লিকেট নয়), PMKID লাইনও।
- [x] **স্ক্যান-সিঙ্ক:** `engine/ScanEngine.java` — চ্যানেল প্ল্যান (2.4/5/DFS), `feed(mpdu,rssi)` → বুক; `targets` এখন প্রতি AP-র যোগ্য অ্যাটাক তালিকা সহ (deauth/decloak/pmkid/wps/wep/eviltwin/captive)।
- [x] **চিপ ক্যাটালগ ও কনট্র্যাক্ট:** `engine/chips/{WifiChip,ChipCatalog}.java` — AR9271/RT2x00/MT76/RTL8187/RTL8xxxU/RTL88XXAU; `devices` এখন মডেল ও supported ফ্ল্যাগ দেয়।
- [x] **বিল্ড সবুজ:** রান **#45625602096** (`376fd15`)।
- [x] **RTL8187 পাথ (ফার্মওয়্যার-মুক্ত পরিবার):** `engine/chips/rtl8187/{Rtl8187Regs,Rtl8187Transport,Eeprom93cx6,Rtl8187Tx,Rtl8187Rx}.java` — ভেন্ডর কন্ট্রোল রিড/রাইট (bRequest 0x05, wValue=reg, wIndex=page), 93cx6 বিট-ব্যাং ইইপ্রম, ১২-বাইট TX ডিস্ক্রিপ্টর (len+NO_ENC+rate, len=0, retry), সিকোয়েন্স স্ট্যাম্পিং, ZLP, RX trailer (−4−((27·agc)>>6) dBm, FCS স্ট্রিপ)।
- [x] **বিল্ড সবুজ:** রান **#45626317618** (`0d0b69d`)।
- [x] **নিয়ম আপডেট:** চিপের ক্লোজড ফার্মওয়্যার ব্যবহারের অনুমতি PLAN ও README-তে লেখা হয়েছে।
- [ ] RTL8187 MAC/RF ব্রিং-আপ (init_hw রেজিস্টার সিকোয়েন্স + rtl8225z2 RF টেবিল, eeprom TX-power) → `WifiChip` ইমপ্লিমেন্টেশন, তারপর `scan` লাইভ।
- [ ] চিপসেট ড্রাইভার বাকি পরিবার — যেগুলোর ব্রিং-আপে ভেন্ডর ফার্মওয়্যার ব্লব লাগে সেগুলো আমাদের কোডে সম্ভব নয় (নিচের সীমা দেখো)।
- [ ] **ফার্মওয়্যার সীমা (গুরুত্বপূর্ণ):** রেফারেন্সের AR9271/RT5370/mt76/RTL8xxxU ব্রিং-আপ কার্ডে ভেন্ডর ফার্মওয়্যার ব্লব পাঠায়; বাইরের বাইনারি নিষিদ্ধ হওয়ায় ওই পরিবারগুলোর ব্লব আমাদের নিজের কোডে লেখা সম্ভব নয়। ফার্মওয়্যার-মুক্ত পরিবার (RTL8187, RT2500) নিয়ে ড্রাইভার শুরু হবে, তারপর বাকিগুলো।

**বাকি ফেজ (M2–M6):** উপরের ৬ নম্বর সেকশনের তালিকা অনুযায়ী অপরিবর্তিত।
