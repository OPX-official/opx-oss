package com.opx.oss;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Stryker's install log: every punctuated step of the download and the unpack, with a monospace
 * chip per level. It polls {@link RuntimeDownloadManager#logEntries()} while it is open, so the
 * lines keep arriving even though the download itself runs in the engine service.
 */
final class InstallLogDialog {

    private final Dialog dialog;
    private final RecyclerView list;
    private final TextView state;
    private final LogAdapter adapter = new LogAdapter();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            reload();
            handler.postDelayed(this, 400);
        }
    };

    private InstallLogDialog(Dialog dialog, RecyclerView list, TextView state) {
        this.dialog = dialog;
        this.list = list;
        this.state = state;
    }

    static InstallLogDialog show(Context context) {
        View root = LayoutInflater.from(context).inflate(R.layout.intro_install_log, null);
        RecyclerView list = root.findViewById(R.id.install_log_list);
        list.setLayoutManager(new LinearLayoutManager(context));

        TextView state = root.findViewById(R.id.install_log_state);

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        InstallLogDialog handle = new InstallLogDialog(dialog, list, state);
        list.setAdapter(handle.adapter);

        MaterialButton close = root.findViewById(R.id.install_log_close);
        close.setOnClickListener(v -> dialog.dismiss());
        MaterialButton copy = root.findViewById(R.id.install_log_copy);
        copy.setOnClickListener(v -> {
            ClipboardManager cb =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb == null) return;
            cb.setPrimaryClip(ClipData.newPlainText("OPX install log", handle.adapter.asText()));
            Toast.makeText(context, R.string.hc_copied, Toast.LENGTH_SHORT).show();
        });

        dialog.setOnDismissListener(d -> handle.handler.removeCallbacks(handle.poll));
        dialog.show();
        handle.reload();
        handle.handler.post(handle.poll);
        return handle;
    }

    /** Appends only what is new; a trimmed log (over 600 lines) is reloaded whole. */
    private void reload() {
        List<RuntimeDownloadManager.LogEntry> entries = RuntimeDownloadManager.logEntries();
        if (entries.size() < adapter.size()) adapter.clear();
        for (int i = adapter.size(); i < entries.size(); i++) {
            adapter.append(entries.get(i));
        }
        RuntimeDownloadManager.Snapshot snapshot = RuntimeDownloadManager.snapshot();
        if (state != null) state.setText(snapshot.label);
        if (adapter.size() > 0) list.scrollToPosition(adapter.size() - 1);
    }

    void setState(CharSequence text) {
        if (state != null) state.setText(text);
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    void dismiss() {
        handler.removeCallbacks(poll);
        list.setAdapter(null);
        if (dialog.isShowing()) dialog.dismiss();
    }

    /** The chip list; one row per log line, coloured by level like Stryker's LogAdapter. */
    private static final class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

        private final List<RuntimeDownloadManager.LogEntry> lines = new ArrayList<>();

        void append(RuntimeDownloadManager.LogEntry line) {
            lines.add(line);
            notifyItemInserted(lines.size() - 1);
        }

        void clear() {
            int size = lines.size();
            lines.clear();
            if (size > 0) notifyItemRangeRemoved(0, size);
        }

        int size() {
            return lines.size();
        }

        String asText() {
            StringBuilder sb = new StringBuilder();
            for (RuntimeDownloadManager.LogEntry line : lines) {
                sb.append('[').append(chipFor(line.level)).append("] ")
                        .append(line.text).append(System.lineSeparator());
            }
            return sb.toString();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.intro_log_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            RuntimeDownloadManager.LogEntry line = lines.get(position);
            int color = ContextCompat.getColor(holder.itemView.getContext(),
                    colorFor(line.level));
            holder.chip.setText(chipFor(line.level));
            holder.chip.setTextColor(color);
            if (holder.chip.getBackground() instanceof GradientDrawable) {
                GradientDrawable bg = (GradientDrawable) holder.chip.getBackground().mutate();
                bg.setColor((color & 0x00FFFFFF) | 0x33000000);
                holder.chip.setBackground(bg);
            } else {
                holder.chip.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
                holder.chip.getBackground().setAlpha(60);
            }
            holder.text.setText(line.text);
            holder.text.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                    line.level == RuntimeDownloadManager.LOG_ERROR ? R.color.log_err
                            : R.color.log_text));
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        private static int colorFor(int level) {
            switch (level) {
                case RuntimeDownloadManager.LOG_SUCCESS:
                    return R.color.log_ok;
                case RuntimeDownloadManager.LOG_WARN:
                    return R.color.log_warn;
                case RuntimeDownloadManager.LOG_ERROR:
                    return R.color.log_err;
                case RuntimeDownloadManager.LOG_COMMAND:
                    return R.color.log_cmd;
                case RuntimeDownloadManager.LOG_STEP:
                    return R.color.log_step;
                case RuntimeDownloadManager.LOG_INFO:
                default:
                    return R.color.log_info;
            }
        }

        private static String chipFor(int level) {
            switch (level) {
                case RuntimeDownloadManager.LOG_SUCCESS:
                    return "OK";
                case RuntimeDownloadManager.LOG_WARN:
                    return "WRN";
                case RuntimeDownloadManager.LOG_ERROR:
                    return "ERR";
                case RuntimeDownloadManager.LOG_COMMAND:
                    return "CMD";
                case RuntimeDownloadManager.LOG_STEP:
                    return ">>>";
                case RuntimeDownloadManager.LOG_INFO:
                default:
                    return "...";
            }
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView chip;
            final TextView text;

            VH(@NonNull View itemView) {
                super(itemView);
                chip = itemView.findViewById(R.id.log_chip);
                text = itemView.findViewById(R.id.log_text);
            }
        }
    }
}
