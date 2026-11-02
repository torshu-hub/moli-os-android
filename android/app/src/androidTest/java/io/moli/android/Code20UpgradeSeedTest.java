package io.moli.android;
import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Official API on the old code19 APK; synthetic human session, no physical drivers. */
@RunWith(AndroidJUnit4.class)
public final class Code20UpgradeSeedTest {
    private String cookie;
    private JSONObject api(String method,String path,JSONObject body)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(EngineHost.origin()+path).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestMethod(method);c.setRequestProperty("Cookie",cookie);
        try{
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            assertEquals(200,c.getResponseCode());
            String session=c.getHeaderField("Set-Cookie");if(session!=null&&session.startsWith("moli_session="))cookie+="; "+session.split(";",2)[0];
            try(InputStream in=c.getInputStream()){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}
        }finally{c.disconnect();}
    }
    @Test public void seedCode19WithDraftInactiveAndActiveRules()throws Exception {
        new Code15UpgradeSeedTest().seedRealCode14();
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();assertEquals(Integer.parseInt(InstrumentationRegistry.getArguments().getString("expectedSeed","19")),app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        app.startForegroundService(new Intent(app,MoliService.class));Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        try{
            cookie="moli_android="+EngineHost.token();
            JSONObject graph=DemandAutomation.graph("synthetic-energy:room/declared_demand","synthetic-plug:outlet/on");
            api("POST","/api/automations",new JSONObject().put("name","Draft link").put("graph",graph));
            // Existing fixture PIN through the official session route; no parallel auth.
            api("POST","/api/session",new JSONObject().put("pin",ProtectedKey.open(app).getString("pin")));
            JSONObject manual=new JSONObject().put("nodes",new JSONArray().put(new JSONObject().put("id","manual").put("type","manual"))).put("edges",new JSONArray());
            for(String name:new String[]{"Inactive fixture","Active fixture"}){
                JSONObject v=api("POST","/api/automations",new JSONObject().put("name",name).put("graph",manual));String id=v.getJSONObject("automation").getString("id");
                JSONObject approved=api("POST","/api/automations/"+id+"/approve",new JSONObject().put("fingerprint",v.getString("fingerprint")));assertTrue(approved.getBoolean("live"));
                if(name.startsWith("Inactive"))api("POST","/api/automations/"+id+"/enabled",new JSONObject().put("on",false).put("fingerprint",approved.getString("fingerprint")));
            }
        }finally{app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP));Beta2JourneyTest.waitState(EngineHost.State.STOPPED);}
        EngineHost.inspect(()->{ConfigStore store=new ConfigStore(app);assertTrue(app.getSharedPreferences(Code12UpgradeSeedTest.PREFS,Context.MODE_PRIVATE).edit().putString("files",Code12UpgradeSeedTest.hashes(store.directory).toString()).commit());return null;}).get(15,TimeUnit.SECONDS);
    }
}
