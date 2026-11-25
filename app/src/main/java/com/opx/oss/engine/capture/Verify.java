package com.opx.oss.engine.capture;

import com.opx.oss.engine.crypto.WpaKeys;
import com.opx.oss.engine.dot11.Mac;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Checks a passphrase against a captured handshake with our own key schedule. */
public final class Verify {

    private Verify() {
    }

    /** True when {@code passphrase} produces the MIC the station put on this handshake. */
    public static boolean psk(Handshake handshake, String passphrase) {
        if (handshake == null || passphrase == null) return false;
        if (handshake.ssid == null || handshake.ssid.isEmpty()) return false;
        if (handshake.anonce == null || handshake.snonce == null
                || handshake.mic == null || handshake.eapol == null) {
            return false;
        }
        byte[] raw = rawPmk(passphrase);
        byte[] pmk = raw != null
                ? raw
                : WpaKeys.pmk(passphrase, handshake.ssid.getBytes(StandardCharsets.UTF_8));
        return pmk(handshake, pmk);
    }

    /** A 64-hex passphrase field is a raw PMK, as hashcat reads it. */
    private static byte[] rawPmk(String passphrase) {
        if (passphrase.length() != 64) return null;
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int high = Character.digit(passphrase.charAt(i * 2), 16);
            int low = Character.digit(passphrase.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    /** True when a raw 64-hex PMK produces the captured MIC. */
    public static boolean pmk(Handshake handshake, byte[] pmk) {
        if (handshake == null || pmk == null || pmk.length != 32) return false;
        if (handshake.mic == null || handshake.eapol == null) return false;
        byte[] kck = WpaKeys.kck(pmk, Mac.of(handshake.bssid), Mac.of(handshake.station),
                handshake.anonce, handshake.snonce, handshake.keyVersion);
        return Arrays.equals(WpaKeys.mic(kck, handshake.eapol, handshake.keyVersion),
                handshake.mic);
    }
}
