package com.opx.oss;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

/**
 * The captive-portal half of Evil Twin, laid out like the rest of Stryker's sheets.
 *
 * The twin AP is hosted by wifi4's own {@code EvilTwinCampaign}; this dialog takes the password
 * a client would submit and asks the engine to check its MIC against the captured handshake.
 * A match stops the campaign and comes back through {@code psk_verified} with the key.
 */
final class CaptivePortalDialog {

    interface Listener {
        void onVerify(String password);
    }

    private final Context context;
    private final Dialog dialog;
    private final Listener listener;
    private final EditText input;
    private final TextView status;
    private final MaterialButton verify;

    CaptivePortalDialog(Context context, String ssid, Listener listener) {
        this(context, ssid, "default", listener);
    }

    /**
     * The AP crack mode's sheet: same verification flow, but the page copy comes from the
     * template the crack dialog selected (wifi4's own Evil Twin hosts the AP either way).
     */
    CaptivePortalDialog(Context context, String ssid, String template, Listener listener) {
        this.context = context;
        this.listener = listener;
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
        title.setText(templateTitle(template));
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

        TextView copy = new TextView(context);
        copy.setText(templateBody(template));
        copy.setTextColor(ContextCompat.getColor(context, R.color.grey));
        copy.setTextSize(12);
        copy.setLineSpacing(0, 1.15f);
        body.addView(copy);

        input = new EditText(context);
        input.setHint("ap".equals(template)
                ? R.string.portal_ap_hint : R.string.wifi_captive_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextColor(ContextCompat.getColor(context, R.color.night_contrast_2));
        input.setHintTextColor(ContextCompat.getColor(context, R.color.grey));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(14);
        body.addView(input, inputParams);

        status = new TextView(context);
        status.setTextColor(ContextCompat.getColor(context, R.color.grey));
        status.setTextSize(12);
        status.setPadding(0, dp(6), 0, 0);
        body.addView(status);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(8);

        MaterialButton cancel = new MaterialButton(context);
        cancel.setText(R.string.wifi_captive_cancel);
        cancel.setTextColor(ContextCompat.getColor(context, R.color.grey));
        cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        cancel.setOnClickListener(v -> dismiss());
        actions.addView(cancel);

        verify = new MaterialButton(context);
        verify.setText(R.string.wifi_captive_verify);
        verify.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.stryker_accent)));
        verify.setTextColor(Color.WHITE);
        verify.setCornerRadius(dp(14));
        verify.setOnClickListener(v -> submit());
        actions.addView(verify);
        body.addView(actions, actionParams);

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

    /** Live AP state: what the engine is doing, or why the AP did not come up. */
    void status(String message) {
        if (message == null || message.isEmpty()) return;
        status.setText(message);
        status.setTextColor(ContextCompat.getColor(context, R.color.grey));
    }

    void result(boolean ok, String message) {
        status.setText(message);
        status.setTextColor(ContextCompat.getColor(context, ok ? R.color.green : R.color.red));
        if (ok) {
            verify.setEnabled(false);
            input.setEnabled(false);
        } else {
            verify.setEnabled(true);
        }
    }

    private static int templateTitle(String template) {
        if ("ap".equals(template)) return R.string.portal_ap_title;
        if ("router".equals(template)) return R.string.wifi_portal_router_title;
        if ("hotel".equals(template)) return R.string.wifi_portal_hotel_title;
        return R.string.wifi_portal_default_title;
    }

    private static int templateBody(String template) {
        if ("ap".equals(template)) return R.string.portal_ap_body;
        if ("router".equals(template)) return R.string.wifi_portal_router_body;
        if ("hotel".equals(template)) return R.string.wifi_portal_hotel_body;
        return R.string.wifi_portal_default_body;
    }

    private void submit() {
        String password = input.getText().toString();
        if (password.isEmpty()) {
            status.setText(R.string.wifi_captive_missing);
            status.setTextColor(ContextCompat.getColor(context, R.color.red));
            return;
        }
        verify.setEnabled(false);
        status.setText(R.string.wifi_attack_secret_wait);
        status.setTextColor(ContextCompat.getColor(context, R.color.grey));
        listener.onVerify(password);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
