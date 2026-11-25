package com.opx.oss.engine.chips.rtl8187;

import java.io.IOException;

/**
 * The card's 93cx6 serial EEPROM, clocked out over four bits of the EEPROM command register:
 * chip select, clock, data in and data out. The permanent MAC lives in its first words.
 */
public final class Eeprom93cx6 {

    private final Rtl8187Transport transport;
    private final int width;

    private boolean dataIn;
    private boolean dataOut;
    private boolean clock;
    private boolean chipSelect;

    public Eeprom93cx6(Rtl8187Transport transport, int width) {
        this.transport = transport;
        this.width = width;
    }

    /** Address width: 8 bits on a 93c66 (RX_CONF bit 6 set), else 6 on a 93c46. */
    public static int width(Rtl8187Transport transport) throws IOException {
        int rxConf = transport.read32(Rtl8187Regs.REG_RX_CONF);
        return (rxConf & (1 << 6)) != 0
                ? Rtl8187Regs.EEPROM_WIDTH_93C66 : Rtl8187Regs.EEPROM_WIDTH_93C46;
    }

    public int read(int word) throws IOException {
        start();
        int command = (Rtl8187Regs.EEPROM_READ_OPCODE << width) | (word & ((1 << width) - 1));
        writeBits(command, Rtl8187Regs.EEPROM_OPCODE_WIDTH + width);
        int value = readBits(16);
        cleanup();
        return value;
    }

    public int[] read(int word, int words) throws IOException {
        int[] out = new int[words];
        for (int i = 0; i < words; i++) out[i] = read(word + i);
        return out;
    }

    /** Sixteen-bit words 0x07..0x09 hold the station address, low byte first. */
    public byte[] mac() throws IOException {
        int[] words = read(Rtl8187Regs.EEPROM_MAC_ADDR, 3);
        return new byte[]{
                (byte) (words[0] & 0xFF), (byte) ((words[0] >> 8) & 0xFF),
                (byte) (words[1] & 0xFF), (byte) ((words[1] >> 8) & 0xFF),
                (byte) (words[2] & 0xFF), (byte) ((words[2] >> 8) & 0xFF)
        };
    }

    /**
     * The per-channel TX power bytes: channels 1-6 from words 0x16-0x18, 7-10 from 0x3D-0x3E,
     * 11-14 from 0x1B-0x1C. Each byte is cck in the low nibble and ofdm in the high.
     */
    public int[] txPowerValues() throws IOException {
        int[] values = new int[14];
        int index = 0;
        for (int i = 0; i < 3; i++) {
            int word = read(Rtl8187Regs.EEPROM_TXPWR_CHAN_1 + i);
            values[index++] = word & 0xFF;
            values[index++] = (word >> 8) & 0xFF;
        }
        for (int i = 0; i < 2; i++) {
            int word = read(Rtl8187Regs.EEPROM_TXPWR_CHAN_4 + i);
            values[index++] = word & 0xFF;
            values[index++] = (word >> 8) & 0xFF;
        }
        for (int i = 0; i < 2; i++) {
            int word = read(Rtl8187Regs.EEPROM_TXPWR_CHAN_6 + i);
            values[index++] = word & 0xFF;
            values[index++] = (word >> 8) & 0xFF;
        }
        return values;
    }

    /** The two-nibble TX power correction applied on top of every channel's value (z2 only). */
    public int txpwrBase() throws IOException {
        return read(Rtl8187Regs.EEPROM_TXPWR_BASE);
    }

    /** Board strap word 0x3B; its high byte selects the 0x8198 rfkill mask. */
    public int selectGpio() throws IOException {
        return read(Rtl8187Regs.EEPROM_SELECT_GPIO);
    }

    private void start() throws IOException {
        sample();
        dataIn = false;
        dataOut = false;
        clock = false;
        chipSelect = true;
        apply();
        pulseHigh();
        pulseLow();
    }

    private void cleanup() throws IOException {
        sample();
        dataIn = false;
        chipSelect = false;
        apply();
        pulseHigh();
        pulseLow();
    }

    private void writeBits(int data, int count) throws IOException {
        sample();
        dataIn = false;
        dataOut = false;
        for (int bit = count - 1; bit >= 0; bit--) {
            dataIn = (data & (1 << bit)) != 0;
            apply();
            pulseHigh();
            pulseLow();
        }
        dataIn = false;
        apply();
    }

    private int readBits(int count) throws IOException {
        sample();
        dataIn = false;
        dataOut = false;
        int buffer = 0;
        for (int bit = count - 1; bit >= 0; bit--) {
            pulseHigh();
            sample();
            dataIn = false;
            if (dataOut) buffer |= 1 << bit;
            pulseLow();
        }
        return buffer;
    }

    private void pulseHigh() throws IOException {
        clock = true;
        apply();
    }

    private void pulseLow() throws IOException {
        clock = false;
        apply();
    }

    /** The chip drives data out on the read bit of the same register. */
    private void sample() throws IOException {
        int register = transport.read8(Rtl8187Regs.REG_EEPROM_CMD);
        dataIn = (register & Rtl8187Regs.EEPROM_CMD_WRITE) != 0;
        dataOut = (register & Rtl8187Regs.EEPROM_CMD_READ) != 0;
        clock = (register & Rtl8187Regs.EEPROM_CMD_CK) != 0;
        chipSelect = (register & Rtl8187Regs.EEPROM_CMD_CS) != 0;
    }

    private void apply() throws IOException {
        int register = Rtl8187Regs.EEPROM_CMD_PROGRAM;
        if (dataIn) register |= Rtl8187Regs.EEPROM_CMD_WRITE;
        if (dataOut) register |= Rtl8187Regs.EEPROM_CMD_READ;
        if (clock) register |= Rtl8187Regs.EEPROM_CMD_CK;
        if (chipSelect) register |= Rtl8187Regs.EEPROM_CMD_CS;
        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, register);
    }
}
