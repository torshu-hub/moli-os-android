package io.moli.android;

import android.content.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;

/** Synthetic stopped-engine prepare on a private test context. Never starts real drivers. */
final class ConcordanceFixture {
    static void verify(Context app) throws Exception {
        assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"));
        File root = new File(app.getCacheDir(), "concordance-" + java.util.UUID.randomUUID());
        File files = new File(root, "files"), noBackup = new File(root, "no-backup");
        assertTrue(files.mkdirs()); assertTrue(noBackup.mkdirs());
        Context fixture = new ContextWrapper(app) {
            @Override public File getFilesDir() { return files; }
            @Override public File getNoBackupFilesDir() { return noBackup; }
        };
        try {
            ProtectedKey.createPreview(fixture);
            File dir = ProfileStore.data(fixture); assertTrue(dir.mkdir());
            byte[] wrapped = Files.readAllBytes(new File(noBackup, "moli-key.wrap").toPath());
            ProfileStore.demo(fixture); ProfileStore.configureWeather(fixture, "Fixture location", 48, 2);
            JSONArray plugs = new JSONArray();
            for (int i = 1; i <= 4; i++) plugs.put(new JSONObject().put("id", String.format(java.util.Locale.ROOT,"%032x",i))
                    .put("uuid", String.format(java.util.Locale.ROOT,"%032x",100+i)).put("name", "Fixture " + i)
                    .put("host", "192.168.1." + (40+i)).put("port",80).put("enabled",true));
            JSONObject equipment = new JSONObject().put("meross",plugs)
                    .put("printer",new JSONObject().put("host","192.168.1.20").put("port",631).put("path","/ipp/print"))
                    .put("gateway",new JSONObject().put("location","http://192.168.1.1:80/root.xml"));
            EquipmentStore.save(fixture, equipment);
            ProfileStore.atomic(new File(dir,"moli.toml"), "[server]\nworker_threads=3\n[[driver]]\nid='external'\nkind='helpers'\n");
            ProfileStore.atomic(new File(dir,"home.json"), "{\"rooms\":[{\"name\":\"Fixture room\",\"protected\":true}],\"outdoor\":{\"weather\":\"external:weather\"}}");
            byte[] approval = Files.readAllBytes(new File(dir,"trusted-profiles.toml").toPath());
            byte[] selection = Files.readAllBytes(new File(dir,"android-integrations.json").toPath());
            byte[] preferences = Files.readAllBytes(new File(dir,"android-equipment.json").toPath());
            // Actual Java -> JNI validation -> fsync/rename transaction, without NativeServer.start.
            ProfileStore.prepare(fixture).close();
            // The first preparation may add the shipped tablet profile approval;
            // user-approved weather bytes must remain verbatim in the trust store.
            byte[] installedApproval = Files.readAllBytes(new File(dir,"trusted-profiles.toml").toPath());
            assertTrue(new String(installedApproval,java.nio.charset.StandardCharsets.UTF_8).contains(new String(approval,java.nio.charset.StandardCharsets.UTF_8)));
            approval = installedApproval;
            assertFalse(new File(dir,ConfigurationTransaction.JOURNAL).exists());
            byte[] legacy = Files.readAllBytes(new File(dir,"android-concordance.legacy.json").toPath());
            assertTrue(ProfileStore.read(new File(dir,"moli.toml"),262144).contains("external"));
            String updated = ProfileStore.read(new File(dir,"moli.toml"),262144).replace("Fixture 1", "Moli label");
            ProfileStore.atomic(new File(dir,"moli.toml"), updated);
            ProfileStore.prepare(fixture).close(); ProfileStore.prepare(fixture).close();
            assertTrue(ProfileStore.read(new File(dir,"moli.toml"),262144).contains("Moli label"));
            JSONObject home = ProfileStore.home(fixture);
            assertEquals("Moli label",home.getJSONObject("android").getJSONArray("meross").getJSONObject(0).getString("name"));
            assertTrue(home.getJSONArray("rooms").getJSONObject(0).getBoolean("protected"));
            assertEquals("external:weather",home.getJSONObject("outdoor").getString("weather"));
            assertArrayEquals(wrapped, Files.readAllBytes(new File(noBackup,"moli-key.wrap").toPath()));
            assertArrayEquals(approval, Files.readAllBytes(new File(dir,"trusted-profiles.toml").toPath()));
            assertEquals(new JSONObject(new String(selection,java.nio.charset.StandardCharsets.UTF_8)).getJSONObject("weather").getString("sha256"),ProfileStore.state(fixture).getJSONObject("weather").getString("sha256"));
            assertEquals(new JSONObject(new String(preferences,java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("meross").getJSONObject(0).getString("uuid"),EquipmentStore.read(fixture).getJSONArray("meross").getJSONObject(0).getString("uuid"));
            assertArrayEquals(legacy, Files.readAllBytes(new File(dir,"android-concordance.legacy.json").toPath()));
            JSONObject binding = new JSONObject(ProfileStore.read(new File(dir,"android-concordance.json"),524288));
            String id = "android-meross-" + String.format(java.util.Locale.ROOT,"%032x",1);
            assertEquals(plugs.getJSONObject(0).getString("uuid"), binding.getJSONObject("drivers").getJSONObject(id).getJSONObject("options").getJSONObject("vars").getString("expected_uuid"));
            equipment=EquipmentStore.read(fixture);
            activation(fixture, equipment, dir, binding, id);
            convergence(fixture,dir,root,wrapped);
            equipment=EquipmentStore.read(fixture);
            ProfileStore.atomic(new File(dir,"moli.toml"),ProfileStore.read(new File(dir,"moli.toml"),262144).replace("Moli label","Concurrent Moli label"));
            byte[] before = Files.readAllBytes(new File(dir,"moli.toml").toPath());
            byte[] oldHome = Files.readAllBytes(new File(dir,"home.json").toPath());
            equipment.getJSONArray("meross").getJSONObject(0).put("name", "Android conflict"); EquipmentStore.save(fixture,equipment);
            try { ProfileStore.prepare(fixture).close(); fail("conflict must preserve files"); }
            catch(ProfileStore.Rejected expected) { assertEquals("configuration_conflict", expected.getMessage()); }
            assertArrayEquals(before, Files.readAllBytes(new File(dir,"moli.toml").toPath()));
            assertArrayEquals(oldHome, Files.readAllBytes(new File(dir,"home.json").toPath()));
            Files.delete(new File(dir,"home.json").toPath());
            try { ProfileStore.prepare(fixture).close(); fail("missing established home must not be recreated"); }
            catch(ProfileStore.Rejected expected) { assertEquals("configuration_conflict", expected.getMessage()); }
            assertFalse(new File(dir,"home.json").exists());
            assertArrayEquals(before, Files.readAllBytes(new File(dir,"moli.toml").toPath()));
        } finally {
            try (java.util.stream.Stream<Path> stream = Files.walk(root.toPath())) {
                for (Path path : (Iterable<Path>)stream.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
    private static void activation(Context fixture, JSONObject equipment, File dir, JSONObject binding, String id) throws Exception {
        JSONObject keys = ProtectedKey.open(fixture);
        JSONArray updates = new JSONArray();
        for (int i=0;i<4;i++) updates.put(new JSONObject().put("id",equipment.getJSONArray("meross").getJSONObject(i).getString("id"))
                .put("key","b".repeat(32)).put("required",true)); // Synthetic fixture values only.
        JSONObject vaultRequest = new JSONObject().put("data",dir.getAbsolutePath()).put("master",keys.getString("master")).put("keys",updates);
        ProfileStore.nativeResult(vaultRequest.toString(),4);
        byte[] vault = Files.readAllBytes(new File(dir,"secrets.enc").toPath());
        File database = new File(dir,"disable-continuity.db");
        try (android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(database,null)) {
            db.execSQL("CREATE TABLE sample(value INTEGER)"); db.execSQL("INSERT INTO sample VALUES(42)");
        }
        byte[] history = Files.readAllBytes(database.toPath());
        JSONObject plug = equipment.getJSONArray("meross").getJSONObject(0);
        plug.put("enabled",false); EquipmentStore.save(fixture,equipment);
        ProfileStore.prepare(fixture).close(); ProfileStore.prepare(fixture).close();
        JSONObject disabled = new JSONObject(ProfileStore.read(new File(dir,"android-concordance.json"),524288)).getJSONObject("drivers").getJSONObject(id);
        assertFalse(disabled.getBoolean("enabled"));
        assertEquals(binding.getJSONObject("drivers").getJSONObject(id).getJSONObject("options").toString(), disabled.getJSONObject("options").toString());
        assertEquals(4,EquipmentStore.read(fixture).getJSONArray("meross").length());
        assertFalse(ProfileStore.home(fixture).getJSONObject("android").getJSONArray("meross").getJSONObject(0).getBoolean("enabled"));
        assertTrue(ProfileStore.read(new File(dir,"moli.toml"),262144).contains("Moli label"));
        disabledInventory(fixture,equipment,new File(dir.getParentFile(),"disabled-engine"));
        plug.put("enabled",true); EquipmentStore.save(fixture,equipment);
        ProfileStore.prepare(fixture).close();
        JSONObject enabled = new JSONObject(ProfileStore.read(new File(dir,"android-concordance.json"),524288)).getJSONObject("drivers").getJSONObject(id);
        assertTrue(enabled.getBoolean("enabled")); assertEquals(disabled.getJSONObject("options").toString(),enabled.getJSONObject("options").toString());
        assertTrue(ProfileStore.home(fixture).getJSONObject("android").getJSONArray("meross").getJSONObject(0).getBoolean("enabled"));
        for (int i=0;i<updates.length();i++) updates.getJSONObject(i).put("key","");
        ProfileStore.nativeResult(vaultRequest.toString(),4); // Required keys still exist, no rewrite.
        assertArrayEquals(vault,Files.readAllBytes(new File(dir,"secrets.enc").toPath()));
        assertArrayEquals(history,Files.readAllBytes(database.toPath()));
        try (android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(database.getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
             android.database.Cursor row=db.rawQuery("SELECT value FROM sample",null)) { assertTrue(row.moveToFirst()); assertEquals(42,row.getInt(0)); }
    }
    private static void disabledInventory(Context fixture, JSONObject equipment, File engineDir) throws Exception {
        assertTrue(engineDir.mkdir());
        JSONArray plugs = new JSONArray(equipment.getJSONArray("meross").toString());
        for(int i=0;i<plugs.length();i++) plugs.getJSONObject(i).put("enabled",false);
        JSONObject input = new JSONObject().put("config","").put("data",engineDir.getAbsolutePath()).put("equipment",new JSONObject().put("meross",plugs));
        ProfileStore.atomic(new File(engineDir,"moli.toml"), ProfileStore.nativeResult(input.toString(),1).getString("config"));
        JSONObject keys=ProtectedKey.open(fixture); String token=ProtectedKey.randomHex();
        int port=NativeServer.start(engineDir.getAbsolutePath(),keys.getString("master"),token,keys.getString("pin"));
        try {
            java.net.HttpURLConnection request=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+port+"/api/system").openConnection();
            request.setConnectTimeout(5000); request.setReadTimeout(5000); request.setRequestProperty("Cookie","moli_android="+token);
            JSONObject system;
            try { assertEquals(200,request.getResponseCode()); system=new JSONObject(ProfileStore.bounded(request.getInputStream(),524288)); }
            finally { request.disconnect(); }
            JSONArray integrations=system.getJSONArray("integrations"); JSONObject meross=null;
            for(int i=0;i<integrations.length();i++) if("meross-mss310".equals(integrations.getJSONObject(i).optString("id"))) meross=integrations.getJSONObject(i);
            assertNotNull(meross); JSONArray instances=meross.getJSONArray("instances"); assertEquals(4,instances.length());
            for(int i=0;i<instances.length();i++) { assertFalse(instances.getJSONObject(i).getBoolean("enabled")); assertTrue(instances.getJSONObject(i).isNull("status")); }
            JSONArray runtime=system.getJSONArray("drivers");
            for(int i=0;i<runtime.length();i++) assertFalse(runtime.getJSONObject(i).getString("instance").startsWith("android-meross-"));
        } finally { NativeServer.stop(); }
    }
    private static void convergence(Context fixture, File dir, File root, byte[] wrapped) throws Exception {
        // Removal means inactive, with every old association still recoverable.
        JSONObject oldEquipment=EquipmentStore.read(fixture);
        EquipmentStore.save(fixture,new JSONObject());
        for(String key:new String[]{"demo","weather"})ProfileStore.remove(fixture,key);
        ProfileStore.prepare(fixture).close();
        JSONObject disabled=EquipmentStore.read(fixture), state=ProfileStore.state(fixture);
        assertFalse(disabled.getJSONObject("printer").getBoolean("enabled"));
        assertFalse(disabled.getJSONObject("gateway").getBoolean("enabled"));
        assertEquals(4,disabled.getJSONArray("meross").length());
        assertFalse(disabled.getJSONArray("meross").getJSONObject(0).getBoolean("enabled"));
        assertFalse(state.has("weather")); assertTrue(state.getJSONObject("retained").has("weather"));
        String stamp=ProfileStore.stamp(fixture);
        ProfileStore.atomic(new File(dir,"home.json"),ProfileStore.home(fixture).put("opaque-marker","preserved").toString());
        try {ProfileStore.checkStamp(fixture,stamp);fail("Stale form must be rejected before save");}catch(ProfileStore.Rejected expected){assertEquals("configuration_conflict",expected.getMessage());}
        ProfileStore.weather(fixture); ProfileStore.demo(fixture);
        assertEquals(48,ProfileStore.state(fixture).getJSONObject("weather").getJSONObject("location").getDouble("latitude"),0.00001);
        EquipmentStore.save(fixture,oldEquipment); ProfileStore.prepare(fixture).close();
        assertTrue(EquipmentStore.read(fixture).getJSONObject("printer").getBoolean("enabled"));
        // Encrypted restore into a new generation changes all app-owned absolute paths.
        char[] password="synthetic concordance recovery words".toCharArray(); java.io.ByteArrayOutputStream archive=new java.io.ByteArrayOutputStream();
        ProfileStore.remove(fixture,"weather"); ProfileStore.prepare(fixture).close();
        BackupStorage.exportTo(fixture,password,archive);
        ProfileStore.weather(fixture); ProfileStore.prepare(fixture).close();
        File restoredFiles=new File(root,"restored-files"), restoredWrap=new File(root,"restored-wrap");assertTrue(restoredFiles.mkdir());assertTrue(restoredWrap.mkdir());
        Context restored=new ContextWrapper(fixture) {
            @Override public File getFilesDir(){return restoredFiles;}
            @Override public File getNoBackupFilesDir(){return restoredWrap;}
        };
        BackupStorage.restoreFresh(restored,new java.io.ByteArrayInputStream(archive.toByteArray()),password);
        File destination=ProfileStore.data(restored);
        byte[] vault=Files.readAllBytes(new File(destination,"secrets.enc").toPath());
        byte[] approvals=Files.readAllBytes(new File(destination,"trusted-profiles.toml").toPath());
        ProfileStore.prepare(restored).close(); ProfileStore.prepare(restored).close();
        assertFalse(ProfileStore.state(restored).has("weather"));
        ProfileStore.weather(restored); ProfileStore.prepare(restored).close();
        assertEquals("Fixture location",ProfileStore.state(restored).getJSONObject("weather").getJSONObject("location").getString("label"));
        assertEquals(48,ProfileStore.state(restored).getJSONObject("weather").getJSONObject("location").getDouble("latitude"),0.00001);
        String config=ProfileStore.read(new File(destination,"moli.toml"),262144);
        assertTrue(config.contains(destination.getAbsolutePath()+"/android-profiles/"));
        assertFalse(config.contains(dir.getAbsolutePath()+"/android-profiles/"));
        assertEquals(ProfileStore.state(fixture).getJSONObject("weather").getString("sha256"),ProfileStore.state(restored).getJSONObject("weather").getString("sha256"));
        assertEquals(EquipmentStore.read(fixture).getJSONArray("meross").getJSONObject(0).getString("uuid"),EquipmentStore.read(restored).getJSONArray("meross").getJSONObject(0).getString("uuid"));
        assertArrayEquals(vault,Files.readAllBytes(new File(destination,"secrets.enc").toPath()));
        assertArrayEquals(approvals,Files.readAllBytes(new File(destination,"trusted-profiles.toml").toPath()));
        assertEquals("preserved",ProfileStore.home(restored).getString("opaque-marker"));
        assertTrue("Portable key preserved",ProtectedKey.open(fixture).getString("master").equals(ProtectedKey.open(restored).getString("master")));
        assertArrayEquals(wrapped,Files.readAllBytes(new File(fixture.getNoBackupFilesDir(),"moli-key.wrap").toPath()));
    }
    private ConcordanceFixture() {}
}
