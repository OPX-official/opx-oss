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

    static {
        // Atheros
        put(0x0CF3, 0x9271, "AR9271", "ar9271");
        put(0x0CF3, 0x1006, "AR9271", "ar9271");
        // Ralink / MediaTek
        put(0x148F, 0x5370, "RT5370", "rt2x00");
        put(0x148F, 0x5372, "RT5372", "rt2x00");
        put(0x148F, 0x3070, "RT3070", "rt2x00");
        put(0x148F, 0x3572, "RT3572", "rt2x00");
        put(0x148F, 0x5572, "RT5572", "rt2x00");
        put(0x148F, 0x7601, "MT7601U", "mt76");
        put(0x0E8D, 0x7610, "MT7610U", "mt76");
        put(0x0E8D, 0x7612, "MT7612U", "mt76");
        put(0x0E8D, 0x7921, "MT7921AU", "mt76");
        // Realtek
        put(0x0BDA, 0x8187, "RTL8187", "rtl8187");
        put(0x0BDA, 0x8179, "RTL8188EU", "rtl8xxxu");
        put(0x0BDA, 0x8178, "RTL8188CU", "rtl8xxxu");
        put(0x0BDA, 0x8812, "RTL8812AU", "rtl88xxau");
        put(0x0BDA, 0x8813, "RTL8814AU", "rtl88xxau");
        put(0x0BDA, 0x8811, "RTL8811AU", "rtl88xxau");
        put(0x0BDA, 0x8821, "RTL8821AU", "rtl88xxau");
        put(0x0BDA, 0xB822, "RTL8822BU", "rtl88xxau");
    }

    private ChipCatalog() {
    }

    private static void put(int vendor, int product, String model, String family) {
        BY_PRODUCT.put(key(vendor, product), new Entry(model, family));
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
