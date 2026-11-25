package com.opx.oss.engine.chips.rtl8187;

import android.content.Context;

import com.opx.oss.engine.UsbTransport;
import com.opx.oss.engine.chips.Driver;
import com.opx.oss.engine.chips.RxReader;
import com.opx.oss.engine.dot11.Mac;

import java.io.IOException;
import java.util.Random;

/** The RTL8187L behind the engine's chip contract: registers, radio, transmitter and reader. */
public final class Rtl8187Chip implements Driver {

    private static final int[] CHANNELS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
    private static final int RX_TIMEOUT_MS = 100;

    private final Context context;
    private final String path;

    private UsbTransport usb;
    private Rtl8187Transport registers;
    private Rtl8187Mac macBlock;
    private Rtl8187Phy phy;
    private Rtl8225 radio;
    private Rtl8225.Variant variant;
    private Rtl8187Tx transmitter;
    private int[] txPower = new int[14];
    private int txpwrBase;
    private RxReader reader;
    private volatile boolean running;
    private volatile String macAddress;
    private volatile int channel = 1;
    private final java.util.concurrent.atomic.AtomicLong acks =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile DisconnectListener disconnectListener;

    public Rtl8187Chip(Context context, String path) {
        this.context = context.getApplicationContext();
        this.path = path;
    }

    @Override
    public String name() {
        return "RTL8187";
    }

    @Override
    public String mac() {
        return macAddress;
    }

    @Override
    public int[] supportedChannels() {
        return CHANNELS.clone();
    }

    /**
     * The probe and cold bring-up in the order the card expects: EEPROM (MAC, per-channel TX
     * power, base), asic revision, rfkill, RF variant, MAC init with the radio's own init in the
     * middle, then start and the monitor filter write.
     */
    @Override
    public void open() throws IOException {
        usb = UsbTransport.open(context, path);
        registers = new Rtl8187Transport(usb);

        registers.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_CONFIG);
        Eeprom93cx6 eeprom = new Eeprom93cx6(registers, Eeprom93cx6.width(registers));

        byte[] address = eeprom.mac();
        if ((address[0] & 0x01) != 0 || isAllZero(address)) {
            // An empty or multicast address means the EEPROM lost its MAC: use a local one.
            new Random().nextBytes(address);
            address[0] &= (byte) 0xFE;
            address[0] |= (byte) 0x02;
        }
        macAddress = Mac.text(address);

        txPower = eeprom.txPowerValues();
        txpwrBase = eeprom.txpwrBase();
        int selectGpio = eeprom.selectGpio();

        macBlock = new Rtl8187Mac(registers);
        int asicRevision = macBlock.asicRevision();
        registers.write8(Rtl8187Regs.REG_EEPROM_CMD, Rtl8187Regs.EEPROM_CMD_NORMAL);

        int rfkillMask = Rtl8187Regs.RFKILL_MASK_8187_89_97;
        int productId = usb.productId();
        if ((productId == 0x8197 || productId == 0x8198) && (selectGpio & 0xFF00) != 0) {
            rfkillMask = Rtl8187Regs.RFKILL_MASK_8198;
        }
        if (!macBlock.isRadioEnabled(rfkillMask)) {
            throw new IOException("the card's hardware radio switch is off");
        }

        phy = new Rtl8187Phy(registers, asicRevision);
        radio = new Rtl8225(registers, phy);
        variant = radio.detect();

        radioChannelPower(1);
        macBlock.initHw(() -> radio.init(variant,
                txPower[0] & 0xF, (txPower[0] >> 4) & 0xFF, txpwrBase));
        int rxConfig = macBlock.start();
        macBlock.configureFilter(rxConfig);

        transmitter = new Rtl8187Tx(usb);
        setChannel(1);
    }

    @Override
    public boolean isUp() {
        return usb != null && phy != null;
    }

    /** The EEPROM's per-channel hw_value: cck in the low nibble, ofdm in the high. */
    private void radioChannelPower(int channel) throws IOException {
        int hwValue = txPower[channel - 1];
        radio.setTxPower(variant, channel, hwValue & 0xF, (hwValue >> 4) & 0xFF, txpwrBase);
    }

    @Override
    public void setChannel(int channel) throws IOException {
        if (phy == null) throw new IOException("the card is not open");
        radioChannelPower(channel);
        phy.configChannel(channel);
        this.channel = channel;
    }

    public int channel() {
        return channel;
    }

    @Override
    public void transmit(byte[] mpdu) throws IOException {
        if (transmitter == null) throw new IOException("the card is not open");
        transmitter.stampSequence(mpdu);
        transmitter.send(mpdu, Rtl8187Regs.RATE_1MBPS, Rtl8187Regs.RETRY_COUNT);
    }

    @Override
    public void startReceive(FrameSink sink) throws IOException {
        if (usb == null) throw new IOException("the card is not open");
        if (running) return;
        running = true;
        reader = new RxReader(
                () -> {
                    byte[] buffer = new byte[Rtl8187Regs.MAX_RX];
                    int received = usb.bulkIn(buffer, RX_TIMEOUT_MS);
                    if (received <= 0) return new byte[0];
                    byte[] out = new byte[received];
                    System.arraycopy(buffer, 0, out, 0, received);
                    return out;
                },
                (buffer, length) -> {
                    Rtl8187Rx.Received frame = Rtl8187Rx.parse(buffer, length);
                    if (frame == null || frame.checksumError) return;
                    // The hardware MAC transmits the ACK for us; the reply only matters as a tally.
                    if (frame.mpdu.length >= 10 && (frame.mpdu[0] & 0xFC) == 0xD4) {
                        acks.incrementAndGet();
                        return;
                    }
                    try {
                        sink.onFrame(frame.mpdu, frame.rssi);
                    } catch (RuntimeException ignored) {
                        // A sink that throws must not stop the reader.
                    }
                },
                error -> {
                    running = false;
                    DisconnectListener listener = disconnectListener;
                    if (listener != null) listener.onDisconnect();
                },
                "opx-rtl8187-rx");
        reader.start();
    }

    /** The silicon MAC answers ACKs for the card's own address; a forged MAC is not ACKed. */
    @Override
    public FakeMacSupport fakeMacSupport() {
        return FakeMacSupport.FIXED_MAC;
    }

    @Override
    public void registerDisconnect(DisconnectListener listener) {
        disconnectListener = listener;
    }

    @Override
    public long acksSeen() {
        return acks.get();
    }

    @Override
    public void resetAcks() {
        acks.set(0);
    }

    @Override
    public void close() {
        running = false;
        RxReader stopping = reader;
        reader = null;
        if (stopping != null) stopping.stop(300);
        if (macBlock != null && phy != null) {
            try {
                macBlock.stop(phy::stop);
            } catch (IOException ignored) {
                // The card may already be unplugged.
            }
        }
        if (usb != null) {
            usb.close();
            usb = null;
        }
        phy = null;
    }

    private static boolean isAllZero(byte[] address) {
        for (byte value : address) if (value != 0) return false;
        return true;
    }
}
