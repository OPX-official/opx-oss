package com.opx.oss;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

/**
 * The Logs screen: everything the app logged, in one place, with a Copy button so the whole
 * history can be pasted into a bug report. The log is a rolling buffer of the last 500 lines
 * plus the file copy under {@code files/opx-logs.txt}.
 */
public class LogsFragment extends Fragment {

    private TextView logView;
    private TextView countView;

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

        view.findViewById(R.id.logs_copy).setOnClickListener(v -> copy());
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
        String text = AppLog.snapshot();
        String file = AppLog.readFile(requireContext());
        logView.setText(text.isEmpty() ? file : text);
        countView.setText(getString(R.string.logs_count, AppLog.size()));
        logView.setTextIsSelectable(true);
    }

    private void copy() {
        String text = AppLog.snapshot();
        if (text == null || text.isEmpty()) {
            text = AppLog.readFile(requireContext());
        }
        if (text == null || text.isEmpty()) {
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
}
