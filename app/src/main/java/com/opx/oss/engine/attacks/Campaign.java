package com.opx.oss.engine.attacks;

import com.opx.oss.engine.chips.WifiChip;

import org.json.JSONObject;

/**
 * One campaign against one access point: it runs on the engine's worker, reports progress as
 * events, and stops the moment the app asks it to.
 */
public abstract class Campaign {

    /** Where a campaign's events go. */
    public interface Events {
        void emit(JSONObject event);
    }

    protected final WifiChip chip;
    protected final String bssid;
    protected final JSONObject options;
    private volatile boolean stopped;

    protected Campaign(WifiChip chip, String bssid, JSONObject options) {
        this.chip = chip;
        this.bssid = bssid;
        this.options = options == null ? new JSONObject() : options;
    }

    /** The name the app sends in the {@code attack} command. */
    public abstract String kind();

    protected abstract void run(Events events) throws Exception;

    public final void start(Events events) throws Exception {
        stopped = false;
        run(events);
    }

    public void stop() {
        stopped = true;
    }

    public boolean isStopped() {
        return stopped;
    }

    protected boolean shouldStop() {
        return stopped || !Thread.currentThread().isAlive();
    }

    protected void log(Events events, String line) {
        events.emit(event("attack_log").put("message", line));
    }

    protected void progress(Events events, int percent, String label) {
        events.emit(event("attack_progress").put("percent", percent).put("label", label));
    }

    protected JSONObject event(String name) {
        JSONObject event = new JSONObject();
        try {
            event.put("event", name);
        } catch (Exception ignored) {
            // A JSONObject accepts every string key.
        }
        return event;
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
    }
}
