package com.opx.oss;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The crack dialog for one saved handshake: wifi4's own tools on the left of the choice, its own
 * password sources below, then progress and the recovered key.
 * <p>
 * CPU Crack always exists (it is pure Python in wifi4). Hashcat is offered only when the engine
 * reports a hashcat executable, and says why when it does not — a button that cannot run is
 * worse than no button.
 */
final class CrackDialog {

    interface Listener {
        /** The user wants a wordlist: the screen opens the document picker. */
        void onPickWordlist();
    }

    /** AP mode: the screen starts wifi4's Evil Twin and its captive portal. */
    interface ApListener {
        void onStartAp(String bssid, String ssid, String password, String templatePath,
                       String iface);
    }

    /** The screen opens the portal catalog for this dialog. */
    interface TemplateListener {
        void onPickTemplate();
    }

    private final Context context;
    private final Dialog dialog;
    private final Listener listener;
    private final JSONObject capture;

    private final TextView status;
    private final TextView captureText;
    private final MaterialCardView cpuCard;
    private final MaterialCardView hashcatCard;
    private final MaterialCardView apCard;
    private final LinearLayout apOptions;
    private final Spinner apIface;
    private final List<String> apIfaceNames = new ArrayList<>();
    private final EditText apName;
    private final EditText apPassword;
    private final MaterialButton apTemplate;
    private final TextView sourcesTitle;
    private final LinearLayout sourcesGroup;
    private final MaterialCardView wordlistCard;
    private final TextView cpuDesc;
    private final TextView hashcatDesc;
    private final MaterialSwitch defaultPasswords;
    private final MaterialSwitch ssidPasswords;
    private final MaterialSwitch probedPasswords;
    private final TextView wordlistName;
    private final TextView wordlistMeta;
    private final LinearProgressIndicator progress;
    private final MaterialCardView resultsCard;
    private final TextView results;
    private final MaterialButton run;
    private final MaterialButton stop;

    private String tool = "cpu_crack";
    private String wordlistPath = "";
    private boolean running;
    private boolean hashcatAvailable;
    private String hashcatReason = "";
    private ApListener apListener;
    private TemplateListener templateListener;
    private String apTemplatePath = "";

    CrackDialog(Context context, JSONObject capture, Listener listener) {
        this.context = context;
        this.capture = capture;
        this.listener = listener;
        this.dialog = new Dialog(context);
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_crack, null);
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCancelable(true);

        status = content.findViewById(R.id.hc_status);
        captureText = content.findViewById(R.id.hc_capture);
        cpuCard = content.findViewById(R.id.hc_tool_cpu);
        hashcatCard = content.findViewById(R.id.hc_tool_hashcat);
        apCard = content.findViewById(R.id.hc_tool_ap);
        apOptions = content.findViewById(R.id.hc_ap_options);
        apIface = content.findViewById(R.id.hc_ap_iface);
        apName = content.findViewById(R.id.hc_ap_name);
        apPassword = content.findViewById(R.id.hc_ap_password);
        apTemplate = content.findViewById(R.id.hc_ap_template);
        sourcesTitle = content.findViewById(R.id.hc_sources_title);
        sourcesGroup = content.findViewById(R.id.hc_sources_group);
        wordlistCard = content.findViewById(R.id.hc_wordlist_card);
        cpuDesc = content.findViewById(R.id.hc_tool_cpu_desc);
        hashcatDesc = content.findViewById(R.id.hc_tool_hashcat_desc);
        defaultPasswords = content.findViewById(R.id.hc_default_passwords);
        ssidPasswords = content.findViewById(R.id.hc_ssid_passwords);
        probedPasswords = content.findViewById(R.id.hc_probed_passwords);
        wordlistName = content.findViewById(R.id.hc_wordlist_name);
        wordlistMeta = content.findViewById(R.id.hc_wordlist_meta);
        progress = content.findViewById(R.id.hc_progress);
        resultsCard = content.findViewById(R.id.hc_results_card);
        results = content.findViewById(R.id.hc_results);
        run = content.findViewById(R.id.hc_btn_run);
        stop = content.findViewById(R.id.hc_btn_stop);

        String ssid = capture.optString("ssid");
        String bssid = capture.optString("bssid");
        boolean pmkid = "pmkid".equals(capture.optString("kind"));
        captureText.setText(String.format(Locale.US, "%s · %s · %s",
                ssid == null || ssid.isEmpty() ? capture.optString("id") : ssid,
                bssid == null ? "" : bssid.toUpperCase(Locale.US),
                pmkid ? "PMKID" : "handshake"));
        status.setText(R.string.hc_subtitle);

        // AP mode's defaults: the captured network's name and wifi4's default portal page.
        ((TextView) content.findViewById(R.id.hc_tool_ap_desc)).setText(R.string.hc_tool_ap_desc);
        apName.setText(ssid == null ? "" : ssid);
        apTemplate.setText(R.string.portal_template_choose);
        // Category then page; only the catalog's "Custom HTML file…" reaches the picker.
        apTemplate.setOnClickListener(v -> PortalTemplatePicker.show(context,
                new PortalTemplatePicker.Listener() {
                    @Override
                    public void onPicked(File file, String label) {
                        setTemplate(file, label);
                    }

                    @Override
                    public void onCustom() {
                        if (templateListener != null) templateListener.onPickTemplate();
                    }
                }));

        cpuCard.setOnClickListener(v -> selectTool("cpu_crack"));
        apCard.setOnClickListener(v -> selectTool("ap"));
        hashcatCard.setOnClickListener(v -> {
            if (hashcatAvailable) selectTool("hashcat");
            else if (!hashcatReason.isEmpty()) Toast.makeText(context, hashcatReason, Toast.LENGTH_SHORT).show();
        });
        wordlistCard.setOnClickListener(v -> {
            if (listener != null) listener.onPickWordlist();
        });
        content.findViewById(R.id.hc_btn_close).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.hc_copy).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS", results.getText()));
                Toast.makeText(context, R.string.hc_copied, Toast.LENGTH_SHORT).show();
            }
        });
        run.setOnClickListener(v -> start());
        stop.setOnClickListener(v -> {
            if (running) {
                stop.setText(R.string.hc_stopping);
                WifiBridge.get(context).stopCrack();
            }
        });

        if (capture.optJSONArray("keys") != null && capture.optJSONArray("keys").length() > 0) {
            JSONArray keys = capture.optJSONArray("keys");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < keys.length(); i++) {
                if (text.length() > 0) text.append('\n');
                text.append(keys.optString(i));
            }
            showResults(true, text.toString(), context.getString(R.string.hc_already_cracked));
        }
        selectTool("cpu_crack");
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

    /** The screen's picker returned a wordlist; the engine reads the copied file directly. */
    void setWordlist(String name, String path) {
        wordlistPath = path == null ? "" : path;
        wordlistName.setText(name == null || name.isEmpty() ? name : name);
        wordlistMeta.setText(path == null ? "" : path);
    }

    /** The screen's AP handler; the dialog only collects the settings for it. */
    void setApListener(ApListener listener) {
        this.apListener = listener;
    }

    void setTemplateListener(TemplateListener listener) {
        this.templateListener = listener;
    }

    /** wifi4's pooled cards: the AP hosts on the one selected here. */
    void setInterfaces(JSONArray interfaces) {
        apIfaceNames.clear();
        List<String> labels = new ArrayList<>();
        if (interfaces != null) {
            for (int i = 0; i < interfaces.length(); i++) {
                JSONObject info = interfaces.optJSONObject(i);
                if (info == null) continue;
                String name = info.optString("name", "");
                if (name.isEmpty()) continue;
                apIfaceNames.add(name);
                String label = info.optString("label", "");
                labels.add(label.isEmpty() || label.equals(name) ? name : label + " · " + name);
            }
        }
        if (labels.isEmpty()) {
            apIfaceNames.add("");
            labels.add(context.getString(R.string.hc_ap_iface_default));
        }
        apIface.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
                labels));
        ((ArrayAdapter<?>) apIface.getAdapter()).setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
    }

    /** The picked page; an empty path keeps opx_ap's built-in sign-in page. */
    void setTemplate(File file, String label) {
        apTemplatePath = file == null ? "" : file.getAbsolutePath();
        apTemplate.setText(label == null || label.isEmpty()
                ? context.getString(R.string.portal_template_choose) : label);
    }

    private void selectTool(String name) {
        tool = name;
        boolean ap = "ap".equals(name);
        tint(cpuCard, "cpu_crack".equals(name));
        tint(hashcatCard, "hashcat".equals(name));
        tint(apCard, ap);
        // AP mode has no password sources: the client supplies the password to the portal.
        apOptions.setVisibility(ap ? View.VISIBLE : View.GONE);
        sourcesTitle.setVisibility(ap ? View.GONE : View.VISIBLE);
        sourcesGroup.setVisibility(ap ? View.GONE : View.VISIBLE);
        wordlistCard.setVisibility(ap ? View.GONE : View.VISIBLE);
        run.setText(ap ? R.string.hc_ap_start : R.string.hc_run);
    }

    private void tint(MaterialCardView card, boolean selected) {
        card.setStrokeWidth(selected ? 2 : 0);
        card.setStrokeColor(ContextCompat.getColor(context, R.color.stryker_accent));
        card.setCardBackgroundColor(ContextCompat.getColor(context,
                selected ? R.color.itemcolor : R.color.light_contrast));
    }

    private void start() {
        if (running) return;
        if ("ap".equals(tool)) {
            String name = apName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(context, R.string.hc_ap_name_missing, Toast.LENGTH_SHORT).show();
                return;
            }
            status.setText(R.string.hc_ap_starting);
            int ifacePosition = apIface.getSelectedItemPosition();
            String iface = ifacePosition >= 0 && ifacePosition < apIfaceNames.size()
                    ? apIfaceNames.get(ifacePosition) : "";
            if (apListener != null) {
                apListener.onStartAp(capture.optString("bssid"), name,
                        apPassword.getText().toString(), apTemplatePath, iface);
            }
            dialog.dismiss();
            return;
        }
        List<String> wordlists = new ArrayList<>();
        if (!wordlistPath.isEmpty()) wordlists.add(wordlistPath);
        if (!defaultPasswords.isChecked() && !ssidPasswords.isChecked()
                && !probedPasswords.isChecked() && wordlists.isEmpty()) {
            Toast.makeText(context, R.string.hc_no_source, Toast.LENGTH_LONG).show();
            return;
        }
        running = true;
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        resultsCard.setVisibility(View.GONE);
        status.setText(R.string.hc_starting);
        stop.setVisibility(View.VISIBLE);
        stop.setText(R.string.hc_stop);
        run.setEnabled(false);
        WifiBridge.get(context).crack(capture.optString("id"), tool, wordlists,
                defaultPasswords.isChecked(), ssidPasswords.isChecked(),
                probedPasswords.isChecked());
    }

    /** Every engine event while this dialog is open. */
    void onEngineEvent(JSONObject event) {
        String name = event.optString("event");
        if ("crack_tools".equals(name)) {
            JSONArray tools = event.optJSONArray("tools");
            if (tools == null) return;
            for (int i = 0; i < tools.length(); i++) {
                JSONObject entry = tools.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("tool");
                String description = entry.optString("description", "");
                if ("hashcat".equals(id)) {
                    hashcatAvailable = entry.optBoolean("available");
                    hashcatReason = entry.optString("reason", "");
                    hashcatDesc.setText(hashcatAvailable ? description : hashcatReason);
                    hashcatCard.setAlpha(hashcatAvailable ? 1f : 0.45f);
                } else if ("cpu_crack".equals(id)) {
                    cpuDesc.setText(description);
                }
                JSONObject defaults = entry.optJSONObject("default_passwords");
                if (defaults != null) {
                    boolean available = defaults.optBoolean("available");
                    int count = defaults.optInt("count");
                    defaultPasswords.setEnabled(available);
                    if (!available) defaultPasswords.setChecked(false);
                    defaultPasswords.setText(available && count > 0
                            ? context.getString(R.string.hc_default_passwords_count, count)
                            : context.getString(R.string.hc_default_passwords));
                }
            }
        } else if ("crack_started".equals(name)) {
            if (!event.optString("capture").equals(capture.optString("id"))) return;
            running = true;
            status.setText(R.string.hc_running);
        } else if ("crack_stopping".equals(name)) {
            status.setText(R.string.hc_stopping);
        } else if ("crack_progress".equals(name)) {
            if (!event.optString("capture").equals(capture.optString("id"))) return;
            Double percent = event.isNull("percent") ? null : event.optDouble("percent");
            if (percent == null || Double.isNaN(percent)) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setProgressCompat((int) Math.max(0, Math.min(100, percent)), true);
            }
            String message = event.optString("message", "");
            String detail = event.optString("detail", "");
            status.setText(detail.isEmpty() ? message
                    : String.format("%s · %s", message, detail));
        } else if ("crack_done".equals(name)) {
            if (!event.optString("capture").equals(capture.optString("id"))) return;
            running = false;
            progress.setVisibility(View.GONE);
            stop.setVisibility(View.GONE);
            run.setEnabled(true);
            JSONArray keys = event.optJSONArray("keys");
            StringBuilder text = new StringBuilder();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    if (text.length() > 0) text.append('\n');
                    text.append(keys.optString(i));
                }
            }
            boolean ok = event.optBoolean("ok") && text.length() > 0;
            if (ok) {
                showResults(true, text.toString(), context.getString(R.string.hc_done_ok));
            } else {
                String message = event.optString("message", "");
                showResults(false, message.isEmpty()
                        ? context.getString(R.string.hc_done_fail) : message,
                        context.getString(R.string.hc_done_fail));
            }
        }
    }

    private void showResults(boolean ok, String text, String headline) {
        resultsCard.setVisibility(View.VISIBLE);
        results.setText(text);
        results.setTextColor(Color.parseColor(ok ? "#7CFC9B" : "#FF8A80"));
        status.setText(headline);
    }
}
