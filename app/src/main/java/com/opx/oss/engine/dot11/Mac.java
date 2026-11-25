package com.opx.oss.engine.dot11;

import java.security.SecureRandom;
import java.util.Locale;

/** A MAC address in its two forms (6 bytes on the wire, colon hex for the app), and the header. */
public final class Mac {

    public static final byte[] BROADCAST = {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
    };

    private static final SecureRandom RANDOM = new SecureRandom();

    private Mac() {
    }

    public static byte[] of(String mac) {
        if (mac == null) return new byte[6];
        String[] parts = mac.trim().split(":");
        if (parts.length != 6) return new byte[6];
        byte[] out = new byte[6];
        try {
            for (int i = 0; i < 6; i++) out[i] = (byte) Integer.parseInt(parts[i], 16);
        } catch (NumberFormatException e) {
            return new byte[6];
        }
        return out;
    }

    public static String text(byte[] mac) {
        if (mac == null || mac.length != 6) return "00:00:00:00:00:00";
        StringBuilder text = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) text.append(':');
            text.append(String.format(Locale.US, "%02x", mac[i] & 0xFF));
        }
        return text.toString();
    }

    /** A 24-byte 802.11 header with a zeroed sequence-control field. */
    public static byte[] header(int fc0, int fc1, byte[] addr1, byte[] addr2, byte[] addr3) {
        byte[] header = new byte[24];
        header[0] = (byte) fc0;
        header[1] = (byte) fc1;
        System.arraycopy(addr1, 0, header, 4, 6);
        System.arraycopy(addr2, 0, header, 10, 6);
        System.arraycopy(addr3, 0, header, 16, 6);
        return header;
    }

    /** Header length from the two frame-control octets (4-address, QoS and HT control aware). */
    public static int headerLength(int fc0, int fc1) {
        int length = 24;
        if ((fc1 & 0x01) != 0 && (fc1 & 0x02) != 0) length += 6;
        if ((((fc0 & 0xF0) >> 4) & 0x08) != 0) length += 2;
        if ((fc1 & 0x80) != 0) length += 4;
        return length;
    }

    /** Random locally-administered unicast MAC (0x02 prefix), for a synthetic client. */
    public static byte[] randomClient() {
        byte[] mac = new byte[6];
        RANDOM.nextBytes(mac);
        mac[0] = (byte) ((mac[0] & 0xFC) | 0x02);
        return mac;
    }

    /** True for a multicast/broadcast address: never a client. */
    public static boolean isGroup(String mac) {
        try {
            int first = Integer.parseInt(mac.split(":", 2)[0], 16);
            return (first & 1) != 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
