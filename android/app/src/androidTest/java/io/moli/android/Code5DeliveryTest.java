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

/** One code4 → code5 upgrade: native navigation, both official forms and actual embedded Web. */
@RunWith(AndroidJUnit4.class)
public final class Code5DeliveryTest {
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
        File dir=new File(app.getFilesDir(),"code5-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    }
    private void service(String action)throws Exception {
        Intent intent=new Intent(app,MoliService.class);if(action!=null)intent.setAction(action);
        app.startForegroundService(intent);Beta2JourneyTest.waitState(action==null ? EngineHost.State.RUNNING : EngineHost.State.STOPPED);
    }
    private SharedPreferences proof(){return app.getSharedPreferences("code5-update-proof",Context.MODE_PRIVATE);}
    private void sameData()throws Exception {
        ConfigStore store=new ConfigStore(app);
        assertEquals(proof().getString("config","missing"),Beta2JourneyTest.digest(store.read()));
        assertEquals(proof().getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        for(String file:new String[]{"home.json","automations.json"})assertEquals(file,proof().getString(file,"missing"),Beta2JourneyTest.digest(store.disk.read(file,ConfigurationTransaction.FILE_LIMIT)));
    }
    @Test public void seedOnRealCode4()throws Exception {
        assertEquals(4,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        assertFalse(ProtectedKey.exists(app));ProtectedKey.create(app,"482913");
        service(null);service(MoliService.STOP);
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);
            store.save(store.read(),"weather-fixture",new JSONObject().put("lat","0").put("lon","0"));
            // Explicit presentation choices avoid a network-dependent binding/restart
            // during navigation. Missing-air association is covered by the JVM fixture.
            store.disk.write("home.json","{\"title\":\"Fixture home\",\"outdoor\":{\"weather\":\"existing-owner-choice\",\"air\":\"existing-air-choice\"},\"preserve\":{\"nested\":[1,true]}}\n");
            JSONArray rules=new JSONArray();
            for(int i=0;i<4;i++)rules.put(new JSONObject().put("kind","time").put("after","07:00").put("before","22:00").put("days",new JSONArray("[1,2,3,4,5,6,7]")));
            JSONArray nodes=new JSONArray().put(new JSONObject().put("id","start").put("type","manual").put("x",0).put("y",0))
                .put(new JSONObject().put("id","condition").put("type","if").put("all",true).put("rules",rules).put("x",300).put("y",0));
            JSONObject graph=new JSONObject().put("nodes",nodes).put("edges",new JSONArray().put(new JSONObject().put("from","start").put("port","out").put("to","condition")));
            JSONObject automation=new JSONObject().put("id","code4-layout-fixture").put("name","Condition de test code4").put("enabled",false).put("graph",graph);
            store.disk.write("automations.json",new JSONObject().put("automations",new JSONArray().put(automation)).toString(2)+"\n");return null;
        }).get(30,TimeUnit.SECONDS);
        service(null);
        JSONObject fixture=new JSONObject(Beta2JourneyTest.get("/api/automations/code4-layout-fixture",Beta2JourneyTest.cookie()).text);
        assertFalse(fixture.getJSONObject("automation").getBoolean("enabled"));assertFalse(fixture.getBoolean("live"));
        ConfigStore store=new ConfigStore(app);
        assertTrue(proof().edit().putString("config",Beta2JourneyTest.digest(store.read()))
            .putString("key",Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")))
            .putString("home.json",Beta2JourneyTest.digest(store.disk.read("home.json",ConfigurationTransaction.FILE_LIMIT)))
            .putString("automations.json",Beta2JourneyTest.digest(store.disk.read("automations.json",ConfigurationTransaction.FILE_LIMIT)))
            .putString("oldIndex",Beta2JourneyTest.digest(Beta2JourneyTest.get("/",Beta2JourneyTest.cookie()).text)).commit());
        service(MoliService.STOP);System.out.println("Code4 fixture persisted: native config, wrapped-key access, home and disabled automation; no real command.");
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

    private void management()throws Exception {
        waitUi(()->current() instanceof ManagementActivity && current().hasWindowFocus(),"Management not resumed");
    }
    private void select(String profile)throws Exception {
        waitUi(()->current().getWindow().getDecorView().findViewWithTag(profile)!=null,"Official catalogue entry absent: "+profile);
        main(()->current().getWindow().getDecorView().findViewWithTag(profile).performClick());
        waitUi(()->current().findViewById(R.id.variable_lat)!=null,"Official variables form missing");
    }
    private String config()throws Exception {return EngineHost.inspect(()->new ConfigStore(app).read()).get(15,TimeUnit.SECONDS);}
    private void webReady()throws Exception {
        waitUi(()->current() instanceof MainActivity && ((MainActivity)current()).web.isShown(),"Dashboard host not ready");
        waitJs("!!document.querySelector('.maison .rail')","Official dashboard did not render");
        main(()->assertNull("Native selector banner remains",current().findViewById(R.id.apk_home_return)));
    }
    private void back()throws Exception {main(()->current().onBackPressed());ins.waitForIdleSync();}
    @Test public void upgradeNavigationOfficialProfilesAndPortraitRail()throws Exception {
        assertEquals(5,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());sameData();
        Intent launcher=app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        assertEquals(ManagementActivity.class.getName(),launcher.getComponent().getClassName());
        ins.startActivitySync(launcher);management();
        Activity[] first={null};main(()->first[0]=current());orient(first[0],ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        click(R.id.navigation_integrations);select("open-meteo");
        main(()->{
            assertEquals("0",((EditText)current().findViewById(R.id.variable_lat)).getText().toString());
            assertEquals("0",((EditText)current().findViewById(R.id.variable_lon)).getText().toString());
        });
        click(R.id.management_back);select("open-meteo-air");
        main(()->{
            ((EditText)current().findViewById(R.id.variable_lat)).setText("0");
            ((EditText)current().findViewById(R.id.variable_lon)).setText("0");
            EditText input=current().findViewById(R.id.variable_lon);input.requestFocus();
            current().getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        waitUi(()->current().getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()),"Air form keyboard did not open");
        main(()->{
            View save=current().findViewById(R.id.save_configuration);android.graphics.Rect box=new android.graphics.Rect();
            assertTrue(save.getGlobalVisibleRect(box));assertEquals(save.getHeight(),box.height());
            assertTrue(box.bottom<=current().getWindow().getDecorView().getHeight()-current().getWindow().getDecorView().getRootWindowInsets().getInsets(WindowInsets.Type.ime()).bottom);
        });
        main(()->first[0]=current());orient(first[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        main(()->assertEquals("0",((EditText)current().findViewById(R.id.variable_lon)).getText().toString()));
        click(R.id.advanced_options);
        // Profile ID collision must reject without changing the retained weather configuration.
        main(()->((EditText)current().findViewById(R.id.integration_instance)).setText("weather-fixture"));
        click(R.id.save_configuration);
        waitUi(()->current().findViewById(R.id.form_feedback).isShown() && current().findViewById(R.id.save_configuration).isEnabled(),"Conflict was not surfaced");
        assertEquals(proof().getString("config","missing"),Beta2JourneyTest.digest(config()));
        main(()->((EditText)current().findViewById(R.id.integration_instance)).setText("open-meteo-air"));
        click(R.id.save_configuration);
        waitUi(()->current().findViewById(R.id.catalogue_entry)!=null && current().findViewById(R.id.catalogue_entry).isEnabled(),"Air save did not return to catalogue");
        capture("portrait-integrations");
        String configured=config();
        JSONObject catalogue=ConfigStore.nativeCall(new JSONObject().put("action","catalogue").put("text",configured));
        JSONArray packages=catalogue.getJSONArray("packages");
        assertEquals(2,packages.length());
        assertEquals("weather-fixture",packages.getJSONObject(0).getJSONArray("instances").getJSONObject(0).getString("id"));
        assertEquals("open-meteo-air",packages.getJSONObject(1).getJSONArray("instances").getJSONObject(0).getString("id"));
        assertEquals("0",packages.getJSONObject(0).getJSONArray("instances").getJSONObject(0).getJSONObject("vars").getString("lat"));
        select("open-meteo-air");main(()->assertEquals("0",((EditText)current().findViewById(R.id.variable_lat)).getText().toString()));click(R.id.management_back);
        click(R.id.navigation_home);click(R.id.navigation_moli);Beta2JourneyTest.waitState(EngineHost.State.RUNNING);webReady();
        // Current code5 HTML/JS/CSS must be served by the engine, not merely present in source.
        String html=Beta2JourneyTest.get("/",Beta2JourneyTest.cookie()).text;
        assertEquals(InstrumentationRegistry.getArguments().getString("expectedIndex"),Beta2JourneyTest.digest(html));
        assertNotEquals(proof().getString("oldIndex","missing"),Beta2JourneyTest.digest(html));
        for(String kind:new String[]{"Js","Css"}) {
            Matcher matcher=Pattern.compile("(?:src|href)=\"(/assets/[^\"]+\\."+(kind.equals("Js") ? "js" : "css")+")\"").matcher(html);assertTrue(matcher.find());
            assertEquals(InstrumentationRegistry.getArguments().getString("expected"+kind),Beta2JourneyTest.digest(Beta2JourneyTest.get(matcher.group(1),Beta2JourneyTest.cookie()).text));
        }
        // Two actual hash transitions: Android Back follows internal history before leaving home.
        js("(()=>{document.querySelector('.rail a[href=\"#/dehors\"]').click();return true})()");waitJs("location.hash==='#/dehors'","Dehors missing");
        js("(()=>{document.querySelector('.rail a[href=\"#/automatismes\"]').click();return true})()");waitJs("location.hash==='#/automatismes'","Automations missing");
        System.out.println("Web history before Android Back: "+js("({route:location.hash,length:history.length})"));
        back();waitJs("location.hash==='#/dehors'","Android Back did not preserve Web history");
        js("(()=>{document.querySelector('.rail a[href=\"#/\"]').click();return true})()");waitJs("location.hash==='#/'","Home missing");back();management();
        click(R.id.navigation_moli);webReady();
        // Portrait test includes the real three previously hidden actions in the single scrollport.
        main(()->first[0]=current());orient(first[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);webReady();
        waitJs("innerWidth<=760","Portrait responsive viewport missing");
        assertEquals("auto",new JSONTokener(js("getComputedStyle(document.querySelector('.rail')).overflowX")).nextValue().toString());
        tapDom(".rail .theme");
        tapDom(".rail a[href='#/systeme']");waitJs("!!document.querySelector('.systeme')","System destination missing");capture("portrait-rail-actions");
        tapDom(".rail a[href='#/atelier']");waitJs("!!document.querySelector('section.home')&&!document.querySelector('.maison')","Workshop destination missing");
        back();waitJs("!!document.querySelector('.systeme')","Back from Workshop failed");
        main(()->first[0]=current());orient(first[0],ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);webReady();
        assertEquals("auto",new JSONTokener(js("getComputedStyle(document.querySelector('.rail ul')).overflowY")).nextValue().toString());
        assertEquals("true",js("document.querySelector('.rail-foot').getBoundingClientRect().bottom<=innerHeight"));
        capture("landscape-full-dashboard");
        main(()->current().finish());management();
        service(MoliService.STOP);assertEquals(configured,config());service(null);assertEquals(configured,config());
        ConfigStore store=new ConfigStore(app);
        JSONObject home=new JSONObject(store.disk.read("home.json",ConfigurationTransaction.FILE_LIMIT));
        assertEquals("existing-owner-choice",home.getJSONObject("outdoor").getString("weather"));
        assertEquals("existing-air-choice",home.getJSONObject("outdoor").getString("air"));
        assertEquals("[1,true]",home.getJSONObject("preserve").getJSONArray("nested").toString());
        assertEquals(proof().getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        assertEquals(proof().getString("automations.json","missing"),Beta2JourneyTest.digest(store.disk.read("automations.json",ConfigurationTransaction.FILE_LIMIT)));
        main(()->current().finish());service(MoliService.STOP);
        System.out.println("PASS code4 → code5: data/key retained; weather+air official forms, conflict and restart; direct two-space navigation/back; actual current Web served and complete portrait rail. Air network readings not required or fabricated.");
    }
}
