package com.opx.oss;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Evil Twin options that wifi4's TUI modal exposes, reduced to the controls the app needs:
 * twin BSSID/channel, which eviction frames to send, whether to punt once, and the period.
 * The chosen values go through {@code attack(options)} to the engine's {@code EvilTwinInput};
 * leaving a field blank keeps the engine's safe one-card default.
 */
final class EvilTwinOptionsDialog {

    interface Listener {
        void onStart(JSONObject options);
    }

    private final Context context;
    private final Dialog dialog;
    private final Listener listener;
    private final String ssid;
    private final EditText bssid;
    private final EditText channel;
    private final EditText period;
    private final CheckBox deauth;
    private final CheckBox csa;
    private final CheckBox btm;
    private final CheckBox puntOnce;
    private final TextView error;

    EvilTwinOptionsDialog(Context context, String ssid, String targetBssid, int targetChannel,
                          Listener listener) {
        this.context = context;
        this.listener = listener;
        this.ssid = ssid == null ? "" : ssid;
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

        bssid = field(R.string.wifi_eviltwin_bssid, nextBssid(targetBssid));
        body.addView(label(R.string.wifi_eviltwin_bssid));
        body.addView(bssid);

        channel = field(R.string.wifi_eviltwin_channel, String.valueOf(targetChannel));
        channel.setInputType(InputType.TYPE_CLASS_NUMBER);
        body.addView(label(R.string.wifi_eviltwin_channel));
        body.addView(channel);

        TextView punts = label(R.string.wifi_eviltwin_punts);
        body.addView(punts);
        deauth = check(R.string.wifi_eviltwin_deauth, true);
        csa = check(R.string.wifi_eviltwin_csa, true);
        btm = check(R.string.wifi_eviltwin_btm, true);
        body.addView(deauth);
        body.addView(csa);
        body.addView(btm);

        period = field(R.string.wifi_eviltwin_period, "30");
        period.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        body.addView(label(R.string.wifi_eviltwin_period));
        body.addView(period);

        puntOnce = check(R.string.wifi_eviltwin_once, false);
        body.addView(puntOnce);

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
        String twin = bssid.getText().toString().trim().toLowerCase();
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
        double seconds;
        try {
            seconds = Double.parseDouble(period.getText().toString().trim());
        } catch (NumberFormatException e) {
            showError(R.string.wifi_eviltwin_bad_period);
            return;
        }
        if (seconds < 1 || seconds > 3600) {
            showError(R.string.wifi_eviltwin_bad_period);
            return;
        }
        try {
            JSONObject options = new JSONObject();
            options.put("twin_bssid", twin);
            options.put("twin_channel", twinChannel);
            options.put("punt_period", seconds);
            options.put("punt_once", puntOnce.isChecked());
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

    private static String nextBssid(String target) {
        if (target == null || !target.matches("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")) return "";
        String lower = target.toLowerCase();
        int last = Integer.parseInt(lower.substring(lower.length() - 2), 16);
        return lower.substring(0, lower.length() - 2) + String.format(java.util.Locale.US, "%02x",
                (last + 1) % 256);
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
