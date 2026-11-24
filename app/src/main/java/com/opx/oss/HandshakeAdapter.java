package com.opx.oss;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The saved-capture list, shaped like Stryker's Handshakes adapter: day headers, an icon tile per
 * capture, the SSID, a size/MAC line, a kind or CRACKED pill, and the crack action.
 * <p>
 * Rows are the engine's {@code captures} entries, so a capture that has no key yet offers the
 * crack dialog and one that has a key copies it.
 */
final class HandshakeAdapter extends RecyclerView.Adapter<HandshakeAdapter.Holder> {

    interface Listener {
        void onCrack(JSONObject capture);
    }

    private static final class Row {
        final String header;
        final JSONObject capture;

        Row(String header, JSONObject capture) {
            this.header = header;
            this.capture = capture;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Listener listener;

    HandshakeAdapter(Listener listener) {
        this.listener = listener;
    }

    void setCaptures(JSONArray captures) {
        rows.clear();
        String current = null;
        if (captures != null) {
            for (int i = 0; i < captures.length(); i++) {
                JSONObject capture = captures.optJSONObject(i);
                if (capture == null) continue;
                String day = dayLabel(capture.optLong("when"));
                if (!day.equals(current)) {
                    current = day;
                    rows.add(new Row(day, null));
                }
                rows.add(new Row(null, capture));
            }
        }
        notifyDataSetChanged();
    }

    private static String dayLabel(long epochSeconds) {
        if (epochSeconds <= 0) return "";
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(epochSeconds * 1000L);
        Calendar now = Calendar.getInstance();
        if (sameDay(then, now)) return "Today";
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, now)) return "Yesterday";
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(epochSeconds * 1000L));
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_handshake, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Row row = rows.get(position);
        if (row.capture == null) {
            holder.item.setVisibility(View.GONE);
            holder.divider.setVisibility(View.GONE);
            holder.dateHeader.setVisibility(View.VISIBLE);
            holder.dateHeader.setText(row.header);
            return;
        }
        holder.item.setVisibility(View.VISIBLE);
        holder.dateHeader.setVisibility(View.GONE);
        holder.divider.setVisibility(position == rows.size() - 1 ? View.GONE : View.VISIBLE);

        JSONObject capture = row.capture;
        Context context = holder.itemView.getContext();
        String id = capture.optString("id");
        String ssid = capture.optString("ssid");
        String bssid = capture.optString("bssid");
        boolean pmkid = "pmkid".equals(capture.optString("kind"));
        JSONArray keys = capture.optJSONArray("keys");
        String key = keys != null && keys.length() > 0 ? keys.optString(0) : "";

        holder.name.setText(ssid == null || ssid.isEmpty() ? id : ssid);
        holder.icon.setImageResource(pmkid ? R.drawable.latest_wifi
                : R.drawable.handshake_interface);
        holder.icon.setColorFilter(Color.parseColor(pmkid ? "#0277BD" : "#2E7D32"));

        String size = humanSize(capture.optLong("size"));
        String mac = bssid == null || bssid.isEmpty() ? "" : bssid.toUpperCase(Locale.US);
        if (key.isEmpty()) {
            holder.progress.setText(mac.isEmpty() ? size : size + "  ·  " + mac);
            holder.progress.setTextColor(Color.parseColor("#9E9E9E"));
            holder.pill.setText(pmkid ? R.string.hs_kind_pmkid : R.string.hs_kind_handshake);
            holder.pill.setTextColor(Color.parseColor(pmkid ? "#0277BD" : "#2E7D32"));
        } else {
            holder.progress.setText(context.getString(R.string.hs_password, key));
            holder.progress.setTextColor(Color.parseColor("#388E3C"));
            holder.pill.setText(R.string.hs_state_cracked);
            holder.pill.setTextColor(Color.parseColor("#388E3C"));
        }
        holder.pill.setVisibility(View.VISIBLE);

        holder.item.setOnClickListener(v -> {
            if (!key.isEmpty()) copy(context, key);
            else if (listener != null) listener.onCrack(capture);
        });
        holder.brute.setOnClickListener(v -> {
            if (listener != null) listener.onCrack(capture);
        });
        holder.overflow.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(context, holder.overflow);
            if (!key.isEmpty()) menu.getMenu().add(0, 1, 0, R.string.hs_password_copy);
            menu.getMenu().add(0, 2, 1, R.string.hs_action_crack);
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    copy(context, key);
                    return true;
                }
                if (item.getItemId() == 2 && listener != null) {
                    listener.onCrack(capture);
                    return true;
                }
                return false;
            });
            menu.show();
        });
    }

    private static void copy(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("OPX OSS", text));
            Toast.makeText(context, R.string.hs_password_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View item;
        final TextView dateHeader;
        final View divider;
        final ImageView icon;
        final TextView name;
        final TextView progress;
        final TextView pill;
        final ImageView brute;
        final ImageView overflow;

        Holder(@NonNull View itemView) {
            super(itemView);
            item = itemView.findViewById(R.id.hs_item);
            dateHeader = itemView.findViewById(R.id.hs_date_header);
            divider = itemView.findViewById(R.id.hs_item_divider);
            icon = itemView.findViewById(R.id.hs_icon);
            name = itemView.findViewById(R.id.hs_name);
            progress = itemView.findViewById(R.id.hs_progress);
            pill = itemView.findViewById(R.id.hs_state_chip);
            brute = itemView.findViewById(R.id.hs_brute);
            overflow = itemView.findViewById(R.id.hs_overflow);
        }
    }
}
