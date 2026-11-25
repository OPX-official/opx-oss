package com.opx.oss.engine.dot11.wsc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * WSC attributes and the eight registration messages, as hostapd builds them: big-endian TLVs,
 * a 102-byte header of fixed attributes, then the optional ones, then the Authenticator.
 */
public final class WscMessage {

    public static final int ATTR_AP_CHANNEL = 0x1001;
    public static final int ATTR_ASSOC_STATE = 0x1002;
    public static final int ATTR_AUTH_TYPE = 0x1003;
    public static final int ATTR_AUTH_TYPE_FLAGS = 0x1004;
    public static final int ATTR_AUTHENTICATOR = 0x1005;
    public static final int ATTR_CONFIG_METHODS = 0x1008;
    public static final int ATTR_CONFIG_ERROR = 0x1009;
    public static final int ATTR_CONFIRM_URL4 = 0x100A;
    public static final int ATTR_CONFIRM_URL6 = 0x100B;
    public static final int ATTR_CONN_TYPE = 0x100C;
    public static final int ATTR_CONN_TYPE_FLAGS = 0x100D;
    public static final int ATTR_CRED = 0x100E;
    public static final int ATTR_ENCR_TYPE = 0x100F;
    public static final int ATTR_ENCR_TYPE_FLAGS = 0x1010;
    public static final int ATTR_ENCR_SETTINGS = 0x1018;
    public static final int ATTR_ENROLLEE_NONCE = 0x101A;
    public static final int ATTR_FEATURE_ID = 0x101B;
    public static final int ATTR_IDENTITY = 0x101C;
    public static final int ATTR_KEY_WRAP_AUTH = 0x101E;
    public static final int ATTR_KEY_ID = 0x101F;
    public static final int ATTR_MAC_ADDR = 0x1020;
    public static final int ATTR_MANUFACTURER = 0x1021;
    public static final int ATTR_MSG_TYPE = 0x1022;
    public static final int ATTR_MODEL_NAME = 0x1023;
    public static final int ATTR_MODEL_NUMBER = 0x1024;
    public static final int ATTR_NETWORK_INDEX = 0x1026;
    public static final int ATTR_NETWORK_KEY = 0x1027;
    public static final int ATTR_NETWORK_KEY_INDEX = 0x1028;
    public static final int ATTR_NEW_DEVICE_NAME = 0x102A;
    public static final int ATTR_NEW_PASSWORD = 0x102B;
    public static final int ATTR_OS_VERSION = 0x102D;
    public static final int ATTR_POWER_LEVEL = 0x102F;
    public static final int ATTR_PSK_CURRENT = 0x1030;
    public static final int ATTR_PSK_MAX = 0x1031;
    public static final int ATTR_PUBLIC_KEY = 0x1032;
    public static final int ATTR_RADIO_ENABLED = 0x1033;
    public static final int ATTR_REBOOT = 0x1034;
    public static final int ATTR_REGISTRAR_CURRENT = 0x1035;
    public static final int ATTR_REGISTRAR_ESTABLISHED = 0x1036;
    public static final int ATTR_REGISTRAR_LIST = 0x1037;
    public static final int ATTR_REGISTRAR_MAX = 0x1038;
    public static final int ATTR_REGISTRAR_NONCE = 0x1039;
    public static final int ATTR_REQUEST_TYPE = 0x103A;
    public static final int ATTR_RESPONSE_TYPE = 0x103B;
    public static final int ATTR_RF_BANDS = 0x103C;
    public static final int ATTR_SECRET_NONCE = 0x103F;
    public static final int ATTR_SSID = 0x1045;
    public static final int ATTR_TOTAL_NETWORKS = 0x1046;
    public static final int ATTR_UUID_E = 0x1047;
    public static final int ATTR_UUID_R = 0x1048;
    public static final int ATTR_VERSION = 0x104A;
    public static final int ATTR_WPS_STATE = 0x1044;
    public static final int ATTR_VENDOR_EXT = 0x1049;
    public static final int ATTR_R_HASH1 = 0x103D;
    public static final int ATTR_R_HASH2 = 0x103E;
    public static final int ATTR_E_HASH1 = 0x1014;
    public static final int ATTR_E_HASH2 = 0x1015;
    public static final int ATTR_E_SNONCE1 = 0x1016;
    public static final int ATTR_E_SNONCE2 = 0x1017;

    /** Message numbers of the registration protocol. */
    public static final int MSG_M1 = 0x04;
    public static final int MSG_M2 = 0x05;
    public static final int MSG_M3 = 0x07;
    public static final int MSG_M4 = 0x08;
    public static final int MSG_M5 = 0x09;
    public static final int MSG_M6 = 0x0A;
    public static final int MSG_M7 = 0x0B;
    public static final int MSG_M8 = 0x0C;
    public static final int MSG_WSC_DONE = 0x0F;
    public static final int MSG_WSC_NACK = 0x0E;

    /** One attribute: its id and its bytes. */
    public static final class Attribute {
        public final int id;
        public final byte[] value;

        public Attribute(int id, byte[] value) {
            this.id = id;
            this.value = value;
        }
    }

    private final List<Attribute> attributes = new ArrayList<>();

    public WscMessage add(int id, byte[] value) {
        attributes.add(new Attribute(id, value));
        return this;
    }

    public WscMessage add(int id, int value) {
        return add(id, new byte[]{(byte) value});
    }

    public WscMessage addText(int id, String text) {
        return add(id, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Attribute ids in the order they were added. */
    public List<Integer> ids() {
        List<Integer> ids = new ArrayList<>(attributes.size());
        for (Attribute attribute : attributes) ids.add(attribute.id);
        return ids;
    }

    /** The first attribute with this id, or {@code null}. */
    public byte[] get(int id) {
        for (Attribute attribute : attributes) {
            if (attribute.id == id) return attribute.value;
        }
        return null;
    }

    /** Everything before the Authenticator, which is what its HMAC covers. */
    public byte[] body() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Attribute attribute : attributes) {
            if (attribute.id == ATTR_AUTHENTICATOR) continue;
            out.write(attribute.id >> 8);
            out.write(attribute.id & 0xFF);
            out.write((attribute.value.length >> 8) & 0xFF);
            out.write(attribute.value.length & 0xFF);
            out.write(attribute.value, 0, attribute.value.length);
        }
        return out.toByteArray();
    }

    public byte[] encode() {
        return body();
    }

    /** Append the Authenticator over this message and the one before it. */
    public WscMessage authenticate(byte[] authKey, byte[] previousMessage) {
        byte[] authenticator = WscCrypto.authenticator(authKey, previousMessage, body());
        return add(ATTR_AUTHENTICATOR, authenticator);
    }

    /** True when the message carries the Authenticator the key says it should. */
    public boolean authentic(byte[] authKey, byte[] previousMessage) {
        byte[] given = get(ATTR_AUTHENTICATOR);
        if (given == null) return false;
        byte[] expected = WscCrypto.authenticator(authKey, previousMessage, bodyWithout(ATTR_AUTHENTICATOR));
        return java.util.Arrays.equals(given, expected);
    }

    private byte[] bodyWithout(int skip) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Attribute attribute : attributes) {
            if (attribute.id == skip) continue;
            out.write(attribute.id >> 8);
            out.write(attribute.id & 0xFF);
            out.write((attribute.value.length >> 8) & 0xFF);
            out.write(attribute.value.length & 0xFF);
            out.write(attribute.value, 0, attribute.value.length);
        }
        return out.toByteArray();
    }

    /** Parse an attribute stream; a truncated attribute ends the list. */
    public static WscMessage parse(byte[] data) {
        WscMessage message = new WscMessage();
        int at = 0;
        while (at + 4 <= data.length) {
            int id = ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
            int length = ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
            at += 4;
            if (length < 0 || at + length > data.length) break;
            message.add(id, WscCrypto.copy(data, at, length));
            at += length;
        }
        return message;
    }

    /** M1: the enrollee's public key and nonce, asking the registrar to start. */
    public static WscMessage m1(byte[] enrolleeMac, byte[] publicKey, byte[] nonce, int configMethods) {
        return new WscMessage()
                .add(ATTR_MSG_TYPE, MSG_M1)
                .add(ATTR_UUID_E, new byte[16])
                .add(ATTR_MAC_ADDR, enrolleeMac)
                .add(ATTR_ENCR_TYPE_FLAGS, new byte[]{0x00, 0x0F})
                .add(ATTR_AUTH_TYPE_FLAGS, new byte[]{0x00, 0x22})
                .add(ATTR_CONFIG_METHODS, new byte[]{(byte) (configMethods >> 8), (byte) configMethods})
                .add(ATTR_ENROLLEE_NONCE, nonce)
                .add(ATTR_PUBLIC_KEY, publicKey);
    }

    /** M3: the registrar's public key and nonce, with its hashes of the first PIN half. */
    public static WscMessage m3(byte[] registrarMac, byte[] publicKey, byte[] nonce,
                                byte[] eHash1, byte[] eHash2) {
        return new WscMessage()
                .add(ATTR_MSG_TYPE, MSG_M3)
                .add(ATTR_REGISTRAR_NONCE, nonce)
                .add(ATTR_PUBLIC_KEY, publicKey)
                .add(ATTR_E_HASH1, eHash1)
                .add(ATTR_E_HASH2, eHash2)
                .add(ATTR_MAC_ADDR, registrarMac);
    }

    /** M5: the first half was right; here is the hash of the second. */
    public static WscMessage m5(byte[] eHash2) {
        return new WscMessage().add(ATTR_MSG_TYPE, MSG_M5).add(ATTR_E_HASH2, eHash2);
    }

    /** M6: the wrapped network settings. */
    public static WscMessage m6(byte[] encryptedSettings, byte[] iv, byte[] keyWrapAuth) {
        return new WscMessage()
                .add(ATTR_MSG_TYPE, MSG_M6)
                .add(ATTR_ENCR_SETTINGS, concat(iv, encryptedSettings))
                .add(ATTR_KEY_WRAP_AUTH, keyWrapAuth);
    }

    /** M7: the registrar accepted the enrollee's credentials. */
    public static WscMessage m7(byte[] keyWrapAuth) {
        return new WscMessage().add(ATTR_MSG_TYPE, MSG_M7).add(ATTR_KEY_WRAP_AUTH, keyWrapAuth);
    }

    /** WSC_NACK with the config error the AP reported. */
    public static WscMessage nack(int configError) {
        return new WscMessage().add(ATTR_MSG_TYPE, MSG_WSC_NACK)
                .add(ATTR_CONFIG_ERROR, configError);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
