package com.opx.oss;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The OPX engine host: a started foreground service that downloads, installs and verifies the
 * userland, runs one-off commands inside it and keeps the ongoing notification with Exit.
 * <p>
 * There is no terminal: the service owns no shell sessions. Everything a module needs goes
 * through {@link #runCommand} or through the headless wifi4 process the WiFi screen attaches
 * to, so the app works the same whether the user ever sees a prompt or not.
 */
public class OpxService extends Service {

    public static final String ACTION_START = "com.opx.oss.action.START";
    public static final String ACTION_EXIT = "com.opx.oss.action.EXIT";

    public static final int STATE_STARTING = 0;
    public static final int STATE_INSTALLING = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_FAILED = 3;
    public static final int STATE_STOPPED = 4;

    private static final String CHANNEL_ID = "opx-engine";
    private static final int NOTIFICATION_ID = 1;

    /**
     * Receives engine state changes. Screens register while they are visible and read the state
     * with {@link #getState()}; the callback is only the "something changed" signal.
     */
    public interface Listener {
        void onEngineStateChanged();
    }

    /** Result of a headless command; modules use this instead of a shell session. */
    public interface CommandCallback {
        void onCommandFinished(int exitCode, String output);
    }

    private static OpxService instance;

    /**
     * The engine state belongs to the process, not to the service object. The activity starts the
     * service and resumes straight away, so a screen routinely asks for the state before Android
     * has even constructed {@link OpxService}; while this was an instance field, that screen read
     * "starting" and, because it could not register on a null instance either, never heard the
     * change — the drawer said "Starting the engine…" forever while the notification, written by
     * the service itself, already said "Engine ready".
     */
    private static volatile int state = STATE_STARTING;
    private static volatile String error;
    /** Registered screens; kept here so a listener can register before the service exists. */
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Actions waiting for the userland to be installed and probed (see {@link #whenReady}). */
    private final ArrayDeque<Runnable> readyActions = new ArrayDeque<>();
    /** How long one wake-lock grant lasts; every progress tick renews it. */
    private static final long WAKE_TIMEOUT_MS = 10L * 60L * 1000L;

    private Thread engineThread;
    private boolean exiting;
    /** Resumes an offline-failed download the moment a network comes back. */
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile long lastAttemptAt;

    public static void start(Context context) {
        // A start request always means "starting": never the "stopped" left behind by Exit.
        if (state == STATE_STOPPED) state = STATE_STARTING;
        Intent intent = new Intent(context, OpxService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static OpxService get() {
        return instance;
    }

    /**
     * Restarts the install-and-probe pass behind the runtime. The Settings screen's Restart row
     * calls this instead of only restarting the wifi4 process, which used to leave the engine in
     * whatever state it was in.
     */
    public static void restart(Context context) {
        OpxService service = get();
        if (service == null) {
            start(context);
            return;
        }
        service.restartEngine();
    }

    /**
     * First-run setup calls this only after the intro has collected every permission. The
     * service may already exist (the app starts it with the process), but it will not touch
     * the network or install anything until this flag is set.
     */
    public static void beginDownload(Context context) {
        if (context == null) return;
        context.getSharedPreferences("opx", Context.MODE_PRIVATE).edit()
                .putBoolean("download_allowed", true).apply();
        start(context);
    }

    private static boolean downloadAllowed(Context context) {
        // An app updated from the bundled-runtime build has intro_seen=true but no
        // download_allowed flag. Its permissions were collected already, so resume the
        // download instead of sitting in STARTING forever.
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("opx", Context.MODE_PRIVATE);
        return prefs.getBoolean("download_allowed", false)
                || prefs.getBoolean("intro_seen", false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        watchNetwork();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every start path must leave the service in the foreground; a startForegroundService()
        // that does not call this within 5s is killed.
        startForeground(NOTIFICATION_ID, buildNotification());

        String action = intent == null ? null : intent.getAction();
        if (ACTION_EXIT.equals(action)) {
            exitAll();
            return START_NOT_STICKY;
        }
        startEngine();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        instance = null;
        stopWatchingNetwork();
        clearReadyActions();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- engine

    /**
     * Brings the engine's environment up. The engine itself lives in the app's own process now,
     * so there is nothing to download, unpack or exec: the service only prepares the storage
     * the captures are written to and reports ready.
     */
    private void startEngine() {
        if (state == STATE_READY) return;
        if (engineThread != null && engineThread.isAlive()) return;
        AppLog.info("OpxService", "engine thread starting (native engine)");
        lastAttemptAt = System.currentTimeMillis();

        engineThread = new Thread(() -> {
            final Context context = getApplicationContext();
            try {
                OpxRuntime.setupStorage(context);
                if (exiting) return;
                setState(STATE_READY, null);
                AppLog.info("OpxService", "engine ready (native)");
            } catch (Exception e) {
                AppLog.error("OpxService", e);
                if (exiting) return;
                setState(STATE_FAILED, e.toString());
            }
        }, "opx-engine");
        engineThread.start();
    }

    // ------------------------------------------------------- download keep-alive

    /**
     * Keeps the CPU awake for the download. A foreground service alone is not a promise that the
     * process keeps running with the screen off; the engine thread calls this and renews it from
     * every progress tick, so the lock never expires mid-file.
     */
    private PowerManager.WakeLock acquireDownloadWake() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) return null;
        try {
            PowerManager.WakeLock wake =
                    power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opx:runtime-download");
            wake.setReferenceCounted(false);
            wake.acquire(WAKE_TIMEOUT_MS);
            return wake;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Keeps Wi-Fi at full power while bytes move. Without it several ROMs drop the radio into a
     * low-power state as soon as the screen goes dark, and a 100+ MB download crawls or stalls.
     */
    @SuppressWarnings("deprecation")
    private WifiManager.WifiLock acquireWifiLock() {
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return null;
        try {
            WifiManager.WifiLock lock = wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, "opx:runtime-download");
            lock.setReferenceCounted(false);
            lock.acquire();
            return lock;
        } catch (RuntimeException e) {
            // A ROM that refuses the lock must not stop the download.
            return null;
        }
    }

    /**
     * Auto-resume: a download that ran out of retries while offline starts again by itself when
     * a network appears, instead of waiting for the user to find the Retry button.
     */
    private void watchNetwork() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                new Handler(Looper.getMainLooper()).post(OpxService.this::resumeDownload);
            }
        };
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            manager.registerNetworkCallback(request, networkCallback);
        } catch (RuntimeException e) {
            AppLog.info("OpxService", "network callback unavailable; auto-resume is off");
            networkCallback = null;
        }
    }

    private void stopWatchingNetwork() {
        if (networkCallback == null) return;
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            if (manager != null) manager.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException ignored) {
            // Already unregistered with the service.
        }
        networkCallback = null;
    }

    private void resumeDownload() {
        if (exiting || state != STATE_FAILED) return;
        if (OpxRuntime.isInstalled(this)) return;
        // A network callback can fire in bursts; do not restart the engine repeatedly.
        if (System.currentTimeMillis() - lastAttemptAt < 5000L) return;
        AppLog.info("OpxService", "network available again; resuming the download");
        RuntimeDownloadManager.retry();
        setState(STATE_STARTING, null);
        startEngine();
    }

    /** Re-runs the install/repair/probe pass, e.g. after the user grants storage. */
    void restartEngine() {
        if (state == STATE_INSTALLING) return;
        if (engineThread != null && engineThread.isAlive()) return;
        AppLog.info("OpxService", "restart requested");
        setState(STATE_STARTING, null);
        startEngine();
    }

    private void setState(int newState, String newError) {
        AppLog.info("OpxService", "state -> " + newState + (newError == null ? "" : " (" + newError + ")"));
        state = newState;
        error = newError;
        updateNotification();
        notifyState();
        if (newState == STATE_READY) drainReadyActions();
        if (newState == STATE_FAILED) clearReadyActions();
    }

    /** Every visible screen re-reads the state; it is the same value the notification shows. */
    private static void notifyState() {
        for (Listener listener : listeners) {
            try {
                listener.onEngineStateChanged();
            } catch (RuntimeException ignored) {
                // A screen on its way out must not keep the others from hearing the change.
            }
        }
    }

    public static int getState() {
        return state;
    }

    public static String getError() {
        return error;
    }

    // ------------------------------------------------------- headless commands

    /**
     * Runs the action once the engine is ready, on the caller's thread. Modules use this
     * instead of waiting for the runtime themselves. Actions registered before a failure are
     * dropped: there is no runtime to run them against.
     */
    public void whenReady(Runnable action) {
        if (action == null) return;
        synchronized (readyActions) {
            if (state == STATE_STARTING || state == STATE_INSTALLING) {
                readyActions.add(action);
                return;
            }
            // Nothing queued against a runtime that failed, or one the user has exited.
            if (state == STATE_FAILED || state == STATE_STOPPED) return;
        }
        action.run();
    }

    /**
     * The same queue, callable before Android has constructed the service. USB attach arrives on
     * an arbitrary screen, often while the runtime is still installing, so the action is kept
     * until the engine is ready instead of being dropped.
     */
    public static void runWhenReady(Context context, Runnable action) {
        if (context == null || action == null) return;
        start(context);
        OpxService service = get();
        if (service != null) {
            service.whenReady(action);
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] tries = {0};
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                OpxService ready = get();
                if (ready != null) {
                    ready.whenReady(action);
                } else if (tries[0]++ < 300) {
                    handler.postDelayed(this, 100);
                }
            }
        }, 100);
    }

    /**
     * Runs a one-off command in the packaged userland. No terminal and no session is created,
     * so app features work even though the app has no terminal at all. The callback runs on a
     * background thread once the command finishes.
     */
    public void runCommand(CommandCallback callback, String... command) {
        if (command == null || command.length == 0) return;
        whenReady(() -> new Thread(() -> {
            OpxRuntime.CommandResult result = OpxRuntime.run(this, command);
            if (callback != null) callback.onCommandFinished(result.exitCode, result.output);
        }, "opx-command").start());
    }

    private void drainReadyActions() {
        List<Runnable> actions;
        synchronized (readyActions) {
            actions = new ArrayList<>(readyActions);
            readyActions.clear();
        }
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // One broken module must not keep the others from getting the runtime.
            }
        }
    }

    private void clearReadyActions() {
        synchronized (readyActions) {
            readyActions.clear();
        }
    }

    /** The notification's Exit action: stop the engine process and take the service down. */
    private void exitAll() {
        exiting = true;
        if (engineThread != null) engineThread.interrupt();
        clearReadyActions();
        WifiBridge.get(this).stop();
        state = STATE_STOPPED;
        error = null;
        notifyState();
        stopForeground(true);
        stopSelf();
    }

    public static void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ---------------------------------------------------------- notification

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent exitIntent = PendingIntent.getService(this, 2,
                new Intent(this, OpxService.class).setAction(ACTION_EXIT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_term)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(notificationText())
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, getString(R.string.notification_action_exit), exitIntent)
                .build();
    }

    private String notificationText() {
        switch (state) {
            case STATE_INSTALLING:
                RuntimeDownloadManager.Snapshot download = RuntimeDownloadManager.snapshot();
                if (download.state == RuntimeDownloadManager.STATE_DOWNLOADING) {
                    return getString(R.string.notification_state_downloading,
                            download.percent(), download.label);
                }
                return getString(R.string.notification_state_installing);
            case STATE_READY:
                return getString(R.string.notification_state_ready);
            case STATE_FAILED:
                return getString(R.string.notification_state_failed);
            default:
                return getString(R.string.notification_state_starting);
        }
    }

    private void updateNotification() {
        if (exiting) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }
}
