package com.opx.oss.engine.crack;

import com.opx.oss.engine.capture.Handshake;
import com.opx.oss.engine.capture.Verify;
import com.opx.oss.engine.crypto.WpaKeys;
import com.opx.oss.engine.dot11.Mac;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.BooleanSupplier;

/**
 * The in-process passphrase search: one candidate at a time through our own PBKDF2 and MIC
 * check, with a progress callback so the app can paint a rate, and a stop flag it can set.
 */
public final class CpuCracker {

    /** How often the search reports back, in candidates. */
    public static final int REPORT_EVERY = 2500;

    /** A candidate that reproduced a captured MIC. */
    public static final class Match {
        public final String psk;
        public final Handshake handshake;
        public final long tried;
        public final long millis;

        Match(String psk, Handshake handshake, long tried, long millis) {
            this.psk = psk;
            this.handshake = handshake;
            this.tried = tried;
            this.millis = millis;
        }
    }

    /** Told how far the search has come; {@code total} is negative for a wordlist. */
    public interface Progress {
        void onProgress(long tried, long total, String candidate);
    }

    private CpuCracker() {
    }

    /**
     * Walk the candidates against every target until one reproduces a captured MIC, the
     * candidates run out, or {@code stop} turns true.
     */
    public static Match crack(List<Handshake> targets, Iterator<String> candidates, long total,
                              Progress progress, BooleanSupplier stop) {
        long start = System.currentTimeMillis();
        long tried = 0;
        while (candidates.hasNext()) {
            if (stop != null && stop.getAsBoolean()) return null;
            String candidate = candidates.next();
            tried++;
            byte[] pmk = null;
            for (Handshake handshake : targets) {
                if (handshake.ssid == null || handshake.ssid.isEmpty()) continue;
                if (pmk == null) {
                    pmk = WpaKeys.pmk(candidate, handshake.ssid.getBytes(StandardCharsets.UTF_8));
                }
                boolean found = handshake.mic != null
                        ? Verify.pmk(handshake, pmk)
                        : java.util.Arrays.equals(
                                WpaKeys.pmkid(pmk, Mac.of(handshake.bssid), Mac.of(handshake.station)),
                                handshake.pmkid);
                if (found) {
                    return new Match(candidate, handshake, tried,
                            System.currentTimeMillis() - start);
                }
            }
            if (progress != null && tried % REPORT_EVERY == 0) {
                progress.onProgress(tried, total, candidate);
            }
        }
        if (progress != null) progress.onProgress(tried, total, null);
        return null;
    }

    /** A wordlist whose words are mangled by {@code rule} before they are tried. */
    public static Iterator<String> wordlist(File file, String rule) throws IOException {
        final Iterator<String> words = wordlist(file);
        if (rule == null || rule.isEmpty()) return words;
        return new Iterator<String>() {
            @Override
            public boolean hasNext() {
                return words.hasNext();
            }

            @Override
            public String next() {
                return Rules.apply(words.next(), rule);
            }
        };
    }

    /** A wordlist file, read lazily so a large list costs no memory up front. */
    public static Iterator<String> wordlist(File file) throws IOException {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8));
        return new Iterator<String>() {
            private String next = advance();

            private String advance() {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isEmpty()) return line;
                    }
                    reader.close();
                    return null;
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public String next() {
                if (next == null) throw new NoSuchElementException();
                String current = next;
                next = advance();
                return current;
            }
        };
    }

    /** How many candidates a mask such as {@code ?d?d?d?d?d?d?d?d} produces. */
    public static long count(String mask) {
        long total = 1;
        for (int i = 0; i < mask.length(); i++) {
            char c = mask.charAt(i);
            if (c != '?') continue;
            if (i + 1 >= mask.length()) break;
            total *= alphabet(mask.charAt(++i)).length;
            if (total <= 0) return Long.MAX_VALUE;
        }
        return total;
    }

    /** Expand a mask lazily: {@code ?d} digits, {@code ?l} lower, {@code ?u} upper, {@code ?s} symbols. */
    public static Iterator<String> expand(String mask) {
        final char[][] slots = slots(mask);
        final long total = count(mask);
        return new Iterator<String>() {
            private long index;

            @Override
            public boolean hasNext() {
                return index < total;
            }

            @Override
            public String next() {
                if (!hasNext()) throw new NoSuchElementException();
                long value = index++;
                StringBuilder text = new StringBuilder(slots.length);
                for (int slot = slots.length - 1; slot >= 0; slot--) {
                    char[] alphabet = slots[slot];
                    text.append(alphabet[(int) (value % alphabet.length)]);
                    value /= alphabet.length;
                }
                return text.reverse().toString();
            }
        };
    }

    private static char[][] slots(String mask) {
        StringBuilder literals = new StringBuilder();
        for (int i = 0; i < mask.length(); i++) {
            char c = mask.charAt(i);
            if (c == '?' && i + 1 < mask.length()) {
                literals.append('\u0000').append(mask.charAt(++i));
            } else {
                literals.append('\u0001').append(c);
            }
        }
        String spec = literals.toString();
        int slots = 0;
        for (int i = 0; i < spec.length(); i += 2) {
            if (spec.charAt(i) == '\u0000') slots++;
        }
        char[][] table = new char[slots][];
        int at = 0;
        for (int i = 0; i < spec.length(); i += 2) {
            if (spec.charAt(i) == '\u0000') table[at++] = alphabet(spec.charAt(i + 1));
        }
        return table;
    }

    private static char[] alphabet(char kind) {
        switch (kind) {
            case 'd':
                return "0123456789".toCharArray();
            case 'l':
                return "abcdefghijklmnopqrstuvwxyz".toCharArray();
            case 'u':
                return "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
            case 's':
                return "!@#$%^&*()-_=+".toCharArray();
            default:
                return new char[]{kind};
        }
    }

    /** The PMKID check alone, for a line that carries no EAPOL pair. */
    public static boolean pmkid(String passphrase, String ssid, byte[] pmkid,
                                String bssid, String station) {
        byte[] pmk = WpaKeys.pmk(passphrase, ssid.getBytes(StandardCharsets.UTF_8));
        return java.util.Arrays.equals(
                WpaKeys.pmkid(pmk, Mac.of(bssid), Mac.of(station)), pmkid);
    }
}
