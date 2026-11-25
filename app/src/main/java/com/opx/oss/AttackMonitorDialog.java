package com.opx.oss;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stryker's attack monitor, driven by the engine's campaign events.
 * <p>
 * The same long pop-up Stryker opens for an attack: icon, title and target, a state pill, the
 * Progress/Terminal tabs, a stage timeline with a spinner on the running stage, metric chips
 * (elapsed / inject / deauth / EAPOL / clients / beacon) and the live rate sparkline. Everything
 * comes from wifi4's own campaign counters and packet stats, so no stage or number is guessed
 * from a log line.
 */
final class AttackMonitorDialog {

    private static final long RATE_STEP_MS = 60L;
    private static final int RATE_STEPS = 8;

    private final Context context;
    private final Dialog dialog;
    private final LottieAnimationView anim;
    private final TextView title;
    private final TextView target;
    private final TextView pill;
    private final TextView meta;
    private final TextView result;
    private final View secretCard;
    private final TextView secretValue;
    private final TextView console;
    private final TextView consoleCount;
    private final TextView tabProgress;
    private final TextView tabTerminal;
    private final LinearLayout stages;
    private final LinearLayout metrics;
    private final SparklineView rateView;
    private final View rateBlock;
    private final View progressScroll;
    private final View consoleScroll;
    private final com.google.android.material.button.MaterialButton stop;
    private final com.google.android.material.button.MaterialButton primary;
    private boolean chopping;
    private final String kind;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Map<String, StageRow> stageRows = new LinkedHashMap<>();
    private final Map<String, TextView> metricValues = new LinkedHashMap<>();
    private final StringBuilder log = new StringBuilder();
    private boolean finished;

    private float rateShown;
    private float rateCeiling = 8f;
    private Runnable rateAnim;

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
        secretCard = content.findViewById(R.id.attack_secret_card);
        secretValue = content.findViewById(R.id.attack_secret_value);
        console = content.findViewById(R.id.attack_console);
        consoleCount = content.findViewById(R.id.attack_console_count);
        tabProgress = content.findViewById(R.id.attack_tab_progress);
        tabTerminal = content.findViewById(R.id.attack_tab_terminal);
        stages = content.findViewById(R.id.attack_stages);
        metrics = content.findViewById(R.id.attack_metrics);
        rateView = content.findViewById(R.id.attack_rate);
        rateBlock = content.findViewById(R.id.attack_rate_block);
        progressScroll = content.findViewById(R.id.attack_body_scroll);
        consoleScroll = content.findViewById(R.id.attack_console_scroll);
        stop = content.findViewById(R.id.attack_stop);
        primary = content.findViewById(R.id.attack_primary);

        ImageView icon = content.findViewById(R.id.attack_icon);
        icon.setImageResource(WifiFragment.attackIconRes(this.kind));

        title.setText(context.getString(WifiFragment.attackTitleRes(this.kind)));
        String name = ssid == null || ssid.isEmpty()
                ? context.getString(R.string.wifi_hidden_ssid) : ssid;
        target.setText(name);
        meta.setText(channel > 0
                ? String.format(Locale.US, "%s · CH %d", bssid, channel)
                : bssid);

        anim.setAnimation(WifiFragment.attackAnimRes(this.kind));
        anim.setRepeatCount(-1);
        anim.playAnimation();

        rateView.setAccent(ContextCompat.getColor(context, R.color.stryker_accent));
        buildStagesFor(this.kind);

        tabProgress.setOnClickListener(v -> showTab(true));
        tabTerminal.setOnClickListener(v -> showTab(false));
        content.findViewById(R.id.attack_console_copy).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS", log.toString()));
            }
        });
        content.findViewById(R.id.attack_secret_copy).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS",
                        secretValue.getText().toString()));
                android.widget.Toast.makeText(context, R.string.wifi_attack_secret_copied,
                        android.widget.Toast.LENGTH_SHORT).show();
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

        // ChopChop is the WEP campaign's own sub-mode (the TUI's second button); every other
        // campaign has nothing to toggle, so the button stays hidden.
        primary.setOnClickListener(v -> {
            if (!"wep".equals(this.kind) || finished) return;
            chopping = !chopping;
            WifiBridge.get(context).chop(chopping);
            renderChop();
        });
        if ("wep".equals(this.kind)) {
            primary.setVisibility(View.VISIBLE);
            renderChop();
        }

        showTab(true);
    }

    AttackMonitorDialog(Context context, String kind, String label, String ssid, String bssid,
                        int channel) {
        this(context, kind, ssid, bssid, channel);
        if (label != null && !label.isEmpty()) title.setText(label);
    }

    private void renderChop() {
        primary.setText(chopping ? R.string.wifi_attack_chop_stop : R.string.wifi_attack_chop);
        primary.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, chopping ? R.color.red : R.color.green)));
    }

    /**
     * The stages the engine reports, so a monitor opened mid-attack shows the campaign where it
     * really is; the engine's first progress tick replaces these labels and states.
     */
    private void buildStagesFor(String kind) {
        stages.removeAllViews();
        stageRows.clear();
        if ("pmkid".equals(kind)) {
            addStage("assoc", "Associating with the AP", "active");
            addStage("eapol", "Requesting the PMKID", "pending");
        } else if ("wps".equals(kind)) {
            addStage("assoc", "Associating with the AP", "active");
            addStage("wps", "WPS exchange", "pending");
            addStage("pixie", "Pixie-dust computation", "pending");
        } else if ("pbc".equals(kind)) {
            addStage("assoc", "Joining the Push-Button window", "active");
            addStage("wps", "WPS PBC exchange", "pending");
        } else if ("wep".equals(kind)) {
            addStage("ivs", "Collecting usable IVs", "active");
            addStage("crack", "PTW key recovery", "pending");
        } else if ("eviltwin".equals(kind)) {
            addStage("assoc", "Punting clients onto the twin", "active");
            addStage("eapol", "Capturing the 4-way handshake", "pending");
        } else if ("captive".equals(kind)) {
            addStage("assoc", "Hosting the captive twin", "active");
            addStage("eapol", "Waiting for a client M2", "pending");
        } else if ("decloak".equals(kind) || "known_decloak".equals(kind)) {
            addStage("probe", "Probing the hidden AP", "active");
            addStage("ssid", "SSID revealed", "pending");
        } else if ("verify".equals(kind)) {
            addStage("assoc", "Joining the AP", "active");
            addStage("verify", "Checking the credential", "pending");
        } else {
            addStage("deauth", "Deauthenticating clients", "active");
            addStage("eapol", "Handshake / PMKID", "pending");
        }
        addStage("save", "Saving the capture", "pending");
    }

    void show() {
        if (!dialog.isShowing()) dialog.show();
    }

    void dismiss() {
        handler.removeCallbacksAndMessages(null);
        dialog.dismiss();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    /** One engine event for this attack; anything else is ignored. */
    void onEvent(JSONObject event) {
        String name = event.optString("event");
        if ("chop".equals(name)) {
            chopping = event.optBoolean("active");
            renderChop();
        } else if ("attack_log".equals(name)) {
            String line = event.optString("line");
            if (line.isEmpty()) return;
            appendLog(line);
        } else if ("attack_progress".equals(name)) {
            JSONObject progress = event.optJSONObject("progress");
            if (progress != null) applyProgress(progress);
            JSONArray lines = event.optJSONArray("lines");
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) {
                    String line = lines.optString(i, "");
                    if (!line.isEmpty()) appendLog(line);
                }
            }
            String under = event.optString("under", "");
            if (!under.isEmpty() && !"null".equals(under)) meta.setText(under);
        } else if ("attack_status".equals(name)) {
            JSONObject progress = event.optJSONObject("progress");
            if (progress != null) applyProgress(progress);
        } else if ("attack_done".equals(name)) {
            finish(event);
        }
    }

    private void applyProgress(JSONObject progress) {
        JSONArray rows = progress.optJSONArray("stages");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String key = row.optString("key");
                String label = row.optString("label");
                String detail = row.optString("detail", "");
                if ("null".equals(detail)) detail = "";
                StageRow stage = stageRows.get(key);
                if (stage == null) {
                    stage = addStage(key, label, row.optString("state", "pending"));
                }
                if (!label.isEmpty()) stage.title.setText(label);
                applyState(stage, row.optString("state", "pending"), detail);
            }
        }

        JSONArray chips = progress.optJSONArray("metrics");
        if (chips != null) {
            for (int i = 0; i < chips.length(); i++) {
                JSONObject chip = chips.optJSONObject(i);
                if (chip == null) continue;
                String key = chip.optString("key");
                TextView value = metricValues.get(key);
                if (value == null) addMetric(key, chip.optString("label", key),
                        chip.optString("value", "—"), i);
                else value.setText(chip.optString("value", "—"));
            }
        }

        JSONObject rates = progress.optJSONObject("rates");
        if (rates != null) {
            double inject = rates.optDouble("inject", 0);
            double deauth = rates.optDouble("deauth", 0);
            animateRate((float) (inject + deauth));
        }
    }

    private StageRow addStage(String key, String label, String state) {
        View row = LayoutInflater.from(context).inflate(R.layout.wifi_attack_stage, stages, false);
        StageRow handle = new StageRow(
                row.findViewById(R.id.stage_title),
                row.findViewById(R.id.stage_detail),
                row.findViewById(R.id.stage_icon),
                row.findViewById(R.id.stage_spinner),
                row.findViewById(R.id.stage_indicator),
                row.findViewById(R.id.stage_rail));
        handle.title.setText(label);
        handle.state = state;
        stageRows.put(key, handle);
        stages.addView(row);
        applyState(handle, state, "");
        return handle;
    }

    private void addMetric(String key, String label, String value, int index) {
        LinearLayout row = null;
        int rowIndex = index / 2;
        if (metrics.getChildCount() <= rowIndex) {
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(6);
            metrics.addView(row, params);
        } else {
            row = (LinearLayout) metrics.getChildAt(rowIndex);
        }
        View chip = LayoutInflater.from(context).inflate(R.layout.wifi_attack_metric, row, false);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) chip.getLayoutParams();
        if (index % 2 == 1) params.setMarginStart(dp(6));
        chip.setLayoutParams(params);
        ImageView icon = chip.findViewById(R.id.metric_icon);
        icon.setImageResource(metricIcon(key));
        icon.setColorFilter(ContextCompat.getColor(context, R.color.stryker_accent),
                PorterDuff.Mode.SRC_IN);
        ((TextView) chip.findViewById(R.id.metric_label)).setText(label);
        TextView text = chip.findViewById(R.id.metric_value);
        text.setText(value);
        metricValues.put(key, text);
        row.addView(chip);
    }

    private static int metricIcon(String key) {
        switch (key) {
            case "elapsed":
                return R.drawable.timer;
            case "inject":
                return R.drawable.bolt;
            case "deauth":
                return R.drawable.deauth;
            case "eapol":
                return R.drawable.key;
            case "clients":
                return R.drawable.devices;
            case "beacon":
                return R.drawable.wifi;
            case "tried":
                return R.drawable.fiber_pin;
            case "pin":
                return R.drawable.key;
            default:
                return R.drawable.bolt;
        }
    }

    /**
     * The engine samples once a second; Stryker's monitor pushes several points per second, so the
     * graph rises smoothly instead of stepping. Each new rate is drawn in a short ramp, which is
     * what gives the injection line its heartbeat look.
     */
    private void animateRate(float value) {
        if (finished) return;
        if (rateAnim != null) handler.removeCallbacks(rateAnim);
        rateCeiling = Math.max(rateCeiling, value);
        final float from = rateShown;
        final float to = value;
        final float ceiling = Math.max(rateCeiling, 8f);
        final int[] step = {0};
        rateAnim = new Runnable() {
            @Override
            public void run() {
                step[0]++;
                float t = Math.min(1f, step[0] / (float) RATE_STEPS);
                rateShown = from + (to - from) * t;
                rateView.push(Math.min(1f, rateShown / ceiling));
                if (step[0] < RATE_STEPS) handler.postDelayed(this, RATE_STEP_MS);
            }
        };
        handler.post(rateAnim);
    }

    private void appendLog(String line) {
        if (log.length() > 0 && log.toString().endsWith(line + "\n")) return;
        log.append(line).append('\n');
        console.setText(log.toString());
        int lines = log.toString().split("\n").length;
        consoleCount.setText(String.valueOf(lines));
        consoleScroll.post(() -> {
            if (consoleScroll instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) consoleScroll).fullScroll(View.FOCUS_DOWN);
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
        failStages();
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
        primary.setVisibility(View.GONE);
        result.setText(message == null || message.isEmpty()
                ? context.getString(R.string.hc_done_fail) : message);
        result.setTextColor(ContextCompat.getColor(context, R.color.red));
        result.setVisibility(View.VISIBLE);
    }

    private void finish(JSONObject event) {
        finished = true;
        boolean ok = event.optBoolean("ok");
        String summary = event.optString("summary", "");
        String detail = event.optString("detail", "");
        for (StageRow row : stageRows.values()) {
            if ("active".equals(row.state)) {
                applyState(row, ok ? "done" : "failed", row.detail.getText().toString());
            }
        }
        StageRow save = stageRows.get("save");
        if (save != null) applyState(save, ok ? "done" : "failed", ok ? "capture written" : "");
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
        animateRate(0f);
        primary.setVisibility(View.GONE);

        String text = context.getString(ok ? R.string.wifi_attack_done_ok : R.string.wifi_attack_done_fail,
                summary);
        if (event.optBoolean("stopped")) {
            text = text + " · " + context.getString(R.string.wifi_attack_stopped);
        }
        if (!detail.isEmpty() && !"null".equals(detail)) text = text + "\n" + detail;
        showSecret(event);
        result.setText(text);
        result.setTextColor(ContextCompat.getColor(context, ok ? R.color.green : R.color.red));
        result.setVisibility(View.VISIBLE);
        progressScroll.post(() -> {
            if (progressScroll instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) progressScroll).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /** Shows the first recovered passphrase/WEP key as Stryker's copyable result block. */
    private void showSecret(JSONObject event) {
        JSONArray keys = event.optJSONArray("keys");
        String secret = "";
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String value = keys.optString(i, "");
                if (!value.isEmpty() && !"null".equals(value)) {
                    secret = value;
                    break;
                }
            }
        }
        if (secret.isEmpty()) {
            secretCard.setVisibility(View.GONE);
            return;
        }
        secretValue.setText(secret);
        secretCard.setVisibility(View.VISIBLE);
        progressScroll.post(() -> {
            if (progressScroll instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) progressScroll).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void failStages() {
        for (StageRow row : stageRows.values()) {
            if ("active".equals(row.state)) applyState(row, "failed", "");
        }
    }

    private void applyState(StageRow row, String state, String detail) {
        row.state = state;
        int color;
        switch (state) {
            case "active":
                color = ContextCompat.getColor(context, R.color.stryker_accent);
                row.spinner.setVisibility(View.VISIBLE);
                row.icon.setVisibility(View.GONE);
                row.title.setTypeface(null, Typeface.BOLD);
                break;
            case "done":
                color = ContextCompat.getColor(context, R.color.green);
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.VISIBLE);
                row.icon.setImageResource(R.drawable.done);
                row.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                row.title.setTypeface(null, Typeface.NORMAL);
                break;
            case "failed":
                color = ContextCompat.getColor(context, R.color.red);
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.VISIBLE);
                row.icon.setImageResource(R.drawable.error);
                row.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                row.title.setTypeface(null, Typeface.BOLD);
                break;
            case "pending":
            default:
                color = ContextCompat.getColor(context, R.color.grey);
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.GONE);
                row.title.setTypeface(null, Typeface.NORMAL);
                break;
        }
        row.title.setTextColor(color);
        if (row.indicator.getBackground() != null) {
            row.indicator.getBackground().mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
            row.indicator.getBackground().setAlpha("pending".equals(state) ? 40 : 70);
        }
        row.rail.setBackgroundColor("done".equals(state)
                ? (color & 0x00FFFFFF) | 0x66000000 : 0x22808080);
        if (detail != null && !detail.isEmpty()) {
            row.detail.setVisibility(View.VISIBLE);
            row.detail.setText(detail);
        } else {
            row.detail.setVisibility(View.GONE);
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class StageRow {
        final TextView title;
        final TextView detail;
        final ImageView icon;
        final ProgressBar spinner;
        final FrameLayout indicator;
        final View rail;
        String state = "pending";

        StageRow(TextView title, TextView detail, ImageView icon, ProgressBar spinner,
                 FrameLayout indicator, View rail) {
            this.title = title;
            this.detail = detail;
            this.icon = icon;
            this.spinner = spinner;
            this.indicator = indicator;
            this.rail = rail;
        }
    }
}
