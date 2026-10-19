package io.moli.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Invoked one method at a time in two independently installed disposable apps. */
@RunWith(AndroidJUnit4.class)
public final class RecoveryTransferTest {
    private static final char[] PASSWORD = "synthetic recovery words only".toCharArray();
    private static HttpURLConnection request(int port, String path, String token) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setRequestProperty("Cookie","moli_android="+token); return c;
    }
    @Test public void exportSynthetic() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.moli.android.app.preview",context.getPackageName());
        assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        if (!ProtectedKey.exists(context)) ProtectedKey.create(context,"4826");
        org.json.JSONObject keys=ProtectedKey.open(context); File data=ProtectedKey.dataDirectory(context,keys);
        String token=ProtectedKey.randomHex(); int port=NativeServer.start(data.getAbsolutePath(),keys.getString("master"),token,keys.getString("pin"));
        try {
            HttpURLConnection login=request(port,"/api/session",token); login.setRequestMethod("POST"); login.setDoOutput(true);
            login.setRequestProperty("Content-Type","application/json");
            try (OutputStream body=login.getOutputStream()) {
                body.write(new org.json.JSONObject().put("pin",keys.getString("pin")).toString().getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(200,login.getResponseCode()); login.disconnect();
        } finally { NativeServer.stop(); }
        assertTrue(new File(data,"secrets.enc").isFile());
        File recordings=new File(data,"recordings"); if (!recordings.exists()) assertTrue(recordings.mkdir());
        Files.write(new File(recordings,"transfer.txt").toPath(),new byte[]{17,42});
        try (SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(new File(data,"recovery-check.db"),null)) {
            db.execSQL("CREATE TABLE sample (value INTEGER)"); db.execSQL("INSERT INTO sample VALUES (42)");
        }
        Files.write(new File(data,"settings.json").toPath(),"{}".getBytes(StandardCharsets.UTF_8));
        try (OutputStream out=new FileOutputStream(new File(context.getFilesDir(),"transfer.molibak"))) {
            BackupStorage.exportTo(context,PASSWORD,out);
        }
    }
    @Test public void importFreshIntoIndependentUid() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.moli.android.app.preview.receiver",context.getPackageName());
        // A separate Android UID cannot open the sender's Keystore or private files.
        assertFalse(ProtectedKey.exists(context));
        java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertFalse(store.containsAlias("moli.master.wrap.v1"));
        try (InputStream in=new FileInputStream(new File(context.getFilesDir(),"transfer.molibak"))) {
            BackupStorage.restoreFresh(context,in,PASSWORD);
        }
        assertTrue(ProtectedKey.exists(context));
        org.json.JSONObject keys=ProtectedKey.open(context); File restored=ProtectedKey.dataDirectory(context,keys);
        assertTrue(new File(restored,"secrets.enc").isFile());
        assertTrue(new File(restored,"settings.json").isFile());
        assertFalse(new File(restored,"master.key").exists());
        assertArrayEquals(new byte[]{17,42},Files.readAllBytes(new File(restored,"recordings/transfer.txt").toPath()));
        try (SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(restored,"recovery-check.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);
             android.database.Cursor row=db.rawQuery("SELECT value FROM sample",null)) {
            assertTrue(row.moveToFirst()); assertEquals(42,row.getInt(0));
        }
        String token=ProtectedKey.randomHex();
        // Embedded start refuses an undecryptable vault: success proves the portable key works.
        int port=NativeServer.start(restored.getAbsolutePath(),keys.getString("master"),token,keys.getString("pin"));
        try {
            HttpURLConnection health=request(port,"/api/health",token); assertEquals(200,health.getResponseCode()); health.disconnect();
        } finally { NativeServer.stop(); }
        assertFalse(context.getSharedPreferences("host",Context.MODE_PRIVATE).getBoolean("wanted",true));
    }
}
