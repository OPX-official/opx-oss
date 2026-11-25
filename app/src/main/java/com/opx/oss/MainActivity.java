package com.opx.oss;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

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

    private DrawerLayout drawer;
    private int currentItem = -1;
    private TextView runtimeStatus;

    private View rowHome;
    private View rowLogs;
    private View rowWifi;
    private View rowHandshakes;
    private View rowCredentials;
    private View rowAbout;
    private View rowSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        drawer = findViewById(R.id.drawer_layout);
        runtimeStatus = findViewById(R.id.drawer_runtime_status);

        findViewById(R.id.menu_img).setOnClickListener(v -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.settings_icon).setOnClickListener(v -> open(R.id.nav_settings));
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
            // The runtime that is actually installed (32-bit phones show arm, 64-bit aarch64).
            arch.setText(OpxRuntime.arch(MainActivity.this));
        } catch (RuntimeException ignored) {
            arch.setText("—");
        }

        rowHome = findViewById(R.id.dashboard_item);
        rowLogs = findViewById(R.id.logs_item);
        rowWifi = findViewById(R.id.wifi_item);
        rowHandshakes = findViewById(R.id.handshakes_item);
        rowCredentials = findViewById(R.id.credentials_item);
        rowAbout = findViewById(R.id.about_item);
        rowSettings = findViewById(R.id.settings_item);
        decorate(rowHome, R.drawable.home, R.string.nav_home);
        decorate(rowLogs, R.drawable.terminal, R.string.nav_logs);
        decorate(rowWifi, R.drawable.wifi, R.string.nav_wifi);
        decorate(rowHandshakes, R.drawable.handshake_interface, R.string.nav_handshakes);
        decorate(rowCredentials, R.drawable.key, R.string.nav_credentials);
        decorate(rowAbout, R.drawable.info_outlined, R.string.nav_about);
        decorate(rowSettings, R.drawable.settings, R.string.nav_settings);

        rowHome.setOnClickListener(v -> open(R.id.nav_home));
        rowLogs.setOnClickListener(v -> open(R.id.nav_logs));
        rowWifi.setOnClickListener(v -> open(R.id.nav_wifi));
        rowHandshakes.setOnClickListener(v -> open(R.id.nav_handshakes));
        rowCredentials.setOnClickListener(v -> open(R.id.nav_credentials));
        rowAbout.setOnClickListener(v -> open(R.id.nav_about));
        rowSettings.setOnClickListener(v -> open(R.id.nav_settings));

        // The engine ships inside the APK, so the service starts on every launch: there is no
        // runtime to download and no intro gate to wait behind.
        OpxService.start(this);

        if (savedInstanceState == null) open(R.id.nav_home);
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
        // Registered even when the service does not exist yet: the state is process-wide, so the
        // drawer follows the engine to "ready" exactly like the notification does.
        OpxService.addListener(this);
        // Back from the intro (or any other screen): if an adapter is plugged in without its
        // permission, this is where the prompt appears — no WiFi screen needed.
        UsbAutoConnect.resume(this);
        applyRuntimeState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        OpxService.removeListener(this);
    }

    private void applyRuntimeState() {
        if (runtimeStatus == null) return;
        int state = OpxService.getState();
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
            case OpxService.STATE_STOPPED:
                runtimeStatus.setText(R.string.engine_stopped);
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
        } else if (itemId == R.id.nav_handshakes) {
            target = new HandshakesFragment();
        } else if (itemId == R.id.nav_credentials) {
            target = new CredentialsFragment();
        } else if (itemId == R.id.nav_settings) {
            target = new SettingsFragment();
        } else {
            target = new HomeFragment();
        }
        // Stryker's screen transition: fade + settle, both directions.
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(R.anim.nav_fade_enter, R.anim.nav_fade_exit,
                        R.anim.nav_fade_enter, R.anim.nav_fade_exit)
                .replace(R.id.content_frame, target)
                .commit();

        rowHome.setActivated(itemId == R.id.nav_home);
        rowLogs.setActivated(itemId == R.id.nav_logs);
        rowWifi.setActivated(itemId == R.id.nav_wifi);
        rowHandshakes.setActivated(itemId == R.id.nav_handshakes);
        rowCredentials.setActivated(itemId == R.id.nav_credentials);
        rowAbout.setActivated(itemId == R.id.nav_about);
        rowSettings.setActivated(itemId == R.id.nav_settings);
        drawer.closeDrawer(GravityCompat.START);
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
}
