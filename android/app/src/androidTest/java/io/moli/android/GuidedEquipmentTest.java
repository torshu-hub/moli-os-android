package io.moli.android;

import android.app.*;
import android.content.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Guided UI and synthetic account/candidates, followed by real Rust reads of a disposable MSS fixture. */
@RunWith(AndroidJUnit4.class)
public final class GuidedEquipmentTest {
    @Test public void restoredInventoryGuidedIdentificationLocalReadAndRedactedCorrelation()throws Exception {
        assertTrue(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();assertEquals(EngineHost.State.STOPPED,EngineHost.state());if(!ProtectedKey.exists(app))ProtectedKey.create(app,"4826");
        app.getSharedPreferences("meross-draft",Context.MODE_PRIVATE).edit().clear().commit();
        for(String kind:new String[]{"weather","demo","import"})ProfileStore.remove(app,kind);
        EngineHost.maintain(()->{EquipmentStore.save(app,new JSONObject());return null;}).get();
        TrialLog.end(app);TrialLog.begin(app);String own=MerossReadTest.address();
        try(MerossReadTest.Fixture server=new MerossReadTest.Fixture(1)){
            MerossAccountTest.Fixture cache=new MerossAccountTest.Fixture(app);cache.connected=true;int[] calls={0,0};
            MerossAccountStore.Backend backend=input->{
                switch(input.getString("action")){
                    case "status":return cache.status().put("inventory_wait_ms",0);
                    case "inventory":calls[0]++;return cache.status().put("inventory_wait_ms",60000);
                    case "identify":{
                        calls[1]++;assertEquals(8,input.getJSONArray("candidates").length());JSONArray rows=new JSONArray();
                        for(int i=1;i<=4;i++)rows.put(new JSONObject().put("host","192.168.1."+(19+i)).put("uuid",String.format(Locale.ROOT,"%032x",i)).put("result","matched"));
                        rows.put(new JSONObject().put("host","192.168.1.90").put("result","different"));rows.put(new JSONObject().put("host","192.168.1.91").put("result","unavailable"));rows.put(new JSONObject().put("host","192.168.1.92").put("result","key_rejected"));rows.put(new JSONObject().put("host","192.168.1.93").put("result","incompatible"));
                        return new JSONObject().put("state","identified").put("results",rows);
                    }
                    case "associate":{
                        assertEquals(MerossAccountTest.UUID,input.getString("uuid"));assertEquals("192.168.1.20",input.getString("host"));assertTrue(input.getBoolean("enabled"));
                        // The fake association maps a synthetic candidate to the in-emulator responder; no production override exists.
                        JSONObject row=new JSONObject().put("id",MerossAccountTest.UUID).put("uuid",MerossAccountTest.UUID).put("name","Guided fixture").put("host",own).put("port",server.port()).put("enabled",true);
                        EngineHost.maintain(()->{EquipmentStore.save(app,new JSONObject().put("meross",new JSONArray().put(row)),new JSONArray().put(new JSONObject().put("id",MerossAccountTest.UUID).put("key","a".repeat(32)).put("required",true)));return null;}).get();
                        return new JSONObject().put("state","associated").put("uuid",MerossAccountTest.UUID);
                    }
                    default:throw new AssertionError("Only cache, explicit inventory, identity and association allowed");
                }
            };
            try(ActivityScenario<MerossManualActivity> ui=ActivityScenario.launch(MerossManualActivity.class)){
                MerossAccountTest.until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(a.loaded&&!a.busy));return ready.get();});
                ui.onActivity(a->{a.backend=backend;a.loaded=false;});MerossAccountTest.message(ui,R.string.account_connected);
                ui.onActivity(a->{assertEquals(4,a.inventory.length());assertFalse(a.password.isShown());a.refresh.performClick();});MerossAccountTest.message(ui,R.string.account_connected);assertEquals(1,calls[0]);
                ui.onActivity(a->a.pick.performClick());
                MerossAccountTest.until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{LocalNetworkActivity p=MerossAccountTest.picker();ready.set(p!=null&&p.consentDialog!=null&&p.consentDialog.isShowing());});return ready.get();});
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                    LocalNetworkActivity p=MerossAccountTest.picker();p.consentDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                    p.begin(new DiscoverySearch(){public void start(Listener listener){for(int n:new int[]{20,21,22,23,90,91,92,93})listener.found(new NetworkEvidence.Entry("192.168.1."+n,80,"",NetworkEvidence.Kind.HTTP,NetworkEvidence.Proof.OPEN_PORT,"",""));listener.finished(End.COMPLETE);}public void close(){}});
                });
                MerossAccountTest.until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{LocalNetworkActivity p=MerossAccountTest.picker();ready.set(p!=null&&p.useCandidates.isEnabled()&&p.cards.getChildCount()==8);});return ready.get();});
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->MerossAccountTest.picker().useCandidates.performClick());
                MerossAccountTest.until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(!a.busy&&a.verifiedSelection()&&a.associate.isEnabled()));return ready.get();});assertEquals(1,calls[1]);
                ui.onActivity(a->{assertEquals(0,a.devices.getSelectedItemPosition());assertEquals(4,a.inventory.length());a.readConsent.setChecked(true);a.associate.performClick();});MerossAccountTest.message(ui,R.string.account_associated);
                ui.onActivity(a->{Button start=MerossAccountTest.button(a.getWindow().getDecorView(),a.getString(R.string.equipment_start));assertNotNull(start);start.performClick();});
            }
            MerossAccountTest.until(()->EngineHost.state()==EngineHost.State.RUNNING);
            MerossAccountTest.until(()->{server.check();JSONObject d=MerossReadTest.device(1);return d!=null&&d.getJSONObject("state").optJSONObject("power")!=null;});
            JSONObject sample=MerossReadTest.device(1);assertEquals(123.456,sample.getJSONObject("state").getJSONObject("power").getDouble("value"),0.001);
            EquipmentMonitor.publish(app,EquipmentStore.read(app),MerossReadTest.get("/api/devices"),null);
            String exported=TrialLog.export(app);for(String value:new String[]{"192.168.",own,MerossAccountTest.UUID,"a".repeat(32),"Guided fixture"})assertFalse(value,exported.contains(value));
            assertTrue(exported.contains("UUID_DIFFERENT"));assertTrue(exported.contains("CLE_REFUSEE"));assertTrue(exported.contains("INCOMPATIBLE"));assertTrue(exported.contains("dernière_réception="));
            TrialJournal journal=TrialJournal.parse(ProfileStore.bounded(new java.io.FileInputStream(new java.io.File(app.getFilesDir(),"trial-session.json")),131072));String subject=null,candidate=null;boolean linked=false,read=false;
            for(int i=0;i<journal.events.length();i++){JSONObject e=journal.events.getJSONObject(i);if(e.optString("event").equals("UUID_CHECK")&&e.optString("result").equals("ENREGISTRE")){subject=e.getString("subject");candidate=e.getString("candidate");}}
            assertNotNull(subject);assertNotNull(candidate);
            for(int i=0;i<journal.events.length();i++){JSONObject e=journal.events.getJSONObject(i);if(e.optString("event").equals("CANDIDATE_RESULT")&&subject.equals(e.optString("subject"))&&candidate.equals(e.optString("candidate")))linked=true;if(e.optString("event").equals("EQUIPMENT_READ")&&e.optInt("a")==3&&e.optString("result").equals("FRAIS")&&subject.equals(e.optString("subject")))read=true;}
            assertTrue(linked);assertTrue(read);assertEquals(0,cache.signins);
        }finally{
            app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP));MerossAccountTest.until(()->EngineHost.state()==EngineHost.State.STOPPED);
            TrialLog.end(app);EngineHost.maintain(()->{EquipmentStore.save(app,new JSONObject());return null;}).get();
        }
    }
}
