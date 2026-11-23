package com.opx.oss;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * The app's own rolling log: every interesting thing the engine, the USB bridge and the service
 * do is timestamped and kept here so the Logs screen can show it and copy it.
 * <p>
 * The buffer is memory-only (a ring of the last {@value #MAX_ENTRIES} lines) plus one file,
 * {@code files/opx-logs.txt}, that survives a process restart until the next install.
 */
public final class AppLog {

    private static final String TAG = "OpxLog";
    private static final int MAX_ENTRIES = 500;

    private static final Object LOCK = new Object();
    private static final Deque<String> ENTRIES = new ArrayDeque<>();

    private static Writer fileWriter;
    private static boolean fileStarted;

    private AppLog() {
    }

    /** One line: time, tag, message. Truncated to 400 chars so the buffer stays small. */
    public static void log(String tag, String message) {
        if (message == null) message = "";
        String line = time() + " [" + tag + "] " + truncate(message.trim(), 400);
        synchronized (LOCK) {
            ENTRIES.addLast(line);
            while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
        }
        Log.d(TAG, line);
        appendToFile(line);
    }

    public static void info(String tag, String message) {
        log(tag, message);
    }

    public static void error(String tag, Throwable throwable) {
        if (throwable != null) log(tag, String.valueOf(throwable));
    }

    /** Every buffered line, oldest first, ready to copy/paste. */
    public static String snapshot() {
        synchronized (LOCK) {
            return String.join("\n", ENTRIES);
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return ENTRIES.size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            ENTRIES.clear();
        }
        truncateFile();
    }

    /** The log file written so far, or "" when it does not exist yet. */
    public static String readFile(Context context) {
        File file = new File(context.getFilesDir(), "opx-logs.txt");
        if (!file.isFile()) return "";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
            return text.toString();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static void appendToFile(String line) {
        Context context = OpxApp.get();
        if (context == null) return;
        try {
            if (!fileStarted) {
                File file = new File(context.getFilesDir(), "opx-logs.txt");
                if (file.exists() && file.length() > 512 * 1024) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
                fileWriter = new OutputStreamWriter(
                        new FileOutputStream(file, true), java.nio.charset.StandardCharsets.UTF_8);
                fileStarted = true;
            }
            synchronized (LOCK) {
                fileWriter.write(line);
                fileWriter.write('\n');
                fileWriter.flush();
            }
        } catch (IOException ignored) {
            // Logging is best effort; a missing file never breaks the engine.
        }
    }

    private static void truncateFile() {
        Context context = OpxApp.get();
        if (context == null) return;
        try {
            synchronized (LOCK) {
                if (fileWriter != null) {
                    fileWriter.close();
                    fileWriter = null;
                    fileStarted = false;
                }
            }
            //noinspection ResultOfMethodCallIgnored
            new File(context.getFilesDir(), "opx-logs.txt").delete();
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max - 3) + "...";
    }

    private static String time() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }
}
