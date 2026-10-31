package io.moli.android;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Only isolated synthetic code8 data; no discovery classes needed on the old APK. */
@RunWith(AndroidJUnit4.class)
public final class Code9UpgradeSeedTest {
    static final String PREFS="code9-update-proof";
    static JSONObject hashes(File directory)throws Exception {
        JSONObject hashes=new JSONObject();
        try(java.util.stream.Stream<Path> paths=Files.walk(directory.toPath())){
            for(Path path:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator){
                String hash=android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)),android.util.Base64.NO_WRAP);
                hashes.put(directory.toPath().relativize(path).toString(),hash);
            }
        }
        return hashes;
    }
    static void sameData(Context app)throws Exception {
        ConfigStore store=new ConfigStore(app);SharedPreferences proof=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        JSONObject before=new JSONObject(proof.getString("files","{}")),after=hashes(store.directory);
        assertEquals(before.length(),after.length());
        for(java.util.Iterator<String> keys=before.keys();keys.hasNext();){String name=keys.next();assertEquals(name,before.getString(name),after.getString(name));}
        assertEquals(proof.getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
    }
    @Test public void seedRealCode9()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.moli.android.app.beta2.test",app.getPackageName());
        assertEquals(10,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());assertFalse(ProtectedKey.exists(app));
        ProtectedKey.create(app,"482913");
        app.startForegroundService(new Intent(app,MoliService.class));Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP));Beta2JourneyTest.waitState(EngineHost.State.STOPPED);
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);JSONObject vars=new JSONObject().put("lat","0").put("lon","0");
            for(String profile:new String[]{"open-meteo","open-meteo-air","tempo"}){
                store.save(store.read(),profile,profile+"-fixture",profile.equals("tempo")?new JSONObject():vars);store.setEnabled(store.read(),profile+"-fixture",false);
            }
            store.save(store.read(),"ipp","printer-fixture",new JSONObject(),new JSONObject().put("host","192.0.2.1").put("port","631"));store.setEnabled(store.read(),"printer-fixture",false);
            for(int n=1;n<=4;n++) {
                String id="existing-plug-"+n;
                store.save(store.read(),"meross-mss310",id,new JSONObject().put("expected_uuid",String.format("%032x",n)),
                    new JSONObject().put("host","192.0.2."+n).put("port","80").put("name","Configured plug "+n));
                store.setEnabled(store.read(),id,false);
            }
            String before=store.read();store.transaction.commitExpected(before,before+"\n# code8 fixture comment kept verbatim\n");
            store.disk.write("home.json","{\"outdoor\":{\"weather\":\"owner-weather-choice\",\"air\":\"owner-air-choice\"},\"preserve\":{\"nested\":[1,true]}}\n");
            store.disk.write("automations.json","{\"automations\":[]}\n");return null;
        }).get(30,TimeUnit.SECONDS);
        ConfigStore store=new ConfigStore(app);
        assertTrue(app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("files",hashes(store.directory).toString())
            .putString("key",Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master"))).commit());
    }
}
