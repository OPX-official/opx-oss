package com.opx.oss;

import android.content.res.ColorStateList;
import android.graphics.Color;
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
 * pills, monospace BSSID, a vendor line and a signal-quality chip.
 * <p>
 * The security pill reads wifi4's own bucket ({@code EncryptionType}: OPEN, WEP, WPA, WPA2,
 * WPA3, WPA3_TRANSITION, OWE, UNKNOWN) rather than sniffing the raw encryption string.
 */
public class WifiAdapter extends RecyclerView.Adapter<WifiAdapter.Holder> {

    /** A row was tapped: the screen opens the target sheet for that network. */
    public interface OnNetworkClick {
        void onNetworkClick(Network network);
    }

    private static final int LOCKED_BG = Color.parseColor("#FFD7DA");
    private static final int LOCKED_FG = Color.parseColor("#B71C1C");
    private static final int ENHANCED_OPEN_BG = Color.parseColor("#FFF3CD");
    private static final int ENHANCED_OPEN_FG = Color.parseColor("#8D6E00");

    /** One row; fed either by the wifi4 engine or by the built-in radio. */
    public static final class Network {
        public String ssid = "";
        public String bssid = "";
        public String privacy = "";
        public String security = "";
        public String vendor = "";
        public String chipset = "";
        public String source = "";
        public String band = "";
        public boolean hidden;
        public int channel;
        public int signal;
        public boolean wps;

        public String key() {
            return bssid == null || bssid.isEmpty() ? "ssid:" + ssid : bssid.toLowerCase(Locale.US);
        }

        public boolean isFiveGhz() {
            return channel > 14;
        }

        /** The pill text, or null when the network is open (nothing to warn about). */
        public String securityLabel() {
            if (security == null || security.isEmpty()
                    || "OPEN".equals(security) || "UNKNOWN".equals(security)) {
                return null;
            }
            if ("WPA3_TRANSITION".equals(security)) return "WPA3→2";
            return security;
        }

        /** dBm to the 0–100% Stryker shows in the row chip. */
        public int quality() {
            if (signal >= 0) return 0;
            int quality = 2 * (signal + 100);
            if (quality < 0) return 0;
            return Math.min(quality, 100);
        }

        /** Stryker's second line: the manufacturer, else the chipset, else the raw security. */
        public String sourceLine() {
            if (vendor != null && !vendor.isEmpty()) return vendor;
            if (chipset != null && !chipset.isEmpty()) return chipset;
            if (privacy != null && !privacy.isEmpty()) return privacy;
            return "usb".equals(source) ? "USB adapter" : "Built-in radio";
        }
    }

    private final Map<String, Network> items = new LinkedHashMap<>();
    private OnNetworkClick onNetworkClick;

    public void setOnNetworkClick(OnNetworkClick listener) {
        this.onNetworkClick = listener;
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    /** Adds or refreshes one network; returns true when it is a new row. */
    public boolean upsert(Network network) {
        if (network == null) return false;
        items.put(network.key(), network);
        notifyDataSetChanged();
        return true;
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

        pill(holder.lock, network.securityLabel());
        holder.wps.setVisibility(network.wps ? View.VISIBLE : View.GONE);
        holder.wpsLabel.setVisibility(network.wps ? View.VISIBLE : View.GONE);
        holder.five.setVisibility(network.isFiveGhz() ? View.VISIBLE : View.GONE);

        holder.divider.setVisibility(position == items.size() - 1 ? View.GONE : View.VISIBLE);

        // Without this a row is just text: tapping a network has to open its attacks.
        holder.item.setOnClickListener(v -> {
            if (onNetworkClick != null) onNetworkClick.onNetworkClick(network);
        });
    }

    private static void pill(TextView view, String label) {
        if (label == null) {
            view.setVisibility(View.GONE);
            return;
        }
        boolean enhancedOpen = "OWE".equals(label);
        view.setText(label);
        view.setBackgroundTintList(ColorStateList.valueOf(
                enhancedOpen ? ENHANCED_OPEN_BG : LOCKED_BG));
        view.setTextColor(enhancedOpen ? ENHANCED_OPEN_FG : LOCKED_FG);
        view.setVisibility(View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View item;
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
            item = itemView.findViewById(R.id.item);
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
