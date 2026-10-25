package io.moli.android;

import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import org.json.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Local WebView responses only; real Rust engine stays untouched. No provider
 * request, configuration mutation, account or physical command. */
final class NetatmoDisplayFixture {
    private interface Check{boolean ready()throws Exception;}
    private static void until(Check check)throws Exception{long end=SystemClock.elapsedRealtime()+30000;while(!check.ready()){if(SystemClock.elapsedRealtime()>end)fail("Netatmo display fixture deadline");Thread.sleep(50);}}
    private static String js(ActivityScenario<MainActivity> ui,String code)throws Exception{AtomicReference<String> out=new AtomicReference<>();ui.onActivity(a->a.web.evaluateJavascript(code,out::set));until(()->out.get()!=null);return out.get();}
    private static JSONObject sample(Object value)throws Exception{return new JSONObject().put("value",value==null?JSONObject.NULL:value).put("ts",System.currentTimeMillis());}
    private static JSONObject device(String id,String name,String model,JSONObject values)throws Exception{
        JSONArray points=new JSONArray();java.util.Iterator<String> keys=values.keys();while(keys.hasNext()){String key=keys.next();if(key.equals("freshness")||key.endsWith("_at")||key.equals("max_age_seconds"))continue;points.put(new JSONObject().put("key",key).put("label",key).put("access",new JSONObject().put("read",true).put("write",false)));}
        return new JSONObject().put("id","fixture-netatmo:"+id).put("instance","fixture-netatmo").put("native_name",name).put("manufacturer","Netatmo").put("model",model).put("online",true).put("state",values).put("points",points);
    }
    private static JSONObject values()throws Exception{long now=System.currentTimeMillis();return new JSONObject().put("freshness",sample("cloud_recent")).put("collected_at",sample(now)).put("server_at",sample(now)).put("max_age_seconds",sample(300));}
    private static JSONObject snapshot(boolean ok)throws Exception{
        long now=System.currentTimeMillis();JSONObject steps=new JSONObject();for(String stage:new String[]{"authorization","homesdata","homestatus","parsing","publication"})steps.put(stage,new JSONObject().put("status",ok?"ok":"transport").put("attempts",1).put("successes",ok?1:0).put("failures",ok?0:1).put("last_attempt_at",now).put(ok?"last_success_at":"last_error_at",now));
        JSONObject diag=new JSONObject().put("instance","fixture-netatmo").put("kind","netatmo").put("configured",true).put("enabled",true).put("credential_present",true).put("target_selected",true).put("driver_started",true).put("started_at",now).put("last_attempt_at",now).put("last_response_at",ok?now:0).put("last_publish_at",ok?now:0).put("stages",steps).put("summary",new JSONObject().put("devices",ok?2:0).put("fresh",ok?2:0).put("temperatures",ok?1:0).put("setpoints",ok?1:0).put("demand_present",ok?1:0));
        JSONArray devices=new JSONArray();if(ok){devices.put(device("room-test","private-fixture-room","room",values().put("temperature",sample(19.5)).put("setpoint_temperature",sample(20.5)).put("mode",sample("schedule"))));devices.put(device("module-test","private-fixture-thermostat","NATherm1",values().put("boiler_status",sample(true)).put("declared_demand",sample("on"))));}
        return new JSONObject().put("devices",devices).put("drivers",new JSONArray().put(new JSONObject().put("instance","fixture-netatmo").put("kind","netatmo").put("status",new JSONObject().put("state",ok?"running":"waiting")))).put("diagnostics",new JSONArray().put(diag));
    }
    static void verify(ActivityScenario<MainActivity> ui)throws Exception{
        assertTrue(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"));
        ui.onActivity(a->MerossAccountTest.button(a.getWindow().getDecorView(),a.getString(R.string.preview_devices)).performClick());
        until(()->"true".equals(js(ui,"!!document.querySelector('[data-netatmo-overview]')")));
        String real="window.__moliOriginalFetch=window.fetch;window.__moliSystemHang=false;window.fetch=(path,options)=>{if(path==='/api/system'&&window.__moliSystemHang)return new Promise(()=>{});return window.__moliOriginalFetch(path,options).then(async r=>{if(path!=='/api/devices')return r;const base=await r.json(),f=window.__moliNetatmoFixture;return new Response(JSON.stringify({...base,devices:[...base.devices,...f.devices],drivers:[...base.drivers,...f.drivers],diagnostics:[...(base.diagnostics||[]),...f.diagnostics]}),{status:200,headers:{'content-type':'application/json'}});});};";
        js(ui,"window.__moliNetatmoFixture="+snapshot(true)+";"+real+"document.dispatchEvent(new Event('visibilitychange'));true");
        try{
            until(()->"true".equals(js(ui,"document.querySelectorAll('[data-netatmo-status=cloud_recent]').length===2 && document.querySelector('[data-netatmo-overview]').innerText.includes('19') && document.querySelector('[data-netatmo-overview]').innerText.includes('20')")));
            js(ui,"window.__moliNetatmoFixture="+snapshot(false)+";document.dispatchEvent(new Event('visibilitychange'));true");
            until(()->"true".equals(js(ui,"!!document.querySelector('[data-netatmo-overview-status=error_transport]') && !document.querySelector('[data-netatmo-status]')")));
            js(ui,"window.__moliSystemHang=true;window.__moliAt=Array.prototype.at;window.__moliFindLast=Array.prototype.findLast;Array.prototype.at=undefined;Array.prototype.findLast=undefined;location.hash='#/systeme';true");
            until(()->"true".equals(js(ui,"!!document.querySelector('[data-system-error]') && !!document.querySelector('[data-system-retry]')")));
            AtomicReference<String> report=new AtomicReference<>();ui.onActivity(a->{try{NetatmoDiagnostics.observe(a,snapshot(true));report.set(TrialLog.export(a));}catch(Exception e){throw new AssertionError(e);}});
            assertTrue(report.get().contains("UI_SYSTEM : FAILED cause=TIMEOUT"));assertTrue(report.get().contains("NETATMO_TILE"));assertFalse(report.get().contains("private-fixture"));assertFalse(report.get().contains("fixture-netatmo"));
            assertTrue(report.get().contains("NETATMO_HOMESTATUS : OK"));assertTrue(report.get().contains("NETATMO_DATA : OK"));
            js(ui,"window.__moliSystemHang=false;document.querySelector('[data-system-retry]').click();true");
            until(()->"true".equals(js(ui,"!!document.querySelector('[data-system-graph]') && !document.querySelector('[data-system-error]') && !document.querySelector('[data-system-render-error]')")));
        }finally{js(ui,"window.fetch=window.__moliOriginalFetch;if(window.__moliAt)Array.prototype.at=window.__moliAt;if(window.__moliFindLast)Array.prototype.findLast=window.__moliFindLast;location.hash='#/';true");}
    }
    private NetatmoDisplayFixture(){}
}
