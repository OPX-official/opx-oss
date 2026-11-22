package com.opx.oss;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Owns the bundled Termux userland.
 * <p>
 * The Termux bootstrap binaries are compiled with a hardcoded {@code $PREFIX} of
 * {@code /data/data/com.termux/files/usr}. OPX OSS lives in {@code com.opx.oss}, so the
 * real prefix ({@code <files>/usr}) is bind-mounted onto that expected guest path by the
 * bundled proot. Everything the app needs is extracted from the APK on first launch, so
 * installing never touches the network.
 * <p>
 * The app targets SDK 28 on purpose: Android 10+ denies {@code execve()} on files in the app
 * home directory for apps targeting 29+, which is where the bundled proot and the Termux
 * binaries live (upstream Termux keeps the same target SDK for the same reason).
 */
public final class OpxRuntime {

    public static final String GUEST_PREFIX = "/data/data/com.termux/files/usr";
    public static final String GUEST_HOME = "/data/data/com.termux/files/home";

    private static final String[] SHARED_BINDS = {
            "/dev", "/proc", "/sys",
            "/system", "/system_ext", "/vendor", "/odm", "/product", "/apex",
            "/sdcard", "/storage"
    };

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

    /**
     * Guest path {@link #GUEST_CACHE} is the apt cache directory compiled into the Termux packages
     * ({@code -DCACHE_DIR=${TERMUX_CACHE_DIR}/apt}) and read by the {@code pkg} mirror wrapper. The
     * Android cache directory of the app is the exact equivalent, so it is bound there; without it
     * apt fails with "Archives directory .../apt/archives/partial is missing".
     */
    public static final String GUEST_CACHE = "/data/data/com.termux/cache";

    public static File cacheDir(Context context) {
        File cache = context.getCacheDir();
        return cache != null ? cache : new File(context.getFilesDir(), "cache");
    }

    /** Recreates the apt cache tree the Termux packages expect inside {@link #cacheDir(Context)}. */
    private static void ensureAptCache(Context context) throws IOException {
        File apt = new File(cacheDir(context), "apt");
        ensureDirectory(apt);
        ensureDirectory(new File(apt, "archives"));
        ensureDirectory(new File(apt, "archives/partial"));
    }

    public static File prootDir(Context context) {
        return new File(context.getFilesDir(), "libexec/opx-proot");
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

    /** Extracts the bundled bootstrap and proot runtime. Blocking; call off the UI thread. */
    public static void install(Context context) throws Exception {
        String arch = termuxArch();
        File files = context.getFilesDir();
        File prefix = prefixDir(context);
        File staging = new File(files, "usr-staging");

        deleteTree(staging);
        deleteTree(prefix);
        if (!staging.mkdirs()) throw new IOException("Cannot create " + staging);

        extractBootstrap(context.getAssets(), "bootstrap/bootstrap-" + arch + ".zip", staging);

        if (!staging.renameTo(prefix)) throw new IOException("Cannot move the bootstrap into place");

        // proot is kept outside the bootstrap: it has to start before the prefix exists in the guest.
        File prootDir = prootDir(context);
        deleteTree(prootDir);
        if (!prootDir.mkdirs()) throw new IOException("Cannot create " + prootDir);

        File libDir = new File(prefix, "lib");
        if (!libDir.exists() && !libDir.mkdirs()) throw new IOException("Cannot create " + libDir);

        copyAsset(context.getAssets(), "proot/" + arch + "/proot", new File(prootDir, "proot"), 0700);
        copyAsset(context.getAssets(), "proot/" + arch + "/loader", new File(prootDir, "loader"), 0700);
        copyAsset(context.getAssets(), "proot/" + arch + "/libtalloc.so.2",
                new File(libDir, "libtalloc.so.2"), 0600);
        copyAsset(context.getAssets(), "proot/" + arch + "/libandroid-shmem.so",
                new File(libDir, "libandroid-shmem.so"), 0600);

        if (!homeDir(context).exists() && !homeDir(context).mkdirs()) {
            throw new IOException("Cannot create " + homeDir(context));
        }
        if (!tmpDir(context).exists() && !tmpDir(context).mkdirs()) {
            throw new IOException("Cannot create " + tmpDir(context));
        }
        ensureAptCache(context);

        createGuestRootfs(context);
        patchSecondStageUidCheck(context);
    }

    /**
     * Builds the proot filesystem view. The guest root only needs the mount points that the
     * bind mounts in {@link #prootArgs(Context)} land on; the real prefix and home are bound
     * onto the paths the Termux binaries were compiled with.
     */
    private static void createGuestRootfs(Context context) throws Exception {
        File rootfs = rootfsDir(context);
        deleteTree(rootfs);

        File termuxFiles = new File(rootfs, "data/data/com.termux/files");
        if (!termuxFiles.mkdirs()) throw new IOException("Cannot create " + termuxFiles);
        ensureDirectory(new File(termuxFiles, "usr"));
        ensureDirectory(new File(termuxFiles, "home"));
        ensureDirectory(new File(rootfs, "data/data/com.termux/cache"));
        ensureDirectory(new File(rootfs, "tmp"));
        ensureDirectory(new File(rootfs, "sdcard"));
        ensureDirectory(new File(rootfs, "storage"));
        // Mount point for the host linker configuration; without it the Android linker of every
        // guest binary prints a warning on stderr, which in turn corrupts shell captures like
        // "$(id -u 2>&1)" in the bootstrap maintenance scripts.
        ensureDirectory(new File(rootfs, "linkerconfig"));
    }

    /**
     * The host path to bind over the guest's empty {@code /linkerconfig}, or {@code null}.
     * <p>
     * Bionic prints "failed to find generated linker configuration" for every guest binary when the
     * guest has no {@code ld.config.txt}, and the Android runtime executables (the {@code am} wrapper
     * runs {@code /system/bin/app_process}) cannot resolve {@code /system/lib64} at all without it.
     * The directory itself is not readable on every ROM, so the single file is preferred: proot can
     * bind it as long as the app may read it, while binding the directory needs a traversable,
     * listable {@code /linkerconfig}. When neither works the guest keeps its own empty directory and
     * only the cosmetic warning remains.
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
     * The bootstrap second stage insists on {@code id -u 2>&1} returning nothing but digits. Any
     * warning on stderr (linker configuration, for example) breaks that check, so the capture is
     * narrowed to stderr-less output. Only run once, by {@link #install(Context)}.
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

    /**
     * Idempotent touch-up for an already installed prefix, so an app update does not need a full
     * reinstall to pick up runtime layout fixes. Blocking; call off the UI thread.
     */
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
     * Builds {@code ~/storage} the same way the Termux app does after {@code termux-setup-storage}:
     * the app process links the shared-storage directories into the home directory, because the
     * broadcast the script sends is addressed to the Termux package itself. Safe to call repeatedly;
     * blocking, call off the UI thread.
     */
    public static void setupStorage(Context context) {
        if (!hasStoragePermission(context)) return;
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return;

        File storage = new File(homeDir(context), "storage");
        File[] existing = storage.listFiles();
        if (existing != null) {
            for (File child : existing) {
                // Only remove the links: recursing would delete what they point at (shared storage).
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
                new java.io.FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString();
    }

    /** argv for the terminal session: proot [flags] bash -l. */
    public static String[] prootArgs(Context context) {
        List<String> argv = new ArrayList<>();
        argv.add(new File(prootDir(context), "proot").getAbsolutePath());
        argv.add("--link2symlink");
        // Deliberately no "-0": faking uid 0 makes the bootstrap second stage abort with
        // "The uid '0' returned by 'id -u' command is not valid", and packages are meant to be
        // configured as the real app uid, exactly like upstream Termux.
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
        // The bootstrap was compiled for com.termux, so the real prefix and home have to answer
        // on the guest paths baked into every Termux binary.
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
        argv.add(GUEST_PREFIX + "/bin/bash");
        argv.add("-l");
        return argv.toArray(new String[0]);
    }

    public static String[] environment(Context context) {
        File libDir = new File(prefixDir(context), "lib");
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
                // What the Termux app exports for its own shell; the bootstrap maintenance
                // scripts check these.
                "TERMUX__UID=" + android.os.Process.myUid(),
                "TERMUX__USER_ID=" + android.os.Process.myUid(),
                "TERMUX_PREFIX=" + GUEST_PREFIX,
                "TERMUX_PACKAGE_MANAGER=apt",
                "TERMUX_PACKAGE_ARCH=" + termuxArch(),
                // proot itself is a host process and resolves its libraries from the real prefix;
                // the guest entries are what the mounted prefix binaries look for.
                "LD_LIBRARY_PATH=" + libDir.getAbsolutePath() + ":" + GUEST_PREFIX + "/lib",
                "PROOT_LOADER=" + new File(prootDir(context), "loader").getAbsolutePath(),
                "PROOT_TMP_DIR=" + tmpDir(context).getAbsolutePath()
        };
    }

    /**
     * Runs the bundled proot once with a throwaway command and returns {@code null} when the
     * shell answers, or the captured output when starting it failed. Blocking; call off the UI
     * thread. The result is also written to {@code files/opx-proot.log} for bug reports.
     */
    public static String probe(Context context) {
        List<String> command = new ArrayList<>(Arrays.asList(prootArgs(context)));
        command.add("-c");
        command.add("echo OPX_PROBE_OK; echo PREFIX=$PREFIX; id");

        StringBuilder output = new StringBuilder();
        output.append("$ ").append(TextUtils.join(" ", command)).append('\n');
        boolean ok = false;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.environment().clear();
            for (String entry : environment(context)) {
                int split = entry.indexOf('=');
                builder.environment().put(entry.substring(0, split), entry.substring(split + 1));
            }
            Process process = builder.start();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
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
        }

        String log = output.toString();
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "opx-proot.log"))) {
            out.write(log.getBytes("UTF-8"));
        } catch (IOException ignored) {
            // Diagnostics are best effort only.
        }
        return ok ? null : log;
    }

    private static void extractBootstrap(AssetManager assets, String assetPath, File destination)
            throws Exception {
        byte[] buffer = new byte[8192];
        List<String[]> symlinks = new ArrayList<>();

        try (InputStream asset = assets.open(assetPath);
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

    private static void copyAsset(AssetManager assets, String assetPath, File destination, int mode)
            throws Exception {
        ensureDirectory(destination.getParentFile());
        try (InputStream in = assets.open(assetPath);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        Os.chmod(destination.getAbsolutePath(), mode);
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
