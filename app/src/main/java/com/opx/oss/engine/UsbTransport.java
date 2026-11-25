package com.opx.oss.engine;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Our own USB transport: the adapter's control and bulk endpoints driven through Android's host
 * API, so nothing below the bridge has to be a compiled third-party library.
 * <p>
 * The bridge stays the single owner of the {@code opx.usb} socket and of the descriptor it passes
 * over it; this class only opens the same device in-process to move frames, after the app-wide
 * permission the bridge asks for has been granted.
 */
public final class UsbTransport implements Closeable {

    private static final String ACTION_PERMISSION = "com.opx.oss.action.USB_PERMISSION";
    private static final long PERMISSION_WAIT_MS = 120_000L;

    private final UsbDeviceConnection connection;
    private final UsbInterface claimed;
    private final UsbEndpoint in;
    private final UsbEndpoint out;

    private UsbTransport(UsbDeviceConnection connection, UsbInterface claimed,
                         UsbEndpoint in, UsbEndpoint out) {
        this.connection = connection;
        this.claimed = claimed;
        this.in = in;
        this.out = out;
    }

    /** Open {@code name} (the bridge's device path) for frame I/O. */
    public static UsbTransport open(Context context, String name) throws IOException {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) throw new IOException("this build has no USB host service");
        UsbDevice device = find(manager, name);
        if (device == null) throw new IOException("no USB device at " + name);
        if (!manager.hasPermission(device) && !requestPermission(context, manager, device)) {
            throw new IOException("USB permission was not granted for " + name);
        }

        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Android refused to open " + name);

        // Prefer a vendor-specific interface (every supported adapter's frame pipe), then any
        // interface that actually carries bulk endpoints.
        UsbInterface claimed = null;
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            UsbEndpoint candidateIn = null;
            UsbEndpoint candidateOut = null;
            for (int e = 0; e < candidate.getEndpointCount(); e++) {
                UsbEndpoint endpoint = candidate.getEndpoint(e);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN && candidateIn == null) {
                    candidateIn = endpoint;
                } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT && candidateOut == null) {
                    candidateOut = endpoint;
                }
            }
            if (candidateIn == null || candidateOut == null) continue;
            if (claimed == null
                    || candidate.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                claimed = candidate;
                in = candidateIn;
                out = candidateOut;
                if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) break;
            }
        }
        if (claimed == null) {
            connection.close();
            throw new IOException(name + " has no bulk endpoints to carry frames");
        }
        if (!connection.claimInterface(claimed, true)) {
            connection.close();
            throw new IOException("could not claim the interface of " + name);
        }
        return new UsbTransport(connection, claimed, in, out);
    }

    /** Path of the device the bridge would name, if it is still attached. */
    public static boolean attached(Context context, String name) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return manager != null && find(manager, name) != null;
    }

    public int bulkIn(byte[] buffer, int timeoutMs) {
        return connection.bulkTransfer(in, buffer, buffer.length, timeoutMs);
    }

    public int bulkOut(byte[] data, int timeoutMs) {
        return connection.bulkTransfer(out, data, data.length, timeoutMs);
    }

    /** A vendor control request (register reads/writes, channel selection, firmware checks). */
    public int control(int requestType, int request, int value, int index, byte[] data, int timeoutMs) {
        return connection.controlTransfer(requestType, request, value, index,
                data, data == null ? 0 : data.length, timeoutMs);
    }

    @Override
    public void close() {
        try {
            connection.releaseInterface(claimed);
        } catch (Exception ignored) {
            // The device may already be gone.
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // Already closed by the kernel.
        }
    }

    private static UsbDevice find(UsbManager manager, String name) {
        Map<String, UsbDevice> devices = manager.getDeviceList();
        if (devices == null) return null;
        for (UsbDevice device : devices.values()) {
            if (name.equals(device.getDeviceName())) return device;
        }
        return null;
    }

    /** Ask Android for the app-wide permission the bridge asks for, and wait for the answer. */
    private static boolean requestPermission(Context context, UsbManager manager, UsbDevice device) {
        Intent intent = new Intent(ACTION_PERMISSION).setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent, flags);
        if (pending == null) return false;

        CountDownLatch answered = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent received) {
                if (ACTION_PERMISSION.equals(received.getAction())) answered.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        try {
            manager.requestPermission(device, pending);
            answered.await(PERMISSION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {
                // Already unregistered.
            }
        }
        return manager.hasPermission(device);
    }
}
