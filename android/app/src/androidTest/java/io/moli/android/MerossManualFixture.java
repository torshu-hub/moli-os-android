package io.moli.android;

import android.app.AlertDialog;
import android.content.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Additional scenario in MerossReadTest's existing emulator gate, synthetic only. */
final class MerossManualFixture {
    private interface Check { boolean ready() throws Exception; }
    private static void until(Check check)throws Exception {
        long end=SystemClock.elapsedRealtime()+45000;
        while(!check.ready()){if(SystemClock.elapsedRealtime()>end)fail("Synthetic manual controls timed out");Thread.sleep(100);}
    }
    private static String js(ActivityScenario<MainActivity> ui,String code)throws Exception {
        AtomicReference<String> result=new AtomicReference<>();ui.onActivity(a->a.web.evaluateJavascript(code,result::set));until(()->result.get()!=null);return result.get();
    }
    private static void saved(ActivityScenario<EquipmentSettingsActivity> ui)throws Exception {
        until(()->{AtomicReference<Boolean> ok=new AtomicReference<>(false);ui.onActivity(a->ok.set(a.start.isEnabled()&&a.message.getText().toString().equals(a.getString(R.string.equipment_saved))));return ok.get();});
    }
    private static void select(ActivityScenario<EquipmentSettingsActivity> ui,int index,boolean confirm)throws Exception {
        ui.onActivity(a->{a.meross.entries.get(index).control.performClick();assertFalse(a.meross.entries.get(index).control.isChecked());});
        until(()->{AtomicReference<Boolean> shown=new AtomicReference<>(false);ui.onActivity(a->shown.set(a.meross.controlDialog!=null&&a.meross.controlDialog.isShowing()));return shown.get();});
        ui.onActivity(a->a.meross.controlDialog.getButton(confirm?AlertDialog.BUTTON_POSITIVE:AlertDialog.BUTTON_NEGATIVE).performClick());
        // AlertDialog posts its listener and dismissal to the main looper.
        // Observe their completion instead of assuming performClick is synchronous.
        until(()->{AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a->{
            int selected=0;for(MerossForm.Row row:a.meross.entries)if(row.control.isChecked())selected++;
            done.set(!a.meross.controlDialog.isShowing()&&a.meross.entries.get(index).control.isChecked()==confirm&&selected<2);
        });return done.get();});
    }
    private static void stop(Context app)throws Exception {
        app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP));until(()->EngineHost.state()==EngineHost.State.STOPPED);
    }
    static void verify()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();FreshEquipmentFixture.create(app);
        String ip=MerossReadTest.address();
        try(MerossReadTest.Fixture one=new MerossReadTest.Fixture(1,true);MerossReadTest.Fixture two=new MerossReadTest.Fixture(2)){
            one.on=false;
            JSONArray entries=new JSONArray();
            for(int i=1;i<=2;i++)entries.put(new JSONObject().put("id",String.format(java.util.Locale.ROOT,"%032x",i+100)).put("name","Manual fixture "+i).put("host",ip).put("port",i==1?one.port():two.port()).put("enabled",true));
            JSONArray keys=new JSONArray();for(int i=0;i<entries.length();i++)keys.put(new JSONObject().put("id",entries.getJSONObject(i).getString("id")).put("key","a".repeat(32)).put("required",true));
            EngineHost.maintain(()->{EquipmentStore.save(app,new JSONObject().put("meross",entries),keys);return null;}).get();
            try {
                app.startForegroundService(new Intent(app,MoliService.class));until(()->EngineHost.state()==EngineHost.State.RUNNING);until(()->MerossReadTest.device(1)!=null&&MerossReadTest.device(2)!=null);
                EquipmentMonitor.rememberIdentities(EquipmentStore.read(app),MerossReadTest.get("/api/devices"),System.currentTimeMillis());
                assertEquals(0,one.sets);assertEquals(0,two.sets);
                stop(app);
                try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(EquipmentSettingsActivity.class)) {
                    until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(a.save.isEnabled()&&a.meross.entries.size()==2));return ready.get();});
                    ui.onActivity(a->{
                        a.focus("meross");MerossForm.Row first=a.meross.entries.get(0),second=a.meross.entries.get(1);
                        assertTrue(first.control.isEnabled());assertFalse(first.control.isChecked());assertFalse(second.control.isChecked());
                    });
                    select(ui,1,false);
                    select(ui,0,true);
                    select(ui,1,true);
                    select(ui,0,true);
                    ui.onActivity(a->a.save.performClick());saved(ui);
                    JSONObject stored=EquipmentStore.read(app).getJSONArray("meross").getJSONObject(0);
                    assertTrue(stored.getBoolean("manual_control"));assertFalse(stored.has("uuid"));assertEquals(String.format(java.util.Locale.ROOT,"%032x",1),stored.getString("manual_uuid"));
                    ui.recreate();until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(a.save.isEnabled()&&a.meross.entries.size()==2));return ready.get();});
                    ui.onActivity(a->{assertTrue(a.meross.entries.get(0).control.isChecked());assertFalse(a.meross.entries.get(1).control.isChecked());assertEquals("",a.meross.entries.get(0).key.getText().toString());a.start.performClick();});
                }
                until(()->EngineHost.state()==EngineHost.State.RUNNING);until(()->MerossReadTest.device(1)!=null&&MerossReadTest.device(2)!=null);
                assertEquals(0,one.sets);
                try(AutoCloseable display=TouchRegression.tablet();ActivityScenario<MainActivity> home=ActivityScenario.launch(MainActivity.class)){
                    home.onActivity(MainActivity::showDevices);
                    until(()->"true".equals(js(home,"document.querySelectorAll('[data-meross-manual]').length===1 && document.querySelector('[data-meross-on]:not(:disabled)')!==null")));
                    assertEquals("true",js(home,"document.querySelector('[data-meross-on]').click();true"));
                    until(()->one.sets==1&&"true".equals(js(home,"document.querySelector('[data-meross-result=confirmed]')!==null && document.querySelector('[data-meross-off]:not(:disabled)')!==null")));
                    assertTrue(MerossReadTest.device(1).getJSONObject("state").getJSONObject("on").getBoolean("value"));
                    assertEquals("true",js(home,"document.querySelector('[data-meross-off]').click();true"));
                    until(()->one.sets==2&&!one.on&&"true".equals(js(home,"document.querySelector('[data-meross-off]:not(:disabled)')!==null")));
                    assertFalse(MerossReadTest.device(1).getJSONObject("state").getJSONObject("on").getBoolean("value"));assertEquals(0,two.sets);
                    EquipmentMonitor.publish(app,EquipmentStore.read(app),MerossReadTest.get("/api/devices"),null);
                    String report=TrialLog.export(app);assertTrue(report.contains("MEROSS_ACK : OK"));assertTrue(report.contains("MEROSS_READBACK : OK"));
                    for(String privateValue:new String[]{ip,"a".repeat(32),String.format(java.util.Locale.ROOT,"%032x",1),"Manual fixture"})assertFalse(report.contains(privateValue));
                    one.badAck=true;assertEquals("true",js(home,"document.querySelector('[data-meross-on]').click();true"));
                    until(()->one.sets==3&&"true".equals(js(home,"document.querySelector('[data-meross-result=unknown]')!==null")));
                    EquipmentMonitor.publish(app,EquipmentStore.read(app),MerossReadTest.get("/api/devices"),null);
                    assertTrue(TrialLog.export(app).contains("MEROSS_ACK : FAILED"));assertEquals(3,one.sets);assertEquals(0,two.sets);
                }
                stop(app);
                try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(EquipmentSettingsActivity.class)){
                    until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(a.save.isEnabled()&&a.meross.entries.size()==2));return ready.get();});
                    ui.onActivity(a->{a.meross.entries.get(0).control.setChecked(false);a.save.performClick();});saved(ui);ui.onActivity(a->a.start.performClick());
                }
                until(()->EngineHost.state()==EngineHost.State.RUNNING);until(()->MerossReadTest.device(1)!=null);
                assertEquals(3,one.sets); // Neither stop, revocation nor restart sends OFF or repeats SET.
                assertEquals(2,EquipmentStore.read(app).getJSONArray("meross").length());
                one.check();two.check();
            }finally{stop(app);}
        }
    }
    private MerossManualFixture(){}
}
