package com.opx.oss;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * wifi4's packet dashboard as rows: one labelled sparkline per frame class, each in its own
 * colour, on its own scale, with the rate it is going at right now beside it.
 *
 * <p>The classes are wifi4's own: DATA, INJECT and DEAUTH are the three rate lines, and EAPOL
 * (or WEP IV on a WEP target) rides as its own row instead of being folded into a fourth line
 * next to them. Every row is normalised against its own peak, so a 12/s deauth burst still draws
 * a readable heartbeat beside a 400/s data stream; a row with nothing going out dims, which is
 * how the meter answers "which one is running" at a glance.
 */
final class PacketMeter {

    private static final long STEP_MS = 60L;
    private static final int STEPS = 6;

    private static final int COLOR_DATA = 0xFF42A5F5;
    private static final int COLOR_INJECT = 0xFFFFA726;
    private static final int COLOR_DEAUTH = 0xFFEF5350;
    private static final int COLOR_EAPOL = 0xFF66BB6A;
    private static final int COLOR_WEP_IV = 0xFF26A69A;

    private final Context context;
    private final LinearLayout container;
    private final List<Row> rows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable step;

    PacketMeter(Context context, LinearLayout container, String kind) {
        this.context = context;
        this.container = container;
        container.removeAllViews();
        rows.clear();
        add("data", R.string.wifi_rate_data, COLOR_DATA);
        add("inject", R.string.wifi_rate_inject, COLOR_INJECT);
        add("deauth", R.string.wifi_rate_deauth, COLOR_DEAUTH);
        if ("wep".equals(kind)) add("wep_iv", R.string.wifi_rate_wep_iv, COLOR_WEP_IV);
        else add("eapol", R.string.wifi_rate_eapol, COLOR_EAPOL);
    }

    private void add(String key, int labelRes, int color) {
        View view = LayoutInflater.from(context).inflate(R.layout.wifi_meter_row, container, false);
        TextView label = view.findViewById(R.id.meter_label);
        label.setText(labelRes);
        label.setTextColor(color);
        SparklineView graph = view.findViewById(R.id.meter_graph);
        // No in-graph name: the gutter label already says which class this row is.
        graph.setSeries(new String[]{""}, new int[]{color});
        TextView value = view.findViewById(R.id.meter_value);
        value.setTextColor(color);
        value.setText(String.format(Locale.US, "%.0f/s", 0f));
        container.addView(view);
        Row row = new Row(key, label, value, graph);
        row.apply(0f);
        rows.add(row);
    }

    /** Feeds one engine sample: keys are wifi4's frame classes, values are frames this second. */
    void update(JSONObject rates) {
        if (rows.isEmpty()) return;
        for (Row row : rows) {
            row.to = rates == null ? 0f : (float) Math.max(0d, rates.optDouble(row.key, 0d));
        }
        ramp();
    }

    /** The campaign ended: the tails drop to zero and every row settles on its final rate. */
    void idle() {
        for (Row row : rows) row.to = 0f;
        ramp();
    }

    void stop() {
        if (step != null) handler.removeCallbacks(step);
        handler.removeCallbacksAndMessages(null);
        step = null;
    }

    /**
     * The engine samples once a second; Stryker's monitor moves its graph several times a second,
     * so each new rate is drawn as a short ramp. That ramp is what makes the lines pulse like a
     * heartbeat instead of stepping once per second.
     */
    private void ramp() {
        if (step != null) handler.removeCallbacks(step);
        final float[] from = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) from[i] = rows.get(i).shown;
        final int[] tick = {0};
        step = new Runnable() {
            @Override
            public void run() {
                tick[0]++;
                float t = Math.min(1f, tick[0] / (float) STEPS);
                for (int i = 0; i < rows.size(); i++) {
                    Row row = rows.get(i);
                    row.shown = from[i] + (row.to - from[i]) * t;
                    row.graph.push(0, row.shown);
                    row.value.setText(String.format(Locale.US, "%.0f/s", row.shown));
                    row.apply(row.shown);
                }
                if (tick[0] < STEPS) handler.postDelayed(this, STEP_MS);
            }
        };
        handler.post(step);
    }

    /** One class's row: its history, its live rate and the dimming that marks a quiet class. */
    private static final class Row {
        final String key;
        final TextView label;
        final TextView value;
        final SparklineView graph;
        float shown;
        float to;

        Row(String key, TextView label, TextView value, SparklineView graph) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.graph = graph;
        }

        /**
         * A class that is not going out recedes to grey, exactly like wifi4 dims a flat dashboard
         * row: the colour stays, the shout does not.
         */
        void apply(float rate) {
            boolean running = rate > 0.05f;
            label.setAlpha(running ? 1f : 0.45f);
            value.setAlpha(running ? 1f : 0.45f);
            graph.setAlpha(running ? 1f : 0.45f);
        }
    }
}
