package com.opx.oss;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The OPX engine: a started foreground service that installs/verifies the bundled Termux userland
 * and owns every shell session.
 * <p>
 * It is started together with {@link MainActivity}, not by the terminal screen, so the rest of the
 * app (and every later module such as the WiFi engine) can rely on the runtime being there without
 * the user ever opening the terminal. Sessions live here as well, which is what lets them keep
 * running after the terminal activity is closed or the task is swiped away from the recents list;
 * only the notification's Exit action ends them, exactly like Termux.
 */
public class OpxService extends Service implements TerminalSessionClient {

    public static final String ACTION_START = "com.opx.oss.action.START";
    public static final String ACTION_NEW_SESSION = "com.opx.oss.action.NEW_SESSION";
    public static final String ACTION_EXIT = "com.opx.oss.action.EXIT";

    public static final int STATE_STARTING = 0;
    public static final int STATE_INSTALLING = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_FAILED = 3;

    private static final String CHANNEL_ID = "opx-engine";
    private static final int NOTIFICATION_ID = 1;

    /** Receives engine and session changes; the terminal activity registers while it is visible. */
    public interface Listener {
        void onEngineStateChanged();

        void onSessionsChanged();

        void onSessionTextChanged(TerminalSession session);

        void onEngineExited();
    }

    /** Result of a headless command; app modules use this instead of a terminal session. */
    public interface CommandCallback {
        void onCommandFinished(int exitCode, String output);
    }

    private static OpxService instance;

    private final List<TerminalSession> sessions = new ArrayList<>();
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
        // Every start path (app launch, notification, terminal) must leave the service in the
        // foreground; a startForegroundService() that does not call this within 5s is killed.
        startForeground(NOTIFICATION_ID, buildNotification());

        String action = intent == null ? null : intent.getAction();
        if (ACTION_EXIT.equals(action)) {
            exitAll();
            return START_NOT_STICKY;
        }
        if (ACTION_NEW_SESSION.equals(action)) {
            newSession();
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
        for (TerminalSession session : new ArrayList<>(sessions)) session.finishIfRunning();
        sessions.clear();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- engine

    /**
     * Installs the bundled userland on first launch and verifies it with a throwaway proot run, so a
     * broken runtime is reported here instead of looking like a terminal that closes itself.
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
     * Runs the action once the engine is ready, on the caller's thread. Modules use this instead of
     * poking at the runtime directly, so nothing has to wait for (or depend on) the terminal.
     * Actions registered before a failure are dropped: there is no runtime to run them against.
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
     * Runs a one-off shell command in the bundled userland. No terminal and no session is created,
     * so app features (the WiFi engine, helper scripts) work even when the user never opens the
     * terminal. The callback runs on a background thread once the command finishes.
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

    // -------------------------------------------------------------- sessions

    /** Creates another shell; every session is a separate proot process, like Termux sessions. */
    public TerminalSession newSession() {
        if (!OpxRuntime.isInstalled(this)) return null;
        String[] argv = OpxRuntime.prootArgs(this);
        File home = OpxRuntime.homeDir(this);
        TerminalSession session = new TerminalSession(argv[0], home.getAbsolutePath(),
                Arrays.copyOfRange(argv, 1, argv.length), OpxRuntime.environment(this), 2000, this);
        sessions.add(session);
        updateNotification();
        Listener current = listener;
        if (current != null) current.onSessionsChanged();
        return session;
    }

    public void closeSession(TerminalSession session) {
        if (session == null) return;
        sessions.remove(session);
        session.finishIfRunning();
        updateNotification();
        Listener current = listener;
        if (current != null) current.onSessionsChanged();
    }

    public List<TerminalSession> sessions() {
        return Collections.unmodifiableList(new ArrayList<>(sessions));
    }

    public int sessionCount() {
        return sessions.size();
    }

    public TerminalSession session(int index) {
        return index >= 0 && index < sessions.size() ? sessions.get(index) : null;
    }

    public boolean contains(TerminalSession session) {
        return sessions.contains(session);
    }

    /** The notification's Exit action: end every shell and take the engine down. */
    private void exitAll() {
        exiting = true;
        clearReadyActions();
        for (TerminalSession session : new ArrayList<>(sessions)) session.finishIfRunning();
        sessions.clear();
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
        Intent open = new Intent(this, TerminalActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent newSessionIntent = PendingIntent.getService(this, 1,
                new Intent(this, OpxService.class).setAction(ACTION_NEW_SESSION),
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
                .addAction(0, getString(R.string.notification_action_new_session), newSessionIntent)
                .addAction(0, getString(R.string.notification_action_exit), exitIntent)
                .build();
    }

    private String notificationText() {
        switch (state) {
            case STATE_INSTALLING:
                return getString(R.string.notification_state_installing);
            case STATE_READY:
                return getResources().getQuantityString(R.plurals.notification_state_ready,
                        sessionCount(), sessionCount());
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

    // ---------------------------------------------------- TerminalSessionClient

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        Listener current = listener;
        if (current != null) current.onSessionTextChanged(changedSession);
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        Listener current = listener;
        if (current != null) current.onSessionsChanged();
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        if (sessions.remove(finishedSession)) {
            updateNotification();
            Listener current = listener;
            if (current != null) current.onSessionsChanged();
        }
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;
        ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
        CharSequence text = item == null ? null : item.coerceToText(this);
        if (session != null && text != null) session.getEmulator().paste(text.toString());
    }

    @Override
    public void onBell(TerminalSession session) {
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public void setTerminalShellPid(TerminalSession session, int pid) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return 0;
    }

    @Override
    public void logError(String tag, String message) {
    }

    @Override
    public void logWarn(String tag, String message) {
    }

    @Override
    public void logInfo(String tag, String message) {
    }

    @Override
    public void logDebug(String tag, String message) {
    }

    @Override
    public void logVerbose(String tag, String message) {
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
    }
}
