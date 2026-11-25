package com.opx.oss.engine;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The capture vault: handshakes and PMKIDs written next to the app, under its own files
 * directory, so nothing depends on shared storage or a runtime prefix.
 */
public final class Vault {

    private static final String DIRECTORY = "vault";

    private Vault() {
    }

    public static File dir(Context context) {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.isDirectory()) directory.mkdirs();
        return directory;
    }

    public static File save(Context context, String name, byte[] data) throws IOException {
        File target = new File(dir(context), safe(name));
        try (FileOutputStream out = new FileOutputStream(target, true)) {
            out.write(data);
        }
        return target;
    }

    public static List<File> list(Context context) {
        File[] files = dir(context).listFiles();
        List<File> captures = new ArrayList<>();
        if (files != null) {
            for (File file : files) if (file.isFile()) captures.add(file);
        }
        captures.sort(Comparator.comparingLong(File::lastModified).reversed());
        return captures;
    }

    public static boolean delete(Context context, String name) {
        File target = new File(dir(context), safe(name));
        return target.isFile() && target.delete();
    }

    /** The {@code captures} event's payload: name, size and when each capture was written. */
    public static JSONArray toJson(Context context) {
        JSONArray array = new JSONArray();
        for (File file : list(context)) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("name", file.getName());
                entry.put("path", file.getAbsolutePath());
                entry.put("size", file.length());
                entry.put("modified", file.lastModified());
            } catch (Exception ignored) {
                // Every value above is a plain type.
            }
            array.put(entry);
        }
        return array;
    }

    /** Keeps a capture name inside the vault: no separators, no traversal. */
    private static String safe(String name) {
        String cleaned = name == null ? "" : name.replace('/', '_').replace('\\', '_').trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) cleaned = "capture";
        return cleaned;
    }
}
