package com.opx.oss.engine.chips.rtl8187;

import java.io.IOException;

/**
 * The MAC half of the card: the analogue block, the soft reset, the register bring-up the
 * vendor documents, and the latch that finally lets frames flow.
 */
public final class Rtl8187Mac {

    /** The radio's own initialisation, run in the middle of the MAC sequence. */
    public interface RadioInit {
        void run() throws IOException;
    }

    /** The radio's own shutdown, run in the middle of the MAC stop sequence. */
    public interface RadioStop {
        void run() throws IOException;
    }

    private final Rtl8187Transport transport;

    public Rtl8187Mac(Rtl8187Transport transport) {
        this.transport = transport;
    }

    /** Power the analogue block on, inside the window the card expects for it. */
    public void setAnaparam() throws IOException {
        setAnaparam(true);
    }

    /** Power the analogue block up or down; shutdown writes the off value in the same window. */
    public void setAnaparam(boolean on) throws IOException {
        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_CONFIG);
        int config3 = transport.read8(Rtl8187Regs.REG_CONFIG3) | Rtl8187Regs.CONFIG3_ANAPARAM_WRITE;
        transport.write8(Rtl8187Regs.REG_CONFIG3, config3);
        transport.write32(Rtl8187Regs.REG_ANAPARAM,
                on ? Rtl8187Regs.ANAPARAM_ON : Rtl8187Regs.ANAPARAM_OFF);
        if (on) transport.write32(Rtl8187Regs.REG_ANAPARAM2, Rtl8187Regs.ANAPARAM2_ON);
        transport.write8(Rtl8187Regs.REG_CONFIG3,
                config3 & ~Rtl8187Regs.CONFIG3_ANAPARAM_WRITE);
        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_NORMAL);
    }

    /** Soft-reset the MAC and wait for the register file to reload from the EEPROM. */
    public void cmdReset() throws IOException {
        int command = transport.read8(Rtl8187Regs.REG_CMD) & (1 << 1);
        transport.write8(Rtl8187Regs.REG_CMD, command | Rtl8187Regs.CMD_RESET);

        boolean cleared = false;
        for (int attempt = 0; attempt < 10; attempt++) {
            sleep(2);
            if ((transport.read8(Rtl8187Regs.REG_CMD) & Rtl8187Regs.CMD_RESET) == 0) {
                cleared = true;
                break;
            }
        }
        if (!cleared) throw new IOException("the MAC never came out of reset");

        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_LOAD);
        boolean loaded = false;
        for (int attempt = 0; attempt < 10; attempt++) {
            sleep(4);
            if ((transport.read8(Rtl8187Regs.REG_EEPROM_CMD) & Rtl8187Regs.EEPROM_CMD_CONFIG) == 0) {
                loaded = true;
                break;
            }
        }
        if (!loaded) throw new IOException("the card never finished loading its EEPROM");
    }

    /** The full bring-up: analogue, reset, pin defaults, timing, radio, then the page-1 tail. */
    public void initHw(RadioInit radio) throws IOException {
        setAnaparam();
        transport.write16(Rtl8187Regs.REG_INT_MASK, 0);
        sleep(200);

        transport.write8(Rtl8187Regs.REG_MAGIC_FE18, 0x10);
        transport.write8(Rtl8187Regs.REG_MAGIC_FE18, 0x11);
        transport.write8(Rtl8187Regs.REG_MAGIC_FE18, 0x00);
        sleep(200);

        cmdReset();
        setAnaparam();

        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, 0);
        transport.write8(Rtl8187Regs.REG_GPIO0, 0);
        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, 4 << 8);
        transport.write8(Rtl8187Regs.REG_GPIO0, 1);
        transport.write8(Rtl8187Regs.REG_GP_ENABLE, 0);

        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_CONFIG);
        transport.write16(Rtl8187Regs.REG_MAGIC_FFF4, 0xFFFF);
        int config1 = (transport.read8(Rtl8187Regs.REG_CONFIG1) & 0x3F) | 0x80;
        transport.write8(Rtl8187Regs.REG_CONFIG1, config1);
        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_NORMAL);

        transport.write32(Rtl8187Regs.REG_INT_TIMEOUT, 0);
        transport.write8(Rtl8187Regs.REG_WPA_CONF, 0);
        transport.write8(Rtl8187Regs.REG_RATE_FALLBACK, 0);
        transport.write8(Rtl8187Regs.REG_RESP_RATE, (8 << 4) | 0);
        transport.write16(Rtl8187Regs.REG_BRSR, 0x01F3);

        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, 0);
        transport.write8(Rtl8187Regs.REG_GPIO0, 0);
        int fe53 = transport.read8(Rtl8187Regs.REG_MAGIC_FE53) | (1 << 7);
        transport.write8(Rtl8187Regs.REG_MAGIC_FE53, fe53);
        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, 4 << 8);
        transport.write8(Rtl8187Regs.REG_GPIO0, 0x20);
        transport.write8(Rtl8187Regs.REG_GP_ENABLE, 0);
        transport.write16(Rtl8187Regs.REG_RFPINSOUTPUT, 0x80);
        transport.write16(Rtl8187Regs.REG_RFPINSSELECT, 0x80);
        transport.write16(Rtl8187Regs.REG_RFPINSENABLE, 0x80);
        sleep(100);

        transport.write32(Rtl8187Regs.REG_RF_TIMING, 0x000A8008);
        transport.write16(Rtl8187Regs.REG_BRSR, 0xFFFF);
        transport.write32(Rtl8187Regs.REG_RF_PARA, 0x00100044);
        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_CONFIG);
        transport.write8(Rtl8187Regs.REG_CONFIG3, 0x44);
        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_NORMAL);
        transport.write16(Rtl8187Regs.REG_RFPINSENABLE, 0x1FF7);
        sleep(100);

        radio.run();

        transport.write16(Rtl8187Regs.REG_BRSR, 0x01F3);
        int pageSelect = transport.read8(Rtl8187Regs.REG_PGSELECT) & ~1;
        transport.write8(Rtl8187Regs.REG_PGSELECT, pageSelect | 1);
        transport.write16(Rtl8187Regs.REG_MAGIC_ASIC_REV, 0x10);
        transport.write8(Rtl8187Regs.REG_TALLY_SEL, 0x80);
        transport.write8(0xFFFF, 0x60);
        transport.write8(Rtl8187Regs.REG_PGSELECT, pageSelect);
    }

    /** Read the silicon revision the RF path selector needs: the low bits behind page 1. */
    public int asicRevision() throws IOException {
        int pageSelect = transport.read8(Rtl8187Regs.REG_PGSELECT);
        transport.write8(Rtl8187Regs.REG_PGSELECT, pageSelect | 1);
        int revision = transport.read16(Rtl8187Regs.REG_MAGIC_ASIC_REV);
        transport.write8(Rtl8187Regs.REG_PGSELECT, pageSelect);
        return revision & 0x03;
    }

    /** Open the receive filters and latch transmit and receive: frames start arriving. */
    public int start() throws IOException {
        transport.write16(Rtl8187Regs.REG_INT_MASK, 0xFFFF);
        transport.write32(Rtl8187Regs.REG_MAR, 0xFFFFFFFF);
        transport.write32(Rtl8187Regs.REG_MAR + 4, 0xFFFFFFFF);

        int rxConfig = Rtl8187Regs.RX_CONF_ONLYERLPKT
                | Rtl8187Regs.RX_CONF_RX_AUTORESETPHY
                | Rtl8187Regs.RX_CONF_BSSID
                | Rtl8187Regs.RX_CONF_MGMT
                | Rtl8187Regs.RX_CONF_DATA
                | (7 << 13)
                | (7 << 10)
                | Rtl8187Regs.RX_CONF_BROADCAST
                | Rtl8187Regs.RX_CONF_NICMAC;
        transport.write32(Rtl8187Regs.REG_RX_CONF, rxConfig);

        int cwConfig = transport.read8(Rtl8187Regs.REG_CW_CONF);
        cwConfig &= ~1;
        cwConfig |= 1 << 1;
        transport.write8(Rtl8187Regs.REG_CW_CONF, cwConfig);

        int agc = transport.read8(Rtl8187Regs.REG_TX_AGC_CTL) & ~0x07;
        transport.write8(Rtl8187Regs.REG_TX_AGC_CTL, agc);

        transport.write32(Rtl8187Regs.REG_TX_CONF,
                Rtl8187Regs.TX_CONF_CW_MIN | (7 << 21) | Rtl8187Regs.TX_CONF_NO_ICV);

        int command = transport.read8(Rtl8187Regs.REG_CMD);
        transport.write8(Rtl8187Regs.REG_CMD,
                command | Rtl8187Regs.CMD_TX_ENABLE | Rtl8187Regs.CMD_RX_ENABLE);
        return rxConfig;
    }

    /**
     * The monitor/filter write mac80211 issues after start(): FCS failures and control frames are
     * switched on and the monitor bit is set, which is what makes the card hand up every frame it
     * hears instead of only its own BSS. Returns the new RX_CONF.
     */
    public int configureFilter(int rxConfig) throws IOException {
        rxConfig ^= Rtl8187Regs.RX_CONF_FCS;
        rxConfig ^= Rtl8187Regs.RX_CONF_CTRL;
        rxConfig |= Rtl8187Regs.RX_CONF_MONITOR;
        transport.write32(Rtl8187Regs.REG_RX_CONF, rxConfig);
        return rxConfig;
    }

    /** Shutdown: stop interrupts, stop the queues, stop the radio, power the analogue down. */
    public void stop(RadioStop rfStop) throws IOException {
        transport.write16(Rtl8187Regs.REG_INT_MASK, 0);

        int command = transport.read8(Rtl8187Regs.REG_CMD);
        command &= ~Rtl8187Regs.CMD_TX_ENABLE;
        command &= ~Rtl8187Regs.CMD_RX_ENABLE;
        transport.write8(Rtl8187Regs.REG_CMD, command & 0xFF);

        rfStop.run();
        setAnaparam(false);

        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_CONFIG);
        int config4 = transport.read8(Rtl8187Regs.REG_CONFIG4);
        transport.write8(Rtl8187Regs.REG_CONFIG4, config4 | Rtl8187Regs.CONFIG4_VCOOFF);
        transport.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_NORMAL);
    }

    /** The hardware radio switch: GPIO0's bit is cleared, then read back on GPIO1. */
    public boolean isRadioEnabled(int mask) throws IOException {
        int gpio = transport.read8(Rtl8187Regs.REG_GPIO0);
        transport.write8(Rtl8187Regs.REG_GPIO0, gpio & ~mask & 0xFF);
        gpio = transport.read8(Rtl8187Regs.REG_GPIO1);
        return (gpio & mask) != 0;
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
