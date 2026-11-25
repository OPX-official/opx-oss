package com.opx.oss.engine;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The scan sink: one row per BSSID, newest signal wins, a known SSID is never overwritten by a
 * hidden beacon of the same AP.
 */
public final class ScanBook {

    private final Map<String, AccessPoint> byBssid = new ConcurrentHashMap<>();

    public void clear() {
        byBssid.clear();
    }

    public int size() {
        return byBssid.size();
    }

    public AccessPoint get(String bssid) {
        return byBssid.get(bssid);
    }

    /** Fold one sighting in, keeping what the latest beacon is silent about. */
    public AccessPoint upsert(AccessPoint seen) {
        if (seen == null || seen.bssid == null || seen.bssid.isEmpty()) return null;
        AccessPoint known = byBssid.get(seen.bssid);
        if (known == null) {
            byBssid.put(seen.bssid, seen);
            return seen;
        }
        if (!seen.ssid.isEmpty() || known.ssid.isEmpty()) {
            known.ssid = seen.ssid;
            known.hidden = seen.ssid.isEmpty();
        }
        known.rssi = seen.rssi;
        if (seen.channel != 0) known.channel = seen.channel;
        if (!"OPEN".equals(seen.encryption)) known.encryption = seen.encryption;
        if (!seen.akms.isEmpty()) {
            known.akms.clear();
            known.akms.addAll(seen.akms);
        }
        known.wpa3 = seen.wpa3;
        known.transitionMode = seen.transitionMode;
        known.pmfCapable = seen.pmfCapable;
        known.pmfRequired = seen.pmfRequired;
        known.wps = seen.wps || known.wps;
        known.lastSeen = System.currentTimeMillis();
        known.beacons++;
        return known;
    }

    /** Every AP, strongest first. */
    public List<AccessPoint> list() {
        List<AccessPoint> all = new ArrayList<>(byBssid.values());
        all.sort(Comparator.comparingInt((AccessPoint ap) -> ap.rssi).reversed());
        return all;
    }

    public JSONArray toJson() {
        JSONArray out = new JSONArray();
        for (AccessPoint ap : list()) out.put(ap.toJson());
        return out;
    }
}
