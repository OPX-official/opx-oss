package com.opx.oss;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The USB host bridge: the app's own USB API, living in the same process as the engine.
 * <p>
 * wifi4 asks the {@code termux-usb} command for a descriptor; this class answers on the
 * abstract socket {@code opx.usb} in the same UID, asks Android for the device permission
 * and hands the descriptor to the requesting process over SCM_RIGHTS. No second app:
 * <pre>
 *   termux-usb (shim in the userland)  ->  abstract socket "opx.usb"  ->  this class
 *                                      <-  JSON reply + SCM_RIGHTS fd
 * </pre>
 * The engine's wifi4 backend is unchanged: it still asks {@code termux-usb} for a descriptor.
 */
public final class UsbHostBridge {

    private static final String TAG = "OpxUsb";
    private static final String SOCKET_NAME = "opx.usb";
    private static final String ACTION_PERMISSION = "com.opx.oss.action.USB_PERMISSION";

    private static UsbHostBridge instance;

    private final Context context;
    private final UsbManager usbManager;
    private final List<UsbDeviceConnection> openConnections = new ArrayList<>();
    private final List<ParcelFileDescriptor> openDescriptors = new ArrayList<>();

    private Thread acceptThread;
    private LocalServerSocket server;
    private volatile boolean running;

    private UsbHostBridge(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public static synchronized void start(Context context) {
        if (instance != null && instance.running) return;
        instance = new UsbHostBridge(context.getApplicationContext());
        instance.listen();
    }

    public static synchronized void stop() {
        if (instance == null) return;
        instance.shutdown();
        instance = null;
    }

    private void listen() {
        try {
            server = new LocalServerSocket(SOCKET_NAME);
        } catch (Exception e) {
            Log.w(TAG, "cannot open the USB bridge socket", e);
            AppLog.error(TAG, e);
            return;
        }
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try (LocalSocket socket = server.accept()) {
                    if (socket.getPeerCredentials().getUid() != android.os.Process.myUid()) {
                        Log.w(TAG, "rejected a request from another uid");
                        continue;
                    }
                    handle(socket);
                } catch (Exception e) {
                    if (running) {
                        Log.w(TAG, "USB bridge request failed", e);
                        AppLog.error(TAG, e);
                    }
                }
            }
        }, "opx-usb-bridge");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "USB bridge listening on " + SOCKET_NAME);
    }

    private void shutdown() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
            // Closing twice is harmless.
        }
        server = null;
        synchronized (openDescriptors) {
            for (ParcelFileDescriptor descriptor : openDescriptors) {
                try {
                    descriptor.close();
                } catch (Exception ignored) {
                    // Already closed together with its connection.
                }
            }
            openDescriptors.clear();
        }
        synchronized (openConnections) {
            for (UsbDeviceConnection connection : openConnections) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                    // Already closed by the kernel.
                }
            }
            openConnections.clear();
        }
    }

    private void handle(LocalSocket socket) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null || line.isEmpty()) return;
        JSONObject request = new JSONObject(line);
        String command = request.optString("cmd");

        if ("list".equals(command)) {
            JSONObject response = new JSONObject();
            JSONArray devices = new JSONArray();
            for (String path : devicePaths()) devices.put(path);
            AppLog.info(TAG, "list -> " + devices);
            response.put("ok", true);
            response.put("devices", devices);
            write(socket, response);
            return;
        }

        if ("open".equals(command)) {
            String path = request.optString("path");
            JSONObject response = new JSONObject();
            UsbDevice device = find(path);
            if (device == null) {
                AppLog.info(TAG, "open " + path + " -> no such device");
                response.put("ok", false);
                response.put("error", "no USB device at " + path);
                write(socket, response);
                return;
            }
            if (!ensurePermission(device)) {
                AppLog.info(TAG, "open " + path + " -> permission not granted");
                response.put("ok", false);
                response.put("error", "USB permission was not granted for " + path);
                write(socket, response);
                return;
            }
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                AppLog.info(TAG, "open " + path + " -> Android refused");
                response.put("ok", false);
                response.put("error", "Android refused to open " + path);
                write(socket, response);
                return;
            }
            // The connection owns the descriptor; SCM_RIGHTS gives the engine its own copy, so
            // both stay usable and the wrapper must outlive the send (closed in shutdown()).
            // Hand a duplicate to the engine: the connection keeps owning its own fd, so the
            // two close paths (ParcelFileDescriptor / UsbDeviceConnection) never fight.
            int duplicated = android.system.Os.dup(connection.getFileDescriptor());
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.adoptFd(duplicated);
            response.put("ok", true);
            setFileDescriptorsForSend(socket, new FileDescriptor[]{descriptor.getFileDescriptor()});
            write(socket, response);
            setFileDescriptorsForSend(socket, null);
            synchronized (openConnections) {
                openConnections.add(connection);
            }
            synchronized (openDescriptors) {
                openDescriptors.add(descriptor);
            }
            Log.i(TAG, "passed " + path + " to the engine");
            AppLog.info(TAG, "open " + path + " -> fd passed to the engine");
            return;
        }

        JSONObject response = new JSONObject();
        response.put("ok", false);
        response.put("error", "unknown command: " + command);
        write(socket, response);
    }

    private void write(LocalSocket socket, JSONObject response) throws Exception {
        OutputStream out = socket.getOutputStream();
        out.write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private List<String> devicePaths() {
        List<String> paths = new ArrayList<>();
        if (usbManager == null) return paths;
        for (String name : usbManager.getDeviceList().keySet()) paths.add(name);
        return paths;
    }

    private UsbDevice find(String path) {
        if (usbManager == null || path == null) return null;
        UsbDevice direct = usbManager.getDeviceList().get(path);
        if (direct != null) return direct;
        // The engine may hand back a path from an earlier enumeration; match by path suffix.
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (path.equalsIgnoreCase(device.getDeviceName())) return device;
        }
        return null;
    }

    /** Asks Android for the device permission and waits for the user's answer. */
    private boolean ensurePermission(UsbDevice device) {
        if (usbManager == null) return false;
        if (usbManager.hasPermission(device)) return true;

        final CountDownLatch answered = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                answered.countDown();
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_PERMISSION));
        try {
            // Android fills the answer into this PendingIntent's extras (EXTRA_DEVICE and
            // EXTRA_PERMISSION_GRANTED); an immutable intent cannot receive those fill-ins,
            // so this uses FLAG_MUTABLE like Termux:API does. The grant itself is still read
            // back with hasPermission(), so the extras are only belt and braces.
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingFlags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(context, 0,
                    new Intent(ACTION_PERMISSION).setPackage(context.getPackageName()),
                    pendingFlags);
            usbManager.requestPermission(device, pending);
            boolean answeredInTime = answered.await(120, TimeUnit.SECONDS);
            if (!answeredInTime) {
                Log.w(TAG, "USB permission request timed out");
                AppLog.info(TAG, "permission request for " + device.getDeviceName() + " timed out");
            }
            boolean granted = usbManager.hasPermission(device);
            AppLog.info(TAG, "permission for " + device.getDeviceName() + " -> " + granted);
            return granted;
        } catch (Exception e) {
            Log.w(TAG, "USB permission request failed", e);
            return false;
        } finally {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
        }
    }

    /**
     * {@code LocalSocket#setFileDescriptorsForSend} is the kernel's SCM_RIGHTS path. It is
     * reached through reflection so the build does not depend on a hidden SDK member.
     */
    private static void setFileDescriptorsForSend(LocalSocket socket, FileDescriptor[] descriptors)
            throws Exception {
        Method method = LocalSocket.class.getMethod("setFileDescriptorsForSend",
                FileDescriptor[].class);
        method.invoke(socket, (Object) descriptors);
    }

    /** Human readable one-line description, used by the WiFi screen. */
    public static String describe(UsbDevice device) {
        if (device == null) return "";
        return String.format(Locale.US, "%04x:%04x", device.getVendorId(), device.getProductId());
    }

    /**
     * A text diagnosis of the whole USB path: what Android sees, which devices hold a
     * permission, and whether the bridge socket is listening. This is what the WiFi screen
     * shows under "USB adapter" and what its Copy button shares.
     */
    public static String diagnose(Context context) {
        StringBuilder text = new StringBuilder();
        text.append("OPX USB diagnosis\n");
        text.append("app: ").append(context.getPackageName()).append('
');
        text.append("bridge socket: ").append(SOCKET_NAME)
                .append(" (").append(instance != null && instance.running ? "listening" : "not listening")
                .append(")\n");

        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            text.append("UsbManager: unavailable\n");
            AppLog.info(TAG, text.toString().replace('\n', " | "));
            return text.toString();
        }
        java.util.List<UsbDevice> devices = new ArrayList<>(manager.getDeviceList().values());
        text.append("USB devices attached: ").append(devices.size()).append('
');
        for (UsbDevice device : devices) {
            text.append("- ").append(device.getDeviceName())
                    .append("  product=").append(device.getProductId())
                    .append(" vendor=").append(device.getVendorId())
                    .append(" permission=").append(manager.hasPermission(device) ? "granted" : "not granted")
                    .append('
');
        }
        if (devices.isEmpty()) {
            text.append("  (none — plug an OTG Wi-Fi adapter in and accept the permission prompt)\n");
        }
        text.append("runtime prefix: ").append(OpxRuntime.PREFIX_GUEST).append('
');
        text.append("installed: ").append(OpxRuntime.isInstalled(context)).append('
');
        AppLog.info(TAG, text.toString().replace('\n', " | "));
        return text.toString();
    }
}
