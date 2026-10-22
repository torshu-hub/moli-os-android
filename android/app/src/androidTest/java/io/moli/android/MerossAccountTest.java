package io.moli.android;

import android.app.Activity;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** UI fake backend + real vault/config bridge. No real account or device traffic. */
@RunWith(AndroidJUnit4.class)
public final class MerossAccountTest {
    interface Check{boolean ready()throws Exception;}
    static void until(Check c)throws Exception{long end=SystemClock.elapsedRealtime()+20000;while(!c.ready()){if(SystemClock.elapsedRealtime()>end)fail("Account UI within deadline");Thread.sleep(50);}}
    static Button button(View root,String text){if(root instanceof Button && ((Button)root).getText().toString().equals(text))return (Button)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){Button b=button(((ViewGroup)root).getChildAt(i),text);if(b!=null)return b;}return null;}
    static LocalNetworkActivity picker(){for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a instanceof LocalNetworkActivity)return (LocalNetworkActivity)a;return null;}
    static final String UUID="00000000000000000000000000000001",KEY="b".repeat(32);
    static final class Fixture implements MerossAccountStore.Backend {
        final Context app;int signins,associations;boolean connected;
        Fixture(Context app){this.app=app;}
        JSONObject status()throws Exception{JSONArray items=new JSONArray();if(connected)for(int i=1;i<=4;i++)items.put(new JSONObject().put("uuid",String.format(java.util.Locale.ROOT,"%032x",i)).put("name","Invented plug "+i).put("model","MSS310").put("hardware","6.0.0"));return new JSONObject().put("state",connected?"connected":"disconnected").put("items",items).put("cached_ms",System.currentTimeMillis()).put("inventory_wait_ms",connected?60000:0);}
        public JSONObject call(JSONObject input)throws Exception{
            switch(input.getString("action")){
                case "status":return status();
                case "signin":
                    signins++;assertEquals("user@example.com",input.getString("email"));assertEquals("invented-password",input.getString("password"));assertEquals("eu",input.getString("region"));
                    if(signins==1)throw new ProfileStore.Rejected("account_mfa");if(signins==2)assertEquals("123456",input.getString("otp"));connected=true;return status();
                case "inventory":connected=false;return new JSONObject().put("state","expired").put("items",new JSONArray());
                case "associate":
                    associations++;assertEquals(UUID,input.getString("uuid"));
                    if(input.getString("host").equals("192.168.1.22"))throw new ProfileStore.Rejected("account_local");
                    if(input.getString("host").equals("192.168.1.20"))throw new ProfileStore.Rejected("account_identity");
                    JSONObject config=EquipmentStore.read(app);config.put("meross",new JSONArray().put(new JSONObject().put("id",UUID).put("uuid",UUID).put("name",input.getString("name")).put("host",input.getString("host")).put("port",80).put("enabled",input.getBoolean("enabled"))));
                    EngineHost.maintain(() -> {EquipmentStore.save(app,config,new JSONArray().put(new JSONObject().put("id",UUID).put("key",KEY).put("required",true)));return null;}).get();
                    return new JSONObject().put("state","associated").put("uuid",UUID);
                case "forget":assertTrue(input.getBoolean("revoke"));connected=false;return status().put("revoked",false);
                default:throw new AssertionError("Unexpected account operation");
            }
        }
    }
    static void message(ActivityScenario<MerossAccountActivity> ui,int id)throws Exception{until(() -> {AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a -> done.set(!a.busy && a.cloudConsent.isEnabled() && a.message.getText().toString().equals(a.getString(id))));return done.get();});}
    static void ready(ActivityScenario<MerossAccountActivity> ui)throws Exception{until(() -> {AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a -> done.set(!a.busy && a.connect.isEnabled()));return done.get();});}
    @Test public void voluntaryMfaPreviewDiscoveryUuidRefusalExpiryAndForgetPreserveLocalSetup()throws Exception{
        assertTrue("Disposable emulator only",android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();assertEquals(EngineHost.State.STOPPED,EngineHost.state());if(!ProtectedKey.exists(app))ProtectedKey.create(app,"4826");
        Fixture fixture=new Fixture(app);
        try{
            try(ActivityScenario<MerossAccountActivity> ui=ActivityScenario.launch(MerossAccountActivity.class)){
                until(() -> {AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a -> done.set(a.loaded && !a.busy));return done.get();});
                ui.onActivity(a -> {a.backend=fixture;a.cloudConsent.setChecked(true);assertFalse(a.password.isSaveEnabled());assertFalse(a.otp.isSaveEnabled());assertTrue(a.getString(R.string.account_help).contains("Meross"));});
                ready(ui);ui.onActivity(a -> {a.email.setText("user@example.com");a.password.setText("invented-password");a.connect.performClick();assertTrue(a.password.getText().toString().isEmpty());});message(ui,R.string.account_mfa);
                ready(ui);ui.onActivity(a -> {a.password.setText("invented-password");a.otp.setText("123456");a.connect.performClick();});message(ui,R.string.account_connected);
                ui.onActivity(a -> {assertEquals(4,a.inventory.length());assertFalse(a.readConsent.isChecked());a.pick.performClick();});
                until(() -> {AtomicReference<Boolean> done=new AtomicReference<>(false);InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> done.set(picker()!=null));return done.get();});
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    LocalNetworkActivity p=picker();p.begin(new DiscoverySearch(){public void start(Listener listener){listener.found(new NetworkEvidence.Entry("192.168.1.20",80,"",NetworkEvidence.Kind.HTTP,NetworkEvidence.Proof.OPEN_PORT,"",""));listener.finished(End.COMPLETE);}public void close(){}});
                });
                until(() -> {AtomicReference<Boolean> done=new AtomicReference<>(false);InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> done.set(picker()!=null && picker().cards.getChildCount()==1));return done.get();});
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {LocalNetworkActivity p=picker();Button use=button(p.cards,p.getString(R.string.account_pick_ip));assertNotNull(use);use.performClick();});
                until(() -> {AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a -> done.set(a.host.getText().toString().equals("192.168.1.20") && a.associate.isEnabled()));return done.get();});
                ui.onActivity(a -> a.associate.performClick());message(ui,R.string.account_identity);assertEquals(0,EquipmentStore.read(app).optJSONArray("meross")==null?0:EquipmentStore.read(app).getJSONArray("meross").length());
                ui.onActivity(a -> {a.host.setText("192.168.1.22");a.associate.performClick();});message(ui,R.string.account_local);
                ui.onActivity(a -> {a.host.setText("192.168.1.21");a.readConsent.setChecked(true);a.associate.performClick();});message(ui,R.string.account_associated);
                assertEquals(UUID,EquipmentStore.read(app).getJSONArray("meross").getJSONObject(0).getString("uuid"));
                ui.onActivity(a -> {assertFalse(a.refresh.isEnabled());a.submit("inventory",new JSONObject());});message(ui,R.string.account_expired);ready(ui);
                ui.onActivity(a -> {a.email.setText("user@example.com");a.password.setText("invented-password");a.connect.performClick();});message(ui,R.string.account_connected);
                ui.onActivity(a -> {try{a.submit("forget",new JSONObject().put("revoke",true));}catch(Exception e){throw new AssertionError(e);}});message(ui,R.string.account_forgotten_offline);
                assertEquals(3,fixture.signins);assertEquals(3,fixture.associations);
            }
            try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(EquipmentSettingsActivity.class)){
                until(() -> {AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a -> done.set(a.meross.entries.size()==1 && a.save.isEnabled()));return done.get();});
                ui.onActivity(a -> {assertEquals(UUID,a.meross.entries.get(0).uuid);assertTrue(a.meross.entries.get(0).key.getText().toString().isEmpty());a.meross.entries.get(0).name.setText("Invented renamed plug");a.save.performClick();});
                until(() -> {AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a -> done.set(a.message.getText().toString().equals(a.getString(R.string.equipment_saved))));return done.get();});
            }
            JSONObject persisted=EquipmentStore.read(app).getJSONArray("meross").getJSONObject(0);assertEquals(UUID,persisted.getString("uuid"));assertTrue(persisted.getBoolean("enabled"));assertFalse(persisted.toString().contains(KEY));
            assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        }finally{EngineHost.maintain(() -> {EquipmentStore.save(app,new JSONObject());return null;}).get();}
    }
}
