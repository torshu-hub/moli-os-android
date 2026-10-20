package io.moli.android;

import android.os.SystemClock;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Active, bounded loopback HTTP checks; no wake lock, stored token or response log. */
final class EngineHealth {
    static final long INTERVAL = 30_000, FRESH = 65_000;
    private static final ExecutorService CHECKS = Executors.newSingleThreadExecutor();
    private static long lease = -1, lastAttempt = -1, lastSuccess = -1, maxGap;
    private static int successes, failures;
    private static boolean busy, lastOk;
    private EngineHealth() {}

    static synchronized void reset(long generation) {
        lease = generation; lastAttempt = lastSuccess = -1; maxGap = 0;
        successes = failures = 0; lastOk = false;
        // An older request may still be running. It releases busy, but cannot publish.
    }
    static synchronized long[] snapshot() {
        long current = EngineHost.currentLease();
        if (lease != current) reset(current);
        long now = SystemClock.elapsedRealtime();
        long age = lastSuccess < 0 ? -1 : now - lastSuccess;
        long gap = Math.max(maxGap, lastAttempt < 0 ? 0 : now - lastAttempt);
        boolean ready = lease == EngineHost.currentLease() && EngineHost.state() == EngineHost.State.RUNNING && lastOk && age >= 0 && age <= FRESH;
        return new long[]{ready ? 1 : 0, age, successes, failures, gap};
    }
    static synchronized void check() {
        if (busy || EngineHost.state() != EngineHost.State.RUNNING) return;
        final long generation = EngineHost.currentLease();
        final String origin = EngineHost.origin(), token = EngineHost.token();
        if (generation != lease) reset(generation);
        long now = SystemClock.elapsedRealtime();
        if (lastAttempt >= 0) maxGap = Math.max(maxGap, now - lastAttempt);
        lastAttempt = now; busy = true;
        CHECKS.execute(() -> {
            boolean ok = request(origin, token);
            synchronized (EngineHealth.class) {
                busy = false;
                if (generation != lease || generation != EngineHost.currentLease()
                        || EngineHost.state() != EngineHost.State.RUNNING) return;
                lastOk = ok;
                if (ok) { successes++; lastSuccess = SystemClock.elapsedRealtime(); }
                else failures++;
            }
        });
    }
    static boolean request(String origin, String token) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(origin + "/api/health");
            if (!"http".equals(url.getProtocol()) || !"127.0.0.1".equals(url.getHost())
                    || url.getPort() < 1 || token == null) return false;
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("Cookie", "moli_android=" + token);
            if (connection.getResponseCode() != 200) return false;
            // Health may contain driver details; only validate identity and discard it.
            try (InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > 65_536) return false;
                    bytes.write(buffer, 0, n);
                }
                byte[] body = bytes.toByteArray();
                return "moli-os".equals(new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("name"));
            }
        } catch (Exception failure) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }
}
