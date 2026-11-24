package com.opx.oss;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * The attack-rate sparkline: Stryker's own view, so an attack's frame rate draws the same graph
 * its monitor draws. Newest sample sits at the right edge; the filled area fades downwards and
 * the dot marks the live head, which makes a pulsing injection rate read like a heartbeat.
 */
public final class SparklineView extends View {

    private static final int CAPACITY = 160;

    private final float[] values = new float[CAPACITY];
    private int count;
    private int head;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

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
        applyAccent();
    }

    public void setAccent(int color) {
        accent = color;
        gradientReady = false;
        applyAccent();
        invalidate();
    }

    private void applyAccent() {
        linePaint.setColor(accent);
        dotPaint.setColor(accent);
        glowPaint.setColor(withAlpha(accent, 0x33));
    }

    /** Appends one sample in 0..1; negative clears that slot (a gap in the graph). */
    public void push(float normalized) {
        float value = normalized < 0f ? -1f : Math.min(normalized, 1f);
        if (count < CAPACITY) {
            values[count++] = value;
        } else {
            values[head] = value;
            head = (head + 1) % CAPACITY;
        }
        invalidate();
    }

    public void clear() {
        count = 0;
        head = 0;
        invalidate();
    }

    private float valueAt(int index) {
        if (count < CAPACITY) return values[index];
        return values[(head + index) % CAPACITY];
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
        if (count < 2) return;

        if (!gradientReady) {
            fillPaint.setShader(new LinearGradient(0f, top, 0f, bottom,
                    withAlpha(accent, 0x66), withAlpha(accent, 0x00), Shader.TileMode.CLAMP));
            gradientReady = true;
        }

        float step = count > 1 ? w / (float) (count - 1) : w;
        int i = 0;
        int lastDrawn = -1;
        while (i < count) {
            if (valueAt(i) < 0f) {
                i++;
                continue;
            }
            int j = i;
            while (j < count && valueAt(j) >= 0f) j++;
            drawRun(canvas, i, j, step, top, bottom);
            lastDrawn = j - 1;
            i = j;
        }
        if (lastDrawn >= 0) {
            float x = Math.min(step * lastDrawn, w - dp(3f));
            float y = bottom - (bottom - top) * valueAt(lastDrawn);
            canvas.drawCircle(x, y, dp(6f), glowPaint);
            canvas.drawCircle(x, y, dp(2.6f), dotPaint);
        }
    }

    private void drawRun(Canvas canvas, int from, int to, float step, float top, float bottom) {
        if (to - from < 1) return;
        linePath.reset();
        fillPath.reset();
        float firstX = step * from;
        float lastX = firstX;
        for (int i = from; i < to; i++) {
            float x = step * i;
            float y = bottom - (bottom - top) * valueAt(i);
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
        fillPath.lineTo(lastX, bottom);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);
        if (to - from > 1) canvas.drawPath(linePath, linePaint);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
