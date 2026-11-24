package com.opx.oss;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stryker's attack monitor, driven by the engine's campaign events.
 * <p>
 * The same long pop-up Stryker opens for an attack: icon, title and target, a state pill, the
 * Progress/Terminal tabs, the campaign's own status lines as stages, its gif, and the result
 * footer. The lines come from wifi4 itself ({@code attack_progress}/{@code attack_log}), so the
 * dialog never invents a stage the campaign is not in.
 */
final class AttackMonitorDialog {

    private final Context context;
    private final Dialog dialog;
    private final LottieAnimationView anim;
    private final TextView title;
    private final TextView target;
    private final TextView pill;
    private final TextView meta;
    private final TextView result;
    private final TextView console;
    private final TextView consoleCount;
    private final TextView tabProgress;
    private final TextView tabTerminal;
    private final LinearLayout stages;
    private final View progressScroll;
    private final View consoleScroll;
    private final com.google.android.material.button.MaterialButton stop;
    private final String kind;

    private final StringBuilder log = new StringBuilder();
    private boolean finished;

    AttackMonitorDialog(Context context, String kind, String ssid, String bssid, int channel) {
        this.context = context;
        this.kind = kind == null ? "deauth" : kind;
        this.dialog = new Dialog(context);
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_attack_monitor, null);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCancelable(true);

        anim = content.findViewById(R.id.attack_anim);
        title = content.findViewById(R.id.attack_title);
        target = content.findViewById(R.id.attack_target);
        pill = content.findViewById(R.id.attack_pill);
        meta = content.findViewById(R.id.attack_meta);
        result = content.findViewById(R.id.attack_result);
        console = content.findViewById(R.id.attack_console);
        consoleCount = content.findViewById(R.id.attack_console_count);
        tabProgress = content.findViewById(R.id.attack_tab_progress);
        tabTerminal = content.findViewById(R.id.attack_tab_terminal);
        stages = content.findViewById(R.id.attack_stages);
        progressScroll = content.findViewById(R.id.attack_body_scroll);
        consoleScroll = content.findViewById(R.id.attack_console_scroll);
        stop = content.findViewById(R.id.attack_stop);

        ImageView icon = content.findViewById(R.id.attack_icon);
        icon.setImageResource(WifiFragment.attackIconRes(this.kind));

        title.setText(context.getString(WifiFragment.attackTitleRes(this.kind)));
        String name = ssid == null || ssid.isEmpty()
                ? context.getString(R.string.wifi_hidden_ssid) : ssid;
        target.setText(name);
        meta.setText(channel > 0
                ? String.format(java.util.Locale.US, "%s · CH %d", bssid, channel)
                : bssid);

        anim.setAnimation(WifiFragment.attackAnimRes(this.kind));
        anim.setRepeatCount(-1);
        anim.playAnimation();

        tabProgress.setOnClickListener(v -> showTab(true));
        tabTerminal.setOnClickListener(v -> showTab(false));
        content.findViewById(R.id.attack_console_copy).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS", log.toString()));
            }
        });

        stop.setOnClickListener(v -> {
            if (finished) {
                dialog.dismiss();
            } else {
                stop.setText(R.string.wifi_attack_stopping);
                WifiBridge.get(context).stopAttack();
            }
        });

        stage(context.getString(R.string.wifi_attack_starting));
        showTab(true);
    }

    void show() {
        if (!dialog.isShowing()) dialog.show();
    }

    void dismiss() {
        dialog.dismiss();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    /** One engine event for this attack; anything else is ignored. */
    void onEvent(JSONObject event) {
        String name = event.optString("event");
        if ("attack_log".equals(name)) {
            String line = event.optString("line");
            if (line.isEmpty()) return;
            appendLog(line);
            stage(line);
        } else if ("attack_progress".equals(name)) {
            JSONArray lines = event.optJSONArray("lines");
            if (lines == null) return;
            for (int i = 0; i < lines.length(); i++) {
                String line = lines.optString(i);
                if (!line.isEmpty()) stage(line);
            }
            String under = event.optString("under", "");
            if (!under.isEmpty() && !"null".equals(under)) meta.setText(under);
        } else if ("attack_done".equals(name)) {
            finish(event);
        }
    }

    private void appendLog(String line) {
        log.append(line).append('\n');
        console.setText(log.toString());
        int lines = log.toString().split("\n").length;
        consoleCount.setText(String.valueOf(lines));
        consoleScroll.post(() -> {
            if (consoleScroll instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) consoleScroll).fullScroll(View.FOCUS_DOWN);
            }
            if (progressScroll instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) progressScroll).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void stage(String line) {
        TextView row = new TextView(context);
        row.setText("● " + line);
        row.setTextSize(12f);
        row.setTextColor(ContextCompat.getColor(context, R.color.night_contrast_2));
        row.setPadding(0, 3, 0, 3);
        stages.addView(row);
        while (stages.getChildCount() > 10) stages.removeViewAt(0);
        progressScroll.post(() -> {
            if (progressScroll instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) progressScroll).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void showTab(boolean progress) {
        progressScroll.setVisibility(progress ? View.VISIBLE : View.GONE);
        consoleScroll.setVisibility(progress ? View.GONE : View.VISIBLE);
        tabProgress.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, progress ? R.color.itemcolor : android.R.color.transparent)));
        tabTerminal.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, progress ? android.R.color.transparent : R.color.itemcolor)));
        tabProgress.setTextColor(ContextCompat.getColor(context,
                progress ? R.color.stryker_accent : R.color.grey));
        tabTerminal.setTextColor(ContextCompat.getColor(context,
                progress ? R.color.grey : R.color.stryker_accent));
    }

    /** The engine refused or failed the attack before it streamed any event of its own. */
    void fail(String message) {
        if (finished) return;
        finished = true;
        pill.setText(R.string.wifi_attack_pill_failed);
        pill.setTextColor(ContextCompat.getColor(context, R.color.red));
        pill.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.tile_red)));
        stop.setText(R.string.wifi_attack_dismiss);
        stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.light_lite_contrast)));
        stop.setTextColor(ContextCompat.getColor(context, R.color.night_contrast_2));
        anim.pauseAnimation();
        anim.setAnimation(R.raw.error);
        anim.playAnimation();
        result.setText(message == null || message.isEmpty()
                ? context.getString(R.string.hc_done_fail) : message);
        result.setTextColor(ContextCompat.getColor(context, R.color.red));
        result.setVisibility(View.VISIBLE);
        stage(message);
    }

    private void finish(JSONObject event) {
        finished = true;
        boolean ok = event.optBoolean("ok");
        String summary = event.optString("summary", "");
        String detail = event.optString("detail", "");
        pill.setText(ok ? R.string.wifi_attack_pill_ok : R.string.wifi_attack_pill_failed);
        pill.setTextColor(ContextCompat.getColor(context, ok ? R.color.green : R.color.red));
        pill.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, ok ? R.color.tile_green : R.color.tile_red)));
        stop.setText(R.string.wifi_attack_dismiss);
        stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.light_lite_contrast)));
        stop.setTextColor(ContextCompat.getColor(context, R.color.night_contrast_2));
        anim.pauseAnimation();
        anim.setRepeatCount(0);
        anim.setAnimation(ok ? R.raw.confetti : R.raw.error);
        anim.playAnimation();

        String text = context.getString(ok ? R.string.wifi_attack_done_ok : R.string.wifi_attack_done_fail,
                summary);
        if (event.optBoolean("stopped")) {
            text = text + " · " + context.getString(R.string.wifi_attack_stopped);
        }
        if (!detail.isEmpty() && !"null".equals(detail)) text = text + "\n" + detail;
        result.setText(text);
        result.setTextColor(ContextCompat.getColor(context, ok ? R.color.green : R.color.red));
        result.setVisibility(View.VISIBLE);
        stage(summary);
    }
}
