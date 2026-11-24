package com.opx.oss;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Stryker's first-run flow with the OPX steps: welcome, permissions, runtime download, engine
 * install and ready. Permissions are collected here, once, before any other screen is reachable;
 * the download screen follows the phone's real ABI and resumes Stryker-style after a pause or a
 * killed process.
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
            R.raw.connection,
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshChrome();
            handler.postDelayed(this, 500);
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
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intro);
        background = findViewById(R.id.intro_background);
        next = findViewById(R.id.intro_next);
        skip = findViewById(R.id.intro_skip);

        for (int i = 0; i < SLIDES.length; i++) {
            View slide = findViewById(SLIDES[i]);
            LottieAnimationView anim = slide.findViewById(R.id.slide_anim);
            ((TextView) slide.findViewById(R.id.slide_title)).setText(TITLES[i]);
            ((TextView) slide.findViewById(R.id.slide_body)).setText(BODIES[i]);
            anim.setAnimation(ANIMS[i]);
            configureAction(slide, i);
        }

        skip.setOnClickListener(v -> {
            if (index < INDEX_DOWNLOAD) {
                index = allPermissionsGranted() ? INDEX_DOWNLOAD : INDEX_PERMISSIONS;
                show();
            } else if (index < INDEX_FINAL) {
                index = INDEX_FINAL;
                show();
            }
        });
        next.setOnClickListener(v -> {
            if (index == INDEX_PERMISSIONS && !allPermissionsGranted()) {
                askPermissions();
                return;
            }
            if (index == INDEX_DOWNLOAD && !runtimeReady()) {
                if (RuntimeDownloadManager.snapshot().state == RuntimeDownloadManager.STATE_ERROR) {
                    retryDownload();
                }
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

    private void configureAction(View slide, int slideIndex) {
        MaterialButton action = slide.findViewById(R.id.slide_action);
        if (slideIndex == INDEX_PERMISSIONS) {
            action.setText(R.string.intro_grant);
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(v -> askPermissions());
        } else if (slideIndex == INDEX_DOWNLOAD) {
            action.setText(R.string.intro_retry);
            action.setOnClickListener(v -> retryDownload());
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
                anim.playAnimation();
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
        if (index == INDEX_DOWNLOAD && allPermissionsGranted()) {
            OpxService.beginDownload(this);
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
            next.setEnabled(allPermissionsGranted());
            View slide = findViewById(R.id.slide_permissions);
            MaterialButton action = slide.findViewById(R.id.slide_action);
            action.setVisibility(allPermissionsGranted() ? View.GONE : View.VISIBLE);
        } else if (index == INDEX_DOWNLOAD) {
            RuntimeDownloadManager.Snapshot snapshot = RuntimeDownloadManager.snapshot();
            View slide = findViewById(R.id.slide_download);
            ProgressBar progress = slide.findViewById(R.id.slide_progress);
            TextView status = slide.findViewById(R.id.slide_status);
            MaterialButton action = slide.findViewById(R.id.slide_action);
            progress.setVisibility(View.VISIBLE);
            status.setVisibility(View.VISIBLE);

            progress.setIndeterminate(false);
            if (runtimeReady()) {
                progress.setProgress(100);
                status.setText(R.string.intro_download_ready);
                action.setVisibility(View.GONE);
                next.setEnabled(true);
            } else if (OpxService.getState() == OpxService.STATE_FAILED) {
                progress.setProgress(0);
                String error = OpxService.getError();
                status.setText(getString(R.string.intro_download_error,
                        error == null ? getString(R.string.intro_download_unknown) : error));
                action.setVisibility(View.VISIBLE);
                action.setText(R.string.intro_retry);
                next.setEnabled(false);
            } else if (snapshot.state == RuntimeDownloadManager.STATE_ERROR) {
                progress.setProgress(0);
                status.setText(getString(R.string.intro_download_error, snapshot.error == null
                        ? getString(R.string.intro_download_unknown) : snapshot.error));
                action.setVisibility(View.VISIBLE);
                action.setText(R.string.intro_retry);
                next.setEnabled(false);
            } else if (snapshot.state == RuntimeDownloadManager.STATE_READY) {
                progress.setIndeterminate(true);
                status.setText(R.string.intro_installing);
                action.setVisibility(View.GONE);
                next.setEnabled(false);
            } else if (snapshot.state == RuntimeDownloadManager.STATE_DOWNLOADING) {
                progress.setIndeterminate(false);
                progress.setProgress(snapshot.percent());
                status.setText(getString(R.string.intro_download_progress,
                        snapshot.percent(), snapshot.arch + " · " + snapshot.label));
                action.setVisibility(View.GONE);
                next.setEnabled(false);
            } else {
                progress.setProgress(0);
                status.setText(R.string.intro_download_wait);
                action.setVisibility(View.VISIBLE);
                action.setText(R.string.intro_start_download);
                next.setEnabled(false);
            }
        } else {
            next.setEnabled(true);
        }
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
        if (!allPermissionsGranted()) {
            index = INDEX_PERMISSIONS;
            show();
            return;
        }
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
        requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
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
            // A second denial is final; the system settings page is the only way left.
            View slide = findViewById(R.id.slide_permissions);
            MaterialButton action = slide.findViewById(R.id.slide_action);
            action.setText(R.string.intro_open_settings);
            action.setOnClickListener(v -> openAppSettings());
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
