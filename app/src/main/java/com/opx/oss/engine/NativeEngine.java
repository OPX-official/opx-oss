package com.opx.oss.engine;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.opx.oss.AppLog;
import com.opx.oss.UsbHostBridge;
import com.opx.oss.engine.capture.HandshakeBook;
import com.opx.oss.engine.chips.ChipCatalog;
import com.opx.oss.engine.chips.WifiChip;
import com.opx.oss.engine.chips.rtl8187.Rtl8187Chip;

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
    private volatile WifiChip chip;
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
                case "scan":
                    scan(command.optBoolean("seconds", 0));
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
                case "attack_status":
                    emit(event("attack_status").put("running", false));
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
        chip = new Rtl8187Chip(context, path);
        chip.open();
        AppLog.info("OpxEngine", "adapter up: " + chip.name() + " " + chip.mac());

        scanEngine.setPlan(true, false);
        scanEngine.start();
        emit(event("scan_started").put("adapter", chip.name()));
        chip.startReceive(scanEngine::feed);

        Thread hop = new Thread(() -> {
            while (scanEngine.isRunning()) {
                int next = scanEngine.nextChannel();
                try {
                    chip.setChannel(next);
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
            emitQuietly(event("scan_done").put("count", scan.size())
                    .put("frames", scanEngine.frames()));
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

    private void stopScan() {
        boolean wasRunning = scanEngine.isRunning();
        scanEngine.stop();
        WifiChip stopping = chip;
        chip = null;
        if (stopping != null) stopping.close();
        if (!wasRunning) emitQuietly(event("scan_done").put("count", scan.size()));
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
