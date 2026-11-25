package com.opx.oss;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The captive-portal pages OPX-wifi4 ships in {@code captive_portal/}: 208 templates across 19
 * categories, plus its default login and success pages. They live in the APK's assets and are
 * copied into the app's files directory when picked, because the portal host (opx_ap) reads a
 * plain path; a page the user supplies from their own storage takes the same route.
 */
final class PortalTemplates {

    static final String CATALOG = "portal/catalog";
    static final String TEMPLATES = "portal/templates";

    /** One catalog page: its category, its asset file and the label a sheet shows. */
    static final class Entry {
        final String category;
        final String asset;
        final String label;

        Entry(String category, String asset, String label) {
            this.category = category;
            this.asset = asset;
            this.label = label;
        }
    }

    private PortalTemplates() {
    }

    static List<String> categories(Context context) {
        List<String> found = new ArrayList<>();
        try {
            String[] names = context.getAssets().list(CATALOG);
            if (names != null) {
                for (String name : names) found.add(name);
            }
        } catch (IOException ignored) {
            // An empty list only hides the catalog; the built-in page still works.
        }
        Collections.sort(found);
        return found;
    }

    static List<Entry> templates(Context context, String category) {
        List<Entry> found = new ArrayList<>();
        try {
            String[] names = context.getAssets().list(CATALOG + "/" + category);
            if (names != null) {
                for (String name : names) {
                    if (!name.toLowerCase(Locale.US).endsWith(".html")) continue;
                    found.add(new Entry(category, name, humanize(category, name)));
                }
            }
        } catch (IOException ignored) {
            // An empty category is just not offered.
        }
        Collections.sort(found, (left, right) -> left.label.compareToIgnoreCase(right.label));
        return found;
    }

    static String categoryLabel(String category) {
        String text = category.replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** "Hotels_Best-Western_forgot.html" -> "Best Western forgot". */
    static String humanize(String category, String file) {
        String name = file.replaceAll("(?i)\\.html?$", "");
        if (name.contains("_")) {
            String head = name.substring(0, name.indexOf('_'));
            if (head.equalsIgnoreCase(category) || category.toLowerCase(Locale.US)
                    .startsWith(head.toLowerCase(Locale.US))) {
                name = name.substring(name.indexOf('_') + 1);
            }
        }
        name = name.replace('_', ' ').replace('-', ' ').trim();
        return name.isEmpty() ? file : name;
    }

    /** Copy one catalog page into the app's files tree and return its absolute path. */
    static File materialize(Context context, Entry entry) throws IOException {
        String target = entry.category + "__" + entry.asset;
        return copyAsset(context, CATALOG + "/" + entry.category + "/" + entry.asset, target);
    }

    /** OPX-wifi4's own default login page (captive_portal/templates/login.html). */
    static File defaultLogin(Context context) throws IOException {
        return copyAsset(context, TEMPLATES + "/login.html", "default_login.html");
    }

    /** OPX-wifi4's own success page, shown once the password matches. */
    static File defaultSuccess(Context context) throws IOException {
        return copyAsset(context, TEMPLATES + "/success.html", "default_success.html");
    }

    /** A page the user picked from their own storage; same extension rules as the catalog. */
    static File copyCustom(Context context, Uri uri, String displayName) throws IOException {
        String name = displayName == null ? "" : displayName;
        if (name.isEmpty()) {
            String last = uri.getLastPathSegment();
            name = last == null ? "custom.html" : last;
        }
        if (!name.toLowerCase(Locale.US).endsWith(".html")
                && !name.toLowerCase(Locale.US).endsWith(".htm")) {
            name = name + ".html";
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(directory(context), "custom__" + safe);
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("no stream");
            copy(input, output);
        }
        return target;
    }

    static File directory(Context context) {
        File directory = new File(context.getFilesDir(), "portal_templates");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            AppLog.info("OpxPortal", "cannot create " + directory);
        }
        return directory;
    }

    private static File copyAsset(Context context, String asset, String name) throws IOException {
        File directory = directory(context);
        // The catalog is fixed, so one copy per name is enough; an empty file is re-copied.
        File target = new File(directory, name);
        if (target.isFile() && target.length() > 0) return target;
        AssetManager assets = context.getAssets();
        try (InputStream input = assets.open(asset);
             OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
        return target;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
    }
}
