package com.opx.oss;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Evil Twin options wifi4's TUI modal exposes, in the app's own sheet: the twin and punter
 * interfaces, the twin BSSID (Same / +1 / Rand), the twin channel, which eviction frames to send
 * and the punt cycle. The values go through {@code attack(options)} to the engine's
 * {@code EvilTwinInput}, which enforces the same rules the TUI does: one card cannot leave the
 * target's channel, a same BSSID on the same channel collides, and a DFS channel must be the
 * target's own.
 */
final class EvilTwinOptionsDialog {

    interface Listener {
        void onStart(JSONObject options);
    }

    /** The TUI's punt cycles: label, seconds (0 = never), punt once. */
    private static final String[] CYCLE_LABELS = {
            "Never", "Once", "5 seconds", "10 seconds", "20 seconds", "30 seconds",
            "45 seconds", "60 seconds"};
    private static final double[] CYCLE_SECONDS = {0, 30, 5, 10, 20, 30, 45, 60};
    private static final boolean[] CYCLE_ONCE = {false, true, false, false, false, false,
            false, false};

    private final Context context;
    private final Dialog dialog;
    private final Listener listener;
    private final String targetBssid;
    private final int targetChannel;
    private final List<String> interfaces;
    private final boolean single;

    private final Spinner twinIface;
    private final Spinner punterIface;
    private final Spinner cycle;
    private final EditText bssid;
    private final EditText channel;
    private final CheckBox deauth;
    private final CheckBox csa;
    private final CheckBox btm;
    private final TextView error;

    EvilTwinOptionsDialog(Context context, String ssid, String targetBssid, int targetChannel,
                          List<String> interfaces, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.targetBssid = targetBssid == null ? "" : targetBssid.toLowerCase(Locale.US);
        this.targetChannel = targetChannel;
        this.interfaces = interfaces == null ? new ArrayList<>() : new ArrayList<>(interfaces);
        this.single = this.interfaces.size() <= 1;
        this.dialog = new Dialog(context);
        dialog.setCancelable(true);

        MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.light_contrast));
        card.setRadius(dp(22));
        card.setCardElevation(0);
        card.setStrokeColor(ContextCompat.getColor(context, R.color.light_lite_contrast));
        card.setStrokeWidth(dp(1));

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(20), dp(20), dp(14));

        TextView title = new TextView(context);
        title.setText(R.string.wifi_eviltwin_title);
        title.setTextColor(ContextCompat.getColor(context, R.color.night_contrast_2));
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        body.addView(title);

        TextView target = new TextView(context);
        target.setText(ssid == null || ssid.isEmpty()
                ? context.getString(R.string.wifi_hidden_ssid) : ssid);
        target.setTextColor(ContextCompat.getColor(context, R.color.stryker_accent));
        target.setTextSize(13);
        target.setPadding(0, dp(2), 0, dp(8));
        body.addView(target);

        if (this.interfaces.isEmpty()) {
            TextView missing = label(R.string.wifi_eviltwin_iface_missing);
            missing.setTextColor(ContextCompat.getColor(context, R.color.red));
            body.addView(missing);
        }

        body.addView(label(R.string.wifi_eviltwin_iface));
        twinIface = spinner(this.interfaces);
        twinIface.setEnabled(!single);
        body.addView(twinIface);

        body.addView(label(R.string.wifi_eviltwin_punter));
        punterIface = spinner(this.interfaces);
        punterIface.setEnabled(!single);
        body.addView(punterIface);

        body.addView(label(R.string.wifi_eviltwin_bssid));
        bssid = field(R.string.wifi_eviltwin_bssid, nextBssid(targetBssid));
        bssid.setInputType(InputType.TYPE_CLASS_TEXT);
        body.addView(bssid);
        LinearLayout bssidButtons = new LinearLayout(context);
        bssidButtons.setOrientation(LinearLayout.HORIZONTAL);
        bssidButtons.setGravity(Gravity.START);
        bssidButtons.addView(smallButton(R.string.wifi_eviltwin_same,
                v -> bssid.setText(this.targetBssid)));
        bssidButtons.addView(smallButton(R.string.wifi_eviltwin_plus1,
                v -> bssid.setText(nextBssid(this.targetBssid))));
        bssidButtons.addView(smallButton(R.string.wifi_eviltwin_rand,
                v -> bssid.setText(randomBssid())));
        body.addView(bssidButtons);

        body.addView(label(R.string.wifi_eviltwin_channel));
        channel = field(R.string.wifi_eviltwin_channel, String.valueOf(targetChannel));
        channel.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (single) {
            // One radio hosts the twin AND punts for it: the target's channel is the only one.
            channel.setText(String.valueOf(targetChannel));
            channel.setEnabled(false);
            body.addView(label(R.string.wifi_eviltwin_single));
        }
        body.addView(channel);

        body.addView(label(R.string.wifi_eviltwin_punts));
        deauth = check(R.string.wifi_eviltwin_deauth, true);
        csa = check(R.string.wifi_eviltwin_csa, true);
        btm = check(R.string.wifi_eviltwin_btm, true);
        body.addView(deauth);
        body.addView(csa);
        body.addView(btm);

        body.addView(label(R.string.wifi_eviltwin_cycle));
        cycle = spinner(java.util.Arrays.asList(CYCLE_LABELS));
        cycle.setSelection(5);   // 30 seconds, the engine's own default
        body.addView(cycle);

        error = new TextView(context);
        error.setTextColor(ContextCompat.getColor(context, R.color.red));
        error.setTextSize(12);
        error.setVisibility(android.view.View.GONE);
        body.addView(error);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton cancel = new MaterialButton(context);
        cancel.setText(R.string.wifi_captive_cancel);
        cancel.setTextColor(ContextCompat.getColor(context, R.color.grey));
        cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        cancel.setOnClickListener(v -> dismiss());
        actions.addView(cancel);

        MaterialButton start = new MaterialButton(context);
        start.setText(R.string.wifi_eviltwin_start);
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.stryker_accent)));
        start.setTextColor(Color.WHITE);
        start.setCornerRadius(dp(14));
        start.setOnClickListener(v -> submit());
        actions.addView(start);
        body.addView(actions);

        card.addView(body);
        dialog.setContentView(card);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    void show() {
        if (!dialog.isShowing()) dialog.show();
    }

    void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    private void submit() {
        if (interfaces.isEmpty()) {
            showError(R.string.wifi_eviltwin_iface_missing);
            return;
        }
        String twin = bssid.getText().toString().trim().toLowerCase(Locale.US);
        if (!twin.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}")) {
            showError(R.string.wifi_eviltwin_bad_bssid);
            return;
        }
        int twinChannel;
        try {
            twinChannel = Integer.parseInt(channel.getText().toString().trim());
        } catch (NumberFormatException e) {
            showError(R.string.wifi_eviltwin_bad_channel);
            return;
        }
        if (twinChannel < 1 || twinChannel > 196) {
            showError(R.string.wifi_eviltwin_bad_channel);
            return;
        }
        if (single && twinChannel != targetChannel) {
            // The engine would lock it anyway; say so instead of silently changing the choice.
            showError(R.string.wifi_eviltwin_single);
            return;
        }
        if (!single && twinChannel != targetChannel && isDfs(twinChannel)) {
            showError(R.string.wifi_eviltwin_dfs);
            return;
        }
        if (twin.equals(targetBssid) && twinChannel == targetChannel) {
            showError(R.string.wifi_eviltwin_collide);
            return;
        }
        int selected = cycle.getSelectedItemPosition();
        if (selected < 0 || selected >= CYCLE_LABELS.length) selected = 5;
        try {
            JSONObject options = new JSONObject();
            options.put("twin_bssid", twin);
            options.put("twin_channel", twinChannel);
            options.put("twin_iface", String.valueOf(twinIface.getSelectedItem()));
            options.put("punt_iface", String.valueOf(punterIface.getSelectedItem()));
            if (selected == 0) {
                // "Never": punt once while every client is settled, then host quietly.
                options.put("punt_period", JSONObject.NULL);
                options.put("punt_once", true);
            } else {
                options.put("punt_period", CYCLE_SECONDS[selected]);
                options.put("punt_once", CYCLE_ONCE[selected]);
            }
            if (!deauth.isChecked() && !csa.isChecked() && !btm.isChecked()) {
                options.put("punt", "none");
            } else {
                JSONArray modes = new JSONArray();
                if (deauth.isChecked()) modes.put("deauth");
                if (csa.isChecked()) modes.put("csa");
                if (btm.isChecked()) modes.put("btm");
                options.put("punt_modes", modes);
            }
            listener.onStart(options);
            dismiss();
        } catch (Exception e) {
            showError(R.string.wifi_eviltwin_bad_options);
        }
    }

    private void showError(int message) {
        error.setText(message);
        error.setVisibility(android.view.View.VISIBLE);
    }

    /** 5 GHz DFS bands: 52-64 and 100-144 (wifi4's is_dfs_channel). */
    private static boolean isDfs(int channel) {
        return (channel >= 52 && channel <= 64) || (channel >= 100 && channel <= 144);
    }

    /** The TUI's and the engine's own rule: bump the last hex digit, wrapping f to 0. */
    private static String nextBssid(String target) {
        if (target == null || !target.matches("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")) return "";
        String lower = target.toLowerCase(Locale.US);
        int last = Integer.parseInt(lower.substring(lower.length() - 1), 16);
        return lower.substring(0, lower.length() - 1) + Integer.toHexString((last + 1) % 16);
    }

    private static String randomBssid() {
        java.util.Random random = new java.util.Random();
        StringBuilder text = new StringBuilder("02");
        for (int i = 0; i < 5; i++) {
            text.append(String.format(Locale.US, ":%02x", random.nextInt(256)));
        }
        return text.toString();
    }

    private EditText field(int hint, String value) {
        EditText edit = new EditText(context);
        edit.setHint(hint);
        edit.setText(value);
        edit.setSingleLine(true);
        edit.setTextColor(ContextCompat.getColor(context, R.color.night_contrast_2));
        edit.setHintTextColor(ContextCompat.getColor(context, R.color.grey));
        edit.setPadding(dp(12), dp(8), dp(12), dp(8));
        return edit;
    }

    private Spinner spinner(List<String> values) {
        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private MaterialButton smallButton(int text, android.view.View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(context);
        button.setText(text);
        button.setTextSize(12);
        button.setTextColor(ContextCompat.getColor(context, R.color.stryker_accent));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setCornerRadius(dp(12));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView label(int text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(context, R.color.grey));
        view.setTextSize(11);
        view.setPadding(0, dp(10), 0, 0);
        return view;
    }

    private CheckBox check(int text, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(text);
        box.setTextColor(ContextCompat.getColor(context, R.color.night_contrast_2));
        box.setTextSize(13);
        box.setChecked(checked);
        return box;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
