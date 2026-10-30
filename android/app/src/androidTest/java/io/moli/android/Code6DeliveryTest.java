package io.moli.android;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import org.json.*;
import java.util.regex.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** One code5 → code6 upgrade: actual catalogue/instance activation and unified embedded rail. */
@RunWith(AndroidJUnit4.class)
public final class Code6DeliveryTest {
    final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    final Context app=ins.getTargetContext();
    @org.junit.Rule public final org.junit.rules.TestWatcher failureCapture=new org.junit.rules.TestWatcher(){
        @Override protected void failed(Throwable error,org.junit.runner.Description description){
            try{capture("failure");}catch(Exception ignored){/* Keep the original assertion. */}
        }
    };
    private void main(Runnable r){ins.runOnMainSync(r);}
    private void waitUi(BooleanSupplier condition,String message)throws Exception {
        long end=SystemClock.uptimeMillis()+20000;
        while(SystemClock.uptimeMillis()<end){boolean[] ok={false};main(()->{try{ok[0]=condition.getAsBoolean();}catch(java.util.NoSuchElementException transition){}});if(ok[0])return;Thread.sleep(100);}fail(message);
    }
    private Activity current(){return ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).iterator().next();}
    private void orient(Activity a,int value)throws Exception {
        int desired=value==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? android.content.res.Configuration.ORIENTATION_PORTRAIT : android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        boolean changed=a.getResources().getConfiguration().orientation!=desired;
        main(()->a.setRequestedOrientation(value));
        waitUi(()->{
            for(Activity active:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if(active.getClass()==a.getClass() && (!changed || active!=a) && active.getResources().getConfiguration().orientation==desired && active.hasWindowFocus())return true;
            return false;
        },"Orientation did not settle");
        ins.waitForIdleSync();Thread.sleep(800);
    }
    private void click(int id)throws Exception {
        waitUi(()->{View v=current().findViewById(id);return v!=null&&v.isShown()&&v.isEnabled();},"Native action missing: "+id);
        main(()->current().findViewById(id).performClick());ins.waitForIdleSync();
    }
    private void capture(String name)throws Exception {
        CountDownLatch frame=new CountDownLatch(1);
        main(()->{View decor=current().getWindow().getDecorView();decor.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);decor.invalidate();});
        assertTrue(frame.await(5,TimeUnit.SECONDS));Thread.sleep(150);
        File dir=new File(app.getFilesDir(),"code6-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    }
    private void service(String action)throws Exception {
        Intent intent=new Intent(app,MoliService.class);if(action!=null)intent.setAction(action);
        app.startForegroundService(intent);Beta2JourneyTest.waitState(action==null ? EngineHost.State.RUNNING : EngineHost.State.STOPPED);
    }
    private SharedPreferences proof(){return app.getSharedPreferences("code6-update-proof",Context.MODE_PRIVATE);}
    private void sameData()throws Exception {
        ConfigStore store=new ConfigStore(app);
        assertEquals(proof().getString("config","missing"),Beta2JourneyTest.digest(store.read()));
        assertEquals(proof().getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        for(String file:new String[]{"home.json","automations.json"})assertEquals(file,proof().getString(file,"missing"),Beta2JourneyTest.digest(store.disk.read(file,ConfigurationTransaction.FILE_LIMIT)));
    }

    @Test public void seedOnRealCode5()throws Exception {
        assertEquals(5,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        assertFalse(ProtectedKey.exists(app));ProtectedKey.create(app,"482913");
        service(null);service(MoliService.STOP);
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);JSONObject vars=new JSONObject().put("lat","0").put("lon","0");
            store.save(store.read(),"weather-one",vars);store.save(store.read(),"weather-two",vars);
            store.save(store.read(),"open-meteo-air","air-fixture",vars);
            String before=store.read();
            assertTrue(before.contains("id = \"weather-two\""));
            String candidate=before.replace("id = \"weather-two\"","id = \"weather-two\"\nenabled = false # retained off")
                .replace("id = \"air-fixture\"","id = \"air-fixture\"\nenabled = true # retained on")
                +"\n# native driver fixture; reads only local system information\n[[driver]]\nid = \"host-fixture\"\nkind = \"host\"\nenabled = false # retained host\n";
            store.disk.write("seed.toml",candidate);
            try{ConfigStore.nativeCall(new JSONObject().put("action","validate").put("path",new File(store.directory,"seed.toml").getAbsolutePath()));}
            finally{store.disk.remove("seed.toml");}
            store.transaction.commitExpected(before,candidate);
            store.disk.write("home.json","{\"outdoor\":{\"weather\":\"owner-weather-choice\",\"air\":\"owner-air-choice\"},\"preserve\":{\"nested\":[1,true]}}\n");
            JSONArray rules=new JSONArray();
            for(int i=0;i<4;i++)rules.put(new JSONObject().put("kind","time").put("after","07:00").put("before","22:00").put("days",new JSONArray("[1,2,3,4,5,6,7]")));
            JSONArray nodes=new JSONArray().put(new JSONObject().put("id","start").put("type","manual").put("x",0).put("y",0))
                .put(new JSONObject().put("id","condition").put("type","if").put("all",true).put("rules",rules).put("x",300).put("y",0));
            JSONObject graph=new JSONObject().put("nodes",nodes).put("edges",new JSONArray().put(new JSONObject().put("from","start").put("port","out").put("to","condition")));
            JSONObject automation=new JSONObject().put("id","code6-inert-fixture").put("name","Condition de test code6").put("enabled",false).put("graph",graph);
            store.disk.write("automations.json",new JSONObject().put("automations",new JSONArray().put(automation)).toString(2)+"\n");
            return null;
        }).get(30,TimeUnit.SECONDS);
        service(null);ConfigStore store=new ConfigStore(app);
        assertTrue(proof().edit().putString("config",Beta2JourneyTest.digest(store.read()))
            .putString("key",Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")))
            .putString("home.json",Beta2JourneyTest.digest(store.disk.read("home.json",ConfigurationTransaction.FILE_LIMIT)))
            .putString("automations.json",Beta2JourneyTest.digest(store.disk.read("automations.json",ConfigurationTransaction.FILE_LIMIT)))
            .putString("oldIndex",Beta2JourneyTest.digest(Beta2JourneyTest.get("/",Beta2JourneyTest.cookie()).text)).commit());
        service(MoliService.STOP);
    }
    private String js(String expression)throws Exception {
        CountDownLatch latch=new CountDownLatch(1);String[] raw={null};
        main(()->((MainActivity)current()).web.evaluateJavascript("JSON.stringify("+expression+")",v->{raw[0]=v;latch.countDown();}));
        assertTrue("WebView callback timeout",latch.await(5,TimeUnit.SECONDS));
        return new JSONTokener(raw[0]==null ? "null" : raw[0]).nextValue().toString();
    }
    private void waitJs(String expression,String message)throws Exception {
        long end=SystemClock.uptimeMillis()+30000;
        while(SystemClock.uptimeMillis()<end){if("true".equals(js(expression)))return;Thread.sleep(100);}
        fail(message+"; state="+EngineHost.state()+"; lease="+EngineHost.currentLease()+"; "+js("({href:location.href,viewport:[innerWidth,innerHeight]})"));
    }
    private void route(String route)throws Exception {
        main(()->((MainActivity)current()).web.loadUrl(EngineHost.origin()+"/#/"+route));
        waitJs("!!document.querySelector('.maison .rail')","Embedded official Web did not render");
    }
    private void tapDom(String selector)throws Exception {
        js("(()=>{document.querySelector("+JSONObject.quote(selector)+").scrollIntoView({block:'center',inline:'nearest'});return true})()");Thread.sleep(100);
        assertEquals("Touch target covered: "+selector,"true",js("(()=>{const e=document.querySelector("+JSONObject.quote(selector)+"),r=e.getBoundingClientRect(),h=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return e===h||e.contains(h)})()"));
        JSONObject box=new JSONObject(js("(()=>{const r=document.querySelector("+JSONObject.quote(selector)+").getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2,w:innerWidth}})()"));
        int[] origin=new int[2];float[] scale={0};
        main(()->{android.webkit.WebView web=((MainActivity)current()).web;web.getLocationOnScreen(origin);scale[0]=(float)(web.getWidth()/box.optDouble("w"));});
        float x=origin[0]+(float)box.getDouble("x")*scale[0],y=origin[1]+(float)box.getDouble("y")*scale[0];long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,x,y,0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{assertTrue(ins.getUiAutomation().injectInputEvent(down,true));assertTrue(ins.getUiAutomation().injectInputEvent(up,true));}finally{down.recycle();up.recycle();}
        ins.waitForIdleSync();
    }


    private String config()throws Exception{return EngineHost.inspect(()->new ConfigStore(app).read()).get(15,TimeUnit.SECONDS);}
    private JSONObject catalogue()throws Exception{return ConfigStore.nativeCall(new JSONObject().put("action","catalogue").put("text",config()));}
    private JSONObject packageFor(JSONObject catalogue,String profile)throws Exception{
        JSONArray packages=catalogue.getJSONArray("packages");for(int i=0;i<packages.length();i++)if(profile.equals(packages.getJSONObject(i).getJSONObject("manifest").getJSONObject("integration").getString("id")))return packages.getJSONObject(i);throw new AssertionError(profile);
    }
    private boolean enabled(String profile,String id)throws Exception{
        JSONArray instances=packageFor(catalogue(),profile).getJSONArray("instances");for(int i=0;i<instances.length();i++)if(id.equals(instances.getJSONObject(i).getString("id")))return instances.getJSONObject(i).getBoolean("enabled");throw new AssertionError(id);
    }
    private View tag(String value){return current().getWindow().getDecorView().findViewWithTag(value);}
    private void clickTag(String value)throws Exception{
        waitUi(()->tag(value)!=null&&tag(value).isEnabled(),"Native action unavailable: "+value);
        main(()->{View v=tag(value);v.requestRectangleOnScreen(new android.graphics.Rect(0,-40,v.getWidth(),v.getHeight()),true);});
        ins.waitForIdleSync();main(()->tag(value).performClick());ins.waitForIdleSync();
    }
    private void settled(String id)throws Exception{
        waitUi(()->tag("toggle:"+id)!=null&&tag("toggle:"+id).isEnabled()&&current().findViewById(R.id.form_feedback).getVisibility()==View.GONE,"Activation was not read back");
    }
    private void webReady()throws Exception{
        waitUi(()->current() instanceof MainActivity && ((MainActivity)current()).web.isShown(),"Dashboard missing");
        waitJs("!!document.querySelector('.maison .rail ul')","Official Web did not render");
    }
    private boolean publishedDriver(String id)throws Exception{
        JSONArray drivers=new JSONObject(Beta2JourneyTest.get("/api/health",Beta2JourneyTest.cookie()).text).getJSONArray("drivers");
        for(int i=0;i<drivers.length();i++)if(id.equals(drivers.getJSONObject(i).getString("instance")))return true;return false;
    }
    private void retainUserData()throws Exception{
        ConfigStore store=new ConfigStore(app);
        assertEquals(proof().getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        for(String file:new String[]{"home.json","automations.json"})assertEquals(file,proof().getString(file,"missing"),Beta2JourneyTest.digest(store.disk.read(file,ConfigurationTransaction.FILE_LIMIT)));
    }
    @Test public void upgradeGroupedCatalogueActivationAndUnifiedRail()throws Exception{
        assertEquals(6,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());sameData();
        // The generic zero-parameter profile really passes native validation/storage;
        // it stays disabled, so this is not a provider/network acceptance test.
        EngineHost.maintain(app,()->{ConfigStore store=new ConfigStore(app);store.save(store.read(),"tempo","tempo-fixture",new JSONObject());store.setEnabled(store.read(),"tempo-fixture",false);return null;}).get(30,TimeUnit.SECONDS);
        ins.startActivitySync(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        Activity[] a={null};main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        click(R.id.navigation_integrations);
        waitUi(()->tag("package:open-meteo")!=null,"Catalogue not loaded");
        JSONObject inventory=catalogue();
        JSONArray packages=inventory.getJSONArray("packages");
        assertEquals(Integer.parseInt(InstrumentationRegistry.getArguments().getString("expectedPackages")),packages.length());
        main(()->{
            for(int i=0;i<packages.length();i++)try{assertNotNull(tag("package:"+packages.getJSONObject(i).getJSONObject("manifest").getJSONObject("integration").getString("id")));}catch(JSONException e){throw new AssertionError(e);}
            assertNotNull(tag("provider:Open-Meteo"));assertNotNull(tag("provider:"+app.getString(R.string.no_declared_brand)));assertNotNull(tag("provider:"+app.getString(R.string.multiple_brands)));
            assertFalse(tag("hue").isEnabled());assertFalse(tag("unconfigured-toggle:hue").isEnabled());
            assertFalse(tag("configure:host-fixture").isEnabled());assertTrue(tag("toggle:host-fixture").isEnabled());
            assertEquals(tag("configure:weather-one").getParent(),tag("toggle:weather-one").getParent());
        });
        assertTrue(enabled("open-meteo","weather-one"));assertFalse(enabled("open-meteo","weather-two"));assertTrue(enabled("open-meteo-air","air-fixture"));
        clickTag("configure:weather-one");main(()->assertEquals("0",((EditText)current().findViewById(R.id.variable_lat)).getText().toString()));click(R.id.management_back);
        clickTag("configure:air-fixture");main(()->assertEquals("0",((EditText)current().findViewById(R.id.variable_lon)).getText().toString()));click(R.id.management_back);
        clickTag("toggle:weather-one");settled("weather-one");assertFalse(enabled("open-meteo","weather-one"));assertFalse(enabled("open-meteo","weather-two"));
        clickTag("toggle:weather-two");settled("weather-two");assertTrue(enabled("open-meteo","weather-two"));assertFalse(enabled("open-meteo","weather-one"));
        String before=config(),concurrent=before+"\n# concurrent fixture edit retained\n";
        EngineHost.maintain(app,()->{new ConfigStore(app).transaction.commitExpected(before,concurrent);return null;}).get(15,TimeUnit.SECONDS);
        clickTag("toggle:host-fixture");
        waitUi(()->{TextView error=current().findViewById(R.id.form_feedback);return error!=null&&error.getVisibility()==View.VISIBLE&&error.getText().toString().equals(app.getString(R.string.configuration_conflict));},"Stale snapshot was not rejected clearly");
        assertEquals(concurrent,config());assertFalse(enabled("host","host-fixture"));
        click(R.id.navigation_integrations);settled("host-fixture");
        click(R.id.navigation_home);click(R.id.engine_action);Beta2JourneyTest.waitState(EngineHost.State.RUNNING);click(R.id.navigation_integrations);settled("host-fixture");
        assertFalse(publishedDriver("host-fixture"));long lease=EngineHost.currentLease();
        clickTag("toggle:host-fixture");settled("host-fixture");Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        assertTrue(EngineHost.currentLease()>lease);assertTrue(enabled("host","host-fixture"));assertTrue(publishedDriver("host-fixture"));
        clickTag("toggle:host-fixture");settled("host-fixture");Beta2JourneyTest.waitState(EngineHost.State.RUNNING);assertFalse(publishedDriver("host-fixture"));
        String configured=config();assertTrue(configured.contains("# concurrent fixture edit retained"));assertTrue(configured.contains("# retained off"));assertTrue(configured.contains("# retained on"));
        main(()->tag("package:open-meteo").requestRectangleOnScreen(new android.graphics.Rect(0,-50,tag("package:open-meteo").getWidth(),tag("package:open-meteo").getHeight()),true));capture("catalogue-landscape");
        main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);settled("weather-one");
        main(()->tag("package:open-meteo").requestRectangleOnScreen(new android.graphics.Rect(0,-50,tag("package:open-meteo").getWidth(),tag("package:open-meteo").getHeight()),true));capture("catalogue-portrait");
        click(R.id.navigation_moli);webReady();
        String html=Beta2JourneyTest.get("/",Beta2JourneyTest.cookie()).text;
        assertEquals(InstrumentationRegistry.getArguments().getString("expectedIndex"),Beta2JourneyTest.digest(html));assertNotEquals(proof().getString("oldIndex","missing"),Beta2JourneyTest.digest(html));
        for(String kind:new String[]{"Js","Css"}){Matcher matcher=Pattern.compile("(?:src|href)=\"(/assets/[^\"]+\\."+(kind.equals("Js")?"js":"css")+")\"").matcher(html);assertTrue(matcher.find());assertEquals(InstrumentationRegistry.getArguments().getString("expected"+kind),Beta2JourneyTest.digest(Beta2JourneyTest.get(matcher.group(1),Beta2JourneyTest.cookie()).text));}
        main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);webReady();
        waitJs("innerWidth<=760","Portrait viewport missing");
        assertEquals("true",js("document.querySelectorAll('.rail ul').length===1&&!document.querySelector('.rail-foot,.phone-foot')&&getComputedStyle(document.querySelector('.rail ul')).overflowX==='auto'"));
        tapDom(".rail .theme");tapDom(".rail a[href='#/systeme']");waitJs("!!document.querySelector('.systeme')","System access missing");
        js("(()=>{document.querySelector('.rail ul').scrollLeft=100000;return true})()");capture("rail-portrait");
        tapDom(".rail a[href='#/atelier']");waitJs("!!document.querySelector('section.home')&&!document.querySelector('.maison')","Workshop access missing");
        main(()->current().onBackPressed());waitJs("!!document.querySelector('.systeme')","Return from Workshop failed");
        main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);webReady();
        assertEquals("true",js("document.querySelectorAll('.rail ul').length===1&&!document.querySelector('.rail-foot,.phone-foot')&&getComputedStyle(document.querySelector('.rail ul')).overflowY==='auto'"));
        js("(()=>{const list=document.querySelector('.rail ul');list.scrollTop=list.scrollHeight;return true})()");
        assertEquals("true",js("(()=>{const e=document.querySelector('.rail .atelier'),r=e.getBoundingClientRect(),p=e.closest('ul').getBoundingClientRect();return r.top>=p.top&&r.bottom<=p.bottom+1})()"));capture("rail-landscape");
        retainUserData();assertEquals(configured,config());main(()->current().finish());
        service(MoliService.STOP);service(null);assertEquals(configured,config());retainUserData();service(MoliService.STOP);
        main(()->{for(Activity active:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))active.finish();});
        System.out.println("PASS: code5→code6 without erasure; full grouped official catalogue; honest editors; per-instance toggles and real restart/readback, stale conflict; existing data/guards retained; current embedded Web and one rail in both orientations. No physical command.");
    }
}
