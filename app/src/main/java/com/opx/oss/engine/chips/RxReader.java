package com.opx.oss.engine.chips;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The shared USB bulk-IN read loop every driver uses, ported from the reference
 * {@code chips/rx_reader.py}: blocking reads on their own thread, pause/resume for channel hops,
 * a consecutive-error limit, and a fatal callback for an unplug or a wedged pipe.
 * <p>
 * The reference also batches buffers and hands them to an asyncio loop because its dispatch is
 * async; here dispatch is synchronous on this thread, so the batching/backlog numbers only survive
 * as the empty-read backoff and drop accounting they were protecting.
 */
public final class RxReader {

    private static final int PAUSE_POLL_MS = 3;
    private static final int EMPTY_SPIN_LIMIT = 8;
    private static final int EMPTY_BACKOFF_MS = 1;
    private static final int ERROR_BACKOFF_MS = 10;

    /** One blocking read; an empty array (or {@code null}) means "nothing arrived". */
    public interface ReadOnce {
        byte[] read() throws IOException;
    }

    /** Sink for the frame bytes of one successful read. */
    public interface Dispatch {
        void dispatch(byte[] buffer, int length);
    }

    /** Called once when the reader gives up: unplug, or too many consecutive errors. */
    public interface Fatal {
        void onFatal(IOException error);
    }

    private final ReadOnce readOnce;
    private final Dispatch dispatch;
    private final Fatal fatal;
    private final int maxErrors;
    private final String name;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pauseRequested = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean fatalFired = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicInteger consecutiveErrors = new AtomicInteger();
    private volatile Thread thread;

    public RxReader(ReadOnce readOnce, Dispatch dispatch, Fatal fatal, String name) {
        this(readOnce, dispatch, fatal, name, 5);
    }

    public RxReader(ReadOnce readOnce, Dispatch dispatch, Fatal fatal, String name, int maxErrors) {
        this.readOnce = readOnce;
        this.dispatch = dispatch;
        this.fatal = fatal;
        this.name = name;
        this.maxErrors = maxErrors;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        thread = new Thread(this::run, name);
        thread.setDaemon(true);
        thread.start();
    }

    /** Stop reading and wait up to {@code joinMillis} for the blocking read to return. */
    public void stop(long joinMillis) {
        running.set(false);
        Thread stopping = thread;
        thread = null;
        if (stopping != null) {
            try {
                stopping.join(joinMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean running() {
        return running.get();
    }

    /** Stop issuing bulk reads and wait until the thread is parked; false on timeout. */
    public boolean pause(long timeoutMillis) {
        pauseRequested.set(true);
        if (!running.get()) return true;
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (paused.get()) return true;
            sleep(1);
        }
        return false;
    }

    public void resume() {
        paused.set(false);
        pauseRequested.set(false);
    }

    public long dropped() {
        return dropped.get();
    }

    private void run() {
        int empty = 0;
        while (running.get()) {
            if (pauseRequested.get()) {
                paused.set(true);
                sleep(PAUSE_POLL_MS);
                continue;
            }
            paused.set(false);

            byte[] buffer;
            int length;
            try {
                buffer = readOnce.read();
                consecutiveErrors.set(0);
            } catch (IOException error) {
                int streak = consecutiveErrors.incrementAndGet();
                if (isFatal(error, streak)) break;
                sleep(ERROR_BACKOFF_MS);
                continue;
            }

            if (buffer == null || buffer.length == 0) {
                empty++;
                if (empty >= EMPTY_SPIN_LIMIT) sleep(EMPTY_BACKOFF_MS);
                continue;
            }
            empty = 0;
            length = buffer.length;
            try {
                dispatch.dispatch(buffer, length);
            } catch (RuntimeException ignored) {
                // A sink that throws must not stop the reader.
                dropped.incrementAndGet();
            }
        }
        running.set(false);
    }

    private boolean isFatal(IOException error, int streak) {
        if (streak >= maxErrors) {
            fireFatal(error);
            return true;
        }
        return false;
    }

    private void fireFatal(IOException error) {
        if (fatal == null || !fatalFired.compareAndSet(false, true)) return;
        fatal.onFatal(error);
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
