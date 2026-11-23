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
 * The WiFi section, laid out like Stryker's: screen title, status card (interface, adapter count
 * chip, interface/adapters/channel meta row), then the network card.
 * <p>
 * Two scan paths, both terminal-free: the headless wifi4 engine over a USB adapter, and
 * Android's built-in radio. The USB card below carries the live diagnosis and its Copy button.
 */
public class WifiFragment extends Fragment implements WifiBridge.Listener, OpxService.Listener {

    private static final int REQUEST_LOCATION = 2;

    private final WifiAdapter adapter = new WifiAdapter();

    private TextView ifaceValue;
    private TextView statusValue;
    private TextView countChip;
    private TextView ifaceMeta;
    private TextView adaptersValue;
    private TextView channelValue;
    private TextView usbDiag;
    private TextView scanText;
    private TextView scanSubtext;
    private View emptyCard;
    private View listCard;
    private View tryAgain;
    private LinearProgressIndicator progress;
    private SwipeRefreshLayout refresh;
    private RecyclerView list;

    private WifiManager wifiManager;
    private BroadcastReceiver scanReceiver;
    private boolean scanning;
    private int adapters;
    private int lastChannel;

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
        adaptersValue = view.findViewById(R.id.wifi_band_value);
        channelValue = view.findViewById(R.id.wifi_channel_value);
        usbDiag = view.findViewById(R.id.wifi_usb_diag);
        scanText = view.findViewById(R.id.scan_text);
        scanSubtext = view.findViewById(R.id.scan_subtext);
        emptyCard = view.findViewById(R.id.wifi_empty_card);
        listCard = view.findViewById(R.id.wifi_list_card);
        tryAgain = view.findViewById(R.id.try_again);
        progress = view.findViewById(R.id.wifi_scan_progress);
        refresh = view.findViewById(R.id.refresh);
        list = view.findViewById(R.id.wifilist);

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        refresh.setOnRefreshListener(() -> {
            refresh.setRefreshing(false);
            scan();
        });

        view.findViewById(R.id.wifi_scan_usb).setOnClickListener(v -> scan());
        view.findViewById(R.id.wifi_scan_builtin).setOnClickListener(v -> scanBuiltInRadio());
        tryAgain.setOnClickListener(v -> scan());
        view.findViewById(R.id.wifi_status_row).setOnClickListener(v -> diagnose());
        view.findViewById(R.id.wifi_usb_diagnose).setOnClickListener(v -> diagnose());
        view.findViewById(R.id.wifi_usb_copy).setOnClickListener(v ->
                copy(usbDiag.getText().toString(), R.string.wifi_usb_copied));

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
                statusValue.setText(R.string.wifi_status_scanning);
                WifiBridge bridge = WifiBridge.get(requireContext());
                bridge.start();
                bridge.requestDevices();
                break;
            case OpxService.STATE_FAILED:
                statusValue.setText(getString(R.string.wifi_usb_error,
                        service == null ? "" : String.valueOf(service.getError())));
                break;
            case OpxService.STATE_INSTALLING:
                statusValue.setText(R.string.notification_state_installing);
                break;
            default:
                statusValue.setText(R.string.notification_state_starting);
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
        scanText.setText(R.string.wifi_scanning);
        scanSubtext.setText(R.string.wifi_usb_scanning);
        tryAgain.setVisibility(View.GONE);
        statusValue.setText(R.string.wifi_usb_scanning);
        WifiBridge bridge = WifiBridge.get(requireContext());
        bridge.start();
        bridge.scan(25);
    }

    private void diagnose() {
        usbDiag.setText(UsbHostBridge.diagnose(requireContext()));
        Snackbar.make(requireView(), R.string.wifi_usb_diagnosed, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public void onEngineEvent(JSONObject event) {
        String name = event.optString("event");
        if ("network".equals(name)) {
            WifiAdapter.Network network = new WifiAdapter.Network();
            network.ssid = event.optString("ssid");
            network.bssid = event.optString("bssid");
            network.privacy = event.optString("privacy", "");
            network.vendor = event.optString("vendor", "");
            network.wps = event.optBoolean("wps");
            network.channel = event.optInt("channel");
            network.signal = event.optInt("signal");
            network.source = "usb";
            adapter.upsert(network);
            if (network.channel > 0) {
                lastChannel = network.channel;
                channelValue.setText(String.valueOf(lastChannel));
            }
            updateCount();
        } else if ("ready".equals(name)) {
            String version = event.optString("version", "");
            statusValue.setText(version.isEmpty()
                    ? getString(R.string.wifi_state_ready)
                    : getString(R.string.wifi_engine_ready_version, version));
        } else if ("scan_done".equals(name)) {
            scanning = false;
            progress.setVisibility(View.GONE);
            int found = event.optInt("count");
            statusValue.setText(getString(R.string.wifi_scan_done_short, found));
            updateCount();
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
            if (adapters == 0) text.append(getString(R.string.wifi_usb_none));
            usbDiag.setText(text.toString());
            adaptersValue.setText(String.valueOf(adapters));
            ifaceValue.setText(adapters == 0 ? getString(R.string.wifi_state_no_adapter) : "wlan0");
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
            int aps = event.optInt("access_points", 0);
            if (aps > 0) adaptersValue.setText(String.valueOf(adapters == 0 ? aps : adapters));
        } else if ("error".equals(name)) {
            scanning = false;
            progress.setVisibility(View.GONE);
            String message = event.optString("message");
            statusValue.setText(getString(R.string.wifi_engine_error, message));
            usbDiag.setText(getString(R.string.wifi_usb_error, message));
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
            network.ssid = result.SSID;
            network.bssid = result.BSSID;
            network.privacy = result.capabilities;
            network.wps = result.capabilities != null && result.capabilities.contains("WPS");
            network.channel = frequencyToChannel(result.frequency);
            network.signal = result.level;
            network.source = "builtin";
            adapter.upsert(network);
            if (network.channel > 0) lastChannel = network.channel;
        }
        channelValue.setText(lastChannel > 0 ? String.valueOf(lastChannel) : "—");
        statusValue.setText(getString(R.string.wifi_scan_done_short, adapter.size()));
        updateCount();
    }

    private static int frequencyToChannel(int frequency) {
        if (frequency == 2484) return 14;
        if (frequency >= 2412 && frequency <= 2484) return (frequency - 2412) / 5 + 1;
        if (frequency >= 5000) return (frequency - 5000) / 5;
        return 0;
    }

    private void updateCount() {
        int size = adapter.size();
        countChip.setText(String.valueOf(size));
        emptyCard.setVisibility(size == 0 ? View.VISIBLE : View.GONE);
        listCard.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
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

    // --------------------------------------------------------- OpxService.Listener

    @Override
    public void onEngineStateChanged() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(this::applyEngineState);
    }

    @Override
    public void onEngineExited() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() ->
                statusValue.setText(R.string.wifi_state_no_adapter));
    }
}
