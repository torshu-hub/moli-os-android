package io.moli.android;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Existing Rust file-profile trust and polling on Android; no SAF UI is claimed. */
@RunWith(AndroidJUnit4.class)
public final class ProfileFixtureTest {
    private static void write(java.nio.file.Path path, String text, java.nio.charset.Charset charset) throws IOException {
        Files.write(path, text.getBytes(charset));
    }
    private static JSONObject snapshot(int port, String token) throws Exception {
        HttpURLConnection request = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/devices").openConnection();
        request.setRequestProperty("Cookie", "moli_android=" + token);
        request.setConnectTimeout(2000); request.setReadTimeout(2000);
        try {
            assertEquals(200, request.getResponseCode());
            return new JSONObject(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally { request.disconnect(); }
    }
    private static JSONObject waitFor(int port, String token, boolean waiting) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 15000;
        JSONObject result;
        do {
            result = snapshot(port, token);
            if (waiting ? result.getJSONArray("drivers").toString().contains("waiting")
                    : result.getJSONArray("devices").toString().contains("42.5")) return result;
            Thread.sleep(100);
        } while (android.os.SystemClock.elapsedRealtime() < until);
        fail("Expected synthetic profile state did not arrive"); return result;
    }
    @Test public void fileProfileRequiresApprovalAndRejectsChangedBytes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File data = new File(context.getCacheDir(), "profile-fixture-" + System.nanoTime());
        assertTrue(data.mkdir());
        AtomicInteger reads = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket endpoint = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))) {
            endpoint.setSoTimeout(200);
            Future<?> server = worker.submit(() -> {
                while (!Thread.currentThread().isInterrupted() && !endpoint.isClosed()) {
                    try (Socket client = endpoint.accept()) {
                        client.setSoTimeout(2000);
                        BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String first = input.readLine();
                        if (!"GET /status HTTP/1.1".equals(first)) throw new AssertionError("Read-only GET expected");
                        String line; while ((line = input.readLine()) != null && !line.isEmpty()) { }
                        reads.incrementAndGet();
                        byte[] body = "{\"temperature\":42.5}".getBytes(StandardCharsets.UTF_8);
                        client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        client.getOutputStream().write(body);
                    } catch (SocketTimeoutException timeout) { /* bounded shutdown */ }
                    catch (IOException failure) { if (!endpoint.isClosed()) throw new RuntimeException(failure); }
                }
            });
            String profile = "[profile]\nid = \"synthetic-temperature\"\nname = \"Synthetic temperature\"\n"
                    + "[[request]]\nid = \"reading\"\npath = \"/status\"\nformat = \"json\"\nevery = \"5s\"\n"
                    + "[[point]]\nkey = \"temperature\"\nlabel = \"Synthetic temperature\"\nfrom = \"reading.temperature\"\nkind = \"numeric\"\nunit = \"°C\"\n";
            File file = new File(data, "synthetic.toml");
            write(file.toPath(), profile, StandardCharsets.UTF_8);
            String config = "[[driver]]\nid = \"synthetic\"\nkind = \"profile\"\n[driver.options]\nprofile = \""
                    + file.getAbsolutePath() + "\"\nhost = \"127.0.0.1\"\nport = " + endpoint.getLocalPort() + "\n";
            write(new File(data, "moli.toml").toPath(), config, StandardCharsets.UTF_8);
            String key = ProtectedKey.randomHex(), token = ProtectedKey.randomHex();
            try {
                int port = NativeServer.start(data.getAbsolutePath(), key, token, "4826");
                waitFor(port, token, true); assertEquals(0, reads.get()); NativeServer.stop();
                // Fixture models an explicit approval of these exact bytes. It does not auto-trust imports.
                StringBuilder sha = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(profile.getBytes(StandardCharsets.UTF_8)))
                    sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                write(new File(data, "trusted-profiles.toml").toPath(),
                        "[[trusted]]\npath = \"" + file.getAbsolutePath() + "\"\nsha256 = \"" + sha + "\"\n", StandardCharsets.UTF_8);
                port = NativeServer.start(data.getAbsolutePath(), key, token, "4826");
                JSONObject live = waitFor(port, token, false);
                assertTrue(live.getJSONArray("devices").toString().contains("42.5"));
                assertTrue(reads.get() > 0); NativeServer.stop();
                int approvedReads = reads.get();
                write(file.toPath(), profile + "\n# changed after approval\n", StandardCharsets.UTF_8);
                port = NativeServer.start(data.getAbsolutePath(), key, token, "4826");
                waitFor(port, token, true);
                assertEquals(approvedReads, reads.get());
            } finally { NativeServer.stop(); }
            worker.shutdownNow(); server.get(4, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }
}
