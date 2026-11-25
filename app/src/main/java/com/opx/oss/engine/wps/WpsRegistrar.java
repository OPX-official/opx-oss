package com.opx.oss.engine.wps;

import com.opx.oss.engine.dot11.wsc.WscCrypto;
import com.opx.oss.engine.dot11.wsc.WscMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * The registrar half of a WPS PIN registration: it answers the enrollee's M1 with M2, proves
 * knowledge of the first PIN half in M4, confirms the second in M6, and hands the network
 * credentials over in M8. Split-PIN means a wrong half is answered with a NACK, so a caller can
 * walk the keyspace one half at a time.
 */
public final class WpsRegistrar {

    /** What the registrar tells its caller after each message. */
    public static final class Step {
        public final byte[] reply;
        public final boolean firstHalfAccepted;
        public final boolean done;
        public final String credential;

        Step(byte[] reply, boolean firstHalfAccepted, boolean done, String credential) {
            this.reply = reply;
            this.firstHalfAccepted = firstHalfAccepted;
            this.done = done;
            this.credential = credential;
        }
    }

    private final String pin;
    private final byte[] enrolleeMac;
    private final byte[] registrarMac;
    private final byte[] ssid;
    private final String passphrase;
    private final int authType;
    private final int encryptionType;

    private WscCrypto.KeyPair keyPair;
    private byte[] enrolleePublicKey;
    private byte[] enrolleeNonce;
    private byte[] registrarNonce;
    private byte[] authKey;
    private byte[] keyWrapKey;
    private byte[] psk1;
    private byte[] psk2;
    private byte[] eHash1;
    private byte[] eHash2;
    private byte[] lastMessage;
    private int state;

    public WpsRegistrar(String pin, byte[] enrolleeMac, byte[] registrarMac, byte[] ssid,
                        String passphrase) {
        this(pin, enrolleeMac, registrarMac, ssid, passphrase, 0x0020, 0x0008);
    }

    public WpsRegistrar(String pin, byte[] enrolleeMac, byte[] registrarMac, byte[] ssid,
                        String passphrase, int authType, int encryptionType) {
        this.pin = pin;
        this.enrolleeMac = enrolleeMac;
        this.registrarMac = registrarMac;
        this.ssid = ssid;
        this.passphrase = passphrase;
        this.authType = authType;
        this.encryptionType = encryptionType;
    }

    /**
     * Take one WSC message from the enrollee and return what to send back. A NACK is returned
     * when a PIN half is wrong, so the caller can move on to its next candidate.
     */
    public Step feed(byte[] message) {
        WscMessage incoming = WscMessage.parse(message);
        byte[] type = incoming.get(WscMessage.ATTR_MSG_TYPE);
        int number = type == null ? -1 : type[0] & 0xFF;

        if (number == WscMessage.MSG_M1) {
            enrolleePublicKey = incoming.get(WscMessage.ATTR_PUBLIC_KEY);
            enrolleeNonce = incoming.get(WscMessage.ATTR_ENROLLEE_NONCE);
            if (enrolleePublicKey == null || enrolleeNonce == null) {
                return nack(0x01);
            }
            keyPair = WscCrypto.dhGenerate();
            registrarNonce = WscCrypto.nonce();
            byte[] shared = WscCrypto.dhShared(enrolleePublicKey, keyPair.secret);
            byte[][] keys = WscCrypto.deriveKeys(shared, enrolleeNonce, enrolleeMac, registrarNonce);
            authKey = keys[0];
            keyWrapKey = keys[1];
            byte[][] psk = WscCrypto.derivePsk(authKey, pin);
            psk1 = psk[0];
            psk2 = psk[1];
            eHash1 = WscCrypto.eOrRHash(authKey, registrarNonce, psk1,
                    enrolleePublicKey, keyPair.publicKey);
            eHash2 = WscCrypto.eOrRHash(authKey, registrarNonce, psk2,
                    enrolleePublicKey, keyPair.publicKey);

            WscMessage m2 = new WscMessage()
                    .add(WscMessage.ATTR_MSG_TYPE, WscMessage.MSG_M2)
                    .add(WscMessage.ATTR_ENROLLEE_NONCE, enrolleeNonce)
                    .add(WscMessage.ATTR_REGISTRAR_NONCE, registrarNonce)
                    .add(WscMessage.ATTR_PUBLIC_KEY, keyPair.publicKey)
                    .add(WscMessage.ATTR_AUTH_TYPE_FLAGS, new byte[]{0x00, 0x22})
                    .add(WscMessage.ATTR_ENCR_TYPE_FLAGS, new byte[]{0x00, 0x0F})
                    .add(WscMessage.ATTR_AUTHENTICATOR, new byte[8]);
            if (incoming.get(WscMessage.ATTR_UUID_E) != null) {
                m2.add(WscMessage.ATTR_UUID_E, incoming.get(WscMessage.ATTR_UUID_E));
            }
            byte[] reply = m2.authenticate(authKey, message).encode();
            lastMessage = reply;
            state = 1;
            return new Step(reply, false, false, null);
        }

        if (number == WscMessage.MSG_M3) {
            if (authKey == null || eHash1 == null) return nack(0x01);
            // M4 carries E-Hash1: only a registrar holding the right PSK1 produces it, and the
            // enrollee answers M5 when it matches. A wrong first half ends with its NACK.
            WscMessage m4 = WscMessage.m4(eHash1);
            byte[] reply = m4.authenticate(authKey, lastMessage).encode();
            lastMessage = reply;
            state = 3;
            return new Step(reply, true, false, null);
        }

        if (number == WscMessage.MSG_M5) {
            // The enrollee proved the first half; send the wrapped settings.
            WscMessage settings = networkSettings();
            byte[] plaintext = settings.body();
            byte[] iv = WscCrypto.nonce();
            byte[] encrypted = WscCrypto.wrap(keyWrapKey, iv, plaintext);
            byte[] kwa = WscCrypto.keyWrapAuthenticator(authKey, plaintext);
            WscMessage m6 = WscMessage.m6(encrypted, iv, kwa);
            byte[] reply = m6.authenticate(authKey, lastMessage).encode();
            lastMessage = reply;
            state = 5;
            return new Step(reply, true, false, null);
        }

        if (number == WscMessage.MSG_M7) {
            state = 7;
            return new Step(null, true, true, passphrase);
        }

        return nack(0x01);
    }

    private Step nack(int error) {
        state = -1;
        byte[] reply = WscMessage.nack(error).encode();
        return new Step(reply, false, false, null);
    }

    /** The credential block the enrollee unwraps with KeyWrapKey. */
    private WscMessage networkSettings() {
        WscMessage settings = new WscMessage();
        settings.add(WscMessage.ATTR_SSID, ssid);
        settings.add(WscMessage.ATTR_AUTH_TYPE, new byte[]{(byte) (authType >> 8), (byte) authType});
        settings.add(WscMessage.ATTR_ENCR_TYPE, new byte[]{(byte) (encryptionType >> 8),
                (byte) encryptionType});
        settings.add(WscMessage.ATTR_NETWORK_KEY, passphrase.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        settings.add(WscMessage.ATTR_MAC_ADDR, registrarMac);
        return settings;
    }

    /** The PIN this registrar is proving, for the caller's log. */
    public String pin() {
        return pin;
    }

    /** Which message the exchange is waiting for next. */
    public int state() {
        return state;
    }

    /** The network key handed over at the end of a successful exchange. */
    public String credential() {
        return passphrase;
    }

    /** Both PIN halves, in the shape an offline check wants them. */
    public List<byte[]> hashes() {
        List<byte[]> out = new ArrayList<>(2);
        out.add(eHash1);
        out.add(eHash2);
        return out;
    }
}
