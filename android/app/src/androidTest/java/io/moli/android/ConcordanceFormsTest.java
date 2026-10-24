package io.moli.android;
import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Fresh disposable preview, no equipment driver or cloud request. */
@RunWith(AndroidJUnit4.class)
public final class ConcordanceFormsTest {
    interface Check { boolean ready() throws Exception; }
    static void until(Check c) throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+30000;
        while(!c.ready()) {if(android.os.SystemClock.elapsedRealtime()>end)fail("Expected configuration state");Thread.sleep(50);}
    }
    @Test public void formsEditEffectiveValuesAndRejectStaleOrUnrepresentableSettings() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.moli.android.app.preview",c.getPackageName());
        assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"));
        assertEquals(EngineHost.State.STOPPED,EngineHost.state());assertFalse(ProtectedKey.exists(c));
        ProtectedKey.createPreview(c);File dir=ProfileStore.data(c);assertTrue(dir.mkdir());
        ProfileStore.configureWeather(c,"Fixture place",48,2);
        ProfileStore.atomic(new File(dir,"home.json"),"{\"rooms\":[{\"name\":\"Fixture room\",\"protected\":true}]}");
        JSONArray plugs=new JSONArray(), updates=new JSONArray();
        for(int i=1;i<=4;i++) {String id=String.format(java.util.Locale.ROOT,"%032x",i);
            plugs.put(new JSONObject().put("id",id).put("uuid",String.format(java.util.Locale.ROOT,"%032x",100+i)).put("name","Fixture socket "+i).put("host","192.168.1."+(40+i)).put("port",80).put("enabled",true));
            updates.put(new JSONObject().put("id",id).put("key","b".repeat(32)).put("required",true));
        }
        EquipmentStore.save(c,new JSONObject().put("meross",plugs).put("printer",new JSONObject().put("host","192.168.1.20").put("port",631).put("path","/ipp/print")),updates);
        ProfileStore.prepare(c).close();
        byte[] vault=Files.readAllBytes(new File(dir,"secrets.enc").toPath());
        String text=ProfileStore.read(new File(dir,"moli.toml"),262144);assertTrue(text.contains("lat = \"48\""));
        ProfileStore.atomic(new File(dir,"moli.toml"),text.replace("Fixture socket 1","Moli socket").replace("lat = \"48\"","lat = \"42.5\""));
        ProfileStore.atomic(new File(dir,"home.json"),ProfileStore.home(c).put("android",ProfileStore.home(c).getJSONObject("android").put("location","Moli place")).toString());
        try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(EquipmentSettingsActivity.class)) {
            until(()->{AtomicBoolean ready=new AtomicBoolean();ui.onActivity(a->ready.set(a.save.isEnabled()));return ready.get();});
            ui.onActivity(a->{assertEquals("Moli socket",a.meross.entries.get(0).name.getText().toString());a.meross.entries.get(0).name.setText("Android edit");a.printer.setChecked(false);a.save.performClick();});
            until(()->{AtomicBoolean done=new AtomicBoolean();ui.onActivity(a->done.set(a.getString(R.string.equipment_saved).contentEquals(a.message.getText())));return done.get();});
        }
        ProfileStore.prepare(c).close();
        assertFalse(EquipmentStore.read(c).getJSONObject("printer").getBoolean("enabled"));
        assertEquals("Android edit",EquipmentStore.read(c).getJSONArray("meross").getJSONObject(0).getString("name"));
        try(ActivityScenario<HomeSettingsActivity> ui=ActivityScenario.launch(HomeSettingsActivity.class)) {
            until(()->{AtomicBoolean ready=new AtomicBoolean();ui.onActivity(a->ready.set(a.save.isEnabled()));return ready.get();});
            ui.onActivity(a->{assertEquals("Moli place",a.label.getText().toString());assertEquals("42.5",a.latitude.getText().toString());a.latitude.setText("44.25");a.save.performClick();});
            until(()->{AtomicBoolean done=new AtomicBoolean();ui.onActivity(a->done.set(a.getString(R.string.home_saved).contentEquals(a.message.getText())));return done.get();});
            assertEquals(44.25,ProfileStore.state(c).getJSONObject("weather").getJSONObject("location").getDouble("latitude"),0.00001);
            assertTrue(ProfileStore.home(c).getJSONArray("rooms").getJSONObject(0).getBoolean("protected"));
            String before=ProfileStore.state(c).toString();
            ProfileStore.atomic(new File(dir,"home.json"),ProfileStore.home(c).put("opaque","concurrent edit").toString());
            ui.onActivity(a->{a.latitude.setText("45");a.save.performClick();});
            until(()->{AtomicBoolean blocked=new AtomicBoolean();ui.onActivity(a->blocked.set(a.getString(R.string.configuration_blocked).contentEquals(a.message.getText())));return blocked.get();});
            assertEquals(before,ProfileStore.state(c).toString());
        }
        ProfileStore.prepare(c).close();
        String weather=dir.getAbsolutePath()+"/android-profiles/"+ProfileStore.state(c).getJSONObject("weather").getString("sha256")+".toml";
        String custom=ProfileStore.read(new File(dir,"moli.toml"),262144).replace(weather,"/manual/unsupported-weather.toml");
        ProfileStore.atomic(new File(dir,"moli.toml"),custom);
        try(ActivityScenario<HomeSettingsActivity> ui=ActivityScenario.launch(HomeSettingsActivity.class)) {
            until(()->{AtomicBoolean blocked=new AtomicBoolean();ui.onActivity(a->blocked.set(!a.save.isEnabled() && a.getString(R.string.configuration_blocked).contentEquals(a.message.getText())));return blocked.get();});
        }
        assertEquals(custom,ProfileStore.read(new File(dir,"moli.toml"),262144));
        assertArrayEquals(vault,Files.readAllBytes(new File(dir,"secrets.enc").toPath()));
        assertEquals(EngineHost.State.STOPPED,EngineHost.state());
    }
}
