package io.moli.android;

import android.content.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;

/** Private disposable data generation; provisioning only, never a provider call. */
final class NetatmoPersonalFixture {
    static void verify(Context app)throws Exception {
        assertTrue(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"));
        File root=new File(app.getCacheDir(),"netatmo-personal-"+java.util.UUID.randomUUID()),files=new File(root,"files"),noBackup=new File(root,"no-backup");
        assertTrue(files.mkdirs());assertTrue(noBackup.mkdirs());
        Context fixture=new ContextWrapper(app){@Override public File getFilesDir(){return files;}@Override public File getNoBackupFilesDir(){return noBackup;}};
        try{
            ProtectedKey.createPreview(fixture);File dir=ProfileStore.data(fixture);assertTrue(dir.mkdir());
            String config="# synthetic exact configuration\n[[driver]]\nid='energy-test'\nkind='netatmo'\nenabled=false\n[driver.options]\nhome_id='home-test'\n";
            ProfileStore.atomic(new File(dir,"moli.toml"),config);
            ProfileStore.atomic(new File(dir,"home.json"),"{\"favorites\":[\"synthetic-stable\"]}");
            byte[] wrapped=Files.readAllBytes(new File(noBackup,"moli-key.wrap").toPath()),home=Files.readAllBytes(new File(dir,"home.json").toPath());
            JSONObject request=new JSONObject().put("action","provision").put("instance","energy-test").put("consent",true)
                .put("application",new JSONObject().put("client_id","personal-test").put("client_secret","synthetic-private-application").put("experimental",true));
            JSONObject result=NetatmoOAuthStore.request(fixture,request);
            assertFalse(result.getBoolean("setup_required"));assertTrue(result.getBoolean("experimental"));assertFalse(result.getBoolean("credential_present"));
            assertEquals(NetatmoOAuthUi.PERSONAL_REDIRECT,result.getString("redirect_uri"));
            assertFalse(request.has("master"));assertFalse(request.has("application"));
            assertFalse(result.toString().contains("synthetic-private-application"));
            String vault=new String(Files.readAllBytes(new File(dir,"secrets.enc").toPath()),java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(vault.contains("synthetic-private-application"));
            JSONObject reopened=NetatmoOAuthStore.request(fixture,new JSONObject().put("action","status").put("instance","energy-test"));
            assertEquals("personal-test",reopened.getString("client_id"));
            JSONObject begin=NetatmoOAuthStore.request(fixture,new JSONObject().put("action","begin").put("instance","energy-test").put("consent",true));
            assertTrue(NetatmoOAuthUi.validAuthorization(begin.getString("authorization_url"),"personal-test",NetatmoOAuthUi.PERSONAL_REDIRECT));
            try{NetatmoOAuthStore.request(fixture,new JSONObject().put("action","complete").put("instance","energy-test").put("callback","http://localhost.evil/moli-netatmo?code=synthetic"));fail("forged redirect must be rejected before HTTP");}
            catch(java.util.concurrent.ExecutionException expected){
                assertTrue(expected.getCause() instanceof ProfileStore.Rejected);
                assertEquals("oauth_state",expected.getCause().getMessage());
            }
            NetatmoOAuthStore.request(fixture,new JSONObject().put("action","cancel").put("instance","energy-test"));
            assertEquals(config,ProfileStore.read(new File(dir,"moli.toml"),524288));assertArrayEquals(home,Files.readAllBytes(new File(dir,"home.json").toPath()));
            assertArrayEquals(wrapped,Files.readAllBytes(new File(noBackup,"moli-key.wrap").toPath()));
            assertFalse(new File(dir,ConfigurationTransaction.JOURNAL).exists());
        }finally{
            try(java.util.stream.Stream<Path> stream=Files.walk(root.toPath())){for(Path path:(Iterable<Path>)stream.sorted(java.util.Comparator.reverseOrder())::iterator)Files.delete(path);}
        }
    }
    private NetatmoPersonalFixture(){}
}
