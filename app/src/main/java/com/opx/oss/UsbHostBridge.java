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
 * The USB host bridge that replaces the Termux:API app.
 * <p>
 * Termux:API used to receive a broadcast from {@code termux-usb}, ask Android for permission
 * and hand the device descriptor to the requesting process over a Unix socket. OPX OSS keeps
 * that design but moves the server into this app, so the workspace and the API live in one
 * UID and one APK:
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
                    if (running) Log.w(TAG, "USB bridge request failed", e);
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
                response.put("ok", false);
                response.put("error", "no USB device at " + path);
                write(socket, response);
                return;
            }
            if (!ensurePermission(device)) {
                response.put("ok", false);
                response.put("error", "USB permission was not granted for " + path);
                write(socket, response);
                return;
            }
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                response.put("ok", false);
                response.put("error", "Android refused to open " + path);
                write(socket, response);
                return;
            }
            // The connection owns the descriptor; SCM_RIGHTS gives the engine its own copy, so
            // both stay usable and the wrapper must outlive the send (closed in shutdown()).
            ParcelFileDescriptor descriptor =
                    ParcelFileDescriptor.adoptFd(connection.getFileDescriptor());
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
            PendingIntent pending = PendingIntent.getBroadcast(context, 0,
                    new Intent(ACTION_PERMISSION).setPackage(context.getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pending);
            boolean answeredInTime = answered.await(120, TimeUnit.SECONDS);
            if (!answeredInTime) Log.w(TAG, "USB permission request timed out");
            return usbManager.hasPermission(device);
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
}
