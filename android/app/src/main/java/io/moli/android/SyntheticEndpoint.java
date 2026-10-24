package io.moli.android;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** App-owned read-only samples (tablet and optional fixture); loopback only, no proxy. */
final class SyntheticEndpoint implements AutoCloseable {
    private final ServerSocket socket;
    SyntheticEndpoint() throws IOException { this(null); }
    SyntheticEndpoint(android.content.Context context) throws IOException {
        socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread worker = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(1000);
                    // Bounded request headers; the fixture accepts one exact route.
                    ByteArrayOutputStream request = new ByteArrayOutputStream();
                    int b; String headers = "";
                    while (request.size() < 4096 && (b = client.getInputStream().read()) != -1) {
                        request.write(b);
                        if (b == '\n') { headers = request.toString("US-ASCII"); if (headers.endsWith("\r\n\r\n")) break; }
                    }
                    boolean tablet = context != null && headers.startsWith("GET /tablet HTTP/1.1\r\n");
                    boolean valid = (tablet || headers.startsWith("GET /status HTTP/1.1\r\n")) && headers.endsWith("\r\n\r\n");
                    byte[] body = (valid && tablet ? TabletReading.json(context).toString() : valid ? "{\"temperature\":42.5,\"humidity\":55}" : "{}").getBytes(StandardCharsets.UTF_8);
                    client.getOutputStream().write(("HTTP/1.1 " + (valid ? "200 OK" : "404 Not Found")
                            + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(body);
                } catch (IOException ignored) { /* next bounded request, no data logs */ }
            }
        }, "moli-synthetic");
        worker.setDaemon(true); worker.start();
    }
    int port() { return socket.getLocalPort(); }
    @Override public void close() { try { socket.close(); } catch (IOException ignored) { } }
}
