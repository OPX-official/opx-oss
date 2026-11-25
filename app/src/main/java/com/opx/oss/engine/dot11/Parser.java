package com.opx.oss.engine.dot11;

import com.opx.oss.engine.AccessPoint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Beacon and probe-response parsing into an {@link AccessPoint}: the SSID, the operating
 * channel, the RSN (or legacy WPA) element, the WPS attributes and the vendor-chipset hint,
 * with the same airodump-style encryption label the reference produces.
 */
public final class Parser {

    private static final byte[] RSN_OUI = {0x00, 0x0F, (byte) 0xAC};
    private static final byte[] WPA_OUI = {0x00, 0x50, (byte) 0xF2};

    private static final int AKM_802_1X = 1;
    private static final int AKM_PSK = 2;
    private static final int AKM_FT_802_1X = 3;
    private static final int AKM_FT_PSK = 4;
    private static final int AKM_802_1X_SHA256 = 5;
    private static final int AKM_PSK_SHA256 = 6;
    private static final int AKM_SAE = 8;
    private static final int AKM_FT_SAE = 9;
    private static final int AKM_802_1X_SUITE_B = 11;
    private static final int AKM_802_1X_SUITE_B_192 = 12;
    private static final int AKM_FT_802_1X_SUITE_B_192 = 13;
    private static final int AKM_FILS_SHA256 = 14;
    private static final int AKM_FILS_SHA384 = 15;
    private static final int AKM_FT_FILS_SHA256 = 16;
    private static final int AKM_FT_FILS_SHA384 = 17;
    private static final int AKM_OWE = 18;
    private static final int AKM_FT_PSK_SHA384 = 19;
    private static final int AKM_PSK_SHA384 = 20;
    private static final int AKM_FT_802_1X_SHA384 = 22;
    private static final int AKM_802_1X_SHA384 = 23;
    private static final int AKM_SAE_EXT_KEY = 24;
    private static final int AKM_FT_SAE_EXT_KEY = 25;

    private static final int CIPHER_WEP40 = 1;
    private static final int CIPHER_TKIP = 2;
    private static final int CIPHER_CCMP = 4;
    private static final int CIPHER_WEP104 = 5;
    private static final int CIPHER_AES_128_CMAC = 6;
    private static final int CIPHER_GCMP = 8;
    private static final int CIPHER_GCMP_256 = 9;
    private static final int CIPHER_CCMP_256 = 10;
    private static final int CIPHER_BIP_GMAC_256 = 12;

    private static final Map<Integer, String> CIPHER_NAMES = new LinkedHashMap<>();
    private static final Map<Integer, String> AKM_NAMES = new LinkedHashMap<>();
    private static final Map<String, String> CHIPSET_BY_OUI = new LinkedHashMap<>();

    static {
        CIPHER_NAMES.put(CIPHER_WEP40, "WEP-40");
        CIPHER_NAMES.put(CIPHER_TKIP, "TKIP");
        CIPHER_NAMES.put(CIPHER_CCMP, "CCMP");
        CIPHER_NAMES.put(CIPHER_WEP104, "WEP-104");
        CIPHER_NAMES.put(CIPHER_AES_128_CMAC, "BIP-CMAC-128");
        CIPHER_NAMES.put(CIPHER_GCMP, "GCMP-128");
        CIPHER_NAMES.put(CIPHER_GCMP_256, "GCMP-256");
        CIPHER_NAMES.put(CIPHER_CCMP_256, "CCMP-256");

        AKM_NAMES.put(AKM_802_1X, "EAP");
        AKM_NAMES.put(AKM_PSK, "PSK");
        AKM_NAMES.put(AKM_FT_802_1X, "FT-EAP");
        AKM_NAMES.put(AKM_FT_PSK, "FT-PSK");
        AKM_NAMES.put(AKM_802_1X_SHA256, "EAP-SHA256");
        AKM_NAMES.put(AKM_PSK_SHA256, "PSK-SHA256");
        AKM_NAMES.put(AKM_SAE, "SAE");
        AKM_NAMES.put(AKM_FT_SAE, "FT-SAE");
        AKM_NAMES.put(AKM_802_1X_SUITE_B, "EAP-SUITE-B");
        AKM_NAMES.put(AKM_802_1X_SUITE_B_192, "EAP-SUITE-B-192");
        AKM_NAMES.put(AKM_FT_802_1X_SUITE_B_192, "FT-EAP-SUITE-B-192");
        AKM_NAMES.put(AKM_FILS_SHA256, "FILS-SHA256");
        AKM_NAMES.put(AKM_FILS_SHA384, "FILS-SHA384");
        AKM_NAMES.put(AKM_FT_FILS_SHA256, "FT-FILS-SHA256");
        AKM_NAMES.put(AKM_FT_FILS_SHA384, "FT-FILS-SHA384");
        AKM_NAMES.put(AKM_OWE, "OWE");
        AKM_NAMES.put(AKM_FT_PSK_SHA384, "FT-PSK-SHA384");
        AKM_NAMES.put(AKM_PSK_SHA384, "PSK-SHA384");
        AKM_NAMES.put(AKM_FT_802_1X_SHA384, "FT-EAP-SHA384");
        AKM_NAMES.put(AKM_802_1X_SHA384, "EAP-SHA384");
        AKM_NAMES.put(AKM_SAE_EXT_KEY, "SAE-EXT-KEY");
        AKM_NAMES.put(AKM_FT_SAE_EXT_KEY, "FT-SAE-EXT-KEY");

        CHIPSET_BY_OUI.put("001018", "Broadcom");
        CHIPSET_BY_OUI.put("000c43", "MediaTek");
        CHIPSET_BY_OUI.put("000ce7", "MediaTek");
        CHIPSET_BY_OUI.put("00037f", "Qualcomm");
        CHIPSET_BY_OUI.put("8cfdf0", "Qualcomm");
        CHIPSET_BY_OUI.put("00e04c", "Realtek");
    }

    private static final Set<Integer> ENTERPRISE_AKMS = new LinkedHashSet<>(Arrays.asList(
            AKM_802_1X, AKM_FT_802_1X, AKM_802_1X_SHA256, AKM_802_1X_SUITE_B,
            AKM_802_1X_SUITE_B_192, AKM_FT_802_1X_SUITE_B_192, AKM_FILS_SHA256,
            AKM_FILS_SHA384, AKM_FT_FILS_SHA256, AKM_FT_FILS_SHA384, AKM_FT_802_1X_SHA384,
            AKM_802_1X_SHA384));

    private static final Set<Integer> SAE_AKMS = new LinkedHashSet<>(Arrays.asList(
            AKM_SAE, AKM_FT_SAE, AKM_SAE_EXT_KEY, AKM_FT_SAE_EXT_KEY));

    private static final Set<Integer> PSK_AKMS = new LinkedHashSet<>(Arrays.asList(
            AKM_PSK, AKM_FT_PSK, AKM_PSK_SHA256, AKM_FT_PSK_SHA384, AKM_PSK_SHA384));

    private static final Set<Integer> WPA3_DATA_CIPHERS = new LinkedHashSet<>(Arrays.asList(
            CIPHER_CCMP, CIPHER_GCMP, CIPHER_GCMP_256, CIPHER_CCMP_256));

    private Parser() {
    }

    /** A parsed RSN element; {@code null} from {@link #readRsn} when it is malformed. */
    private static final class Rsn {
        Integer groupCipher;
        String pairwiseCipher;
        int pairwiseCount;
        final List<Integer> pairwiseSuites = new ArrayList<>();
        final List<String> akms = new ArrayList<>();
        final List<Integer> akmSuites = new ArrayList<>();
        int akmCount;
        boolean vendorAkmOffered;
        boolean pmfCapable;
        boolean pmfRequired;
        Integer groupManagementCipher;
    }

    /** A parsed legacy WPA element (00-50-F2 type 1). */
    private static final class Wpa {
        String pairwiseCipher;
        final List<String> akms = new ArrayList<>();
        final List<Integer> akmSuites = new ArrayList<>();
        boolean vendorAkmOffered;
    }

    /** A beacon or probe response at {@code rssi}, or {@code null} for any other frame. */
    public static AccessPoint parseSsidFrame(byte[] frame, int rssi) {
        if (frame == null || frame.length < 36) return null;
        int fc0 = frame[0] & 0xFF;
        if (!Frame.isManagement(fc0)) return null;
        int subtype = Frame.subtype(fc0);
        if (subtype != Frame.SUBTYPE_BEACON && subtype != Frame.SUBTYPE_PROBE_RESP) return null;

        // Per 802.11 the SSID element is mandatory and first; anything else is not a beacon.
        if ((frame[36] & 0xFF) != 0) return null;

        AccessPoint ap = new AccessPoint();
        ap.bssid = Mac.text(slice(frame, 16, 6));
        ap.rssi = rssi;
        ap.beacons = 1;

        int capability = (frame[34] & 0xFF) | ((frame[35] & 0xFF) << 8);
        boolean privacy = (capability & 0x0010) != 0;

        boolean hasRsn = false;
        boolean hasWpa = false;
        boolean beaconProtection = false;
        Rsn rsn = null;
        Wpa wpa = null;
        byte[] rsnRaw = null;
        Integer channelDs = null;
        Integer channelHt = null;
        Integer channelVht = null;
        List<byte[]> wpsPayloads = new ArrayList<>();
        boolean seenSsid = false;

        for (Ie.Element element : Ie.parse(frame, 36)) {
            switch (element.tag) {
                case Ie.SSID:
                    if (seenSsid) break;
                    seenSsid = true;
                    if (element.body.length <= 32) {
                        if (isBlank(element.body)) {
                            ap.hidden = true;
                        } else {
                            String text = new String(element.body, StandardCharsets.UTF_8);
                            if (!printable(text)) return null;
                            ap.ssid = text;
                        }
                    }
                    break;
                case Ie.DS_PARAM:
                    if (element.body.length == 1) channelDs = element.body[0] & 0xFF;
                    break;
                case Ie.HT_INFO:
                    if (element.body.length >= 1) channelHt = element.body[0] & 0xFF;
                    break;
                case 192:                                    // VHT Operation
                    if (element.body.length >= 2) channelVht = element.body[1] & 0xFF;
                    break;
                case 127:                                    // Extended Capabilities
                    if (element.body.length >= 11 && (element.body[10] & 0x10) != 0) {
                        beaconProtection = true;
                    }
                    break;
                case Ie.RSN:
                    hasRsn = true;
                    if (rsnRaw == null) rsnRaw = element.raw;
                    rsn = readRsn(element.body);
                    break;
                case Ie.VENDOR:
                    if (element.body.length >= 4) {
                        byte[] oui = Arrays.copyOfRange(element.body, 0, 3);
                        int type = element.body[3] & 0xFF;
                        if (Arrays.equals(oui, WPA_OUI)) {
                            if (type == 1) {
                                hasWpa = true;
                                if (wpa == null) wpa = readWpa(element.body);
                            } else if (type == 4) {
                                wpsPayloads.add(Arrays.copyOfRange(element.body, 4,
                                        element.body.length));
                            }
                        } else {
                            String chipset = CHIPSET_BY_OUI.get(hex(oui));
                            if (chipset != null && ap.chipset == null) ap.chipset = chipset;
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        if (channelDs != null) {
            ap.channel = channelDs;
        } else if (channelHt != null) {
            ap.channel = channelHt;
        } else if (channelVht != null) {
            ap.channel = channelVht;
        }
        if (ap.channel == 0) ap.channel = 1;

        if (rsn != null) ap.rsnIe = rsnRaw;
        else if (rsnRaw != null) ap.rsnIe = rsnRaw;

        if (!wpsPayloads.isEmpty()) readWps(ap, wpsPayloads);

        boolean wpa3 = false;
        boolean transitionMode = false;
        String pairwiseCipher = null;
        List<String> akmNames = new ArrayList<>();
        List<Integer> akmSuites = new ArrayList<>();
        boolean vendorAkmOffered = false;

        if (rsn != null) {
            ap.rsnValid = true;
            pairwiseCipher = rsn.pairwiseCipher;
            akmNames = rsn.akms;
            akmSuites = rsn.akmSuites;
            vendorAkmOffered = rsn.vendorAkmOffered;
            ap.pmfCapable = rsn.pmfCapable;
            ap.pmfRequired = rsn.pmfRequired;
            wpa3 = intersects(rsn.akmSuites, SAE_AKMS);
            transitionMode = wpa3 && intersects(rsn.akmSuites, PSK_AKMS);
            boolean[] enterprise = classifyWpa3Enterprise(rsn, hasWpa);
            wpa3 = wpa3 || enterprise[0];
            transitionMode = transitionMode || enterprise[1];
        } else if (wpa != null) {
            hasRsn = false;
            pairwiseCipher = wpa.pairwiseCipher;
            akmNames = wpa.akms;
            akmSuites = wpa.akmSuites;
            vendorAkmOffered = wpa.vendorAkmOffered;
        }

        ap.pairwiseCipher = pairwiseCipher;
        ap.akms.addAll(akmNames);
        ap.akmSuites.addAll(akmSuites);
        ap.vendorAkmOffered = vendorAkmOffered;
        ap.beaconProtection = beaconProtection;
        ap.wpa3 = wpa3;
        ap.transitionMode = transitionMode;
        ap.encryption = encryptionLabel(hasRsn, hasWpa, wpa3, transitionMode, akmNames,
                pairwiseCipher, privacy);
        return ap;
    }

    /**
     * The first RSN element's AKM when it lists exactly one 00-0F-AC suite, and whether an RSN
     * element is present without one (used to read a client's chosen AKM).
     */
    public static int[] akmSelection(byte[] data, int start) {
        int end = start;
        for (Ie.Element element : Ie.parse(data, start)) {
            if (element.tag == Ie.RSN) {
                Rsn rsn = readRsn(element.body);
                if (rsn != null && rsn.akmCount == 1 && rsn.akmSuites.size() == 1) {
                    return new int[]{rsn.akmSuites.get(0), 0};
                }
                return new int[]{-1, 1};
            }
            end += element.raw.length;
        }
        boolean dangling = end < data.length && (data[end] & 0xFF) == Ie.RSN;
        return new int[]{-1, dangling ? 1 : 0};
    }

    /** The SSID a probe request asks for, or {@code null} when it is the wildcard or empty. */
    public static String probeRequestSsid(byte[] frame) {
        if (frame == null || frame.length < 24) return null;
        int fc0 = frame[0] & 0xFF;
        if (!Frame.isManagement(fc0) || Frame.subtype(fc0) != Frame.SUBTYPE_PROBE_REQ) return null;
        Ie.Element ssid = Ie.first(Ie.parse(frame, 24), Ie.SSID);
        if (ssid == null || ssid.body.length == 0) return null;
        return new String(ssid.body, StandardCharsets.UTF_8);
    }

    // -- RSN / WPA -----------------------------------------------------------

    private static Rsn readRsn(byte[] body) {
        int n = body.length;
        if (n < 10) return null;
        if (le16(body, 0) != 1) return null;
        Rsn rsn = new Rsn();
        rsn.groupCipher = hasOui(body, 2, RSN_OUI) ? (body[5] & 0xFF) : null;

        int at = 6;
        rsn.pairwiseCount = le16(body, at);
        at += 2;
        if (at + 4 * rsn.pairwiseCount > n) return null;
        for (int i = 0; i < rsn.pairwiseCount; i++) {
            byte[] suite = Arrays.copyOfRange(body, at, at + 4);
            if (hasOui(suite, 0, RSN_OUI)) rsn.pairwiseSuites.add(suite[3] & 0xFF);
            if (rsn.pairwiseCipher == null && hasOui(suite, 0, RSN_OUI)) {
                rsn.pairwiseCipher = CIPHER_NAMES.get(suite[3] & 0xFF);
            }
            at += 4;
        }
        if (at + 2 > n) return null;
        rsn.akmCount = le16(body, at);
        at += 2;
        if (at + 4 * rsn.akmCount > n) return null;
        for (int i = 0; i < rsn.akmCount; i++) {
            byte[] suite = Arrays.copyOfRange(body, at, at + 4);
            at += 4;
            String name;
            if (hasOui(suite, 0, RSN_OUI)) {
                int suiteNumber = suite[3] & 0xFF;
                if (!rsn.akmSuites.contains(suiteNumber)) rsn.akmSuites.add(suiteNumber);
                name = AKM_NAMES.containsKey(suiteNumber)
                        ? AKM_NAMES.get(suiteNumber) : "AKM-" + hex(suite);
            } else {
                rsn.vendorAkmOffered = true;
                name = "Vendor";
            }
            if (!rsn.akms.contains(name)) rsn.akms.add(name);
        }

        if (at < n) {
            if (at + 2 > n) return null;
            int caps = le16(body, at);
            rsn.pmfCapable = (caps & 0x0080) != 0;
            rsn.pmfRequired = (caps & 0x0040) != 0;
            at += 2;
            if (at < n) {
                if (at + 2 > n) return null;
                int pmkidCount = le16(body, at);
                at += 2;
                int pmkidBytes = 16 * pmkidCount;
                if (at + pmkidBytes > n) return null;
                at += pmkidBytes;
                if (at < n && at + 4 > n) return null;
                if (at + 4 <= n && hasOui(body, at, RSN_OUI)) {
                    rsn.groupManagementCipher = body[at + 3] & 0xFF;
                }
            }
        }
        return rsn;
    }

    private static Wpa readWpa(byte[] body) {
        int n = body.length;
        if (n < 12 || le16(body, 4) != 1) return null;
        Wpa wpa = new Wpa();
        int pairwiseCount = le16(body, 10);
        int at = 12;
        if (at + 4 * pairwiseCount + 2 > n) return null;
        for (int i = 0; i < pairwiseCount; i++) {
            byte[] suite = Arrays.copyOfRange(body, at, at + 4);
            if (wpa.pairwiseCipher == null && hasOui(suite, 0, WPA_OUI)) {
                wpa.pairwiseCipher = CIPHER_NAMES.get(suite[3] & 0xFF);
            }
            at += 4;
        }
        int akmCount = le16(body, at);
        at += 2;
        if (at + 4 * akmCount > n) return null;
        for (int i = 0; i < akmCount; i++) {
            byte[] suite = Arrays.copyOfRange(body, at, at + 4);
            at += 4;
            Integer akm = null;
            if (hasOui(suite, 0, WPA_OUI)) {
                int type = suite[3] & 0xFF;
                if (type == 1) akm = AKM_802_1X;
                else if (type == 2) akm = AKM_PSK;
            }
            String name;
            if (akm == null) {
                wpa.vendorAkmOffered = true;
                name = "Vendor";
            } else {
                name = AKM_NAMES.get(akm);
                if (!wpa.akmSuites.contains(akm)) wpa.akmSuites.add(akm);
            }
            if (!wpa.akms.contains(name)) wpa.akms.add(name);
        }
        return wpa;
    }

    /** WPA3-Enterprise classification: {wpa3, transition}. */
    private static boolean[] classifyWpa3Enterprise(Rsn rsn, boolean hasWpa) {
        if (hasWpa || !rsn.pmfCapable || !enterpriseAkmsOnly(rsn)) {
            return new boolean[]{false, false};
        }
        Set<Integer> suites = new LinkedHashSet<>(rsn.akmSuites);
        if (wpa3Enterprise192(rsn)) {
            if (suites.size() == 1 && suites.contains(AKM_802_1X_SUITE_B_192)) {
                return new boolean[]{true, false};
            }
            Set<Integer> ft192 = new LinkedHashSet<>(Arrays.asList(
                    AKM_802_1X_SUITE_B_192, AKM_FT_802_1X_SUITE_B_192));
            if (suites.contains(AKM_FT_802_1X_SUITE_B_192) && ft192.containsAll(suites)) {
                return new boolean[]{true, false};
            }
        }
        if (!modernRsnCiphers(rsn) || !suites.contains(AKM_802_1X_SHA256)) {
            return new boolean[]{false, false};
        }
        if (suites.contains(AKM_802_1X)) {
            return new boolean[]{!rsn.pmfRequired, !rsn.pmfRequired};
        }
        return new boolean[]{rsn.pmfRequired, false};
    }

    private static boolean modernRsnCiphers(Rsn rsn) {
        return rsn.groupCipher != null
                && WPA3_DATA_CIPHERS.contains(rsn.groupCipher)
                && rsn.pairwiseCount == rsn.pairwiseSuites.size()
                && !rsn.pairwiseSuites.isEmpty()
                && WPA3_DATA_CIPHERS.containsAll(rsn.pairwiseSuites);
    }

    private static boolean wpa3Enterprise192(Rsn rsn) {
        return rsn.pmfRequired
                && rsn.pairwiseCount == 1
                && rsn.pairwiseSuites.size() == 1
                && rsn.pairwiseSuites.get(0) == CIPHER_GCMP_256
                && rsn.groupCipher != null && rsn.groupCipher == CIPHER_GCMP_256
                && rsn.groupManagementCipher != null
                && rsn.groupManagementCipher == CIPHER_BIP_GMAC_256;
    }

    private static boolean enterpriseAkmsOnly(Rsn rsn) {
        return rsn.akmCount == rsn.akmSuites.size()
                && !rsn.akmSuites.isEmpty()
                && ENTERPRISE_AKMS.containsAll(rsn.akmSuites);
    }

    // -- WPS -----------------------------------------------------------------

    private static void readWps(AccessPoint ap, List<byte[]> payloads) {
        ap.wps = true;
        boolean version1 = false;
        int version2 = 0;
        for (byte[] payload : payloads) {
            int at = 0;
            while (at + 4 <= payload.length) {
                int attribute = ((payload[at] & 0xFF) << 8) | (payload[at + 1] & 0xFF);
                int length = ((payload[at + 2] & 0xFF) << 8) | (payload[at + 3] & 0xFF);
                at += 4;
                if (length < 0 || at + length > payload.length) break;
                byte[] value = Arrays.copyOfRange(payload, at, at + length);
                at += length;
                switch (attribute) {
                    case 0x1057:                                  // AP Setup Locked
                        if (length >= 1) ap.wpsLocked = value[0] == 0x01;
                        break;
                    case 0x1044:                                  // Wi-Fi Protected Setup State
                        if (length >= 1) ap.wpsState = value[0] & 0xFF;
                        break;
                    case 0x1008:                                  // Config Methods
                        if (length >= 2) ap.wpsConfigMethods = be16(value, 0);
                        break;
                    case 0x1010:                                  // Device Password ID
                        if (length >= 2) ap.wpsDevicePasswordId = be16(value, 0);
                        break;
                    case 0x1041:                                  // Selected Registrar
                        if (length >= 1) ap.wpsSelectedRegistrar = value[0] == 0x01;
                        break;
                    case 0x1021:
                        ap.wscManufacturer = text(value);
                        break;
                    case 0x1023:
                        ap.wscModelName = text(value);
                        break;
                    case 0x1024:
                        ap.wscModelNumber = text(value);
                        break;
                    case 0x1011:
                        ap.wscDeviceName = text(value);
                        break;
                    case 0x1054:
                        ap.wscDeviceType = deviceType(value);
                        break;
                    case 0x104A:
                        if (length >= 1) version1 = true;
                        break;
                    case 0x1049: {
                        Integer second = version2(value);
                        if (second != null) version2 = second;
                        break;
                    }
                    default:
                        break;
                }
            }
        }
        if (version2 >= 0x20) ap.wpsVersion = "2.0";
        else if (version1) ap.wpsVersion = "1.0";
    }

    /** WPS 2.0 Version2 out of a Vendor Extension attribute (WFA OUI 00:37:2A, subelement 0). */
    private static Integer version2(byte[] value) {
        if (value.length < 3 || (value[0] & 0xFF) != 0x00 || (value[1] & 0xFF) != 0x37
                || (value[2] & 0xFF) != 0x2A) {
            return null;
        }
        int at = 3;
        while (at + 2 <= value.length) {
            int subId = value[at] & 0xFF;
            int subLength = value[at + 1] & 0xFF;
            at += 2;
            if (at + subLength > value.length) break;
            if (subId == 0x00 && subLength >= 1) return value[at] & 0xFF;
            at += subLength;
        }
        return null;
    }

    private static final String[] DEVICE_CATEGORIES = {
            null, "computer", "input_device", "printer", "camera", "storage", null, "display",
            "multimedia", "gaming", "telephone", "audio"
    };

    private static String deviceType(byte[] value) {
        if (value.length < 2) return null;
        int category = be16(value, 0);
        return category < DEVICE_CATEGORIES.length ? DEVICE_CATEGORIES[category] : null;
    }

    private static String text(byte[] value) {
        int end = value.length;
        while (end > 0 && value[end - 1] == 0) end--;
        String text = new String(value, 0, end, StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? null : text;
    }

    // -- label ---------------------------------------------------------------

    private static String encryptionLabel(boolean hasRsn, boolean hasWpa, boolean wpa3,
                                          boolean transitionMode, List<String> akms,
                                          String pairwiseCipher, boolean privacy) {
        if (hasRsn) {
            boolean hasSae = false;
            boolean hasPsk = false;
            boolean hasOwe = false;
            List<String> enterprise = new ArrayList<>();
            for (String akm : akms) {
                if (akm.contains("SAE")) hasSae = true;
                if (akm.contains("PSK")) hasPsk = true;
                if (akm.equals("OWE")) hasOwe = true;
                if (akm.startsWith("EAP") || akm.startsWith("FT-EAP")) enterprise.add(akm);
            }
            String wpaTag;
            if (transitionMode) wpaTag = "WPA2/WPA3";
            else if (wpa3) wpaTag = "WPA3";
            else if (hasOwe) wpaTag = "OWE";
            else wpaTag = "WPA2";

            String akmTag;
            if (hasSae && hasPsk) akmTag = "PSK+SAE";
            else if (hasSae) akmTag = "SAE";
            else if (hasOwe) akmTag = null;
            else if (!enterprise.isEmpty() && hasPsk) akmTag = "PSK+" + String.join("+", enterprise);
            else if (!enterprise.isEmpty()) akmTag = String.join("+", enterprise);
            else if (hasPsk) akmTag = "PSK";
            else akmTag = akms.isEmpty() ? null : String.join("+", akms);

            StringBuilder label = new StringBuilder(wpaTag);
            if (akmTag != null && !akmTag.isEmpty()) label.append('-').append(akmTag);
            if (pairwiseCipher != null) label.append('-').append(pairwiseCipher);
            return label.toString();
        }
        if (hasWpa) {
            if (akms.isEmpty()) return "WPA-PSK-TKIP";
            StringBuilder label = new StringBuilder("WPA-").append(String.join("+", akms));
            if (pairwiseCipher != null) label.append('-').append(pairwiseCipher);
            return label.toString();
        }
        return privacy ? "WEP" : "OPEN";
    }

    // -- helpers -------------------------------------------------------------

    private static boolean isBlank(byte[] body) {
        for (byte value : body) if (value != 0) return false;
        return true;
    }

    /** Reject control, format and whitespace-only SSIDs: they corrupt a table row. */
    private static boolean printable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDefined(c) || Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasOui(byte[] data, int offset, byte[] oui) {
        if (offset + 3 > data.length) return false;
        for (int i = 0; i < 3; i++) if (data[offset + i] != oui[i]) return false;
        return true;
    }

    private static boolean intersects(List<Integer> values, Set<Integer> set) {
        for (Integer value : values) if (set.contains(value)) return true;
        return false;
    }

    private static int le16(byte[] data, int offset) {
        if (offset + 2 > data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int be16(byte[] data, int offset) {
        if (offset + 2 > data.length) return 0;
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data) text.append(String.format(java.util.Locale.US, "%02x", value & 0xFF));
        return text.toString();
    }

    public static byte[] slice(byte[] data, int offset, int length) {
        if (offset < 0 || offset + length > data.length) return new byte[0];
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }
}
