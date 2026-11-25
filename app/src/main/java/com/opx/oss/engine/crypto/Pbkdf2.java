package com.opx.oss.engine.crypto;

/** PBKDF2-HMAC-SHA1: the WPA passphrase-to-PMK step, 4096 rounds. */
public final class Pbkdf2 {

    private Pbkdf2() {
    }

    public static byte[] hmacSha1(byte[] password, byte[] salt, int iterations, int length) {
        byte[] out = new byte[length];
        int blocks = (length + 19) / 20;
        int written = 0;
        for (int block = 1; block <= blocks; block++) {
            byte[] counter = {
                    (byte) (block >>> 24), (byte) (block >>> 16),
                    (byte) (block >>> 8), (byte) block
            };
            byte[] u = Hmac.hmacSha1(password, Hmac.concat(salt, counter));
            byte[] result = u.clone();
            for (int round = 1; round < iterations; round++) {
                u = Hmac.hmacSha1(password, u);
                for (int i = 0; i < result.length; i++) result[i] ^= u[i];
            }
            int copy = Math.min(20, length - written);
            System.arraycopy(result, 0, out, written, copy);
            written += copy;
        }
        return out;
    }
}
