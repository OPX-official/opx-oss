package com.opx.oss.engine.crypto;

/** HMAC over either hash above, with the 64-byte block size both use. */
public final class Hmac {

    /** A hash this HMAC can wrap. */
    public interface Hash {
        byte[] digest(byte[] message);
    }

    public static final Hash SHA1 = Sha1::digest;
    public static final Hash SHA256 = Sha256::digest;

    private static final int BLOCK = 64;

    private Hmac() {
    }

    public static byte[] hmac(Hash hash, byte[] key, byte[] message) {
        byte[] block = new byte[BLOCK];
        if (key.length > BLOCK) {
            byte[] reduced = hash.digest(key);
            System.arraycopy(reduced, 0, block, 0, reduced.length);
        } else {
            System.arraycopy(key, 0, block, 0, key.length);
        }
        byte[] inner = new byte[BLOCK];
        byte[] outer = new byte[BLOCK];
        for (int i = 0; i < BLOCK; i++) {
            inner[i] = (byte) (block[i] ^ 0x36);
            outer[i] = (byte) (block[i] ^ 0x5C);
        }
        byte[] innerHash = hash.digest(concat(inner, message));
        return hash.digest(concat(outer, innerHash));
    }

    public static byte[] hmacSha1(byte[] key, byte[] message) {
        return hmac(SHA1, key, message);
    }

    public static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
