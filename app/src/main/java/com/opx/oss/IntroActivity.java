package com.opx.oss;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stryker's first-run flow with the OPX steps: welcome, permissions, runtime download, engine
 * install and ready. Permissions are collected here, once, before any other screen is reachable;
 * the download slide follows the phone's real ABI, names the file it is fetching, shows
 * bytes/speed/ETA, and then keeps the same slide while the verified archive is unpacked.
 */
public class IntroActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 41;
    private static final int INDEX_PERMISSIONS = 1;
    private static final int INDEX_DOWNLOAD = 2;
    private static final int INDEX_FINAL = 4;

    private static final int[] SLIDES = {
            R.id.slide_welcome,
            R.id.slide_permissions,
            R.id.slide_download,
            R.id.slide_engine,
            R.id.slide_ready,
    };

    private static final int[] ANIMS = {
            R.raw.waving_hand,
            R.raw.permissions_key,
            R.raw.icon_morph,
            R.raw.settings_gears,
            R.raw.confetti,
    };

    private static final int[] TITLES = {
            R.string.intro_welcome_title,
            R.string.intro_permissions_title,
            R.string.intro_download_title,
            R.string.intro_engine_title,
            R.string.intro_ready_title,
    };

    private static final int[] BODIES = {
            R.string.intro_welcome_body,
            R.string.intro_permissions_body,
            R.string.intro_download_body,
            R.string.intro_engine_body,
            R.string.intro_ready_body,
    };

    private static final int[] DOTS = {
            R.id.dot_0, R.id.dot_1, R.id.dot_2, R.id.dot_3, R.id.dot_4,
    };

    private int index;
    private IntroBackgroundView background;
    private MaterialButton next;
    private MaterialButton skip;

    // The download slide, laid out like Stryker's install slide.
    private LottieAnimationView downloadAnim;
    private ProgressBar downloadSpinner;
    private ImageView downloadIcon;
    private TextView downloadSubtitle;
    private LinearProgressIndicator downloadProgress;
    private TextView downloadText;
    private TextView downloadFile;
    private MaterialButton downloadAction;
    private InstallLogDialog logDialog;

    /** The system dialog was already requested once; a second Next never waits on it again. */
    private boolean permissionAsked;
    /** A permission request is in flight and the slide should advance when it answers. */
    private boolean pendingAdvance;
    /** The battery whitelist prompt is best-effort and asked at most once per intro. */
    private boolean askedBattery;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshChrome();
            handler.postDelayed(this, 400);
        }
    };
    private static volatile boolean showing;

    /** The USB prompt waits until the intro is out of the way. */
    public static boolean isShowing() {
        return showing;
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = true;
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        showing = false;
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (logDialog != null) {
            logDialog.dismiss();
            logDialog = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intro);
        background = findViewById(R.id.intro_background);
        next = findViewById(R.id.intro_next);
        skip = findViewById(R.id.intro_skip);

        for (int i = 0; i < SLIDES.length; i++) {
            if (i == INDEX_DOWNLOAD) continue;
            View slide = findViewById(SLIDES[i]);
            LottieAnimationView anim = slide.findViewById(R.id.slide_anim);
            ((TextView) slide.findViewById(R.id.slide_title)).setText(TITLES[i]);
            ((TextView) slide.findViewById(R.id.slide_body)).setText(BODIES[i]);
            anim.setAnimation(ANIMS[i]);
            configureAction(slide, i);
        }
        bindDownloadSlide();

        skip.setOnClickListener(v -> {
            if (index < INDEX_DOWNLOAD) {
                // Permissions are optional: a ROM that exposes none of them must never trap the
                // user on the permissions slide.
                index = INDEX_DOWNLOAD;
                show();
            } else if (index < INDEX_FINAL) {
                index = INDEX_FINAL;
                show();
            }
        });
        next.setOnClickListener(v -> {
            if (index == INDEX_PERMISSIONS && !allPermissionsGranted()) {
                // Ask once and move on with whatever the phone answers. If the OEM shows no
                // dialog at all, the second tap advances anyway, so Next is never a dead end.
                if (permissionAsked) {
                    index = INDEX_DOWNLOAD;
                    show();
                    return;
                }
                permissionAsked = true;
                pendingAdvance = true;
                askPermissions();
                return;
            }
            if (index == INDEX_DOWNLOAD && !runtimeReady()) {
                if (downloadFailed()) retryDownload();
                return;
            }
            if (index >= SLIDES.length - 1) {
                finishSetup();
                return;
            }
            index++;
            show();
        });

        show();
    }

    private void bindDownloadSlide() {
        View slide = findViewById(R.id.slide_download);
        downloadAnim = slide.findViewById(R.id.download_anim);
        downloadSpinner = slide.findViewById(R.id.download_spinner);
        downloadIcon = slide.findViewById(R.id.download_icon);
        downloadSubtitle = slide.findViewById(R.id.download_subtitle);
        downloadProgress = slide.findViewById(R.id.download_progress);
        downloadText = slide.findViewById(R.id.download_text);
        downloadFile = slide.findViewById(R.id.download_file);
        downloadAction = slide.findViewById(R.id.download_action);

        ((TextView) slide.findViewById(R.id.download_title)).setText(TITLES[INDEX_DOWNLOAD]);
        downloadAnim.setAnimation(ANIMS[INDEX_DOWNLOAD]);
        downloadAnim.playAnimation();
        downloadAction.setText(R.string.intro_retry);
        downloadAction.setOnClickListener(v -> {
            if (downloadFailed()) retryDownload();
            else {
                OpxService.beginDownload(this);
                OpxService.restart(this);
            }
        });
        MaterialButton details = slide.findViewById(R.id.download_details);
        details.setOnClickListener(v -> showLog());
    }

    private void showLog() {
        if (logDialog != null && logDialog.isShowing()) return;
        logDialog = InstallLogDialog.show(this);
    }

    private void configureAction(View slide, int slideIndex) {
        MaterialButton action = slide.findViewById(R.id.slide_action);
        if (slideIndex == INDEX_PERMISSIONS) {
            action.setText(R.string.intro_grant);
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(v -> askPermissions());
        } else {
            action.setVisibility(View.GONE);
        }
    }

    private void show() {
        for (int i = 0; i < SLIDES.length; i++) {
            View slide = findViewById(SLIDES[i]);
            slide.setVisibility(i == index ? View.VISIBLE : View.GONE);
            if (i == index) {
                LottieAnimationView anim = slide.findViewById(R.id.slide_anim);
                if (anim == null) anim = slide.findViewById(R.id.download_anim);
                if (anim != null) anim.playAnimation();
                slide.setAlpha(0f);
                slide.setTranslationY(24f * getResources().getDisplayMetrics().density);
                slide.animate().alpha(1f).translationY(0f).setDuration(420)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                        .start();
            }
            View dot = findViewById(DOTS[i]);
            dot.setBackgroundResource(i == index
                    ? R.drawable.intro_dot_active
                    : R.drawable.intro_dot);
        }
        if (background != null) background.setPageOffset(index);
        if (index == INDEX_DOWNLOAD) {
            // The download only needs the network. It starts even when a storage, location or
            // notification permission was refused or the ROM has no such switch; the features
            // that need one degrade instead of blocking setup.
            OpxService.beginDownload(this);
            requestBatteryExemption();
        }
        next.setText(index >= SLIDES.length - 1 ? R.string.intro_start : R.string.intro_next);
        skip.setVisibility(index >= INDEX_FINAL ? View.GONE : View.VISIBLE);
        skip.setText(index == 0 ? R.string.intro_skip : R.string.intro_skip_to_setup);
        refreshChrome();
    }

    /** Cheap polling update: never restarts the slide animation while a download is running. */
    private void refreshChrome() {
        if (next == null) return;
        if (index == INDEX_PERMISSIONS) {
            // Missing permissions never disable Next; the slide only offers to grant them.
            next.setEnabled(true);
            View slide = findViewById(R.id.slide_permissions);
            MaterialButton action = slide.findViewById(R.id.slide_action);
            action.setVisibility(allPermissionsGranted() ? View.GONE : View.VISIBLE);
        } else if (index == INDEX_DOWNLOAD) {
            refreshDownload(RuntimeDownloadManager.snapshot());
        } else {
            next.setEnabled(true);
        }
    }

    private boolean downloadFailed() {
        return OpxService.getState() == OpxService.STATE_FAILED
                || RuntimeDownloadManager.snapshot().state == RuntimeDownloadManager.STATE_ERROR;
    }

    /**
     * Stryker's install slide: a spinner (or a done/error icon), the stage that is running, the
     * archive being worked on and a monospace bytes/speed/ETA line underneath.
     */
    private void refreshDownload(RuntimeDownloadManager.Snapshot snapshot) {
        if (downloadProgress == null) return;

        if (runtimeReady()) {
            downloadSpinner.setVisibility(View.GONE);
            downloadIcon.setVisibility(View.VISIBLE);
            downloadIcon.setImageResource(R.drawable.done);
            downloadIcon.setColorFilter(ContextCompat.getColor(this, R.color.green),
                    PorterDuff.Mode.SRC_IN);
            downloadProgress.setIndeterminate(false);
            downloadProgress.setProgressCompat(100, true);
            downloadSubtitle.setText(R.string.intro_download_ready);
            downloadText.setText(getString(R.string.intro_download_done, snapshot.arch));
            downloadFile.setText("");
            downloadAction.setVisibility(View.GONE);
            next.setEnabled(true);
            return;
        }

        if (downloadFailed()) {
            String error = OpxService.getState() == OpxService.STATE_FAILED
                    ? OpxService.getError() : snapshot.error;
            if (error == null || error.isEmpty()) error = getString(R.string.intro_download_unknown);
            downloadSpinner.setVisibility(View.GONE);
            downloadIcon.setVisibility(View.VISIBLE);
            downloadIcon.setImageResource(R.drawable.error);
            downloadIcon.setColorFilter(ContextCompat.getColor(this, R.color.red),
                    PorterDuff.Mode.SRC_IN);
            downloadProgress.setIndeterminate(false);
            downloadProgress.setProgressCompat(0, true);
            downloadSubtitle.setText(getString(R.string.intro_download_error, error));
            downloadText.setText("");
            downloadFile.setText("");
            downloadAction.setVisibility(View.VISIBLE);
            downloadAction.setText(R.string.intro_retry);
            next.setEnabled(false);
            return;
        }

        downloadIcon.setVisibility(View.GONE);
        downloadIcon.clearColorFilter();
        downloadSpinner.setVisibility(View.VISIBLE);
        downloadAction.setVisibility(View.GONE);
        next.setEnabled(false);

        if (snapshot.state == RuntimeDownloadManager.STATE_INSTALLING
                || snapshot.state == RuntimeDownloadManager.STATE_READY) {
            downloadProgress.setIndeterminate(true);
            downloadSubtitle.setText(snapshot.label);
            downloadText.setText(R.string.intro_installing);
            downloadFile.setText("");
        } else if (snapshot.state == RuntimeDownloadManager.STATE_DOWNLOADING) {
            downloadProgress.setIndeterminate(snapshot.total <= 0);
            downloadProgress.setProgressCompat(snapshot.percent(), true);
            downloadSubtitle.setText(snapshot.label);
            downloadText.setText(String.format(Locale.US, "%s / %s (%d%%) · %s · ETA %s",
                    formatMb(snapshot.downloaded), formatMb(snapshot.total), snapshot.percent(),
                    formatSpeed(snapshot.bytesPerSec), formatEta(snapshot.etaSeconds)));
            downloadFile.setText(snapshot.fileCount > 0
                    ? getString(R.string.intro_download_file, snapshot.fileIndex,
                    snapshot.fileCount, snapshot.file)
                    : snapshot.file);
        } else {
            downloadProgress.setIndeterminate(true);
            downloadSubtitle.setText(R.string.intro_download_wait);
            downloadText.setText("");
            downloadFile.setText("");
            downloadAction.setVisibility(View.VISIBLE);
            downloadAction.setText(R.string.intro_start_download);
        }
    }

    private static String formatMb(long bytes) {
        if (bytes <= 0) return "? MB";
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "—";
        if (bytesPerSec < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1024.0 / 1024.0);
    }

    private static String formatEta(long seconds) {
        if (seconds < 0) return "—";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format(Locale.US, "%dm %02ds", seconds / 60, seconds % 60);
        return String.format(Locale.US, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
    }

    private boolean runtimeReady() {
        return OpxRuntime.isInstalled(this) || OpxService.getState() == OpxService.STATE_READY;
    }

    private void retryDownload() {
        RuntimeDownloadManager.retry();
        OpxService.beginDownload(this);
        OpxService.restart(this);
    }

    private void finishSetup() {
        // Deliberately not gated on permissions: whatever the phone allowed is what the app
        // works with, and the user is never sent back to a slide they cannot satisfy.
        if (!runtimeReady()) {
            index = INDEX_DOWNLOAD;
            show();
            return;
        }
        getSharedPreferences("opx", MODE_PRIVATE).edit()
                .putBoolean("intro_seen", true).apply();
        finish();
    }

    // ---------------------------------------------------------------- permissions

    /** Runtime permissions the app needs, all collected on the permissions slide. */
    static String[] requiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        // targetSdk 28 still uses the legacy storage permission pair on every Android version.
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions.toArray(new String[0]);
    }

    private boolean allPermissionsGranted() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void askPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        }
        if (missing.isEmpty()) {
            setupStorage();
            refreshChrome();
            return;
        }
        try {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        } catch (RuntimeException e) {
            // A ROM that cannot answer the request must still let the user past the slide.
            if (pendingAdvance) {
                pendingAdvance = false;
                index = INDEX_DOWNLOAD;
                show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (allPermissionsGranted()) {
            setupStorage();
            UsbAutoConnect.requestNow(this);
        }
        refreshChrome();
        if (!allPermissionsGranted()) {
            // A second denial is final for the dialog; the system settings page is the only way
            // left, and it is an offer — the slide still lets the user continue without it.
            View slide = findViewById(R.id.slide_permissions);
            MaterialButton action = slide.findViewById(R.id.slide_action);
            action.setText(R.string.intro_open_settings);
            action.setOnClickListener(v -> openAppSettings());
        }
        if (pendingAdvance) {
            pendingAdvance = false;
            index = INDEX_DOWNLOAD;
            show();
        }
    }

    /**
     * Best effort: ask the system to exempt the app from Doze/battery savers so a long download
     * keeps running with the screen off. Xiaomi/HyperOS and several OEM builds route this to
     * their own screen, refuse it, or have no such Activity at all; every failure is swallowed
     * and setup continues exactly as before.
     */
    private void requestBatteryExemption() {
        if (askedBattery) return;
        askedBattery = true;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null
                    || power.isIgnoringBatteryOptimizations(getPackageName())) return;
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            // No whitelist screen on this ROM, or the request was denied: nothing to do.
        }
    }

    private void setupStorage() {
        new Thread(() -> OpxRuntime.setupStorage(this), "opx-storage").start();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    /** Back walks the slides instead of dropping the user into an unfinished app. */
    @Override
    public void onBackPressed() {
        if (index > 0) {
            index--;
            show();
            return;
        }
        moveTaskToBack(true);
    }
}
