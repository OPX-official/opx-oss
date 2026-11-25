package com.opx.oss;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * wifi4's {@code EvilTwinInputModal}, in the app's own sheet: the twin interface (only a card that
 * can spoof or host under a fixed MAC), the twin channel (the card's supported channels, DFS
 * excluded off-target, locked to the target when one card must host and punt), the twin BSSID
 * (Same / +1 / Rand, disabled when the card cannot spoof), the punter interface, the PMF-aware
 * punt methods and the punt cycle. It produces the same {@code EvilTwinInput} the TUI's modal
 * does; the engine's {@code _eviltwin_input} enforces the same rules again.
 */
final class EvilTwinOptionsDialog {

    interface Listener {
        void onStart(JSONObject options);
    }

    /** One wifi4 array member, as the backend's {@code interfaces()} describes it. */
    private static final class Iface {
        String name = "";
        String label = "";
        String mac = "";
        boolean host;
        boolean spoofable;
        final List<Integer> channels = new ArrayList<>();
    }

    /** wifi4's {@code _CYCLES}: label, punt_period_sec (0 = None), punt_once. */
    private static final int[] CYCLE_SECONDS = {0, 30, 5, 10, 20, 30, 45, 60};
    private static final boolean[] CYCLE_ONCE = {false, true, false, false, false, false,
            false, false};

    private final Context context;
    private final Dialog dialog;
    private final Listener listener;
    private final String targetBssid;
    private final int targetChannel;
    private final List<Iface> members = new ArrayList<>();
    private final List<Iface> hosts = new ArrayList<>();
    private final boolean single;
    private final boolean robustBlocked;

    private final Spinner twinIface;
    private final Spinner punterIface;
    private final Spinner channel;
    private final Spinner cycle;
    private final EditText bssid;
    private final MaterialButton same;
    private final MaterialButton plusOne;
    private final MaterialButton random;
    private final CheckBox deauth;
    private final CheckBox csa;
    private final CheckBox btm;
    private final CheckBox portal;
    private final MaterialButton templateButton;
    private File templateFile;
    private Runnable templateListener;
    private final TextView warning;
    private final TextView error;
    private final List<Integer> channelValues = new ArrayList<>();

    EvilTwinOptionsDialog(Context context, String ssid, String targetBssid, int targetChannel,
                          String pmf, JSONArray interfaces, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.targetBssid = targetBssid == null ? "" : targetBssid.toLowerCase(Locale.US);
        this.targetChannel = targetChannel;
        this.dialog = new Dialog(context);
        dialog.setCancelable(true);
        if (interfaces != null) {
            for (int i = 0; i < interfaces.length(); i++) {
                Iface iface = parse(interfaces.optJSONObject(i));
                if (iface == null) continue;
                members.add(iface);
                if (iface.host) hosts.add(iface);
            }
        }
        this.single = members.size() <= 1;
        this.robustBlocked = "required".equals(pmf) || "invalid".equals(pmf);

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

        if (hosts.isEmpty()) {
            // wifi4 refuses up front when no member can host the twin (its own message).
            TextView missing = label(R.string.wifi_eviltwin_iface_missing);
            missing.setTextColor(ContextCompat.getColor(context, R.color.red));
            body.addView(missing);
        }

        body.addView(label(R.string.wifi_eviltwin_iface));
        twinIface = spinner(hostLabels());
        body.addView(twinIface);

        body.addView(label(R.string.wifi_eviltwin_channel));
        channel = spinner(new ArrayList<>());
        channel.setEnabled(!single);
        body.addView(channel);

        body.addView(label(R.string.wifi_eviltwin_bssid));
        // wifi4: single card defaults to +1 (the target's BSSID on the same channel would collide),
        // two cards default to the target's own BSSID so the twin is a true clone.
        bssid = field(R.string.wifi_eviltwin_bssid,
                single ? nextBssid(this.targetBssid) : this.targetBssid);
        bssid.setInputType(InputType.TYPE_CLASS_TEXT);
        body.addView(bssid);
        LinearLayout bssidButtons = new LinearLayout(context);
        bssidButtons.setOrientation(LinearLayout.HORIZONTAL);
        bssidButtons.setGravity(Gravity.START);
        same = smallButton(R.string.wifi_eviltwin_same, v -> bssid.setText(this.targetBssid));
        plusOne = smallButton(R.string.wifi_eviltwin_plus1,
                v -> bssid.setText(nextBssid(this.targetBssid)));
        random = smallButton(R.string.wifi_eviltwin_rand, v -> bssid.setText(randomBssid()));
        bssidButtons.addView(same);
        bssidButtons.addView(plusOne);
        bssidButtons.addView(random);
        body.addView(bssidButtons);

        body.addView(label(R.string.wifi_eviltwin_punter));
        punterIface = spinner(memberLabels());
        body.addView(punterIface);
        if (punterIface.getCount() > 1) punterIface.setSelection(punterIndex());

        body.addView(label(R.string.wifi_eviltwin_punts));
        // wifi4's default_punt_modes: CSA alone when PMF makes robust frames unviable, else all
        // three. Deauth and BTM are disabled in that case (their control is greyed in the TUI).
        deauth = check(R.string.wifi_eviltwin_deauth, !robustBlocked);
        csa = check(R.string.wifi_eviltwin_csa, true);
        btm = check(R.string.wifi_eviltwin_btm, !robustBlocked);
        deauth.setEnabled(!robustBlocked);
        btm.setEnabled(!robustBlocked);
        body.addView(deauth);
        body.addView(csa);
        body.addView(btm);

        body.addView(label(R.string.wifi_eviltwin_cycle));
        cycle = spinner(cycleLabels());
        cycle.setSelection(5);   // 30 seconds, wifi4's _DEFAULT_CYCLE
        body.addView(cycle);

        // Portal: instead of the WPA2 twin, host our own open AP and its page. That is the
        // combination a client can actually join and type into, so the page matters here.
        body.addView(label(R.string.wifi_eviltwin_portal));
        portal = check(R.string.wifi_eviltwin_portal_host, false);
        body.addView(portal);
        templateButton = new MaterialButton(context);
        templateButton.setText(R.string.portal_template_choose);
        templateButton.setTextSize(12);
        templateButton.setCornerRadius(dp(14));
        templateButton.setOnClickListener(v -> {
            if (templateListener != null) templateListener.run();
        });
        body.addView(templateButton);

        warning = new TextView(context);
        warning.setText(R.string.wifi_eviltwin_warn_same);
        warning.setTextColor(ContextCompat.getColor(context, R.color.stryker_accent));
        warning.setTextSize(12);
        warning.setVisibility(View.GONE);
        body.addView(warning);

        error = new TextView(context);
        error.setTextColor(ContextCompat.getColor(context, R.color.red));
        error.setTextSize(12);
        error.setVisibility(View.GONE);
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

        twinIface.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Iface twin = hostAt(position);
                fillChannels(twin);
                syncBssid(twin);
                syncWarning();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // The selects are never blank while a host exists.
            }
        });
        punterIface.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                syncWarning();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // The punter select is never blank while a member exists.
            }
        });
        fillChannels(hostAt(0));
        syncBssid(hostAt(0));
        syncWarning();
    }

    void show() {
        if (!dialog.isShowing()) dialog.show();
    }

    void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    /** The screen opens the portal catalog; this sheet only keeps the result. */
    void setTemplateListener(Runnable listener) {
        this.templateListener = listener;
    }

    void setTemplate(File file, String labelText) {
        templateFile = file;
        templateButton.setText(labelText == null || labelText.isEmpty()
                ? context.getString(R.string.portal_template_choose) : labelText);
    }

    // ------------------------------------------------------------ modal wiring

    private void fillChannels(Iface twin) {
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        if (single) {
            // One card hosts the twin AND punts for it: the target's channel is the only one.
            labels.add(context.getString(R.string.wifi_eviltwin_channel_target, targetChannel));
            values.add(targetChannel);
        } else if (twin != null && !twin.channels.isEmpty()) {
            for (int value : twin.channels) {
                if (value <= 0) continue;
                // wifi4: a DFS channel is only usable where the target AP itself transmits.
                if (value != targetChannel && isDfs(value)) continue;
                String text = value == targetChannel
                        ? context.getString(R.string.wifi_eviltwin_channel_target, value)
                        : String.valueOf(value);
                if (!values.contains(value)) {
                    values.add(value);
                    labels.add(text);
                }
            }
        }
        if (values.isEmpty()) {
            labels.add(context.getString(R.string.wifi_eviltwin_channel_target, targetChannel));
            values.add(targetChannel);
        }
        int wanted = single ? targetChannel : decoyChannel(targetChannel);
        int selected = values.indexOf(wanted);
        if (selected < 0) selected = values.indexOf(targetChannel);
        if (selected < 0) selected = 0;

        channelValues.clear();
        channelValues.addAll(values);
        channel.setAdapter(adapter(labels));
        channel.setSelection(selected);
        channel.setEnabled(!single);
    }

    private void syncBssid(Iface twin) {
        boolean spoofable = twin != null && twin.spoofable;
        // A FIXED_MAC card (or one that cannot spoof) hosts under the address it already ACKs.
        bssid.setEnabled(spoofable);
        same.setEnabled(spoofable);
        plusOne.setEnabled(spoofable);
        random.setEnabled(spoofable);
        if (!spoofable) {
            String mac = twin != null && !twin.mac.isEmpty() ? twin.mac : targetBssid;
            bssid.setText(mac);
        }
    }

    private void syncWarning() {
        Iface twin = selected(twinIface, hosts);
        Iface punter = selected(punterIface, members);
        boolean sameIface = twin != null && punter != null && twin.name.equals(punter.name);
        warning.setVisibility(sameIface ? View.VISIBLE : View.GONE);
    }

    private void submit() {
        if (portal.isChecked()) {
            // No twin settings apply: opx_ap hosts an open AP under the target's SSID.
            try {
                JSONObject options = new JSONObject();
                options.put("portal", true);
                if (templateFile != null) {
                    options.put("template_path", templateFile.getAbsolutePath());
                }
                listener.onStart(options);
                dismiss();
            } catch (Exception e) {
                showError(R.string.wifi_eviltwin_bad_options);
            }
            return;
        }
        if (members.isEmpty()) {
            showError(R.string.wifi_eviltwin_iface_missing);
            return;
        }
        if (hosts.isEmpty()) {
            // wifi4's _start_eviltwin refusal, word for word.
            showError(R.string.wifi_eviltwin_no_host);
            return;
        }
        Iface twin = selected(twinIface, hosts);
        Iface punter = selected(punterIface, members);
        if (twin == null || punter == null) {
            showError(R.string.wifi_eviltwin_iface_missing);
            return;
        }
        String twinBssid = bssid.getText().toString().trim().toLowerCase(Locale.US);
        if (!twinBssid.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}")) {
            showError(R.string.wifi_eviltwin_bad_bssid);
            return;
        }
        int position = channel.getSelectedItemPosition();
        if (position < 0 || position >= channelValues.size()) {
            showError(R.string.wifi_eviltwin_bad_channel);
            return;
        }
        int twinChannel = channelValues.get(position);
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
        if (twinBssid.equals(targetBssid) && twinChannel == targetChannel) {
            showError(R.string.wifi_eviltwin_collide);
            return;
        }
        int selectedCycle = cycle.getSelectedItemPosition();
        if (selectedCycle < 0 || selectedCycle >= CYCLE_SECONDS.length) selectedCycle = 5;
        try {
            JSONObject options = new JSONObject();
            options.put("twin_bssid", twinBssid);
            options.put("twin_channel", twinChannel);
            options.put("twin_iface", twin.name);
            options.put("punt_iface", punter.name);
            // wifi4's _CYCLES: "Never" is punt_period_sec=None + punt_once=False (host only).
            if (selectedCycle == 0) {
                options.put("punt_period", JSONObject.NULL);
                options.put("punt_once", false);
            } else {
                options.put("punt_period", CYCLE_SECONDS[selectedCycle]);
                options.put("punt_once", CYCLE_ONCE[selectedCycle]);
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
        error.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------- the members

    private static Iface parse(JSONObject row) {
        if (row == null) return null;
        Iface iface = new Iface();
        iface.name = row.optString("name", "");
        if (iface.name.isEmpty()) return null;
        iface.label = row.optString("label", iface.name);
        iface.mac = row.optString("mac", "").toLowerCase(Locale.US);
        iface.host = row.optBoolean("host");
        iface.spoofable = row.optBoolean("spoofable");
        JSONArray channels = row.optJSONArray("channels");
        if (channels != null) {
            for (int i = 0; i < channels.length(); i++) {
                int value = channels.optInt(i, 0);
                if (value > 0) iface.channels.add(value);
            }
        }
        return iface;
    }

    /** The modal shows the card's product name, with its array name when they differ. */
    private static String display(Iface iface) {
        if (iface.label == null || iface.label.isEmpty() || iface.label.equals(iface.name)) {
            return iface.name;
        }
        return iface.label + " · " + iface.name;
    }

    private List<String> hostLabels() {
        List<String> labels = new ArrayList<>();
        for (Iface iface : hosts) labels.add(display(iface));
        return labels;
    }

    private List<String> memberLabels() {
        List<String> labels = new ArrayList<>();
        for (Iface iface : members) labels.add(display(iface));
        return labels;
    }

    /** wifi4's modal starts the punter on the first member that is not the twin. */
    private int punterIndex() {
        Iface twin = hostAt(0);
        for (int i = 0; i < members.size(); i++) {
            if (twin == null || !members.get(i).name.equals(twin.name)) return i;
        }
        return 0;
    }

    private Iface hostAt(int position) {
        return position >= 0 && position < hosts.size() ? hosts.get(position) : null;
    }

    private static Iface selected(Spinner spinner, List<Iface> from) {
        int position = spinner.getSelectedItemPosition();
        return position >= 0 && position < from.size() ? from.get(position) : null;
    }

    private List<String> cycleLabels() {
        List<String> labels = new ArrayList<>();
        labels.add(context.getString(R.string.wifi_eviltwin_cycle_never));
        labels.add(context.getString(R.string.wifi_eviltwin_cycle_once));
        for (int i = 2; i < CYCLE_SECONDS.length; i++) {
            labels.add(context.getString(R.string.wifi_eviltwin_cycle_seconds, CYCLE_SECONDS[i]));
        }
        return labels;
    }

    // ----------------------------------------------------------------- geometry

    /** wifi4's {@code csa_target_channel}: a valid decoy in the target AP's own band. */
    private static int decoyChannel(int targetChannel) {
        if (targetChannel > 14) return targetChannel == 36 ? 40 : 36;
        return targetChannel == 1 ? 6 : 1;
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

    // ------------------------------------------------------------------ widgets

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
        spinner.setAdapter(adapter(values));
        return spinner;
    }

    private ArrayAdapter<String> adapter(List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private MaterialButton smallButton(int text, View.OnClickListener listener) {
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
