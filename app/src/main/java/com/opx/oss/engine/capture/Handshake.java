package com.opx.oss.engine.capture;

import com.opx.oss.engine.dot11.Mac;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One four-way handshake between an AP and one station: the nonces, the MIC and the EAPOL bytes
 * the MIC covers.
 * <p>
 * Only a <em>keystone</em> frame can carry the hashline: a complete M2 (SNonce, MIC and payload
 * past the MIC) or an M4 whose nonce is not zeroed. It is paired with an ANonce donor (M1, or M3
 * when the AP answered), which decides the message-pair byte:
 * <pre>
 *   0x00 M1+M2   0x02 M2+M3   0x05 M3+M4   0x01 M1+M4
 * </pre>
 * Every emitted line carries the nonce-correction bit (0x80), as hcxpcapngtool's output does.
 */
public final class Handshake {

    public static final int NONCE_LENGTH = 32;
    public static final int MIC_LENGTH = 16;
    public static final int NONCE_CORRECTION_BIT = 0x80;

    public final String bssid;
    public final String station;
    public String ssid = "";

    /** M1 (or M3) nonce, whichever is the donor for the emitted line. */
    public byte[] anonce;
    public byte[] snonce;
    public byte[] mic;
    public byte[] eapol;
    public byte[] pmkid;
    /** True when the network's AKM is PSK-SHA256, which changes the PMKID hashline's pair field. */
    public boolean sha256;
    /** Key descriptor version of the MIC frame: 1 TKIP, 2 CCMP, 3 AES-CMAC (PSK-SHA256). */
    public int keyVersion = 2;

    public byte[] m1Nonce;
    public byte[] m3Nonce;
    public byte[] m4Nonce;
    public byte[] m4Mic;
    public byte[] m4Eapol;

    public boolean hasM1;
    public boolean hasM2;
    public boolean hasM3;
    public boolean hasM4;
    public long seen = System.currentTimeMillis();

    public Handshake(String bssid, String station) {
        this.bssid = bssid;
        this.station = station;
    }

    /** The hashline for the captured PMKID, or {@code null} when there is none. */
    public String pmkidLine() {
        if (pmkid == null || ssid == null || ssid.isEmpty()) return null;
        return Hc22000.pmkid(ssid, pmkid, Mac.of(bssid), Mac.of(station), sha256);
    }

    /** The hashline for the best crackable pair, or {@code null} while there is none. */
    public String eapolLine() {
        if (ssid == null || ssid.isEmpty()) return null;

        // Keystone M2 first: the AP confirmed it in M3, else the client's own challenge.
        if (usable(snonce, mic, eapol)) {
            if (donor(m3Nonce)) return line(mic, m3Nonce, eapol, 0x02);
            if (donor(m1Nonce)) return line(mic, m1Nonce, eapol, 0x00);
        }
        // A non-zeroed M4 can be the keystone too.
        if (usable(m4Nonce, m4Mic, m4Eapol)) {
            if (donor(m3Nonce)) return line(m4Mic, m3Nonce, m4Eapol, 0x05);
            if (donor(m1Nonce)) return line(m4Mic, m1Nonce, m4Eapol, 0x01);
        }
        return null;
    }

    /** The message-pair byte that {@link #eapolLine()} would emit, or 0 when there is none. */
    public int eapolPair() {
        String line = eapolLine();
        if (line == null) return -1;
        String[] parts = line.split("\\*");
        if (parts.length < 9 || parts[8].isEmpty()) return -1;
        try {
            return Integer.parseInt(parts[8].substring(0, 2), 16) & 0x07;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public List<String> lines() {
        List<String> lines = new ArrayList<>(2);
        String pmkidLine = pmkidLine();
        if (pmkidLine != null) lines.add(pmkidLine);
        String eapolLine = eapolLine();
        if (eapolLine != null) lines.add(eapolLine);
        return lines;
    }

    public String fileName() {
        return "capture_" + bssid.replace(':', '-') + "_" + station.replace(':', '-') + ".22000";
    }

    private static boolean donor(byte[] nonce) {
        return nonce != null && nonce.length == NONCE_LENGTH && !allZero(nonce);
    }

    private static boolean usable(byte[] nonce, byte[] mic, byte[] eapol) {
        return donor(nonce) && mic != null && mic.length == MIC_LENGTH && !allZero(mic)
                && eapol != null && eapol.length >= 97;
    }

    private static boolean allZero(byte[] data) {
        for (byte value : data) if (value != 0) return false;
        return true;
    }

    private String line(byte[] micBytes, byte[] nonce, byte[] payload, int pair) {
        return Hc22000.eapol(ssid, micBytes, Mac.of(bssid), Mac.of(station), nonce, payload, pair);
    }

    /** True when the given copies of a nonce match, for an offline sanity check. */
    public boolean sameNonce(byte[] left, byte[] right) {
        return left != null && right != null && Arrays.equals(left, right);
    }
}
