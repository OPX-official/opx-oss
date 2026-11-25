package com.opx.oss.engine.chips;

import android.content.Context;

import com.opx.oss.engine.chips.rtl8187.Rtl8187Chip;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which USB id pairs the engine can actually bring up. This is separate from {@link ChipCatalog},
 * which also names adapters the engine recognises but has no driver for; here a missing entry is an
 * error the UI can show, not a silent failure.
 */
public final class ChipRegistry {

    /** Builds a driver for one opened device path. */
    public interface Factory {
        Driver create(Context context, String path);
    }

    private static final Map<Integer, Factory> FACTORIES = new LinkedHashMap<>();

    static {
        // Realtek RTL8187L (AWUS036H and friends): the only family ported so far.
        FACTORIES.put(key(0x0BDA, 0x8187), Rtl8187Chip::new);
    }

    private ChipRegistry() {
    }

    /** True when this id pair has a driver behind it, in Java or in the embedded Python tree. */
    public static boolean hasDriver(Context context, int vendorId, int productId) {
        if (FACTORIES.containsKey(key(vendorId, productId))) return true;
        try {
            return PythonChip.supports(context, vendorId, productId);
        } catch (RuntimeException noPython) {
            return false;
        }
    }

    /**
     * Open the device with its family's driver and run the staged bring-up.
     *
     * @throws BringUpError when no driver exists for the id pair, naming the family the catalogue
     *     says it needs, so the gap is visible instead of pretending to support the card.
     */
    public static Driver open(Context context, int vendorId, int productId, String path)
            throws IOException {
        Factory factory = FACTORIES.get(key(vendorId, productId));
        if (factory == null && PythonChip.supports(context, vendorId, productId)) {
            // The family is vendored from wifi4 and runs in the embedded Python runtime.
            Driver driver = new PythonChip(context, path);
            driver.connect(null);
            return driver;
        }
        if (factory == null) {
            ChipCatalog.Entry entry = ChipCatalog.resolve(vendorId, productId);
            String model = entry == null ? "unknown adapter" : entry.model;
            String family = entry == null ? "unknown" : entry.family;
            throw new BringUpError("driver",
                    String.format("%s (%04x:%04x) needs the %s family, which is not ported yet",
                            model, vendorId, productId, family));
        }
        Driver driver = factory.create(context, path);
        driver.connect(null);
        return driver;
    }

    private static int key(int vendor, int product) {
        return ((vendor & 0xFFFF) << 16) | (product & 0xFFFF);
    }
}
