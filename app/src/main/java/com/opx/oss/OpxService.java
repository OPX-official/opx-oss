package com.opx.oss;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

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

    private static final String CHANNEL_ID = "opx-engine";
    private static final int NOTIFICATION_ID = 1;

    /** Receives engine state changes; the WiFi screen registers while it is visible. */
    public interface Listener {
        void onEngineStateChanged();

        void onEngineExited();
    }

    /** Result of a headless command; modules use this instead of a shell session. */
    public interface CommandCallback {
        void onCommandFinished(int exitCode, String output);
    }

    private static OpxService instance;

    /** Actions waiting for the userland to be installed and probed (see {@link #whenReady}). */
    private final ArrayDeque<Runnable> readyActions = new ArrayDeque<>();
    private volatile int state = STATE_STARTING;
    private volatile String error;
    private Thread engineThread;
    private Listener listener;
    private boolean exiting;

    public static void start(Context context) {
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
        listener = null;
        clearReadyActions();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- engine

    /**
     * Installs the packaged userland on first launch and verifies it with a throwaway proot
     * run, so a broken runtime is reported here instead of looking like a dead WiFi screen.
     */
    private void startEngine() {
        if (state == STATE_READY && OpxRuntime.isInstalled(this)) return;
        if (engineThread != null && engineThread.isAlive()) return;

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
                if (exiting) return;
                setState(failure == null ? STATE_READY : STATE_FAILED, failure);
            } catch (Exception e) {
                if (exiting) return;
                setState(STATE_FAILED, e.toString());
            }
        }, "opx-engine");
        engineThread.start();
    }

    private void setState(int newState, String newError) {
        state = newState;
        error = newError;
        updateNotification();
        Listener current = listener;
        if (current != null) current.onEngineStateChanged();
        if (newState == STATE_READY) drainReadyActions();
        if (newState == STATE_FAILED) clearReadyActions();
    }

    public int getState() {
        return state;
    }

    public String getError() {
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
            if (state == STATE_FAILED) return;
        }
        action.run();
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
        Listener current = listener;
        if (current != null) current.onEngineExited();
        stopForeground(true);
        stopSelf();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void clearListener(Listener listener) {
        if (this.listener == listener) this.listener = null;
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
