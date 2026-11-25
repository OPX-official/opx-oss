package com.opx.oss.engine;

import com.opx.oss.engine.dot11.Parser;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns the radio's frames into rows: every beacon and probe response the chip hands over is
 * parsed and folded into the scan book, while the channel plan tells the caller where to tune
 * next.
 */
public final class ScanEngine {

    private static final int[] CHANNELS_2G = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13};
    private static final int[] CHANNELS_5G = {36, 40, 44, 48, 149, 153, 157, 161, 165};
    private static final int[] CHANNELS_DFS =
            {52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144};

    private final ScanBook book;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile int[] plan = CHANNELS_2G;
    private volatile int cursor;
    private volatile int frames;
    private volatile int parsed;

    public ScanEngine(ScanBook book) {
        this.book = book;
    }

    public ScanBook book() {
        return book;
    }

    /** Choose the channel plan: 2.4 GHz only, plus 5 GHz, plus DFS when the preference is on. */
    public void setPlan(boolean include5g, boolean includeDfs) {
        if (!include5g) {
            plan = CHANNELS_2G;
        } else if (!includeDfs) {
            int[] combined = new int[CHANNELS_2G.length + CHANNELS_5G.length];
            System.arraycopy(CHANNELS_2G, 0, combined, 0, CHANNELS_2G.length);
            System.arraycopy(CHANNELS_5G, 0, combined, CHANNELS_2G.length, CHANNELS_5G.length);
            plan = combined;
        } else {
            int[] combined = new int[CHANNELS_2G.length + CHANNELS_5G.length + CHANNELS_DFS.length];
            System.arraycopy(CHANNELS_2G, 0, combined, 0, CHANNELS_2G.length);
            System.arraycopy(CHANNELS_5G, 0, combined, CHANNELS_2G.length, CHANNELS_5G.length);
            System.arraycopy(CHANNELS_DFS, 0, combined,
                    CHANNELS_2G.length + CHANNELS_5G.length, CHANNELS_DFS.length);
            plan = combined;
        }
        cursor = 0;
    }

    public void start() {
        running.set(true);
    }

    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int frames() {
        return frames;
    }

    public int parsed() {
        return parsed;
    }

    /** The next channel the caller should tune to, cycling through the plan. */
    public int nextChannel() {
        int[] current = plan;
        if (current.length == 0) return 1;
        int index = cursor;
        cursor = (index + 1) % current.length;
        return current[index];
    }

    /** One frame from the radio: folded into the book when it is a beacon or probe response. */
    public void feed(byte[] mpdu, int rssi) {
        if (!running.get() || mpdu == null) return;
        frames++;
        AccessPoint seen = Parser.parseSsidFrame(mpdu, rssi);
        if (seen == null) return;
        parsed++;
        book.upsert(seen);
    }
}
