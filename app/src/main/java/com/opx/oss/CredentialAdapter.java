package com.opx.oss;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One row per recovered credential, the way Stryker's saved-password list draws them: the network,
 * its type and BSSID, the secret itself and a copy button, with the details a tap away.
 */
public class CredentialAdapter extends RecyclerView.Adapter<CredentialAdapter.Holder> {

    interface Listener {
        void onOpen(JSONObject credential);

        void onCopy(String value);
    }

    private final List<JSONObject> rows = new ArrayList<>();
    private final Listener listener;

    CredentialAdapter(Listener listener) {
        this.listener = listener;
    }

    void setItems(JSONArray credentials) {
        rows.clear();
        if (credentials != null) {
            for (int i = 0; i < credentials.length(); i++) {
                JSONObject credential = credentials.optJSONObject(i);
                if (credential != null) rows.add(credential);
            }
        }
        notifyDataSetChanged();
    }

    static String kindLabel(Context context, String kind) {
        switch (kind == null ? "" : kind) {
            case "wps_pin":
                return context.getString(R.string.hs_key_wps_pin);
            case "wps_pbc":
                return context.getString(R.string.hs_key_wps_pbc);
            case "wep":
                return context.getString(R.string.hs_key_wep);
            default:
                return context.getString(R.string.hs_key_wpa_psk);
        }
    }

    /** What the copy button puts on the clipboard: the PSK/key when there is one, else the PIN. */
    static String secretOf(JSONObject credential) {
        String value = credential.optString("value");
        return value.isEmpty() ? credential.optString("pin") : value;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_credential, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        JSONObject credential = rows.get(position);
        Context context = holder.itemView.getContext();
        String kind = credential.optString("kind");
        String ssid = credential.optString("ssid");
        String bssid = credential.optString("bssid");
        String value = credential.optString("value");
        String pin = credential.optString("pin");
        String id = credential.optString("id");

        holder.ssid.setText(ssid == null || ssid.isEmpty()
                ? (id.isEmpty() ? "—" : id) : ssid);

        StringBuilder meta = new StringBuilder(kindLabel(context, kind));
        if (bssid != null && !bssid.isEmpty()) {
            meta.append("  ·  ").append(bssid.toUpperCase(Locale.US));
        }
        long when = credential.optLong("when");
        if (when > 0) meta.append("  ·  ").append(day(when));
        holder.meta.setText(meta);

        if (!value.isEmpty()) {
            holder.value.setText(value);
            holder.value.setTextColor(Color.parseColor("#2E7D32"));
        } else if (!pin.isEmpty()) {
            holder.value.setText(context.getString(R.string.hs_key_pin, pin));
            holder.value.setTextColor(Color.parseColor("#6A1B9A"));
        } else {
            holder.value.setText("");
            holder.value.setTextColor(Color.parseColor("#9E9E9E"));
        }

        holder.icon.setImageResource(iconRes(kind));
        holder.icon.setColorFilter(Color.parseColor(tintOf(kind)));
        holder.divider.setVisibility(position == rows.size() - 1 ? View.GONE : View.VISIBLE);

        String secret = secretOf(credential);
        holder.copy.setAlpha(secret.isEmpty() ? 0.35f : 1f);
        holder.copy.setOnClickListener(v -> {
            if (!secret.isEmpty()) listener.onCopy(secret);
        });
        holder.item.setOnClickListener(v -> listener.onOpen(credential));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private static int iconRes(String kind) {
        if ("wpa_psk".equals(kind)) return R.drawable.password;
        if ("wep".equals(kind)) return R.drawable.unlock;
        return R.drawable.key;
    }

    private static String tintOf(String kind) {
        if ("wpa_psk".equals(kind)) return "#3949AB";
        if ("wep".equals(kind)) return "#6D4C41";
        return "#6A1B9A";
    }

    private static String day(long epochSeconds) {
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(epochSeconds * 1000L));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View item;
        final View divider;
        final ImageView icon;
        final ImageView copy;
        final TextView ssid;
        final TextView meta;
        final TextView value;

        Holder(@NonNull View view) {
            super(view);
            item = view.findViewById(R.id.cred_item);
            divider = view.findViewById(R.id.cred_divider);
            icon = view.findViewById(R.id.cred_icon);
            copy = view.findViewById(R.id.cred_copy);
            ssid = view.findViewById(R.id.cred_ssid);
            meta = view.findViewById(R.id.cred_meta);
            value = view.findViewById(R.id.cred_value);
        }
    }
}
