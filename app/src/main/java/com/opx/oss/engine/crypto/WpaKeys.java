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

    /** Key descriptor version of an EAPOL-Key payload: the Key Information field's low 3 bits. */
    public static int keyVersion(byte[] eapol) {
        if (eapol == null || eapol.length < 7) return 0;
        return eapol[6] & 0x07;
    }

    /** PMK = PBKDF2-HMAC-SHA1(passphrase, SSID, 4096, 32). */
    public static byte[] pmk(String passphrase, byte[] ssid) {
        // A 64-hex passphrase field is the PMK itself, not a passphrase to stretch.
        byte[] raw = rawPmk(passphrase);
        if (raw != null) return raw;
        return Pbkdf2.hmacSha1(passphrase.getBytes(StandardCharsets.UTF_8), ssid, 4096, 32);
    }

    /** A 64-hex-digit string read as the 32-byte PMK it spells, or {@code null}. */
    public static byte[] rawPmk(String text) {
        if (text == null || text.length() != 64) return null;
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int high = Character.digit(text.charAt(i * 2), 16);
            int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    /** 802.11 KDF-Hash-Length over HMAC-SHA256: counter, label, context and bits, little endian. */
    public static byte[] kdfSha256(byte[] key, byte[] label, byte[] context, int lengthBits) {
        byte[] length = {(byte) lengthBits, (byte) (lengthBits >>> 8)};
        byte[] out = new byte[lengthBits / 8];
        int written = 0;
        for (int counter = 1; written < out.length; counter++) {
            byte[] input = new byte[2 + label.length + context.length + 2];
            input[0] = (byte) counter;
            input[1] = (byte) (counter >>> 8);
            System.arraycopy(label, 0, input, 2, label.length);
            System.arraycopy(context, 0, input, 2 + label.length, context.length);
            input[input.length - 2] = length[0];
            input[input.length - 1] = length[1];
            byte[] block = Hmac.hmac(Hmac.SHA256, key, input);
            int copy = Math.min(block.length, out.length - written);
            System.arraycopy(block, 0, out, written, copy);
            written += copy;
        }
        return out;
    }

    /**
     * The PTK's first {@code length} octets (KCK, then KEK, then TK). Key descriptor version 3
     * (PSK-SHA256) derives it with HMAC-SHA256 instead of HMAC-SHA1; versions 1 and 2 use SHA1.
     */
    public static byte[] ptk(byte[] pmk, byte[] aa, byte[] spa,
                             byte[] anonce, byte[] snonce, int length, int keyVersion) {
        return ptk(pmk, aa, spa, anonce, snonce, length,
                keyVersion == 3 ? Hmac.SHA256 : Hmac.SHA1);
    }

    /** The PTK's first {@code length} octets (KCK, then KEK, then TK). */
    public static byte[] ptk(byte[] pmk, byte[] aa, byte[] spa,
                             byte[] anonce, byte[] snonce, int length) {
        return ptk(pmk, aa, spa, anonce, snonce, length, Hmac.SHA1);
    }

    private static byte[] ptk(byte[] pmk, byte[] aa, byte[] spa, byte[] anonce, byte[] snonce,
                              int length, Hmac.Hash hash) {
        if (hash == Hmac.SHA256) {
            // 384-bit KDF-SHA256 PTK: MAC and nonce pairs sorted, as the SHA1 PRF does.
            byte[] data = new byte[76];
            byte[] macLow = less(aa, spa) ? aa : spa;
            byte[] macHigh = less(aa, spa) ? spa : aa;
            byte[] nonceLow = less(anonce, snonce) ? anonce : snonce;
            byte[] nonceHigh = less(anonce, snonce) ? snonce : anonce;
            System.arraycopy(macLow, 0, data, 0, 6);
            System.arraycopy(macHigh, 0, data, 6, 6);
            System.arraycopy(nonceLow, 0, data, 12, 32);
            System.arraycopy(nonceHigh, 0, data, 44, 32);
            byte[] full = kdfSha256(pmk, PAIRWISE_LABEL, data, 384);
            byte[] out = new byte[length];
            System.arraycopy(full, 0, out, 0, Math.min(length, full.length));
            return out;
        }
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
            byte[] block = Hmac.hmac(hash, pmk, input);
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

    /** KCK for a specific key descriptor version (3 derives it with SHA-256). */
    public static byte[] kck(byte[] pmk, byte[] aa, byte[] spa, byte[] anonce, byte[] snonce,
                             int keyVersion) {
        return ptk(pmk, aa, spa, anonce, snonce, 16, keyVersion);
    }

    /**
     * EAPOL-Key MIC for a key descriptor version: 1 = HMAC-MD5, 2 = HMAC-SHA1, 3 = AES-CMAC.
     * The MIC is the first 16 octets of the hash, or the whole CMAC tag.
     */
    public static byte[] mic(byte[] kck, byte[] eapol, int keyVersion) {
        if (keyVersion == 3) return Cmac.cmac(kck, eapol);
        byte[] digest = keyVersion == 1
                ? Hmac.hmac(Hmac.MD5, kck, eapol)
                : Hmac.hmacSha1(kck, eapol);
        byte[] out = new byte[16];
        System.arraycopy(digest, 0, out, 0, 16);
        return out;
    }

    /** EAPOL-Key MIC (key descriptor version 2): HMAC-SHA1 over the frame, first 16 octets. */
    public static byte[] mic(byte[] kck, byte[] eapol) {
        return mic(kck, eapol, 2);
    }

    /** PMKID (AKM 6): HMAC-SHA256-128 of the same input; hashcat reads it from message pair 03. */
    public static byte[] pmkidSha256(byte[] pmk, byte[] aa, byte[] spa) {
        byte[] name = "PMK Name".getBytes(StandardCharsets.US_ASCII);
        byte[] digest = Hmac.hmac(Hmac.SHA256, pmk, Hmac.concat(Hmac.concat(name, aa), spa));
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
