package com.opx.oss.engine.chips;

import android.content.Context;

import com.opx.oss.AppLog;
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
    private static final String BACKEND_MODULE = "opx_wifi4";

    private static volatile boolean runtimeStarted;

    private final Context context;
    private final String path;

    private UsbFdHandle handle;
    /** This card's session key in {@code opx_driver_host}: its usbfs fd, so a hub's other
     *  adapters stay separate sessions and never tune or transmit through this one. */
    private int session;
    private Object module;
    private volatile String macAddress;
    private volatile Driver.FrameSink sink;
    private volatile Driver.DisconnectListener disconnectListener;
    private volatile boolean up;

    public PythonChip(Context context, String path) {
        this.context = context.getApplicationContext();
        this.path = path;
    }

    /**
     * Start the embedded interpreter once per process. Chaquopy refuses {@code getModule()} before
     * this, and the engine asks "do you have a driver?" long before it opens a card.
     */
    static synchronized void ensureStarted(Context context) {
        if (runtimeStarted) return;
        try {
            Class<?> platformClass = Class.forName("com.chaquo.python.android.AndroidPlatform");
            Object platform = platformClass.getConstructor(Context.class)
                    .newInstance(context.getApplicationContext());
            Class<?> pythonClass = Class.forName("com.chaquo.python.Python");
            // There is no class called com.chaquo.python.Platform: start() takes the *nested*
            // Python$Platform, so look the method up by name instead of guessing its type.
            Method start = null;
            for (Method candidate : pythonClass.getMethods()) {
                if ("start".equals(candidate.getName()) && candidate.getParameterCount() == 1
                        && candidate.getParameterTypes()[0].isInstance(platform)) {
                    start = candidate;
                    break;
                }
            }
            if (start == null) throw new NoSuchMethodException("Python.start(Platform)");
            start.invoke(null, platform);
            runtimeStarted = true;
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException("this build has no embedded Python", missing);
        } catch (ReflectiveOperationException failed) {
            throw new IllegalStateException("embedded Python could not start", failed);
        }
    }

    /** Start the interpreter for the backend commands (idempotent, same runtime as the chip). */
    public static void ensurePython(Context context) {
        ensureStarted(context);
    }

    /** Call {@code opx_wifi4.<function>}; the backend answers in JSON text. */
    public static String backendText(String function, Object... args) {
        return text(call(pythonModule(BACKEND_MODULE), function, args));
    }

    /** Call {@code opx_wifi4.<function>} for a yes/no answer. */
    public static boolean backendBool(String function, Object... args) {
        return truth(call(pythonModule(BACKEND_MODULE), function, args));
    }

    /** True when the vendored wifi4 registry claims this id pair. */
    static boolean supports(Context context, int vendorId, int productId) {
        try {
            ensureStarted(context);
            Object module = hostModule();
            return truth(call(module, "has_driver", vendorId, productId));
        } catch (Throwable missing) {
            // No embedded Python (JVM tests, stripped build): this family simply isn't available.
            // Logged, because "the card is unsupported" and "Python did not start" look the same
            // to the engine and only this line tells them apart.
            AppLog.error("OpxPython", missing);
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
        ensureStarted(context);
        handle = UsbFdHandle.open(context, path);
        try {
            module = hostModule();
            session = handle.fileDescriptor();
            Object mac = call(module, "start", session,
                    handle.vendorId(), handle.productId(), this);
            macAddress = text(mac);
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
        callChecked("set_channel", session, channel, false);
    }

    @Override
    public void transmit(byte[] mpdu) throws IOException {
        if (!up) throw new IOException("the card is not open");
        callChecked("inject", session, mpdu);
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
            return number(call(module, "acks", session));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    @Override
    public void close() {
        up = false;
        if (module != null) {
            try {
                call(module, "stop", session);
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
        return pythonModule(HOST_MODULE);
    }

    private static Object pythonModule(String name) {
        try {
            Class<?> pythonClass = Class.forName("com.chaquo.python.Python");
            Object python = pythonClass.getMethod("getInstance").invoke(null);
            Method getModule = pythonClass.getMethod("getModule", String.class);
            return getModule.invoke(python, name);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException failed) {
            throw new IllegalStateException("embedded Python is not available", failed);
        }
    }

    private static Object call(Object module, String name, Object... args) {
        try {
            Method method = module.getClass().getMethod("callAttr", String.class, Object[].class);
            return method.invoke(module, name, args);
        } catch (InvocationTargetException failed) {
            // getCause() is the Python exception (PyException); its message is the traceback tail,
            // which is what tells a firmware problem from a missing module.
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw new IllegalStateException("Python call " + name + " failed: " + describe(cause),
                    cause);
        } catch (NoSuchMethodException | IllegalAccessException failed) {
            throw new IllegalStateException("Python call " + name + " failed: " + describe(failed),
                    failed);
        }
    }

    // -- Python value conversion --------------------------------------------------------

    /**
     * Chaquopy returns every Python value as a {@code PyObject}, never as a Java wrapper: asking
     * {@code Boolean.TRUE.equals(...)} would therefore be false even for Python {@code True}. Let
     * the object convert itself.
     */
    private static boolean truth(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value == null) return false;
        try {
            Object answer = value.getClass().getMethod("toBoolean").invoke(value);
            return answer instanceof Boolean && (Boolean) answer;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return false;
        }
    }

    private static long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            Object answer = value.getClass().getMethod("toLong").invoke(value);
            return answer instanceof Number ? ((Number) answer).longValue() : 0L;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return 0L;
        }
    }

    /** A Python string as Java text, and Python {@code None} as {@code null}. */
    private static String text(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        String rendered = value.toString();
        return "None".equals(rendered) ? null : rendered;
    }

    private static String describe(Throwable failed) {
        Throwable root = failed;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isEmpty() ? root.toString() : message;
    }
}
