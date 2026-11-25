package com.opx.oss.engine.capture;

import com.opx.oss.engine.dot11.Mac;

import java.util.ArrayList;
import java.util.List;

/**
 * One four-way handshake between an AP and one station: the nonces, the MIC and the EAPOL bytes
 * the MIC covers. A complete pair is what becomes a crackable hashline.
 */
public final class Handshake {

    public final String bssid;
    public final String station;
    public String ssid = "";

    public byte[] anonce;
    public byte[] snonce;
    public byte[] mic;
    public byte[] eapol;
    public byte[] pmkid;
    /** True when the network's AKM is PSK-SHA256, which changes the PMKID hashline's pair field. */
    public boolean sha256;

    public boolean hasM1;
    public boolean hasM2;
    public boolean hasM3;
    public boolean hasM4;
    public long seen = System.currentTimeMillis();

    public Handshake(String bssid, String station) {
        this.bssid = bssid;
        this.station = station;
    }

    /** The hashline for the captured PMKID, or {@code null} when there is none. */
    public String pmkidLine() {
        if (pmkid == null || ssid == null || ssid.isEmpty()) return null;
        return Hc22000.pmkid(ssid, pmkid, Mac.of(bssid), Mac.of(station), sha256);
    }

    /** The hashline for the captured EAPOL pair, or {@code null} when it is not crackable yet. */
    public String eapolLine() {
        if (mic == null || eapol == null || anonce == null || ssid == null || ssid.isEmpty()) {
            return null;
        }
        int pair;
        if (hasM1 && hasM2) {
            pair = 0x00;          // M1+M2, authorized
        } else if (hasM2 && hasM3) {
            pair = 0x02;          // M2+M3
        } else {
            return null;
        }
        return Hc22000.eapol(ssid, mic, Mac.of(bssid), Mac.of(station), anonce, eapol, pair);
    }

    public List<String> lines() {
        List<String> lines = new ArrayList<>(2);
        String pmkidLine = pmkidLine();
        if (pmkidLine != null) lines.add(pmkidLine);
        String eapolLine = eapolLine();
        if (eapolLine != null) lines.add(eapolLine);
        return lines;
    }

    public String fileName() {
        return "capture_" + bssid.replace(':', '-') + "_" + station.replace(':', '-') + ".22000";
    }
}
