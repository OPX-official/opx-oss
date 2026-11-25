package com.opx.oss.engine.chips.rtl8187;

/** The RTL8187 register map and the vendor request that reads and writes it. */
public final class Rtl8187Regs {

    /** Vendor request both directions use: wValue is the register, wIndex the page. */
    public static final int REQ_REGISTER = 0x05;
    public static final int REQT_READ = 0xC0;    // vendor IN
    public static final int REQT_WRITE = 0x40;   // vendor OUT

    /** The card's 32-bit register window; every CSR address below starts here. */
    public static final int CSR_BASE = 0xFF00;

    public static final int REG_MAC0 = CSR_BASE + 0x00;          // MAC[0..5], u8 each
    public static final int REG_MAR = CSR_BASE + 0x08;           // multicast filter, 2 x u32
    public static final int REG_BSSID = CSR_BASE + 0x2E;
    public static final int REG_CMD = CSR_BASE + 0x37;           // u8 command register
    public static final int REG_TX_CONF = CSR_BASE + 0x40;       // u32
    public static final int REG_RX_CONF = CSR_BASE + 0x44;       // u32
    public static final int REG_EEPROM_CMD = CSR_BASE + 0x50;    // u8, 93cx6 bit-bang
    public static final int REG_CONFIG0 = CSR_BASE + 0x51;
    public static final int REG_CONFIG1 = CSR_BASE + 0x52;
    public static final int REG_CONFIG2 = CSR_BASE + 0x53;
    public static final int REG_ANAPARAM = CSR_BASE + 0x54;
    public static final int REG_MSR = CSR_BASE + 0x58;
    public static final int REG_CONFIG3 = CSR_BASE + 0x59;
    public static final int REG_CONFIG4 = CSR_BASE + 0x5A;
    public static final int REG_TESTR = CSR_BASE + 0x5B;
    public static final int REG_PGSELECT = CSR_BASE + 0x5E;
    public static final int REG_ANAPARAM2 = CSR_BASE + 0x60;
    public static final int REG_INT_MASK = CSR_BASE + 0x3C;
    public static final int REG_INT_STATUS = CSR_BASE + 0x3E;
    public static final int REG_TX_FIFO_COUNT = CSR_BASE + 0x12;

    /** EEPROM command register bits: the four SPI lines plus the mode field. */
    public static final int EEPROM_CMD_READ = 1;
    public static final int EEPROM_CMD_WRITE = 1 << 1;
    public static final int EEPROM_CMD_CK = 1 << 2;
    public static final int EEPROM_CMD_CS = 1 << 3;
    public static final int EEPROM_CMD_PROGRAM = 2 << 6;

    /** 93cx6 widths: 6-bit addresses on a 93c46, 8-bit on a 93c66. */
    public static final int EEPROM_WIDTH_93C46 = 6;
    public static final int EEPROM_WIDTH_93C66 = 8;
    public static final int EEPROM_READ_OPCODE = 0x06;
    public static final int EEPROM_OPCODE_WIDTH = 3;

    /** TX descriptor bits (the 12-byte rtl8187_tx_hdr). */
    public static final int TX_FLAG_NO_ENC = 1 << 15;
    public static final int TX_FLAG_MOREFRAG = 1 << 17;
    public static final int RETRY_COUNT = 7;
    public static final int TX_HDR_SIZE = 12;

    /** RX trailer: 16 bytes on the 8187L, 20 on the 8187B. */
    public static final int RX_HDR_SIZE_8187L = 16;
    public static final int RX_HDR_SIZE_8187B = 20;
    public static final int RX_FLAG_CRC32_ERR = 1 << 13;

    public static final int EP_BULK_IN = 0x81;
    public static final int EP_BULK_OUT = 0x02;
    public static final int MAX_RX = 0x9C4;

    /** Rate indices: 0 is 1 Mbps CCK, the robust broadcast rate for injected frames. */
    public static final int RATE_1MBPS = 0;
    public static final int RATE_2MBPS = 1;
    public static final int RATE_6MBPS = 4;

    // Registers the bring-up sequence touches.
    public static final int REG_BRSR = CSR_BASE + 0x2C;
    public static final int REG_RESP_RATE = CSR_BASE + 0x34;
    public static final int REG_INT_TIMEOUT = CSR_BASE + 0x48;
    public static final int REG_PHY = CSR_BASE + 0x7C;
    public static final int REG_RFPINSOUTPUT = CSR_BASE + 0x80;
    public static final int REG_RFPINSENABLE = CSR_BASE + 0x82;
    public static final int REG_RFPINSSELECT = CSR_BASE + 0x84;
    public static final int REG_RFPINSINPUT = CSR_BASE + 0x86;
    public static final int REG_RF_PARA = CSR_BASE + 0x88;
    public static final int REG_RF_TIMING = CSR_BASE + 0x8C;
    public static final int REG_GP_ENABLE = CSR_BASE + 0x90;
    public static final int REG_GPIO0 = CSR_BASE + 0x91;
    public static final int REG_TX_AGC_CTL = CSR_BASE + 0x9C;
    public static final int REG_WPA_CONF = CSR_BASE + 0xB0;
    public static final int REG_CW_CONF = CSR_BASE + 0xBC;
    public static final int REG_RATE_FALLBACK = CSR_BASE + 0xBE;
    public static final int REG_TALLY_SEL = CSR_BASE + 0xFC;
    public static final int REG_ATIM_WND = CSR_BASE + 0x72;
    public static final int REG_BEACON_INTERVAL = CSR_BASE + 0x70;
    public static final int REG_BEACON_INTERVAL_TIME = CSR_BASE + 0x74;
    public static final int REG_ATIMTR_INTERVAL = CSR_BASE + 0x76;

    // Registers outside the CSR window, addressed directly.
    public static final int REG_MAGIC_FE18 = 0xFE18;
    public static final int REG_MAGIC_FE53 = 0xFE53;
    public static final int REG_MAGIC_FFF4 = 0xFFF4;
    public static final int REG_MAGIC_ASIC_REV = 0xFFFE;

    public static final int EEPROM_CMD_NORMAL = 0 << 6;
    public static final int EEPROM_CMD_LOAD = 1 << 6;
    public static final int EEPROM_CMD_CONFIG = 3 << 6;

    public static final int CMD_TX_ENABLE = 1 << 2;
    public static final int CMD_RX_ENABLE = 1 << 3;
    public static final int CMD_RESET = 1 << 4;

    public static final int CONFIG3_ANAPARAM_WRITE = 1 << 6;
    public static final int ANAPARAM_ON = 0xA0000A59;
    public static final int ANAPARAM2_ON = 0x860C7312;

    public static final int RX_CONF_NICMAC = 1 << 1;
    public static final int RX_CONF_BROADCAST = 1 << 3;
    public static final int RX_CONF_DATA = 1 << 18;
    public static final int RX_CONF_MGMT = 1 << 20;
    public static final int RX_CONF_BSSID = 1 << 23;
    public static final int RX_CONF_RX_AUTORESETPHY = 1 << 28;
    public static final int RX_CONF_ONLYERLPKT = 1 << 31;

    public static final int TX_CONF_LOOPBACK_MAC = 1 << 17;
    public static final int TX_CONF_NO_ICV = 1 << 19;
    public static final int TX_CONF_CW_MIN = 1 << 31;

    private Rtl8187Regs() {
    }
}
