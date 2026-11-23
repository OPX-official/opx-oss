package com.opx.oss;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The network list. Rows follow Stryker's WiFi layout: SSID and BSSID on the left, signal on
 * the right, and a badge line for encryption, WPS and band.
 */
public class WifiAdapter extends RecyclerView.Adapter<WifiAdapter.Holder> {

    /** One row; fed either by the wifi4 engine or by the built-in radio. */
    public static final class Network {
        public String ssid = "";
        public String bssid = "";
        public String privacy = "";
        public String source = "";
        public int channel;
        public int signal;
        public boolean wps;

        public String key() {
            return bssid == null || bssid.isEmpty() ? "ssid:" + ssid : bssid.toLowerCase(Locale.US);
        }

        public String bandLabel() {
            if (channel <= 0) return null;
            return channel <= 14 ? "2.4 GHz" : "5 GHz";
        }

        public String badges() {
            List<String> parts = new ArrayList<>();
            if (privacy != null && !privacy.isEmpty()) parts.add("\uD83D\uDD12 " + shortPrivacy());
            if (wps) parts.add("WPS");
            String band = bandLabel();
            if (band != null) parts.add(band);
            StringBuilder text = new StringBuilder();
            for (String part : parts) {
                if (text.length() > 0) text.append(" · ");
                text.append(part);
            }
            return text.toString();
        }

        private String shortPrivacy() {
            if (privacy == null) return "";
            String upper = privacy.toUpperCase(Locale.US);
            if (upper.contains("WPA3")) return "WPA3";
            if (upper.contains("WPA2")) return "WPA2";
            if (upper.contains("WPA")) return "WPA";
            if (upper.contains("WEP")) return "WEP";
            if (upper.contains("[ESS]")) return "OPEN";
            return privacy.length() > 8 ? privacy.substring(0, 8) : privacy;
        }
    }

    private final Map<String, Network> items = new LinkedHashMap<>();

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    /** Adds or refreshes one network; returns true when it is a new row. */
    public boolean upsert(Network network) {
        if (network == null) return false;
        String key = network.key();
        boolean added = !items.containsKey(key);
        items.put(key, network);
        notifyDataSetChanged();
        return added;
    }

    public int size() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_wifi, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Network network = new ArrayList<>(items.values()).get(position);
        String name = network.ssid == null || network.ssid.isEmpty()
                ? holder.itemView.getContext().getString(R.string.wifi_hidden_ssid)
                : network.ssid;
        holder.name.setText(name);
        holder.meta.setText(String.format(Locale.US, "%s · ch %d", network.bssid, network.channel));
        holder.signal.setText(network.signal == 0
                ? "—"
                : String.format(Locale.US, "%d dBm", network.signal));
        String badges = network.badges();
        holder.badges.setText(badges);
        holder.badges.setVisibility(badges.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final TextView signal;
        final TextView badges;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.wifi_name);
            meta = itemView.findViewById(R.id.wifi_meta);
            signal = itemView.findViewById(R.id.wifi_signal);
            badges = itemView.findViewById(R.id.wifi_badges);
        }
    }
}
