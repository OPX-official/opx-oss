package com.opx.oss.engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One access point as the scan sees it: identity, radio facts and its security posture. */
public final class AccessPoint {

    public String ssid = "";
    public String bssid = "";
    public int channel;
    public int rssi;
    public String encryption = "OPEN";
    public final List<String> akms = new ArrayList<>();
    public boolean wpa3;
    public boolean transitionMode;
    public boolean pmfCapable;
    public boolean pmfRequired;
    public boolean wps;
    public boolean hidden;
    /** The AP's own RSN element, kept so a client frame can mirror it exactly. */
    public byte[] rsnIe;
    public long lastSeen = System.currentTimeMillis();
    public int beacons;

    /** A stable, human label for the security row. */
    public String securityLabel() {
        if (hidden) return encryption + " (hidden)";
        return encryption;
    }

    /** The app's row shape, the same fields the engine has always sent. */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("ssid", ssid);
            json.put("bssid", bssid);
            json.put("channel", channel);
            json.put("signal", rssi);
            json.put("encryption", encryption);
            json.put("akms", new JSONArray(akms));
            json.put("wpa3", wpa3);
            json.put("transition", transitionMode);
            json.put("pmf", pmfRequired ? "required" : (pmfCapable ? "capable" : "no"));
            json.put("wps", wps);
            json.put("hidden", hidden);
        } catch (Exception ignored) {
            // Every value above is a plain type.
        }
        return json;
    }
}
