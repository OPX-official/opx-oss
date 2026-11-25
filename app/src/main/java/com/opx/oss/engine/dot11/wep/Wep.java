package com.opx.oss.engine.dot11.wep;

import java.util.Arrays;

/** The fixed parts of WEP that make an ARP packet readable without the key. */
public final class Wep {

    /**
     * First 16 bytes of a WEP-encrypted ARP request: LLC/SNAP, EtherType ARP, Ethernet/IPv4,
     * 6/4 byte addresses and opcode 1.
     */
    public static final byte[] ARP_REQUEST = {
            (byte) 0xAA, (byte) 0xAA, 0x03, 0x00, 0x00, 0x00, 0x08, 0x06,
            0x00, 0x01, 0x08, 0x00, 0x06, 0x04, 0x00, 0x01
    };

    private Wep() {
    }

    /** Keystream bytes from an ARP ciphertext prefix, by XOR with the known plaintext. */
    public static byte[] keystreamFromArp(byte[] cipher, int offset, int length) {
        int count = Math.min(length, ARP_REQUEST.length);
        byte[] keystream = new byte[count];
        for (int i = 0; i < count; i++) keystream[i] = (byte) (cipher[offset + i] ^ ARP_REQUEST[i]);
        return keystream;
    }

    /** The 24-bit IV of a protected frame, at the start of its body. */
    public static byte[] iv(byte[] frame, int headerLength) {
        return Arrays.copyOfRange(frame, headerLength, headerLength + 3);
    }
}
