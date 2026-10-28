package io.moli.android;

import android.app.AlertDialog;
import android.content.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Four independent synthetic plugs in the existing emulator gate. No real equipment. */
final class MerossManualFixture {
    private interface Check { boolean ready() throws Exception; }
    private static void until(Check check)throws Exception {
        long end=SystemClock.elapsedRealtime()+45000;
        while(!check.ready()){if(SystemClock.elapsedRealtime()>end)fail("Synthetic manual controls timed out");Thread.sleep(100);}
    }
    private static String js(ActivityScenario<MainActivity> ui,String code)throws Exception {
        AtomicReference<String> result=new AtomicReference<>();ui.onActivity(a->a.web.evaluateJavascript(code,result::set));until(()->result.get()!=null);return result.get();
    }
    private static String id(int n){return String.format(java.util.Locale.ROOT,"%032x",n+101);}
    private static String card(int n){return "document.querySelector('[data-equipment-instance=\"android-meross-"+id(n)+"\"]')";}
    private static void ready(ActivityScenario<EquipmentSettingsActivity> ui)throws Exception {
        until(()->{AtomicReference<Boolean> ok=new AtomicReference<>(false);ui.onActivity(a->ok.set(a.save.isEnabled()&&a.meross.entries.size()==4));return ok.get();});
        ui.onActivity(a->a.focus("meross"));
    }
    private static int selected(EquipmentSettingsActivity a){int n=0;for(MerossForm.Row row:a.meross.entries)if(row.control.isChecked())n++;return n;}
    private static void saved(ActivityScenario<EquipmentSettingsActivity> ui)throws Exception {
        until(()->{AtomicReference<Boolean> ok=new AtomicReference<>(false);ui.onActivity(a->ok.set(a.start.isEnabled()&&a.message.getText().toString().equals(a.getString(R.string.equipment_saved))));return ok.get();});
    }
    private static void select(ActivityScenario<EquipmentSettingsActivity> ui,int index,boolean confirm)throws Exception {
        AtomicReference<AlertDialog> identity=new AtomicReference<>();AtomicReference<Boolean> critical=new AtomicReference<>(false);
        ui.onActivity(a->{a.meross.entries.get(index).control.performClick();assertFalse(a.meross.entries.get(index).control.isChecked());identity.set(a.meross.controlDialog);critical.set(a.meross.entries.get(index).critical.isChecked());assertTrue(identity.get().isShowing());});
        ui.onActivity(a->identity.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        if(critical.get()){
            until(()->{AtomicReference<Boolean> shown=new AtomicReference<>(false);ui.onActivity(a->shown.set(a.meross.controlDialog!=identity.get()&&a.meross.controlDialog.isShowing()));return shown.get();});
            ui.onActivity(a->{assertFalse(a.meross.entries.get(index).control.isChecked());a.meross.controlDialog.getButton(confirm?AlertDialog.BUTTON_POSITIVE:AlertDialog.BUTTON_NEGATIVE).performClick();});
        }
        until(()->{AtomicReference<Boolean> done=new AtomicReference<>(false);ui.onActivity(a->done.set(!a.meross.controlDialog.isShowing()&&a.meross.entries.get(index).control.isChecked()==confirm));return done.get();});
    }
    private static void stop(Context app)throws Exception {
        app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP));until(()->EngineHost.state()==EngineHost.State.STOPPED);
    }
    private static void permissions(int count)throws Exception {
        until(()->{int writers=0;for(int n=1;n<=4;n++){JSONObject device=MerossReadTest.device(n);if(device==null)return false;
            JSONArray points=device.getJSONArray("points");for(int k=0;k<points.length();k++){JSONObject point=points.getJSONObject(k);if(point.getString("key").equals("on")&&point.getJSONObject("access").getBoolean("write"))writers++;}}
            return writers==count;});
    }
    private static void refuseUnauthorised(int n)throws Exception {
        JSONObject device=MerossReadTest.device(n+1);assertNotNull(device);
        HttpURLConnection c=(HttpURLConnection)new URL(EngineHost.origin()+"/api/command").openConnection();
        c.setConnectTimeout(2000);c.setReadTimeout(4000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Cookie","moli_android="+EngineHost.token());c.setRequestProperty("x-moli-origin","ui");c.setRequestProperty("Origin",EngineHost.origin());c.setRequestProperty("Content-Type","application/json");
        try{c.getOutputStream().write(new JSONObject().put("point",device.getString("id")+"/on").put("value",true).toString().getBytes(StandardCharsets.UTF_8));assertEquals(403,c.getResponseCode());assertTrue("Read-only target refused by the hub",ProfileStore.bounded(c.getErrorStream(),4096).contains("point is read-only"));}finally{c.disconnect();}
    }
    static void verify()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();FreshEquipmentFixture.create(app);
        String ip=MerossReadTest.address();
        try(MerossReadTest.Fixture one=new MerossReadTest.Fixture(1,true);MerossReadTest.Fixture two=new MerossReadTest.Fixture(2,true);
            MerossReadTest.Fixture three=new MerossReadTest.Fixture(3,true);MerossReadTest.Fixture four=new MerossReadTest.Fixture(4,true)){
            MerossReadTest.Fixture[] fixtures={one,two,three,four};JSONArray entries=new JSONArray(),keys=new JSONArray();
            for(int n=0;n<4;n++){fixtures[n].on=false;entries.put(new JSONObject().put("id",id(n)).put("name","Manual fixture "+(n+1)).put("host",ip).put("port",fixtures[n].port()).put("enabled",true));
                keys.put(new JSONObject().put("id",id(n)).put("key","a".repeat(32)).put("required",true));}
            EngineHost.maintain(()->{EquipmentStore.save(app,new JSONObject().put("meross",entries),keys);return null;}).get();
            try {
                app.startForegroundService(new Intent(app,MoliService.class));until(()->EngineHost.state()==EngineHost.State.RUNNING);permissions(0);refuseUnauthorised(0);
                EquipmentMonitor.rememberIdentities(EquipmentStore.read(app),MerossReadTest.get("/api/devices"),System.currentTimeMillis());
                for(MerossReadTest.Fixture fixture:fixtures)assertEquals(0,fixture.sets);
                stop(app);
                for(int count:new int[]{1,2,4}){
                    try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(EquipmentSettingsActivity.class)){
                        ready(ui);
                        if(count==4){
                            select(ui,2,true);ui.onActivity(a->{assertEquals(3,selected(a));a.meross.entries.get(3).critical.performClick();});
                            select(ui,3,false);ui.onActivity(a->assertEquals(3,selected(a)));
                            select(ui,3,true);
                        }else select(ui,count-1,true);
                        ui.onActivity(a->{assertEquals(count,selected(a));a.save.performClick();});saved(ui);
                        ui.recreate();ready(ui);ui.onActivity(a->{assertEquals(count,selected(a));for(MerossForm.Row row:a.meross.entries)assertEquals("",row.key.getText().toString());if(count==4){assertTrue(a.meross.entries.get(3).critical.isChecked());assertEquals(String.format(java.util.Locale.ROOT,"%032x",4),a.meross.entries.get(3).criticalAck);}a.start.performClick();});
                    }
                    until(()->EngineHost.state()==EngineHost.State.RUNNING);permissions(count);
                    if(count<4){refuseUnauthorised(count);for(MerossReadTest.Fixture fixture:fixtures)assertEquals(0,fixture.sets);
                        EquipmentMonitor.rememberIdentities(EquipmentStore.read(app),MerossReadTest.get("/api/devices"),System.currentTimeMillis());stop(app);}
                }
                String master=ProtectedKey.open(app).getString("master");
                try(AutoCloseable display=TouchRegression.tablet();ActivityScenario<MainActivity> home=ActivityScenario.launch(MainActivity.class)){
                    home.onActivity(MainActivity::showEquipmentPage);
                    until(()->"true".equals(js(home,"document.querySelectorAll('[data-meross-manual]').length===4 && document.querySelectorAll('[data-meross-on]:not(:disabled)').length===4")));
                    for(int n=0;n<4;n++){
                        final int target=n;
                        assertEquals("true",js(home,card(n)+".querySelector('[data-meross-on]').click();true"));
                        until(()->fixtures[target].sets==1&&"true".equals(js(home,card(target)+".querySelector('[data-meross-result=confirmed]')!==null && "+card(target)+".querySelector('[data-meross-off]:not(:disabled)')!==null")));
                        assertTrue(fixtures[n].on);
                        for(int other=0;other<4;other++)assertEquals(other<n?2:other==n?1:0,fixtures[other].sets);
                        assertEquals("true",js(home,card(n)+".querySelector('[data-meross-off]').click();true"));
                        until(()->fixtures[target].sets==2&&!fixtures[target].on&&"true".equals(js(home,card(target)+".querySelector('[data-meross-result=confirmed]')!==null && "+card(target)+".querySelector('[data-meross-on]:not(:disabled)')!==null")));
                    }
                    assertEquals("true",js(home,"document.documentElement.scrollWidth<=innerWidth && !!document.querySelector('.critical-use')"));
                    EquipmentMonitor.publish(app,EquipmentStore.read(app),MerossReadTest.get("/api/devices"),null);
                    String report=TrialLog.export(app);assertTrue(report.contains("MEROSS_ACK : OK"));assertTrue(report.contains("MEROSS_READBACK : OK"));
                    for(String value:new String[]{ip,"a".repeat(32),String.format(java.util.Locale.ROOT,"%032x",1),"Manual fixture"})assertFalse(report.contains(value));
                    one.badAck=true;assertEquals("true",js(home,card(0)+".querySelector('[data-meross-on]').click();true"));
                    until(()->one.sets==3&&"true".equals(js(home,card(0)+".querySelector('[data-meross-result=unknown]')!==null")));
                    EquipmentMonitor.publish(app,EquipmentStore.read(app),MerossReadTest.get("/api/devices"),null);
                    assertTrue(TrialLog.export(app).contains("MEROSS_ACK : FAILED"));
                    assertEquals("true",js(home,card(3)+".querySelector('[data-meross-on]:not(:disabled)')!==null"));
                    for(int n=1;n<4;n++)assertEquals(2,fixtures[n].sets);
                }
                stop(app);
                try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(EquipmentSettingsActivity.class)){
                    ready(ui);ui.onActivity(a->{a.meross.entries.get(0).control.setChecked(false);assertEquals(3,selected(a));a.save.performClick();});saved(ui);ui.onActivity(a->a.start.performClick());
                }
                until(()->EngineHost.state()==EngineHost.State.RUNNING);permissions(3);refuseUnauthorised(0);
                assertEquals(master,ProtectedKey.open(app).getString("master"));assertEquals(4,EquipmentStore.read(app).getJSONArray("meross").length());assertEquals(3,one.sets);
                for(int n=1;n<4;n++)assertEquals(2,fixtures[n].sets);for(MerossReadTest.Fixture fixture:fixtures)fixture.check();
                TouchRegression.evidence("MEROSS multi: 0/1/2/4 independent permissions; four targeted synthetic ON/OFF pairs, critical confirmation cancelled/accepted, isolated unknown ACK and single revocation; no startup or retry SET");
            }finally{stop(app);}
        }
    }
    private MerossManualFixture(){}
}
