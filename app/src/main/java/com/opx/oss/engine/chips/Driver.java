package com.opx.oss.engine.chips;

import java.io.IOException;

/**
 * The contract every chip family's driver implements, mirroring the reference {@code chips/driver.py}:
 * the channel plan, the fake-MAC capability, a staged connect, a disconnect callback and the ACK
 * tally. {@link WifiChip} stays the minimal "tune, transmit, receive" surface the engine uses; a
 * driver is that plus everything bring-up and the UI need.
 */
public interface Driver extends WifiChip {

    /** Bring-up progress sink: percentage 0..100 and a short stage message. */
    interface Progress {
        void report(int percent, String message);
    }

    /** Why frames coming back are or are not ACKed by the silicon. */
    enum FakeMacSupport {
        /** Not ported/known yet. */
        UNIMPLEMENTED,
        /** The silicon genuinely cannot ACK a chosen MAC. */
        NONE,
        /** The card ACKs only its own burned-in MAC. */
        FIXED_MAC,
        /** The card ACKs an arbitrary forged MAC. */
        SPOOFABLE
    }

    /** 2.4 GHz channels 1-14. */
    int[] CHANNELS_2G = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
    /** 5 GHz non-DFS channels (UNII-1 + UNII-3). */
    int[] CHANNELS_5G = {36, 40, 44, 48, 149, 153, 157, 161, 165};
    /** 5 GHz DFS channels; only hopped with the DFS preference on. */
    int[] CHANNELS_DFS = {52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144};

    /** MAC whose ACKs this radio answers. */
    FakeMacSupport fakeMacSupport();

    /** Sink called once when the receive path dies for good (unplug, firmware crash). */
    interface DisconnectListener {
        void onDisconnect();
    }

    void registerDisconnect(DisconnectListener listener);

    /** Full bring-up. Defaults to {@link #open()} for drivers without a staged path. */
    default boolean connect(Progress progress) throws IOException {
        if (progress != null) progress.report(0, "bring-up");
        open();
        if (progress != null) progress.report(100, "ready");
        return true;
    }

    /** Tune; {@code scan} marks a transient hop that may take a lighter path. */
    default boolean setChannel(int channel, boolean scan) throws IOException {
        setChannel(channel);
        return true;
    }

    /** Every channel this driver can tune to, from the plan constants. */
    default int[] allChannels() {
        int[] base = supportedChannels();
        return base.clone();
    }
}
