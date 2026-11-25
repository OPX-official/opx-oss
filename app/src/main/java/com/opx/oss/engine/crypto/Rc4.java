package com.opx.oss.engine.crypto;

/** RC4: WEP's key stream and the TKIP/legacy MIC path. */
public final class Rc4 {

    private final int[] state = new int[256];
    private int i;
    private int j;

    public Rc4(byte[] key) {
        for (int k = 0; k < 256; k++) state[k] = k;
        int jj = 0;
        for (int k = 0; k < 256; k++) {
            jj = (jj + state[k] + (key[k % key.length] & 0xFF)) & 0xFF;
            int swap = state[k];
            state[k] = state[jj];
            state[jj] = swap;
        }
    }

    /** Encrypt or decrypt in place; both directions are the same operation. */
    public byte[] process(byte[] data) {
        byte[] out = new byte[data.length];
        for (int n = 0; n < data.length; n++) {
            i = (i + 1) & 0xFF;
            j = (j + state[i]) & 0xFF;
            int swap = state[i];
            state[i] = state[j];
            state[j] = swap;
            out[n] = (byte) (data[n] ^ state[(state[i] + state[j]) & 0xFF]);
        }
        return out;
    }
}
