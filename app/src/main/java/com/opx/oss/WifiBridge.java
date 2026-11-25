package com.opx.oss;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.opx.oss.engine.NativeEngine;
import com.opx.oss.engine.UsbFd;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Drives the headless engine now that it lives in the app's own process: one JSON object per
 * command in, one event out. The WiFi screen never sees a shell and there is no terminal UI
 * anywhere in the app.
 * <p>
 * Events arrive on the main thread:
 * <pre>
 *   {"event":"ready","version":".."}
 *   {"event":"network","ssid":..,"bssid":..,"channel":..,"signal":..}
 *   {"event":"scan_done","count":7}
 *   {"event":"error","message":".."}
 * </pre>
 * Every event and every command is also written to the in-app log, so a failure always has a
 * visible reason.
 */
public final class WifiBridge {

    public interface Listener {
        void onEngineEvent(JSONObject event);

        void onEngineStopped(String reason);
    }

    private static final String TAG = "OpxWifiBridge";
    private static final long USB_PROBE_INTERVAL_MS = 30_000L;

    private static WifiBridge instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private NativeEngine engine;
    private volatile boolean running;
    private volatile boolean userStopped;
    /** Last time the bridge was asked to explain an empty device list. */
    private long lastUsbProbeAt;
    /** A running session holds a partial wakelock; a scan has to outlive the screen. */
    private PowerManager.WakeLock wakeLock;

    private WifiBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized WifiBridge get(Context context) {
        if (instance == null) instance = new WifiBridge(context);
        return instance;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        return running;
    }

    /** Starts the engine if it is not running. Cheap to call from onResume. */
    public synchronized void start() {
        if (running) return;
        userStopped = false;
        if (OpxService.getState() != OpxService.STATE_READY) {
            AppLog.info(TAG, "start() ignored: the engine service is not ready");
            return;
        }

        // The engine reaches the adapter through the in-app USB bridge, so it must be listening
        // before the engine asks for its first device.
        UsbHostBridge.start(context);
        if (!UsbHostBridge.isListening()) {
            // The engine finds its adapter only through this socket: starting it without a
            // bridge would leave a session that can never see a card and logs "no adapter" for
            // as long as it runs instead of the real reason.
            AppLog.info(TAG, "engine not started: the USB bridge socket is not listening");
            notifyStopped("the USB bridge could not open; the engine was not started");
            return;
        }
        acquireWakeLock();

        engine = NativeEngine.start(context, event -> {
            AppLog.verbose(TAG, "event: " + event);
            main.post(() -> deliver(event));
        });
        running = true;
        AppLog.info(TAG, "native engine started");

        // The ready ping, then its settings, the adapter list and the status, so the WiFi screen
        // always knows whether an adapter is there.
        send(json("cmd", "ping"));
        setDfs(context.getSharedPreferences(SettingsFragment.PREFS, Context.MODE_PRIVATE)
                .getBoolean(SettingsFragment.PREF_DFS, false));
        requestDevices();
        send(json("cmd", "status"));
    }

    private void deliver(JSONObject event) {
        if ("devices".equals(event.optString("event"))) {
            int listed = event.optJSONArray("devices") == null
                    ? 0 : event.optJSONArray("devices").length();
            if (listed == 0) probeUsbBridge();
        }
        for (Listener listener : listeners) {
            try {
                listener.onEngineEvent(event);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener failed", e);
            }
        }
    }

    /**
     * Asks the bridge itself how it sees the USB bus and logs the answer.
     * <p>
     * When the engine reports no adapter, the failing layer is either the socket, the permission
     * or the cable; this names which one, rate-limited, instead of repeating "no adapter".
     */
    private void probeUsbBridge() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUsbProbeAt < USB_PROBE_INTERVAL_MS) return;
        lastUsbProbeAt = now;
        Thread probe = new Thread(() -> {
            try {
                AppLog.info(TAG, "bridge listening=" + UsbHostBridge.isListening()
                        + " devices=" + UsbFd.list());
            } catch (IOException e) {
                AppLog.info(TAG, "cannot ask the USB bridge: " + e.getMessage());
            }
        }, "opx-usb-probe");
        probe.setDaemon(true);
        probe.start();
    }

    private void notifyStopped(String reason) {
        for (Listener listener : listeners) {
            try {
                listener.onEngineStopped(reason);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener failed", e);
            }
        }
    }

    /** Sends one command. Ignored when the engine is not running. */
    public synchronized void send(JSONObject command) {
        if (!running || engine == null) return;
        AppLog.verbose(TAG, "send: " + command);
        engine.send(command);
    }

    public void requestDevices() {
        send(json("cmd", "devices"));
    }

    /** Applies the one scanner preference the app exposes: DFS channel hopping. */
    public void setDfs(boolean enabled) {
        JSONObject command = json("cmd", "config");
        try {
            command.put("dfs", enabled);
        } catch (JSONException ignored) {
            // A plain boolean cannot fail to serialize.
        }
        send(command);
    }

    public void scan(int seconds) {
        JSONObject command = json("cmd", "scan");
        try {
            command.put("seconds", seconds);
        } catch (JSONException ignored) {
            // seconds is optional for the engine
        }
        send(command);
    }

    /** Cancels a running scan and keeps the adapter alive; {@link #stop()} ends the session. */
    public void stopScan() {
        send(json("cmd", "stop_scan"));
    }

    /**
     * Asks the engine which attacks it offers for each AP in its sink. The reply is a
     * {@code targets} event, so the app never guesses eligibility from the row labels.
     */
    public void requestTargets() {
        send(json("cmd", "targets"));
    }

    /** Starts one campaign against one BSSID; progress arrives as attack_log/attack_progress. */
    public void attack(String kind, String bssid) {
        attack(kind, bssid, null);
    }

    /** Evil Twin/captive portal settings from the target sheet; null keeps the defaults. */
    public void attack(String kind, String bssid, JSONObject options) {
        JSONObject command = json("cmd", "attack");
        try {
            command.put("kind", kind);
            command.put("bssid", bssid);
            if (options != null) command.put("options", options);
        } catch (JSONException ignored) {
            // Both keys are always present, so this cannot fail.
        }
        send(command);
    }

    /** Verifies a captive-portal candidate against the AP's captured handshake. */
    public void verifyPsk(String bssid, String psk) {
        JSONObject command = json("cmd", "verify_psk");
        try {
            command.put("bssid", bssid);
            command.put("psk", psk);
        } catch (JSONException ignored) {
            // Plain strings cannot fail to serialize.
        }
        send(command);
    }

    public void stopAttack() {
        send(json("cmd", "stop_attack"));
    }

    /** One client, one de-auth burst: the inline ✕ on the clients panel. */
    public void deauthClient(String bssid, String client) {
        JSONObject command = json("cmd", "deauth_client");
        try {
            command.put("bssid", bssid);
            command.put("client", client);
        } catch (JSONException ignored) {
            // Plain strings cannot fail to serialize.
        }
        send(command);
    }

    /** Broadcast de-auth. */
    public void deauthAll(String bssid) {
        JSONObject command = json("cmd", "deauth_all");
        try {
            command.put("bssid", bssid);
        } catch (JSONException ignored) {
            // A plain string cannot fail to serialize.
        }
        send(command);
    }

    /** The engine's silenced list: campaigns off and captures ignored for that AP. */
    public void setSilenced(String bssid, boolean silenced) {
        JSONObject command = json("cmd", "silence");
        try {
            command.put("bssid", bssid);
            command.put("on", silenced);
        } catch (JSONException ignored) {
            // A plain string and boolean cannot fail to serialize.
        }
        send(command);
    }

    /** ChopChop, the WEP campaign's packet-forging sub-mode. */
    public void chop(boolean on) {
        JSONObject command = json("cmd", "chop");
        try {
            command.put("on", on);
        } catch (JSONException ignored) {
            // A plain boolean cannot fail to serialize.
        }
        send(command);
    }

    /** Asks whether a campaign is running, for a WiFi screen opened mid-attack. */
    public void requestAttackStatus() {
        send(json("cmd", "attack_status"));
    }

    /** Asks for the saved handshake/PMKID list; the reply is a {@code captures} event. */
    public void requestCaptures() {
        send(json("cmd", "captures"));
    }

    /** Asks which cracking tools this build can actually start ({@code crack_tools}). */
    public void requestCrackTools() {
        send(json("cmd", "crack_tools"));
    }

    /**
     * Runs one cracking tool over one saved capture. {@code wordlists} are absolute paths the
     * engine can read; the booleans select the default-password list, the AP's SSID-derived
     * candidates and the SSIDs nearby clients probed for this session.
     */
    public void crack(String capture, String tool, List<String> wordlists,
                      boolean defaultPasswords, boolean ssidPasswords, boolean probedSsids) {
        JSONObject command = json("cmd", "crack");
        try {
            command.put("capture", capture);
            command.put("tool", tool);
            command.put("wordlists", new org.json.JSONArray(wordlists));
            command.put("default_passwords", defaultPasswords);
            command.put("ssid_passwords", ssidPasswords);
            command.put("probed_ssids", probedSsids);
        } catch (JSONException ignored) {
            // Every key above is a plain value, so this cannot fail.
        }
        send(command);
    }

    public void stopCrack() {
        send(json("cmd", "stop_crack"));
    }

    private static JSONObject json(String key, String value) {
        JSONObject object = new JSONObject();
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
            // A null value cannot fail.
        }
        return object;
    }

    /**
     * Keeps the CPU awake for as long as the engine runs. Aggressive OEM power managers
     * (Xiaomi/Redmi/Poco among them) suspend a backgrounded app between doze windows, which
     * stalls USB bulk transfers mid-campaign; a live session keeps this lock so its scans
     * survive a screen-off, which is what the engine expects while a campaign runs.
     */
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power == null) return;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opx:engine");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            AppLog.info(TAG, "wakelock held while the engine runs");
        } catch (RuntimeException e) {
            AppLog.info(TAG, "cannot hold a wakelock: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                AppLog.info(TAG, "wakelock released");
            }
        } catch (RuntimeException ignored) {
            // Already released with the process.
        }
        wakeLock = null;
    }

    /** Shuts the engine down. The next {@link #start()} brings up a fresh session. */
    public synchronized void stop() {
        userStopped = true;
        running = false;
        NativeEngine.stop();
        engine = null;
        UsbHostBridge.stop();
        releaseWakeLock();
        AppLog.info(TAG, "engine stopped by the app");
    }
}
