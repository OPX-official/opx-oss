package com.opx.oss.engine.capture;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The {@code 22000} hashline: one line per crackable PMKID or handshake, in the field order
 * the cracker reads.
 * <pre>
 *   WPA*01*PMKID*MAC_AP*MAC_STA*ESSID*ANONCE*EAPOL*MESSAGEPAIR
 *   WPA*02*MIC*MAC_AP*MAC_STA*ESSID*ANONCE*EAPOL*MESSAGEPAIR
 * </pre>
 * MACs are lower-case hex without separators, the SSID is the hex of its bytes, the EAPOL field
 * is the whole frame with the MIC zeroed, and a PMKID line has empty ANONCE and EAPOL fields.
 */
public final class Hc22000 {

    /** Message-pair bit that marks a PMKID computed with SHA-256 (AKM 6). */
    public static final int PMKID_SHA256_BIT = 0x02;
    /** Nonce-error-correction bit: hashcat only corrects nonces when this is set. */
    public static final int NONCE_CORRECTION_BIT = 0x80;

    private Hc22000() {
    }

    public static String pmkid(String ssid, byte[] pmkid, byte[] bssid, byte[] station,
                               boolean sha256) {
        if (ssid == null || ssid.isEmpty() || pmkid == null || pmkid.length != 16) return null;
        String messagePair = sha256 ? "03" : "";
        return "WPA*01*" + hex(pmkid) + "*" + mac(bssid) + "*" + mac(station) + "*"
                + ssidHex(ssid) + "***" + messagePair;
    }

    public static String eapol(String ssid, byte[] mic, byte[] bssid, byte[] station,
                               byte[] anonce, byte[] eapol, int messagePair) {
        if (ssid == null || ssid.isEmpty() || mic == null || mic.length != 16) return null;
        return "WPA*02*" + hex(mic) + "*" + mac(bssid) + "*" + mac(station) + "*"
                + ssidHex(ssid) + "*" + hex(anonce) + "*" + hex(eapol) + "*"
                + String.format(Locale.US, "%02x", (messagePair | NONCE_CORRECTION_BIT) & 0xFF);
    }

    public static String mac(byte[] address) {
        return hex(address);
    }

    public static String ssidHex(String ssid) {
        return hex(ssid.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data) text.append(String.format(Locale.US, "%02x", value & 0xFF));
        return text.toString();
    }
}
