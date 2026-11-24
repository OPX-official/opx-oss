package com.opx.oss;

import android.Manifest;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The WiFi section, laid out like Stryker's: screen title, status card (interface, network count
 * chip, interface/band/channel meta row), then the network card.
 * <p>
 * Two scan paths, both terminal-free: the headless wifi4 engine over a USB adapter, and
 * Android's built-in radio. Every field comes from wifi4's own model — the security bucket from
 * {@code EncryptionType}, the vendor from {@code ap.identity.manufacturer}, the band from the
 * channels seen — so a row here says the same thing the engine's output says.
 */
public class WifiFragment extends Fragment implements WifiBridge.Listener, OpxService.Listener {

    private static final int REQUEST_LOCATION = 2;

    private final WifiAdapter adapter = new WifiAdapter();
    private final Set<String> bands = new HashSet<>();

    private TextView ifaceValue;
    private TextView statusValue;
    private TextView countChip;
    private TextView ifaceMeta;
    private TextView bandValue;
    private TextView channelValue;
    private TextView usbDiag;
    private TextView scanText;
    private TextView scanSubtext;
    private View emptyCard;
    private View listCard;
    private View tryAgain;
    private LinearProgressIndicator progress;
    private LottieAnimationView scanAnim;
    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private View errorCard;
    private TextView errorToggle;
    private TextView errorMessage;
    private TextView errorDetail;
    private String lastError = "";

    // The target dialog: one card per attack the engine says it can run against this AP.
    private Dialog sheet;
    private WifiAdapter.Network sheetTarget;
    private LinearLayout sheetAttacks;
    private TextView sheetClients;
    private TextView sheetNote;
    private View sheetWpsLocked;
    private JSONObject targets;
    private String targetsReason = "";

    // The running attack's monitor: Stryker's long pop-up, fed by the engine's events.
    private AttackMonitorDialog attackMonitor;

    // The running-attack card.
    private View attackCard;
    private LottieAnimationView attackAnim;
    private TextView attackTitle;
    private TextView attackSub;
    private TextView attackLines;
    private TextView attackResult;
    private MaterialButton attackStop;
    private boolean attackRunning;
    private String attackProgress = "";
    private final StringBuilder attackLog = new StringBuilder();

    // Stryker's expandable action button: Pixie dust, Handshakes, Deauth.
    private View fabMenu;
    private boolean fabOpen;

    private WifiManager wifiManager;
    private BroadcastReceiver scanReceiver;
    private boolean scanning;
    private int adapters;
    private int lastChannel;
    /**
     * wifi4 acquires the USB descriptor on its own poller thread, so the first answer can be
     * empty even with the card plugged in. Ask again while this screen is visible; the engine
     * answers with whatever the poller has acquired by then.
     */
    private final Handler deviceRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable deviceRefresh = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || !isResumed()) return;
            WifiBridge bridge = WifiBridge.get(requireContext());
            if (bridge.isRunning()) bridge.requestDevices();
            deviceRefreshHandler.postDelayed(this, 5000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wifi, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ifaceValue = view.findViewById(R.id.wifi_iface_value);
        statusValue = view.findViewById(R.id.wifi_status_value);
        countChip = view.findViewById(R.id.wifi_count_chip);
        ifaceMeta = view.findViewById(R.id.wifi_iface_meta);
        bandValue = view.findViewById(R.id.wifi_band_value);
        channelValue = view.findViewById(R.id.wifi_channel_value);
        usbDiag = view.findViewById(R.id.wifi_usb_diag);
        scanText = view.findViewById(R.id.scan_text);
        scanSubtext = view.findViewById(R.id.scan_subtext);
        emptyCard = view.findViewById(R.id.wifi_empty_card);
        listCard = view.findViewById(R.id.wifi_list_card);
        tryAgain = view.findViewById(R.id.try_again);
        progress = view.findViewById(R.id.wifi_scan_progress);
        scanAnim = view.findViewById(R.id.wifi_scan_anim);
        refresh = view.findViewById(R.id.refresh);
        list = view.findViewById(R.id.wifilist);
        errorCard = view.findViewById(R.id.wifi_error_card);
        errorToggle = view.findViewById(R.id.wifi_error_toggle);
        errorMessage = view.findViewById(R.id.wifi_error_message);
        errorDetail = view.findViewById(R.id.wifi_error_detail);

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        adapter.setOnNetworkClick(this::openTarget);

        attackCard = view.findViewById(R.id.wifi_attack_card);
        attackAnim = view.findViewById(R.id.wifi_attack_anim);
        attackTitle = view.findViewById(R.id.wifi_attack_title);
        attackSub = view.findViewById(R.id.wifi_attack_sub);
        attackLines = view.findViewById(R.id.wifi_attack_lines);
        attackResult = view.findViewById(R.id.wifi_attack_result);
        attackStop = view.findViewById(R.id.wifi_attack_stop);
        attackStop.setOnClickListener(v -> {
            if (attackRunning) {
                attackStop.setText(R.string.wifi_attack_stopping);
                WifiBridge.get(requireContext()).stopAttack();
            } else {
                attackCard.setVisibility(View.GONE);
            }
        });

        refresh.setOnRefreshListener(() -> {
            refresh.setRefreshing(false);
            scan();
        });

        fabMenu = view.findViewById(R.id.wifi_fab_menu);
        view.findViewById(R.id.wifi_fab).setOnClickListener(v -> toggleFab());
        view.findViewById(R.id.wifi_fab_hs).setOnClickListener(v -> {
            toggleFab();
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).open(R.id.nav_handshakes);
            }
        });
        view.findViewById(R.id.wifi_fab_deauth).setOnClickListener(v -> {
            toggleFab();
            pickTarget("deauth");
        });
        view.findViewById(R.id.wifi_fab_pixie).setOnClickListener(v -> {
            toggleFab();
            pickTarget("wps");
        });

        view.findViewById(R.id.wifi_scan_usb).setOnClickListener(v -> scan());
        view.findViewById(R.id.wifi_scan_builtin).setOnClickListener(v -> scanBuiltInRadio());
        tryAgain.setOnClickListener(v -> scan());
        view.findViewById(R.id.wifi_status_row).setOnClickListener(v -> diagnose());
        view.findViewById(R.id.wifi_usb_diagnose).setOnClickListener(v -> diagnose());
        view.findViewById(R.id.wifi_usb_copy).setOnClickListener(v ->
                copy(usbDiag.getText().toString(), R.string.wifi_usb_copied));
        view.findViewById(R.id.wifi_error_copy).setOnClickListener(v ->
                copy(lastError, R.string.wifi_error_copied));
        view.findViewById(R.id.wifi_error_retry).setOnClickListener(v -> scan());
        errorToggle.setOnClickListener(v -> toggleErrorDetails());

        wifiManager = (WifiManager) requireContext().getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onBuiltInResults();
            }
        };

        usbDiag.setText(R.string.wifi_usb_waiting);
        updateCount();
    }

    @Override
    public void onResume() {
        super.onResume();
        WifiBridge.get(requireContext()).addListener(this);
        OpxService.addListener(this);
        applyEngineState();
        requireContext().registerReceiver(scanReceiver,
                new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        deviceRefreshHandler.removeCallbacks(deviceRefresh);
        deviceRefreshHandler.postDelayed(deviceRefresh, 5000);
    }

    @Override
    public void onPause() {
        super.onPause();
        deviceRefreshHandler.removeCallbacks(deviceRefresh);
        WifiBridge.get(requireContext()).removeListener(this);
        OpxService.removeListener(this);
        try {
            requireContext().unregisterReceiver(scanReceiver);
        } catch (IllegalArgumentException ignored) {
            // Never registered (onPause without onResume).
        }
    }

    // --------------------------------------------------------------- engine

    private void applyEngineState() {
        switch (OpxService.getState()) {
            case OpxService.STATE_READY:
                // The status line reports the runtime, not a scan: "Scanning…" belonged there only
                // while a scan was actually running, so this screen looked busy when it was idle.
                if (!scanning) statusValue.setText(R.string.wifi_state_ready);
                WifiBridge bridge = WifiBridge.get(requireContext());
                bridge.start();
                bridge.requestDevices();
                bridge.requestTargets();
                bridge.requestAttackStatus();
                break;
            case OpxService.STATE_FAILED:
                statusValue.setText(getString(R.string.wifi_usb_error,
                        String.valueOf(OpxService.getError())));
                break;
            case OpxService.STATE_INSTALLING:
                statusValue.setText(R.string.notification_state_installing);
                break;
            case OpxService.STATE_STOPPED:
                statusValue.setText(R.string.engine_stopped);
                break;
            default:
                statusValue.setText(R.string.notification_state_starting);
                break;
        }
    }

    private void scan() {
        if (OpxService.getState() != OpxService.STATE_READY) {
            Snackbar.make(requireView(), R.string.wifi_engine_pending, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (scanning) return;
        if (attackRunning) {
            // Channel hopping for a scan would pull the radio off the campaign's target.
            Snackbar.make(requireView(), R.string.wifi_scan_attack_running,
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        scanning = true;
        adapter.clear();
        bands.clear();
        updateBand();
        updateCount();
        progress.setVisibility(View.VISIBLE);
        scanText.setText(R.string.wifi_scanning);
        scanSubtext.setText(R.string.wifi_usb_scanning);
        tryAgain.setVisibility(View.GONE);
        hideError();
        statusValue.setText(R.string.wifi_usb_scanning);
        WifiBridge bridge = WifiBridge.get(requireContext());
        bridge.start();
        bridge.scan(25);
    }

    /** Stryker-style pop-up: the descriptor identity first, then the full diagnosis on the card. */
    private void diagnose() {
        String text = UsbHostBridge.diagnose(requireContext());
        usbDiag.setText(text);
        new UsbDialog(getString(R.string.cmd_dialog_usb_title), text,
                getString(R.string.wifi_usb_grant), this::scan)
                .show(getParentFragmentManager(), "usb_dialog");
    }

    @Override
    public void onEngineEvent(JSONObject event) {
        String name = event.optString("event");
        if ("network".equals(name)) {
            WifiAdapter.Network network = new WifiAdapter.Network();
            network.hidden = event.optBoolean("hidden");
            network.ssid = event.optString("ssid");
            network.bssid = event.optString("bssid");
            network.privacy = event.optString("privacy", "");
            network.security = event.optString("security", "");
            network.vendor = event.optString("vendor", "");
            network.chipset = event.optString("chipset", "");
            network.band = event.optString("band", "");
            network.wps = event.optBoolean("wps");
            network.channel = event.optInt("channel");
            network.signal = event.optInt("signal");
            network.source = "usb";
            adapter.upsert(network);
            if (!network.band.isEmpty() && !"null".equals(network.band)) {
                bands.add(network.band);
                updateBand();
            }
            if (network.channel > 0) {
                lastChannel = network.channel;
                channelValue.setText(String.valueOf(lastChannel));
            }
            updateCount();
        } else if ("ready".equals(name)) {
            JSONObject runtime = event.optJSONObject("runtime");
            boolean wifi4Ok = runtime == null || runtime.optBoolean("wifi4_ok", true);
            if (!wifi4Ok) {
                // The runtime is up but the engine cannot import wifi4: say so instead of
                // looking like an empty scan (the "engine ready, no adapters" case).
                String error = runtime.optString("wifi4_error", "");
                String message = getString(R.string.wifi4_missing, error);
                statusValue.setText(message);
                usbDiag.setText(message + "\n\n" + usbDiag.getText());
            } else {
                String version = event.optString("version", "");
                statusValue.setText(version.isEmpty()
                        ? getString(R.string.wifi_state_ready)
                        : getString(R.string.wifi_engine_ready_version, version));
            }
        } else if ("scan_done".equals(name)) {
            scanning = false;
            progress.setVisibility(View.GONE);
            if (event.optBoolean("cancelled")) {
                // An attack parks the radio by cancelling the scan; the row list is still valid.
                return;
            }
            int found = event.optInt("count");
            statusValue.setText(getString(R.string.wifi_scan_done_short, found));
            updateCount();
            // The sink now holds the APs with their real AKM/PMF/WPS state: ask for the attacks.
            WifiBridge.get(requireContext()).requestTargets();
        } else if ("devices".equals(name)) {
            JSONArray devices = event.optJSONArray("devices");
            adapters = devices == null ? 0 : devices.length();
            StringBuilder text = new StringBuilder();
            text.append(getString(R.string.wifi_usb_devices, adapters)).append('\n');
            if (devices != null) {
                for (int i = 0; i < devices.length(); i++) {
                    JSONObject device = devices.optJSONObject(i);
                    if (device == null) continue;
                    text.append("- ");
                    String description = device.optString("description", "");
                    if (!description.isEmpty()) text.append(description).append(' ');
                    String chipset = device.optString("chipset", "");
                    if (!chipset.isEmpty() && !"null".equals(chipset)) {
                        text.append('(').append(chipset).append(')');
                    }
                    text.append('\n');
                }
            }
            if (adapters == 0) {
                text.append(getString(R.string.wifi_usb_none));
                // wifi4's USB backend knows why: no permission, no host support, no card.
                String hint = event.optString("hint", "");
                if (!hint.isEmpty() && !"null".equals(hint)) text.append("\n\n").append(hint);
            }
            usbDiag.setText(text.toString());
            ifaceValue.setText(adapters == 0
                    ? getString(R.string.wifi_state_no_adapter) : "wlan0");
        } else if ("usb_attached".equals(name)) {
            // Plugging the card in anywhere in the app lands here: the interface is already up.
            String iface = event.optString("iface", "wlan0");
            ifaceValue.setText(iface);
            ifaceMeta.setText(iface);
            String chipset = event.optString("chipset", "");
            String label = chipset.isEmpty() || "null".equals(chipset) ? iface : chipset;
            statusValue.setText(getString(R.string.wifi_usb_auto_ready, label));
            WifiBridge.get(requireContext()).requestDevices();
        } else if ("usb_waiting".equals(name)) {
            String message = event.optString("message", "");
            if (!message.isEmpty() && !"null".equals(message)) {
                usbDiag.setText(message);
                statusValue.setText(R.string.wifi_state_no_adapter);
            }
        } else if ("usb_detached".equals(name)) {
            ifaceValue.setText(R.string.wifi_state_no_adapter);
            ifaceMeta.setText("—");
            statusValue.setText(R.string.wifi_usb_unplugged);
            // Everything on the list came from that radio; a stale row cannot be attacked.
            adapter.clear();
            bands.clear();
            updateBand();
            targets = null;
            targetsReason = "";
            lastChannel = 0;
            channelValue.setText("—");
            updateCount();
            if (sheet != null) {
                sheet.dismiss();
                sheet = null;
            }
            attackRunning = false;
            if (attackMonitor != null) {
                attackMonitor.dismiss();
                attackMonitor = null;
            }
            if (attackCard != null) attackCard.setVisibility(View.GONE);
        } else if ("status".equals(name)) {
            boolean connected = event.optBoolean("connected");
            String iface = event.optString("iface", "");
            ifaceValue.setText(connected && !iface.isEmpty()
                    ? iface : getString(R.string.wifi_state_no_adapter));
            ifaceMeta.setText(connected && !iface.isEmpty() ? iface : "—");
            String chipset = event.optString("chipset", "");
            if (connected && !chipset.isEmpty() && !"null".equals(chipset)) {
                statusValue.setText(chipset);
            }
            String band = event.optString("band", "");
            if (!band.isEmpty() && !"null".equals(band)) bandValue.setText(band);
        } else if ("targets".equals(name)) {
            JSONArray list = event.optJSONArray("targets");
            targetsReason = event.optString("reason", "");
            JSONObject map = new JSONObject();
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject target = list.optJSONObject(i);
                    if (target == null) continue;
                    String bssid = target.optString("bssid").toLowerCase(Locale.US);
                    if (bssid.isEmpty()) continue;
                    try {
                        map.put(bssid, target);
                    } catch (org.json.JSONException ignored) {
                        // A duplicate BSSID only replaces the older entry.
                    }
                }
            }
            targets = map;
            if (sheet != null && sheet.isShowing()) renderTargetAttacks();
        } else if ("attack_status".equals(name)) {
            if (!event.optBoolean("running")) {
                if (!attackRunning && attackMonitor != null) attackMonitor.dismiss();
                return;
            }
            // The screen was opened while a campaign was already running: show its monitor.
            if (!attackRunning) {
                attackRunning = true;
                showAttackCard(event.optString("kind"), event.optString("ssid"),
                        event.optString("bssid"));
                attackMonitor = new AttackMonitorDialog(requireContext(), event.optString("kind"),
                        event.optString("ssid"), event.optString("bssid"),
                        event.optInt("channel"));
                attackMonitor.show();
                // A monitor opened mid-campaign starts from the engine's own progress snapshot.
                attackMonitor.onEvent(event);
            }
        } else if ("attack_started".equals(name)) {
            attackRunning = true;
            showAttackCard(event.optString("kind"), event.optString("ssid"),
                    event.optString("bssid"));
            if (attackMonitor == null || !attackMonitor.isShowing()) {
                attackMonitor = new AttackMonitorDialog(requireContext(), event.optString("kind"),
                        event.optString("ssid"), event.optString("bssid"),
                        event.optInt("channel"));
                attackMonitor.show();
            }
        } else if ("attack_log".equals(name)) {
            if (!attackRunning) return;
            if (attackMonitor != null) attackMonitor.onEvent(event);
            String line = event.optString("line");
            if (line.isEmpty()) return;
            attackLog.append(line).append('\n');
            String[] parts = attackLog.toString().split("\n");
            if (parts.length > 6) {
                StringBuilder trimmed = new StringBuilder();
                for (int i = parts.length - 6; i < parts.length; i++) trimmed.append(parts[i]).append('\n');
                attackLog.setLength(0);
                attackLog.append(trimmed);
            }
            renderAttackLines();
        } else if ("attack_progress".equals(name)) {
            if (!attackRunning) return;
            if (attackMonitor != null) attackMonitor.onEvent(event);
            JSONArray lines = event.optJSONArray("lines");
            StringBuilder text = new StringBuilder();
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) {
                    String line = lines.optString(i);
                    if (line.isEmpty()) continue;
                    if (text.length() > 0) text.append('\n');
                    text.append(line);
                }
            }
            attackProgress = text.toString();
            renderAttackLines();
        } else if ("attack_done".equals(name)) {
            if (attackMonitor != null) attackMonitor.onEvent(event);
            onAttackDone(event);
        } else if ("error".equals(name)) {
            if (attackMonitor != null && attackMonitor.isShowing()) {
                attackMonitor.fail(event.optString("message"));
            }
            scanning = false;
            progress.setVisibility(View.GONE);
            // The card carries the reason and the traceback; the header only says that the
            // scan failed instead of pushing a raw "TypeError: ..." into the status line.
            showError(event.optString("message"), event.optString("detail"));
            updateCount();
        }
    }

    @Override
    public void onEngineStopped(String reason) {
        scanning = false;
        progress.setVisibility(View.GONE);
        statusValue.setText(getString(R.string.wifi_usb_stopped, reason));
        updateCount();
    }

    // ----------------------------------------------------------- built-in radio

    private void scanBuiltInRadio() {
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            Snackbar.make(requireView(), R.string.wifi_builtin_unavailable, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_LOCATION);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        wifiManager.startScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != REQUEST_LOCATION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanBuiltInRadio();
        }
    }

    private void onBuiltInResults() {
        progress.setVisibility(View.GONE);
        if (wifiManager == null) return;
        List<ScanResult> results;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException e) {
            return;
        }
        if (results == null) return;
        for (ScanResult result : results) {
            WifiAdapter.Network network = new WifiAdapter.Network();
            network.hidden = result.SSID == null || result.SSID.isEmpty();
            network.ssid = result.SSID;
            network.bssid = result.BSSID;
            network.privacy = result.capabilities;
            network.security = builtInSecurity(result.capabilities);
            network.wps = result.capabilities != null && result.capabilities.contains("WPS");
            network.channel = frequencyToChannel(result.frequency);
            network.signal = result.level;
            network.source = "builtin";
            if (network.channel > 0) {
                network.band = network.isFiveGhz() ? "5" : "2.4";
                bands.add(network.band);
                lastChannel = network.channel;
            }
            adapter.upsert(network);
        }
        updateBand();
        channelValue.setText(lastChannel > 0 ? String.valueOf(lastChannel) : "—");
        statusValue.setText(getString(R.string.wifi_scan_done_short, adapter.size()));
        updateCount();
    }

    /** Android's capabilities string mapped to the same buckets wifi4 uses. */
    private static String builtInSecurity(String capabilities) {
        if (capabilities == null || capabilities.isEmpty()) return "OPEN";
        String upper = capabilities.toUpperCase(Locale.US);
        if (upper.contains("WPA3")) {
            return upper.contains("WPA2") || upper.contains("RSN") ? "WPA3_TRANSITION" : "WPA3";
        }
        if (upper.contains("WPA2")) return "WPA2";
        if (upper.contains("WPA")) return "WPA";
        if (upper.contains("WEP")) return "WEP";
        return "OPEN";
    }

    private static int frequencyToChannel(int frequency) {
        if (frequency == 2484) return 14;
        if (frequency >= 2412 && frequency <= 2484) return (frequency - 2412) / 5 + 1;
        if (frequency >= 5000) return (frequency - 5000) / 5;
        return 0;
    }

    private void updateBand() {
        if (bandValue == null) return;
        if (bands.isEmpty()) {
            bandValue.setText("—");
        } else if (bands.size() > 1) {
            bandValue.setText("2.4 / 5");
        } else {
            bandValue.setText(bands.iterator().next());
        }
    }

    private void updateCount() {
        int size = adapter.size();
        countChip.setText(String.valueOf(size));
        emptyCard.setVisibility(size == 0 ? View.VISIBLE : View.GONE);
        listCard.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
        // The radar animation only runs while the empty card is on screen.
        if (scanAnim != null) {
            if (size == 0) scanAnim.playAnimation();
            else scanAnim.pauseAnimation();
        }
        if (size == 0) {
            scanText.setText(scanning ? R.string.wifi_scanning : R.string.wifi_empty_title);
            scanSubtext.setText(scanning ? R.string.wifi_usb_scanning : R.string.wifi_empty_body);
            tryAgain.setVisibility(scanning ? View.GONE : View.VISIBLE);
        }
    }

    private void copy(String text, int messageRes) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS", text));
            Snackbar.make(requireView(), messageRes, Snackbar.LENGTH_SHORT).show();
        }
    }

    // ----------------------------------------------------------- target sheet

    /** Tapping a network opens its attacks; the engine supplies what applies to that AP. */
    private void openTarget(WifiAdapter.Network network) {
        if (network == null || !isAdded()) return;
        WifiBridge.get(requireContext()).requestTargets();
        sheetTarget = network;

        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_wifi_target, null);
        TextView name = content.findViewById(R.id.target_ssid);
        TextView meta = content.findViewById(R.id.target_meta);
        TextView model = content.findViewById(R.id.target_model);
        sheetClients = content.findViewById(R.id.target_clients);
        sheetAttacks = content.findViewById(R.id.target_attacks);
        sheetNote = content.findViewById(R.id.target_note);
        sheetWpsLocked = content.findViewById(R.id.target_wps_locked);

        name.setText(network.ssid == null || network.ssid.isEmpty()
                ? getString(R.string.wifi_hidden_ssid) : network.ssid);
        String security = network.securityLabel();
        if (security == null) security = network.privacy == null ? "" : network.privacy;
        meta.setText(String.format(Locale.US, "%s · CH %d · %s",
                network.bssid == null ? "—" : network.bssid.toUpperCase(Locale.US),
                network.channel, security));
        model.setText(network.sourceLine());
        renderTargetAttacks();

        Dialog dialog = new Dialog(requireContext());
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        content.findViewById(R.id.target_close).setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (sheet == dialog) {
                sheet = null;
                sheetTarget = null;
                sheetAttacks = null;
                sheetNote = null;
                sheetClients = null;
                sheetWpsLocked = null;
            }
        });
        sheet = dialog;
        dialog.show();
    }

    private void renderTargetAttacks() {
        if (sheetAttacks == null || sheetNote == null || sheetTarget == null) return;
        sheetAttacks.removeAllViews();
        JSONObject target = targetFor(sheetTarget.bssid);
        if (target == null) {
            // No target data yet (or a built-in-radio row, which the engine cannot inject on).
            boolean usb = "usb".equals(sheetTarget.source);
            sheetNote.setText(usb ? R.string.wifi_attack_loading : R.string.wifi_attack_usb_only);
            return;
        }

        JSONArray attacks = target.optJSONArray("attacks");
        if (attacks == null || attacks.length() == 0) {
            sheetNote.setText(targetsReason.isEmpty()
                    ? getString(R.string.wifi_attack_none) : targetsReason);
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < attacks.length(); i++) {
            JSONObject attack = attacks.optJSONObject(i);
            if (attack == null) continue;
            if (row == null || i % 2 == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                params.topMargin = dp(10);
                sheetAttacks.addView(row, params);
            }
            addAttackCard(row, attack.optString("kind"), attack.optBoolean("enabled"),
                    attack.optString("reason", ""), i % 2 == 1);
        }
        if (sheetClients != null) {
            sheetClients.setText(getString(R.string.wifi_attack_clients,
                    target.optInt("clients"), target.optInt("handshakes")));
        }
        if (sheetWpsLocked != null) {
            sheetWpsLocked.setVisibility(target.optBoolean("wps_locked") ? View.VISIBLE : View.GONE);
        }
        sheetNote.setText(R.string.wifi_attack_hint);
    }

    private void addAttackCard(LinearLayout row, String kind, boolean enabled, String reason,
                               boolean second) {
        View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_attack_card, row, false);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) card.getLayoutParams();
        if (second) params.leftMargin = dp(6);
        else params.rightMargin = dp(6);
        card.setLayoutParams(params);
        ((TextView) card.findViewById(R.id.attack_title)).setText(attackTitleRes(kind));
        TextView desc = card.findViewById(R.id.attack_desc);
        if (enabled || reason.isEmpty()) {
            desc.setText(attackDescRes(kind));
        } else {
            desc.setText(reason);
        }
        ((ImageView) card.findViewById(R.id.attack_icon)).setImageResource(attackIconRes(kind));
        card.setAlpha(enabled ? 1f : 0.45f);
        card.setOnClickListener(v -> {
            if (enabled) {
                startAttack(kind);
            } else if (!reason.isEmpty()) {
                Snackbar.make(requireView(), reason, Snackbar.LENGTH_SHORT).show();
            }
        });
        row.addView(card);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Stryker's FAB expands its three shortcuts in place instead of pushing a new screen. */
    private void toggleFab() {
        if (fabMenu == null) return;
        fabOpen = !fabOpen;
        if (fabOpen) {
            fabMenu.setVisibility(View.VISIBLE);
            fabMenu.setAlpha(0f);
            fabMenu.setTranslationY(dp(16));
            fabMenu.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            fabMenu.animate().alpha(0f).translationY(dp(16)).setDuration(150)
                    .withEndAction(() -> fabMenu.setVisibility(View.GONE)).start();
        }
    }

    /**
     * The quick actions work on the last scan's targets: the engine says which network actually
     * accepts the attack, so the list only holds targets that will start, never a dead tap.
     */
    private void pickTarget(String kind) {
        WifiBridge.get(requireContext()).requestTargets();
        List<WifiAdapter.Network> matches = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (WifiAdapter.Network network : adapter.networks()) {
            JSONObject target = targetFor(network.bssid);
            if (target == null) continue;
            JSONArray attacks = target.optJSONArray("attacks");
            if (attacks == null) continue;
            for (int i = 0; i < attacks.length(); i++) {
                JSONObject attack = attacks.optJSONObject(i);
                if (attack == null) continue;
                if (!kind.equals(attack.optString("kind"))) continue;
                if (!attack.optBoolean("enabled")) continue;
                String ssid = network.ssid == null || network.ssid.isEmpty()
                        ? getString(R.string.wifi_hidden_ssid) : network.ssid;
                labels.add(String.format(Locale.US, "%s · %s · CH %d", ssid,
                        network.bssid == null ? "—" : network.bssid.toUpperCase(Locale.US),
                        network.channel));
                matches.add(network);
                break;
            }
        }
        if (matches.isEmpty()) {
            Snackbar.make(requireView(), R.string.wifi_pick_empty, Snackbar.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.wifi_pick_title)
                .setItems(labels.toArray(new String[0]),
                        (dialog, which) -> openTarget(matches.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startAttack(String kind) {
        WifiAdapter.Network target = sheetTarget;
        if (target == null) return;
        if (OpxService.getState() != OpxService.STATE_READY) {
            Snackbar.make(requireView(), R.string.wifi_engine_pending, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (sheet != null) sheet.dismiss();
        WifiBridge bridge = WifiBridge.get(requireContext());
        bridge.start();
        // Show Stryker's monitor straight away; the engine's attack_started fills it in.
        attackMonitor = new AttackMonitorDialog(requireContext(), kind, target.ssid,
                target.bssid, target.channel);
        attackMonitor.show();
        bridge.attack(kind, target.bssid);
    }

    private JSONObject targetFor(String bssid) {
        if (targets == null || bssid == null || bssid.isEmpty()) return null;
        return targets.optJSONObject(bssid.toLowerCase(Locale.US));
    }

    // ------------------------------------------------------- running attack

    @Override
    public void onDestroyView() {
        if (attackMonitor != null) attackMonitor.dismiss();
        attackMonitor = null;
        if (sheet != null) sheet.dismiss();
        sheet = null;
        super.onDestroyView();
    }

    private void showAttackCard(String kind, String ssid, String bssid) {
        if (attackCard == null) return;
        attackProgress = "";
        attackLog.setLength(0);
        attackTitle.setText(attackTitleRes(kind));
        String name = ssid == null || ssid.isEmpty() ? getString(R.string.wifi_hidden_ssid) : ssid;
        attackSub.setText(String.format("%s · %s", name, bssid));
        attackLines.setVisibility(View.GONE);
        attackResult.setVisibility(View.GONE);
        attackStop.setText(R.string.wifi_attack_stop);
        attackStop.setTextColor(ContextCompat.getColor(requireContext(), R.color.red));
        if (attackAnim != null) {
            attackAnim.setRepeatCount(-1);
            attackAnim.setAnimation(attackAnimRes(kind));
            attackAnim.playAnimation();
        }
        attackCard.setVisibility(View.VISIBLE);
    }

    private void renderAttackLines() {
        if (attackLines == null) return;
        String text = attackProgress;
        if (attackLog.length() > 0) text = text.isEmpty() ? attackLog.toString() : text + "\n" + attackLog;
        text = text.trim();
        if (text.isEmpty()) {
            attackLines.setVisibility(View.GONE);
            return;
        }
        attackLines.setText(text);
        attackLines.setVisibility(View.VISIBLE);
    }

    private void onAttackDone(JSONObject event) {
        attackRunning = false;
        boolean ok = event.optBoolean("ok");
        String summary = event.optString("summary", "");
        String detail = event.optString("detail", "");
        if (attackAnim != null) attackAnim.pauseAnimation();
        if (attackStop != null) {
            attackStop.setText(R.string.wifi_attack_dismiss);
            attackStop.setTextColor(ContextCompat.getColor(requireContext(), R.color.stryker_accent));
        }
        if (attackResult == null) return;
        String text = getString(ok ? R.string.wifi_attack_done_ok : R.string.wifi_attack_done_fail,
                summary);
        if (event.optBoolean("stopped")) text = text + " · " + getString(R.string.wifi_attack_stopped);
        if (!detail.isEmpty() && !"null".equals(detail)) text = text + "\n" + detail;
        attackResult.setText(text);
        attackResult.setTextColor(ContextCompat.getColor(requireContext(),
                ok ? R.color.green : R.color.red));
        attackResult.setVisibility(View.VISIBLE);
        // The engine holds the captures; the sheet's counts come back fresh from it.
        WifiBridge.get(requireContext()).requestTargets();
    }

    static int attackTitleRes(String kind) {
        switch (kind) {
            case "pmkid":
                return R.string.wifi_attack_pmkid;
            case "wps":
                return R.string.wifi_attack_wps;
            case "wep":
                return R.string.wifi_attack_wep;
            case "pbc":
                return R.string.wifi_attack_pbc;
            default:
                return R.string.wifi_attack_deauth;
        }
    }

    static int attackDescRes(String kind) {
        switch (kind) {
            case "pmkid":
                return R.string.wifi_attack_pmkid_desc;
            case "wps":
                return R.string.wifi_attack_wps_desc;
            case "wep":
                return R.string.wifi_attack_wep_desc;
            case "pbc":
                return R.string.wifi_attack_pbc_desc;
            default:
                return R.string.wifi_attack_deauth_desc;
        }
    }

    static int attackIconRes(String kind) {
        switch (kind) {
            case "pmkid":
                return R.drawable.key;
            case "wps":
                return R.drawable.fiber_pin;
            case "wep":
                return R.drawable.autopixie;
            case "pbc":
                return R.drawable.fiber_pin;
            default:
                return R.drawable.deauth;
        }
    }

    static int attackAnimRes(String kind) {
        switch (kind) {
            case "pmkid":
                return R.raw.brutehydra;
            case "wps":
                return R.raw.meteor;
            case "wep":
                return R.raw.atom2;
            case "pbc":
                return R.raw.meteor;
            default:
                return R.raw.brute;
        }
    }

    // ------------------------------------------------------------- engine error

    /**
     * A failed scan gets its own card: the one-line reason, the traceback the engine sends in
     * the error event, and a copy button — so a report does not have to start with a screenshot
     * of the Logs screen.
     */
    private void showError(String message, String detail) {
        if (message == null) message = "";
        if (detail == null || "null".equals(detail)) detail = "";
        lastError = detail.isEmpty() ? message : message + "\n\n" + detail;
        errorMessage.setText(message.isEmpty() ? getString(R.string.wifi_state_failed) : message);
        errorDetail.setText(detail);
        errorDetail.setVisibility(View.GONE);
        errorToggle.setText(R.string.wifi_error_show);
        errorToggle.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        errorCard.setVisibility(View.VISIBLE);
        statusValue.setText(R.string.wifi_state_failed);
    }

    private void toggleErrorDetails() {
        boolean shown = errorDetail.getVisibility() == View.VISIBLE;
        errorDetail.setVisibility(shown ? View.GONE : View.VISIBLE);
        errorToggle.setText(shown ? R.string.wifi_error_show : R.string.wifi_error_hide);
    }

    private void hideError() {
        if (errorCard != null) errorCard.setVisibility(View.GONE);
    }

    // --------------------------------------------------------- OpxService.Listener

    @Override
    public void onEngineStateChanged() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(this::applyEngineState);
    }
}
