package com.opx.oss;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Drives the headless wifi4 engine: one long-lived process inside the installed runtime,
 * spoken to with JSON lines. The WiFi screen never sees a shell and there is no terminal UI
 * anywhere in the app.
 * <p>
 * Events arrive on the main thread:
 * <pre>
 *   {"event":"ready","version":"0.1.0"}
 *   {"event":"network","ssid":..,"bssid":..,"channel":..,"signal":..}
 *   {"event":"scan_done","count":7}
 *   {"event":"error","message":".."}
 * </pre>
 * Every line (stdout and stderr) is also written to the in-app log, so a failure always has
 * a visible reason.
 */
public final class WifiBridge {

    public interface Listener {
        void onEngineEvent(JSONObject event);

        void onEngineStopped(String reason);
    }

    private static final String TAG = "OpxWifiBridge";
    private static final String SOCKET_NAME = "opx.usb";

    private static WifiBridge instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private Process process;
    private BufferedWriter stdin;
    private Thread stdoutThread;
    private Thread stderrThread;
    private volatile boolean running;
    private volatile boolean userStopped;
    private int restarts;
    private long restartWindowStart;
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
            AppLog.info(TAG, "start() ignored: runtime not ready");
            return;
        }

        // The engine reaches the adapter through the in-app USB bridge, so it must be listening
        // before the process asks for its first device. The shim it execs is repaired first:
        // an installed prefix can still carry a #!/usr/bin/env line, which Android cannot exec.
        OpxRuntime.ensureUsbShim(context);
        UsbHostBridge.start(context);
        if (!UsbHostBridge.isListening()) {
            // The engine finds its adapter only through this socket: starting it without a
            // bridge would run a process that can never see a card and log "no adapter" for
            // the whole session instead of the real reason.
            AppLog.info(TAG, "engine not started: the USB bridge socket is not listening");
            notifyStopped("the USB bridge could not open; the engine was not started");
            return;
        }
        acquireWakeLock();
        // pyusb needs the prefix's libusb visible; the shim must exist before python starts.
        OpxRuntime.ensureSiteShim(context);

        List<String> command = OpxRuntime.engineCommand(context);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        builder.environment().clear();
        OpxRuntime.applyEnvironment(builder, context);
        AppLog.info(TAG, "starting engine: " + String.join(" ", command));
        try {
            process = builder.start();
        } catch (IOException e) {
            Log.w(TAG, "cannot start the engine", e);
            AppLog.error(TAG, e);
            notifyStopped("cannot start the engine: " + e.getMessage());
            return;
        }

        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),
                StandardCharsets.UTF_8));
        running = true;

        stdoutThread = new Thread(this::readEvents, "opx-engine-stdout");
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        stderrThread = new Thread(this::readErrors, "opx-engine-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        // The engine only speaks after a command: the ready ping, then its settings, the
        // adapter list and the status, so the WiFi screen always knows whether an adapter is there.
        send(json("cmd", "ping"));
        setDfs(context.getSharedPreferences(SettingsFragment.PREFS, Context.MODE_PRIVATE)
                .getBoolean(SettingsFragment.PREF_DFS, false));
        requestDevices();
        send(json("cmd", "status"));
    }

    private void readEvents() {
        Process started = process;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String payload = line.trim();
                if (payload.isEmpty()) continue;
                try {
                    final JSONObject event = new JSONObject(payload);
                    AppLog.verbose(TAG, "event: " + payload);
                    main.post(() -> deliver(event));
                } catch (JSONException e) {
                    AppLog.info(TAG, "non-JSON engine output: " + payload);
                }
            }
        } catch (IOException e) {
            AppLog.info(TAG, "engine stream closed");
        } finally {
            final boolean wasRunning = running;
            running = false;
            if (wasRunning) {
                AppLog.info(TAG, "engine process exited");
                releaseWakeLock();
                main.post(() -> notifyStopped("the engine stopped"));
                maybeRestart();
            }
        }
    }

    private void readErrors() {
        Process started = process;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) AppLog.verbose(TAG, "stderr: " + line);
        } catch (IOException ignored) {
            // The process is gone; the stdout thread reports it.
        }
    }

    /**
     * Restarts an engine that died unexpectedly, at most three times per ten minutes so a
     * crash loop cannot spin forever.
     */
    private void maybeRestart() {
        if (userStopped) return;
        long now = System.currentTimeMillis();
        if (restartWindowStart == 0 || now - restartWindowStart > 10 * 60 * 1000L) {
            restartWindowStart = now;
            restarts = 0;
        }
        if (restarts >= 3) {
            AppLog.info(TAG, "not restarting the engine again: three restarts in ten minutes");
            return;
        }
        restarts++;
        if (OpxService.getState() != OpxService.STATE_READY) return;
        main.postDelayed(() -> {
            AppLog.info(TAG, "restarting the engine after an unexpected exit");
            start();
        }, 2000);
    }

    private void deliver(JSONObject event) {
        for (Listener listener : listeners) {
            try {
                listener.onEngineEvent(event);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener failed", e);
            }
        }
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
        if (!running || stdin == null) return;
        AppLog.verbose(TAG, "send: " + command);
        try {
            stdin.write(command.toString());
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            Log.w(TAG, "cannot write to the engine", e);
            AppLog.error(TAG, e);
            running = false;
            notifyStopped("the engine is not accepting commands");
        }
    }

    public void requestDevices() {
        send(json("cmd", "devices"));
    }

    /** Applies the one wifi4 scanner preference the app exposes: DFS channel hopping. */
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

    /** Cancels a running scan and keeps the adapter alive; {@link #stop()} ends the process. */
    public void stopScan() {
        send(json("cmd", "stop_scan"));
    }

    /**
     * Asks the engine which attacks wifi4 offers for each AP in its sink. The reply is a
     * {@code targets} event, so the app never guesses eligibility from the row labels.
     */
    public void requestTargets() {
        send(json("cmd", "targets"));
    }

    /** Starts one campaign against one BSSID; progress arrives as attack_log/attack_progress. */
    public void attack(String kind, String bssid) {
        attack(kind, bssid, null);
    }

    /** Evil Twin/captive portal settings from the target sheet; null keeps wifi4's defaults. */
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

    /** One client, one de-auth burst: the TUI's inline ✕ on its clients panel. */
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

    /** Broadcast de-auth: the TUI's 'd' hotkey. */
    public void deauthAll(String bssid) {
        JSONObject command = json("cmd", "deauth_all");
        try {
            command.put("bssid", bssid);
        } catch (JSONException ignored) {
            // A plain string cannot fail to serialize.
        }
        send(command);
    }

    /** wifi4's silenced list: campaigns off and captures ignored for that AP. */
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

    /** Asks which cracking tools this runtime can actually start ({@code crack_tools}). */
    public void requestCrackTools() {
        send(json("cmd", "crack_tools"));
    }

    /**
     * Runs one wifi4 cracking tool over one saved capture. {@code wordlists} are absolute paths
     * the engine can read; the booleans select wifi4's default-password list, the AP's
     * SSID-derived candidates and the SSIDs nearby clients probed for this session.
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

    /** Shuts the engine process down. The next {@link #start()} spawns a fresh one. */
    public synchronized void stop() {
        userStopped = true;
        running = false;
        if (process != null) {
            try {
                stdin.write("{\"cmd\":\"stop\"}\n");
                stdin.flush();
            } catch (IOException | NullPointerException ignored) {
                // The process may already be gone.
            }
            process.destroy();
            process = null;
        }
        stdin = null;
        UsbHostBridge.stop();
        releaseWakeLock();
        AppLog.info(TAG, "engine stopped by the app");
    }
}
