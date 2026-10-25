package io.moli.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

/** Disposable emulator only. No real login, LAN operation or plaintext cross-UID transfer. */
@RunWith(AndroidJUnit4.class)
public final class BetaUpdateTest {
    private static final char[] PASSWORD = "synthetic migration words only".toCharArray();
    private static final String PROOF = "beta-fixture-proof.json";
    private static final String LOCAL_KEY = "b".repeat(32);
    private static Context context() {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Disposable emulator only",android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"));
        assertTrue(c.getPackageName().equals("io.moli.android.app.preview.automatic.v0130") || c.getPackageName().equals("io.moli.android.app.beta") || c.getPackageName().equals("io.moli.android.app.preview.receiver"));
        if(c.getPackageName().endsWith(".beta")) assertTrue("Never run against a distribution beta",BuildConfig.UPDATE_FIXTURE);
        assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        return c;
    }
    private static String hash(byte[] bytes)throws Exception {
        byte[] raw=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();
        for(byte b:raw)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private static JSONObject read(File f)throws Exception {return new JSONObject(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));}
    private static void write(File f,JSONObject value)throws Exception {Files.write(f.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));}
    private static JSONObject inventory(File dir)throws Exception {
        JSONObject result=new JSONObject();
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(dir.toPath())) {
            for(java.nio.file.Path p:(Iterable<java.nio.file.Path>)files.filter(Files::isRegularFile)::iterator) {
                String name=dir.toPath().relativize(p).toString();
                if(!name.equals(PROOF))result.put(name,hash(Files.readAllBytes(p)));
            }
        }
        return result;
    }
    private static void same(JSONObject expected,JSONObject observed)throws Exception {
        assertEquals("Same inventory length",expected.length(),observed.length());
        for(Iterator<String> it=expected.keys();it.hasNext();) {String n=it.next();assertEquals("Preserved file: "+n,expected.getString(n),observed.getString(n));}
    }
    private static PackageInfo info(Context c)throws Exception {return c.getPackageManager().getPackageInfo(c.getPackageName(),android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);}
    private static String wrapper(Context c)throws Exception {return hash(Files.readAllBytes(new File(c.getNoBackupFilesDir(),"moli-key.wrap").toPath()));}
    private static String certificate(Context c)throws Exception {return hash(info(c).signingInfo.getApkContentsSigners()[0].toByteArray());}
    private static JSONObject credentialsProof(JSONObject keys)throws Exception {
        return new JSONObject().put("master",hash(keys.getString("master").getBytes(StandardCharsets.UTF_8)))
                .put("pin",hash(keys.getString("pin").getBytes(StandardCharsets.UTF_8))).put("format",keys.optInt("format",1));
    }
    private static void sameCredentials(JSONObject proof,JSONObject keys)throws Exception {
        JSONObject observed=credentialsProof(keys);assertEquals(proof.getInt("format"),observed.getInt("format"));
        assertEquals("Same portable key",proof.getString("master"),observed.getString("master"));
        assertEquals("Same bootstrap/PIN",proof.getString("pin"),observed.getString("pin"));
    }
    /** Constructs only synthetic MOLISEC1 test data per moli-runtime/src/secrets.rs.
     * The unchanged Rust reader below must accept it; no test hook in production. */
    private static void syntheticVault(File dir,JSONObject keys)throws Exception {
        JSONArray items=new JSONArray();JSONObject secrets=new JSONObject();
        for(int i=1;i<=4;i++) {
            String id=String.format(java.util.Locale.ROOT,"%032x",i);
            items.put(new JSONObject().put("uuid",id).put("name","Invented plug "+i).put("model","MSS310").put("hardware","6.0.0"));
            secrets.put("android-meross-"+id+"/key",LOCAL_KEY);
        }
        secrets.put("android-meross-account/session",new JSONObject().put("credentials",new JSONObject().put("host","iotx-eu.meross.com").put("key",LOCAL_KEY).put("token","invented-session-token"))
                .put("items",items).put("cached_ms",System.currentTimeMillis()).toString());
        secrets.put("energy-update-fixture/oauth_personal_application",new JSONObject().put("client_id","fixture-personal").put("client_secret","invented-personal-secret").put("experimental",true).toString());
        secrets.put("energy-update-fixture/oauth_tokens",new JSONObject().put("access_token","invented-netatmo-access").put("refresh_token","invented-netatmo-refresh").put("expires_at",System.currentTimeMillis()/1000+10800).toString());
        byte[] master=new byte[32],nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        for(int i=0;i<32;i++)master[i]=(byte)Integer.parseInt(keys.getString("master").substring(i*2,i*2+2),16);
        Cipher cipher=Cipher.getInstance("ChaCha20/Poly1305/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(master,"ChaCha20"),new IvParameterSpec(nonce));
        cipher.updateAAD("moli-os secrets v1".getBytes(StandardCharsets.UTF_8));
        try(OutputStream out=new FileOutputStream(new File(dir,"secrets.enc"))) {
            out.write("MOLISEC1".getBytes(StandardCharsets.US_ASCII));out.write(nonce);out.write(cipher.doFinal(secrets.toString().getBytes(StandardCharsets.UTF_8)));
        }finally{java.util.Arrays.fill(master,(byte)0);}
    }
    private static void seed(Context c,File dir,JSONObject keys)throws Exception {
        assertTrue(dir.isDirectory() || dir.mkdirs());syntheticVault(dir,keys);
        // No configured drivers at this point: native vault/config initialization, no network.
        NativeServer.start(dir.getAbsolutePath(),keys.getString("master"),ProtectedKey.randomHex(),keys.getString("pin"));
        NativeServer.stop();
        File toml=new File(dir,"moli.toml");
        ProfileStore.atomic(toml,ProfileStore.read(toml,524288)+"\n[[driver]]\nid='energy-update-fixture'\nkind='netatmo'\nenabled=false\n[driver.options]\nhome_id='home-update-fixture'\nclient_id='fixture-personal'\npoll_seconds=240\nmax_age_seconds=600\n");
        JSONArray plugs=new JSONArray();
        for(int i=1;i<=4;i++) {String id=String.format(java.util.Locale.ROOT,"%032x",i);plugs.put(new JSONObject().put("id",id).put("uuid",id).put("name","Invented plug "+i).put("host","192.168.1."+(20+i)).put("port",80).put("enabled",false));}
        JSONObject config=new JSONObject().put("meross",plugs).put("gateway",new JSONObject().put("location","http://192.168.1.1:80/root.xml"));
        write(new File(dir,"android-equipment.json"),config);
        write(new File(dir,"android-integrations.json"),new JSONObject().put("demo",true));
        File recordings=new File(dir,"recordings");assertTrue(recordings.isDirectory() || recordings.mkdir());
        Files.write(new File(recordings,"synthetic.txt").toPath(),new byte[]{17,42});
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(new File(dir,"update-check.db"),null)) {
            db.execSQL("CREATE TABLE sample (value INTEGER)");db.execSQL("INSERT INTO sample VALUES (42)");
        }
        write(new File(dir,PROOF),new JSONObject().put("source_uid",android.os.Process.myUid()).put("credentials",credentialsProof(keys)).put("inventory",inventory(dir)));
    }
    @Test public void exportPreviewWithoutPin()throws Exception {
        Context c=context();assertEquals("io.moli.android.app.preview.automatic.v0130",c.getPackageName());assertEquals(16,info(c).getLongVersionCode());assertFalse(ProtectedKey.exists(c));
        ProtectedKey.createPreview(c);JSONObject keys=ProtectedKey.open(c);assertEquals(2,keys.getInt("format"));
        seed(c,ProtectedKey.dataDirectory(c,keys),keys);
        try(OutputStream out=new FileOutputStream(new File(c.getFilesDir(),"beta-transfer.molibak"))) {BackupStorage.exportTo(c,PASSWORD,out);}
    }
    @Test public void exportLegacyNumericPinFixture()throws Exception {
        Context c=context();File dir=new File(c.getCacheDir(),"legacy-numeric-fixture");assertFalse(dir.exists());
        JSONObject keys=new JSONObject().put("master",ProtectedKey.randomHex()).put("pin","4826");seed(c,dir,keys);
        try(OutputStream out=new FileOutputStream(new File(c.getFilesDir(),"legacy-transfer.molibak"))) {BackupArchive.exportTo(dir,keys,PASSWORD,out);}
    }
    private static void checkPreserved(Context c)throws Exception {
        JSONObject keys=ProtectedKey.open(c);File dir=ProtectedKey.dataDirectory(c,keys);JSONObject proof=read(new File(dir,PROOF));
        sameCredentials(proof.getJSONObject("credentials"),keys);same(proof.getJSONObject("inventory"),inventory(dir));
        assertFalse(new File(dir,"master.key").exists());assertArrayEquals(new byte[]{17,42},Files.readAllBytes(new File(dir,"recordings/synthetic.txt").toPath()));
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(dir,"update-check.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);android.database.Cursor row=db.rawQuery("SELECT value FROM sample",null)) {assertTrue(row.moveToFirst());assertEquals(42,row.getInt(0));}
        JSONObject status=MerossAccountStore.request(c,new JSONObject().put("action","status"));
        assertEquals("connected",status.getString("state"));assertEquals(4,status.getJSONArray("items").length());assertFalse(status.toString().contains("invented-session-token"));assertFalse(status.toString().contains(LOCAL_KEY));
        JSONObject netatmo=NetatmoOAuthStore.request(c,new JSONObject().put("action","status").put("instance","energy-update-fixture"));
        assertFalse(netatmo.getBoolean("setup_required"));assertTrue(netatmo.getBoolean("credential_present"));assertEquals("home-update-fixture",netatmo.getString("selected_home"));
        assertEquals("idle",netatmo.getString("state"));assertFalse(netatmo.toString().contains("invented-personal-secret"));assertFalse(netatmo.toString().contains("invented-netatmo"));
        JSONObject equipment=EquipmentStore.read(c);assertEquals(4,equipment.getJSONArray("meross").length());assertTrue(equipment.has("gateway"));assertTrue(ProfileStore.state(c).getBoolean("demo"));
        JSONArray required=new JSONArray();for(int i=1;i<=4;i++)required.put(new JSONObject().put("id",String.format(java.util.Locale.ROOT,"%032x",i)).put("key","").put("required",true));
        ProfileStore.nativeResult(new JSONObject().put("data",dir.getAbsolutePath()).put("master",keys.getString("master")).put("keys",required).toString(),4);
        same(proof.getJSONObject("inventory"),inventory(dir)); // Read-only JNI did not rewrite the vault.
    }
    @Test public void importIntoFreshBetaA()throws Exception {
        Context c=context();assertEquals("io.moli.android.app.beta",c.getPackageName());assertEquals(17,info(c).getLongVersionCode());assertFalse(ProtectedKey.exists(c));
        java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);assertFalse(store.containsAlias("moli.master.wrap.v1"));
        // Opening the new home must leave restoration available before first Search/My devices.
        try(ActivityScenario<MainActivity> home=ActivityScenario.launch(MainActivity.class)){assertFalse(ProtectedKey.exists(c));}
        try(InputStream in=new FileInputStream(new File(c.getFilesDir(),"beta-transfer.molibak"))) {BackupStorage.restoreFresh(c,in,PASSWORD);}
        JSONObject keys=ProtectedKey.open(c);File dir=ProtectedKey.dataDirectory(c,keys);assertNotEquals(read(new File(dir,PROOF)).getInt("source_uid"),android.os.Process.myUid());
        checkPreserved(c);
        assertTrue(c.getSharedPreferences("host",Context.MODE_PRIVATE).edit().putBoolean("wanted",false).putString("update-sentinel","invented preference").commit());
        write(new File(c.getFilesDir(),"beta-before.json"),new JSONObject().put("uid",android.os.Process.myUid()).put("certificate",certificate(c)).put("wrapper",wrapper(c)).put("directory",dir.getName()).put("inventory",inventory(dir)));
        assertFalse(c.getSharedPreferences("host",Context.MODE_PRIVATE).getBoolean("wanted",true));
    }
    @Test public void verifyBetaBAfterReplacement()throws Exception {
        Context c=context();assertEquals("io.moli.android.app.beta",c.getPackageName());assertEquals(18,info(c).getLongVersionCode());JSONObject before=read(new File(c.getFilesDir(),"beta-before.json"));
        assertEquals(before.getInt("uid"),android.os.Process.myUid());assertEquals(before.getString("certificate"),certificate(c));assertEquals(before.getString("wrapper"),wrapper(c));
        assertEquals(before.getString("directory"),ProfileStore.data(c).getName());same(before.getJSONObject("inventory"),inventory(ProfileStore.data(c)));checkPreserved(c);
        assertEquals("invented preference",c.getSharedPreferences("host",Context.MODE_PRIVATE).getString("update-sentinel",""));
        assertFalse(c.getSharedPreferences("host",Context.MODE_PRIVATE).getBoolean("wanted",true));
        try(ActivityScenario<MainActivity> home=ActivityScenario.launch(MainActivity.class)) {assertEquals(EngineHost.State.STOPPED,EngineHost.state());}
        assertEquals(before.getString("wrapper"),wrapper(c)); // No regeneration on reopening.
        ConcordanceFixture.verify(c); // Exercise the new stopped-engine persistence after install -r.
        checkPreserved(c); // The upgraded app's original vault, UID data and approvals remain untouched.
    }
    @Test public void restoreLegacyPinInIndependentUid()throws Exception {
        Context c=context();assertEquals("io.moli.android.app.preview.receiver",c.getPackageName());assertFalse(ProtectedKey.exists(c));
        try(InputStream in=new FileInputStream(new File(c.getFilesDir(),"legacy-transfer.molibak"))) {BackupStorage.restoreFresh(c,in,PASSWORD);}
        assertEquals(1,ProtectedKey.open(c).optInt("format",1));assertEquals("4826",ProtectedKey.open(c).getString("pin"));checkPreserved(c);
    }
}
