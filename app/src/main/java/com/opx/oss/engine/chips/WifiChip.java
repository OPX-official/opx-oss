package com.opx.oss.engine.chips;

import java.io.Closeable;
import java.io.IOException;

/** What the engine needs from any adapter: a channel, a transmitter and a stream of MPDUs. */
public interface WifiChip extends Closeable {

    /** Frames that arrive from the radio, with the signal the device measured. */
    interface FrameSink {
        void onFrame(byte[] mpdu, int rssi);
    }

    /** The model the user sees, e.g. "AR9271". */
    String name();

    /** The card's own MAC once bring-up has read it, else {@code null}. */
    String mac();

    /** Every channel this card can tune to. */
    int[] supportedChannels();

    /** Bring the card up: firmware/register programming, then the radio. */
    void open() throws IOException;

    boolean isUp();

    /** Tune to one channel. */
    void setChannel(int channel) throws IOException;

    /** Transmit one MPDU (no FCS: the card adds it). */
    void transmit(byte[] mpdu) throws IOException;

    /** Start the receive thread feeding {@code sink}. */
    void startReceive(FrameSink sink) throws IOException;

    /** Link-layer ACKs the radio has handed up since the last reset; the silicon ACKs for us. */
    default long acksSeen() {
        return 0;
    }

    /** Clear the ACK tally, e.g. before a probe burst. */
    default void resetAcks() {
    }
}
