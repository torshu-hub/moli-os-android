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
    private static void waitJs(ActivityScenario<MainActivity> ui,String condition)throws Exception {
        try{TouchRegression.until(()->"true".equals(js(ui,condition)));}
        catch(AssertionError failure){throw new AssertionError("Synthetic observation UI: "+js(ui,"JSON.stringify({fixture:!!window.observationFixtureState,source:document.querySelectorAll('[data-observation-source] option').length,target:document.querySelectorAll('[data-observation-target] option').length,card:!!document.querySelector('[data-observation]'),state:document.querySelector('[data-observation]')?.dataset.observationState,blocked:document.querySelector('[data-observation-source]')?.disabled})"),failure);}
    }
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
        activity.showEquipmentPage();
        // Reloading immediately after a hash navigation can reload the old
        // document/route instead. Force one new document with an explicit route.
        activity.web.loadUrl(EngineHost.origin()+"/?synthetic_observation="+System.nanoTime()+"#/appareils");
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
                TouchRegression.until(()->MerossReadTest.device(1)!=null&&MerossReadTest.device(2)!=null&&MerossReadTest.device(3)!=null&&MerossReadTest.device(4)!=null);
                String before=EquipmentStore.read(app).toString(),master=ProtectedKey.open(app).getString("master");
                byte[] vault=java.nio.file.Files.readAllBytes(new File(ProfileStore.data(app),"secrets.enc").toPath());
                JSONObject snapshot=MerossReadTest.get("/api/devices");
                String script=fixture+";window.installObservationFixture("+snapshot+",{key:'native-"+permissions+"',refreshSamples:true});";
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
                    if(permissions==4){continuousDryOnly(ui);nativeActivationStaysBlocked(app);}
                    String report=TrialLog.export(app);assertTrue(report.contains("UI_OBSERVATION"));assertFalse(report.contains("Synthetic thermostat"));assertFalse(report.contains("Observation fixture"));
                }finally{stop(app);}
                assertEquals(before,EquipmentStore.read(app).toString());assertEquals(master,ProtectedKey.open(app).getString("master"));
                assertArrayEquals(vault,java.nio.file.Files.readAllBytes(new File(ProfileStore.data(app),"secrets.enc").toPath()));
                for(MerossReadTest.Fixture device:devices){assertEquals(0,device.sets);device.check();}
            }
        }
        TouchRegression.evidence("OBSERVATION WebView: explicit source/target, ON/OFF/unknown/invalid/stale/offline/recovery, saved selection across rotation/recreation; daily native candidate stays disabled while explicit dry observation starts, follows simulated publications and stops; 0/1/4 manual permissions and vault preserved; zero command requests and SET on synthetic responders");
    }
    private static void continuousDryOnly(ActivityScenario<MainActivity> ui)throws Exception{
        waitJs(ui,"!!document.querySelector('[data-daily-prepare]:not(:disabled)')");
        js(ui,"document.querySelector('[data-daily-prepare]').click();true");
        waitJs(ui,"!!document.querySelector('[data-daily-start]:not(:disabled)')");
        js(ui,"document.querySelector('[data-daily-start]').click();true");
        waitJs(ui,"document.querySelector('[data-daily-observation]')?.dataset.dailyObservation==='active'");
        js(ui,"window.fixtureObservation('on');document.dispatchEvent(new Event('visibilitychange'));true");
        waitJs(ui,"document.querySelector('[data-daily-decision]')?.textContent.includes('Simulation')&&document.querySelector('[data-daily-decision]')?.textContent.includes('ON')");
        js(ui,"window.fixtureObservation('unknown');document.dispatchEvent(new Event('visibilitychange'));true");
        waitJs(ui,"!document.querySelector('[data-daily-decision]')?.textContent.includes('ON')");
        js(ui,"document.querySelector('[data-daily-stop]').click();true");
        waitJs(ui,"document.querySelector('[data-daily-observation]')?.dataset.dailyObservation==='stopped'");
        assertEquals("true",js(ui,"document.querySelector('[data-daily-heating]')?.dataset.dailyHeating==='disabled'&&window.observationFixtureState.commands===0"));
        js(ui,"window.fixtureObservation('on');true");
    }
    private static java.util.List<android.widget.CheckBox> checkboxes(android.view.View view){
        java.util.List<android.widget.CheckBox> result=new java.util.ArrayList<>();if(view instanceof android.widget.CheckBox check)result.add(check);
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++)result.addAll(checkboxes(group.getChildAt(i)));return result;
    }
    private static void nativeActivationStaysBlocked(Context app)throws Exception{
        // Test-only substitution of the embedded origin. Owned loopback responder,
        // never a provider or device; no production injection hook is shipped.
        java.lang.reflect.Field field=EngineHost.class.getDeclaredField("origin");field.setAccessible(true);String previous=(String)field.get(null);
        try(TrialServer server=new TrialServer()){
            field.set(null,"http://127.0.0.1:"+server.socket.getLocalPort());
            try(ActivityScenario<HeatingTrialActivity> trial=ActivityScenario.launch(new Intent(app,HeatingTrialActivity.class))){
                trial.onActivity(a->{assertTrue((a.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);assertEquals(0,checkboxes(a.getWindow().getDecorView()).size());assertNull(confirmation(a));});
                waitTrial(trial,a->a.findViewById(R.id.heating_prepare).isEnabled());
                assertEquals(0,server.executions);
                trial.onActivity(a->a.findViewById(R.id.heating_prepare).performClick());
                waitTrial(trial,a->confirmation(a)!=null);
                assertEquals(0,server.executions);
                trial.onActivity(a->confirmation(a).performClick());
                waitTrial(trial,a->((android.widget.TextView)a.findViewById(R.id.heating_message)).getText().toString().equals(a.getString(R.string.heating_trial_confirmed)));
                assertEquals(1,server.executions);
                trial.recreate();waitTrial(trial,a->a.findViewById(R.id.heating_prepare).isEnabled());assertEquals(1,server.executions);
                trial.onActivity(a->a.findViewById(R.id.heating_prepare).performClick());waitTrial(trial,a->confirmation(a)!=null);
                trial.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
                trial.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
                waitTrial(trial,a->a.findViewById(R.id.heating_prepare).isEnabled());
                trial.onActivity(a->assertNull(confirmation(a)));assertEquals(1,server.executions);
                field.set(null,previous);
            }
            server.check();
        }finally{field.set(null,previous);}
    }
    private static android.widget.Button confirmation(HeatingTrialActivity activity){
        try{java.lang.reflect.Field field=HeatingTrialActivity.class.getDeclaredField("confirmation");field.setAccessible(true);android.app.AlertDialog dialog=(android.app.AlertDialog)field.get(activity);return dialog!=null&&dialog.isShowing()?dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE):null;}catch(Exception e){throw new AssertionError(e);}
    }
    private static void waitTrial(ActivityScenario<HeatingTrialActivity> trial,java.util.function.Predicate<HeatingTrialActivity> condition)throws Exception{
        TouchRegression.until(()->{java.util.concurrent.atomic.AtomicBoolean yes=new java.util.concurrent.atomic.AtomicBoolean();trial.onActivity(a->yes.set(condition.test(a)));return yes.get();});
    }
    private static final class TrialServer implements AutoCloseable {
        final ServerSocket socket;final Thread worker;volatile int executions;volatile Throwable failure;volatile boolean closed;
        TrialServer()throws Exception{socket=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));worker=new Thread(this::serve,"synthetic-trial");worker.start();}
        void serve(){try{while(!closed)try(Socket connection=socket.accept()){
            BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8));String request=reader.readLine(),line;int length=0;String cookie="";
            while((line=reader.readLine())!=null&&!line.isEmpty()){String lower=line.toLowerCase(java.util.Locale.ROOT);if(lower.startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());if(lower.startsWith("cookie:"))cookie=line;}
            char[] input=new char[length];int read=0;while(read<length){int n=reader.read(input,read,length-read);if(n<0)throw new EOFException();read+=n;}
            assertTrue(cookie.contains("moli_android="));JSONObject body=length==0?new JSONObject():new JSONObject(new String(input));
            String path=request.split(" ")[1],extra="";JSONObject result=new JSONObject().put("autonomous",false);
            if(path.endsWith("/status")){result.put("revision",1).put("epoch",2).put("selection",new JSONObject().put("source_name","Synthetic thermostat").put("target_name","Synthetic outlet")).put("state","ready").put("demand","on").put("actual",false).put("collected_at",System.currentTimeMillis());}
            else if(path.endsWith("/prepare")){assertEquals(1,body.getInt("revision"));result.put("state","prepared").put("prepared",new JSONObject().put("fingerprint","synthetic-fingerprint").put("demand","on").put("expires_at",System.currentTimeMillis()+120000));}
            else if(path.endsWith("/execute")){assertTrue(cookie.contains("moli_session="));assertTrue(body.getBoolean("confirmed"));assertEquals(4,body.length());assertEquals("synthetic-fingerprint",body.getString("fingerprint"));executions++;result.put("state","confirmed");}
            else if(path.equals("/api/session")){if(request.startsWith("POST"))extra="Set-Cookie: moli_session="+"b".repeat(64)+"; HttpOnly; Path=/\r\n";}
            else if(path.endsWith("/cancel"))result.put("state","cancelled");else if(path.equals("/api/health"))result.put("name","moli-os");else if(path.equals("/api/devices")||path.equals("/api/snapshot"))result.put("devices",new JSONArray()).put("drivers",new JSONArray()).put("diagnostics",new JSONArray());else if(!request.startsWith("GET "))throw new AssertionError("Unexpected synthetic mutation: "+path);
            byte[] output=result.toString().getBytes(StandardCharsets.UTF_8);connection.getOutputStream().write(("HTTP/1.1 200 OK\r\n"+extra+"Content-Type: application/json\r\nContent-Length: "+output.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));connection.getOutputStream().write(output);
        }}catch(Throwable error){if(!closed)failure=error;}}
        void check(){if(failure!=null)throw new AssertionError("Synthetic native trial",failure);}
        public void close()throws Exception{closed=true;socket.close();worker.join(2000);check();}
    }
    private ObservationFixture(){}
}
