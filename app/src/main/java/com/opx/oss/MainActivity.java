package com.opx.oss;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

/**
 * Single-activity host, laid out like Stryker's shell: a light toolbar with the brand and a menu
 * button, and a custom drawer (logo, runtime status card, sectioned rows) instead of a
 * NavigationView.
 */
public class MainActivity extends AppCompatActivity implements OpxService.Listener {

    private static final int REQUEST_STORAGE = 3;

    private DrawerLayout drawer;
    private int currentItem = -1;
    private TextView runtimeStatus;

    private View rowHome;
    private View rowLogs;
    private View rowWifi;
    private View rowAbout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        drawer = findViewById(R.id.drawer_layout);
        runtimeStatus = findViewById(R.id.drawer_runtime_status);

        findViewById(R.id.menu_img).setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.settings_icon).setOnClickListener(v -> open(R.id.nav_logs));
        findViewById(R.id.menu_close_drawer).setOnClickListener(v -> drawer.closeDrawer(GravityCompat.START));

        TextView version = findViewById(R.id.drawer_version);
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            version.setText("v" + name);
        } catch (Exception ignored) {
            version.setText(R.string.about_version_unknown);
        }
        TextView arch = findViewById(R.id.drawer_arch_badge);
        try {
            arch.setText(OpxRuntime.termuxArch());
        } catch (RuntimeException ignored) {
            arch.setText("—");
        }

        rowHome = findViewById(R.id.dashboard_item);
        rowLogs = findViewById(R.id.logs_item);
        rowWifi = findViewById(R.id.wifi_item);
        rowAbout = findViewById(R.id.about_item);
        decorate(rowHome, R.drawable.home, R.string.nav_home);
        decorate(rowLogs, R.drawable.terminal, R.string.nav_logs);
        decorate(rowWifi, R.drawable.wifi, R.string.nav_wifi);
        decorate(rowAbout, R.drawable.info_outlined, R.string.nav_about);

        rowHome.setOnClickListener(v -> open(R.id.nav_home));
        rowLogs.setOnClickListener(v -> open(R.id.nav_logs));
        rowWifi.setOnClickListener(v -> open(R.id.nav_wifi));
        rowAbout.setOnClickListener(v -> open(R.id.nav_about));

        // The engine starts with the app, not with a screen: the runtime is installed and kept
        // warm so the USB bridge and the WiFi engine are usable from any tab.
        OpxService.start(this);
        requestStorageOnce();

        if (savedInstanceState == null) {
            open(R.id.nav_home);
        }
    }

    private void decorate(View row, int iconRes, int titleRes) {
        ImageView icon = row.findViewById(R.id.row_icon);
        TextView title = row.findViewById(R.id.row_title);
        icon.setImageResource(iconRes);
        title.setText(titleRes);
    }

    @Override
    protected void onResume() {
        super.onResume();
        OpxService service = OpxService.get();
        if (service != null) service.setListener(this);
        applyRuntimeState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        OpxService service = OpxService.get();
        if (service != null) service.clearListener(this);
    }

    private void applyRuntimeState() {
        if (runtimeStatus == null) return;
        OpxService service = OpxService.get();
        int state = service == null ? OpxService.STATE_STARTING : service.getState();
        switch (state) {
            case OpxService.STATE_READY:
                runtimeStatus.setText(R.string.notification_state_ready);
                break;
            case OpxService.STATE_FAILED:
                runtimeStatus.setText(R.string.notification_state_failed);
                break;
            case OpxService.STATE_INSTALLING:
                runtimeStatus.setText(R.string.notification_state_installing);
                break;
            default:
                runtimeStatus.setText(R.string.notification_state_starting);
                break;
        }
    }

    /** Opens one destination; also used by the Home tiles. */
    void open(int itemId) {
        if (itemId == currentItem) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        currentItem = itemId;
        Fragment target;
        if (itemId == R.id.nav_logs) {
            target = new LogsFragment();
        } else if (itemId == R.id.nav_about) {
            target = new AboutFragment();
        } else if (itemId == R.id.nav_wifi) {
            target = new WifiFragment();
        } else {
            target = new HomeFragment();
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, target)
                .commit();

        rowHome.setActivated(itemId == R.id.nav_home);
        rowLogs.setActivated(itemId == R.id.nav_logs);
        rowWifi.setActivated(itemId == R.id.nav_wifi);
        rowAbout.setActivated(itemId == R.id.nav_about);
        drawer.closeDrawer(GravityCompat.START);
    }

    /** targetSdk 28: storage is an ordinary runtime permission, needed for {@code ~/storage}. */
    private void requestStorageOnce() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;
        if (OpxRuntime.hasStoragePermission(this)) return;
        requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) return;
        if (OpxRuntime.hasStoragePermission(this)) {
            new Thread(() -> OpxRuntime.setupStorage(this), "opx-storage").start();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
    }

    // --------------------------------------------------------- OpxService.Listener

    @Override
    public void onEngineStateChanged() {
        runOnUiThread(this::applyRuntimeState);
    }

    @Override
    public void onEngineExited() {
        runOnUiThread(() -> runtimeStatus.setText(R.string.engine_stopped));
    }
}
