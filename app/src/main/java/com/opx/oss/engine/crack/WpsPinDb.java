package com.opx.oss.engine.crack;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shipped factory-PIN database: model names, per-OUI PINs and per-OUI algorithm
 * associations, generated from the same tables the reference carries and kept as one JSON
 * asset so it costs nothing until a WPS target asks for it.
 */
public final class WpsPinDb {

    private static final String ASSET = "wps_pins.json";

    public static final class Db {
        public final Map<String, List<String>> model = new LinkedHashMap<>();
        public final Map<String, List<String>> algos = new LinkedHashMap<>();
        public final Map<String, List<String>> pins = new LinkedHashMap<>();

        public boolean isEmpty() {
            return model.isEmpty() && algos.isEmpty() && pins.isEmpty();
        }
    }

    private static Db cached;

    private WpsPinDb() {
    }

    /** The database, parsed once per process. */
    public static synchronized Db load(Context context) {
        if (cached != null) return cached;
        Db db = new Db();
        try (InputStream in = context.getAssets().open(ASSET)) {
            byte[] data = new byte[in.available()];
            int read = in.read(data);
            String text = new String(data, 0, Math.max(read, 0), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            fill(root.optJSONObject("model"), db.model);
            fill(root.optJSONObject("algos"), db.algos);
            fill(root.optJSONObject("pins"), db.pins);
        } catch (Exception e) {
            // A missing or unreadable asset leaves the database empty; the algorithms still run.
        }
        cached = db;
        return db;
    }

    private static void fill(JSONObject object, Map<String, List<String>> target) {
        if (object == null) return;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            org.json.JSONArray values = object.optJSONArray(key);
            if (values == null) continue;
            List<String> list = new ArrayList<>(values.length());
            for (int i = 0; i < values.length(); i++) {
                String value = values.optString(i);
                if (!value.isEmpty()) list.add(value);
            }
            if (!list.isEmpty()) target.put(key.toUpperCase(Locale.US), list);
        }
    }

    /** Factory PINs the database knows for this OUI, in its own order. */
    public static List<String> pins(Db db, String oui) {
        List<String> list = db.pins.get(oui == null ? "" : oui.toUpperCase(Locale.US));
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /** Algorithm names associated with this OUI. */
    public static List<String> algos(Db db, String oui) {
        List<String> list = db.algos.get(oui == null ? "" : oui.toUpperCase(Locale.US));
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /** Factory PINs known for a router model. */
    public static List<String> model(Db db, String model) {
        List<String> list = db.model.get(model == null ? "" : model.toUpperCase(Locale.US));
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }
}
