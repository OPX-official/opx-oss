package com.opx.oss;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-app terminal (the Terminal section is a shortcut into this screen, the engine itself runs
 * from {@link OpxService}). The session drawer lists every open shell and offers a new one, so
 * several terminals can run at once; they belong to the service and keep running after this screen
 * is closed or the task is swiped away.
 */
public class TerminalActivity extends AppCompatActivity
        implements TerminalViewClient, OpxService.Listener {

    private DrawerLayout drawer;
    private ActionBarDrawerToggle drawerToggle;
    private TerminalView terminalView;
    private ListView sessionList;
    private ArrayAdapter<String> sessionAdapter;
    private View setupPanel;
    private View setupProgress;
    private TextView setupStatus;
    private TerminalSession currentSession;
    private float textSizePx;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int serviceAttachAttempts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        drawer = findViewById(R.id.drawer_layout);
        terminalView = findViewById(R.id.terminal_view);
        sessionList = findViewById(R.id.session_list);
        setupPanel = findViewById(R.id.terminal_setup);
        setupProgress = findViewById(R.id.terminal_setup_progress);
        setupStatus = findViewById(R.id.terminal_setup_status);

        textSizePx = getResources().getDimensionPixelSize(R.dimen.terminal_text_size);
        terminalView.setTerminalViewClient(this);
        terminalView.setTextSize((int) textSizePx);

        drawerToggle = new ActionBarDrawerToggle(this, drawer, toolbar,
                R.string.drawer_open, R.string.drawer_close);
        drawer.addDrawerListener(drawerToggle);
        drawerToggle.syncState();

        sessionAdapter = new ArrayAdapter<>(this, R.layout.item_terminal_session);
        sessionList.setAdapter(sessionAdapter);
        sessionList.setOnItemClickListener((parent, view, position, id) -> {
            drawer.closeDrawers();
            OpxService service = OpxService.get();
            if (service == null) return;
            if (position == 0) {
                switchToNewSession(service);
            } else if (position - 1 < service.sessionCount()) {
                attachSession(service.session(position - 1));
            }
        });
        sessionList.setOnItemLongClickListener((parent, view, position, id) -> {
            OpxService service = OpxService.get();
            if (service == null || position == 0) return false;
            TerminalSession session = service.session(position - 1);
            if (session == null) return false;
            service.closeSession(session);
            return true;
        });

        if (!OpxRuntime.hasStoragePermission(this)) {
            // Termux asks for storage when termux-setup-storage is run; OPX OSS asks once here so
            // ~/storage is usable, which is what makes ~/storage/shared work from the shell.
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        // The engine is normally already running because MainActivity started it; starting it here
        // again is harmless and covers a terminal opened from the notification after an Exit.
        OpxService.start(this);
        attachToService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 1) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            new Thread(() -> OpxRuntime.setupStorage(getApplicationContext()), "opx-storage").start();
        }
    }

    private void attachToService() {
        OpxService service = OpxService.get();
        if (service == null) {
            if (serviceAttachAttempts++ < 50) handler.postDelayed(this::attachToService, 100);
            return;
        }
        service.setListener(this);
        applyEngineState();
    }

    private void applyEngineState() {
        OpxService service = OpxService.get();
        if (service == null) return;
        int state = service.getState();
        if (state == OpxService.STATE_READY) {
            hideSetup();
            if (currentSession == null) {
                if (service.sessionCount() == 0) service.newSession();
                if (service.sessionCount() > 0) attachSession(service.session(0));
            }
            refreshSessions();
        } else if (state == OpxService.STATE_FAILED) {
            showError(getString(R.string.terminal_probe_failed, service.getError()));
        } else {
            showSetup(state == OpxService.STATE_INSTALLING
                    ? getString(R.string.terminal_installing)
                    : getString(R.string.terminal_engine_starting));
        }
    }

    private void switchToNewSession(OpxService service) {
        if (service.getState() != OpxService.STATE_READY) {
            attachSession(null);
            applyEngineState();
            return;
        }
        TerminalSession session = service.newSession();
        if (session != null) attachSession(session);
    }

    private void attachSession(TerminalSession session) {
        if (session == null) return;
        currentSession = session;
        hideSetup();
        terminalView.attachSession(session);
        terminalView.requestFocus();
        terminalView.post(this::showKeyboard);
        refreshSessions();
    }

    private void refreshSessions() {
        OpxService service = OpxService.get();
        List<String> rows = new ArrayList<>();
        rows.add(getString(R.string.terminal_session_new));
        if (service != null) {
            List<TerminalSession> sessions = service.sessions();
            for (int i = 0; i < sessions.size(); i++) {
                TerminalSession session = sessions.get(i);
                String title = session.getTitle();
                if (TextUtils.isEmpty(title)) title = getString(R.string.terminal_session_n, i + 1);
                rows.add((session == currentSession ? "● " : "") + title);
            }
        }
        sessionAdapter.clear();
        sessionAdapter.addAll(rows);
        sessionAdapter.notifyDataSetChanged();
    }

    private void showSetup(String message) {
        setupPanel.setVisibility(View.VISIBLE);
        terminalView.setVisibility(View.GONE);
        setupStatus.setTypeface(Typeface.DEFAULT);
        setupStatus.setOnClickListener(null);
        setupStatus.setText(message);
        setupProgress.setVisibility(View.VISIBLE);
    }

    private void showError(String details) {
        setupPanel.setVisibility(View.VISIBLE);
        terminalView.setVisibility(View.GONE);
        setupStatus.setTypeface(Typeface.MONOSPACE);
        setupStatus.setText(details);
        setupStatus.setTextIsSelectable(true);
        setupProgress.setVisibility(View.GONE);
        setupStatus.setOnClickListener(v -> {
            // Re-running the start command is also the retry path: the service re-probes the
            // runtime and reports through onEngineStateChanged.
            OpxService.start(this);
            attachToService();
        });
    }

    private void hideSetup() {
        setupPanel.setVisibility(View.GONE);
        terminalView.setVisibility(View.VISIBLE);
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    protected void onDestroy() {
        OpxService service = OpxService.get();
        if (service != null) service.clearListener(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        // Leaving the screen (or swiping the task away) must not kill the shells: the service owns
        // them and only the notification's Exit action ends them.
        super.onBackPressed();
    }

    // OpxService.Listener

    @Override
    public void onEngineStateChanged() {
        runOnUiThread(this::applyEngineState);
    }

    @Override
    public void onSessionsChanged() {
        runOnUiThread(() -> {
            OpxService service = OpxService.get();
            if (service == null) return;
            refreshSessions();
            if (service.sessionCount() == 0) {
                currentSession = null;
                if (!isFinishing()) finish();
                return;
            }
            if (currentSession == null || !service.contains(currentSession)) {
                attachSession(service.session(0));
            }
        });
    }

    @Override
    public void onSessionTextChanged(TerminalSession session) {
        if (session == currentSession) terminalView.onScreenUpdated();
    }

    @Override
    public void onEngineExited() {
        runOnUiThread(() -> {
            currentSession = null;
            if (!isFinishing()) finish();
        });
    }

    // TerminalViewClient

    @Override
    public float onScale(float scale) {
        textSizePx = Math.max(8f, Math.min(72f, textSizePx * scale));
        terminalView.setTextSize((int) textSizePx);
        return 1.0f;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        terminalView.requestFocus();
        showKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return false;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return false;
    }

    @Override
    public boolean readAltKey() {
        return false;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
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
