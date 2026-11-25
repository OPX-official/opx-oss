package com.opx.oss.engine.dot11;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 802.11 information elements: walking them and building the client's own set. */
public final class Ie {

    public static final int SSID = 0;
    public static final int RATES = 1;
    public static final int DS_PARAM = 3;
    public static final int RSN = 48;
    public static final int HT_CAP = 45;
    public static final int HT_INFO = 61;
    public static final int EXT_RATES = 50;
    public static final int VENDOR = 221;

    private static final byte[] WPA_OUI = {0x00, 0x50, (byte) 0xF2, 0x01};
    private static final byte[] WPS_OUI = {0x00, 0x50, (byte) 0xF2, 0x04};

    /** One information element: its tag, its body and the raw bytes including tag and length. */
    public static final class Element {
        public final int tag;
        public final byte[] body;
        public final byte[] raw;

        Element(int tag, byte[] body, byte[] raw) {
            this.tag = tag;
            this.body = body;
            this.raw = raw;
        }
    }

    private Ie() {
    }

    public static List<Element> parse(byte[] data, int start) {
        List<Element> elements = new ArrayList<>();
        int i = Math.max(0, start);
        while (i + 2 <= data.length) {
            int tag = data[i] & 0xFF;
            int length = data[i + 1] & 0xFF;
            int end = i + 2 + length;
            if (end > data.length) break;
            byte[] body = new byte[length];
            System.arraycopy(data, i + 2, body, 0, length);
            byte[] raw = new byte[length + 2];
            System.arraycopy(data, i, raw, 0, length + 2);
            elements.add(new Element(tag, body, raw));
            i = end;
        }
        return elements;
    }

    public static Element first(List<Element> elements, int tag) {
        for (Element element : elements) if (element.tag == tag) return element;
        return null;
    }

    public static boolean isWpa(List<Element> elements) {
        for (Element element : elements) {
            if (element.tag == VENDOR && startsWith(element.body, WPA_OUI)) return true;
        }
        return false;
    }

    public static boolean isWps(List<Element> elements) {
        for (Element element : elements) {
            if (element.tag == VENDOR && startsWith(element.body, WPS_OUI)) return true;
        }
        return false;
    }

    public static byte[] ssid(String ssid) {
        byte[] raw = ssid.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(raw.length, 32);
        byte[] out = new byte[length + 2];
        out[0] = 0x00;
        out[1] = (byte) length;
        System.arraycopy(raw, 0, out, 2, length);
        return out;
    }

    private static final byte[] RATES_24 = {
            (byte) 0x82, (byte) 0x84, (byte) 0x8B, (byte) 0x96, 0x0C, 0x12, 0x18, 0x24
    };
    private static final byte[] RATES_5 = {
            (byte) 0x8C, 0x12, (byte) 0x98, 0x24, (byte) 0xB0, 0x48, 0x60, 0x6C
    };
    private static final byte[] EXT_RATES_24 = {0x30, 0x48, 0x60, 0x6C};

    public static byte[] rates(int channel) {
        byte[] rates = channel > 14 ? RATES_5 : RATES_24;
        byte[] out = new byte[rates.length + 2];
        out[0] = (byte) RATES;
        out[1] = (byte) rates.length;
        System.arraycopy(rates, 0, out, 2, rates.length);
        return out;
    }

    public static byte[] extRates(int channel) {
        if (channel > 14) return new byte[0];
        byte[] out = new byte[EXT_RATES_24.length + 2];
        out[0] = (byte) EXT_RATES;
        out[1] = (byte) EXT_RATES_24.length;
        System.arraycopy(EXT_RATES_24, 0, out, 2, EXT_RATES_24.length);
        return out;
    }

    public static byte[] htCapabilities() {
        byte[] out = new byte[26];
        out[0] = (byte) HT_CAP;
        out[1] = 0x1A;
        out[2] = 0x2D;
        out[3] = 0x01;
        out[4] = 0x1B;
        out[5] = (byte) 0xFF;
        return out;
    }

    public static byte[] dsParam(int channel) {
        return new byte[]{(byte) DS_PARAM, 0x01, (byte) (channel & 0xFF)};
    }

    /** The generic WPA2-PSK/CCMP RSN element, used when an AP's own RSN is unusable. */
    public static final byte[] GENERIC_RSN = {
            0x30, 0x14, 0x01, 0x00,
            0x00, 0x0F, (byte) 0xAC, 0x04, 0x01, 0x00, 0x00,
            0x00, 0x0F, (byte) 0xAC, 0x04, 0x01, 0x00, 0x00,
            0x00, 0x0F, (byte) 0xAC, 0x02, 0x00, 0x00
    };

    private static final int AKM_PSK = 2;

    /**
     * Rewrite the AP's RSN element to a single PSK AKM over the AP's ciphers, dropping its
     * PMKID list: a client that echoes the AP's full AKM list is ignored, and one PSK AKM
     * keeps the four-way handshake on the PSK path. {@code null} when the element is malformed
     * and the caller should fall back to {@link #GENERIC_RSN}.
     */
    public static byte[] forcePskAkm(byte[] rsnRaw, boolean pmfCapable) {
        if (rsnRaw == null || rsnRaw.length < 10 || (rsnRaw[0] & 0xFF) != RSN) return null;
        int length = rsnRaw[1] & 0xFF;
        if (length < 8) return null;
        byte[] body = new byte[length];
        System.arraycopy(rsnRaw, 2, body, 0, length);

        int pairwiseCount = (body[6] & 0xFF) | ((body[7] & 0xFF) << 8);
        int akmOffset = 8 + 4 * pairwiseCount;
        if (akmOffset + 2 > body.length) return null;
        int akmCount = (body[akmOffset] & 0xFF) | ((body[akmOffset + 1] & 0xFF) << 8);
        if (akmOffset + 2 + 4 * akmCount > body.length) return null;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(body, 0, akmOffset);
        out.write(0x01);
        out.write(0x00);
        out.write(0x00);
        out.write(0x0F);
        out.write(0xAC);
        out.write(AKM_PSK);
        if (pmfCapable) {
            out.write(0x80);   // RSN caps, MFPC=1, MFPR=0
            out.write(0x00);
            out.write(0x00);   // PMKID count = 0
            out.write(0x00);
            out.write(0x00);   // group-mgmt: BIP-CMAC-128
            out.write(0x0F);
            out.write(0xAC);
            out.write(0x06);
        } else {
            out.write(0x00);
            out.write(0x00);
        }

        byte[] rebuilt = out.toByteArray();
        byte[] element = new byte[rebuilt.length + 2];
        element[0] = (byte) RSN;
        element[1] = (byte) rebuilt.length;
        System.arraycopy(rebuilt, 0, element, 2, rebuilt.length);
        return element;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }
}
