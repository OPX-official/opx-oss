package com.opx.oss.engine.chips;

import android.content.Context;

import com.opx.oss.engine.UsbFdHandle;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A driver that lives in Python: the wifi4 package is vendored under {@code assets/python} and
 * loaded by Chaquopy, and this class is the {@link Driver} the Java engine sees. Nothing about the
 * chip is re-implemented here — {@code opx_driver_host} wraps the bridged usbfs fd with libusb,
 * asks wifi4's own package registry for the driver, and forwards channel changes, injections and
 * received frames.
 * <p>
 * Reflection is deliberate: the engine compiles without Chaquopy on the classpath (the JVM/stub
 * build has no Android runtime), and the bridge is only resolved on a device.
 */
public final class PythonChip implements Driver {

    private static final String HOST_MODULE = "opx_driver_host";

    private final Context context;
    private final String path;

    private UsbFdHandle handle;
    private Object module;
    private volatile String macAddress;
    private volatile Driver.FrameSink sink;
    private volatile Driver.DisconnectListener disconnectListener;
    private volatile boolean up;

    public PythonChip(Context context, String path) {
        this.context = context.getApplicationContext();
        this.path = path;
    }

    /** True when the vendored wifi4 registry claims this id pair. */
    public static boolean supports(int vendorId, int productId) {
        try {
            Object module = hostModule();
            return Boolean.TRUE.equals(call(module, "has_driver", vendorId, productId));
        } catch (Throwable missing) {
            // No embedded Python (JVM tests, stripped build): this family simply isn't available.
            return false;
        }
    }

    @Override
    public String name() {
        return "wifi4";
    }

    @Override
    public String mac() {
        return macAddress;
    }

    @Override
    public int[] supportedChannels() {
        return CHANNELS_2G.clone();
    }

    @Override
    public FakeMacSupport fakeMacSupport() {
        return FakeMacSupport.UNIMPLEMENTED;
    }

    @Override
    public void registerDisconnect(DisconnectListener listener) {
        disconnectListener = listener;
    }

    @Override
    public void open() throws IOException {
        if (up) return;
        handle = UsbFdHandle.open(context, path);
        try {
            module = hostModule();
            Object mac = call(module, "start", handle.fileDescriptor(),
                    handle.vendorId(), handle.productId(), this);
            macAddress = mac == null ? null : String.valueOf(mac);
            up = true;
        } catch (RuntimeException failed) {
            handle.close();
            handle = null;
            throw new IOException("the Python driver could not start: " + failed.getMessage(), failed);
        }
    }

    @Override
    public boolean isUp() {
        return up;
    }

    @Override
    public void setChannel(int channel) throws IOException {
        if (!up) throw new IOException("the card is not open");
        callChecked("set_channel", channel, false);
    }

    @Override
    public void transmit(byte[] mpdu) throws IOException {
        if (!up) throw new IOException("the card is not open");
        callChecked("inject", mpdu);
    }

    @Override
    public void startReceive(FrameSink sink) throws IOException {
        this.sink = sink;
    }

    /** Called from Python for every decoded frame. */
    public void onFrame(byte[] mpdu, int rssi) {
        Driver.FrameSink target = sink;
        if (target == null) return;
        try {
            target.onFrame(mpdu, rssi);
        } catch (RuntimeException ignored) {
            // A throwing sink must not kill the Python reader.
        }
    }

    /** Called from Python when the receive path dies for good. */
    public void onDisconnect() {
        up = false;
        Driver.DisconnectListener listener = disconnectListener;
        if (listener != null) listener.onDisconnect();
    }

    /** Called from Python with 0..100 and a stage message. */
    public void onProgress(int percent, String message) {
        // The engine has no progress UI yet; kept so the Python host can report uniformly.
    }

    @Override
    public long acksSeen() {
        try {
            Object value = call(module, "acks");
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    @Override
    public void close() {
        up = false;
        if (module != null) {
            try {
                call(module, "stop");
            } catch (Throwable ignored) {
                // The card may already be gone.
            }
            module = null;
        }
        if (handle != null) {
            handle.close();
            handle = null;
        }
    }

    private void callChecked(String name, Object... args) throws IOException {
        try {
            call(module, name, args);
        } catch (RuntimeException failed) {
            throw new IOException(name + " failed: " + failed.getMessage(), failed);
        }
    }

    // -- Chaquopy, by reflection so the JVM build needs no Android runtime --------------

    private static Object hostModule() {
        try {
            Class<?> pythonClass = Class.forName("com.chaquo.python.Python");
            Object python = pythonClass.getMethod("getInstance").invoke(null);
            Method getModule = pythonClass.getMethod("getModule", String.class);
            return getModule.invoke(python, HOST_MODULE);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException failed) {
            throw new IllegalStateException("embedded Python is not available", failed);
        }
    }

    private static Object call(Object module, String name, Object... args) {
        try {
            Method method = module.getClass().getMethod("callAttr", String.class, Object[].class);
            return method.invoke(module, name, args);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException failed) {
            throw new IllegalStateException("Python call " + name + " failed", failed);
        }
    }
}
