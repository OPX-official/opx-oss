package com.opx.oss;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads the native runtime into the app's private files directory.
 * <p>
 * The APK no longer carries the 100+ MB bootstrap and payloads. The first launch resolves the
 * device's ABI (32-bit {@code arm} or 64-bit {@code aarch64}), reads the OPX package archive's
 * {@code index.json}, and downloads exactly the bootstrap and payloads for that architecture.
 * <p>
 * Downloads are resumable: bytes go to {@code <name>.part}, a later pass sends {@code Range} and
 * appends only what is missing. A file is moved into place only after its size and SHA-256 match
 * the index, so a killed process or a dropped connection can never leave a half-file that the
 * installer would untar. The same SHA check also lets a retry skip files that are already intact,
 * which is what makes a failed multi-file download cheap to resume.
 */
public final class RuntimeDownloadManager {

    public static final String INDEX_URL =
            "https://raw.githubusercontent.com/OPX-official/opx-packages/gh-pages/index.json";

    public static final int STATE_IDLE = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_ERROR = 3;

    private static final int MAX_RETRIES = 3;
    private static final int BUFFER = 256 * 1024;
    private static final long SPACE_MARGIN = 16L * 1024 * 1024;
    private static final String COMPLETE = ".complete";

    /** UI-facing copy of the download state; never hand out the mutable fields themselves. */
    public static final class Snapshot {
        public final int state;
        public final String arch;
        public final String label;
        public final long downloaded;
        public final long total;
        public final String error;

        Snapshot(int state, String arch, String label, long downloaded, long total, String error) {
            this.state = state;
            this.arch = arch;
            this.label = label;
            this.downloaded = downloaded;
            this.total = total;
            this.error = error;
        }

        public int percent() {
            if (state == STATE_READY) return 100;
            if (total <= 0) return 0;
            return (int) Math.min(100, Math.max(0, downloaded * 100 / total));
        }
    }

    public interface Progress {
        void onProgress(Snapshot snapshot);
    }

    private static final Object LOCK = new Object();
    private static volatile Snapshot snapshot =
            new Snapshot(STATE_IDLE, "", "Waiting for the download", 0, -1, null);

    private RuntimeDownloadManager() {
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static File runtimeDir(Context context) {
        return new File(context.getFilesDir(), "runtime");
    }

    public static File bootstrapDir(Context context) {
        return new File(runtimeDir(context), "bootstrap");
    }

    public static File payloadDir(Context context, String arch) {
        return new File(new File(runtimeDir(context), "payload"), arch);
    }

    public static File bootstrapFile(Context context, String arch) {
        return new File(bootstrapDir(context), "bootstrap-" + arch + ".zip");
    }

    public static boolean isReady(Context context) {
        String arch = arch(context);
        return new File(runtimeDir(context), COMPLETE).isFile()
                && bootstrapFile(context, arch).isFile()
                && hasPayload(context, arch) && hasPayload(context, "all");
    }

    private static boolean hasPayload(Context context, String arch) {
        File[] files = payloadDir(context, arch).listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isFile() && file.length() > 0
                    && (file.getName().endsWith(".tar.gz")
                    || file.getName().endsWith(".targz"))) return true;
        }
        return false;
    }

    /**
     * The runtime architecture for this device. A 64-bit phone gets the aarch64 tree, a 32-bit
     * phone the arm one; the archive's index decides whether either exists.
     */
    public static String arch(Context context) {
        // Supported_ABIS says what the kernel can run, not what this process is. A 32-bit app
        // process on a 64-bit phone must get the arm tree: execve() cannot launch aarch64
        // binaries from a 32-bit process. Process.is64Bit() is the process's own answer.
        boolean process64 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && android.os.Process.is64Bit();
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            if (process64 && "arm64-v8a".equals(abi)) return "aarch64";
            if (!process64 && ("armeabi-v7a".equals(abi) || "armeabi".equals(abi))) return "arm";
            if (process64 && "x86_64".equals(abi)) return "x86_64";
            if (!process64 && "x86".equals(abi)) return "i686";
        }
        // An emulator/ABI list that does not agree with the process: fall back to the first
        // entry whose width matches, then to the process width alone.
        for (String abi : abis) {
            boolean is64 = abi.contains("64");
            if (is64 == process64) {
                if (abi.contains("arm64")) return "aarch64";
                if (abi.contains("arm")) return "arm";
                if (abi.contains("x86_64")) return "x86_64";
                if (abi.contains("x86")) return "i686";
            }
        }
        if (Build.CPU_ABI != null) {
            if (Build.CPU_ABI.contains("arm64")) return "aarch64";
            if (Build.CPU_ABI.contains("arm")) return "arm";
        }
        throw new IllegalStateException("The device reports no supported runtime ABI");
    }

    /**
     * Ensures a complete, checksum-verified runtime exists. Blocking; called from the engine
     * service thread. Every completed file is kept on failure, so a retry resumes the rest.
     */
    public static void ensureDownloaded(Context context, Progress listener) throws Exception {
        synchronized (LOCK) {
            String arch = arch(context);
            if (isReady(context)) {
                publish(new Snapshot(STATE_READY, arch, "Runtime ready", 0, 0, null), listener);
                return;
            }

            File runtime = runtimeDir(context);
            ensureDirectory(runtime);
            ensureDirectory(bootstrapDir(context));
            ensureDirectory(payloadDir(context, arch));
            ensureDirectory(payloadDir(context, "all"));

            publish(new Snapshot(STATE_DOWNLOADING, arch, "Reading package index…", 0, -1, null),
                    listener);
            File indexFile = new File(runtime, "index.json");
            JSONObject index = parseIndex(indexFile);
            if (index == null) {
                delete(indexFile);
                downloadToFile(INDEX_URL, indexFile, null, -1, null);
                index = parseIndex(indexFile);
            }
            if (index == null) throw new IOException("The package index is not valid JSON");
            JSONArray packages = index.optJSONArray("packages");
            if (packages == null) throw new IOException("The package index has no packages");

            List<Entry> selected = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < packages.length(); i++) {
                JSONObject item = packages.optJSONObject(i);
                if (item == null) continue;
                String kind = item.optString("kind", "");
                String itemArch = item.optString("arch", "");
                if (!"bootstrap".equals(kind) && !"payload".equals(kind)) continue;
                if ("bootstrap".equals(kind) && !arch.equals(itemArch)) continue;
                if ("payload".equals(kind) && !arch.equals(itemArch) && !"all".equals(itemArch)) {
                    continue;
                }
                String url = item.optString("url", "");
                String path = item.optString("path", "");
                String sha = item.optString("sha256", "");
                long size = item.optLong("size", -1);
                if (url.isEmpty() || path.isEmpty() || sha.isEmpty()) continue;
                File dest = destination(context, arch, kind, path);
                if (dest == null) continue;
                selected.add(new Entry(url, dest, sha, size, path));
                total += Math.max(0, size);
            }
            if (selected.isEmpty()) {
                throw new IOException("The package index has no runtime for " + arch);
            }

            long done = 0;
            for (Entry entry : selected) {
                if (entry.dest.isFile() && expected(entry.dest, entry.sha256, entry.size)) {
                    done += Math.max(0, entry.dest.length());
                    publish(progress(arch, entry, done, total, true), listener);
                    continue;
                }
                long base = done;
                publish(progress(arch, entry, base, total, false), listener);
                final long start = base;
                final long all = total;
                downloadToFile(entry.url, entry.dest, entry.sha256, entry.size,
                        (downloaded, ignored) -> publish(new Snapshot(STATE_DOWNLOADING, arch,
                                "Downloading " + shortName(entry.dest),
                                start + downloaded, all, null), listener));
                done += Math.max(0, entry.dest.length());
                publish(progress(arch, entry, done, total, true), listener);
            }

            File complete = new File(runtime, COMPLETE);
            try (FileOutputStream out = new FileOutputStream(complete)) {
                out.write((arch + "\n").getBytes("UTF-8"));
            }
            publish(new Snapshot(STATE_READY, arch, "Runtime ready", total, total, null), listener);
            AppLog.info("RuntimeDownload", "runtime ready (" + arch + ", " + selected.size()
                    + " files)");
        }
    }

    /** Records a failure from the surrounding install thread so the intro can offer Retry. */
    public static void reportError(String error) {
        synchronized (LOCK) {
            snapshot = new Snapshot(STATE_ERROR, snapshot.arch,
                    snapshot.label, snapshot.downloaded, snapshot.total,
                    error == null ? "unknown error" : error);
        }
    }

    /** Drops the completion marker so the next pass re-verifies every downloaded file. */
    public static void invalidate(Context context) {
        if (context != null) delete(new File(runtimeDir(context), COMPLETE));
    }

    /** Clears an error so the Intro can retry without throwing away completed files. */
    public static void retry() {
        synchronized (LOCK) {
            if (snapshot.state == STATE_ERROR) {
                snapshot = new Snapshot(STATE_IDLE, snapshot.arch, "Waiting to retry", 0, -1, null);
            }
        }
    }

    private static Snapshot progress(String arch, Entry entry, long done, long total,
                                     boolean complete) {
        return new Snapshot(STATE_DOWNLOADING, arch,
                complete ? "Verified " + shortName(entry.dest) : "Downloading " + shortName(entry.dest),
                done, total, null);
    }

    private static void publish(Snapshot next, Progress listener) {
        snapshot = next;
        if (listener != null) {
            try {
                listener.onProgress(next);
            } catch (RuntimeException ignored) {
                // A disappearing screen must not stop the download.
            }
        }
    }

    private static File destination(Context context, String arch, String kind, String path) {
        String name = path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.isEmpty() || name.contains("..")) return null;
        if ("bootstrap".equals(kind)) {
            // The installer looks for bootstrap-<arch>.zip; do not depend on the archive
            // path using exactly that basename.
            return bootstrapFile(context, arch);
        }
        String itemArch = path.contains("/" + arch + "/") ? arch : "all";
        return new File(payloadDir(context, itemArch), name);
    }

    private static boolean expected(File file, String sha, long size) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        if (size > 0 && file.length() != size) return false;
        try {
            return sha.equalsIgnoreCase(sha256(file));
        } catch (IOException e) {
            return false;
        }
    }

    private static String shortName(File file) {
        String name = file.getName();
        return name.length() > 44 ? name.substring(0, 41) + "…" : name;
    }

    private static JSONObject parseIndex(File file) {
        if (!file.isFile() || file.length() == 0) return null;
        try {
            return new JSONObject(readText(file));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    private interface Bytes {
        void onBytes(long downloaded, long total);
    }

    /**
     * Resumes {@code <dest>.part} with Range, verifies its size and SHA-256, then renames it
     * atomically. A 200 answer to a Range request means the server ignored it, so the partial
     * file is truncated and the download starts over instead of appending garbage.
     */
    private static void downloadToFile(String url, File dest, String sha, long size, Bytes bytes)
            throws Exception {
        if (url == null || !url.startsWith("https://")) {
            throw new IOException("Refusing non-HTTPS URL");
        }
        File parent = dest.getParentFile();
        if (parent != null) ensureDirectory(parent);
        File part = new File(dest.getAbsolutePath() + ".part");
        String lastError = "Download failed";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                attempt(url, dest, part, sha, size, bytes);
                return;
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                if (lastError.startsWith("Checksum") || lastError.startsWith("Size")) {
                    // A corrupt/truncated body is worth one clean retry; the loop still
                    // preserves .part for ordinary network failures.
                    delete(part);
                    if (attempt >= MAX_RETRIES) throw e;
                }
            }
            try {
                Thread.sleep(1000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException(lastError);
    }

    private static void attempt(String url, File dest, File part, String sha, long size,
                                Bytes bytes) throws Exception {
        long existing = part.isFile() ? part.length() : 0;
        if (size > 0 && existing >= size) {
            // A previous process may have written every byte and died before the rename. Do
            // not send a Range request that the server would answer with 416 and make the
            // user redownload 100 MB: verify the completed part and finalize it here.
            if (existing == size && (sha == null || sha.isEmpty()
                    || sha.equalsIgnoreCase(sha256(part)))) {
                delete(dest);
                if (!part.renameTo(dest)) {
                    copy(part, dest);
                    delete(part);
                }
                if (bytes != null) bytes.onBytes(size, size);
                return;
            }
            delete(part);
            existing = 0;
        }
        File parent = dest.getParentFile();
        if (parent != null && size > 0) {
            long usable = parent.getUsableSpace();
            if (usable > 0 && usable < Math.max(0, size - existing) + SPACE_MARGIN) {
                throw new IOException("Not enough free space");
            }
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "OPX-OSS/" + Build.VERSION.SDK_INT);
        if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");

        try {
            int code = connection.getResponseCode();
            if (code == 416) {
                delete(part);
                throw new IOException("Range rejected");
            }
            if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code);
            }
            boolean resume = code == 206 && existing > 0;
            if (code == 206) {
                String contentRange = connection.getHeaderField("Content-Range");
                long start = rangeStart(contentRange);
                if (start != existing) {
                    delete(part);
                    throw new IOException("Unexpected range start");
                }
            }
            long bodyLength = contentLength(connection);
            long total = size > 0 ? size
                    : (bodyLength > 0 ? (resume ? existing + bodyLength : bodyLength) : -1);

            try (RandomAccessFile out = new RandomAccessFile(part, "rw")) {
                if (resume) {
                    out.seek(existing);
                } else {
                    out.setLength(0);
                    out.seek(0);
                }
                try (InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER)) {
                    byte[] buffer = new byte[BUFFER];
                    long downloaded = resume ? existing : 0;
                    long last = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        long now = System.currentTimeMillis();
                        if (bytes != null && now - last >= 150) {
                            last = now;
                            bytes.onBytes(downloaded, total);
                        }
                    }
                }
                out.getFD().sync();
            }

            long actualSize = part.length();
            if (size > 0 && actualSize != size) {
                delete(part);
                throw new IOException("Size mismatch: got " + actualSize + ", expected " + size);
            }
            String actual = sha256(part);
            if (sha != null && !sha.isEmpty() && !sha.equalsIgnoreCase(actual)) {
                delete(part);
                throw new IOException("Checksum mismatch");
            }
            delete(dest);
            if (!part.renameTo(dest)) {
                copy(part, dest);
                delete(part);
            }
            if (bytes != null && size > 0) bytes.onBytes(size, size);
        } finally {
            connection.disconnect();
        }
    }

    /** {@code getContentLengthLong} is API 24; minSdk is 23, so parse the header instead. */
    private static long contentLength(HttpURLConnection connection) {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) return -1;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long rangeStart(String contentRange) {
        if (contentRange == null) return -1;
        int space = contentRange.indexOf(' ');
        int dash = contentRange.indexOf('-');
        if (space < 0 || dash <= space) return -1;
        try {
            return Long.parseLong(contentRange.substring(space + 1, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            byte[] out = digest.digest();
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable");
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir == null || dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Cannot create " + dir);
    }

    private static void delete(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static final class Entry {
        final String url;
        final File dest;
        final String sha256;
        final long size;
        final String path;

        Entry(String url, File dest, String sha256, long size, String path) {
            this.url = url;
            this.dest = dest;
            this.sha256 = sha256;
            this.size = size;
            this.path = path;
        }
    }
}
