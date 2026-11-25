package com.opx.oss.engine.crypto;

/**
 * MD5 (RFC 1321), needed for the WPA1 (TKIP) MIC: key descriptor version 1 uses
 * HMAC-MD5-128 over the EAPOL frame instead of HMAC-SHA1.
 */
public final class Md5 {

    private static final int[] SHIFTS = {
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    };

    private static final int[] K = new int[64];

    static {
        for (int i = 0; i < 64; i++) {
            // Through long: the value reaches 2^32-1, and a direct double->int cast would
            // saturate at Integer.MAX_VALUE instead of wrapping.
            K[i] = (int) (long) Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296.0);
        }
    }

    private Md5() {
    }

    public static byte[] digest(byte[] message) {
        long bits = (long) message.length * 8;
        int padded = ((message.length + 8) / 64 + 1) * 64;
        byte[] data = new byte[padded];
        System.arraycopy(message, 0, data, 0, message.length);
        data[message.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            data[padded - 8 + i] = (byte) (bits >>> (8 * i));
        }

        int a0 = 0x67452301;
        int b0 = 0xEFCDAB89;
        int c0 = 0x98BADCFE;
        int d0 = 0x10325476;

        int[] m = new int[16];
        for (int offset = 0; offset < padded; offset += 64) {
            for (int i = 0; i < 16; i++) {
                int at = offset + i * 4;
                m[i] = (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8)
                        | ((data[at + 2] & 0xFF) << 16) | ((data[at + 3] & 0xFF) << 24);
            }
            int a = a0, b = b0, c = c0, d = d0;
            for (int i = 0; i < 64; i++) {
                int f;
                int g;
                if (i < 16) {
                    f = (b & c) | (~b & d);
                    g = i;
                } else if (i < 32) {
                    f = (d & b) | (~d & c);
                    g = (5 * i + 1) % 16;
                } else if (i < 48) {
                    f = b ^ c ^ d;
                    g = (3 * i + 5) % 16;
                } else {
                    f = c ^ (b | ~d);
                    g = (7 * i) % 16;
                }
                int temp = d;
                d = c;
                c = b;
                b = b + Integer.rotateLeft(a + f + K[i] + m[g], SHIFTS[i]);
                a = temp;
            }
            a0 += a;
            b0 += b;
            c0 += c;
            d0 += d;
        }

        byte[] out = new byte[16];
        int[] words = {a0, b0, c0, d0};
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                out[i * 4 + j] = (byte) (words[i] >>> (8 * j));
            }
        }
        return out;
    }
}
