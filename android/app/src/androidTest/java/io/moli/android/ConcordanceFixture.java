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
            assertArrayEquals(selection, Files.readAllBytes(new File(dir,"android-integrations.json").toPath()));
            assertArrayEquals(preferences, Files.readAllBytes(new File(dir,"android-equipment.json").toPath()));
            assertArrayEquals(legacy, Files.readAllBytes(new File(dir,"android-concordance.legacy.json").toPath()));
            JSONObject binding = new JSONObject(ProfileStore.read(new File(dir,"android-concordance.json"),524288));
            String id = "android-meross-" + String.format(java.util.Locale.ROOT,"%032x",1);
            assertEquals(plugs.getJSONObject(0).getString("uuid"), binding.getJSONObject("drivers").getJSONObject(id).getJSONObject("options").getJSONObject("vars").getString("expected_uuid"));
            byte[] before = Files.readAllBytes(new File(dir,"moli.toml").toPath());
            byte[] oldHome = Files.readAllBytes(new File(dir,"home.json").toPath());
            equipment.getJSONArray("meross").getJSONObject(0).put("name", "Android conflict"); EquipmentStore.save(fixture,equipment);
            try { ProfileStore.prepare(fixture).close(); fail("conflict must preserve files"); }
            catch(ProfileStore.Rejected expected) { assertEquals("configuration_conflict", expected.getMessage()); }
            assertArrayEquals(before, Files.readAllBytes(new File(dir,"moli.toml").toPath()));
            assertArrayEquals(oldHome, Files.readAllBytes(new File(dir,"home.json").toPath()));
        } finally {
            try (java.util.stream.Stream<Path> stream = Files.walk(root.toPath())) {
                for (Path path : (Iterable<Path>)stream.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
    private ConcordanceFixture() {}
}
