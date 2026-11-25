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
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.provider.Settings;
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
    // The interfaces wifi4 holds: the Evil Twin sheet's host/punter selects. Each entry carries
    // the name, label, host/spoofable flags, MAC and supported channels wifi4 reported.
    private JSONArray targetInterfaces = new JSONArray();
    private String connectedIface = "";
    private WifiAdapter.Network sheetTarget;
    private LinearLayout sheetAttacks;
    private TextView sheetClients;
    private TextView sheetNote;
    private View sheetWpsLocked;
    private JSONObject targets;
    private String targetsReason = "";

    // The running attack's monitor: Stryker's long pop-up, fed by the engine's events.
    private AttackMonitorDialog attackMonitor;
    private CaptivePortalDialog captiveDialog;
    private EvilTwinOptionsDialog evilTwinDialog;

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
            if (adapters == 0) {
                ifaceValue.setText(R.string.wifi_state_no_adapter);
                ifaceMeta.setText("—");
            } else {
                // Once the engine opens a chip it names the interface ("wifi4"); until then the
                // placeholder stands in, and the INTERFACE cell gets the same name as the header.
                String live = connectedIface.isEmpty() ? "wlan0" : connectedIface;
                ifaceValue.setText(live);
                ifaceMeta.setText(live);
            }
        } else if ("usb_attached".equals(name)) {
            // Plugging the card in anywhere in the app lands here: the interface is already up.
            String iface = event.optString("iface", "wlan0");
            connectedIface = iface;
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
            if (captiveDialog != null) {
                captiveDialog.dismiss();
                captiveDialog = null;
            }
            if (attackCard != null) attackCard.setVisibility(View.GONE);
        } else if ("status".equals(name)) {
            boolean connected = event.optBoolean("connected");
            String iface = event.optString("iface", "");
            if (connected && !iface.isEmpty()) connectedIface = iface;
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
            JSONArray interfaces = event.optJSONArray("interface_info");
            if (interfaces == null) interfaces = event.optJSONArray("interfaces");
            targetInterfaces = new JSONArray();
            if (interfaces != null) {
                for (int i = 0; i < interfaces.length(); i++) {
                    JSONObject info = interfaces.optJSONObject(i);
                    if (info == null) {
                        // Older builds sent bare names; keep the sheet working with either.
                        String ifaceName = interfaces.optString(i, "");
                        if (ifaceName.isEmpty()) continue;
                        info = new JSONObject();
                        try {
                            info.put("name", ifaceName).put("label", ifaceName)
                                    .put("host", true).put("spoofable", false).put("mac", "");
                        } catch (org.json.JSONException ignored) {
                            continue;
                        }
                    }
                    String ifaceName = info.optString("name", "");
                    if (!ifaceName.isEmpty()) targetInterfaces.put(info);
                }
            }
            if (sheet != null && sheet.isShowing()) renderTargetAttacks();
        } else if ("deauth_sent".equals(name)) {
            int sent = event.optInt("sent");
            int acked = event.optInt("acked");
            String text = getString(R.string.wifi_deauth_sent, sent,
                    acked > 0 ? getString(R.string.wifi_deauth_acked, acked) : "");
            Snackbar.make(requireView(), text, Snackbar.LENGTH_SHORT).show();
        } else if ("silenced".equals(name)) {
            Snackbar.make(requireView(), event.optBoolean("silenced")
                    ? R.string.wifi_target_silenced : R.string.wifi_target_unsilence,
                    Snackbar.LENGTH_SHORT).show();
        } else if ("chop".equals(name)) {
            if (attackMonitor != null) attackMonitor.onEvent(event);
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
            if (captiveDialog != null) captiveDialog.dismiss();
            onAttackDone(event);
        } else if ("psk_verified".equals(name)) {
            boolean ok = event.optBoolean("ok");
            String message = event.optString("message", "");
            if (captiveDialog != null) {
                captiveDialog.result(ok, ok
                        ? getString(R.string.wifi_captive_match)
                        : (message.isEmpty() ? getString(R.string.wifi_captive_mismatch) : message));
            }
            // A match means the portal has nothing left to ask: take it down with the campaign.
            if (ok) WifiBridge.get(requireContext()).stopAttack();
            Snackbar.make(requireView(), ok ? R.string.wifi_captive_match
                    : R.string.wifi_captive_mismatch, Snackbar.LENGTH_LONG).show();
        } else if ("error".equals(name)) {
            if (attackMonitor != null && attackMonitor.isShowing()) {
                attackMonitor.fail(event.optString("message"));
            }
            if (captiveDialog != null) {
                captiveDialog.dismiss();
                captiveDialog = null;
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
            // First-run setup owns the request. A later denial is fixed in system settings,
            // never with a surprise dialog on this screen.
            Snackbar.make(requireView(), R.string.wifi_location_missing, Snackbar.LENGTH_LONG)
                    .setAction(R.string.settings_open, v -> openAppSettings())
                    .show();
            return;
        }
        progress.setVisibility(View.VISIBLE);
        wifiManager.startScan();
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + requireContext().getPackageName())));
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
            // wifi4's row arrived as a network event and no target row covers it yet: the row
            // already carries privacy/hidden/WPS, so offer the campaigns it allows instead of
            // leaving the sheet saying "reading the target".
            target = targetFromNetwork(sheetTarget);
        }

        JSONArray attacks = target.optJSONArray("attacks");
        if (attacks == null || attacks.length() == 0) {
            sheetNote.setText(targetsReason.isEmpty()
                    ? getString(R.string.wifi_attack_none) : targetsReason);
            return;
        }
        renderClientRows(target);
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
            addAttackCard(row, attack.optString("kind"), attack.optString("label", ""),
                    attack.optBoolean("enabled"), attack.optString("reason", ""), i % 2 == 1);
        }
        addSilenceToggle(target);
        if (sheetClients != null) {
            sheetClients.setText(getString(R.string.wifi_attack_clients,
                    target.optInt("clients"), target.optInt("handshakes")));
        }
        if (sheetWpsLocked != null) {
            sheetWpsLocked.setVisibility(target.optBoolean("wps_locked") ? View.VISIBLE : View.GONE);
        }
        sheetNote.setText(R.string.wifi_attack_hint);
    }

    /** The attacks a network row itself allows, used when the engine's target row is missing. */
    private JSONObject targetFromNetwork(WifiAdapter.Network network) {
        JSONArray attacks = new JSONArray();
        attacks.put(attackRow("deauth", true, ""));
        if (network.hidden) attacks.put(attackRow("decloak", true, ""));
        String privacy = network.privacy == null ? "" : network.privacy;
        if (privacy.startsWith("WEP")) {
            attacks.put(attackRow("wep", true, ""));
        } else if (privacy.startsWith("WPA2") || privacy.startsWith("WPA3")) {
            attacks.put(attackRow("pmkid", true, ""));
        }
        if (network.wps) attacks.put(attackRow("wps", true, ""));
        JSONObject target = new JSONObject();
        try {
            target.put("bssid", network.bssid).put("ssid", network.ssid)
                    .put("privacy", privacy).put("hidden", network.hidden)
                    .put("wps", network.wps).put("attacks", attacks);
        } catch (org.json.JSONException ignored) {
            // Plain values.
        }
        return target;
    }

    private static JSONObject attackRow(String kind, boolean enabled, String reason) {
        JSONObject row = new JSONObject();
        try {
            row.put("kind", kind).put("enabled", enabled).put("reason", reason);
        } catch (org.json.JSONException ignored) {
            // Three plain values.
        }
        return row;
    }

    /** The TUI's clients panel: one de-auth per client, plus its broadcast one-shot. */
    private void renderClientRows(JSONObject target) {
        if (sheetAttacks == null || sheetTarget == null) return;
        JSONArray macs = target.optJSONArray("clients_list");
        TextView title = new TextView(requireContext());
        title.setText(R.string.wifi_clients_title);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey));
        title.setTextSize(11);
        title.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(12);
        sheetAttacks.addView(title, titleParams);

        if (macs == null || macs.length() == 0) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.wifi_clients_empty);
            empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey));
            empty.setTextSize(12);
            sheetAttacks.addView(empty);
            return;
        }
        for (int i = 0; i < macs.length(); i++) {
            String mac = macs.optString(i, "");
            if (mac.isEmpty()) continue;
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(4);
            TextView value = new TextView(requireContext());
            value.setText(mac);
            value.setTextColor(ContextCompat.getColor(requireContext(), R.color.night_contrast_2));
            value.setTextSize(12);
            value.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.addView(value, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final String bssid = sheetTarget.bssid;
            final String client = mac;
            row.addView(smallButton(R.string.wifi_client_deauth,
                    v -> WifiBridge.get(requireContext()).deauthClient(bssid, client)));
            sheetAttacks.addView(row, rowParams);
        }
        final String bssid = sheetTarget.bssid;
        sheetAttacks.addView(smallButton(R.string.wifi_clients_deauth_all,
                v -> WifiBridge.get(requireContext()).deauthAll(bssid)));
    }

    /** wifi4's silenced list, the TUI's 's': campaigns off and captures ignored for this AP. */
    private void addSilenceToggle(JSONObject target) {
        if (sheetAttacks == null || sheetTarget == null) return;
        boolean silenced = target.optBoolean("silenced");
        final String bssid = sheetTarget.bssid;
        MaterialButton button = smallButton(
                silenced ? R.string.wifi_target_unsilence : R.string.wifi_target_silence, null);
        button.setOnClickListener(v -> WifiBridge.get(requireContext())
                .setSilenced(bssid, !silenced));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        sheetAttacks.addView(button, params);
    }

    private MaterialButton smallButton(int text, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext());
        button.setText(text);
        button.setTextSize(12);
        button.setTextColor(ContextCompat.getColor(requireContext(), R.color.stryker_accent));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setCornerRadius(dp(12));
        if (listener != null) button.setOnClickListener(listener);
        return button;
    }

    private void addAttackCard(LinearLayout row, String kind, String label, boolean enabled,
                               String reason, boolean second) {
        View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_attack_card, row, false);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) card.getLayoutParams();
        if (second) params.leftMargin = dp(6);
        else params.rightMargin = dp(6);
        card.setLayoutParams(params);
        ((TextView) card.findViewById(R.id.attack_title)).setText(
                label != null && !label.isEmpty() ? label : getString(attackTitleRes(kind)));
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
        if ("eviltwin".equals(kind)) {
            // The TUI's Evil Twin modal: host/punter interfaces, channel, BSSID, punt methods,
            // cycle — all read off wifi4's own member list and the target's PMF posture.
            JSONArray interfaces = targetInterfaces;
            if (interfaces.length() == 0 && !connectedIface.isEmpty()) {
                JSONObject info = new JSONObject();
                try {
                    info.put("name", connectedIface).put("label", connectedIface)
                            .put("host", true).put("spoofable", false).put("mac", "");
                } catch (org.json.JSONException ignored) {
                    // Four plain values.
                }
                interfaces.put(info);
            }
            JSONObject row = targetFor(target.bssid);
            String pmf = row == null ? "" : row.optString("pmf", "");
            evilTwinDialog = new EvilTwinOptionsDialog(requireContext(), target.ssid,
                    target.bssid, target.channel, pmf, interfaces,
                    options -> launchAttack(kind, target, options));
            evilTwinDialog.show();
            return;
        }
        if ("verify".equals(kind)) {
            // The TUI's Verify: the saved credential for this BSSID, checked against the live AP.
            JSONObject data = targetFor(target.bssid);
            JSONObject credential = data == null ? null : data.optJSONObject("credential");
            if (credential == null || credential.optString("value").isEmpty()) {
                Snackbar.make(requireView(), R.string.wifi_attack_none, Snackbar.LENGTH_LONG).show();
                return;
            }
            JSONObject options = new JSONObject();
            try {
                options.put("value", credential.optString("value"));
                options.put("verify_type", credential.optString("type", "wpa_psk"));
            } catch (org.json.JSONException ignored) {
                // Two plain strings cannot fail to serialize.
            }
            launchAttack(kind, target, options);
            return;
        }
        launchAttack(kind, target, null);
    }

    private void launchAttack(String kind, WifiAdapter.Network target, JSONObject options) {
        WifiBridge bridge = WifiBridge.get(requireContext());
        bridge.start();
        // Show Stryker's monitor straight away; the engine's attack_started fills it in.
        attackMonitor = new AttackMonitorDialog(requireContext(), kind,
                attackLabel(kind, target.bssid), target.ssid, target.bssid, target.channel);
        attackMonitor.show();
        if ("captive".equals(kind)) {
            captiveDialog = new CaptivePortalDialog(requireContext(), target.ssid,
                    password -> WifiBridge.get(requireContext()).verifyPsk(target.bssid, password));
            captiveDialog.show();
        }
        bridge.attack(kind, target.bssid, options);
    }

    /** The engine's own button label for this attack ("AutoDeauth", "Decloak Home", …). */
    private String attackLabel(String kind, String bssid) {
        JSONObject data = targetFor(bssid);
        JSONArray attacks = data == null ? null : data.optJSONArray("attacks");
        if (attacks == null) return "";
        for (int i = 0; i < attacks.length(); i++) {
            JSONObject attack = attacks.optJSONObject(i);
            if (attack == null || !kind.equals(attack.optString("kind"))) continue;
            return attack.optString("label", "");
        }
        return "";
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
        if (captiveDialog != null) captiveDialog.dismiss();
        captiveDialog = null;
        if (evilTwinDialog != null) evilTwinDialog.dismiss();
        evilTwinDialog = null;
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
        String secret = firstKey(event);
        if (!secret.isEmpty()) {
            text = text + "\n" + getString(R.string.wifi_attack_secret) + "\n" + secret;
        }
        attackResult.setText(text);
        attackResult.setTextColor(ContextCompat.getColor(requireContext(),
                ok ? R.color.green : R.color.red));
        attackResult.setVisibility(View.VISIBLE);
        // The engine holds the captures; the sheet's counts come back fresh from it.
        WifiBridge.get(requireContext()).requestTargets();
    }

    /** The first recovered passphrase/key in an attack_done, for the card and copy action. */
    static String firstKey(JSONObject event) {
        JSONArray keys = event.optJSONArray("keys");
        if (keys == null) return "";
        for (int i = 0; i < keys.length(); i++) {
            String value = keys.optString(i, "");
            if (!value.isEmpty() && !"null".equals(value)) return value;
        }
        return "";
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
            case "eviltwin":
                return R.string.wifi_attack_eviltwin;
            case "captive":
                return R.string.wifi_attack_captive;
            case "decloak":
            case "known_decloak":
                return R.string.wifi_attack_decloak;
            case "verify":
                return R.string.wifi_attack_verify;
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
            case "eviltwin":
                return R.string.wifi_attack_eviltwin_desc;
            case "captive":
                return R.string.wifi_attack_captive_desc;
            case "decloak":
            case "known_decloak":
                return R.string.wifi_attack_decloak_desc;
            case "verify":
                return R.string.wifi_attack_verify_desc;
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
            case "eviltwin":
                return R.drawable.router;
            case "captive":
                return R.drawable.wifi_tethering;
            case "decloak":
            case "known_decloak":
                return R.drawable.lan;
            case "verify":
                return R.drawable.login;
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
            case "eviltwin":
                return R.raw.router;
            case "captive":
                return R.raw.globe;
            case "decloak":
            case "known_decloak":
                return R.raw.brute;
            case "verify":
                return R.raw.globe;
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
