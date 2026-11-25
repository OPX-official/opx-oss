package com.opx.oss.engine.chips.rtl8187;

import com.opx.oss.engine.UsbTransport;

import java.io.IOException;

/**
 * Register access: one vendor control transfer per read or write, wValue carrying the register
 * and wIndex the page. Everything in the CSR window uses page 0.
 */
public final class Rtl8187Transport {

    private static final int TIMEOUT_MS = 500;

    private final UsbTransport transport;

    public Rtl8187Transport(UsbTransport transport) {
        this.transport = transport;
    }

    public int read8(int register) throws IOException {
        byte[] data = read(register, 1, 0);
        return data.length == 1 ? data[0] & 0xFF : 0;
    }

    public int read16(int register) throws IOException {
        byte[] data = read(register, 2, 0);
        return data.length == 2 ? (data[0] & 0xFF) | ((data[1] & 0xFF) << 8) : 0;
    }

    public int read32(int register) throws IOException {
        byte[] data = read(register, 4, 0);
        if (data.length != 4) return 0;
        return (data[0] & 0xFF) | ((data[1] & 0xFF) << 8)
                | ((data[2] & 0xFF) << 16) | ((data[3] & 0xFF) << 24);
    }

    public byte[] read(int register, int length, int page) throws IOException {
        byte[] buffer = new byte[length];
        int read = transport.control(Rtl8187Regs.REQT_READ, Rtl8187Regs.REQ_REGISTER,
                register, page & 0x03, buffer, TIMEOUT_MS);
        if (read < 0) throw new IOException("register read failed at 0x" + Integer.toHexString(register));
        if (read == length) return buffer;
        byte[] shortRead = new byte[Math.max(read, 0)];
        System.arraycopy(buffer, 0, shortRead, 0, shortRead.length);
        return shortRead;
    }

    public void write8(int register, int value) throws IOException {
        write(register, new byte[]{(byte) value}, 0);
    }

    public void write16(int register, int value) throws IOException {
        write(register, new byte[]{(byte) value, (byte) (value >> 8)}, 0);
    }

    public void write32(int register, int value) throws IOException {
        write(register, new byte[]{
                (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)
        }, 0);
    }

    public void write(int register, byte[] data, int page) throws IOException {
        int sent = transport.control(Rtl8187Regs.REQT_WRITE, Rtl8187Regs.REQ_REGISTER,
                register, page & 0x03, data, TIMEOUT_MS);
        if (sent != data.length) {
            throw new IOException("register write short: " + sent + " of " + data.length
                    + " at 0x" + Integer.toHexString(register));
        }
    }

    public void setBits8(int register, int mask) throws IOException {
        write8(register, read8(register) | mask);
    }

    public void clearBits8(int register, int mask) throws IOException {
        write8(register, read8(register) & ~mask & 0xFF);
    }

    public void setBits32(int register, int mask) throws IOException {
        write32(register, read32(register) | mask);
    }

    public void clearBits32(int register, int mask) throws IOException {
        write32(register, read32(register) & ~mask);
    }
}
