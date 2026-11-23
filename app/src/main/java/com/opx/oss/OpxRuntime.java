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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Owns the bundled userland.
 * <p>
 * Two kinds of assets are shipped, both built by the repository's package pipeline and
 * downloaded from the OPX package archive at APK build time:
 * <ul>
 *   <li>{@code assets/bootstrap/bootstrap-<arch>.zip} — the Termux bootstrap.</li>
 *   <li>{@code assets/payload/<arch|all>/*.tar.gz} — .deb-style trees (proot, python, the
 *       wifi4 engine, the USB bridge) that are untarred straight into the prefix.</li>
 * </ul>
 * The Termux binaries have {@code /data/data/com.termux/files/usr} compiled in, so the real
 * prefix is bind-mounted onto that guest path by the bundled proot.
 * <p>
 * The app targets SDK 28 on purpose: Android 10+ denies {@code execve()} on files in the app
 * home directory for apps targeting 29+, which is where proot and the userland live.
 */
public final class OpxRuntime {

    public static final String GUEST_PREFIX = "/data/data/com.termux/files/usr";
    public static final String GUEST_HOME = "/data/data/com.termux/files/home";

    /** Guest path the Termux packages compile their apt cache into. */
    public static final String GUEST_CACHE = "/data/data/com.termux/cache";

    private static final String[] SHARED_BINDS = {
            "/dev", "/proc", "/sys",
            "/system", "/system_ext", "/vendor", "/odm", "/product", "/apex",
            "/sdcard", "/storage"
    };

    private static final String PAYLOAD_ROOT = "data/data/com.termux/files/";

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

    public static File rootfsDir(Context context) {
        return new File(context.getFilesDir(), "rootfs");
    }

    public static File cacheDir(Context context) {
        File cache = context.getCacheDir();
        return cache != null ? cache : new File(context.getFilesDir(), "cache");
    }

    /** Where the packages put the Python modules (wifi4, the engine, the USB client). */
    public static File pythonDir(Context context) {
        return new File(prefixDir(context), "lib/opx/python");
    }

    public static String termuxArch() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
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
                throw new IllegalStateException("Unsupported ABI: " + abi);
        }
    }

    public static boolean isInstalled(Context context) {
        return new File(prefixDir(context), "bin/bash").isFile();
    }

    /** Extracts the bootstrap and every payload. Blocking; call off the UI thread. */
    public static void install(Context context) throws Exception {
        String arch = termuxArch();
        File files = context.getFilesDir();
        File prefix = prefixDir(context);
        File staging = new File(files, "usr-staging");

        deleteTree(staging);
        deleteTree(prefix);
        if (!staging.mkdirs()) throw new IOException("Cannot create " + staging);

        extractBootstrap(context, "bootstrap/bootstrap-" + arch + ".zip", staging);
        if (!staging.renameTo(prefix)) throw new IOException("Cannot move the bootstrap into place");

        extractPayloads(context, arch, prefix);

        ensureDirectory(homeDir(context));
        ensureDirectory(tmpDir(context));
        ensureAptCache(context);

        createGuestRootfs(context);
        patchSecondStageUidCheck(context);
    }

    /**
     * Untars the packaged trees into the prefix. The arch-specific payload carries the mirrored
     * Termux runtime (python, proot, libusb); the {@code all} payloads carry our own packages.
     */
    private static void extractPayloads(Context context, String arch, File prefix) throws Exception {
        for (String dir : new String[]{"all", arch}) {
            String[] names;
            try {
                names = context.getAssets().list("payload/" + dir);
            } catch (IOException e) {
                names = null;
            }
            if (names == null || names.length == 0) {
                if ("all".equals(dir)) throw new IOException("No packaged payloads in the APK");
                continue;
            }
            List<String> sorted = new ArrayList<>(Arrays.asList(names));
            Collections.sort(sorted);
            for (String name : sorted) {
                if (!name.endsWith(".tar.gz")) continue;
                extractTarGz(new File(context.getCacheDir(), "opx-" + name),
                        context.getAssets().open("payload/" + dir + "/" + name),
                        context.getFilesDir());
            }
        }
        if (!new File(prefix, "bin/proot").isFile()) {
            throw new IOException("The packaged runtime has no proot");
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

    /** Recreates the apt cache tree the Termux packages expect inside the app cache. */
    private static void ensureAptCache(Context context) throws IOException {
        File apt = new File(cacheDir(context), "apt");
        ensureDirectory(apt);
        ensureDirectory(new File(apt, "archives"));
        ensureDirectory(new File(apt, "archives/partial"));
    }

    /**
     * Builds the proot filesystem view. The guest root only needs the mount points the bind
     * mounts land on; the real prefix and home are bound onto the compiled-in Termux paths.
     */
    private static void createGuestRootfs(Context context) throws Exception {
        File rootfs = rootfsDir(context);
        deleteTree(rootfs);

        File termuxFiles = new File(rootfs, "data/data/com.termux/files");
        ensureDirectory(termuxFiles);
        ensureDirectory(new File(termuxFiles, "usr"));
        ensureDirectory(new File(termuxFiles, "home"));
        ensureDirectory(new File(rootfs, "data/data/com.termux/cache"));
        ensureDirectory(new File(rootfs, "tmp"));
        ensureDirectory(new File(rootfs, "sdcard"));
        ensureDirectory(new File(rootfs, "storage"));
        ensureDirectory(new File(rootfs, "linkerconfig"));
    }

    /**
     * The host path to bind over the guest's empty {@code /linkerconfig}, or {@code null}.
     * Bionic prints "failed to find generated linker configuration" for every guest binary when
     * the guest has none, which corrupts shell captures like {@code "$(id -u 2>&1)"}.
     */
    private static String hostLinkerConfig() {
        File config = new File("/linkerconfig/ld.config.txt");
        try {
            if (config.canRead()) return config.getAbsolutePath();
        } catch (SecurityException ignored) {
            // Fall through to the directory check below.
        }
        try {
            if (new File("/linkerconfig").list() != null) return "/linkerconfig";
        } catch (SecurityException ignored) {
            // Neither is usable; the warning is harmless.
        }
        return null;
    }

    /**
     * The bootstrap second stage insists on {@code id -u 2>&1} returning nothing but digits.
     * Any warning on stderr breaks that check, so the capture is narrowed to stdout only.
     */
    private static void patchSecondStageUidCheck(Context context) {
        File script = new File(prefixDir(context),
                "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh");
        if (!script.isFile()) return;
        try {
            String original = readText(script);
            String patched = original.replace("\"$(id -u 2>&1)\"", "\"$(id -u 2>/dev/null)\"");
            if (patched.equals(original)) return;
            try (FileOutputStream out = new FileOutputStream(script)) {
                out.write(patched.getBytes("UTF-8"));
            }
            Os.chmod(script.getAbsolutePath(), 0700);
        } catch (Exception ignored) {
            // The fallback script only logs a failed second stage; the shell still starts.
        }
    }

    /** Idempotent touch-up for an installed prefix, so an app update avoids a full reinstall. */
    public static void repair(Context context) throws IOException {
        ensureDirectory(new File(rootfsDir(context), "linkerconfig"));
        ensureDirectory(new File(rootfsDir(context), "data/data/com.termux/files/usr"));
        ensureDirectory(new File(rootfsDir(context), "data/data/com.termux/files/home"));
        ensureDirectory(new File(rootfsDir(context), "data/data/com.termux/cache"));
        ensureAptCache(context);
        patchSecondStageUidCheck(context);
    }

    /** Whether the app may read shared storage, needed for {@code ~/storage} to be usable. */
    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Builds {@code ~/storage} the way {@code termux-setup-storage} does: the app links the
     * shared-storage directories into the home directory. Safe to call repeatedly; blocking.
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

    private static String readText(File file) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString();
    }

    /** Output and exit status of a one-off command executed in the bundled userland. */
    public static final class CommandResult {
        public final int exitCode;
        public final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /** Runs a one-off shell command inside the userland. Blocking; call off the UI thread. */
    public static CommandResult run(Context context, String... command) {
        StringBuilder output = new StringBuilder();
        if (command == null || command.length == 0) return new CommandResult(-1, "");

        List<String> argv = prootCommand(context);
        argv.add(GUEST_PREFIX + "/bin/bash");
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
        List<String> argv = prootCommand(context);
        argv.add(GUEST_PREFIX + "/bin/python3");
        argv.add(GUEST_PREFIX + "/lib/opx/python/opx_engine.py");
        return argv;
    }

    /** The proot invocation plus its bind mounts, without a command to run. */
    private static List<String> prootCommand(Context context) {
        List<String> argv = new ArrayList<>();
        argv.add(new File(prefixDir(context), "bin/proot").getAbsolutePath());
        argv.add("--link2symlink");
        // Deliberately no "-0": faking uid 0 makes the bootstrap second stage abort, and
        // packages are meant to be configured as the real app uid, exactly like upstream Termux.
        argv.add("--kill-on-exit");
        argv.add("-r");
        argv.add(rootfsDir(context).getAbsolutePath());
        for (String bind : SHARED_BINDS) {
            if (new File(bind).exists()) {
                argv.add("-b");
                argv.add(bind);
            }
        }
        String linkerConfig = hostLinkerConfig();
        if (linkerConfig != null) {
            argv.add("-b");
            argv.add(linkerConfig);
        }
        argv.add("-b");
        argv.add(prefixDir(context).getAbsolutePath() + ":" + GUEST_PREFIX);
        argv.add("-b");
        argv.add(homeDir(context).getAbsolutePath() + ":" + GUEST_HOME);
        argv.add("-b");
        argv.add(tmpDir(context).getAbsolutePath() + ":/tmp");
        argv.add("-b");
        argv.add(cacheDir(context).getAbsolutePath() + ":" + GUEST_CACHE);
        argv.add("-w");
        argv.add(GUEST_HOME);
        return argv;
    }

    public static String[] environment(Context context) {
        File prefix = prefixDir(context);
        File lib = new File(prefix, "lib");
        String pythonPath = pythonDir(context).getAbsolutePath();
        return new String[]{
                "HOME=" + GUEST_HOME,
                "PREFIX=" + GUEST_PREFIX,
                "TMPDIR=" + GUEST_PREFIX + "/tmp",
                "SHELL=" + GUEST_PREFIX + "/bin/bash",
                "PATH=" + GUEST_PREFIX + "/bin:/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "ANDROID_DATA=/data",
                "ANDROID_ROOT=/system",
                // What the Termux app exports for its own shell; the bootstrap scripts check these.
                "TERMUX__UID=" + android.os.Process.myUid(),
                "TERMUX__USER_ID=" + android.os.Process.myUid(),
                "TERMUX_PREFIX=" + GUEST_PREFIX,
                "TERMUX_PACKAGE_MANAGER=apt",
                "TERMUX_PACKAGE_ARCH=" + termuxArch(),
                // The engine and the wifi4 modules live outside the prefix's site-packages so an
                // apt upgrade of python cannot orphan them.
                "PYTHONPATH=" + pythonPath,
                "PYTHONUNBUFFERED=1",
                // proot is a host process and resolves its libraries from the real prefix; the
                // guest entries are what the mounted prefix binaries look for.
                "LD_LIBRARY_PATH=" + lib.getAbsolutePath() + ":" + GUEST_PREFIX + "/lib",
                "PROOT_LOADER=" + new File(prefix, "libexec/proot/loader").getAbsolutePath(),
                "PROOT_TMP_DIR=" + tmpDir(context).getAbsolutePath()
        };
    }

    /** Fills a ProcessBuilder with the userland environment. */
    public static void applyEnvironment(ProcessBuilder builder, Context context) {
        for (String entry : environment(context)) {
            int split = entry.indexOf('=');
            if (split > 0) {
                builder.environment().put(entry.substring(0, split), entry.substring(split + 1));
            }
        }
    }

    /**
     * Runs the bundled proot once with a throwaway command and returns {@code null} when the
     * shell answers, or the captured output when starting it failed. Blocking; the result is
     * also written to {@code files/opx-proot.log} for bug reports.
     */
    public static String probe(Context context) {
        List<String> command = prootCommand(context);
        command.add(GUEST_PREFIX + "/bin/bash");
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
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "opx-proot.log"))) {
            out.write(log.getBytes("UTF-8"));
        } catch (IOException ignored) {
            // Diagnostics are best effort only.
        }
        return ok ? null : log;
    }

    private static void extractBootstrap(Context context, String assetPath, File destination)
            throws Exception {
        byte[] buffer = new byte[8192];
        List<String[]> symlinks = new ArrayList<>();

        try (InputStream asset = context.getAssets().open(assetPath);
             ZipInputStream zip = new ZipInputStream(asset)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
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
