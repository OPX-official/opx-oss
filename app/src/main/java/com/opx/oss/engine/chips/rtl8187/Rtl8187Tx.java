package com.opx.oss.engine.chips.rtl8187;

import com.opx.oss.engine.UsbTransport;

import java.io.IOException;

/**
 * Transmit path: the 12-byte descriptor in front of every frame, the sequence number the card
 * does not assign itself, and the bulk write that hands both to the chip.
 * <p>
 * The descriptor carries the frame length in bits 0-11 of its flags word, bit 15 disables
 * hardware encryption (injected frames are already complete), bits 24-27 hold the rate index;
 * the separate length field stays zero, exactly as the kernel writes it.
 */
public final class Rtl8187Tx {

    private static final int PACKET_ALIGNMENT = 64;

    private final UsbTransport transport;
    private int sequence;

    public Rtl8187Tx(UsbTransport transport) {
        this.transport = transport;
    }

    /** The descriptor for one frame at {@code rateIndex}, retried {@code retries} times. */
    public static byte[] descriptor(int frameLength, int rateIndex, int retries) {
        if (frameLength <= 0 || frameLength > 0x0FFF) {
            throw new IllegalArgumentException("frame too long for the descriptor: " + frameLength);
        }
        int flags = (frameLength & 0x0FFF)
                | Rtl8187Regs.TX_FLAG_NO_ENC
                | ((rateIndex & 0x0F) << 24);
        int retry = ((retries - 1) & 0xFF) << 8;
        return new byte[]{
                (byte) flags, (byte) (flags >>> 8), (byte) (flags >>> 16), (byte) (flags >>> 24),
                0x00, 0x00,                       // rts duration
                0x00, 0x00,                       // length: the flags word carries it
                (byte) retry, (byte) (retry >>> 8), 0x00, 0x00
        };
    }

    /**
     * Stamp the frame's sequence-control field. The RTL8187L has no hardware sequence
     * assignment, so without this every injected frame leaves sequence zero and an AP treats a
     * conversation as one frame repeated.
     */
    public int stampSequence(byte[] frame) {
        if (frame.length < 24) return sequence;
        int fragment = frame[22] & 0x0F;
        if (fragment == 0) sequence = (sequence + 0x10) & 0xFFF0;
        int control = sequence | fragment;
        frame[22] = (byte) control;
        frame[23] = (byte) (control >>> 8);
        return sequence;
    }

    /** Prepend the descriptor and write the frame out. */
    public void send(byte[] frame, int rateIndex, int retries) throws IOException {
        byte[] header = descriptor(frame.length, rateIndex, retries);
        byte[] payload = new byte[header.length + frame.length];
        System.arraycopy(header, 0, payload, 0, header.length);
        System.arraycopy(frame, 0, payload, header.length, frame.length);

        int sent = transport.bulkOut(payload, 1000);
        if (sent != payload.length) {
            throw new IOException("bulk write short: " + sent + " of " + payload.length);
        }
        // A transfer that is an exact multiple of the packet size has no terminating short
        // packet, so the chip never fires it: follow it with an empty write.
        if (payload.length % PACKET_ALIGNMENT == 0) {
            transport.bulkOut(new byte[0], 200);
        }
    }
}
