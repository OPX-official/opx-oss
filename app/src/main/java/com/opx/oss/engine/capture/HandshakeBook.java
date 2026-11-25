package com.opx.oss.engine.capture;

import com.opx.oss.engine.dot11.Eapol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the four-way handshake frames per (AP, station) and reports the moment a pair becomes
 * crackable, exactly once per pair, so the vault never fills with duplicates.
 */
public final class HandshakeBook {

    private final Map<String, Handshake> handshakes = new ConcurrentHashMap<>();
    private final Set<String> emitted = new HashSet<>();

    public void clear() {
        handshakes.clear();
        emitted.clear();
    }

    public List<Handshake> list() {
        return new ArrayList<>(handshakes.values());
    }

    public int size() {
        return handshakes.size();
    }

    /**
     * Feed one EAPOL-Key. Returns the hashline to write when this frame completed a crackable
     * pair, else {@code null}.
     */
    public synchronized String feed(String bssid, String station, String ssid, Eapol eapol) {
        if (bssid == null || station == null || eapol == null) return null;
        // A group address is never a station, and a frame to the AP is not part of a client's
        // handshake.
        if (bssid.equals(station)) return null;

        String key = bssid + "|" + station;
        Handshake handshake = handshakes.get(key);
        if (handshake == null) {
            handshake = new Handshake(bssid, station);
            handshakes.put(key, handshake);
        }
        if (ssid != null && !ssid.isEmpty()) handshake.ssid = ssid;
        handshake.seen = System.currentTimeMillis();

        switch (eapol.messageNumber) {
            case 1:
                handshake.anonce = eapol.nonce;
                handshake.hasM1 = true;
                if (eapol.pmkid != null) handshake.pmkid = eapol.pmkid;
                break;
            case 2:
                handshake.snonce = eapol.nonce;
                handshake.mic = eapol.mic;
                handshake.eapol = eapol.withoutMic();
                handshake.hasM2 = true;
                break;
            case 3:
                handshake.anonce = eapol.nonce;
                handshake.hasM3 = true;
                break;
            case 4:
                handshake.hasM4 = true;
                break;
            default:
                return null;
        }

        if (eapol.messageNumber == 1 && handshake.pmkid != null && emitted.contains(key + "|pmkid")) {
            return null;
        }
        String line = handshake.eapolLine();
        if (line == null) {
            line = handshake.pmkidLine();
            if (line != null) {
                if (!emitted.add(key + "|pmkid")) return null;
                return line;
            }
            return null;
        }
        String pairKey = key + "|" + (handshake.hasM1 && handshake.hasM2 ? "m1m2" : "m2m3");
        if (!emitted.add(pairKey)) return null;
        return line;
    }
}
