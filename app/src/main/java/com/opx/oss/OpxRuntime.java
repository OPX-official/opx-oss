package com.opx.oss;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Owns the bundled Termux userland.
 * <p>
 * The Termux bootstrap binaries are compiled with a hardcoded {@code $PREFIX} of
 * {@code /data/data/com.termux/files/usr}. OPX OSS lives in {@code com.opx.oss}, so the
 * real prefix ({@code <files>/usr}) is bind-mounted onto that expected guest path by the
 * bundled statically-linked-in-place proot. Everything the app needs is extracted from
 * the APK on first launch, so installing never touches the network.
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

        createGuestRootfs(context);
    }

    /**
     * Builds the proot filesystem view. The guest root only carries the paths that have to map
     * onto the real prefix and home; everything else (system binaries, /dev, /sdcard, ...) is
     * bind-mounted by {@link #prootArgs(Context)}.
     */
    private static void createGuestRootfs(Context context) throws Exception {
        File rootfs = rootfsDir(context);
        deleteTree(rootfs);

        File termuxFiles = new File(rootfs, "data/data/com.termux/files");
        if (!termuxFiles.mkdirs()) throw new IOException("Cannot create " + termuxFiles);

        // rootfs/data/data/com.termux/files/usr -> rootfs/usr -> ../usr (the real prefix)
        Os.symlink("../usr", new File(rootfs, "usr").getAbsolutePath());
        Os.symlink("../home", new File(rootfs, "home").getAbsolutePath());
        Os.symlink("../tmp", new File(rootfs, "tmp").getAbsolutePath());
        Os.symlink("../../../../usr", new File(termuxFiles, "usr").getAbsolutePath());
        Os.symlink("../../../../home", new File(termuxFiles, "home").getAbsolutePath());
    }

    /** argv for the terminal session: proot [flags] bash -l. */
    public static String[] prootArgs(Context context) {
        List<String> argv = new ArrayList<>();
        argv.add(new File(prootDir(context), "proot").getAbsolutePath());
        argv.add("--link2symlink");
        argv.add("-0");
        argv.add("--kill-on-exit");
        argv.add("-r");
        argv.add(rootfsDir(context).getAbsolutePath());
        for (String bind : SHARED_BINDS) {
            if (new File(bind).exists()) {
                argv.add("-b");
                argv.add(bind);
            }
        }
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
                // proot itself is a host process and resolves its libraries from the real prefix.
                "LD_LIBRARY_PATH=" + libDir.getAbsolutePath(),
                "PROOT_LOADER=" + new File(prootDir(context), "loader").getAbsolutePath(),
                "PROOT_TMP_DIR=" + tmpDir(context).getAbsolutePath()
        };
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
