package com.opx.oss.engine.chips.rtl8187;

import java.io.IOException;

/**
 * The RTL8225 transceiver in both revisions the RTL8187L shipped with: the original BCD part and
 * the later z2. They share the synthesizer word and the SPI bus, but every table and the whole
 * bring-up sequence differ, so the variant is probed from the chip itself and picked per card.
 */
public final class Rtl8225 {

    /** Which revision of the transceiver sits on the card. */
    public enum Variant {
        BCD, Z2
    }

    private static final int REG_TX_GAIN_CCK = 0xFF00 + 0x9D;
    private static final int REG_TX_GAIN_OFDM = 0xFF00 + 0x9E;
    private static final int REG_TX_ANTENNA = 0xFF00 + 0x9F;
    private static final int REG_HSSI_PARA = 0xFF00 + 0x94;
    private static final int REG_TESTR = 0xFF00 + 0x5B;

    private final Rtl8187Transport transport;
    private final Rtl8187Phy phy;

    public Rtl8225(Rtl8187Transport transport, Rtl8187Phy phy) {
        this.transport = transport;
        this.phy = phy;
    }

    /**
     * Probe which transceiver is fitted: the z2 answers on RF registers 8 and 9 with 0x588 and
     * 0x700, anything else is the BCD part. The probe leaves RF register 0 back at 0x0B7.
     */
    public Variant detect() throws IOException {
        rf(0x0, 0x1B7);
        int reg8 = phy.readRf(8);
        int reg9 = phy.readRf(9);
        rf(0x0, 0x0B7);
        return (reg8 == 0x588 && reg9 == 0x700) ? Variant.Z2 : Variant.BCD;
    }

    /** The transceiver's own bring-up, run in the middle of the MAC sequence. */
    public void init(Variant variant, int cckPower, int ofdmPower, int txpwrBase) throws IOException {
        if (variant == Variant.Z2) {
            initZ2(cckPower, ofdmPower, txpwrBase);
        } else {
            initBcd(cckPower, ofdmPower);
        }
    }

    /** TX power for one channel, cck in the low nibble and ofdm in the high, from the EEPROM. */
    public void setTxPower(Variant variant, int channel, int cckPower, int ofdmPower, int txpwrBase)
            throws IOException {
        if (variant == Variant.Z2) {
            setTxPowerZ2(channel, cckPower, ofdmPower, txpwrBase);
        } else {
            setTxPowerBcd(channel, cckPower, ofdmPower);
        }
    }

    private void initBcd(int cckPower, int ofdmPower) throws IOException {
        rf(0x0, 0x067);
        rf(0x1, 0xFE0);
        rf(0x2, 0x44D);
        rf(0x3, 0x441);
        rf(0x4, 0x486);
        rf(0x5, 0xBC0);
        rf(0x6, 0xAE6);
        rf(0x7, 0x82A);
        rf(0x8, 0x01F);
        rf(0x9, 0x334);
        rf(0xA, 0xFD4);
        rf(0xB, 0x391);
        rf(0xC, 0x050);
        rf(0xD, 0x6DB);
        rf(0xE, 0x029);
        rf(0xF, 0x914);
        sleep(100);

        rf(0x2, 0xC4D);
        sleep(200);
        rf(0x2, 0x44D);
        sleep(200);
        retryCalibration();

        rf(0x0, 0x127);
        for (int i = 0; i < Rtl8225Tables.RTL8225BCD_RXGAIN.length; i++) {
            rf(0x1, i + 1);
            rf(0x2, Rtl8225Tables.RTL8225BCD_RXGAIN[i]);
        }
        rf(0x0, 0x027);
        rf(0x0, 0x22F);

        writeAgc();
        sleep(1);

        writeOfdm(0x00, 0x01);
        writeOfdm(0x01, 0x02);
        writeOfdm(0x02, 0x42);
        writeOfdm(0x03, 0x00);
        writeOfdm(0x04, 0x00);
        writeOfdm(0x05, 0x00);
        writeOfdm(0x06, 0x40);
        writeOfdm(0x07, 0x00);
        writeOfdm(0x08, 0x40);
        writeOfdm(0x09, 0xfe);
        writeOfdm(0x0a, 0x09);
        writeOfdm(0x0b, 0x80);
        writeOfdm(0x0c, 0x01);
        writeOfdm(0x0e, 0xd3);
        writeOfdm(0x0f, 0x38);
        writeOfdm(0x10, 0x84);
        writeOfdm(0x11, 0x06);
        writeOfdm(0x12, 0x20);
        writeOfdm(0x13, 0x20);
        writeOfdm(0x14, 0x00);
        writeOfdm(0x15, 0x40);
        writeOfdm(0x16, 0x00);
        writeOfdm(0x17, 0x40);
        writeOfdm(0x18, 0xef);
        writeOfdm(0x19, 0x19);
        writeOfdm(0x1a, 0x20);
        writeOfdm(0x1b, 0x76);
        writeOfdm(0x1c, 0x04);
        writeOfdm(0x1e, 0x95);
        writeOfdm(0x1f, 0x75);
        writeOfdm(0x20, 0x1f);
        writeOfdm(0x21, 0x27);
        writeOfdm(0x22, 0x16);
        writeOfdm(0x24, 0x46);
        writeOfdm(0x25, 0x20);
        writeOfdm(0x26, 0x90);
        writeOfdm(0x27, 0x88);

        writeBcdSensitivity();

        writeCck(0x00, 0x98);
        writeCck(0x03, 0x20);
        writeCck(0x04, 0x7e);
        writeCck(0x05, 0x12);
        writeCck(0x06, 0xfc);
        writeCck(0x07, 0x78);
        writeCck(0x08, 0x2e);
        writeCck(0x10, 0x9b);
        writeCck(0x11, 0x88);
        writeCck(0x12, 0x47);
        writeCck(0x13, 0xd0);
        writeCck(0x19, 0x00);
        writeCck(0x1a, 0xa0);
        writeCck(0x1b, 0x08);
        writeCck(0x40, 0x86);
        writeCck(0x41, 0x8d);
        writeCck(0x42, 0x15);
        writeCck(0x43, 0x18);
        writeCck(0x44, 0x1f);
        writeCck(0x45, 0x1e);
        writeCck(0x46, 0x1a);
        writeCck(0x47, 0x15);
        writeCck(0x48, 0x10);
        writeCck(0x49, 0x0a);
        writeCck(0x4a, 0x05);
        writeCck(0x4b, 0x02);
        writeCck(0x4c, 0x05);

        transport.write8(REG_TESTR, 0x0D);
        setTxPowerBcd(1, cckPower, ofdmPower);

        writeCck(0x10, 0x9b);
        writeOfdm(0x26, 0x90);
        transport.write8(REG_TX_ANTENNA, 0x03);
        sleep(1);
        transport.write32(REG_HSSI_PARA, 0x3DC00002);

        rf(0x0c, 0x50);
        writeBcdSensitivity();
        writeCck(0x41, Rtl8225Tables.RTL8225_THRESHOLD[2]);
    }

    private void initZ2(int cckPower, int ofdmPower, int txpwrBase) throws IOException {
        rf(0x0, 0x2BF);
        rf(0x1, 0xEE0);
        rf(0x2, 0x44D);
        rf(0x3, 0x441);
        rf(0x4, 0x8C3);
        rf(0x5, 0xC72);
        rf(0x6, 0x0E6);
        rf(0x7, 0x82A);
        rf(0x8, 0x03F);
        rf(0x9, 0x335);
        rf(0xA, 0x9D4);
        rf(0xB, 0x7BB);
        rf(0xC, 0x850);
        rf(0xD, 0xCDF);
        rf(0xE, 0x02B);
        rf(0xF, 0x114);
        sleep(100);

        rf(0x0, 0x1B7);
        for (int i = 0; i < Rtl8225Tables.RTL8225Z2_RXGAIN.length; i++) {
            rf(0x1, i + 1);
            rf(0x2, Rtl8225Tables.RTL8225Z2_RXGAIN[i]);
        }
        rf(0x3, 0x080);
        rf(0x5, 0x004);
        rf(0x0, 0x0B7);
        rf(0x2, 0xC4D);
        sleep(200);
        rf(0x2, 0x44D);
        sleep(100);
        retryCalibration();
        sleep(200);

        rf(0x0, 0x2BF);
        writeAgc();
        sleep(1);

        writeOfdm(0x00, 0x01);
        writeOfdm(0x01, 0x02);
        writeOfdm(0x02, 0x42);
        writeOfdm(0x03, 0x00);
        writeOfdm(0x04, 0x00);
        writeOfdm(0x05, 0x00);
        writeOfdm(0x06, 0x40);
        writeOfdm(0x07, 0x00);
        writeOfdm(0x08, 0x40);
        writeOfdm(0x09, 0xfe);
        writeOfdm(0x0a, 0x08);
        writeOfdm(0x0b, 0x80);
        writeOfdm(0x0c, 0x01);
        writeOfdm(0x0d, 0x43);
        writeOfdm(0x0e, 0xd3);
        writeOfdm(0x0f, 0x38);
        writeOfdm(0x10, 0x84);
        writeOfdm(0x11, 0x07);
        writeOfdm(0x12, 0x20);
        writeOfdm(0x13, 0x20);
        writeOfdm(0x14, 0x00);
        writeOfdm(0x15, 0x40);
        writeOfdm(0x16, 0x00);
        writeOfdm(0x17, 0x40);
        writeOfdm(0x18, 0xef);
        writeOfdm(0x19, 0x19);
        writeOfdm(0x1a, 0x20);
        writeOfdm(0x1b, 0x15);
        writeOfdm(0x1c, 0x04);
        writeOfdm(0x1d, 0xc5);
        writeOfdm(0x1e, 0x95);
        writeOfdm(0x1f, 0x75);
        writeOfdm(0x20, 0x1f);
        writeOfdm(0x21, 0x17);
        writeOfdm(0x22, 0x16);
        writeOfdm(0x23, 0x80);
        writeOfdm(0x24, 0x46);
        writeOfdm(0x25, 0x00);
        writeOfdm(0x26, 0x90);
        writeOfdm(0x27, 0x88);

        writeOfdm(0x0b, Rtl8225Tables.RTL8225Z2_GAIN_BG[4 * 3 + 0]);
        writeOfdm(0x1b, Rtl8225Tables.RTL8225Z2_GAIN_BG[4 * 3 + 1]);
        writeOfdm(0x1d, Rtl8225Tables.RTL8225Z2_GAIN_BG[4 * 3 + 2]);
        writeOfdm(0x21, 0x37);

        writeCck(0x00, 0x98);
        writeCck(0x03, 0x20);
        writeCck(0x04, 0x7e);
        writeCck(0x05, 0x12);
        writeCck(0x06, 0xfc);
        writeCck(0x07, 0x78);
        writeCck(0x08, 0x2e);
        writeCck(0x10, 0x9b);
        writeCck(0x11, 0x88);
        writeCck(0x12, 0x47);
        writeCck(0x13, 0xd0);
        writeCck(0x19, 0x00);
        writeCck(0x1a, 0xa0);
        writeCck(0x1b, 0x08);
        writeCck(0x40, 0x86);
        writeCck(0x41, 0x8d);
        writeCck(0x42, 0x15);
        writeCck(0x43, 0x18);
        writeCck(0x44, 0x36);
        writeCck(0x45, 0x35);
        writeCck(0x46, 0x2e);
        writeCck(0x47, 0x25);
        writeCck(0x48, 0x1c);
        writeCck(0x49, 0x12);
        writeCck(0x4a, 0x09);
        writeCck(0x4b, 0x04);
        writeCck(0x4c, 0x05);

        transport.write8(REG_TESTR, 0x0D);
        sleep(1);
        setTxPowerZ2(1, cckPower, ofdmPower, txpwrBase);

        writeCck(0x10, 0x9b);
        writeOfdm(0x26, 0x90);
        transport.write8(REG_TX_ANTENNA, 0x03);
        sleep(1);
        transport.write32(REG_HSSI_PARA, 0x3DC00002);
    }

    /** The shared AGC table: the z2 revision uses the BCD one, not the z2 table of the same name. */
    private void writeAgc() throws IOException {
        for (int i = 0; i < Rtl8225Tables.RTL8225_AGC.length; i++) {
            writeOfdm(0x0B, Rtl8225Tables.RTL8225_AGC[i]);
            writeOfdm(0x0A, 0x80 + i);
        }
    }

    private void writeBcdSensitivity() throws IOException {
        writeOfdm(0x0d, Rtl8225Tables.RTL8225_GAIN[2 * 4 + 0]);
        writeOfdm(0x1b, Rtl8225Tables.RTL8225_GAIN[2 * 4 + 2]);
        writeOfdm(0x1d, Rtl8225Tables.RTL8225_GAIN[2 * 4 + 3]);
        writeOfdm(0x23, Rtl8225Tables.RTL8225_GAIN[2 * 4 + 1]);
    }

    /** Both revisions retry the RF calibration once when register 6's done bit is still clear. */
    private void retryCalibration() throws IOException {
        if ((phy.readRf(6) & (1 << 7)) != 0) return;
        rf(0x02, 0x0C4D);
        sleep(200);
        rf(0x02, 0x044D);
        sleep(100);
        phy.readRf(6);
    }

    private void setTxPowerBcd(int channel, int cckPower, int ofdmPower) throws IOException {
        cckPower = Math.min(cckPower, 11);
        ofdmPower = ofdmPower > 15 ? 25 : ofdmPower + 10;

        transport.write8(REG_TX_GAIN_CCK,
                Rtl8225Tables.RTL8225_TX_GAIN_CCK_OFDM[cckPower / 6] >> 1);

        int[] table = channel == 14
                ? Rtl8225Tables.RTL8225_TX_POWER_CCK_CH14 : Rtl8225Tables.RTL8225_TX_POWER_CCK;
        int base = (cckPower % 6) * 8;
        for (int i = 0; i < 8; i++) writeCck(0x44 + i, table[base + i]);
        sleep(1);

        anaparam2On();

        writeOfdm(2, 0x42);
        writeOfdm(6, 0x00);
        writeOfdm(8, 0x00);

        transport.write8(REG_TX_GAIN_OFDM,
                Rtl8225Tables.RTL8225_TX_GAIN_CCK_OFDM[ofdmPower / 6] >> 1);
        int value = Rtl8225Tables.RTL8225_TX_POWER_OFDM[ofdmPower % 6];
        writeOfdm(5, value);
        writeOfdm(7, value);
        sleep(1);
    }

    private void setTxPowerZ2(int channel, int cckPower, int ofdmPower, int txpwrBase)
            throws IOException {
        cckPower = Math.min(cckPower, 15) + (txpwrBase & 0xF);
        cckPower = Math.min(cckPower, 35);

        ofdmPower = ofdmPower > 15 ? 25 : ofdmPower + 10;
        ofdmPower = (ofdmPower + (txpwrBase >> 4)) & 0xFF;
        ofdmPower = Math.min(ofdmPower, 35);

        int[] table = channel == 14
                ? Rtl8225Tables.RTL8225Z2_TX_POWER_CCK_CH14
                : Rtl8225Tables.RTL8225Z2_TX_POWER_CCK;
        for (int i = 0; i < 8; i++) writeCck(0x44 + i, table[i]);

        transport.write8(REG_TX_GAIN_CCK, Rtl8225Tables.RTL8225Z2_TX_GAIN_CCK_OFDM[cckPower]);
        sleep(1);

        anaparam2On();

        writeOfdm(2, 0x42);
        writeOfdm(5, 0x00);
        writeOfdm(6, 0x40);
        writeOfdm(7, 0x00);
        writeOfdm(8, 0x40);

        transport.write8(REG_TX_GAIN_OFDM, Rtl8225Tables.RTL8225Z2_TX_GAIN_CCK_OFDM[ofdmPower]);
        sleep(1);
    }

    /** The analogue block is only writable inside this bracket. */
    private void anaparam2On() throws IOException {
        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_CONFIG);
        int config3 = transport.read8(Rtl8187Regs.REG_CONFIG3);
        transport.write8(Rtl8187Regs.REG_CONFIG3,
                config3 | Rtl8187Regs.CONFIG3_ANAPARAM_WRITE);
        transport.write32(Rtl8187Regs.REG_ANAPARAM2, Rtl8187Regs.ANAPARAM2_ON);
        transport.write8(Rtl8187Regs.REG_CONFIG3,
                config3 & ~Rtl8187Regs.CONFIG3_ANAPARAM_WRITE & 0xFF);
        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_NORMAL);
    }

    private void rf(int address, int data) throws IOException {
        phy.writeRf(address, data);
    }

    private void writeOfdm(int address, int data) throws IOException {
        phy.writePhy(address, data);
    }

    private void writeCck(int address, int data) throws IOException {
        phy.writePhyCck(address, data);
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
