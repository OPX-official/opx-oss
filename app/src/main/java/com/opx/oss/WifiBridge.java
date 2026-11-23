package com.opx.oss;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
 * Drives the headless wifi4 engine: one long-lived process inside the bundled runtime,
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
        OpxService service = OpxService.get();
        if (service == null || service.getState() != OpxService.STATE_READY) {
            AppLog.info(TAG, "start() ignored: runtime not ready");
            return;
        }

        // The engine reaches the adapter through the in-app USB bridge, so it must be listening
        // before the process asks for its first device.
        UsbHostBridge.start(context);
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

        // The engine only speaks after a command: the ready ping, then the adapter list and
        // the status, so the WiFi screen always knows whether an adapter is there.
        send(json("cmd", "ping"));
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
                    AppLog.info(TAG, "event: " + payload);
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
            while ((line = reader.readLine()) != null) AppLog.info(TAG, "stderr: " + line);
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
        OpxService service = OpxService.get();
        if (service == null || service.getState() != OpxService.STATE_READY) return;
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
        AppLog.info(TAG, "send: " + command);
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

    private static JSONObject json(String key, String value) {
        JSONObject object = new JSONObject();
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
            // A null value cannot fail.
        }
        return object;
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
        AppLog.info(TAG, "engine stopped by the app");
    }
}
