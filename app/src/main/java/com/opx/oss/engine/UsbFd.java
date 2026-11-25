package com.opx.oss.engine;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The bridge client: asks the USB bridge for an adapter descriptor and keeps the descriptor the
 * kernel hands over in SCM_RIGHTS.
 * <p>
 * The bridge, its abstract socket name, its {@code list}/{@code open} commands and the descriptor
 * hand-off are unchanged; this class is only the in-app caller that used to be an external
 * command. The variable a descriptor is published under stays {@link #FD_VARIABLE}.
 */
public final class UsbFd implements Closeable {

    /** Abstract socket the bridge binds for the lifetime of the app. */
    public static final String SOCKET_NAME = "opx.usb";
    /** The variable a descriptor is published under, unchanged from the first bridge. */
    public static final String FD_VARIABLE = "OPX_USB_FD";

    private final String path;
    private final FileDescriptor descriptor;

    private UsbFd(String path, FileDescriptor descriptor) {
        this.path = path;
        this.descriptor = descriptor;
    }

    /** The device path this descriptor belongs to, as the bridge names it. */
    public String path() {
        return path;
    }

    /** The raw descriptor. It stays owned by this object; callers must not close it themselves. */
    public FileDescriptor descriptor() {
        return descriptor;
    }

    /** Every device path the bridge currently sees. */
    public static List<String> list() throws IOException {
        LocalSocket socket = connect();
        try {
            write(socket, new JSONObject().put("cmd", "list"));
            JSONObject reply = read(socket);
            if (!reply.optBoolean("ok")) {
                throw new IOException(failure(reply, "the bridge refused the device list"));
            }
            List<String> paths = new ArrayList<>();
            JSONArray devices = reply.optJSONArray("devices");
            if (devices != null) {
                for (int i = 0; i < devices.length(); i++) {
                    String path = devices.optString(i);
                    if (path != null && !path.isEmpty()) paths.add(path);
                }
            }
            return paths;
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Ask the bridge to open {@code path}: it answers the permission question if Android has not
     * answered it yet and then hands back a descriptor of its own.
     */
    public static UsbFd open(String path) throws IOException {
        LocalSocket socket = connect();
        try {
            write(socket, new JSONObject().put("cmd", "open").put("path", path));
            JSONObject reply = read(socket);
            if (!reply.optBoolean("ok")) {
                throw new IOException(failure(reply, "the bridge refused " + path));
            }
            List<FileDescriptor> received = socket.getAncillaryFileDescriptors();
            if (received == null || received.isEmpty()) {
                throw new IOException("the bridge granted " + path + " without a descriptor");
            }
            return new UsbFd(path, received.get(0));
        } finally {
            closeQuietly(socket);
        }
    }

    @Override
    public void close() {
        try {
            descriptor.close();
        } catch (Exception ignored) {
            // Already closed by the bridge, or never opened.
        }
    }

    private static LocalSocket connect() throws IOException {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw new IOException("the USB bridge is not listening on " + SOCKET_NAME, e);
        }
    }

    private static void write(LocalSocket socket, JSONObject request) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static JSONObject read(LocalSocket socket) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null || line.isEmpty()) {
            throw new IOException("the USB bridge closed the request without a reply");
        }
        try {
            return new JSONObject(line);
        } catch (Exception e) {
            throw new IOException("the USB bridge answered something that is not JSON: " + line, e);
        }
    }

    private static String failure(JSONObject reply, String fallback) {
        String reason = reply.optString("error");
        return reason == null || reason.isEmpty() ? fallback : reason;
    }

    private static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }
}
