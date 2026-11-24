package com.opx.oss;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

/**
 * The Settings screen. It answers the three questions a bug report starts with — what is
 * installed, what state the engine is in, and how much it logged — and puts the two fixes next
 * to them: restart the engine process, and grant the storage the runtime's {@code ~/storage}
 * links need.
 * <p>
 * The gear in the toolbar used to open the Logs screen; it opens this instead, and the drawer
 * carries a Settings row like Stryker's.
 */
public class SettingsFragment extends Fragment implements OpxService.Listener {

    /** wifi4 scanner preferences the app exposes; the engine reads DFS before it hops. */
    static final String PREFS = "opx_settings";
    static final String PREF_DFS = "dfs_channels";

    private TextView prefixValue;
    private TextView archValue;
    private TextView stateValue;
    private TextView versionValue;
    private TextView engineError;
    private TextView storageValue;
    private TextView logLevelValue;
    private MaterialSwitch dfsSwitch;
    private boolean bindingDfs;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefixValue = view.findViewById(R.id.settings_prefix_value);
        archValue = view.findViewById(R.id.settings_arch_value);
        stateValue = view.findViewById(R.id.settings_state_value);
        versionValue = view.findViewById(R.id.settings_version_value);
        engineError = view.findViewById(R.id.settings_engine_error);
        storageValue = view.findViewById(R.id.settings_storage_value);
        logLevelValue = view.findViewById(R.id.settings_log_level_value);
        dfsSwitch = view.findViewById(R.id.settings_dfs_switch);

        view.findViewById(R.id.settings_restart_row).setOnClickListener(v -> restartEngine());
        view.findViewById(R.id.settings_storage_row).setOnClickListener(v -> repairStorage());
        view.findViewById(R.id.settings_log_level_row).setOnClickListener(v -> chooseLogLevel());
        view.findViewById(R.id.settings_copy_row).setOnClickListener(v -> copyDiagnosis());
        dfsSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (bindingDfs) return;
            SharedPreferences prefs = requireContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(PREF_DFS, checked).apply();
            WifiBridge.get(requireContext()).setDfs(checked);
        });

        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        OpxService.addListener(this);
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        OpxService.removeListener(this);
    }

    // ------------------------------------------------------------------ state

    private void refresh() {
        if (!isAdded() || prefixValue == null) return;
        Context context = requireContext();
        int state = OpxService.getState();

        prefixValue.setText(OpxRuntime.PREFIX_GUEST);
        archValue.setText(archText(context));
        stateValue.setText(stateText(state));
        versionValue.setText("v" + versionName(context));

        String error = OpxService.getError();
        if (error == null || error.isEmpty()) {
            engineError.setVisibility(View.GONE);
        } else {
            engineError.setText(error);
            engineError.setVisibility(View.VISIBLE);
        }

        storageValue.setText(OpxRuntime.hasStoragePermission(context)
                ? R.string.settings_storage_granted : R.string.settings_storage_missing);
        logLevelValue.setText(levelLabel());
        boolean dfs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_DFS, false);
        bindingDfs = true;
        dfsSwitch.setChecked(dfs);
        bindingDfs = false;
    }

    private String stateText(int state) {
        switch (state) {
            case OpxService.STATE_READY:
                return getString(R.string.notification_state_ready);
            case OpxService.STATE_FAILED:
                return getString(R.string.notification_state_failed);
            case OpxService.STATE_INSTALLING:
                return getString(R.string.notification_state_installing);
            case OpxService.STATE_STOPPED:
                return getString(R.string.engine_stopped);
            default:
                return getString(R.string.notification_state_starting);
        }
    }

    private String levelLabel() {
        switch (AppLog.level()) {
            case AppLog.LEVEL_ERROR:
                return getString(R.string.settings_log_errors);
            case AppLog.LEVEL_INFO:
                return getString(R.string.settings_log_normal);
            default:
                return getString(R.string.settings_log_verbose);
        }
    }

    private static String archText(Context context) {
        try {
            return OpxRuntime.arch(context);
        } catch (RuntimeException e) {
            return "—";
        }
    }

    private static String versionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "?";
        }
    }

    // ---------------------------------------------------------------- actions

    /** Stops the engine process and re-runs the runtime install/probe pass. */
    private void restartEngine() {
        Context context = requireContext();
        WifiBridge.get(context).stop();
        OpxService.restart(context);
        Snackbar.make(requireView(), R.string.settings_restarted, Snackbar.LENGTH_SHORT).show();
        refresh();
    }

    /** Rebuilds the {@code ~/storage} links; a missing grant is fixed in system settings. */
    private void repairStorage() {
        Context context = requireContext();
        if (!OpxRuntime.hasStoragePermission(context)) {
            Snackbar.make(requireView(), R.string.settings_storage_denied,
                    Snackbar.LENGTH_LONG)
                    .setAction(R.string.settings_open, v -> openAppSettings())
                    .show();
            return;
        }
        new Thread(() -> {
            OpxRuntime.setupStorage(context);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                Snackbar.make(requireView(), R.string.settings_storage_ready,
                        Snackbar.LENGTH_SHORT).show();
                refresh();
            });
        }, "opx-storage").start();
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + requireContext().getPackageName())));
    }

    private void chooseLogLevel() {
        final int[] levels = {AppLog.LEVEL_ERROR, AppLog.LEVEL_INFO, AppLog.LEVEL_VERBOSE};
        String[] labels = {
                getString(R.string.settings_log_errors),
                getString(R.string.settings_log_normal),
                getString(R.string.settings_log_verbose),
        };
        int checked = 0;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] == AppLog.level()) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_log_level)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppLog.setLevel(requireContext(), levels[which]);
                    dialog.dismiss();
                    refresh();
                })
                .show();
    }

    private void copyDiagnosis() {
        Context context = requireContext();
        StringBuilder text = new StringBuilder();
        text.append("OPX OSS ").append(versionName(context)).append('\n');
        int state = OpxService.getState();
        text.append("state: ").append(stateText(state)).append('\n');
        text.append("arch: ").append(archText(context)).append('\n');
        text.append("installed: ").append(OpxRuntime.isInstalled(context)).append('\n');
        text.append("prefix: ").append(OpxRuntime.PREFIX_GUEST).append('\n');
        text.append("storage: ").append(getString(OpxRuntime.hasStoragePermission(context)
                ? R.string.settings_storage_granted : R.string.settings_storage_missing)).append('\n');
        text.append("log level: ").append(levelLabel()).append('\n');
        String error = OpxService.getError();
        if (error != null && !error.isEmpty()) {
            text.append("engine error: ").append(error).append('\n');
        }

        String log = AppLog.snapshot();
        if (!log.isEmpty()) {
            String[] lines = log.split("\n");
            text.append("\n-- last log --\n");
            for (int i = Math.max(0, lines.length - 40); i < lines.length; i++) {
                text.append(lines[i]).append('\n');
            }
        }

        ClipboardManager clipboard = (ClipboardManager) context
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS diagnosis", text.toString()));
            Snackbar.make(requireView(), R.string.settings_copied, Snackbar.LENGTH_SHORT).show();
        }
    }

    // --------------------------------------------------------- OpxService.Listener

    @Override
    public void onEngineStateChanged() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(this::refresh);
    }
}
