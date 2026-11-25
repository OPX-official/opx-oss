package com.opx.oss.engine.dot11;

import com.opx.oss.engine.AccessPoint;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * MPDU parsing: beacons and probe responses into an {@link AccessPoint}, and the fixed fields
 * every frame type shares.
 */
public final class Parser {

    private static final int AKM_PSK = 2;
    private static final int AKM_PSK_SHA256 = 6;
    private static final int AKM_FT_PSK = 4;
    private static final int AKM_SAE = 8;
    private static final int AKM_FT_SAE = 9;
    private static final int AKM_OWE = 18;
    private static final int AKM_8021X = 1;

    private Parser() {
    }

    /** A beacon or probe response at {@code rssi}, or {@code null} for any other frame. */
    public static AccessPoint parseSsidFrame(byte[] frame, int rssi) {
        if (frame == null || frame.length < 36) return null;
        int fc0 = frame[0] & 0xFF;
        if (!Frame.isManagement(fc0)) return null;
        int subtype = Frame.subtype(fc0);
        if (subtype != Frame.SUBTYPE_BEACON && subtype != Frame.SUBTYPE_PROBE_RESP) return null;

        AccessPoint ap = new AccessPoint();
        ap.bssid = Mac.text(slice(frame, 16, 6));
        ap.rssi = rssi;
        ap.beacons = 1;

        int capability = (frame[34] & 0xFF) | ((frame[35] & 0xFF) << 8);
        boolean privacy = (capability & 0x0010) != 0;

        List<Ie.Element> elements = Ie.parse(frame, 36);
        Ie.Element ssidElement = Ie.first(elements, Ie.SSID);
        if (ssidElement != null) {
            ap.ssid = new String(ssidElement.body, StandardCharsets.UTF_8);
            ap.hidden = ssidElement.body.length == 0;
        } else {
            ap.hidden = true;
        }

        Ie.Element ds = Ie.first(elements, Ie.DS_PARAM);
        if (ds != null && ds.body.length >= 1) {
            ap.channel = ds.body[0] & 0xFF;
        } else {
            Ie.Element ht = Ie.first(elements, Ie.HT_INFO);
            if (ht != null && ht.body.length >= 2) ap.channel = ht.body[0] & 0xFF;
        }
        if (ap.channel == 0) ap.channel = 1;

        ap.wps = Ie.isWps(elements);
        Ie.Element rsn = Ie.first(elements, Ie.RSN);
        if (rsn != null) {
            readRsn(ap, rsn.body);
        } else if (Ie.isWpa(elements)) {
            ap.encryption = "WPA";
        } else if (privacy) {
            ap.encryption = "WEP";
        } else {
            ap.encryption = "OPEN";
        }
        return ap;
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

    /** Reads the RSN element body: ciphers, AKMs and the PMF capability bits. */
    private static void readRsn(AccessPoint ap, byte[] body) {
        if (body.length < 8) return;
        int pairwiseCount = (body[6] & 0xFF) | ((body[7] & 0xFF) << 8);
        int akmOffset = 8 + 4 * pairwiseCount;
        int akmCount = 0;
        if (akmOffset + 2 <= body.length) {
            akmCount = (body[akmOffset] & 0xFF) | ((body[akmOffset + 1] & 0xFF) << 8);
        }

        boolean psk = false;
        boolean sae = false;
        boolean owe = false;
        boolean enterprise = false;
        for (int i = 0; i < akmCount; i++) {
            int at = akmOffset + 2 + 4 * i;
            if (at + 3 >= body.length) break;
            int suite = body[at + 3] & 0xFF;
            String label = akmLabel(suite);
            if (label != null) ap.akms.add(label);
            if (suite == AKM_PSK || suite == AKM_PSK_SHA256 || suite == AKM_FT_PSK) psk = true;
            if (suite == AKM_SAE || suite == AKM_FT_SAE) sae = true;
            if (suite == AKM_OWE) owe = true;
            if (suite == AKM_8021X) enterprise = true;
        }

        int capsOffset = akmOffset + 2 + 4 * akmCount;
        int caps = 0;
        if (capsOffset + 2 <= body.length) {
            caps = (body[capsOffset] & 0xFF) | ((body[capsOffset + 1] & 0xFF) << 8);
        }
        ap.pmfCapable = (caps & 0x0080) != 0;
        ap.pmfRequired = (caps & 0x0040) != 0;

        ap.wpa3 = sae || owe;
        ap.transitionMode = sae && psk;
        if (ap.wpa3 && ap.transitionMode) {
            ap.encryption = "WPA2/WPA3";
        } else if (ap.wpa3 && !psk) {
            ap.encryption = "WPA3";
        } else if (psk) {
            ap.encryption = ap.pmfRequired ? "WPA2 (PMF)" : "WPA2";
        } else if (enterprise) {
            ap.encryption = "WPA2-EAP";
        } else {
            ap.encryption = "WPA2";
        }
    }

    private static String akmLabel(int suite) {
        switch (suite) {
            case 1:
                return "802.1X";
            case AKM_PSK:
                return "PSK";
            case AKM_FT_PSK:
                return "FT-PSK";
            case AKM_PSK_SHA256:
                return "PSK-SHA256";
            case 5:
                return "802.1X-SHA256";
            case AKM_SAE:
                return "SAE";
            case AKM_FT_SAE:
                return "FT-SAE";
            case AKM_OWE:
                return "OWE";
            default:
                return null;
        }
    }

    public static byte[] slice(byte[] data, int offset, int length) {
        if (offset < 0 || offset + length > data.length) return new byte[0];
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }
}
