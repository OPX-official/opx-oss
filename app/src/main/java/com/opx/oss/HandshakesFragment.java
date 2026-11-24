package com.opx.oss;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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

    private CrackDialog crackDialog;
    private String pendingWordlistName = "";
    private Uri pendingWordlist;

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
        crackDialog.show();
        WifiBridge.get(requireContext()).requestCrackTools();
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
