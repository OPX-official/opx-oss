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
    /** First known pairwise cipher of the RSN element, e.g. {@code CCMP}. */
    public String pairwiseCipher;
    /** Raw 00-0F-AC AKM suite numbers, in the order the AP lists them. */
    public final List<Integer> akmSuites = new ArrayList<>();
    /** True when the RSN element parsed completely; false for a truncated or broken one. */
    public boolean rsnValid;
    /** True when the AP lists an AKM outside 00-0F-AC (OSEN, DPP, a vendor suite). */
    public boolean vendorAkmOffered;
    /** Newer Extended Capabilities bit 84: management frames are protected. */
    public boolean beaconProtection;
    /** WPS attributes worth showing: setup lock, state, config methods, identity. */
    public boolean wpsLocked;
    public int wpsState;
    public int wpsConfigMethods;
    public int wpsDevicePasswordId;
    public boolean wpsSelectedRegistrar;
    public String wpsVersion;
    public String wscManufacturer;
    public String wscModelName;
    public String wscModelNumber;
    public String wscDeviceName;
    public String wscDeviceType;
    /** Chipset the vendor IE names, when one is recognised. */
    public String chipset;
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
            if (pairwiseCipher != null) json.put("cipher", pairwiseCipher);
            JSONArray suites = new JSONArray();
            for (Integer suite : akmSuites) suites.put(suite.intValue());
            json.put("akm_suites", suites);
            json.put("rsn_valid", rsnValid);
            json.put("beacon_protection", beaconProtection);
            if (wps) {
                json.put("wps_locked", wpsLocked);
                json.put("wps_state", wpsState);
                json.put("wps_config_methods", wpsConfigMethods);
                json.put("wps_device_password_id", wpsDevicePasswordId);
                if (wpsVersion != null) json.put("wps_version", wpsVersion);
                if (wscManufacturer != null) json.put("manufacturer", wscManufacturer);
                if (wscModelName != null) json.put("model", wscModelName);
            }
            if (chipset != null) json.put("chipset", chipset);
            json.put("wps", wps);
            json.put("hidden", hidden);
        } catch (Exception ignored) {
            // Every value above is a plain type.
        }
        return json;
    }
}
