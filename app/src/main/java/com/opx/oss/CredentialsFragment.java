package com.opx.oss;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Saved passwords screen, Stryker's vault: every PSK, WPS PIN and WEP key wifi4 wrote beside
 * its captures, with the network, how it was recovered and a copy action.
 * <p>
 * The engine owns these files, so the screen asks it for the list and never reads a path itself.
 */
public class CredentialsFragment extends Fragment
        implements WifiBridge.Listener, OpxService.Listener, CredentialAdapter.Listener {

    private final CredentialAdapter adapter = new CredentialAdapter(this);

    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private View listCard;
    private View emptyCard;
    private TextView summaryTitle;
    private TextView storagePath;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_credentials, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        refresh = view.findViewById(R.id.cred_refresh);
        list = view.findViewById(R.id.cred_list);
        listCard = view.findViewById(R.id.cred_list_card);
        emptyCard = view.findViewById(R.id.cred_empty_card);
        summaryTitle = view.findViewById(R.id.cred_summary_title);
        storagePath = view.findViewById(R.id.cred_storage_path);

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        refresh.setOnRefreshListener(() -> {
            refresh.setRefreshing(false);
            requestCredentials();
        });
        view.findViewById(R.id.cred_retry_btn).setOnClickListener(v -> requestCredentials());
    }

    @Override
    public void onResume() {
        super.onResume();
        WifiBridge.get(requireContext()).addListener(this);
        OpxService.addListener(this);
        OpxService.start(requireContext());
        WifiBridge bridge = WifiBridge.get(requireContext());
        bridge.start();
        requestCredentials();
    }

    @Override
    public void onPause() {
        super.onPause();
        WifiBridge.get(requireContext()).removeListener(this);
        OpxService.removeListener(this);
    }

    private void requestCredentials() {
        if (OpxService.getState() != OpxService.STATE_READY) {
            Snackbar.make(requireView(), R.string.wifi_engine_pending, Snackbar.LENGTH_LONG).show();
            return;
        }
        WifiBridge bridge = WifiBridge.get(requireContext());
        bridge.start();
        bridge.requestCaptures();
    }

    // ------------------------------------------------------------- engine events

    @Override
    public void onEngineEvent(JSONObject event) {
        String name = event.optString("event");
        if ("captures".equals(name)) {
            render(event);
        } else if ("capture_saved".equals(name)) {
            requestCredentials();
        } else if ("error".equals(name) && isAdded()) {
            String message = event.optString("message");
            if (!message.isEmpty()) {
                Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show();
            }
        }
    }

    private void render(JSONObject event) {
        if (!isAdded() || summaryTitle == null) return;
        JSONArray credentials = event.optJSONArray("credentials");
        adapter.setItems(credentials);
        int count = credentials == null ? 0 : credentials.length();
        summaryTitle.setText(count == 0
                ? getString(R.string.cred_status_empty)
                : getString(R.string.cred_status_count, count));
        String directory = event.optString("directory", "");
        storagePath.setText(directory.isEmpty() ? getString(R.string.hs_storage_path) : directory);
        listCard.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        emptyCard.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onEngineStopped(String reason) {
        if (!isAdded()) return;
        Snackbar.make(requireView(), getString(R.string.wifi_usb_stopped, reason),
                Snackbar.LENGTH_LONG).show();
    }

    @Override
    public void onEngineStateChanged() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (OpxService.getState() == OpxService.STATE_READY) requestCredentials();
        });
    }

    // ------------------------------------------------------------ adapter callbacks

    @Override
    public void onOpen(JSONObject credential) {
        showDetails(credential);
    }

    @Override
    public void onCopy(String value) {
        copy(value);
    }

    /**
     * Stryker's details sheet: every field the saver wrote, plus how the credential was recovered -
     * the WPS exchange, the capture it was cracked from, or the WEP replay.
     */
    private void showDetails(JSONObject credential) {
        if (!isAdded()) return;
        Context context = requireContext();
        String kind = credential.optString("kind");
        String ssid = credential.optString("ssid");
        String bssid = credential.optString("bssid");
        String value = credential.optString("value");
        String pin = credential.optString("pin");
        String source = credential.optString("id");
        String capture = credential.optString("capture");
        long when = credential.optLong("when");

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(8), dp(22), dp(4));

        addField(body, getString(R.string.cred_field_ssid),
                ssid == null || ssid.isEmpty() ? "—" : ssid, false);
        addField(body, getString(R.string.cred_field_bssid),
                bssid == null || bssid.isEmpty() ? "—" : bssid.toUpperCase(Locale.US), true);
        addField(body, getString(R.string.cred_field_type),
                CredentialAdapter.kindLabel(context, kind), false);
        if (!value.isEmpty()) {
            addField(body, getString(R.string.cred_field_value), value, true);
        }
        if (!pin.isEmpty()) {
            addField(body, getString(R.string.cred_field_pin), pin, true);
        }
        if (when > 0) {
            addField(body, getString(R.string.cred_field_saved), savedAt(when), false);
        }
        if (!source.isEmpty()) {
            addField(body, getString(R.string.cred_field_source), source, true);
        }
        if (!capture.isEmpty()) {
            addField(body, getString(R.string.cred_field_capture), capture, true);
        }

        TextView howTitle = new TextView(context);
        howTitle.setText(R.string.cred_how_title);
        howTitle.setTextSize(11);
        howTitle.setTextColor(0xFF757575);
        howTitle.setLetterSpacing(0.08f);
        howTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(16);
        howTitle.setLayoutParams(titleParams);
        body.addView(howTitle);

        TextView how = new TextView(context);
        how.setText(howText(kind, capture));
        how.setTextSize(13);
        how.setTextColor(0xFF1C1C1C);
        how.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams howParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        howParams.topMargin = dp(4);
        how.setLayoutParams(howParams);
        body.addView(how);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.cred_detail_title)
                .setView(scroll)
                .setNegativeButton(R.string.cred_close, null)
                .create();
        String secret = !value.isEmpty() ? value : pin;
        if (!secret.isEmpty()) {
            dialog.setButton(DialogInterface.BUTTON_POSITIVE,
                    getString(value.isEmpty() ? R.string.cred_copy_pin : R.string.cred_copy_value),
                    (d, which) -> copy(secret));
        }
        dialog.show();
    }

    private void addField(LinearLayout body, String label, String value, boolean mono) {
        Context context = body.getContext();
        TextView name = new TextView(context);
        name.setText(label.toUpperCase(Locale.US));
        name.setTextSize(10);
        name.setTextColor(0xFF9E9E9E);
        name.setLetterSpacing(0.08f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dp(10);
        name.setLayoutParams(nameParams);
        body.addView(name);

        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(14);
        text.setTextColor(0xFF1C1C1C);
        text.setTextIsSelectable(true);
        if (mono) text.setTypeface(Typeface.MONOSPACE);
        body.addView(text);
    }

    private String howText(String kind, String capture) {
        switch (kind == null ? "" : kind) {
            case "wps_pin":
                return getString(R.string.cred_how_wps_pin);
            case "wps_pbc":
                return getString(R.string.cred_how_wps_pbc);
            case "wep":
                return getString(R.string.cred_how_wep);
            default:
                return capture == null || capture.isEmpty()
                        ? getString(R.string.cred_how_wpa_psk_no_capture)
                        : getString(R.string.cred_how_wpa_psk, capture);
        }
    }

    private static String savedAt(long epochSeconds) {
        return new SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())
                .format(new Date(epochSeconds * 1000L));
    }

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS", value));
        Toast.makeText(requireContext(), R.string.cred_copied, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
