package com.opx.oss;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * The VID:PID pairs wifi4 can actually drive, shipped as an APK asset and generated from
 * wifi4's own chip catalogue (see {@code tools/gen-usb-ids.py}).
 * <p>
 * The app asks Android for USB permission for these devices only: a keyboard, mouse or thumb
 * drive plugged into the same hub must not raise a Wi-Fi permission dialog. ZeroCD stubs are
 * included because those adapters first appear as a fake CD-ROM with a different VID:PID and
 * wifi4 has to eject them before the real Wi-Fi device enumerates.
 * <p>
 * The list is advisory, not a gate: when the asset is missing or empty every device counts as
 * known, so an outdated list can never leave the app unable to ask for a real adapter.
 */
public final class UsbIds {

    private static final String TAG = "OpxUsbIds";
    private static final String ASSET = "usb_ids.json";

    private static Set<Integer> known;
    private static boolean loaded;

    private UsbIds() {
    }

    /** True when {@code device} is a Wi-Fi adapter (or its ZeroCD stub), or when the list is unavailable. */
    public static synchronized boolean known(Context context, UsbDevice device) {
        if (device == null) return false;
        Set<Integer> ids = load(context);
        if (ids.isEmpty()) return true;
        return ids.contains(key(device.getVendorId(), device.getProductId()));
    }

    private static int key(int vendor, int product) {
        return ((vendor & 0xffff) << 16) | (product & 0xffff);
    }

    private static Set<Integer> load(Context context) {
        if (loaded) return known;
        loaded = true;
        known = new HashSet<>();
        if (context == null) return known;
        try (InputStream in = context.getAssets().open(ASSET);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            JSONObject root = new JSONObject(text.toString());
            addAll(root.optJSONArray("supported"));
            addAll(root.optJSONArray("zerocd"));
            Log.i(TAG, "USB catalogue: " + known.size() + " VID:PID pairs");
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + ASSET + "; every device is treated as an adapter", e);
            known.clear();
        }
        return known;
    }

    private static void addAll(JSONArray pairs) {
        if (pairs == null) return;
        for (int i = 0; i < pairs.length(); i++) {
            String pair = pairs.optString(i, "");
            String[] parts = pair.split(":");
            if (parts.length != 2) continue;
            try {
                int vendor = Integer.parseInt(parts[0].trim(), 16);
                int product = Integer.parseInt(parts[1].trim(), 16);
                known.add(key(vendor, product));
            } catch (NumberFormatException ignored) {
                // One malformed row must not discard the rest of the catalogue.
            }
        }
    }

}
