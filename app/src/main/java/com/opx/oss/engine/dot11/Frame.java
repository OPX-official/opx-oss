package com.opx.oss.engine.dot11;

/** 802.11 frame construction: the management frames the engine transmits itself. */
public final class Frame {

    public static final int TYPE_MGMT = 0;
    public static final int TYPE_CONTROL = 1;
    public static final int TYPE_DATA = 2;

    public static final int SUBTYPE_ASSOC_REQ = 0;
    public static final int SUBTYPE_ASSOC_RESP = 1;
    public static final int SUBTYPE_REASSOC_REQ = 2;
    public static final int SUBTYPE_PROBE_REQ = 4;
    public static final int SUBTYPE_PROBE_RESP = 5;
    public static final int SUBTYPE_BEACON = 8;
    public static final int SUBTYPE_DISASSOC = 10;
    public static final int SUBTYPE_AUTH = 11;
    public static final int SUBTYPE_DEAUTH = 12;
    public static final int SUBTYPE_ACTION = 13;

    /** The LLC/SNAP header in front of an EAPOL frame. */
    public static final byte[] LLC_SNAP_EAPOL = {
            (byte) 0xAA, (byte) 0xAA, 0x03, 0x00, 0x00, 0x00, (byte) 0x88, (byte) 0x8E
    };

    private Frame() {
    }

    public static int fc0(int type, int subtype) {
        return (subtype << 4) | (type << 2);
    }

    public static boolean isManagement(int fc0) {
        return ((fc0 >> 2) & 0x03) == TYPE_MGMT;
    }

    public static int subtype(int fc0) {
        return (fc0 >> 4) & 0x0F;
    }

    /** A deauthentication frame from the BSSID to one station. */
    public static byte[] deauth(byte[] bssid, byte[] destination, int reason) {
        byte[] header = Mac.header(fc0(TYPE_MGMT, SUBTYPE_DEAUTH), 0, destination, bssid, bssid);
        return Mac.concat(header, new byte[]{(byte) reason, (byte) (reason >> 8)});
    }

    /** A broadcast deauthentication frame; every station of that BSSID is addressed. */
    public static byte[] deauthAll(byte[] bssid, int reason) {
        return deauth(bssid, Mac.BROADCAST, reason);
    }

    /** Open-system authentication, request (sequence 1) or the client's answer (sequence 2). */
    public static byte[] authOpen(byte[] bssid, byte[] station, int sequence) {
        byte[] header = Mac.header(fc0(TYPE_MGMT, SUBTYPE_AUTH), 0, bssid, station, bssid);
        return Mac.concat(header, new byte[]{0x00, 0x00, (byte) sequence, 0x00, 0x00, 0x00});
    }

    /** A probe request: the wildcard SSID when {@code ssid} is null, else that SSID. */
    public static byte[] probeRequest(String ssid, byte[] station, int channel) {
        byte[] header = Mac.header(fc0(TYPE_MGMT, SUBTYPE_PROBE_REQ), 0,
                Mac.BROADCAST, station, Mac.BROADCAST);
        byte[] ssidElement = ssid == null ? new byte[]{0x00, 0x00} : Ie.ssid(ssid);
        return Mac.concat(header, ssidElement, Ie.rates(channel),
                Ie.extRates(channel), Ie.htCapabilities());
    }

    /**
     * An association request carrying the client's RSN element: the frame whose answer (or
     * whose M1) starts the four-way handshake the PMKID attack reads.
     */
    public static byte[] assocRequest(byte[] bssid, byte[] station, String ssid, int channel,
                                      byte[] rsnElement) {
        byte[] header = Mac.header(fc0(TYPE_MGMT, SUBTYPE_ASSOC_REQ), 0, bssid, station, bssid);
        byte[] fixed = new byte[]{
                0x11, 0x04,          // capability: ESS + privacy + short slot
                0x0A, 0x00           // listen interval
        };
        return Mac.concat(header, fixed, Ie.ssid(ssid), Ie.rates(channel), Ie.extRates(channel),
                Ie.htCapabilities(), rsnElement);
    }

    /** The EAPOL-Key body of a data frame, or {@code null} when the frame carries none. */
    public static int eapolOffset(byte[] frame, int headerLength) {
        int at = headerLength;
        if ((frame.length >= at + 8) && (frame[at] & 0xFF) == 0xAA && (frame[at + 1] & 0xFF) == 0xAA) {
            if ((frame[at + 6] & 0xFF) == 0x88 && (frame[at + 7] & 0xFF) == 0x8E) return at + 8;
            if ((frame[at + 6] & 0xFF) == 0x81 && (frame[at + 7] & 0xFF) == 0x00) return at + 8;
        }
        return -1;
    }
}
