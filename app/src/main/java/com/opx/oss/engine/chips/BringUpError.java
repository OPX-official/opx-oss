package com.opx.oss.engine.chips;

/**
 * A bring-up failure tagged with the stage it happened in, so the UI can say which step of the
 * card's start-up failed instead of showing a bare USB error.
 */
public class BringUpError extends java.io.IOException {

    /** Where in the start-up the failure happened. */
    public final String stage;
    /** The underlying detail, or {@code null}. */
    public final String detail;

    public BringUpError(String stage, String detail) {
        super(stage + ": " + detail);
        this.stage = stage;
        this.detail = detail;
    }

    public BringUpError(String stage, String detail, Throwable cause) {
        super(stage + ": " + detail, cause);
        this.stage = stage;
        this.detail = detail;
    }
}
