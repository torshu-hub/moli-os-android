package io.moli.android;
import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Reproduce the code2 mismatch before upgrading: real points, no dashboard reference. */
@RunWith(AndroidJUnit4.class)
public final class Code2WeatherSeedTest {
    @Test public void measuredPointsWithoutDashboardBinding()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(2,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        assertFalse(ProtectedKey.exists(app));ProtectedKey.create(app,"482913");
        app.startForegroundService(new Intent(app,MoliService.class));Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP));Beta2JourneyTest.waitState(EngineHost.State.STOPPED);
        String old=new ConfigStore(app).read();
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);
            store.save(old,"weather-fixture",new JSONObject().put("lat","0").put("lon","0"));
            store.disk.write("home.json","{\"title\":\"Fixture home\",\"outdoor\":{\"air\":\"air-fixture\"},\"preserve\":{\"nested\":[1,true]}}\n");return null;
        }).get();
        app.startForegroundService(new Intent(app,MoliService.class));Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        String id=null;long end=System.currentTimeMillis()+60000;
        while(System.currentTimeMillis()<end && id==null) {
            JSONArray devices=new JSONObject(Beta2JourneyTest.get("/api/devices",Beta2JourneyTest.cookie()).text).getJSONArray("devices");
            for(int i=0;i<devices.length();i++) {
                JSONObject d=devices.getJSONObject(i),sample=d.getJSONObject("state").optJSONObject("temperature");
                if("weather-fixture".equals(d.optString("instance")) && "Open-Meteo".equals(d.optString("manufacturer")) && d.optBoolean("online") && sample!=null && sample.opt("value") instanceof Number && sample.optLong("ts")>0)id=d.getString("id");
            }
            if(id==null)Thread.sleep(250);
        }
        assertNotNull("No real official Open-Meteo points on code2",id);
        JSONObject home=new JSONObject(Beta2JourneyTest.get("/api/home",Beta2JourneyTest.cookie()).text);
        assertFalse("Code2 already has a weather association",home.getJSONObject("outdoor").has("weather"));
        System.out.println("Observed on code2: real official weather samples, but outdoor.weather absent.");
        assertTrue(app.getSharedPreferences("code3-update-proof",Context.MODE_PRIVATE).edit()
            .putString("device",id).putString("config",Beta2JourneyTest.digest(new ConfigStore(app).read()))
            .putString("key",Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master"))).commit());
        app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP));Beta2JourneyTest.waitState(EngineHost.State.STOPPED);
    }
}
