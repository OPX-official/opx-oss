package com.opx.oss;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The OPX engine host: a started foreground service that installs and verifies the packaged
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
    private Thread engineThread;
    private boolean exiting;

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

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
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
        clearReadyActions();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- engine

    /**
     * Installs the packaged runtime on first launch and verifies it with a throwaway run,
     * so a broken runtime is reported here instead of looking like a dead WiFi screen.
     */
    private void startEngine() {
        if (state == STATE_READY && OpxRuntime.isInstalled(this)) return;
        if (engineThread != null && engineThread.isAlive()) return;
        AppLog.info("OpxService", "engine thread starting (installed=" + OpxRuntime.isInstalled(this) + ")");

        engineThread = new Thread(() -> {
            final Context context = getApplicationContext();
            try {
                if (!OpxRuntime.isInstalled(context)) {
                    setState(STATE_INSTALLING, null);
                    OpxRuntime.install(context);
                }
                OpxRuntime.repair(context);
                OpxRuntime.setupStorage(context);
                String failure = OpxRuntime.probe(context);
                AppLog.info("OpxService", "probe " + (failure == null ? "OK" : "FAILED"));
                if (exiting) return;
                setState(failure == null ? STATE_READY : STATE_FAILED, failure);
            } catch (Exception e) {
                AppLog.error("OpxService", e);
                if (exiting) return;
                setState(STATE_FAILED, e.toString());
            }
        }, "opx-engine");
        engineThread.start();
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
