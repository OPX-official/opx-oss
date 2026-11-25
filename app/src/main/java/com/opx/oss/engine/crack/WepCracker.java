package com.opx.oss.engine.crack;

import com.opx.oss.engine.crypto.Rc4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * WEP key recovery from collected IV/keystream samples: a port of the additive PTW vote
 * scheme. Each packet votes for the running sum of the root key bytes; the sums do not depend
 * on the packet's IV, so votes add up, and {@link #recover()} differences the winning sums
 * into key bytes and proves the candidate by re-deriving held-back keystreams.
 */
public final class WepCracker {

    /** Root key lengths tried, in bytes: 40-bit and 104-bit. */
    public static final int[] KEY_LENGTHS = {5, 13};

    /** IVs at which a recovery attempt is worth the work. */
    public static final int READY_THRESHOLD = 10_000;

    private static final int DEPTH_CAP = 16;
    private static final int MAX_TRIALS = 100_000;
    private static final int VERIFY_SAMPLES = 8;
    private static final int VERIFY_TOLERANCE = 1;

    private final int maxKeyLength;
    private final int[][] votes;
    private final List<byte[]> samples = new ArrayList<>();
    private int count;

    public WepCracker() {
        this(KEY_LENGTHS);
    }

    public WepCracker(int[] keyLengths) {
        int longest = 5;
        for (int length : keyLengths) longest = Math.max(longest, length);
        this.maxKeyLength = longest;
        this.votes = new int[3 + longest][256];
    }

    /** One packet: its public IV and the keystream bytes recovered from known plaintext. */
    public void feed(byte[] iv, byte[] keystream) {
        if (iv == null || keystream == null) return;
        if (iv.length < 3 || keystream.length < 2 + maxKeyLength) return;

        int[] state = new int[256];
        for (int i = 0; i < 256; i++) state[i] = i;
        int j = 0;
        for (int i = 0; i < 3; i++) {
            j = (j + state[i] + (iv[i] & 0xFF)) & 0xFF;
            int swap = state[i];
            state[i] = state[j];
            state[j] = swap;
        }
        int[] inverse = new int[256];
        for (int x = 0; x < 256; x++) inverse[state[x]] = x;

        int cumulative = j;
        for (int i = 3; i < 3 + maxKeyLength; i++) {
            cumulative = (cumulative + state[i]) & 0xFF;
            int ks = keystream[i - 1] & 0xFF;
            int sigma = (inverse[(i - ks) & 0xFF] - cumulative) & 0xFF;
            votes[i][sigma]++;
        }
        count++;
        if (samples.size() < 16) {
            samples.add(Arrays.copyOf(iv, 3));
            samples.add(Arrays.copyOf(keystream, keystream.length));
        }
    }

    /** The recovered root key, or {@code null} while the votes do not decide. */
    public byte[] recover() {
        if (count == 0) return null;
        for (int keyLength : KEY_LENGTHS) {
            if (keyLength > maxKeyLength) continue;
            int[][] ranked = new int[keyLength][256];
            for (int m = 0; m < keyLength; m++) {
                Integer[] order = new Integer[256];
                for (int v = 0; v < 256; v++) order[v] = v;
                final int index = 3 + m;
                Arrays.sort(order, (a, b) -> Integer.compare(votes[index][b], votes[index][a]));
                for (int v = 0; v < 256; v++) ranked[m][v] = order[v];
            }
            byte[] key = search(ranked, keyLength);
            if (key != null) return key;
        }
        return null;
    }

    private byte[] search(int[][] ranked, int keyLength) {
        PriorityQueue<int[]> queue = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        Set<String> seen = new HashSet<>();
        int[] start = new int[keyLength];
        queue.add(encode(start));
        seen.add(encode(start).toString());
        int trials = 0;
        while (!queue.isEmpty() && trials < MAX_TRIALS) {
            int[] entry = queue.poll();
            int[] ranks = Arrays.copyOfRange(entry, 1, entry.length);
            byte[] sigmas = new byte[keyLength];
            for (int m = 0; m < keyLength; m++) sigmas[m] = (byte) ranked[m][ranks[m]];
            trials++;
            byte[] key = keyFrom(sigmas);
            if (verify(key)) return key;
            for (int m = 0; m < keyLength; m++) {
                if (ranks[m] + 1 >= DEPTH_CAP) continue;
                int[] next = ranks.clone();
                next[m]++;
                int[] encoded = encode(next);
                if (seen.add(encoded.toString())) queue.add(encoded);
            }
        }
        return null;
    }

    private static int[] encode(int[] ranks) {
        int[] encoded = new int[ranks.length + 1];
        int sum = 0;
        for (int i = 0; i < ranks.length; i++) {
            encoded[i + 1] = ranks[i];
            sum += ranks[i];
        }
        encoded[0] = sum;
        return encoded;
    }

    private static byte[] keyFrom(byte[] sigmas) {
        byte[] key = new byte[sigmas.length];
        int previous = 0;
        for (int m = 0; m < sigmas.length; m++) {
            int value = sigmas[m] & 0xFF;
            key[m] = (byte) ((value - previous) & 0xFF);
            previous = value;
        }
        return key;
    }

    private boolean verify(byte[] key) {
        if (samples.isEmpty()) return false;
        int mismatches = 0;
        int checks = Math.min(VERIFY_SAMPLES, samples.size() / 2);
        for (int index = 0; index < checks; index++) {
            byte[] iv = samples.get(index * 2);
            byte[] keystream = samples.get(index * 2 + 1);
            byte[] combined = new byte[iv.length + key.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(key, 0, combined, iv.length, key.length);
            byte[] produced = new Rc4(combined).process(new byte[keystream.length]);
            if (!Arrays.equals(Arrays.copyOf(produced, Math.min(produced.length, keystream.length)),
                    Arrays.copyOf(keystream, Math.min(produced.length, keystream.length)))) {
                mismatches++;
                if (mismatches > VERIFY_TOLERANCE) return false;
            }
        }
        return true;
    }

    public int sampleCount() {
        return count;
    }

    public boolean ready() {
        return count >= READY_THRESHOLD;
    }
}
