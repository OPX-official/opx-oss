package com.opx.oss;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * The Handshakes screen, laid out like Stryker's: a storage card with totals and the captures
 * wifi4 saved, grouped by day.
 * <p>
 * Everything comes from the engine: it lists its own capture directory and runs the crack, so
 * the screen never reads a path the runtime does not own.
 */
public class HandshakesFragment extends Fragment
        implements WifiBridge.Listener, OpxService.Listener, HandshakeAdapter.Listener {

    private final HandshakeAdapter adapter = new HandshakeAdapter(this);

    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private View emptyCard;
    private View listCard;
    private TextView statusTitle;
    private TextView storagePath;
    private TextView metaTotal;
    private TextView metaCracked;
    private TextView metaSize;
    private View keysTitle;
    private View keysCard;
    private LinearLayout keysList;

    private CrackDialog crackDialog;
    private CaptivePortalDialog portalDialog;
    private String pendingWordlistName = "";
    private Uri pendingWordlist;

    // AP crack mode: wifi4's Evil Twin hosts the AP, this screen runs its portal verification.
    private String apBssid = "";
    private String apName = "";
    private String apTemplate = "default";
    private String apPasswordToTry;

    private final ActivityResultLauncher<String[]> wordlistPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                pendingWordlist = uri;
                copyWordlist(uri);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_handshakes, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        refresh = view.findViewById(R.id.hs_refresh);
        list = view.findViewById(R.id.hs_list);
        emptyCard = view.findViewById(R.id.hs_empty_card);
        listCard = view.findViewById(R.id.hs_list_card);
        statusTitle = view.findViewById(R.id.hs_status_title);
        storagePath = view.findViewById(R.id.hs_storage_path);
        metaTotal = view.findViewById(R.id.hs_meta_total);
        metaCracked = view.findViewById(R.id.hs_meta_cracked);
        metaSize = view.findViewById(R.id.hs_meta_size);
        keysTitle = view.findViewById(R.id.hs_keys_title);
        keysCard = view.findViewById(R.id.hs_keys_card);
        keysList = view.findViewById(R.id.hs_keys_list);

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        refresh.setOnRefreshListener(() -> {
            refresh.setRefreshing(false);
            requestCaptures();
        });
        view.findViewById(R.id.hs_refresh_btn).setOnClickListener(v -> requestCaptures());
    }

    @Override
    public void onResume() {
        super.onResume();
        WifiBridge.get(requireContext()).addListener(this);
        OpxService.addListener(this);
        OpxService.start(requireContext());
        WifiBridge bridge = WifiBridge.get(requireContext());
        bridge.start();
        bridge.requestCaptures();
    }

    @Override
    public void onPause() {
        super.onPause();
        WifiBridge.get(requireContext()).removeListener(this);
        OpxService.removeListener(this);
    }

    @Override
    public void onDestroyView() {
        if (crackDialog != null) crackDialog.dismiss();
        crackDialog = null;
        if (portalDialog != null) portalDialog.dismiss();
        portalDialog = null;
        super.onDestroyView();
    }

    private void requestCaptures() {
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
        if (crackDialog != null) crackDialog.onEngineEvent(event);
        String name = event.optString("event");
        if ("captures".equals(name)) {
            render(event);
        } else if ("capture_saved".equals(name)) {
            requestCaptures();
        } else if ("crack_done".equals(name)) {
            // The recovered key is in the vault now: the row's CRACKED pill and the saved
            // passwords list both come from this same event.
            requestCaptures();
        } else if ("psk_verified".equals(name)) {
            onPasswordChecked(event);
        } else if ("attack_done".equals(name)) {
            if (portalDialog != null) {
                portalDialog.dismiss();
                portalDialog = null;
            }
            requestCaptures();
        } else if ("error".equals(name) && portalDialog != null) {
            String message = event.optString("message", "");
            portalDialog.result(false, message.isEmpty()
                    ? getString(R.string.wifi_captive_mismatch) : message);
        } else if ("error".equals(name) && crackDialog != null && crackDialog.isShowing()) {
            String message = event.optString("message");
            if (!message.isEmpty()) Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show();
        }
    }

    private void render(JSONObject event) {
        if (!isAdded()) return;
        JSONArray captures = event.optJSONArray("captures");
        adapter.setCaptures(captures);
        int count = event.optInt("count");
        int cracked = event.optInt("cracked");
        long bytes = event.optLong("bytes");
        String directory = event.optString("directory", "");

        statusTitle.setText(count == 0
                ? getString(R.string.hs_status_empty)
                : getString(R.string.hs_status_count, count, cracked));
        storagePath.setText(directory.isEmpty() ? getString(R.string.hs_storage_path) : directory);
        metaTotal.setText(String.valueOf(count));
        metaCracked.setText(String.valueOf(cracked));
        metaSize.setText(humanSize(bytes));
        emptyCard.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        listCard.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        renderCredentials(event.optJSONArray("credentials"));
    }

    /** Recovered WPS/WPA/WEP keys, written by wifi4's own savers beside the captures. */
    private void renderCredentials(JSONArray credentials) {
        if (keysList == null) return;
        keysList.removeAllViews();
        int count = credentials == null ? 0 : credentials.length();
        keysTitle.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        keysCard.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < count; i++) {
            JSONObject credential = credentials.optJSONObject(i);
            if (credential == null) continue;
            String ssid = credential.optString("ssid");
            String bssid = credential.optString("bssid");
            String value = credential.optString("value");
            String pin = credential.optString("pin");
            String kind = credential.optString("kind");
            String label = credentialLabel(kind);
            String shown = value.isEmpty() && !pin.isEmpty()
                    ? getString(R.string.hs_key_pin, pin) : value;
            if (!value.isEmpty() && !pin.isEmpty()) {
                shown = value + " · " + getString(R.string.hs_key_pin, pin);
            }

            TextView row = new TextView(requireContext());
            row.setText(String.format(Locale.US, "%s  ·  %s\n%s  ·  %s",
                    ssid == null || ssid.isEmpty() ? "—" : ssid, label,
                    bssid == null ? "" : bssid.toUpperCase(Locale.US), shown));
            row.setTextColor(Color.parseColor("#2E7D32"));
            row.setTextSize(12);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            String copyValue = value.isEmpty() ? pin : value;
            if (!copyValue.isEmpty()) {
                row.setOnClickListener(v -> copy(copyValue));
            }
            keysList.addView(row);
            if (i < count - 1) {
                View divider = new View(requireContext());
                divider.setBackgroundColor(Color.parseColor("#22000000"));
                keysList.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
    }

    private String credentialLabel(String kind) {
        switch (kind) {
            case "wps_pin":
                return getString(R.string.hs_key_wps_pin);
            case "wps_pbc":
                return getString(R.string.hs_key_wps_pbc);
            case "wep":
                return getString(R.string.hs_key_wep);
            default:
                return getString(R.string.hs_key_wpa_psk);
        }
    }

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS", value));
            Toast.makeText(requireContext(), R.string.hs_key_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
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
            if (OpxService.getState() == OpxService.STATE_READY) requestCaptures();
        });
    }

    // ---------------------------------------------------------------- cracking

    @Override
    public void onCrack(JSONObject capture) {
        crackDialog = new CrackDialog(requireContext(), capture, this::pickWordlist);
        crackDialog.setApListener(this::startAp);
        crackDialog.show();
        WifiBridge.get(requireContext()).requestCrackTools();
    }

    // --------------------------------------------------------------- AP mode

    /**
     * The crack dialog's AP mode. A password typed there is checked against the saved handshake
     * first (wifi4's vault); only when it does not match does the twin AP and its portal open.
     */
    private void startAp(String bssid, String ssid, String password, String template) {
        if (!isAdded()) return;
        if (OpxService.getState() != OpxService.STATE_READY) {
            Snackbar.make(requireView(), R.string.wifi_engine_pending, Snackbar.LENGTH_LONG).show();
            return;
        }
        apBssid = bssid == null ? "" : bssid;
        apName = ssid == null || ssid.isEmpty() ? "" : ssid;
        apTemplate = template == null || template.isEmpty() ? "default" : template;
        WifiBridge bridge = WifiBridge.get(requireContext());
        bridge.start();
        if (password != null && !password.isEmpty()) {
            apPasswordToTry = password;
            Snackbar.make(requireView(), R.string.hc_ap_verifying, Snackbar.LENGTH_LONG).show();
            bridge.verifyPsk(apBssid, password);
            return;
        }
        openPortal();
    }

    /** Start wifi4's Evil Twin and the sheet a client's submitted password is checked in. */
    private void openPortal() {
        if (!isAdded()) return;
        WifiBridge bridge = WifiBridge.get(requireContext());
        portalDialog = new CaptivePortalDialog(requireContext(), apName, apTemplate,
                password -> bridge.verifyPsk(apBssid, password));
        portalDialog.show();
        JSONObject options = new JSONObject();
        try {
            options.put("ssid", apName).put("template", apTemplate).put("portal", true);
        } catch (org.json.JSONException ignored) {
            // Three plain values.
        }
        // opx_ap hosts the open AP, its DHCP/DNS and the portal; wifi4 stays the backend.
        bridge.attack("portal", apBssid, options);
    }

    /** A password was checked against the capture; only a match closes the portal. */
    private void onPasswordChecked(JSONObject event) {
        boolean ok = event.optBoolean("ok");
        if (apPasswordToTry != null) {
            // The AP settings' own candidate: cracked outright, or go on to the portal.
            String tried = apPasswordToTry;
            apPasswordToTry = null;
            if (ok) {
                Snackbar.make(requireView(), getString(R.string.hc_ap_match, tried),
                        Snackbar.LENGTH_LONG).show();
                requestCaptures();
            } else {
                Snackbar.make(requireView(), R.string.hc_ap_nomatch, Snackbar.LENGTH_LONG).show();
                openPortal();
            }
            return;
        }
        if (portalDialog == null) return;
        if (ok) {
            portalDialog.result(true, getString(R.string.wifi_captive_match));
            // The portal comes down with the campaign that hosts it.
            WifiBridge.get(requireContext()).stopAttack();
        } else {
            portalDialog.result(false, getString(R.string.wifi_captive_mismatch));
        }
    }

    private void pickWordlist() {
        try {
            wordlistPicker.launch(new String[]{"text/*", "application/octet-stream"});
        } catch (RuntimeException e) {
            Snackbar.make(requireView(), R.string.hc_no_picker, Snackbar.LENGTH_LONG).show();
        }
    }

    /**
     * Copies the picked document into the app's own files, so the engine (same uid, different
     * process) can read a stable path even after the picker's grant expires.
     */
    private void copyWordlist(Uri uri) {
        ContentResolver resolver = requireContext().getContentResolver();
        String name = "wordlist.txt";
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String picked = cursor.getString(index);
                    if (picked != null && !picked.isEmpty()) name = picked;
                }
            }
        } catch (RuntimeException ignored) {
            // A provider without metadata still gives a readable stream.
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        File directory = new File(requireContext().getFilesDir(), "wordlists");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Snackbar.make(requireView(), R.string.hc_wordlist_failed, Snackbar.LENGTH_LONG).show();
            return;
        }
        File target = new File(directory, safe);
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("no stream");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        } catch (IOException | RuntimeException e) {
            Snackbar.make(requireView(), R.string.hc_wordlist_failed, Snackbar.LENGTH_LONG).show();
            return;
        }
        pendingWordlist = null;
        pendingWordlistName = safe;
        if (crackDialog != null) crackDialog.setWordlist(safe, target.getAbsolutePath());
    }
}
