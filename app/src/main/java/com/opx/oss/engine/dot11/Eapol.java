package com.opx.oss.engine.dot11;

/**
 * One EAPOL-Key frame of the four-way handshake, read out of its data frame.
 * <p>
 * Offsets are relative to the start of the 802.1X payload: 1 type, 2 length, 5 key information
 * (big endian), 9 replay counter, 17 key nonce, 81 key MIC, 97 key-data length, 99 key data.
 */
public final class Eapol {

    public static final int TYPE_KEY = 3;
    public static final int MIC_OFFSET = 81;
    public static final int MIC_LENGTH = 16;
    public static final int NONCE_LENGTH = 32;
    public static final int KEY_DESCRIPTOR_RSN = 2;


    private static final byte[] LLC_SNAP = {
            (byte) 0xAA, (byte) 0xAA, 0x03, 0x00, 0x00, 0x00, (byte) 0x88, (byte) 0x8E
    };
    private static final byte[] PMKID_OUI = {0x00, 0x0F, (byte) 0xAC};

    public final byte[] frame;
    public final int keyInfo;
    public final long replay;
    public final byte[] nonce;
    public final byte[] mic;
    public final byte[] keyData;
    public final int messageNumber;
    public final byte[] pmkid;

    private Eapol(byte[] frame, int keyInfo, long replay, byte[] nonce, byte[] mic,
                  byte[] keyData, int messageNumber, byte[] pmkid) {
        this.frame = frame;
        this.keyInfo = keyInfo;
        this.replay = replay;
        this.nonce = nonce;
        this.mic = mic;
        this.keyData = keyData;
        this.messageNumber = messageNumber;
        this.pmkid = pmkid;
    }

    /**
     * The EAPOL-Key in this data frame, or {@code null} when it carries none. The LLC/SNAP
     * header is searched for, because a device may pad the 802.11 header for alignment.
     */
    public static Eapol parse(byte[] frame, int headerLength) {
        if (frame == null) return null;
        int searchEnd = Math.min(frame.length, headerLength + 16);
        int signature = indexOf(frame, LLC_SNAP, headerLength, searchEnd);
        if (signature < 0) return null;
        int start = signature + LLC_SNAP.length;
        if (frame.length < start + 4) return null;
        if ((frame[start + 1] & 0xFF) != TYPE_KEY) return null;

        int bodyLength = be16(frame, start + 2);
        int end = start + 4 + bodyLength;
        if (frame.length < start + 99 || end < start + 99) return null;
        int keyDataLength = be16(frame, start + 97);
        if (start + 99 + keyDataLength > end) return null;

        int keyInfo = be16(frame, start + 5);
        long replay = 0;
        for (int i = start + 9; i < start + 17; i++) replay = (replay << 8) | (frame[i] & 0xFF);
        byte[] nonce = slice(frame, start + 17, NONCE_LENGTH);
        byte[] mic = slice(frame, start + MIC_OFFSET, MIC_LENGTH);
        int dataEnd = Math.min(start + 99 + keyDataLength, Math.min(end, frame.length));
        byte[] keyData = dataEnd > start + 99 ? slice(frame, start + 99, dataEnd - start - 99) : new byte[0];

        int messageNumber = classify(keyInfo, keyData.length);
        byte[] pmkid = extractPmkid(keyData);
        return new Eapol(slice(frame, start, Math.min(end, frame.length) - start),
                keyInfo, replay, nonce, mic, keyData, messageNumber, pmkid);
    }

    /**
     * M1: ACK, no MIC, no install. M3: ACK, MIC, install. M2: no ACK, MIC, key data present.
     * M4: no ACK, MIC, no key data. 0 for anything else (group rekey, malformed flags).
     */
    private static int classify(int keyInfo, int keyDataLength) {
        boolean install = (keyInfo & 0x0040) != 0;
        boolean ack = (keyInfo & 0x0080) != 0;
        boolean mic = (keyInfo & 0x0100) != 0;
        if (ack && !mic && !install) return 1;
        if (ack && mic && install) return 3;
        if (!ack && mic && !install) return keyDataLength > 0 ? 2 : 4;
        return 0;
    }

    /** The PMKID KDE at the start of the key data (M1), or {@code null}. */
    private static byte[] extractPmkid(byte[] keyData) {
        int i = 0;
        while (i + 2 <= keyData.length) {
            int type = keyData[i] & 0xFF;
            int length = keyData[i + 1] & 0xFF;
            int end = i + 2 + length;
            if (end > keyData.length) break;
            if (type == 0xDD && length >= 20
                    && (keyData[i + 2] & 0xFF) == (PMKID_OUI[0] & 0xFF)
                    && (keyData[i + 3] & 0xFF) == (PMKID_OUI[1] & 0xFF)
                    && (keyData[i + 4] & 0xFF) == (PMKID_OUI[2] & 0xFF)
                    && (keyData[i + 5] & 0xFF) == 0x04) {
                byte[] pmkid = slice(keyData, i + 6, 16);
                boolean zero = true;
                for (byte value : pmkid) if (value != 0) zero = false;
                if (!zero) return pmkid;
            }
            i = end;
        }
        return null;
    }

    /**
     * Build an EAPOL-Key payload (802.1X version 2). Multi-byte fields are network order; the
     * MIC goes in zeroed and is spliced back with {@link #withMic} once it is computed.
     */
    public static byte[] buildKey(int keyInfo, int keyLength, long replay, byte[] nonce,
                                 byte[] keyData, byte[] mic) {
        if (nonce.length != NONCE_LENGTH) throw new IllegalArgumentException("nonce must be 32 bytes");
        if (mic.length != MIC_LENGTH) throw new IllegalArgumentException("mic must be 16 bytes");
        byte[] data = keyData == null ? new byte[0] : keyData;
        int bodyLength = 95 + data.length;
        byte[] out = new byte[4 + bodyLength];
        out[0] = 0x02;                       // 802.1X version 2
        out[1] = TYPE_KEY;
        out[2] = (byte) (bodyLength >> 8);
        out[3] = (byte) bodyLength;
        int at = 4;
        out[at++] = (byte) KEY_DESCRIPTOR_RSN;
        out[at++] = (byte) (keyInfo >> 8);
        out[at++] = (byte) keyInfo;
        out[at++] = (byte) (keyLength >> 8);
        out[at++] = (byte) keyLength;
        for (int i = 7; i >= 0; i--) out[at++] = (byte) (replay >>> (8 * i));
        System.arraycopy(nonce, 0, out, at, NONCE_LENGTH);
        at += NONCE_LENGTH;
        at += 16 + 8 + 8;                    // Key IV, Key RSC, Key ID
        System.arraycopy(mic, 0, out, at, MIC_LENGTH);
        at += MIC_LENGTH;
        out[at++] = (byte) (data.length >> 8);
        out[at++] = (byte) data.length;
        System.arraycopy(data, 0, out, at, data.length);
        return out;
    }

    /** The payload with its MIC field replaced. */
    public static byte[] withMic(byte[] payload, byte[] mic) {
        if (mic.length != MIC_LENGTH) throw new IllegalArgumentException("mic must be 16 bytes");
        byte[] out = payload.clone();
        System.arraycopy(mic, 0, out, MIC_OFFSET, MIC_LENGTH);
        return out;
    }

    /** The same frame with its MIC zeroed: what the MIC is computed over. */
    public byte[] withoutMic() {
        byte[] copy = frame.clone();
        if (copy.length >= MIC_OFFSET + MIC_LENGTH) {
            for (int i = MIC_OFFSET; i < MIC_OFFSET + MIC_LENGTH; i++) copy[i] = 0;
        }
        return copy;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from, int to) {
        outer:
        for (int i = from; i + needle.length <= to; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int be16(byte[] data, int offset) {
        if (offset + 2 > data.length) return 0;
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        if (offset < 0 || length <= 0 || offset + length > data.length) return new byte[0];
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }
}
