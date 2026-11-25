package com.opx.oss.engine.chips.rtl8187;

import android.content.Context;

import com.opx.oss.engine.UsbTransport;
import com.opx.oss.engine.chips.WifiChip;
import com.opx.oss.engine.dot11.Mac;

import java.io.IOException;

/** The RTL8187L behind the engine's chip contract: registers, radio, transmitter and reader. */
public final class Rtl8187Chip implements WifiChip {

    private static final int[] CHANNELS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
    private static final int RX_TIMEOUT_MS = 100;

    private final Context context;
    private final String path;

    private UsbTransport usb;
    private Rtl8187Transport registers;
    private Rtl8187Phy phy;
    private Rtl8225z2 radio;
    private Rtl8187Tx transmitter;
    private Thread reader;
    private volatile boolean running;
    private volatile String macAddress;
    private volatile int channel = 1;

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

    @Override
    public void open() throws IOException {
        usb = UsbTransport.open(context, path);
        registers = new Rtl8187Transport(usb);

        // The station address sits in the card's own EEPROM, read before the MAC reloads.
        Eeprom93cx6 eeprom = new Eeprom93cx6(registers, Eeprom93cx6.width(registers));
        macAddress = Mac.text(eeprom.mac());

        Rtl8187Mac mac = new Rtl8187Mac(registers);
        int asicRevision = mac.asicRevision();
        phy = new Rtl8187Phy(registers, asicRevision);
        radio = new Rtl8225z2(registers, phy);
        mac.initHw(radio::init);
        mac.start();

        transmitter = new Rtl8187Tx(usb);
        setChannel(1);
    }

    @Override
    public boolean isUp() {
        return usb != null && !running;
    }

    @Override
    public void setChannel(int channel) throws IOException {
        if (phy == null) throw new IOException("the card is not open");
        radio.setTxPower(channel, 0, 0, 0);
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
        reader = new Thread(() -> {
            byte[] buffer = new byte[Rtl8187Regs.MAX_RX];
            while (running) {
                int received;
                try {
                    received = usb.bulkIn(buffer, RX_TIMEOUT_MS);
                } catch (RuntimeException e) {
                    if (running) running = false;
                    break;
                }
                if (received <= 0) continue;
                Rtl8187Rx.Received frame = Rtl8187Rx.parse(buffer, received);
                if (frame == null || frame.checksumError) continue;
                try {
                    sink.onFrame(frame.mpdu, frame.rssi);
                } catch (RuntimeException ignored) {
                    // A sink that throws must not stop the reader.
                }
            }
        }, "opx-rtl8187-rx");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public void close() {
        running = false;
        Thread stopping = reader;
        reader = null;
        if (stopping != null) {
            try {
                stopping.join(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (phy != null) {
            try {
                phy.stop();
            } catch (IOException ignored) {
                // The card may already be unplugged.
            }
        }
        if (usb != null) {
            usb.close();
            usb = null;
        }
    }
}
