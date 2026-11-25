package com.opx.oss.engine.crypto;

/**
 * AES-CMAC (RFC 4493) over a 16-byte key, the MIC of key descriptor version 3
 * (AES-128-CMAC, used by PSK-SHA256 and 802.11w).
 */
public final class Cmac {

    private static final byte[] RB = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0x87
    };

    private Cmac() {
    }

    public static byte[] cmac(byte[] key, byte[] message) {
        Aes aes = new Aes(key);
        byte[] zero = new byte[16];
        byte[] k1 = dbl(aes.encryptBlock(zero));
        byte[] k2 = dbl(k1);

        int blocks = (message.length + 15) / 16;
        byte[] last;
        boolean complete = message.length > 0 && message.length % 16 == 0;
        if (complete) {
            last = new byte[16];
            System.arraycopy(message, message.length - 16, last, 0, 16);
            xor(last, k1);
        } else {
            int tailLength = message.length % 16;
            last = new byte[16];
            System.arraycopy(message, message.length - tailLength, last, 0, tailLength);
            last[tailLength] = (byte) 0x80;
            xor(last, k2);
        }

        byte[] x = new byte[16];
        for (int block = 0; block < blocks - 1; block++) {
            byte[] chunk = new byte[16];
            System.arraycopy(message, block * 16, chunk, 0, 16);
            xor(chunk, x);
            x = aes.encryptBlock(chunk);
        }
        xor(last, x);
        return aes.encryptBlock(last);
    }

    private static byte[] dbl(byte[] block) {
        byte[] out = new byte[16];
        int carry = 0;
        for (int i = 15; i >= 0; i--) {
            int value = block[i] & 0xFF;
            out[i] = (byte) ((value << 1) | carry);
            carry = (value >>> 7) & 1;
        }
        if (carry != 0) {
            for (int i = 0; i < 16; i++) out[i] ^= RB[i];
        }
        return out;
    }

    private static void xor(byte[] target, byte[] other) {
        for (int i = 0; i < 16; i++) target[i] ^= other[i];
    }
}
