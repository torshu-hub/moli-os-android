package io.moli.android;

import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.webkit.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import static org.junit.Assert.*;

/** Shipped WebView UI against shared synthetic API responses. Rust HTTP/engine
 * admission is independently tested. Only emulator-owned GET responders exist. */
final class ObservationFixture {
    private static String js(ActivityScenario<MainActivity> ui,String code)throws Exception {return TouchRegression.js(ui,code);}
    private static void waitJs(ActivityScenario<MainActivity> ui,String condition)throws Exception {TouchRegression.until(()->"true".equals(js(ui,condition)));}
    private static void stop(Context app)throws Exception {app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP));TouchRegression.until(()->EngineHost.state()==EngineHost.State.STOPPED);}
    private static void install(MainActivity activity,String script){
        activity.web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                String origin=EngineHost.origin();Uri uri=request.getUrl();
                if(!request.isForMainFrame()||origin==null||!request.getMethod().equals("GET")||!"/".equals(uri.getPath())||!uri.toString().startsWith(origin+"/"))return null;
                try{
                    HttpURLConnection c=(HttpURLConnection)new URL(origin+"/").openConnection();c.setConnectTimeout(2000);c.setReadTimeout(3000);c.setUseCaches(false);c.setRequestProperty("Cookie","moli_android="+EngineHost.token());c.setRequestProperty("Accept-Encoding","identity");
                    String html;try{if(c.getResponseCode()!=200)return null;html=ProfileStore.bounded(c.getInputStream(),2000000);}finally{c.disconnect();}
                    html=html.replace("</head>","<script>"+script+"</script></head>");
                    return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                }catch(Exception unavailable){return null;}
            }
        });
        activity.showEquipmentPage();activity.web.reload();
    }
    private static void refresh(ActivityScenario<MainActivity> ui)throws Exception {
        waitJs(ui,"!!document.querySelector('[data-observation-refresh]:not(:disabled)')");
        js(ui,"document.querySelector('[data-observation-refresh]').click();true");
    }
    private static void mode(ActivityScenario<MainActivity> ui,String mode)throws Exception {
        js(ui,"window.fixtureObservation('"+mode+"');true");refresh(ui);
    }
    static void verify()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();FreshEquipmentFixture.create(app);
        String fixture=ProfileStore.bounded(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("observation-fixture.js"),32768);
        String ip=MerossReadTest.address();
        try(MerossReadTest.Fixture one=new MerossReadTest.Fixture(1,true);MerossReadTest.Fixture two=new MerossReadTest.Fixture(2,true);
            MerossReadTest.Fixture three=new MerossReadTest.Fixture(3,true);MerossReadTest.Fixture four=new MerossReadTest.Fixture(4,true)){
            MerossReadTest.Fixture[] devices={one,two,three,four};
            for(int permissions:new int[]{0,1,4}){
                JSONArray rows=new JSONArray(),keys=new JSONArray();
                for(int n=0;n<4;n++){
                    String id=String.format(java.util.Locale.ROOT,"%032x",n+1);
                    rows.put(new JSONObject().put("id",id).put("uuid",id).put("manual_uuid",id).put("name","Observation fixture "+(n+1)).put("host",ip).put("port",devices[n].port()).put("enabled",true).put("manual_control",n<permissions));
                    keys.put(new JSONObject().put("id",id).put("key","a".repeat(32)).put("required",true));
                }
                EngineHost.maintain(()->{EquipmentStore.save(app,new JSONObject().put("meross",rows),keys);return null;}).get();
                app.startForegroundService(new Intent(app,MoliService.class));TouchRegression.until(()->EngineHost.state()==EngineHost.State.RUNNING);
                TouchRegression.until(()->MerossReadTest.device(1)!=null&&MerossReadTest.device(4)!=null);
                String before=EquipmentStore.read(app).toString(),master=ProtectedKey.open(app).getString("master");
                byte[] vault=java.nio.file.Files.readAllBytes(new File(ProfileStore.data(app),"secrets.enc").toPath());
                JSONObject snapshot=MerossReadTest.get("/api/devices");
                String script=fixture+";window.installObservationFixture("+snapshot+",{key:'native-"+permissions+"'});";
                try(AutoCloseable display=TouchRegression.tablet();ActivityScenario<MainActivity> ui=ActivityScenario.launch(MainActivity.class)){
                    ui.onActivity(a->install(a,script));
                    waitJs(ui,"document.querySelectorAll('[data-observation-source] option').length===2 && document.querySelectorAll('[data-observation-target] option').length===5 && !document.querySelector('[data-observation-source]').disabled");
                    assertEquals("true",js(ui,"(()=>{const s=document.querySelector('[data-observation-source]'),t=document.querySelector('[data-observation-target]');s.value=s.options[1].value;s.dispatchEvent(new Event('change',{bubbles:true}));t.value=t.options[1].value;t.dispatchEvent(new Event('change',{bubbles:true}));return true})()"));
                    waitJs(ui,"!!document.querySelector('[data-observation-save]:not(:disabled)')");js(ui,"document.querySelector('[data-observation-save]').click();true");
                    waitJs(ui,"document.querySelector('[data-observation-intention]')?.textContent==='ON'");
                    mode(ui,"off");waitJs(ui,"document.querySelector('[data-observation-intention]')?.textContent==='OFF'");
                    for(String value:new String[]{"unknown","null","boolean","old","future","missing","source_offline","target_offline","identity","coherence"}){
                        mode(ui,value);waitJs(ui,"!['ON','OFF'].includes(document.querySelector('[data-observation-intention]')?.textContent)");
                    }
                    mode(ui,"on");waitJs(ui,"document.querySelector('[data-observation-intention]')?.textContent==='ON'");
                    ui.onActivity(a->a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                    TouchRegression.until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(a.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE));return ready.get();});
                    ui.onActivity(a->install(a,script));waitJs(ui,"document.querySelector('[data-observation-intention]')?.textContent==='ON'");
                    ui.recreate();ui.onActivity(a->install(a,script));waitJs(ui,"document.querySelector('[data-observation-intention]')?.textContent==='ON'");
                    assertEquals("true",js(ui,"document.documentElement.scrollWidth<=innerWidth && window.observationFixtureState.commands===0"));
                    assertEquals("true",js(ui,"document.querySelector('[data-observation-source]').value==='android-netatmo-personal:synthetic-thermostat'"));
                    if(permissions==4)nativeActivationStaysBlocked(app);
                    String report=TrialLog.export(app);assertTrue(report.contains("UI_OBSERVATION"));assertFalse(report.contains("Synthetic thermostat"));assertFalse(report.contains("Observation fixture"));
                }finally{stop(app);}
                assertEquals(before,EquipmentStore.read(app).toString());assertEquals(master,ProtectedKey.open(app).getString("master"));
                assertArrayEquals(vault,java.nio.file.Files.readAllBytes(new File(ProfileStore.data(app),"secrets.enc").toPath()));
                for(MerossReadTest.Fixture device:devices){assertEquals(0,device.sets);device.check();}
            }
        }
        TouchRegression.evidence("OBSERVATION WebView: explicit source/target, ON/OFF/unknown/invalid/stale/offline/recovery, saved selection across rotation/recreation; 0/1/4 manual permissions and vault preserved; zero command requests and SET on synthetic responders");
    }
    private static java.util.List<android.widget.CheckBox> checkboxes(android.view.View view){
        java.util.List<android.widget.CheckBox> result=new java.util.ArrayList<>();if(view instanceof android.widget.CheckBox check)result.add(check);
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++)result.addAll(checkboxes(group.getChildAt(i)));return result;
    }
    private static void nativeActivationStaysBlocked(Context app)throws Exception{
        try(ActivityScenario<HeatingTrialActivity> trial=ActivityScenario.launch(new Intent(app,HeatingTrialActivity.class))){
            trial.onActivity(a->{assertTrue((a.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);java.util.List<android.widget.CheckBox> checks=checkboxes(a.getWindow().getDecorView());assertEquals(5,checks.size());for(android.widget.CheckBox c:checks){assertFalse(c.isChecked());c.setChecked(true);}assertFalse(a.findViewById(R.id.heating_apply).isEnabled());});
            trial.recreate();trial.onActivity(a->{for(android.widget.CheckBox c:checkboxes(a.getWindow().getDecorView()))assertFalse(c.isChecked());assertFalse(a.findViewById(R.id.heating_apply).isEnabled());});
        }
    }
    private ObservationFixture(){}
}
