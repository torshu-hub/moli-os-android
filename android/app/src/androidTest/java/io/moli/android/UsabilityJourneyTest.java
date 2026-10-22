package io.moli.android;

import android.content.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Transversal fixture journey; no real login, scan, printer, router or plug is contacted. */
@RunWith(AndroidJUnit4.class)
public final class UsabilityJourneyTest {
    private static void resumed(ActivityScenario<MerossAccountActivity> ui,MerossAccountStore.Backend backend)throws Exception {
        MerossAccountTest.until(()->{AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a->done.set(a.loaded && !a.busy));return done.get();});
        ui.onActivity(a->{a.backend=backend;a.loaded=false;});MerossAccountTest.message(ui,R.string.account_connected);
    }
    @Test public void cachedAccountReturnsWithoutPasswordAndExportCorrelatesOnlyPseudonyms()throws Exception {
        assertTrue(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();assertEquals(EngineHost.State.STOPPED,EngineHost.state());if(!ProtectedKey.exists(app))ProtectedKey.create(app,"4826");
        app.getSharedPreferences("meross-draft",Context.MODE_PRIVATE).edit().clear().commit();
        TrialLog.end(app);TrialLog.begin(app);
        MerossAccountTest.Fixture fixture=new MerossAccountTest.Fixture(app);fixture.connected=true;
        MerossAccountStore.Backend backend=input->{
            if(input.optString("action").equals("identify"))return new JSONObject().put("state","identified").put("results",new JSONArray().put(new JSONObject().put("host","192.168.1.21").put("uuid",MerossAccountTest.UUID).put("result","matched")));
            return fixture.call(input);
        };
        try {
            try(ActivityScenario<MerossAccountActivity> ui=ActivityScenario.launch(MerossAccountActivity.class)){
                resumed(ui,backend);
                ui.onActivity(a->{assertEquals(4,a.inventory.length());assertFalse(a.password.isShown());a.host.setText("192.168.1.20");a.name.setText("Private personal name");});
                ui.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);ui.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);MerossAccountTest.message(ui,R.string.account_connected);
                ui.onActivity(a->{assertEquals("192.168.1.20",a.host.getText().toString());assertEquals("Private personal name",a.name.getText().toString());assertFalse(a.password.isShown());a.associate.performClick();});MerossAccountTest.message(ui,R.string.account_identity);
                ui.onActivity(a->{try{a.submit("identify",new JSONObject().put("candidates",new JSONArray().put("192.168.1.21")));}catch(Exception e){throw new AssertionError(e);}});
                MerossAccountTest.until(()->{AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a->done.set(!a.busy && a.host.getText().toString().equals("192.168.1.21") && a.associate.isEnabled()));return done.get();});
                ui.onActivity(a->{a.readConsent.setChecked(true);a.associate.performClick();});MerossAccountTest.message(ui,R.string.account_associated);
                ui.recreate();resumed(ui,backend);ui.onActivity(a->{assertEquals(4,a.inventory.length());assertFalse(a.password.isShown());});
                assertEquals(0,fixture.signins);
            }
            // Group service evidence by the same IP, without claiming a port identifies a manufacturer.
            try(ActivityScenario<LocalNetworkActivity> ui=ActivityScenario.launch(LocalNetworkActivity.class)){
                ui.onActivity(a->a.begin(new DiscoverySearch(){public void start(Listener l){l.found(new NetworkEvidence.Entry("192.168.1.20",80,"Private personal name",NetworkEvidence.Kind.HTTP,NetworkEvidence.Proof.OPEN_PORT,"",""));l.found(new NetworkEvidence.Entry("192.168.1.20",631,"Invented printer",NetworkEvidence.Kind.IPP,NetworkEvidence.Proof.ANNOUNCEMENT,"/ipp/print",""));l.finished(End.COMPLETE);}public void close(){}}));
                MerossAccountTest.until(()->{AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a->done.set(a.cards.getChildCount()==1 && a.search.isEnabled()));return done.get();});
                ui.onActivity(a->{a.copy(a.results.list().get(0));a.configure(a.results.list().get(1),"ipp");});
            }
            JSONObject config=EquipmentStore.read(app);config.put("printer",new JSONObject().put("host","192.168.1.20").put("port",631).put("path","/ipp/print"));config.put("gateway",new JSONObject().put("location","http://192.168.1.1/root.xml"));
            JSONObject empty=new JSONObject().put("devices",new JSONArray()).put("drivers",new JSONArray());
            EquipmentMonitor.publish(app,config,empty,null); // Absence is exported, never invented as success.
            java.lang.reflect.Field cached=TrialLog.class.getDeclaredField("journal");cached.setAccessible(true);cached.set(null,null);TrialLog.attach(app);assertTrue(TrialLog.active());
            String text=TrialLog.export(app);assertTrue(text.contains("Session et inventaire en cache"));assertTrue(text.contains("ENREGISTRE"));assertTrue(text.contains("REFUSE"));assertTrue(text.contains("ABSENT"));assertTrue(text.contains("appareil#"));
            for(String secret:new String[]{"192.168.","Private personal name",MerossAccountTest.UUID,MerossAccountTest.KEY,"user@example.com","invented-password","123456","iotx-eu.meross.com"})assertFalse(secret,text.contains(secret));
            try(ActivityScenario<TrialJournalActivity> ui=ActivityScenario.launch(TrialJournalActivity.class)){
                ui.onActivity(a->{assertTrue(TrialLog.active());a.end.performClick();});
                ui.recreate();assertFalse(TrialLog.active());assertTrue(TrialLog.export(app).contains("Fin de session"));
            }
        }finally{TrialLog.end(app);EngineHost.maintain(()->{EquipmentStore.save(app,new JSONObject());return null;}).get();app.getSharedPreferences("meross-draft",Context.MODE_PRIVATE).edit().clear().commit();}
    }
}
