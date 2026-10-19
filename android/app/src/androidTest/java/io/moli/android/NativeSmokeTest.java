package io.moli.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import org.junit.FixMethodOrder;
import org.junit.runners.MethodSorters;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.content.Context;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** No household devices or real credentials; executable on an isolated emulator. */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class NativeSmokeTest {
    private static HttpURLConnection get(int port, String path, String token) throws Exception {
        HttpURLConnection request = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        request.setConnectTimeout(5000); request.setReadTimeout(5000);
        if (token != null) request.setRequestProperty("Cookie", "moli_android=" + token);
        return request;
    }
    @Test public void a_nativeServerAuthenticatesEmbedsDashboardAndRestarts() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File data = new File(context.getCacheDir(), "synthetic-native-test-" + System.nanoTime());
        String key = ProtectedKey.randomHex();
        String token = ProtectedKey.randomHex();
        int port = NativeServer.start(data.getAbsolutePath(), key, token, "4826");
        try {
            HttpURLConnection stranger = get(port, "/api/health", null);
            assertEquals(401, stranger.getResponseCode()); stranger.disconnect();
            HttpURLConnection valid = get(port, "/api/health", token);
            assertEquals(200, valid.getResponseCode());
            assertTrue(new String(valid.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("moli-os"));
            valid.disconnect();
            HttpURLConnection dashboard = get(port, "/", token);
            assertEquals(200, dashboard.getResponseCode());
            assertTrue(new String(dashboard.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT).contains("<!doctype html>"));
            dashboard.disconnect();
        } finally { NativeServer.stop(); }
        assertTrue(new File(data, "state.json").exists());
        assertFalse(new File(data, "master.key").exists());
        int again = NativeServer.start(data.getAbsolutePath(), key, token, "4826");
        try {
            HttpURLConnection healthy = get(again, "/api/health", token);
            assertEquals(200, healthy.getResponseCode()); healthy.disconnect();
        } finally { NativeServer.stop(); }
    }

    @Test public void b_androidKeystoreKeepsTheSamePortableMasterKey() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // This test runs in a disposable emulator application sandbox only.
        if (!ProtectedKey.exists(context)) ProtectedKey.create(context, "4826");
        String first = ProtectedKey.open(context).getString("master");
        assertEquals(64, first.length());
        assertEquals(first, ProtectedKey.open(context).getString("master"));
        assertFalse(new File(context.getFilesDir(), "moli/master.key").exists());
    }

    @Test public void z_foregroundHostRendersTheOriginalDashboardInWebView() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!ProtectedKey.exists(context)) ProtectedKey.create(context, "4826");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicBoolean rendered = new AtomicBoolean(false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!rendered.get() && System.nanoTime() < deadline) {
                CountDownLatch evaluated = new CountDownLatch(1);
                scenario.onActivity(activity -> activity.web.evaluateJavascript(
                        "location.hostname === '127.0.0.1' && document.getElementById('app') !== null && document.getElementById('app').innerText.length > 20",
                        result -> { rendered.set("true".equals(result)); evaluated.countDown(); }));
                assertTrue(evaluated.await(5, TimeUnit.SECONDS));
                if (!rendered.get()) Thread.sleep(250);
            }
            assertTrue("The real dashboard must render through the authenticated local WebView", rendered.get());
        } finally {
            context.stopService(new android.content.Intent(context, MoliService.class));
            NativeServer.stop();
        }
    }
}
