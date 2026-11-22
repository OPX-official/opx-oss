package com.opx.oss;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.util.Arrays;

/** Full screen terminal: the bundled Termux userland running under proot. */
public class TerminalActivity extends AppCompatActivity
        implements TerminalSessionClient, TerminalViewClient {

    private TerminalView terminalView;
    private View setupPanel;
    private TextView setupStatus;
    private TerminalSession session;
    private float textSizePx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        terminalView = findViewById(R.id.terminal_view);
        setupPanel = findViewById(R.id.terminal_setup);
        setupStatus = findViewById(R.id.terminal_setup_status);
        textSizePx = getResources().getDimensionPixelSize(R.dimen.terminal_text_size);
        terminalView.setTerminalViewClient(this);
        terminalView.setTextSize((int) textSizePx);

        if (savedInstanceState == null) {
            prepare();
        }
    }

    private void prepare() {
        if (OpxRuntime.isInstalled(this)) {
            startSession();
            return;
        }
        setupPanel.setVisibility(View.VISIBLE);
        terminalView.setVisibility(View.GONE);
        setupStatus.setText(R.string.terminal_installing);
        new Thread(() -> {
            try {
                OpxRuntime.install(getApplicationContext());
                runOnUiThread(this::startSession);
            } catch (final Exception e) {
                runOnUiThread(() -> showInstallError(e));
            }
        }, "opx-bootstrap").start();
    }

    private void showInstallError(Exception e) {
        setupStatus.setText(getString(R.string.terminal_install_failed, e.getMessage()));
        Toast.makeText(this, R.string.terminal_install_failed_short, Toast.LENGTH_LONG).show();
    }

    private void startSession() {
        setupPanel.setVisibility(View.GONE);
        terminalView.setVisibility(View.VISIBLE);

        String[] argv = OpxRuntime.prootArgs(this);
        File home = OpxRuntime.homeDir(this);
        session = new TerminalSession(argv[0], home.getAbsolutePath(),
                Arrays.copyOfRange(argv, 1, argv.length), OpxRuntime.environment(this), 2000, this);
        terminalView.attachSession(session);
        terminalView.requestFocus();
    }

    @Override
    protected void onDestroy() {
        if (session != null) session.finishIfRunning();
        super.onDestroy();
    }

    // TerminalSessionClient

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        terminalView.onScreenUpdated();
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        finish();
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
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

    // TerminalViewClient

    @Override
    public float onScale(float scale) {
        textSizePx = Math.max(8f, Math.min(72f, textSizePx * scale));
        terminalView.setTextSize((int) textSizePx);
        return 1.0f;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
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
}
