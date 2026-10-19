package io.moli.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Iterator;
import static org.junit.Assert.*;

/** Two separate instrumentation invocations surrounding adb install -r, never app-data clearing. */
@RunWith(AndroidJUnit4.class)
public final class UpdateContinuityTest {
    private static String digest(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte b : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    private static JSONObject inventory(File root) throws Exception {
        JSONObject result = new JSONObject();
        try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(root.toPath())) {
            for (java.nio.file.Path path : (Iterable<java.nio.file.Path>)files.filter(Files::isRegularFile)::iterator)
                result.put(root.toPath().relativize(path).toString(), digest(Files.readAllBytes(path)));
        }
        return result;
    }
    private static PackageInfo info(Context context) throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
    }
    @Test public void prepareBeforeUpdate() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.moli.android.app.preview", context.getPackageName());
        assertEquals(3, info(context).getLongVersionCode());
        assertFalse(ProtectedKey.exists(context));
        // Seeds real engine config/vault, SQLite and a recording; exports encrypted rescue archive.
        new RecoveryTransferTest().exportSynthetic();
        assertTrue(context.getSharedPreferences("host", Context.MODE_PRIVATE).edit()
                .putBoolean("wanted", false).putString("update-sentinel", "synthetic setting").commit());
        JSONObject keys = ProtectedKey.open(context);
        File data = ProtectedKey.dataDirectory(context, keys);
        JSONObject snapshot = new JSONObject().put("uid", android.os.Process.myUid())
                .put("directory", data.getName()).put("inventory", inventory(data))
                .put("wrapper", digest(Files.readAllBytes(new File(context.getNoBackupFilesDir(), "moli-key.wrap").toPath())))
                .put("certificate", digest(info(context).signingInfo.getApkContentsSigners()[0].toByteArray()));
        // Only hashes and synthetic metadata are recorded; no plaintext recovery material.
        Files.write(new File(context.getFilesDir(), "update-check.json").toPath(), snapshot.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Test public void verifyAfterUpdate() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(4, info(context).getLongVersionCode());
        JSONObject previous = new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(), "update-check.json").toPath()), StandardCharsets.UTF_8));
        assertEquals(previous.getInt("uid"), android.os.Process.myUid());
        assertEquals(previous.getString("certificate"), digest(info(context).signingInfo.getApkContentsSigners()[0].toByteArray()));
        assertEquals(previous.getString("wrapper"), digest(Files.readAllBytes(new File(context.getNoBackupFilesDir(), "moli-key.wrap").toPath())));
        JSONObject keys = ProtectedKey.open(context); // Same wrapped record remains decryptable in Android Keystore.
        assertEquals("4826", keys.getString("pin"));
        File data = ProtectedKey.dataDirectory(context, keys);
        assertEquals(previous.getString("directory"), data.getName());
        JSONObject before = previous.getJSONObject("inventory"), after = inventory(data);
        assertEquals(before.length(), after.length());
        for (Iterator<String> names = before.keys(); names.hasNext();) {
            String name = names.next(); assertEquals(name, before.getString(name), after.getString(name));
        }
        assertFalse(context.getSharedPreferences("host", Context.MODE_PRIVATE).getBoolean("wanted", true));
        assertEquals("synthetic setting", context.getSharedPreferences("host", Context.MODE_PRIVATE).getString("update-sentinel", ""));
        // Reopening preserves explicit stop, including while reading update/power information.
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            assertEquals(EngineHost.State.STOPPED, EngineHost.state());
        }
        try (ActivityScenario<AppInfoActivity> ignored = ActivityScenario.launch(AppInfoActivity.class)) {
            assertEquals(EngineHost.State.STOPPED, EngineHost.state());
        }
        // Existing targeted assertion proves native restart + authenticated original WebView.
        new NativeSmokeTest().z_foregroundHostRendersTheOriginalDashboardInWebView();
    }
}
