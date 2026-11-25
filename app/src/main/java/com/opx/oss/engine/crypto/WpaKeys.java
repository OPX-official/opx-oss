package com.opx.oss.engine.crypto;

import java.nio.charset.StandardCharsets;

/**
 * The WPA/WPA2-PSK key schedule: passphrase to PMK (PBKDF2, 4096 rounds), PMK plus both nonces
 * and both MACs to the PTK, KCK out of the PTK, and the EAPOL MIC over the KCK.
 */
public final class WpaKeys {

    private static final byte[] PAIRWISE_LABEL =
            "Pairwise key expansion".getBytes(StandardCharsets.US_ASCII);

    private WpaKeys() {
    }

    /** PMK = PBKDF2-HMAC-SHA1(passphrase, SSID, 4096, 32). */
    public static byte[] pmk(String passphrase, byte[] ssid) {
        return Pbkdf2.hmacSha1(passphrase.getBytes(StandardCharsets.UTF_8), ssid, 4096, 32);
    }

    /** The PTK's first {@code length} octets (KCK, then KEK, then TK). */
    public static byte[] ptk(byte[] pmk, byte[] aa, byte[] spa,
                             byte[] anonce, byte[] snonce, int length) {
        byte[] data = new byte[76];
        byte[] macLow = less(aa, spa) ? aa : spa;
        byte[] macHigh = less(aa, spa) ? spa : aa;
        byte[] nonceLow = less(anonce, snonce) ? anonce : snonce;
        byte[] nonceHigh = less(anonce, snonce) ? snonce : anonce;
        System.arraycopy(macLow, 0, data, 0, 6);
        System.arraycopy(macHigh, 0, data, 6, 6);
        System.arraycopy(nonceLow, 0, data, 12, 32);
        System.arraycopy(nonceHigh, 0, data, 44, 32);

        byte[] out = new byte[length];
        int written = 0;
        for (int counter = 0; written < length; counter++) {
            byte[] input = Hmac.concat(PAIRWISE_LABEL, new byte[]{0x00});
            input = Hmac.concat(input, data);
            input = Hmac.concat(input, new byte[]{(byte) counter});
            byte[] block = Hmac.hmacSha1(pmk, input);
            int copy = Math.min(block.length, length - written);
            System.arraycopy(block, 0, out, written, copy);
            written += copy;
        }
        return out;
    }

    /** KCK: the PTK's first 16 octets, the key the EAPOL MIC is computed with. */
    public static byte[] kck(byte[] pmk, byte[] aa, byte[] spa, byte[] anonce, byte[] snonce) {
        return ptk(pmk, aa, spa, anonce, snonce, 16);
    }

    /** EAPOL-Key MIC (key descriptor version 2): HMAC-SHA1 over the frame, first 16 octets. */
    public static byte[] mic(byte[] kck, byte[] eapol) {
        byte[] digest = Hmac.hmacSha1(kck, eapol);
        byte[] out = new byte[16];
        System.arraycopy(digest, 0, out, 0, 16);
        return out;
    }

    /** PMKID (AKM 2): HMAC-SHA1-128(PMK, "PMK Name" || AP || STA). */
    public static byte[] pmkid(byte[] pmk, byte[] aa, byte[] spa) {
        byte[] name = "PMK Name".getBytes(StandardCharsets.US_ASCII);
        byte[] digest = Hmac.hmacSha1(pmk, Hmac.concat(Hmac.concat(name, aa), spa));
        byte[] out = new byte[16];
        System.arraycopy(digest, 0, out, 0, 16);
        return out;
    }

    private static boolean less(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xFF;
            int y = b[i] & 0xFF;
            if (x != y) return x < y;
        }
        return a.length < b.length;
    }
}
