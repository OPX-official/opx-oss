package com.opx.oss;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

/**
 * The Logs screen, laid out like Stryker's logger: a light header with the title, a monospace
 * console and copy / share / clear actions. The buffer is the last ~500 lines of everything the
 * app logged; a file copy lives in {@code files/opx-logs.txt}.
 */
public class LogsFragment extends Fragment {

    private TextView logView;
    private TextView countView;
    private ScrollView scroll;
    private View emptyView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        logView = view.findViewById(R.id.logs_text);
        countView = view.findViewById(R.id.logs_count);
        scroll = view.findViewById(R.id.logs_scroll);
        emptyView = view.findViewById(R.id.logs_empty_anim);

        view.findViewById(R.id.logs_copy).setOnClickListener(v -> copy());
        view.findViewById(R.id.logs_share).setOnClickListener(v -> share());
        view.findViewById(R.id.logs_clear).setOnClickListener(v -> {
            AppLog.clear();
            refresh();
            Snackbar.make(requireView(), R.string.logs_cleared, Snackbar.LENGTH_SHORT).show();
        });

        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (!isAdded() || logView == null) return;
        String text = AppLog.snapshot();
        if (text.isEmpty()) text = AppLog.readFile(requireContext());
        logView.setText(text.isEmpty() ? getString(R.string.logs_empty) : text);
        // Stryker's empty-state animation instead of a blank console.
        if (emptyView != null) emptyView.setVisibility(text.isEmpty() ? View.VISIBLE : View.GONE);
        int lines = text.isEmpty() ? 0 : text.split("\n").length;
        countView.setText(getString(R.string.logs_count, lines));
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private String text() {
        String text = AppLog.snapshot();
        if (text.isEmpty()) text = AppLog.readFile(requireContext());
        return text;
    }

    private void copy() {
        String text = text();
        if (text.isEmpty()) {
            Snackbar.make(requireView(), R.string.logs_empty, Snackbar.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS logs", text));
            Snackbar.make(requireView(), R.string.logs_copied, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void share() {
        String text = text();
        if (text.isEmpty()) {
            Snackbar.make(requireView(), R.string.logs_empty, Snackbar.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.logs_subject));
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, getString(R.string.logs_share)));
    }
}
