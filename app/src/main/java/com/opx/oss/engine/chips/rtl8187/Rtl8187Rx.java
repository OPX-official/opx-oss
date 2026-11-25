package com.opx.oss.engine.chips.rtl8187;

/**
 * Receive path: each bulk read carries one frame followed by a trailer that says how long it is,
 * how strong the signal was and whether the checksum matched.
 * <p>
 * The card is configured to include the four-byte FCS, so it is stripped here before the frame
 * reaches the parser.
 */
public final class Rtl8187Rx {

    /** One received frame, ready for the parser. */
    public static final class Received {
        public final byte[] mpdu;
        public final int rssi;
        public final int rateIndex;
        public final boolean checksumError;

        Received(byte[] mpdu, int rssi, int rateIndex, boolean checksumError) {
            this.mpdu = mpdu;
            this.rssi = rssi;
            this.rateIndex = rateIndex;
            this.checksumError = checksumError;
        }
    }

    private Rtl8187Rx() {
    }

    /** Decode one bulk read, or {@code null} when it cannot be a frame. */
    public static Received parse(byte[] buffer, int received) {
        if (buffer == null) return null;
        int length = received <= 0 ? buffer.length : Math.min(received, buffer.length);
        int headerSize = Rtl8187Regs.RX_HDR_SIZE_8187L;
        if (length < headerSize) return null;

        int trailer = length - headerSize;
        int flags = (buffer[trailer] & 0xFF)
                | ((buffer[trailer + 1] & 0xFF) << 8)
                | ((buffer[trailer + 2] & 0xFF) << 16)
                | ((buffer[trailer + 3] & 0xFF) << 24);
        int agc = buffer[trailer + 6] & 0xFF;

        int frameLength = flags & 0x0FFF;
        if (frameLength < 4 || frameLength > trailer) return null;

        int rssi = -4 - ((27 * agc) >> 6);
        int rateIndex = (flags >>> 20) & 0x0F;
        boolean checksumError = (flags & Rtl8187Regs.RX_FLAG_CRC32_ERR) != 0;

        byte[] mpdu = new byte[frameLength - 4];
        System.arraycopy(buffer, 0, mpdu, 0, mpdu.length);
        return new Received(mpdu, rssi, rateIndex, checksumError);
    }
}
