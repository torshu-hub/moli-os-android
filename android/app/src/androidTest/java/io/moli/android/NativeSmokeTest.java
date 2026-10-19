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

    private static Context sandbox(Context base, String name) {
        File root = new File(base.getCacheDir(), name + "-" + System.nanoTime());
        File files = new File(root, "files"), protectedFiles = new File(root, "protected");
        assertTrue(files.mkdirs()); assertTrue(protectedFiles.mkdirs());
        return new android.content.ContextWrapper(base) {
            @Override public File getFilesDir() { return files; }
            @Override public File getNoBackupFilesDir() { return protectedFiles; }
        };
    }
    @Test public void c_backupRestoresKeySqliteAndVaultIntoAnIndependentSandbox() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context source = sandbox(base, "export"), destination = sandbox(base, "import");
        ProtectedKey.create(source, "4826");
        org.json.JSONObject original = ProtectedKey.open(source);
        File data = ProtectedKey.dataDirectory(source, original);
        String sourceToken = ProtectedKey.randomHex();
        int sourcePort = NativeServer.start(data.getAbsolutePath(), original.getString("master"), sourceToken, "4826");
        try {
            HttpURLConnection login = get(sourcePort,"/api/session",sourceToken);
            login.setRequestMethod("POST"); login.setDoOutput(true); login.setRequestProperty("Content-Type","application/json");
            try (java.io.OutputStream body = login.getOutputStream()) { body.write("{\"pin\":\"4826\"}".getBytes(StandardCharsets.UTF_8)); }
            assertEquals(200,login.getResponseCode()); login.disconnect();
        } finally { NativeServer.stop(); }
        assertTrue(new File(data, "secrets.enc").isFile());
        File recordings = new File(data,"recordings"); assertTrue(recordings.mkdir());
        java.nio.file.Files.write(new File(recordings,"synthetic.txt").toPath(), new byte[]{17,42});
        java.nio.file.Files.write(new File(data,"settings.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(new File(data,"synthetic.db"),null)) {
            db.execSQL("CREATE TABLE sample (value INTEGER)"); db.execSQL("INSERT INTO sample VALUES (42)");
        }
        char[] password = "synthetic recovery words only".toCharArray();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); BackupStorage.exportTo(source,password,out);
        byte[] archive = out.toByteArray();
        byte[] truncated = java.util.Arrays.copyOf(archive, archive.length-20);
        try { BackupStorage.restoreFresh(destination,new java.io.ByteArrayInputStream(truncated),password); fail("Incomplete archive"); }
        catch (java.io.IOException expected) { assertFalse(ProtectedKey.exists(destination)); }
        assertEquals(0, destination.getFilesDir().list().length);
        BackupStorage.restoreFresh(destination,new java.io.ByteArrayInputStream(archive),password);
        org.json.JSONObject recovered = ProtectedKey.open(destination);
        assertTrue("Recovered key must match",original.getString("master").equals(recovered.getString("master")));
        File restored = ProtectedKey.dataDirectory(destination,recovered);
        assertArrayEquals(new byte[]{17,42},java.nio.file.Files.readAllBytes(new File(restored,"recordings/synthetic.txt").toPath()));
        assertTrue(new File(restored,"settings.json").isFile());
        assertFalse(new File(restored,"master.key").exists());
        try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(new File(restored,"synthetic.db").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
             android.database.Cursor cursor = db.rawQuery("SELECT value FROM sample",null)) {
            assertTrue(cursor.moveToFirst()); assertEquals(42,cursor.getInt(0));
        }
        int port = NativeServer.start(restored.getAbsolutePath(),recovered.getString("master"),ProtectedKey.randomHex(),recovered.getString("pin"));
        assertTrue(port > 0); NativeServer.stop();
        try { BackupStorage.restoreFresh(destination,new java.io.ByteArrayInputStream(archive),password); fail("Existing installation"); }
        catch (java.io.IOException expected) { assertTrue("Existing key must remain unchanged",original.getString("master").equals(ProtectedKey.open(destination).getString("master"))); }
        java.util.Arrays.fill(password,'\0');
    }
    private static void awaitState(EngineHost.State expected) throws Exception {
        long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while (EngineHost.state()!=expected && System.nanoTime()<until) Thread.sleep(100);
        assertEquals(expected,EngineHost.state());
    }
    @Test public void d_serviceSerializesRapidCyclesAndActivityRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!ProtectedKey.exists(context)) ProtectedKey.create(context,"4826");
        for (int cycle=0;cycle<3;cycle++) {
            context.startForegroundService(new android.content.Intent(context,MoliService.class));
            awaitState(EngineHost.State.RUNNING);
            assertTrue(context.getSystemService(android.app.NotificationManager.class).getActiveNotifications().length>0);
            if (cycle==0) {
                try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
                    scenario.recreate(); awaitState(EngineHost.State.RUNNING);
                }
                assertEquals(EngineHost.State.RUNNING,EngineHost.state());
            }
            context.startService(new android.content.Intent(context,MoliService.class).setAction(MoliService.STOP));
            awaitState(EngineHost.State.STOPPED);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (context.getSystemService(android.app.NotificationManager.class).getActiveNotifications().length!=0 && System.nanoTime()<until) Thread.sleep(100);
            assertEquals(0,context.getSystemService(android.app.NotificationManager.class).getActiveNotifications().length);
        }
        // Persistence of an explicit stop across Activity recreation: reopening must not start it.
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.recreate(); assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        }
        // A start followed immediately by stop must drain in order, never overlap JNI calls.
        long lease = EngineHost.start(context); EngineHost.stop(lease); awaitState(EngineHost.State.STOPPED);
        assertNull(EngineHost.origin());
        // Restore starts from stopped state only; maintenance excludes a simultaneous start.
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<Void> maintenance = EngineHost.maintain(() -> { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return null; });
        assertTrue(entered.await(5,TimeUnit.SECONDS)); assertEquals(-1,EngineHost.start(context));
        release.countDown(); maintenance.get(5,TimeUnit.SECONDS); awaitState(EngineHost.State.STOPPED);
    }

    @Test public void z_foregroundHostRendersTheOriginalDashboardInWebView() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!ProtectedKey.exists(context)) ProtectedKey.create(context, "4826");
        context.getSharedPreferences("host",Context.MODE_PRIVATE).edit().putBoolean("wanted",true).commit();
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
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while (EngineHost.state()!=EngineHost.State.STOPPED && System.nanoTime()<deadline) Thread.sleep(100);
            assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        }
    }
}
