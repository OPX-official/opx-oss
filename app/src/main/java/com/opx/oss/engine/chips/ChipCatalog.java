package com.opx.oss.engine.chips;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The adapters the engine drives, by USB vendor and product id. This is the engine's own
 * catalogue: what it can actually bring up, not every card the kernel knows.
 */
public final class ChipCatalog {

    /** One entry: the model name and the driver family that brings it up. */
    public static final class Entry {
        public final String model;
        public final String family;

        Entry(String model, String family) {
            this.model = model;
            this.family = family;
        }
    }

    private static final Map<Integer, Entry> BY_PRODUCT = new LinkedHashMap<>();
    /** Which bundled firmware folder each adapter needs, when it needs one at all. */
    private static final Map<Integer, String> FIRMWARE = new LinkedHashMap<>();

    static {
        // Atheros
        put(0x0CF3, 0x9271, "AR9271", "ar9271", "ar9271_v2");
        put(0x0CF3, 0x1006, "AR9271", "ar9271", "ar9271_v2");
        // Ralink / MediaTek
        put(0x148F, 0x5370, "RT5370", "rt2x00", "rt5370");
        put(0x148F, 0x5372, "RT5372", "rt2x00", "rt5372");
        put(0x148F, 0x3070, "RT3070", "rt2x00", "rt3070");
        put(0x148F, 0x3572, "RT3572", "rt2x00", "rt3572");
        put(0x148F, 0x5572, "RT5572", "rt2x00", "rt5572");
        put(0x148F, 0x7601, "MT7601U", "mt76", "mt7601u");
        put(0x0E8D, 0x7610, "MT7610U", "mt76", "mt76x0u");
        put(0x0E8D, 0x7612, "MT7612U", "mt76", "mt76x2u");
        put(0x0E8D, 0x7921, "MT7921AU", "mt76", "mt7921au");
        put(0x0E8D, 0x7925, "MT7925AU", "mt76", "mt7925au");
        // Realtek
        put(0x0BDA, 0x8187, "RTL8187", "rtl8187");
        put(0x0BDA, 0x8179, "RTL8188EU", "rtl8xxxu", "rtl8188eus");
        put(0x0BDA, 0x8178, "RTL8188CU", "rtl8xxxu", "rtl8192cu");
        put(0x0BDA, 0x8812, "RTL8812AU", "rtl88xxau", "rtl8812au");
        put(0x0BDA, 0x8813, "RTL8814AU", "rtl88xxau", "rtl8814au_dkms");
        put(0x0BDA, 0x8811, "RTL8811AU", "rtl88xxau", "rtl8821au");
        put(0x0BDA, 0x8821, "RTL8821AU", "rtl88xxau", "rtl8821au");
        put(0x0BDA, 0xB822, "RTL8822BU", "rtl88xxau", "rtl8822bu");
        put(0x0BDA, 0xB82C, "RTL8822CU", "rtl88xxau", "rtl8822cu");
        put(0x0BDA, 0x8192, "RTL8192FU", "rtl8xxxu", "rtl8192fu");
        put(0x0BDA, 0xA891, "RTL8922AU", "rtl88xxau", "rtl8922au");
    }

    private ChipCatalog() {
    }

    private static void put(int vendor, int product, String model, String family) {
        put(vendor, product, model, family, null);
    }

    private static void put(int vendor, int product, String model, String family, String firmware) {
        int key = key(vendor, product);
        BY_PRODUCT.put(key, new Entry(model, family));
        if (firmware != null) FIRMWARE.put(key, firmware);
    }

    /** The firmware folder this adapter's bring-up needs, or {@code null} when it needs none. */
    public static String firmware(int vendorId, int productId) {
        return FIRMWARE.get(key(vendorId, productId));
    }

    /** The entry for a USB id pair, or {@code null} when the engine has no driver for it. */
    public static Entry resolve(int vendorId, int productId) {
        return BY_PRODUCT.get(key(vendorId, productId));
    }

    /** Model name for a USB id pair, or {@code null}. */
    public static String model(int vendorId, int productId) {
        Entry entry = resolve(vendorId, productId);
        return entry == null ? null : entry.model;
    }

    public static boolean supported(int vendorId, int productId) {
        return resolve(vendorId, productId) != null;
    }

    private static int key(int vendor, int product) {
        return ((vendor & 0xFFFF) << 16) | (product & 0xFFFF);
    }
}
