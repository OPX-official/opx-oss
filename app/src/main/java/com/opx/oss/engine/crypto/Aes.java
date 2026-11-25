package com.opx.oss.engine.crypto;

/**
 * AES-128 with CBC, written here so the WSC key wrap needs nothing from the platform.
 * The S-box is derived at class load (GF(2^8) inverse followed by the affine map) rather than
 * typed in, so there is no table to mistype.
 */
public final class Aes {

    private static final int[] SBOX = new int[256];
    private static final int[] INV_SBOX = new int[256];
    private static final int[] RCON = {0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36};

    static {
        for (int value = 0; value < 256; value++) {
            int inverse = value == 0 ? 0 : power(value, 254);
            int s = (rotate(inverse, 1) ^ rotate(inverse, 2) ^ rotate(inverse, 3)
                    ^ rotate(inverse, 4) ^ 0x63) & 0xFF;
            SBOX[value] = s;
            INV_SBOX[s] = value;
        }
    }

    private final int[] roundKeys = new int[176];

    public Aes(byte[] key) {
        if (key.length != 16) throw new IllegalArgumentException("AES-128 needs a 16-byte key");
        System.arraycopy(key, 0, roundKeys, 0, 16);
        int words = 4;
        for (int i = 4; i < 44; i++) {
            int temp = readWord(roundKeys, (i - 1) * 4);
            if (i % 4 == 0) {
                temp = subWord(rotateWord(temp)) ^ (RCON[i / 4 - 1] << 24);
            }
            int previous = readWord(roundKeys, (i - 4) * 4);
            writeWord(roundKeys, i * 4, previous ^ temp);
            words++;
        }
        if (words != 44) throw new IllegalStateException("key schedule is short");
    }

    public byte[] encryptBlock(byte[] block) {
        int[] state = load(block);
        addRoundKey(state, 0);
        for (int round = 1; round < 10; round++) {
            subBytes(state, SBOX);
            shiftRows(state);
            mixColumns(state);
            addRoundKey(state, round);
        }
        subBytes(state, SBOX);
        shiftRows(state);
        addRoundKey(state, 10);
        return store(state);
    }

    public byte[] decryptBlock(byte[] block) {
        int[] state = load(block);
        addRoundKey(state, 10);
        for (int round = 9; round > 0; round--) {
            invShiftRows(state);
            subBytes(state, INV_SBOX);
            addRoundKey(state, round);
            invMixColumns(state);
        }
        invShiftRows(state);
        subBytes(state, INV_SBOX);
        addRoundKey(state, 0);
        return store(state);
    }

    public static byte[] cbcEncrypt(byte[] key, byte[] iv, byte[] data) {
        if (data.length % 16 != 0) throw new IllegalArgumentException("CBC needs whole blocks");
        Aes aes = new Aes(key);
        byte[] out = new byte[data.length];
        byte[] previous = iv.clone();
        for (int offset = 0; offset < data.length; offset += 16) {
            byte[] block = new byte[16];
            for (int i = 0; i < 16; i++) block[i] = (byte) (data[offset + i] ^ previous[i]);
            previous = aes.encryptBlock(block);
            System.arraycopy(previous, 0, out, offset, 16);
        }
        return out;
    }

    public static byte[] cbcDecrypt(byte[] key, byte[] iv, byte[] data) {
        if (data.length % 16 != 0) throw new IllegalArgumentException("CBC needs whole blocks");
        Aes aes = new Aes(key);
        byte[] out = new byte[data.length];
        byte[] previous = iv.clone();
        for (int offset = 0; offset < data.length; offset += 16) {
            byte[] block = new byte[16];
            System.arraycopy(data, offset, block, 0, 16);
            byte[] plain = aes.decryptBlock(block);
            for (int i = 0; i < 16; i++) out[offset + i] = (byte) (plain[i] ^ previous[i]);
            previous = block;
        }
        return out;
    }

    public static byte[] pad(byte[] data) {
        int extra = 16 - (data.length % 16);
        byte[] out = new byte[data.length + extra];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = data.length; i < out.length; i++) out[i] = (byte) extra;
        return out;
    }

    /** Remove PKCS#5 padding, tolerating a block that does not carry any. */
    public static byte[] unpad(byte[] data) {
        if (data.length == 0) return data;
        int extra = data[data.length - 1] & 0xFF;
        if (extra < 1 || extra > 16 || extra > data.length) return data;
        byte[] out = new byte[data.length - extra];
        System.arraycopy(data, 0, out, 0, out.length);
        return out;
    }

    // -- state ---------------------------------------------------------------

    private static int[] load(byte[] block) {
        int[] state = new int[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                state[column * 4 + row] = block[column * 4 + row] & 0xFF;
            }
        }
        return state;
    }

    private static byte[] store(int[] state) {
        byte[] block = new byte[16];
        for (int i = 0; i < 16; i++) block[i] = (byte) state[i];
        return block;
    }

    private void addRoundKey(int[] state, int round) {
        for (int i = 0; i < 16; i++) state[i] ^= roundKeys[round * 16 + i] & 0xFF;
    }

    private static void subBytes(int[] state, int[] box) {
        for (int i = 0; i < 16; i++) state[i] = box[state[i]];
    }

    private static void shiftRows(int[] state) {
        int[] copy = state.clone();
        for (int row = 1; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                state[column * 4 + row] = copy[((column + row) % 4) * 4 + row];
            }
        }
    }

    private static void invShiftRows(int[] state) {
        int[] copy = state.clone();
        for (int row = 1; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                state[column * 4 + row] = copy[((column - row + 4) % 4) * 4 + row];
            }
        }
    }

    private static void mixColumns(int[] state) {
        for (int column = 0; column < 4; column++) {
            int at = column * 4;
            int a0 = state[at], a1 = state[at + 1], a2 = state[at + 2], a3 = state[at + 3];
            state[at] = mul(a0, 2) ^ mul(a1, 3) ^ a2 ^ a3;
            state[at + 1] = a0 ^ mul(a1, 2) ^ mul(a2, 3) ^ a3;
            state[at + 2] = a0 ^ a1 ^ mul(a2, 2) ^ mul(a3, 3);
            state[at + 3] = mul(a0, 3) ^ a1 ^ a2 ^ mul(a3, 2);
        }
    }

    private static void invMixColumns(int[] state) {
        for (int column = 0; column < 4; column++) {
            int at = column * 4;
            int a0 = state[at], a1 = state[at + 1], a2 = state[at + 2], a3 = state[at + 3];
            state[at] = mul(a0, 14) ^ mul(a1, 11) ^ mul(a2, 13) ^ mul(a3, 9);
            state[at + 1] = mul(a0, 9) ^ mul(a1, 14) ^ mul(a2, 11) ^ mul(a3, 13);
            state[at + 2] = mul(a0, 13) ^ mul(a1, 9) ^ mul(a2, 14) ^ mul(a3, 11);
            state[at + 3] = mul(a0, 11) ^ mul(a1, 13) ^ mul(a2, 9) ^ mul(a3, 14);
        }
    }

    // -- arithmetic ----------------------------------------------------------

    private static int mul(int a, int b) {
        int product = 0;
        for (int i = 0; i < 8; i++) {
            if ((b & 1) != 0) product ^= a;
            b >>= 1;
            a = xtime(a);
        }
        return product & 0xFF;
    }

    private static int xtime(int a) {
        a <<= 1;
        if ((a & 0x100) != 0) a ^= 0x11B;
        return a & 0xFF;
    }

    private static int power(int value, int exponent) {
        int result = 1;
        int base = value;
        while (exponent > 0) {
            if ((exponent & 1) != 0) result = mul(result, base);
            base = mul(base, base);
            exponent >>= 1;
        }
        return result;
    }

    /** One-byte rotate used by the affine transform (bit order matters). */
    private static int rotate(int value, int bits) {
        return ((value << bits) | (value >>> (8 - bits))) & 0xFF;
    }

    private static int rotateWord(int word) {
        return ((word << 8) | (word >>> 24)) & 0xFFFFFFFF;
    }

    private static int subWord(int word) {
        return (SBOX[(word >>> 24) & 0xFF] << 24)
                | (SBOX[(word >>> 16) & 0xFF] << 16)
                | (SBOX[(word >>> 8) & 0xFF] << 8)
                | SBOX[word & 0xFF];
    }

    private static int readWord(int[] buffer, int offset) {
        return ((buffer[offset] & 0xFF) << 24) | ((buffer[offset + 1] & 0xFF) << 16)
                | ((buffer[offset + 2] & 0xFF) << 8) | (buffer[offset + 3] & 0xFF);
    }

    private static void writeWord(int[] buffer, int offset, int word) {
        buffer[offset] = (word >>> 24) & 0xFF;
        buffer[offset + 1] = (word >>> 16) & 0xFF;
        buffer[offset + 2] = (word >>> 8) & 0xFF;
        buffer[offset + 3] = word & 0xFF;
    }
}
