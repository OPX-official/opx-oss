package com.opx.oss.engine.crypto;

/** CRC-32, the 802.11 frame check sequence. */
public final class Crc32 {

    private static final int[] TABLE = new int[256];

    static {
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            }
            TABLE[n] = c;
        }
    }

    private Crc32() {
    }

    public static int of(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = TABLE[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }

    public static byte[] fcs(byte[] data) {
        int crc = of(data, 0, data.length);
        return new byte[]{(byte) crc, (byte) (crc >>> 8), (byte) (crc >>> 16), (byte) (crc >>> 24)};
    }
}
