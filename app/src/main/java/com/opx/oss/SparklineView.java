package com.opx.oss;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The attack-rate sparkline: Stryker's own view, so an attack's frame rate draws the same graph
 * its monitor draws. Newest sample sits at the right edge; the filled area fades downwards and
 * the dot marks the live head, which makes a pulsing injection rate read like a heartbeat.
 *
 * <p>Several rates share the one plot: each series keeps its own samples and its own colour, so
 * injection, deauths and EAPOL rise and fall together on the same line, and each one carries its
 * name in small type right above the point where it peaks - the graph alone never says which
 * line is which.
 */
public final class SparklineView extends View {

    private static final int CAPACITY = 160;
    private static final int MAX_SERIES = 4;

    private final float[][] values = new float[MAX_SERIES][CAPACITY];
    private final int[] counts = new int[MAX_SERIES];
    private final int[] heads = new int[MAX_SERIES];
    private final float[] peaks = new float[MAX_SERIES];

    private String[] labels = {""};
    private final int[] colors = {0xFF1E88E5, 0xFFFF7043, 0xFF66BB6A, 0xFFAB47BC};
    private int seriesCount = 1;
    private boolean customSeries;
    private float scale = 1f;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final List<float[]> anchors = new ArrayList<>();

    private int accent = 0xFF1E88E5;
    private boolean gradientReady;

    public SparklineView(Context context) {
        super(context);
        init();
    }

    public SparklineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SparklineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1.8f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.6f));
        gridPaint.setColor(0x22808080);
        dotPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        labelPaint.setTextSize(dp(8.5f));
        labelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        labelPaint.setTextAlign(Paint.Align.LEFT);
    }

    /** One series per rate: the names drawn above each line, in the matching colours. */
    public void setSeries(String[] seriesLabels, int[] seriesColors) {
        if (seriesLabels == null || seriesColors == null) return;
        int count = Math.min(Math.min(seriesLabels.length, seriesColors.length), MAX_SERIES);
        if (count <= 0) return;
        labels = new String[count];
        for (int i = 0; i < count; i++) {
            labels[i] = seriesLabels[i] == null ? "" : seriesLabels[i];
            colors[i] = seriesColors[i];
        }
        seriesCount = count;
        customSeries = true;
        clear();
    }

    public void setAccent(int color) {
        accent = color;
        if (!customSeries) colors[0] = color;
        gradientReady = false;
        invalidate();
    }

    /** Appends one raw sample to one series; the view scales every series against the same peak. */
    public void push(int series, float value) {
        if (series < 0 || series >= seriesCount) return;
        float sample = Math.max(0f, value);
        if (counts[series] < CAPACITY) {
            values[series][counts[series]++] = sample;
        } else {
            values[series][heads[series]] = sample;
            heads[series] = (heads[series] + 1) % CAPACITY;
        }
        if (sample > peaks[series]) peaks[series] = sample;
        invalidate();
    }

    public void clear() {
        for (int s = 0; s < MAX_SERIES; s++) {
            counts[s] = 0;
            heads[s] = 0;
            peaks[s] = 0f;
        }
        scale = 1f;
        gradientReady = false;
        invalidate();
    }

    private float valueAt(int series, int index) {
        if (counts[series] < CAPACITY) return values[series][index];
        return values[series][(heads[series] + index) % CAPACITY];
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        gradientReady = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        float inset = dp(2f);
        float top = inset;
        float bottom = h - inset;

        for (int i = 1; i < 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(0f, y, w, y, gridPaint);
        }

        // One scale for every line, so the tallest rate is the tallest line: a slow EAPOL stream
        // stays visibly slower than the injection burst instead of being stretched to match it.
        float highest = 0f;
        for (int s = 0; s < seriesCount; s++) highest = Math.max(highest, peaks[s]);
        scale = Math.max(highest, 1f);

        anchors.clear();
        boolean any = false;
        for (int s = 0; s < seriesCount; s++) {
            if (counts[s] < 2) continue;
            any = true;
            drawSeries(canvas, s, w, top, bottom);

            int peakIndex = 0;
            float peakValue = -1f;
            for (int i = 0; i < counts[s]; i++) {
                float value = valueAt(s, i);
                if (value > peakValue) {
                    peakValue = value;
                    peakIndex = i;
                }
            }
            if (peakValue > 0f && counts[s] > 1) {
                float step = w / (float) (counts[s] - 1);
                float x = Math.min(step * peakIndex, w - dp(1f));
                float y = bottom - (bottom - top) * Math.min(peakValue / scale, 1f);
                anchors.add(new float[]{x, y, s});
                float[] headXy = headPoint(s, w, top, bottom);
                canvas.drawCircle(headXy[0], headXy[1], dp(5f), glowPaintFor(s));
                canvas.drawCircle(headXy[0], headXy[1], dp(2.2f), dotPaintFor(s));
            }
        }
        if (!any) return;
        drawLabels(canvas, w, h, top, bottom);
    }

    private void drawSeries(Canvas canvas, int series, float w, float top, float bottom) {
        int count = counts[series];
        float step = w / (float) (count - 1);
        linePaint.setColor(colors[series]);
        // Only the first series keeps the faded area: three overlapping fills would hide the lines.
        boolean filled = series == 0;
        if (filled) {
            if (!gradientReady) {
                fillPaint.setShader(new LinearGradient(0f, top, 0f, bottom,
                        withAlpha(colors[0], 0x55), withAlpha(colors[0], 0x00),
                        Shader.TileMode.CLAMP));
                gradientReady = true;
            }
        }
        drawRun(canvas, series, 0, count, step, top, bottom, filled);
    }

    private void drawRun(Canvas canvas, int series, int from, int to, float step,
                         float top, float bottom, boolean filled) {
        if (to - from < 1) return;
        linePath.reset();
        fillPath.reset();
        float lastX = step * from;
        for (int i = from; i < to; i++) {
            float x = step * i;
            float y = bottom - (bottom - top) * Math.min(valueAt(series, i) / scale, 1f);
            if (i == from) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, bottom);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
            lastX = x;
        }
        if (filled) {
            fillPath.lineTo(lastX, bottom);
            fillPath.close();
            canvas.drawPath(fillPath, fillPaint);
        }
        if (to - from > 1) canvas.drawPath(linePath, linePaint);
    }

    private float[] headPoint(int series, float w, float top, float bottom) {
        int last = counts[series] - 1;
        float step = w / (float) Math.max(counts[series] - 1, 1);
        float x = Math.min(step * last, w - dp(3f));
        float y = bottom - (bottom - top) * Math.min(valueAt(series, last) / scale, 1f);
        return new float[]{x, y};
    }

    private Paint glowPaintFor(int series) {
        glowPaint.setColor(withAlpha(colors[series], 0x33));
        return glowPaint;
    }

    private Paint dotPaintFor(int series) {
        dotPaint.setColor(colors[series]);
        return dotPaint;
    }

    /**
     * The name of each rate sits just above the point where its line peaks, in that line's colour.
     * Labels are nudged apart (and pulled back inside the view) so two rates peaking together do
     * not write over each other.
     */
    private void drawLabels(Canvas canvas, float w, float h, float top, float bottom) {
        if (anchors.isEmpty()) return;
        anchors.sort(Comparator.comparingDouble(a -> a[1]));
        float textHeight = labelPaint.getTextSize();
        float gap = textHeight + dp(1.5f);
        float lastBaseline = -Float.MAX_VALUE;
        for (float[] anchor : anchors) {
            int series = (int) anchor[2];
            String label = labels[series];
            if (label == null || label.isEmpty()) continue;
            float textWidth = labelPaint.measureText(label);
            float baseline = anchor[1] - dp(3f);
            // A peak that touches the ceiling has no room above it: write under the spike instead.
            if (baseline - textHeight < top) baseline = anchor[1] + textHeight + dp(1f);
            baseline = Math.max(baseline, top + textHeight);
            if (baseline < lastBaseline + gap) baseline = lastBaseline + gap;
            float maxBaseline = h - dp(1f);
            if (baseline > maxBaseline) baseline = maxBaseline;
            lastBaseline = baseline;
            float x = anchor[0] - textWidth / 2f;
            x = Math.max(dp(1f), Math.min(x, w - textWidth - dp(1f)));
            labelPaint.setColor(withAlpha(colors[series], 0xF2));
            canvas.drawText(label, x, baseline, labelPaint);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
