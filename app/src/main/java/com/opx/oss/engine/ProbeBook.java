package com.opx.oss.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What clients ask for: the SSIDs heard in probe requests, per station. A hidden access point
 * is named by its own clients this way, and the SSID list also feeds the cracker's candidates.
 */
public final class ProbeBook {

    private final Map<String, String> byStation = new ConcurrentHashMap<>();
    private final Map<String, String> bssidByStation = new ConcurrentHashMap<>();

    public void record(String station, String ssid) {
        if (station == null || ssid == null || ssid.isEmpty()) return;
        byStation.put(station, ssid);
    }

    /** Remember which AP a station is talking to, so its probe can name that AP. */
    public void associate(String station, String bssid) {
        if (station == null || bssid == null) return;
        bssidByStation.put(station, bssid);
    }

    public String ssidOf(String station) {
        return byStation.get(station);
    }

    public String bssidOf(String station) {
        return bssidByStation.get(station);
    }

    /** An SSID a client of {@code bssid} asked for, or {@code null} when none was heard. */
    public String decloakFor(String bssid) {
        for (Map.Entry<String, String> entry : bssidByStation.entrySet()) {
            if (!bssid.equals(entry.getValue())) continue;
            String ssid = byStation.get(entry.getKey());
            if (ssid != null && !ssid.isEmpty()) return ssid;
        }
        return null;
    }

    public List<String> ssids() {
        return new ArrayList<>(new java.util.LinkedHashSet<>(byStation.values()));
    }

    public int size() {
        return byStation.size();
    }

    public void clear() {
        byStation.clear();
        bssidByStation.clear();
    }
}
