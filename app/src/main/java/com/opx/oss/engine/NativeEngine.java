package com.opx.oss.engine;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.opx.oss.AppLog;
import com.opx.oss.UsbHostBridge;
import com.opx.oss.engine.capture.HandshakeBook;
import com.opx.oss.engine.chips.ChipCatalog;
import com.opx.oss.engine.attacks.Campaign;
import com.opx.oss.engine.attacks.DeauthCampaign;
import com.opx.oss.engine.attacks.DecloakCampaign;
import com.opx.oss.engine.chips.WifiChip;
import com.opx.oss.engine.chips.firmware.FirmwareStore;
import com.opx.oss.engine.chips.rtl8187.Rtl8187Chip;
import com.opx.oss.engine.dot11.Eapol;
import com.opx.oss.engine.dot11.Mac;
import com.opx.oss.engine.dot11.Parser;

import com.opx.oss.engine.dot11.Frame;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The headless engine, in the app's own process: it speaks the same line protocol the screens
 * already use, one JSON object per command in and one event out.
 * <p>
 * This is the layer that replaces the external engine process. Its command and event names are
 * the protocol the app has always spoken, so the UI keeps its contract while the work behind it
 * is our own code, with no runtime to install and no interpreter to spawn.
 */
public final class NativeEngine {

    public static final String VERSION = "1.0.0";

    /** Every event the engine emits, in the same shape the app already parses. */
    public interface Listener {
        void onEvent(JSONObject event);
    }

    private static NativeEngine instance;

    private final Context context;
    private final Listener listener;
    private final ScanBook scan = new ScanBook();
    private final HandshakeBook handshakes = new HandshakeBook();
    private final ScanEngine scanEngine = new ScanEngine(scan);
    private final ProbeBook probes = new ProbeBook();
    private volatile WifiChip chip;
    private volatile Campaign campaign;
    private volatile String campaignKind;
    private volatile String campaignBssid;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "opx-native-engine");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopped;

    private NativeEngine(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Start the engine for {@code listener}; the {@code ready} event follows immediately. */
    public static synchronized NativeEngine start(Context context, Listener listener) {
        if (instance != null) return instance;
        NativeEngine engine = new NativeEngine(context.getApplicationContext(), listener);
        instance = engine;
        JSONObject ready = new JSONObject();
        try {
            ready.put("event", "ready");
            ready.put("version", VERSION);
            ready.put("runtime", engine.runtime());
        } catch (Exception ignored) {
            // A ready event without fields still tells the UI the engine is up.
        }
        engine.emit(ready);
        return engine;
    }

    public static synchronized void stop() {
        if (instance == null) return;
        instance.shutdown();
        instance = null;
    }

    public static synchronized boolean isRunning() {
        return instance != null && !instance.stopped;
    }

    /** Feed one command; a long campaign never blocks the caller or the next command. */
    public void send(JSONObject command) {
        if (stopped || command == null) return;
        worker.execute(() -> dispatch(command));
    }

    private void dispatch(JSONObject command) {
        String name = command.optString("cmd");
        try {
            switch (name) {
                case "":
                    error("no command");
                    break;
                case "ping":
                    emit(event("ready").put("version", VERSION).put("runtime", runtime()));
                    break;
                case "status":
                    emit(event("status").put("iface", JSONObject.NULL).put("connected", false)
                            .put("usb", usbState()));
                    break;
                case "config":
                    emit(event("config").put("dfs", command.optBoolean("dfs", false)));
                    break;
                case "devices":
                    devices();
                    break;
                case "targets":
                    targets();
                    break;
                case "probed":
                    emit(event("probed").put("ssids", new JSONArray(probes.ssids())));
                    break;
                case "scan":
                    scan(command.optInt("seconds", 0));
                    break;
                case "stop_scan":
                    stopScan();
                    break;
                case "captures":
                    emit(event("captures").put("captures", Vault.toJson(context)));
                    break;
                case "crack_tools":
                    emit(event("crack_tools").put("tools", new JSONArray().put("cpu_crack")));
                    break;
                case "attack":
                    attack(command);
                    break;
                case "stop_attack":
                    stopAttack();
                    break;
                case "attack_status":
                    attackStatus();
                    break;
                case "stop":
                    stopScan();
                    emit(event("stopped"));
                    shutdown();
                    break;
                default:
                    error("unknown command: " + name);
                    break;
            }
        } catch (Exception e) {
            error(name + " failed: " + e.getMessage());
        }
    }

    private void devices() throws Exception {
        JSONArray devices = new JSONArray();
        List<String> paths = UsbFd.list();
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        for (String path : paths) {
            JSONObject entry = new JSONObject();
            entry.put("path", path);
            UsbDevice device = manager == null ? null : device(manager, path);
            if (device != null) {
                entry.put("name", device.getDeviceName());
                entry.put("vendor_id", device.getVendorId());
                entry.put("product_id", device.getProductId());
                String model = ChipCatalog.model(device.getVendorId(), device.getProductId());
                entry.put("model", model == null ? "unsupported" : model);
                entry.put("supported", model != null);
                String firmware = ChipCatalog.firmware(
                        device.getVendorId(), device.getProductId());
                entry.put("firmware", firmware == null ? JSONObject.NULL : firmware);
                entry.put("firmware_bundled", firmware != null
                        && !FirmwareStore.forChip(context, firmware).isEmpty());
            }
            devices.put(entry);
        }
        emit(event("devices").put("devices", devices).put("usb", usbState()));
    }

    /** Tune the adapter across the plan and fold every beacon it hears into the book. */
    private void scan(int seconds) throws Exception {
        if (scanEngine.isRunning()) {
            error("a scan is already running");
            return;
        }
        String path = firstSupportedDevice();
        if (path == null) {
            error("no supported adapter is attached");
            return;
        }
        scan.clear();
        WifiChip adapter = openChip(path);
        AppLog.info("OpxEngine", "adapter up: " + adapter.name() + " " + adapter.mac());

        scanEngine.setPlan(true, false);
        scanEngine.start();
        emit(event("scan_started").put("adapter", adapter.name()));
        adapter.startReceive(this::onFrame);

        WifiChip hopping = adapter;
        Thread hop = new Thread(() -> {
            while (scanEngine.isRunning()) {
                int next = scanEngine.nextChannel();
                try {
                    hopping.setChannel(next);
                } catch (Exception e) {
                    if (scanEngine.isRunning()) error("channel " + next + ": " + e.getMessage());
                    scanEngine.stop();
                    break;
                }
                try {
                    Thread.sleep(120);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            emitScanDone();
        }, "opx-scan-hop");
        hop.setDaemon(true);
        hop.start();

        if (seconds > 0) {
            worker.execute(() -> {
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                stopScan();
            });
        }
    }

    /** Every frame the radio hears: folded into the scan book and read for a handshake. */
    private void onFrame(byte[] mpdu, int rssi) {
        if (mpdu == null || mpdu.length < 24) return;
        scanEngine.feed(mpdu, rssi);
        capture(mpdu);
    }

    /**
     * Reads a data frame for a four-way handshake or a probe request for a name. A completed
     * pair is written to the vault once and reported as {@code capture_saved}.
     */
    private void capture(byte[] mpdu) {
        int fc0 = mpdu[0] & 0xFF;
        int type = (fc0 >> 2) & 0x03;
        if (type == 0 && Frame.subtype(fc0) == Frame.SUBTYPE_PROBE_REQ) {
            String ssid = Parser.probeRequestSsid(mpdu);
            if (ssid != null) probes.record(Mac.text(Parser.slice(mpdu, 10, 6)), ssid);
            return;
        }
        if (type != 2) return;

        int headerLength = Mac.headerLength(fc0, mpdu[1] & 0xFF);
        Eapol eapol = Eapol.parse(mpdu, headerLength);
        if (eapol == null) return;

        boolean toDs = (mpdu[1] & 0x01) != 0;
        boolean fromDs = (mpdu[1] & 0x02) != 0;
        String addr1 = Mac.text(Parser.slice(mpdu, 4, 6));
        String addr2 = Mac.text(Parser.slice(mpdu, 10, 6));
        String addr3 = Mac.text(Parser.slice(mpdu, 16, 6));
        String bssid = toDs ? addr1 : (fromDs ? addr2 : addr3);
        String station = toDs ? addr2 : addr1;
        probes.associate(station, bssid);

        AccessPoint ap = scan.get(bssid);
        String ssid = ap == null ? "" : ap.ssid;
        String line = handshakes.feed(bssid, station, ssid, eapol);
        if (line == null) return;
        try {
            java.io.File saved = Vault.save(context, "capture_" + bssid.replace(':', '-') + ".22000",
                    (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            emit(event("capture_saved").put("file", saved.getName())
                    .put("bssid", bssid).put("count", Vault.list(context).size()));
        } catch (Exception e) {
            error("cannot save the capture: " + e.getMessage());
        }
    }

    private WifiChip openChip(String path) throws Exception {
        WifiChip adapter = chip;
        if (adapter != null) return adapter;
        adapter = new Rtl8187Chip(context, path);
        adapter.open();
        chip = adapter;
        return adapter;
    }

    /** Start one campaign against one BSSID on the open adapter. */
    private void attack(JSONObject command) throws Exception {
        if (campaign != null) {
            error("an attack is already running");
            return;
        }
        String kind = command.optString("kind");
        String bssid = command.optString("bssid");
        if (bssid.isEmpty()) {
            error("no target BSSID");
            return;
        }
        String path = firstSupportedDevice();
        if (path == null) {
            error("no supported adapter is attached");
            return;
        }
        WifiChip adapter = openChip(path);
        AccessPoint target = scan.get(bssid);
        if (target != null && target.channel > 0) adapter.setChannel(target.channel);

        JSONObject options = command.optJSONObject("options");
        Campaign starting;
        switch (kind) {
            case "deauth":
                starting = new DeauthCampaign(adapter, bssid, options);
                break;
            case "decloak":
                starting = new DecloakCampaign(adapter, bssid, options, probes, scan);
                break;
            default:
                error(kind + " is not available yet");
                return;
        }

        campaign = starting;
        campaignKind = kind;
        campaignBssid = bssid;
        emit(event("attack_started").put("kind", kind).put("bssid", bssid));
        Campaign running = starting;
        worker.execute(() -> {
            boolean ok = true;
            String summary = "";
            try {
                running.start(event -> emitQuietly(event));
                summary = kind + " finished";
            } catch (Exception e) {
                ok = false;
                summary = e.getMessage();
            } finally {
                campaign = null;
                JSONObject done = event("attack_done");
                try {
                    done.put("ok", ok).put("kind", kind).put("summary", summary);
                } catch (Exception ignored) {
                    // Both values are plain types.
                }
                emitQuietly(done);
            }
        });
    }

    private void stopAttack() {
        Campaign running = campaign;
        campaign = null;
        if (running != null) running.stop();
        emitQuietly(event("attack_stopped"));
    }

    private void attackStatus() {
        JSONObject status = event("attack_status");
        try {
            status.put("running", campaign != null);
            status.put("kind", campaignKind == null ? JSONObject.NULL : campaignKind);
            status.put("bssid", campaignBssid == null ? JSONObject.NULL : campaignBssid);
        } catch (Exception ignored) {
            // Every value is a plain type.
        }
        emitQuietly(status);
    }

    private void stopScan() {
        boolean wasRunning = scanEngine.isRunning();
        scanEngine.stop();
        WifiChip stopping = chip;
        chip = null;
        if (stopping != null) {
            try {
                stopping.close();
            } catch (Exception e) {
                AppLog.info("OpxEngine", "closing the adapter: " + e.getMessage());
            }
        }
        if (!wasRunning) emitScanDone();
    }

    /** The device path of the first attached adapter the engine has a driver for. */
    private String firstSupportedDevice() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        try {
            for (String path : UsbFd.list()) {
                UsbDevice device = manager == null ? null : device(manager, path);
                if (device == null) continue;
                ChipCatalog.Entry entry = ChipCatalog.resolve(
                        device.getVendorId(), device.getProductId());
                if (entry != null && "rtl8187".equals(entry.family)) return path;
            }
        } catch (Exception e) {
            AppLog.info("OpxEngine", "cannot ask the bridge for devices: " + e.getMessage());
        }
        return null;
    }

    /** Every AP with the attacks the engine can actually run against it. */
    private void targets() throws Exception {
        JSONArray targets = new JSONArray();
        for (AccessPoint ap : scan.list()) {
            JSONObject entry = ap.toJson();
            JSONArray attacks = new JSONArray();
            attacks.put("deauth");
            if (ap.hidden) attacks.put("decloak");
            if ("WEP".equals(ap.encryption)) {
                attacks.put("wep");
            } else if (ap.encryption.startsWith("WPA2") || ap.encryption.startsWith("WPA3")) {
                attacks.put("pmkid");
            }
            if (ap.wps) attacks.put("wps");
            if (!ap.ssid.isEmpty()) {
                attacks.put("eviltwin");
                attacks.put("captive");
            }
            entry.put("attacks", attacks);
            targets.put(entry);
        }
        emit(event("targets").put("targets", targets).put("handshakes", handshakes.size()));
    }

    private JSONObject runtime() {
        JSONObject runtime = new JSONObject();
        try {
            runtime.put("kind", "native");
            runtime.put("engine", VERSION);
            runtime.put("usb", usbState());
            String selfCheck = SelfCheck.run();
            runtime.put("selfcheck", selfCheck == null ? "ok" : selfCheck);
            if (selfCheck != null) Log.w("OpxEngine", "self check failed: " + selfCheck);
        } catch (Exception ignored) {
            // The caller still gets the event.
        }
        return runtime;
    }

    private JSONObject usbState() {
        JSONObject usb = new JSONObject();
        try {
            usb.put("bridge", UsbHostBridge.isListening());
            usb.put("variable", UsbFd.FD_VARIABLE);
        } catch (Exception ignored) {
            // Nothing to report beyond the event itself.
        }
        return usb;
    }

    private static UsbDevice device(UsbManager manager, String path) {
        Map<String, UsbDevice> devices = manager.getDeviceList();
        if (devices == null) return null;
        for (UsbDevice device : devices.values()) {
            if (path.equals(device.getDeviceName())) return device;
        }
        return null;
    }

    private JSONObject event(String name) {
        JSONObject event = new JSONObject();
        try {
            event.put("event", name);
        } catch (Exception ignored) {
            // A JSONObject accepts every string key.
        }
        return event;
    }

    private void error(String message) {
        try {
            emit(event("error").put("message", message));
        } catch (Exception ignored) {
            // Nothing further to report.
        }
    }

    private void emitScanDone() {
        JSONObject done = event("scan_done");
        try {
            done.put("count", scan.size());
            done.put("frames", scanEngine.frames());
        } catch (Exception ignored) {
            // Both values are plain numbers.
        }
        emitQuietly(done);
    }

    private void emitQuietly(JSONObject event) {
        try {
            emit(event);
        } catch (RuntimeException ignored) {
            // Nothing further to report.
        }
    }

    private void emit(JSONObject event) {
        if (stopped && !"stopped".equals(event.optString("event"))) return;
        Listener target = listener;
        if (target == null) return;
        try {
            target.onEvent(event);
        } catch (RuntimeException ignored) {
            // A listener that throws must not take the engine down with it.
        }
    }

    private void shutdown() {
        stopped = true;
        worker.shutdownNow();
    }
}
