package com.opx.oss.engine.crack;

import java.util.Locale;

/**
 * Word mangling rules in the shape a rule file uses: each rule is a short string of commands
 * applied left to right to one word, producing one candidate.
 * <p>
 * Supported: {@code :} none, {@code l} lower, {@code u} upper, {@code c} capitalise,
 * {@code r} reverse, {@code d} duplicate, {@code t} toggle case, {@code $X} append,
 * {@code ^X} prepend and {@code sXY} substitute.
 */
public final class Rules {

    private Rules() {
    }

    /** Apply one rule to one word; an unknown command is skipped rather than failing. */
    public static String apply(String word, String rule) {
        if (word == null) return null;
        if (rule == null || rule.isEmpty()) return word;
        String out = word;
        int at = 0;
        while (at < rule.length()) {
            char command = rule.charAt(at++);
            switch (command) {
                case ':':
                    break;
                case 'l':
                    out = out.toLowerCase(Locale.US);
                    break;
                case 'u':
                    out = out.toUpperCase(Locale.US);
                    break;
                case 'c':
                    out = out.isEmpty() ? out
                            : out.substring(0, 1).toUpperCase(Locale.US)
                            + out.substring(1).toLowerCase(Locale.US);
                    break;
                case 'r':
                    out = new StringBuilder(out).reverse().toString();
                    break;
                case 'd':
                    out = out + out;
                    break;
                case 't':
                    out = toggle(out);
                    break;
                case '$':
                    if (at < rule.length()) out = out + rule.charAt(at++);
                    break;
                case '^':
                    if (at < rule.length()) out = rule.charAt(at++) + out;
                    break;
                case 's':
                    if (at + 1 < rule.length()) {
                        char from = rule.charAt(at++);
                        char to = rule.charAt(at++);
                        out = out.replace(from, to);
                    }
                    break;
                default:
                    // A command this engine does not implement: leave the word as it is.
                    break;
            }
        }
        return out;
    }

    private static String toggle(String word) {
        StringBuilder out = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append(Character.toLowerCase(c));
            } else if (Character.isLowerCase(c)) {
                out.append(Character.toUpperCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** A rule file is one rule per line, blank lines and {@code #} comments skipped. */
    public static String[] parse(String text) {
        String[] lines = text.split("\n");
        int count = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) count++;
        }
        String[] rules = new String[count];
        int at = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            rules[at++] = trimmed;
        }
        return rules;
    }
}
