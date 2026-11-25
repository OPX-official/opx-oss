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
import java.util.Locale;

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
    /** The bytes are complete; the prefix is being unpacked (the intro keeps the same slide). */
    public static final int STATE_INSTALLING = 4;

    /** What the slide is doing right now, so its stage line always names the current file. */
    public static final int STAGE_IDLE = 0;
    public static final int STAGE_INDEX = 1;
    public static final int STAGE_DOWNLOAD = 2;
    public static final int STAGE_VERIFY = 3;
    public static final int STAGE_EXTRACT = 4;
    public static final int STAGE_READY = 5;

    /** Log levels of the install log; the dialog draws the same chip for each one. */
    public static final int LOG_INFO = 0;
    public static final int LOG_STEP = 1;
    public static final int LOG_COMMAND = 2;
    public static final int LOG_SUCCESS = 3;
    public static final int LOG_WARN = 4;
    public static final int LOG_ERROR = 5;

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
        /** {@code STAGE_*}: names which step the slide is on. */
        public final int stage;
        /** The file being fetched right now; empty while unpacking. */
        public final String file;
        public final int fileIndex;
        public final int fileCount;
        /** Session throughput in bytes/second, 0 until the first answer arrives. */
        public final long bytesPerSec;
        /** Seconds left at the current speed, -1 when unknown. */
        public final long etaSeconds;

        Snapshot(int state, String arch, String label, long downloaded, long total, String error) {
            this(state, arch, label, downloaded, total, error, STAGE_IDLE, "", 0, 0, 0, -1);
        }

        Snapshot(int state, String arch, String label, long downloaded, long total, String error,
                 int stage, String file, int fileIndex, int fileCount,
                 long bytesPerSec, long etaSeconds) {
            this.state = state;
            this.arch = arch;
            this.label = label;
            this.downloaded = downloaded;
            this.total = total;
            this.error = error;
            this.stage = stage;
            this.file = file;
            this.fileIndex = fileIndex;
            this.fileCount = fileCount;
            this.bytesPerSec = bytesPerSec;
            this.etaSeconds = etaSeconds;
        }

        public int percent() {
            if (state == STATE_READY) return 100;
            if (total <= 0) return 0;
            return (int) Math.min(100, Math.max(0, downloaded * 100 / total));
        }
    }

    /** One line of the install log, the same shape the intro's details dialog renders. */
    public static final class LogEntry {
        public final int level;
        public final String text;

        LogEntry(int level, String text) {
            this.level = level;
            this.text = text;
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

    // ------------------------------------------------------------------ log

    private static final int MAX_LOG = 600;
    private static final List<LogEntry> log = new ArrayList<>();

    /** Copy of the install log, oldest first; safe to call from the UI thread. */
    public static List<LogEntry> logEntries() {
        synchronized (LOCK) {
            return new ArrayList<>(log);
        }
    }

    private static void clearLog() {
        synchronized (LOCK) {
            log.clear();
        }
    }

    private static void appendLog(int level, String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (LOCK) {
            log.add(new LogEntry(level, text));
            while (log.size() > MAX_LOG) log.remove(0);
        }
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

            clearLog();
            appendLog(LOG_STEP, "Preparing the download");
            appendLog(LOG_INFO, "Architecture: " + arch);
            publish(new Snapshot(STATE_DOWNLOADING, arch, "Reading the package index", 0, -1,
                    null, STAGE_INDEX, "index.json", 0, 0, 0, -1), listener);
            File indexFile = new File(runtime, "index.json");
            JSONObject index = parseIndex(indexFile);
            if (index == null) {
                delete(indexFile);
                appendLog(LOG_COMMAND, "GET " + INDEX_URL);
                downloadToFile(INDEX_URL, indexFile, null, -1, null);
                index = parseIndex(indexFile);
            } else {
                appendLog(LOG_INFO, "Using the cached package index");
            }
            if (index == null) throw new IOException("The package index is not valid JSON");
            appendLog(LOG_SUCCESS, "Package index ready");
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
            appendLog(LOG_INFO, selected.size() + " files for " + arch + " · "
                    + formatMb(total));

            // Bytes already on disk when this pass started: they are not this session's
            // throughput, so speed and ETA count only what is actually moving now.
            long skipped = 0;
            for (Entry entry : selected) {
                if (entry.dest.isFile() && entry.size > 0 && entry.dest.length() == entry.size) {
                    skipped += entry.size;
                }
            }
            final long sessionSkipped = skipped;
            final long startedAt = System.currentTimeMillis();
            final int count = selected.size();

            long done = 0;
            int position = 0;
            for (Entry entry : selected) {
                position++;
                String name = shortName(entry.dest);
                if (entry.dest.isFile() && expected(entry.dest, entry.sha256, entry.size)) {
                    done += Math.max(0, entry.dest.length());
                    appendLog(LOG_SUCCESS, "Already verified " + name);
                    publish(progress(arch, entry, done, total, true, position, count), listener);
                    continue;
                }
                long base = done;
                publish(new Snapshot(STATE_DOWNLOADING, arch, "Downloading the OPX packages",
                        base, total, null, STAGE_DOWNLOAD, name, position, count, 0, -1), listener);
                appendLog(LOG_COMMAND, "GET " + entry.url);
                appendLog(LOG_INFO, name + " · " + formatMb(entry.size));
                final long start = base;
                final long all = total;
                final int filePosition = position;
                downloadToFile(entry.url, entry.dest, entry.sha256, entry.size,
                        (downloaded, ignored) -> {
                            long now = System.currentTimeMillis();
                            long moved = Math.max(0, start + downloaded - sessionSkipped);
                            long elapsed = Math.max(1, now - startedAt);
                            long speed = moved * 1000L / elapsed;
                            long remaining = Math.max(0, all - (start + downloaded));
                            long eta = speed > 0 ? remaining / speed : -1;
                            publish(new Snapshot(STATE_DOWNLOADING, arch,
                                    "Downloading the OPX packages", start + downloaded, all,
                                    null, STAGE_DOWNLOAD, name, filePosition, count, speed, eta),
                                    listener);
                        });
                done += Math.max(0, entry.dest.length());
                appendLog(LOG_SUCCESS, "SHA-256 verified " + name);
                publish(progress(arch, entry, done, total, true, position, count), listener);
            }

            File complete = new File(runtime, COMPLETE);
            try (FileOutputStream out = new FileOutputStream(complete)) {
                out.write((arch + "\n").getBytes("UTF-8"));
            }
            appendLog(LOG_SUCCESS, "Every file verified · " + formatMb(total));
            publish(new Snapshot(STATE_READY, arch, "Runtime verified", total, total, null,
                    STAGE_READY, "", count, count, 0, -1), listener);
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
                                     boolean complete, int position, int count) {
        return new Snapshot(STATE_DOWNLOADING, arch,
                complete ? "Verified " + shortName(entry.dest)
                        : "Downloading " + shortName(entry.dest),
                done, total, null, complete ? STAGE_VERIFY : STAGE_DOWNLOAD,
                shortName(entry.dest), position, count, 0, -1);
    }

    /**
     * The bytes are in place and the prefix is being unpacked. The intro keeps the same slide,
     * so its progress bar turns indeterminate and its stage line names the archive being untarred.
     */
    public static void beginInstall(String label) {
        synchronized (LOCK) {
            String stage = label == null ? "Unpacking the runtime" : label;
            appendLog(LOG_STEP, stage);
            snapshot = new Snapshot(STATE_INSTALLING, snapshot.arch, stage,
                    snapshot.downloaded, snapshot.total, null, STAGE_EXTRACT, "", 0, 0, 0, -1);
        }
    }

    /** One unpacking step from {@link OpxRuntime#install}; logged and shown live on the slide. */
    public static void installStep(String label) {
        if (label == null || label.isEmpty()) return;
        synchronized (LOCK) {
            appendLog(LOG_STEP, label);
            snapshot = new Snapshot(STATE_INSTALLING, snapshot.arch, label,
                    snapshot.downloaded, snapshot.total, null, STAGE_EXTRACT, "", 0, 0, 0, -1);
        }
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

    static String formatMb(long bytes) {
        if (bytes <= 0) return "? MB";
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
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
                appendLog(LOG_WARN, "Retry " + attempt + "/" + MAX_RETRIES + " · "
                        + shortName(dest) + " · " + lastError);
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

    /** A connection that delivers nothing for this long is aborted and resumed. */
    private static final long STALL_TIMEOUT_MS = 90L * 1000L;

    /**
     * Watchdog state for one connection. A socket read timeout only fires when the server goes
     * completely silent; a connection that trickles a byte every few seconds never trips it, so
     * the last-byte timestamp is watched separately and the stalled socket is disconnected.
     */
    private static final class Watchdog {
        volatile long lastByte = System.currentTimeMillis();
        volatile boolean running = true;
        volatile boolean stalled;
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

        final Watchdog watchdog = new Watchdog();
        Thread guard = new Thread(() -> {
            while (watchdog.running) {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (!watchdog.running) return;
                if (System.currentTimeMillis() - watchdog.lastByte > STALL_TIMEOUT_MS) {
                    watchdog.stalled = true;
                    // Disconnecting from here is what unblocks a read that stopped moving; the
                    // retry then resumes from the same .part instead of starting the file over.
                    try {
                        connection.disconnect();
                    } catch (RuntimeException ignored) {
                        // Already closed.
                    }
                    return;
                }
            }
        }, "opx-download-watchdog");
        guard.setDaemon(true);
        guard.start();

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
                        watchdog.lastByte = now;
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
        } catch (IOException e) {
            // A stalled body keeps its .part; only the watchdog's message is worth reporting.
            if (watchdog.stalled) throw new IOException("Download stalled", e);
            throw e;
        } finally {
            watchdog.running = false;
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
