package com.opx.oss.engine.chips.rtl8187;

import java.io.IOException;

/**
 * The radio half of the card: the four-byte PHY register window, the RF chip's bit-banged SPI
 * bus (or its 8051 fast path), the per-channel synthesiser word, and the retune sequence.
 */
public final class Rtl8187Phy {

    /** The synthesiser word for channels 1..14, exactly the order the radio expects. */
    private static final int[] CHANNEL_WORDS = {
            0x085C, 0x08DC, 0x095C, 0x09DC, 0x0A5C, 0x0ADC, 0x0B5C,
            0x0BDC, 0x0C5C, 0x0CDC, 0x0D5C, 0x0DDC, 0x0E5C, 0x0F72
    };

    /** RF register that holds the synthesiser word. */
    private static final int RF_CHANNEL_REGISTER = 0x07;
    private static final int RF_STOP_REGISTER = 0x04;
    private static final int RF_STOP_VALUE = 0x1F;

    private final Rtl8187Transport transport;
    private final int asicRevision;

    public Rtl8187Phy(Rtl8187Transport transport, int asicRevision) {
        this.transport = transport;
        this.asicRevision = asicRevision;
    }

    /** One PHY register write: address in the low byte, data above it, bit 7 marks a write. */
    public void writePhy(int address, int data) throws IOException {
        int word = ((data & 0xFFFF) << 8) | (address & 0xFF) | 0x80;
        transport.write8(Rtl8187Regs.REG_PHY + 3, (word >>> 24) & 0xFF);
        transport.write8(Rtl8187Regs.REG_PHY + 2, (word >>> 16) & 0xFF);
        transport.write8(Rtl8187Regs.REG_PHY + 1, (word >>> 8) & 0xFF);
        transport.write8(Rtl8187Regs.REG_PHY, word & 0xFF);
    }

    /** A CCK register write: the same bus with bit 16 selecting the CCK table. */
    public void writePhyCck(int address, int data) throws IOException {
        writePhy(address, data | 0x10000);
    }

    /** Write one RF register, through whichever path this silicon revision exposes. */
    public void writeRf(int address, int data) throws IOException {
        if (asicRevision != 0) {
            writeRf8051(address, data);
        } else {
            writeRfBitBang(address, data);
        }
    }

    /** Read one RF register: always the bit-bang protocol, on every silicon revision. */
    public int readRf(int address) throws IOException {
        int outputRegister = transport.read16(Rtl8187Regs.REG_RFPINSOUTPUT);
        int enableRegister = transport.read16(Rtl8187Regs.REG_RFPINSENABLE);
        int selectRegister = transport.read16(Rtl8187Regs.REG_RFPINSSELECT);
        outputRegister &= ~0xF;

        transport.write16(Rtl8187Regs.REG_RFPINSENABLE, enableRegister | 0x000F);
        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, selectRegister | 0x000F);

        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 2));
        sleepMicros(4);
        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister);
        sleepMicros(5);

        for (int bit = 4; bit >= 0; bit--) {
            int value = outputRegister | ((address >>> bit) & 1);
            if ((bit & 1) == 0) {
                transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, value);
                sleepMicros(1);
            }
            transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, value | (1 << 1));
            sleepMicros(2);
            transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, value | (1 << 1));
            sleepMicros(2);
            if ((bit & 1) != 0) {
                transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, value);
                sleepMicros(1);
            }
        }

        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 3) | (1 << 1));
        sleepMicros(2);
        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 3));
        sleepMicros(2);
        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 3));
        sleepMicros(2);

        int out = 0;
        for (int bit = 11; bit >= 0; bit--) {
            transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 3));
            sleepMicros(1);
            for (int pulse = 0; pulse < 3; pulse++) {
                transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT,
                        outputRegister | (1 << 3) | (1 << 1));
                sleepMicros(2);
            }
            if ((transport.read16(Rtl8187Regs.REG_RFPINSINPUT) & (1 << 1)) != 0) {
                out |= 1 << bit;
            }
            transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 3));
            sleepMicros(2);
        }

        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 3) | (1 << 2));
        sleepMicros(2);
        transport.write16(Rtl8187Regs.REG_RFPINSENABLE, enableRegister);
        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, selectRegister);
        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, 0x03A0);
        return out;
    }

    /** Stop the radio: the RF register every variant uses to shut the front end down. */
    public void stop() throws IOException {
        writeRf(RF_STOP_REGISTER, RF_STOP_VALUE);
    }

    /** Tune to one channel (1..14). The radio needs a moment for the PLL to settle. */
    public void setChannel(int channel) throws IOException {
        if (channel < 1 || channel > 14) {
            throw new IOException("RTL8187 tunes 2.4 GHz only, channel " + channel + " is out of range");
        }
        writeRf(RF_CHANNEL_REGISTER, CHANNEL_WORDS[channel - 1]);
        sleep(10);
    }

    /**
     * Retune the way the card expects: MAC loopback on so no frame leaks out mid-tune, the
     * channel itself, then the transmit configuration restored and the interval registers put
     * back to their defaults.
     */
    public void configChannel(int channel) throws IOException {
        int transmitConfig = transport.read32(Rtl8187Regs.REG_TX_CONF);
        transport.write32(Rtl8187Regs.REG_TX_CONF,
                transmitConfig | Rtl8187Regs.TX_CONF_LOOPBACK_MAC);
        setChannel(channel);
        sleep(10);
        transport.write32(Rtl8187Regs.REG_TX_CONF, transmitConfig);

        transport.write16(Rtl8187Regs.REG_ATIM_WND, 2);
        transport.write16(Rtl8187Regs.REG_ATIMTR_INTERVAL, 100);
        transport.write16(Rtl8187Regs.REG_BEACON_INTERVAL, 100);
        transport.write16(Rtl8187Regs.REG_BEACON_INTERVAL_TIME, 100);
    }

    private void writeRfBitBang(int address, int data) throws IOException {
        int bang = ((data & 0x0FFF) << 4) | (address & 0x0F);

        int outputRegister = transport.read16(Rtl8187Regs.REG_RFPINSOUTPUT) & 0xFFF3;
        int enableRegister = transport.read16(Rtl8187Regs.REG_RFPINSENABLE);
        int selectRegister = transport.read16(Rtl8187Regs.REG_RFPINSSELECT);

        transport.write16(Rtl8187Regs.REG_RFPINSENABLE, enableRegister | 0x0007);
        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, selectRegister | 0x0007);
        sleepMicros(10);

        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 2));
        sleepMicros(2);
        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister);
        sleepMicros(10);

        for (int bit = 15; bit >= 0; bit--) {
            int value = outputRegister | ((bang >>> bit) & 1);
            if ((bit & 1) != 0) transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, value);
            transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, value | (1 << 1));
            transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, value | (1 << 1));
            if ((bit & 1) == 0) transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, value);
        }

        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 2));
        sleepMicros(10);
        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 2));
        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, selectRegister);
    }

    /** The 8051 path: the same pin dance, then a vendor request carrying the value. */
    private void writeRf8051(int address, int data) throws IOException {
        int outputRegister = transport.read16(Rtl8187Regs.REG_RFPINSOUTPUT);
        int enableRegister = transport.read16(Rtl8187Regs.REG_RFPINSENABLE);
        int selectRegister = transport.read16(Rtl8187Regs.REG_RFPINSSELECT);

        outputRegister &= ~(0x3 << 2);
        selectRegister &= ~0xF;

        transport.write16(Rtl8187Regs.REG_RFPINSENABLE, enableRegister | 0x0007);
        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, selectRegister | 0x0007);
        sleepMicros(10);

        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 2));
        sleepMicros(2);
        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister);
        sleepMicros(10);

        transport.rawWrite(address, 0x8225, new byte[]{(byte) data, (byte) (data >>> 8)});

        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 2));
        sleepMicros(10);
        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, outputRegister | (1 << 2));
        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, selectRegister);
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepMicros(int micros) {
        long deadline = System.nanoTime() + micros * 1000L;
        // The SPI clock tolerates a coarse busy-wait; the pin order is what matters.
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
