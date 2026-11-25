package com.opx.oss;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Owns the downloaded native runtime.
 * <p>
 * OPX OSS is a native app: every binary it runs lives under the app's own private
 * directory, {@code /data/data/com.opx.oss/files/usr} (the "prefix"), and every package is
 * compiled for exactly that path. There is no virtualization layer and no other app's prefix
 * anywhere in the stack: the engine, the USB bridge and the UI all run in this process against
 * files inside this app.
 * <p>
 * The APK itself is small: {@link RuntimeDownloadManager} resolves the phone's ABI (arm or
 * aarch64), downloads the matching bootstrap and payloads into {@code files/runtime}, verifies
 * every SHA-256 and resumes partial files. This class then installs from those files:
 * <ul>
 *   <li>{@code runtime/bootstrap/bootstrap-<arch>.zip} — the OPX OSS bootstrap for this prefix.</li>
 *   <li>{@code runtime/payload/<arch|all>/*.tar.gz} — .deb-style trees (python, libusb, the
 *       wifi4 engine, the USB bridge shim) untarred straight into the prefix at install time.</li>
 * </ul>
 * An older APK with bundled {@code assets/bootstrap} and {@code assets/payload} still works:
 * the same install path falls back to assets when no downloaded files are present.
 * <p>
 * The app targets SDK 28 on purpose: Android 10+ denies {@code execve()} on files in the app
 * home directory for apps targeting 29+, which is where the runtime lives.
 */
public final class OpxRuntime {

    /** The runtime prefix. Every package is built for this exact path. */
    public static final String PREFIX_GUEST = "/data/data/com.opx.oss/files/usr";
    /** The runtime home directory, {@code $HOME} for the runtime. */
    public static final String HOME_GUEST = "/data/data/com.opx.oss/files/home";
    /** The runtime cache directory (apt/pip caches, handshake sockets). */
    public static final String CACHE_GUEST = "/data/data/com.opx.oss/cache";

    private static final String PAYLOAD_ROOT = "data/data/com.opx.oss/files/";

    private OpxRuntime() {
    }

    public static File prefixDir(Context context) {
        return new File(context.getFilesDir(), "usr");
    }

    public static File homeDir(Context context) {
        return new File(context.getFilesDir(), "home");
    }

    public static File tmpDir(Context context) {
        return new File(context.getFilesDir(), "tmp");
    }

    public static File cacheDir(Context context) {
        File cache = context.getCacheDir();
        return cache != null ? cache : new File(context.getFilesDir(), "cache");
    }

    /** Where the packages put the Python modules (wifi4, the engine, the USB client). */
    public static File pythonDir(Context context) {
        return new File(prefixDir(context), "lib/opx/python");
    }

    /**
     * The runtime architecture for one Android ABI name, or {@code null} when this app has no
     * runtime for it. {@code armeabi-v7a} is the 32-bit ARM phone build (Xiaomi, Redmi, Poco,
     * Infinix and the rest run it whenever their userspace is 32-bit), {@code arm64-v8a} the
     * 64-bit one; both are built and shipped, so the same APK runs on either.
     */
    private static String archForAbi(String abi) {
        if (abi == null) return null;
        switch (abi) {
            case "arm64-v8a":
                return "aarch64";
            case "armeabi-v7a":
            case "armeabi":
                return "arm";
            case "x86":
                return "i686";
            case "x86_64":
                return "x86_64";
            default:
                return null;
        }
    }

    /** The device's primary ABI mapped to a runtime architecture. */
    public static String deviceArch() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
        String arch = archForAbi(abi);
        if (arch == null) throw new IllegalStateException("Unsupported ABI: " + abi);
        return arch;
    }

    /** Every runtime architecture this APK carries or has already downloaded. */
    public static List<String> shippedArches(Context context) {
        List<String> arches = new ArrayList<>();
        try {
            for (String name : context.getAssets().list("bootstrap")) {
                if (name.startsWith("bootstrap-") && name.endsWith(".zip")) {
                    arches.add(name.substring("bootstrap-".length(), name.length() - ".zip".length()));
                }
            }
        } catch (IOException ignored) {
            // A small APK has no bundled bootstrap; the downloader supplies it.
        }
        File[] files = RuntimeDownloadManager.bootstrapDir(context).listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.startsWith("bootstrap-") && name.endsWith(".zip")) {
                    String arch = name.substring("bootstrap-".length(), name.length() - 4);
                    if (!arches.contains(arch)) arches.add(arch);
                }
            }
        }
        return arches;
    }

    /** True when a bootstrap and the payload trees are available from the downloader or APK. */
    public static boolean hasSources(Context context) {
        if (RuntimeDownloadManager.isReady(context)) return true;
        try {
            for (String name : context.getAssets().list("bootstrap")) {
                if (name.startsWith("bootstrap-") && name.endsWith(".zip")) return true;
            }
        } catch (IOException ignored) {
            // No assets at all is the normal small-APK case.
        }
        return false;
    }

    /**
     * The architecture to install on this device: the first ABI Android reports for which the
     * APK carries a runtime. Reading the assets, not just {@code SUPPORTED_ABIS[0]}, keeps a
     * device whose primary ABI came up short on the runtime it can actually run - a 64-bit
     * phone with only the 32-bit tree present installs that one instead of failing.
     */
    public static String arch(Context context) {
        List<String> candidates = new ArrayList<>();
        Collections.addAll(candidates, Build.SUPPORTED_ABIS);
        if (!TextUtils.isEmpty(Build.CPU_ABI)) candidates.add(Build.CPU_ABI);
        if (!TextUtils.isEmpty(Build.CPU_ABI2)) candidates.add(Build.CPU_ABI2);

        List<String> shipped = shippedArches(context);
        if (shipped.isEmpty()) {
            // A downloaded runtime is not in assets yet; pick the device's own ABI and let the
            // downloader fetch exactly that tree.
            return RuntimeDownloadManager.arch(context);
        }
        for (String abi : candidates) {
            String arch = archForAbi(abi);
            if (arch != null && shipped.contains(arch)) return arch;
        }
        throw new IllegalStateException("This APK ships a runtime for "
                + (shipped.isEmpty() ? "no architecture" : TextUtils.join("/", shipped))
                + " but this device reports " + TextUtils.join(",", candidates));
    }

    /** Written last by {@link #install(Context)}: a half-extracted prefix is not "installed". */
    private static final String INSTALL_MARKER = ".opx-installed";

    public static boolean isInstalled(Context context) {
        File prefix = prefixDir(context);
        return new File(prefix, INSTALL_MARKER).isFile() && new File(prefix, "bin/bash").isFile();
    }

    /** Reports the unpacking steps so the intro can name the archive being extracted. */
    public interface Step {
        void onStep(String label);
    }

    /** Extracts the bootstrap and every payload into the app's own prefix. Blocking. */
    public static void install(Context context) throws Exception {
        install(context, null);
    }

    /** Extracts the runtime and reports each archive through {@code step}; blocking. */
    public static void install(Context context, Step step) throws Exception {
        String arch = arch(context);
        File files = context.getFilesDir();
        File prefix = prefixDir(context);
        File staging = new File(files, "usr-staging");

        AppLog.info("OpxRuntime", "install starting (arch=" + arch + ")");

        deleteTree(staging);
        deleteTree(prefix);
        if (!staging.mkdirs()) throw new IOException("Cannot create " + staging);

        step(step, "Unpacking bootstrap-" + arch + ".zip");
        try (InputStream bootstrap = openBootstrap(context, arch)) {
            extractBootstrap(bootstrap, staging);
        }
        if (!staging.renameTo(prefix)) throw new IOException("Cannot move the bootstrap into place");
        AppLog.info("OpxRuntime", "bootstrap in place");

        extractPayloads(context, arch, prefix, step);
        ensureSiteShim(context);
        ensureUsbShim(context);
        ensureUsbFdVariable(context);

        ensureDirectory(homeDir(context));
        ensureDirectory(tmpDir(context));
        ensureDirectory(cacheDir(context));
        ensureAptCache(context);
        // wifi4 binds its USB handshake socket under $PREFIX/tmp; the shell expects var/.
        ensureDirectory(new File(prefix, "tmp"));
        ensureDirectory(new File(prefix, "var/lib/apt/lists/partial"));
        ensureDirectory(new File(prefix, "var/cache/apt/archives/partial"));
        setupStorage(context);

        String version;
        try {
            version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "unknown";
        }
        step(step, "Writing the version marker");
        try (FileOutputStream marker = new FileOutputStream(new File(prefix, INSTALL_MARKER))) {
            marker.write((version + "\n").getBytes("UTF-8"));
        }

        AppLog.info("OpxRuntime", "install finished");
    }

    /**
     * pyusb loads libusb with {@code ctypes.util.find_library}, which asks ldconfig, gcc and ld
     * — none of which exist inside an app runtime — so it reports "No backend available" even
     * though the prefix ships libusb. The shim lives in the PYTHONPATH directory the app sets,
     * and CPython's {@code site} module imports it before any user code runs.
     */
    static void ensureSiteShim(Context context) {
        File pythonDir = pythonDir(context);
        if (!pythonDir.isDirectory() && !pythonDir.mkdirs()) return;
        File target = new File(pythonDir, "sitecustomize.py");
        try (InputStream in = context.getAssets().open("python/sitecustomize.py");
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
        } catch (IOException e) {
            AppLog.info("OpxRuntime", "cannot write the python site shim: " + e.getMessage());
        }
    }

    /**
     * wifi4 runs the USB shim by exec'ing {@code $PREFIX/bin/opx-usb}, so its first line is an
     * interpreter path the kernel has to resolve. Android has no {@code /usr/bin/env}, and a
     * prefix installed from an older payload still carries {@code #!/usr/bin/env python3}:
     * execve then fails with ENOENT, wifi4 swallows it as "opx-usb is unavailable", and the
     * USB adapter looks like a permission problem while nothing ever reaches the bridge.
     * <p>
     * Rewriting that one line to this prefix's own python repairs an installed prefix in place,
     * so the fix does not wait for a full runtime reinstall.
     */
    static void ensureUsbShim(Context context) {
        File script = new File(prefixDir(context), "bin/opx-usb");
        if (!script.isFile()) {
            AppLog.info("OpxRuntime", "no bin/opx-usb in the prefix; reinstall the runtime for USB");
            return;
        }
        String wanted = "#!" + PREFIX_GUEST + "/bin/python3";
        try {
            byte[] body;
            try (InputStream in = new FileInputStream(script);
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = in.read(chunk)) > 0) buffer.write(chunk, 0, count);
                body = buffer.toByteArray();
            }
            String text = new String(body, StandardCharsets.UTF_8);
            int newline = text.indexOf('\n');
            String firstLine = (newline < 0 ? text : text.substring(0, newline)).trim();
            if (!wanted.equals(firstLine)) {
                String repaired = wanted + "\n" + (newline < 0 ? "" : text.substring(newline + 1));
                try (FileOutputStream out = new FileOutputStream(script)) {
                    out.write(repaired.getBytes(StandardCharsets.UTF_8));
                }
                AppLog.info("OpxRuntime", "opx-usb entry point: " + firstLine + " -> " + wanted);
            }
            try {
                Os.chmod(script.getAbsolutePath(), 0755);
            } catch (ErrnoException e) {
                AppLog.info("OpxRuntime", "cannot make opx-usb executable: " + e.getMessage());
            }
        } catch (IOException e) {
            AppLog.info("OpxRuntime", "cannot repair bin/opx-usb: " + e.getMessage());
        }
    }

    /**
     * Untars the packaged trees into the prefix. The arch-specific payload carries the base
     * runtime (python, libusb, bash, coreutils); the {@code all} payloads carry our own packages.
     */
    private static void step(Step step, String label) {
        if (step == null) return;
        try {
            step.onStep(label);
        } catch (RuntimeException ignored) {
            // A closing screen must not abort the install.
        }
    }

    private static void extractPayloads(Context context, String arch, File prefix, Step step)
            throws Exception {
        for (String dir : new String[]{"all", arch}) {
            List<File> downloaded = new ArrayList<>();
            File[] files = RuntimeDownloadManager.payloadDir(context, dir).listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    if (name.endsWith(".targz") || name.endsWith(".tar.gz")) downloaded.add(file);
                }
            }
            Collections.sort(downloaded, (left, right) ->
                    left.getName().compareTo(right.getName()));
            if (downloaded.isEmpty()) {
                // APKs built by the old pipeline still carry assets; keep them working.
                String[] names;
                try {
                    names = context.getAssets().list("payload/" + dir);
                } catch (IOException e) {
                    names = null;
                }
                if (names != null) {
                    List<String> sorted = new ArrayList<>(Arrays.asList(names));
                    Collections.sort(sorted);
                    for (String name : sorted) {
                        if (!name.endsWith(".targz") && !name.endsWith(".tar.gz")) continue;
                        step(step, "Extracting " + name);
                        extractTarGz(new File(context.getCacheDir(), "opx-" + name),
                                context.getAssets().open("payload/" + dir + "/" + name),
                                context.getFilesDir());
                    }
                }
            } else {
                for (File file : downloaded) {
                    step(step, "Extracting " + file.getName());
                    extractTarGz(new File(context.getCacheDir(), "opx-" + file.getName()),
                            new FileInputStream(file), context.getFilesDir());
                }
            }
        }
        if (!new File(prefix, "bin/python3").isFile()) {
            throw new IOException("The downloaded runtime has no python3");
        }
        if (!new File(prefix, "lib/libusb-1.0.so").isFile()
                && !new File(prefix, "lib/libusb-1.0.so.0").isFile()) {
            AppLog.info("OpxRuntime", "warning: libusb-1.0.so not found under " + prefix);
        }
    }

    /** Streams a payload tar.gz into {@code files}, mapping guest paths onto the app home. */
    private static void extractTarGz(File scratch, InputStream asset, File appFiles)
            throws IOException {
        try (InputStream in = new BufferedInputStream(asset, 1 << 16);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(in, true);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                while (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty()) continue;
                if (name.startsWith(PAYLOAD_ROOT)) {
                    name = name.substring(PAYLOAD_ROOT.length());
                } else if (!name.startsWith("usr/") && !name.startsWith("home/")) {
                    // Only the prefix and the home directory belong in the app home.
                    continue;
                }
                if (name.contains("..")) continue;

                File target = new File(appFiles, name);
                if (entry.isDirectory()) {
                    ensureDirectory(target);
                    continue;
                }
                ensureDirectory(target.getParentFile());
                if (entry.isSymbolicLink()) {
                    try {
                        if (target.exists()) target.delete();
                        Os.symlink(entry.getLinkName(), target.getAbsolutePath());
                    } catch (ErrnoException ignored) {
                        // A stale link or an unsupported target: the next install recreates it.
                    }
                    continue;
                }
                try (FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = tar.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                int mode = entry.getMode() & 0777;
                try {
                    Os.chmod(target.getAbsolutePath(), mode == 0 ? 0644 : mode);
                } catch (ErrnoException ignored) {
                    // Default permissions are good enough for a non-executable file.
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        scratch.delete();
    }

    /** Recreates the apt cache tree the packages expect inside the app cache. */
    private static void ensureAptCache(Context context) throws IOException {
        File apt = new File(cacheDir(context), "apt");
        ensureDirectory(apt);
        ensureDirectory(new File(apt, "archives"));
        ensureDirectory(new File(apt, "archives/partial"));
    }

    /** Idempotent touch-up for an installed prefix, so an app update avoids a full reinstall. */
    public static void repair(Context context) throws IOException {
        File prefix = prefixDir(context);
        ensureDirectory(homeDir(context));
        ensureDirectory(tmpDir(context));
        ensureDirectory(cacheDir(context));
        ensureAptCache(context);
        ensureDirectory(new File(prefix, "tmp"));
        ensureDirectory(new File(prefix, "var/lib/apt/lists/partial"));
        ensureDirectory(new File(prefix, "var/cache/apt/archives/partial"));
        ensureUsbFdVariable(context);
    }

    /**
     * The descriptor half of the handshake happens inside libusb, which reads exactly one
     * environment variable whose name is compiled into the library. The engine exports the
     * granted descriptor as {@code OPX_USB_FD}, but the libusb in the base payload is Termux's
     * own build: it looks for {@code TERMUX_USB_FD}, never sees ours, falls back to scanning
     * /dev/bus/usb (which an app may not open), and fails with "Input/Output Error" while
     * Android has already granted the adapter. wifi4 then reports "no supported USB Wi-Fi
     * adapter found" with the card plugged in.
     * <p>
     * The name is one NUL-terminated string in .rodata, so rewriting it in place (the shorter
     * name padded with NULs, which keeps every offset and size identical) matches the engine
     * without re-downloading the 100 MB runtime. A payload built with the OPX name finds
     * nothing and is left untouched, and this runs from {@link #repair} too, so an already
     * installed prefix is fixed on the next app start.
     */
    static void ensureUsbFdVariable(Context context) {
        byte[] legacy = "TERMUX_USB_FD".getBytes(StandardCharsets.US_ASCII);
        byte[] current = "OPX_USB_FD".getBytes(StandardCharsets.US_ASCII);
        for (String name : new String[]{"libusb-1.0.so.0", "libusb-1.0.so"}) {
            File library = new File(new File(prefixDir(context), "lib"), name);
            if (!library.isFile()) continue;
            try {
                byte[] body;
                try (InputStream in = new FileInputStream(library);
                     ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = in.read(chunk)) > 0) buffer.write(chunk, 0, count);
                    body = buffer.toByteArray();
                }
                int rewritten = 0;
                for (int at = indexOf(body, legacy, 0);
                     at >= 0;
                     at = indexOf(body, legacy, at + legacy.length)) {
                    System.arraycopy(current, 0, body, at, current.length);
                    for (int pad = at + current.length; pad < at + legacy.length; pad++) {
                        body[pad] = 0;
                    }
                    rewritten++;
                }
                if (rewritten == 0) continue;
                try (FileOutputStream out = new FileOutputStream(library)) {
                    out.write(body);
                }
                AppLog.info("OpxRuntime", name + ": " + rewritten
                        + " USB descriptor variable(s) renamed to OPX_USB_FD");
            } catch (IOException e) {
                AppLog.info("OpxRuntime", "cannot repair " + name + ": " + e.getMessage());
            }
        }
    }

    /** First offset of {@code needle} in {@code haystack} at or after {@code from}, or -1. */
    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int at = Math.max(from, 0); at <= haystack.length - needle.length; at++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[at + i] != needle[i]) continue outer;
            }
            return at;
        }
        return -1;
    }

    /** Whether the app may read shared storage, needed for {@code ~/storage} to be usable. */
    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Builds {@code ~/storage} the way the storage-setup tool does: links the shared-storage
     * directories into the home directory. Safe to call repeatedly; blocking.
     */
    public static void setupStorage(Context context) {
        if (!hasStoragePermission(context)) return;
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return;

        File storage = new File(homeDir(context), "storage");
        File[] existing = storage.listFiles();
        if (existing != null) {
            for (File child : existing) {
                // Only remove the links: recursing would delete what they point at.
                //noinspection ResultOfMethodCallIgnored
                child.delete();
            }
        }
        if (!storage.isDirectory() && !storage.mkdirs()) return;

        symlink(storage, "shared", Environment.getExternalStorageDirectory());
        symlink(storage, "documents",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        symlink(storage, "downloads",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        symlink(storage, "dcim",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
        symlink(storage, "pictures",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
        symlink(storage, "music",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
        symlink(storage, "movies",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
        symlink(storage, "podcasts",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            symlink(storage, "audiobooks",
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS));
        }

        File[] dirs = context.getExternalFilesDirs(null);
        if (dirs != null) {
            for (int i = 0; i < dirs.length; i++) {
                if (dirs[i] != null) symlink(storage, "external-" + i, dirs[i]);
            }
        }
        dirs = context.getExternalMediaDirs();
        if (dirs != null) {
            for (int i = 0; i < dirs.length; i++) {
                if (dirs[i] != null) symlink(storage, "media-" + i, dirs[i]);
            }
        }
    }

    private static void symlink(File directory, String name, File target) {
        if (target == null) return;
        try {
            Os.symlink(target.getAbsolutePath(), new File(directory, name).getAbsolutePath());
        } catch (ErrnoException ignored) {
            // An existing link is recreated on the next run.
        }
    }

    /** Output and exit status of a one-off command executed in the runtime. */
    public static final class CommandResult {
        public final int exitCode;
        public final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /** Runs a one-off shell command in the runtime. Blocking; call off the UI thread. */
    public static CommandResult run(Context context, String... command) {
        StringBuilder output = new StringBuilder();
        if (command == null || command.length == 0) return new CommandResult(-1, "");

        List<String> argv = new ArrayList<>();
        argv.add(new File(prefixDir(context), "bin/bash").getAbsolutePath());
        argv.add("-c");
        argv.add(TextUtils.join(" ", command));

        int exitCode = -1;
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(true);
            builder.environment().clear();
            applyEnvironment(builder, context);
            Process process = builder.start();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) output.append(line).append('\n');
            }
            exitCode = process.waitFor();
        } catch (Exception e) {
            output.append(e).append('\n');
        }
        return new CommandResult(exitCode, output.toString());
    }

    /** argv that starts the headless engine; the app feeds it JSON lines on stdin. */
    public static List<String> engineCommand(Context context) {
        List<String> argv = new ArrayList<>();
        argv.add(new File(prefixDir(context), "bin/python3").getAbsolutePath());
        argv.add(PREFIX_GUEST + "/lib/opx/python/opx_engine.py");
        return argv;
    }

    public static String[] environment(Context context) {
        File prefix = prefixDir(context);
        File lib = new File(prefix, "lib");
        String pythonPath = pythonDir(context).getAbsolutePath();
        // The installed arch, so apt and the engine agree with the tree on disk; before an
        // install exists this falls back to the device's own ABI mapping.
        String arch;
        try {
            arch = arch(context);
        } catch (RuntimeException e) {
            arch = deviceArch();
        }
        return new String[]{
                "HOME=" + HOME_GUEST,
                "PREFIX=" + PREFIX_GUEST,
                "TMPDIR=" + PREFIX_GUEST + "/tmp",
                "SHELL=" + PREFIX_GUEST + "/bin/bash",
                "PATH=" + PREFIX_GUEST + "/bin:" + PREFIX_GUEST + "/bin/applets:/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "ANDROID_DATA=/data",
                "ANDROID_ROOT=/system",
                // OPX OSS's own identity: the engine and the runtime underneath it see this
                // app's names only, never another runtime's.
                "OPX_OSS=1",
                "OPX_UID=" + android.os.Process.myUid(),
                "OPX_USER_ID=" + android.os.Process.myUid(),
                "OPX_PREFIX=" + PREFIX_GUEST,
                "OPX_PACKAGE_MANAGER=apt",
                "OPX_PACKAGE_ARCH=" + arch,
                // The engine and the wifi4 modules live outside the prefix's site-packages so an
                // apt upgrade of python cannot orphan them.
                "PYTHONPATH=" + pythonPath,
                "PYTHONUNBUFFERED=1",
                // Native binaries find the runtime's own libraries first, then the vendor
                // OpenCL library harness that the engine stages for hashcat.
                "LD_LIBRARY_PATH=" + lib.getAbsolutePath() + ":" + PREFIX_GUEST + "/lib"
                        + ":" + PREFIX_GUEST + "/opt/vendor/lib",
        };
    }

    /** Fills a ProcessBuilder with the runtime environment. */
    public static void applyEnvironment(ProcessBuilder builder, Context context) {
        for (String entry : environment(context)) {
            int split = entry.indexOf('=');
            if (split > 0) {
                builder.environment().put(entry.substring(0, split), entry.substring(split + 1));
            }
        }
    }

    /**
     * Runs the runtime shell once with a throwaway command and returns {@code null} when it
     * answers, or the captured output when starting it failed. Blocking; the result is also
     * written to {@code files/opx-probe.log} and the in-app log.
     */
    public static String probe(Context context) {
        List<String> command = new ArrayList<>();
        command.add(new File(prefixDir(context), "bin/bash").getAbsolutePath());
        command.add("-c");
        command.add("echo OPX_PROBE_OK; echo PREFIX=$PREFIX; id");

        StringBuilder output = new StringBuilder();
        output.append("$ ").append(TextUtils.join(" ", command)).append('\n');
        boolean ok = false;
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.environment().clear();
            applyEnvironment(builder, context);
            process = builder.start();
            final Process started = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(started.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // The process died; whatever was printed is already captured.
                }
            }, "opx-probe-reader");
            reader.setDaemon(true);
            reader.start();

            for (int waited = 0; waited < 200 && process.isAlive(); waited++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean timedOut = process.isAlive();
            if (timedOut) process.destroy();
            reader.join(1000);
            if (timedOut) output.append("[probe timed out after 20s]\n");
            synchronized (output) {
                ok = output.toString().contains("OPX_PROBE_OK");
            }
        } catch (Exception e) {
            output.append(e).append('\n');
        } finally {
            if (process != null) process.destroy();
        }

        String log = output.toString();
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "opx-probe.log"))) {
            out.write(log.getBytes("UTF-8"));
        } catch (IOException ignored) {
            // Diagnostics are best effort only.
        }
        AppLog.info("OpxRuntime", "probe " + (ok ? "OK" : "FAILED") + ": " + log.replace("\n", " | "));
        return ok ? null : log;
    }

    /** Opens the downloaded bootstrap, or the bundled one when this is an older APK. */
    private static InputStream openBootstrap(Context context, String arch) throws IOException {
        File downloaded = RuntimeDownloadManager.bootstrapFile(context, arch);
        if (downloaded.isFile()) return new FileInputStream(downloaded);
        return context.getAssets().open("bootstrap/bootstrap-" + arch + ".zip");
    }

    private static void extractBootstrap(InputStream asset, File destination)
            throws Exception {
        byte[] buffer = new byte[8192];
        List<String[]> symlinks = new ArrayList<>();

        try (InputStream source = asset;
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    throw new IOException("Unsafe path in bootstrap: " + entryName);
                }
                if (entry.getName().equals("SYMLINKS.txt")) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(zip));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split("\u2190");
                        if (parts.length != 2) throw new IOException("Malformed symlink line: " + line);
                        String linkPath = new File(destination, parts[1]).getAbsolutePath();
                        File parent = new File(linkPath).getParentFile();
                        if (parent != null) ensureDirectory(parent);
                        symlinks.add(new String[]{parts[0], linkPath});
                    }
                    continue;
                }

                File target = new File(destination, entry.getName());
                if (entry.isDirectory()) {
                    ensureDirectory(target);
                    continue;
                }
                ensureDirectory(target.getParentFile());
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                String name = entry.getName();
                if (name.startsWith("bin/") || name.startsWith("libexec")
                        || name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")) {
                    Os.chmod(target.getAbsolutePath(), 0700);
                }
            }
        }

        if (symlinks.isEmpty()) throw new IOException("No SYMLINKS.txt in the bootstrap archive");
        for (String[] symlink : symlinks) Os.symlink(symlink[0], symlink[1]);
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory == null || directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create " + directory);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
