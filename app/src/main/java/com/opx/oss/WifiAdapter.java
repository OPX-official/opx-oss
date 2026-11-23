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
 * The network list, laid out like Stryker's WiFi rows: icon tile, SSID with security/WPS/5G
 * pills, monospace BSSID, a source line and a signal-quality chip.
 */
public class WifiAdapter extends RecyclerView.Adapter<WifiAdapter.Holder> {

    /** One row; fed either by the wifi4 engine or by the built-in radio. */
    public static final class Network {
        public String ssid = "";
        public String bssid = "";
        public String privacy = "";
        public String vendor = "";
        public String source = "";
        public int channel;
        public int signal;
        public boolean wps;

        public String key() {
            return bssid == null || bssid.isEmpty() ? "ssid:" + ssid : bssid.toLowerCase(Locale.US);
        }

        public boolean isFiveGhz() {
            return channel > 14;
        }

        public boolean isLocked() {
            if (privacy == null) return false;
            String upper = privacy.toUpperCase(Locale.US);
            if (upper.contains("WPA") || upper.contains("WEP") || upper.contains("WPS")) return true;
            return !upper.contains("[ESS]") && !upper.isEmpty();
        }

        /** dBm to the 0–100% Stryker shows in the row chip. */
        public int quality() {
            if (signal >= 0) return 0;
            int quality = 2 * (signal + 100);
            if (quality < 0) return 0;
            return Math.min(quality, 100);
        }

        public String sourceLine() {
            if (vendor != null && !vendor.isEmpty()) return vendor;
            if (privacy != null && !privacy.isEmpty()) return privacy;
            return "usb".equals(source) ? "USB adapter" : "Built-in radio";
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
        holder.name.setText(network.ssid == null || network.ssid.isEmpty()
                ? holder.itemView.getContext().getString(R.string.wifi_hidden_ssid)
                : network.ssid);
        holder.bssid.setText(network.bssid == null || network.bssid.isEmpty() ? "—" : network.bssid);
        holder.model.setText(network.sourceLine());
        holder.power.setText(String.format(Locale.US, "%d%%", network.quality()));

        holder.lock.setVisibility(network.isLocked() ? View.VISIBLE : View.GONE);
        holder.wps.setVisibility(network.wps ? View.VISIBLE : View.GONE);
        holder.wpsLabel.setVisibility(network.wps ? View.VISIBLE : View.GONE);
        holder.five.setVisibility(network.isFiveGhz() ? View.VISIBLE : View.GONE);

        holder.divider.setVisibility(position == items.size() - 1 ? View.GONE : View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView bssid;
        final TextView model;
        final TextView power;
        final TextView lock;
        final TextView wps;
        final TextView five;
        final TextView wpsLabel;
        final View divider;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.wifi_name);
            bssid = itemView.findViewById(R.id.wifi_bssid);
            model = itemView.findViewById(R.id.wifi_model);
            power = itemView.findViewById(R.id.wifi_power);
            lock = itemView.findViewById(R.id.lock_mark);
            wps = itemView.findViewById(R.id.wps_mark);
            five = itemView.findViewById(R.id.five_mark);
            wpsLabel = itemView.findViewById(R.id.iswps);
            divider = itemView.findViewById(R.id.wifi_item_divider);
        }
    }
}
