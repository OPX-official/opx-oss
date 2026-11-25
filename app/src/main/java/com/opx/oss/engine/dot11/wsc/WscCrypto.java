package com.opx.oss.engine.dot11.wsc;

import com.opx.oss.engine.crypto.Aes;
import com.opx.oss.engine.crypto.Hmac;
import com.opx.oss.engine.crypto.Sha256;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The cryptographic surface of Wi-Fi Simple Config: MODP-1536 Diffie-Hellman, the WSC key
 * derivation, the split-PIN hashes and the truncated authenticators, all built on our own
 * hashes and AES.
 */
public final class WscCrypto {

    public static final int NONCE_LEN = 16;
    public static final int SECRET_NONCE_LEN = 16;
    public static final int PSK_LEN = 16;
    public static final int HASH_LEN = 32;
    public static final int AUTH_KEY_LEN = 32;
    public static final int KEY_WRAP_KEY_LEN = 16;
    public static final int EMSK_LEN = 32;
    public static final int AUTHENTICATOR_LEN = 8;
    public static final int KWA_LEN = 8;
    public static final int PUBLIC_KEY_LEN = 192;

    /** Diffie-Hellman MODP group 5 (RFC 3526): 1536 bits, generator 2. */
    public static final BigInteger DH_PRIME = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"
                    + "29024E088A67CC74020BBEA63B139B22514A08798E3404DD"
                    + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245"
                    + "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
                    + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D"
                    + "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
                    + "83655D23DCA3AD961C62F356208552BB9ED529077096966D"
                    + "670C354E4ABC9804F1746C08CA237327FFFFFFFFFFFFFFFF", 16);

    private static final BigInteger GENERATOR = BigInteger.valueOf(2);
    private static final byte[] KDF_LABEL =
            "Wi-Fi Easy and Secure Key Derivation".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private WscCrypto() {
    }

    // -- hashes --------------------------------------------------------------

    public static byte[] sha256(byte[]... chunks) {
        int total = 0;
        for (byte[] chunk : chunks) total += chunk.length;
        byte[] joined = new byte[total];
        int at = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, joined, at, chunk.length);
            at += chunk.length;
        }
        return Sha256.digest(joined);
    }

    public static byte[] hmacSha256(byte[] key, byte[]... chunks) {
        int total = 0;
        for (byte[] chunk : chunks) total += chunk.length;
        byte[] joined = new byte[total];
        int at = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, joined, at, chunk.length);
            at += chunk.length;
        }
        return Hmac.hmac(Hmac.SHA256, key, joined);
    }

    // -- Diffie-Hellman ------------------------------------------------------

    /** A fresh private exponent and its zero-padded 192-byte public key. */
    public static final class KeyPair {
        public final BigInteger secret;
        public final byte[] publicKey;

        KeyPair(BigInteger secret, byte[] publicKey) {
            this.secret = secret;
            this.publicKey = publicKey;
        }
    }

    public static KeyPair dhGenerate() {
        BigInteger two = BigInteger.valueOf(2);
        BigInteger secret = new BigInteger(1536, RANDOM).mod(DH_PRIME.subtract(two)).add(two);
        byte[] publicKey = toFixed(GENERATOR.modPow(secret, DH_PRIME), PUBLIC_KEY_LEN);
        return new KeyPair(secret, publicKey);
    }

    /** g^(AB) mod p, zero-padded to 192 bytes. */
    public static byte[] dhShared(byte[] peerPublicKey, BigInteger secret) {
        BigInteger peer = new BigInteger(1, peerPublicKey);
        return toFixed(peer.modPow(secret, DH_PRIME), PUBLIC_KEY_LEN);
    }

    public static byte[] toFixed(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[length];
        if (raw.length >= length) {
            System.arraycopy(raw, raw.length - length, out, 0, length);
        } else {
            System.arraycopy(raw, 0, out, length - raw.length, raw.length);
        }
        return out;
    }

    // -- key derivation ------------------------------------------------------

    /** hostapd's {@code wps_kdf} with the fixed WSC label. */
    public static byte[] kdf(byte[] key, int length) {
        byte[] keyBits = new byte[]{
                (byte) (length >> 24), (byte) (length >> 16), (byte) (length >> 8), (byte) length};
        byte[] out = new byte[length];
        int at = 0;
        int counter = 1;
        while (at < length) {
            byte[] block = hmacSha256(key, new byte[]{
                    (byte) (counter >> 24), (byte) (counter >> 16),
                    (byte) (counter >> 8), (byte) counter}, KDF_LABEL, keyBits);
            int take = Math.min(32, length - at);
            System.arraycopy(block, 0, out, at, take);
            at += take;
            counter++;
        }
        return out;
    }

    /** AuthKey, KeyWrapKey and EMSK from the shared secret and both nonces. */
    public static byte[][] deriveKeys(byte[] shared, byte[] nonceE, byte[] enrolleeMac,
                                      byte[] nonceR) {
        byte[] dhKey = sha256(shared);
        byte[] kdk = hmacSha256(dhKey, nonceE, enrolleeMac, nonceR);
        byte[] keys = kdf(kdk, AUTH_KEY_LEN + KEY_WRAP_KEY_LEN + EMSK_LEN);
        byte[] authKey = copy(keys, 0, AUTH_KEY_LEN);
        byte[] wrapKey = copy(keys, AUTH_KEY_LEN, KEY_WRAP_KEY_LEN);
        byte[] emsk = copy(keys, AUTH_KEY_LEN + KEY_WRAP_KEY_LEN, EMSK_LEN);
        return new byte[][]{authKey, wrapKey, emsk};
    }

    /** PSK1 and PSK2 from the PIN, split at the middle of its ASCII digits. */
    public static byte[][] derivePsk(byte[] authKey, String pin) {
        byte[] digits = pin.getBytes(StandardCharsets.US_ASCII);
        int half = (digits.length + 1) / 2;
        byte[] first = hmacSha256(authKey, copy(digits, 0, half));
        byte[] second = hmacSha256(authKey, copy(digits, half, digits.length - half));
        return new byte[][]{copy(first, 0, PSK_LEN), copy(second, 0, PSK_LEN)};
    }

    public static byte[] eOrRHash(byte[] authKey, byte[] secretNonce, byte[] psk,
                                  byte[] publicKeyE, byte[] publicKeyR) {
        byte[] full = hmacSha256(authKey, secretNonce, psk, publicKeyE, publicKeyR);
        return full;
    }

    /** Authenticator = first 8 bytes of HMAC(AuthKey, previous message || this message). */
    public static byte[] authenticator(byte[] authKey, byte[] lastMessage, byte[] message) {
        byte[] full = hmacSha256(authKey, lastMessage, message);
        return copy(full, 0, AUTHENTICATOR_LEN);
    }

    /** Key Wrap Authenticator = first 8 bytes of HMAC(AuthKey, plaintext so far). */
    public static byte[] keyWrapAuthenticator(byte[] authKey, byte[] plaintext) {
        byte[] full = hmacSha256(authKey, plaintext);
        return copy(full, 0, KWA_LEN);
    }

    // -- PIN -----------------------------------------------------------------

    /** The 8th digit of a WPS PIN, from its first seven. */
    public static int pinChecksum(int seven) {
        int accum = 0;
        int value = seven;
        while (value > 0) {
            accum += 3 * (value % 10);
            value /= 10;
            accum += value % 10;
            value /= 10;
        }
        return (10 - accum % 10) % 10;
    }

    public static boolean pinIsValid(String pin) {
        if (pin == null || pin.length() != 8) return false;
        for (int i = 0; i < 8; i++) if (!Character.isDigit(pin.charAt(i))) return false;
        return pinChecksum(Integer.parseInt(pin.substring(0, 7))) == pin.charAt(7) - '0';
    }

    /** AES-128-CBC over padded data, for the encrypted settings attribute. */
    public static byte[] wrap(byte[] keyWrapKey, byte[] iv, byte[] plaintext) {
        return Aes.cbcEncrypt(keyWrapKey, iv, Aes.pad(plaintext));
    }

    public static byte[] unwrap(byte[] keyWrapKey, byte[] iv, byte[] ciphertext) {
        return Aes.unpad(Aes.cbcDecrypt(keyWrapKey, iv, ciphertext));
    }

    public static byte[] copy(byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, Math.min(length, data.length - offset));
        return out;
    }

    public static byte[] nonce() {
        byte[] value = new byte[NONCE_LEN];
        RANDOM.nextBytes(value);
        return value;
    }
}
