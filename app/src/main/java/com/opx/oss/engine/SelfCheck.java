package com.opx.oss.engine;

import com.opx.oss.engine.capture.Hc22000;
import com.opx.oss.engine.crypto.Crc32;
import com.opx.oss.engine.crypto.Hmac;
import com.opx.oss.engine.crypto.Pbkdf2;
import com.opx.oss.engine.crypto.Rc4;
import com.opx.oss.engine.crypto.Sha1;
import com.opx.oss.engine.crypto.Sha256;
import com.opx.oss.engine.crypto.WpaKeys;
import com.opx.oss.engine.crack.CpuCracker;
import com.opx.oss.engine.crack.WpsPins;
import com.opx.oss.engine.crypto.Aes;
import com.opx.oss.engine.dot11.wsc.WscCrypto;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Known-answer checks for the primitives, run once when the engine starts. A wrong hash or key
 * derivation would otherwise only surface as "the handshake does not crack", which looks like a
 * bad capture instead of a bug here.
 */
public final class SelfCheck {

    private SelfCheck() {
    }

    /** {@code null} when every vector matches, else one line per failure. */
    public static String run() {
        List<String> failures = new ArrayList<>();
        expect(failures, "sha1", "a9993e364706816aba3e25717850c26c9cd0d89d",
                hex(Sha1.digest(bytes("abc"))));
        expect(failures, "sha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                hex(Sha256.digest(bytes("abc"))));
        expect(failures, "hmac-sha1", "de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9",
                hex(Hmac.hmacSha1(bytes("key"), bytes("The quick brown fox jumps over the lazy dog"))));
        expect(failures, "pbkdf2", "0c60c80f961f0e71f3a9b524af6012062fe037a6",
                hex(Pbkdf2.hmacSha1(bytes("password"), bytes("salt"), 1, 20)));
        expect(failures, "crc32", "cbf43926",
                String.format("%08x", Crc32.of(bytes("123456789"), 0, 9)));
        expect(failures, "rc4", "bbf316e8d940af0ad3",
                hex(new Rc4(bytes("Key")).process(bytes("Plaintext"))));
        // IEEE 802.11i passphrase vector: "password" over SSID "IEEE".
        expect(failures, "pmk", "f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e",
                hex(WpaKeys.pmk("password", bytes("IEEE"))));
        expect(failures, "hc22000-pmkid",
                "WPA*01*000102030405060708090a0b0c0d0e0f*001122334455*66778899aabb*49454545***",
                Hc22000.pmkid("IEEE", new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
                        new byte[]{0x00, 0x11, 0x22, 0x33, 0x44, 0x55},
                        new byte[]{0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB},
                        false));
        // PMKID over the same passphrase: HMAC-SHA1-128(PMK, "PMK Name" || AP || STA).
        expect(failures, "pmkid", "88787e90d6da43df43aff21fca3a3df6",
                hex(WpaKeys.pmkid(WpaKeys.pmk("opx-selfcheck", bytes("OPX-SelfCheck")),
                        new byte[]{2, 0, 0, 0, 0, 1}, new byte[]{2, 0, 0, 0, 0, 2})));
        // FIPS-197 appendix C.1, and the WSC key derivation over an all-zero key.
        expect(failures, "aes-128", "69c4e0d86a7b0430d8cdb78070b4c55a",
                hex(new Aes(hexBytes("000102030405060708090a0b0c0d0e0f"))
                        .encryptBlock(hexBytes("00112233445566778899aabbccddeeff"))));
        expect(failures, "wsc-kdf", "09b364567c2ed3e9b517d70240603766e38f4098b16345dcc674beb319b68ab8",
                hex(WscCrypto.copy(WscCrypto.kdf(new byte[32], 80), 0, 32)));
        expect(failures, "wsc-dh", "3803188372c4464b68b267a41431e7583ba86108b53901bb77b1f5992f2f99c3",
                hex(WscCrypto.copy(WscCrypto.toFixed(
                        WscCrypto.DH_PRIME.valueOf(2).pow(12345).mod(WscCrypto.DH_PRIME), 192), 0, 32)));
        expect(failures, "wps-pin-checksum", "7",
                Integer.toString(WscCrypto.pinChecksum(1234567)));
        expect(failures, "wps-pin-spec", "12345670", WpsPins.finalize(1234567));
        expect(failures, "mask-count", "260",
                Long.toString(CpuCracker.count("?d?l")));
        expect(failures, "mask-first", "0a", CpuCracker.expand("?d?l").next());
        return failures.isEmpty() ? null : String.join("; ", failures);
    }

    private static void expect(List<String> failures, String name, String wanted, String actual) {
        if (!wanted.equals(actual)) failures.add(name + " wanted " + wanted + " got " + actual);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hexBytes(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte value : data) text.append(String.format("%02x", value & 0xFF));
        return text.toString();
    }
}
