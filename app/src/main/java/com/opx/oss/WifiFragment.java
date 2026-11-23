package com.opx.oss;

import android.Manifest;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * The WiFi section: adapter status, the scan controls and the network list.
 * <p>
 * Two scan paths, both terminal-free:
 * <ul>
 *   <li>the headless wifi4 engine over a USB adapter (monitor mode, the real tool), and</li>
 *   <li>Android's built-in radio, so the screen is useful before an adapter is plugged in.</li>
 * </ul>
 * Below the status card sits the USB diagnosis: what Android currently sees, whether the
 * in-app bridge socket is listening and what the engine found. Diagnose refreshes it,
 * Copy puts it on the clipboard for bug reports.
 */
public class WifiFragment extends Fragment implements WifiBridge.Listener, OpxService.Listener {

    private static final int REQUEST_LOCATION = 2;

    private final WifiAdapter adapter = new WifiAdapter();

    private TextView ifaceValue;
    private TextView chipsetValue;
    private TextView engineState;
    private TextView usbDiag;
    private TextView countChip;
    private View emptyCard;
    private LinearProgressIndicator progress;
    private SwipeRefreshLayout refresh;
    private RecyclerView list;

    private WifiManager wifiManager;
    private BroadcastReceiver scanReceiver;
    private boolean scanning;

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
        chipsetValue = view.findViewById(R.id.wifi_chipset_value);
        engineState = view.findViewById(R.id.wifi_engine_state);
        usbDiag = view.findViewById(R.id.wifi_usb_diag);
        countChip = view.findViewById(R.id.wifi_count_chip);
        emptyCard = view.findViewById(R.id.wifi_empty_card);
        progress = view.findViewById(R.id.wifi_scan_progress);
        refresh = view.findViewById(R.id.refresh);
        list = view.findViewById(R.id.wifi_list);

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        refresh.setOnRefreshListener(() -> {
            refresh.setRefreshing(false);
            scan();
        });

        view.findViewById(R.id.wifi_scan_usb).setOnClickListener(v -> scan());
        view.findViewById(R.id.wifi_scan_builtin).setOnClickListener(v -> scanBuiltInRadio());

        view.findViewById(R.id.wifi_usb_diagnose).setOnClickListener(v -> {
            usbDiag.setText(UsbHostBridge.diagnose(requireContext()));
            Snackbar.make(requireView(), R.string.wifi_usb_diagnosed, Snackbar.LENGTH_SHORT).show();
        });
        view.findViewById(R.id.wifi_usb_copy).setOnClickListener(v -> copy(usbDiag.getText().toString()));

        wifiManager = (WifiManager) requireContext().getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onBuiltInResults();
            }
        };

        updateCount();
    }

    @Override
    public void onResume() {
        super.onResume();
        WifiBridge.get(requireContext()).addListener(this);
        OpxService service = OpxService.get();
        if (service != null) service.setListener(this);
        applyEngineState();
        requireContext().registerReceiver(scanReceiver,
                new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    @Override
    public void onPause() {
        super.onPause();
        WifiBridge.get(requireContext()).removeListener(this);
        OpxService service = OpxService.get();
        if (service != null) service.clearListener(this);
        try {
            requireContext().unregisterReceiver(scanReceiver);
        } catch (IllegalArgumentException ignored) {
            // Never registered (onPause without onResume).
        }
    }

    // --------------------------------------------------------------- engine

    private void applyEngineState() {
        OpxService service = OpxService.get();
        int state = service == null ? OpxService.STATE_STARTING : service.getState();
        switch (state) {
            case OpxService.STATE_READY:
                engineState.setText(R.string.wifi_state_ready);
                if (usbDiag.getText().length() == 0) {
                    usbDiag.setText(getString(R.string.wifi_usb_waiting));
                }
                WifiBridge bridge = WifiBridge.get(requireContext());
                bridge.start();
                bridge.requestDevices();
                break;
            case OpxService.STATE_FAILED:
                engineState.setText(R.string.wifi_state_failed);
                usbDiag.setText(getString(R.string.wifi_usb_error, service == null ? "" : String.valueOf(service.getError())));
                break;
            case OpxService.STATE_INSTALLING:
                engineState.setText(R.string.notification_state_installing);
                break;
            default:
                engineState.setText(R.string.notification_state_starting);
                break;
        }
    }

    private void scan() {
        OpxService service = OpxService.get();
        if (service == null || service.getState() != OpxService.STATE_READY) {
            Snackbar.make(requireView(), R.string.wifi_engine_pending, Snackbar.LENGTH_LONG).show();
            return;
        }
        if (scanning) return;
        scanning = true;
        adapter.clear();
        updateCount();
        progress.setVisibility(View.VISIBLE);
        usbDiag.setText(getString(R.string.wifi_usb_scanning));
        WifiBridge bridge = WifiBridge.get(requireContext());
        bridge.start();
        bridge.scan(25);
    }

    @Override
    public void onEngineEvent(JSONObject event) {
        String name = event.optString("event");
        if ("network".equals(name)) {
            WifiAdapter.Network network = new WifiAdapter.Network();
            network.ssid = event.optString("ssid");
            network.bssid = event.optString("bssid");
            network.privacy = event.optString("privacy", "");
            network.wps = event.optBoolean("wps");
            network.channel = event.optInt("channel");
            network.signal = event.optInt("signal");
            network.source = "usb";
            adapter.upsert(network);
            updateCount();
            ifaceValue.setText(getString(R.string.wifi_state_ready));
        } else if ("ready".equals(name)) {
            engineState.setText(R.string.wifi_state_ready);
            if (usbDiag.getText().toString().contains(getString(R.string.wifi_usb_waiting))) {
                usbDiag.setText(getString(R.string.wifi_usb_waiting));
            }
        } else if ("scan_done".equals(name)) {
            scanning = false;
            progress.setVisibility(View.GONE);
            usbDiag.setText(getString(R.string.wifi_usb_scan_done, event.optInt("count"), adapter.size()));
        } else if ("devices".equals(name)) {
            JSONArray devices = event.optJSONArray("devices");
            int count = devices == null ? 0 : devices.length();
            StringBuilder text = new StringBuilder();
            text.append(getString(R.string.wifi_usb_devices, count)).append('\n');
            if (devices != null) {
                for (int i = 0; i < devices.length(); i++) {
                    JSONObject device = devices.optJSONObject(i);
                    if (device == null) continue;
                    text.append("- ");
                    String description = device.optString("description", "");
                    if (!description.isEmpty()) text.append(description).append(" ");
                    String chipset = device.optString("chipset", "");
                    if (chipset != null && !chipset.equals("null") && !chipset.isEmpty()) {
                        text.append("(").append(chipset).append(")");
                    }
                    text.append('\n');
                }
            }
            if (count == 0) {
                text.append(getString(R.string.wifi_usb_none));
            }
            usbDiag.setText(text.toString());
            ifaceValue.setText(count == 0 ? getString(R.string.wifi_state_no_adapter)
                    : String.format(Locale.US, "wlan0 (%d USB)", count));
        } else if ("status".equals(name)) {
            boolean connected = event.optBoolean("connected");
            ifaceValue.setText(connected
                    ? String.valueOf(event.optString("iface", "wlan0"))
                    : getString(R.string.wifi_state_no_adapter));
            String chipset = event.has("chipset") ? event.optString("chipset", "") : "";
            chipsetValue.setText("null".equals(chipset) || chipset.isEmpty() ? "—" : chipset);
            if (!connected && usbDiag.getText().length() > 0) {
                // Keep the devices text; only refresh the diagnosis when nothing is attached.
            }
        } else if ("error".equals(name)) {
            scanning = false;
            progress.setVisibility(View.GONE);
            String message = event.optString("message");
            engineState.setText(getString(R.string.wifi_engine_error, message));
            usbDiag.setText(getString(R.string.wifi_usb_error, message));
        }
    }

    @Override
    public void onEngineStopped(String reason) {
        scanning = false;
        progress.setVisibility(View.GONE);
        engineState.setText(R.string.wifi_state_no_adapter);
        usbDiag.setText(getString(R.string.wifi_usb_stopped, reason));
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
            network.ssid = result.SSID;
            network.bssid = result.BSSID;
            network.privacy = result.capabilities;
            network.wps = result.capabilities != null && result.capabilities.contains("WPS");
            network.channel = frequencyToChannel(result.frequency);
            network.signal = result.level;
            network.source = "builtin";
            adapter.upsert(network);
        }
        updateCount();
    }

    private static int frequencyToChannel(int frequency) {
        if (frequency == 2484) return 14;
        if (frequency >= 2412 && frequency <= 2484) return (frequency - 2412) / 5 + 1;
        if (frequency >= 5000) return (frequency - 5000) / 5;
        return 0;
    }

    private void updateCount() {
        countChip.setText(getString(R.string.wifi_networks, adapter.size()));
        emptyCard.setVisibility(adapter.size() == 0 ? View.VISIBLE : View.GONE);
        list.setVisibility(adapter.size() == 0 ? View.GONE : View.VISIBLE);
    }

    private void copy(String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS", text));
            Snackbar.make(requireView(), R.string.wifi_usb_copied, Snackbar.LENGTH_SHORT).show();
        }
    }

    // --------------------------------------------------------- OpxService.Listener

    @Override
    public void onEngineStateChanged() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(this::applyEngineState);
    }

    @Override
    public void onEngineExited() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> engineState.setText(R.string.wifi_state_no_adapter));
    }
}
