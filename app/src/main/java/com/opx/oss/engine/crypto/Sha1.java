package com.opx.oss.engine.crypto;

/** SHA-1, written here so no hashing lives in a library outside this repository. */
public final class Sha1 {

    private static final int[] K = {
            0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC, 0xCA62C1D6
    };

    private Sha1() {
    }

    public static byte[] digest(byte[] message) {
        int bitLength = message.length * 8;
        int padded = ((message.length + 8) / 64 + 1) * 64;
        byte[] block = new byte[padded];
        System.arraycopy(message, 0, block, 0, message.length);
        block[message.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            block[padded - 1 - i] = (byte) (bitLength >>> (8 * i));
        }

        int h0 = 0x67452301, h1 = 0xEFCDAB89, h2 = 0x98BADCFE, h3 = 0x10325476, h4 = 0xC3D2E1F0;
        int[] w = new int[80];
        for (int offset = 0; offset < padded; offset += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = ((block[offset + i * 4] & 0xFF) << 24)
                        | ((block[offset + i * 4 + 1] & 0xFF) << 16)
                        | ((block[offset + i * 4 + 2] & 0xFF) << 8)
                        | (block[offset + i * 4 + 3] & 0xFF);
            }
            for (int i = 16; i < 80; i++) {
                w[i] = Integer.rotateLeft(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
            }
            int a = h0, b = h1, c = h2, d = h3, e = h4;
            for (int i = 0; i < 80; i++) {
                int f;
                if (i < 20) {
                    f = (b & c) | (~b & d);
                } else if (i < 40) {
                    f = b ^ c ^ d;
                } else if (i < 60) {
                    f = (b & c) | (b & d) | (c & d);
                } else {
                    f = b ^ c ^ d;
                }
                int temp = Integer.rotateLeft(a, 5) + f + e + K[i / 20] + w[i];
                e = d;
                d = c;
                c = Integer.rotateLeft(b, 30);
                b = a;
                a = temp;
            }
            h0 += a;
            h1 += b;
            h2 += c;
            h3 += d;
            h4 += e;
        }
        return words(h0, h1, h2, h3, h4);
    }

    static byte[] words(int... values) {
        byte[] out = new byte[values.length * 4];
        for (int i = 0; i < values.length; i++) {
            out[i * 4] = (byte) (values[i] >>> 24);
            out[i * 4 + 1] = (byte) (values[i] >>> 16);
            out[i * 4 + 2] = (byte) (values[i] >>> 8);
            out[i * 4 + 3] = (byte) values[i];
        }
        return out;
    }
}
