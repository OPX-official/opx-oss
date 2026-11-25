package com.opx.oss;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.util.Locale;

/**
 * The app's own directories and architecture.
 * <p>
 * OPX OSS runs everything in this process: the engine, the USB bridge and the captures all live
 * in the app's private storage, so there is nothing to download, unpack or execute. This class
 * only answers where those files go and what the phone's ABI is — the names are kept because
 * the USB bridge and the settings screen print them.
 */
public final class OpxRuntime {

    /** Where the engine keeps its captures and keys. */
    public static final String PREFIX_GUEST = "/data/data/com.opx.oss/files/usr";
    /** The engine's home directory. */
    public static final String HOME_GUEST = "/data/data/com.opx.oss/files/home";
    /** Scratch space for captures mid-write. */
    public static final String CACHE_GUEST = "/data/data/com.opx.oss/cache";

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

    /** The phone's ABI, in the shape the settings screen and the USB log print it. */
    public static String arch(Context context) {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        return abi.toLowerCase(Locale.US);
    }

    /** The engine ships inside the APK, so it is always there to run. */
    public static boolean isInstalled(Context context) {
        return true;
    }

    /** True when the app may write to shared storage, which is where exports go by default. */
    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return true;
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Create the directories the engine writes into; safe to call on every start. */
    public static void setupStorage(Context context) {
        File[] dirs = {prefixDir(context), homeDir(context), tmpDir(context),
                new File(context.getCacheDir(), "captures")};
        for (File dir : dirs) {
            if (!dir.isDirectory()) dir.mkdirs();
        }
        // Keep the shared-storage export folder ready when the permission is there.
        if (hasStoragePermission(context)) {
            File exports = new File(Environment.getExternalStorageDirectory(), "OPX");
            if (!exports.isDirectory()) exports.mkdirs();
        }
    }

    /** Where a capture is written when the user asks for it in shared storage. */
    public static File exportDir() {
        return new File(Environment.getExternalStorageDirectory(), "OPX");
    }
}
