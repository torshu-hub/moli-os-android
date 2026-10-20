package io.moli.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class EngineHealthTest {
    private boolean answer(int status, String body) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<?> response = worker.submit(() -> {
                try (java.net.Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                    String line; boolean auth = false;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.equals("Cookie: moli_android=synthetic")) auth = true;
                    }
                    if (!auth) throw new AssertionError("Authenticated probe required");
                    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    client.getOutputStream().write(("HTTP/1.1 " + status + " Test\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().write(payload);
                } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
            });
            try {
                boolean result = EngineHealth.request("http://127.0.0.1:" + server.getLocalPort(), "synthetic");
                response.get(6, TimeUnit.SECONDS); return result;
            } finally { worker.shutdownNow(); }
        }
    }
    @Test public void requiresActualMoliResponse() throws Exception {
        assertTrue(answer(200, "{\"name\":\"moli-os\"}"));
        assertFalse(answer(200, "{\"name\":\"other\"}"));
        assertFalse(answer(200, "not json"));
    }
    @Test public void rejectsErrorsAndRedirects() throws Exception {
        assertFalse(answer(401, "")); assertFalse(answer(302, ""));
    }
    @Test public void rejectsOversizedResponse() throws Exception { assertFalse(answer(200, "x".repeat(65537))); }
    @Test public void doesNotContactNonLoopbackEndpoint() {
        assertFalse(EngineHealth.request("https://example.com", "synthetic"));
        assertFalse(EngineHealth.request("http://localhost:80", "synthetic"));
    }
}
