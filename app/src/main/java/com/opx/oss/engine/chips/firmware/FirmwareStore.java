package com.opx.oss.engine.chips.firmware;

import android.content.Context;

import com.opx.oss.engine.crypto.Sha256;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The chip firmware the engine ships: every blob the supported radios need, kept in the APK's
 * assets and copied next to the app the first time a chip asks for it.
 * <p>
 * Each blob carries its own licence file, and every copy is verified against the SHA-256 the
 * manifest records, so a radio never gets a truncated image.
 */
public final class FirmwareStore {

    private static final String ASSET_ROOT = "firmware";
    private static final String MANIFEST = "manifest.txt";

    /** One bundled image: which chip asks for it, its file name, size and digest. */
    public static final class Blob {
        public final String chip;
        public final String name;
        public final String sha256;
        public final long size;

        Blob(String chip, String name, String sha256, long size) {
            this.chip = chip;
            this.name = name;
            this.sha256 = sha256;
            this.size = size;
        }

        public boolean isLicence() {
            return name.startsWith("LICENCE") || name.startsWith("LICENSE");
        }
    }

    private FirmwareStore() {
    }

    /** Every blob the APK carries, read from the manifest that travels with them. */
    public static List<Blob> all(Context context) {
        List<Blob> blobs = new ArrayList<>();
        Map<String, Blob> unique = new LinkedHashMap<>();
        try (InputStream in = context.getAssets().open(ASSET_ROOT + "/" + MANIFEST)) {
            String text = new String(readAll(in), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String[] parts = trimmed.split(" ");
                if (parts.length != 3) continue;
                String path = parts[0];
                int slash = path.indexOf('/');
                if (slash <= 0) continue;
                Blob blob = new Blob(path.substring(0, slash), path.substring(slash + 1),
                        parts[1], Long.parseLong(parts[2]));
                unique.put(path, blob);
            }
        } catch (Exception e) {
            return blobs;
        }
        blobs.addAll(unique.values());
        return blobs;
    }

    /** The images one chip family needs, licences included. */
    public static List<Blob> forChip(Context context, String chip) {
        List<Blob> owned = new ArrayList<>();
        for (Blob blob : all(context)) {
            if (blob.chip.equals(chip)) owned.add(blob);
        }
        return owned;
    }

    /** The chip families the APK carries firmware for. */
    public static List<String> chips(Context context) {
        List<String> chips = new ArrayList<>();
        for (Blob blob : all(context)) {
            if (!chips.contains(blob.chip)) chips.add(blob.chip);
        }
        return chips;
    }

    /**
     * Copy one blob to the app's own files and verify it against the manifest. Repeated calls
     * reuse the copy that is already there and correct.
     */
    public static File install(Context context, Blob blob) throws IOException {
        File directory = new File(context.getFilesDir(), ASSET_ROOT + "/" + blob.chip);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create " + directory);
        }
        File target = new File(directory, blob.name);
        if (target.isFile() && target.length() == blob.size
                && blob.sha256.equalsIgnoreCase(digest(target))) {
            return target;
        }
        byte[] data;
        try (InputStream in = context.getAssets().open(ASSET_ROOT + "/" + blob.chip + "/" + blob.name)) {
            data = readAll(in);
        }
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            out.write(data);
        }
        String written = digest(target);
        if (!blob.sha256.equalsIgnoreCase(written)) {
            target.delete();
            throw new IOException("firmware " + blob.name + " does not match its manifest");
        }
        return target;
    }

    /** Every image for one chip, copied and verified; returns the paths in manifest order. */
    public static List<File> installChip(Context context, String chip) throws IOException {
        List<File> files = new ArrayList<>();
        for (Blob blob : forChip(context, chip)) {
            if (!blob.isLicence()) files.add(install(context, blob));
        }
        return files;
    }

    /** One blob as bytes, from the installed copy when it is already verified. */
    public static byte[] read(Context context, String chip, String name) throws IOException {
        for (Blob blob : forChip(context, chip)) {
            if (!blob.name.equals(name)) continue;
            if (blob.isLicence()) throw new IOException(name + " is a licence, not an image");
            File installed = install(context, blob);
            try (FileInputStream in = new FileInputStream(installed)) {
                return readAll(in);
            }
        }
        throw new IOException("no firmware " + chip + "/" + name + " in this build");
    }

    private static String digest(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return hex(Sha256.digest(readAll(in)));
        }
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data) text.append(String.format(Locale.US, "%02x", value & 0xFF));
        return text.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}
