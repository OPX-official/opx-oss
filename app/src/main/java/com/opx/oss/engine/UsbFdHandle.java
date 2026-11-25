package com.opx.oss.engine;

import android.app.PendingIntent;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

/**
 * Opens a USB device the way the Python driver path needs it: the usbfs file descriptor, with the
 * interface deliberately <em>not</em> claimed. libusb takes the fd over with
 * {@code libusb_wrap_sys_device()} and does its own claiming, so claiming here would make the
 * driver's claim fail.
 * <p>
 * Closing the connection closes this handle; the wrapped libusb handle must be closed first, which
 * the Python host does.
 */
public final class UsbFdHandle implements Closeable {

    private final UsbDeviceConnection connection;
    private final int vendorId;
    private final int productId;

    private UsbFdHandle(UsbDeviceConnection connection, int vendorId, int productId) {
        this.connection = connection;
        this.vendorId = vendorId;
        this.productId = productId;
    }

    /** Open {@code name} (the bridge's device path) without claiming any interface. */
    public static UsbFdHandle open(Context context, String name) throws IOException {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) throw new IOException("this build has no USB host service");
        UsbDevice device = find(manager, name);
        if (device == null) throw new IOException("no USB device at " + name);
        if (!manager.hasPermission(device) && !UsbTransport.ensurePermission(context, manager, device)) {
            throw new IOException("USB permission was not granted for " + name);
        }
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Android refused to open " + name);
        return new UsbFdHandle(connection, device.getVendorId(), device.getProductId());
    }

    /** The usbfs fd libusb wraps; valid until {@link #close()}. */
    public int fileDescriptor() {
        return connection.getFileDescriptor();
    }

    public int vendorId() {
        return vendorId;
    }

    public int productId() {
        return productId;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception ignored) {
            // Already gone, or libusb closed the underlying fd.
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

    /** Kept here so both transports share one permission path. */
    static boolean ensurePermission(Context context, UsbManager manager, UsbDevice device) {
        android.content.Intent intent = new android.content.Intent(UsbTransport.ACTION_PERMISSION)
                .setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent, flags);
        if (pending == null) return false;

        final java.util.concurrent.CountDownLatch answered =
                new java.util.concurrent.CountDownLatch(1);
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, android.content.Intent received) {
                if (UsbTransport.ACTION_PERMISSION.equals(received.getAction())) answered.countDown();
            }
        };
        android.content.IntentFilter filter =
                new android.content.IntentFilter(UsbTransport.ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        try {
            manager.requestPermission(device, pending);
            answered.await(120_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
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
