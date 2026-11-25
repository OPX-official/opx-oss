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
        byte[] pmk = WpaKeys.pmk(passphrase, handshake.ssid.getBytes(StandardCharsets.UTF_8));
        byte[] kck = WpaKeys.kck(pmk, Mac.of(handshake.bssid), Mac.of(handshake.station),
                handshake.anonce, handshake.snonce);
        byte[] mic = WpaKeys.mic(kck, handshake.eapol);
        return Arrays.equals(mic, handshake.mic);
    }

    /** True when a raw 64-hex PMK produces the captured MIC. */
    public static boolean pmk(Handshake handshake, byte[] pmk) {
        if (handshake == null || pmk == null || pmk.length != 32) return false;
        byte[] kck = WpaKeys.kck(pmk, Mac.of(handshake.bssid), Mac.of(handshake.station),
                handshake.anonce, handshake.snonce);
        return Arrays.equals(WpaKeys.mic(kck, handshake.eapol), handshake.mic);
    }
}
