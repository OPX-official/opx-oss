package com.opx.oss.engine.capture;

import com.opx.oss.engine.dot11.Eapol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the four-way handshake frames per (AP, station) and reports the moment a crackable pair
 * appears, exactly once per pair, so the vault never fills with duplicates.
 */
public final class HandshakeBook {

    private final Map<String, Handshake> handshakes = new ConcurrentHashMap<>();
    private final Set<String> emitted = new HashSet<>();

    public void clear() {
        handshakes.clear();
        emitted.clear();
    }

    public Handshake get(String bssid, String station) {
        return handshakes.get(bssid + "|" + station);
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
                handshake.m1Nonce = eapol.nonce;
                handshake.anonce = eapol.nonce;
                handshake.hasM1 = true;
                if (eapol.pmkid != null) handshake.pmkid = eapol.pmkid;
                break;
            case 2:
                handshake.snonce = eapol.nonce;
                handshake.mic = eapol.mic;
                handshake.eapol = eapol.withoutMic();
                handshake.keyVersion = eapol.keyInfo & 0x07;
                handshake.hasM2 = true;
                break;
            case 3:
                handshake.m3Nonce = eapol.nonce;
                handshake.anonce = eapol.nonce;
                handshake.hasM3 = true;
                break;
            case 4:
                handshake.m4Nonce = eapol.nonce;
                handshake.m4Mic = eapol.mic;
                handshake.m4Eapol = eapol.withoutMic();
                if (!handshake.hasM2) {
                    handshake.keyVersion = eapol.keyInfo & 0x07;
                    // An M4 carries the echoed SNonce and its MIC; let verification use it when
                    // no M2 was captured.
                    if (handshake.snonce == null) handshake.snonce = eapol.nonce;
                    if (handshake.mic == null) handshake.mic = eapol.mic;
                    if (handshake.eapol == null) handshake.eapol = eapol.withoutMic();
                }
                handshake.hasM4 = true;
                break;
            default:
                return null;
        }

        if (eapol.messageNumber == 1 && handshake.pmkid != null && !emitted.contains(key + "|pmkid")) {
            String pmkidLine = handshake.pmkidLine();
            if (pmkidLine != null && emitted.add(key + "|pmkid")) return pmkidLine;
        }
        String line = handshake.eapolLine();
        if (line == null) return null;
        int pair = handshake.eapolPair();
        if (pair < 0) return null;
        return emitted.add(key + "|eapol" + pair) ? line : null;
    }
}
