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

/** Isolated code3 → code4 update, served Web identity and embedded WebView smoke. */
@RunWith(AndroidJUnit4.class)
public final class Code4WebIntegrationTest {
    final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    final Context app=ins.getTargetContext();
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
        File dir=new File(app.getFilesDir(),"code4-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    }
    private void service(String action)throws Exception {
        Intent intent=new Intent(app,MoliService.class);if(action!=null)intent.setAction(action);
        app.startForegroundService(intent);Beta2JourneyTest.waitState(action==null ? EngineHost.State.RUNNING : EngineHost.State.STOPPED);
    }
    private SharedPreferences proof(){return app.getSharedPreferences("code4-update-proof",Context.MODE_PRIVATE);}
    private void sameData()throws Exception {
        ConfigStore store=new ConfigStore(app);
        assertEquals(proof().getString("config","missing"),Beta2JourneyTest.digest(store.read()));
        assertEquals(proof().getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        for(String file:new String[]{"home.json","automations.json"})assertEquals(file,proof().getString(file,"missing"),Beta2JourneyTest.digest(store.disk.read(file,ConfigurationTransaction.FILE_LIMIT)));
    }
    @Test public void seedOnRealCode3()throws Exception {
        assertEquals(3,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        assertFalse(ProtectedKey.exists(app));ProtectedKey.create(app,"482913");
        service(null);service(MoliService.STOP);
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);
            store.save(store.read(),"weather-fixture",new JSONObject().put("lat","0").put("lon","0"));
            store.disk.write("home.json","{\"title\":\"Fixture home\",\"outdoor\":{\"weather\":\"existing-owner-choice\",\"air\":\"air-fixture\"},\"preserve\":{\"nested\":[1,true]}}\n");
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
        service(MoliService.STOP);System.out.println("Code3 fixture persisted: native config, wrapped-key access, home and disabled automation; no real command.");
    }
    private String js(String expression)throws Exception {
        CountDownLatch latch=new CountDownLatch(1);String[] raw={null};
        main(()->((MainActivity)current()).web.evaluateJavascript("JSON.stringify("+expression+")",v->{raw[0]=v;latch.countDown();}));
        assertTrue("WebView callback timeout",latch.await(5,TimeUnit.SECONDS));
        return new JSONTokener(raw[0]==null ? "null" : raw[0]).nextValue().toString();
    }
    private void waitJs(String expression,String message)throws Exception {
        long end=SystemClock.uptimeMillis()+30000;
        while(SystemClock.uptimeMillis()<end){if("true".equals(js(expression)))return;Thread.sleep(100);}fail(message);
    }
    private void route(String route)throws Exception {
        main(()->((MainActivity)current()).web.loadUrl(EngineHost.origin()+"/#/"+route));
        waitJs("!!document.querySelector('.maison .rail')","Embedded official Web did not render");
    }
    private void tapDom(String selector)throws Exception {
        js("(()=>{document.querySelector("+JSONObject.quote(selector)+").scrollIntoView({block:'center',inline:'nearest'});return true})()");Thread.sleep(100);
        JSONObject box=new JSONObject(js("(()=>{const r=document.querySelector("+JSONObject.quote(selector)+").getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2,w:innerWidth}})()"));
        int[] origin=new int[2];float[] scale={0};
        main(()->{android.webkit.WebView web=((MainActivity)current()).web;web.getLocationOnScreen(origin);scale[0]=(float)(web.getWidth()/box.optDouble("w"));});
        float x=origin[0]+(float)box.getDouble("x")*scale[0],y=origin[1]+(float)box.getDouble("y")*scale[0];long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,x,y,0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{assertTrue(ins.getUiAutomation().injectInputEvent(down,true));assertTrue(ins.getUiAutomation().injectInputEvent(up,true));}finally{down.recycle();up.recycle();}
        ins.waitForIdleSync();
    }
    private void inspector(boolean portrait)throws Exception {
        route("automatismes/code4-layout-fixture");waitJs("!!document.querySelector('.node.logic')","Saved disabled fixture did not load");tapDom(".node.logic");
        waitJs("!!document.querySelector('.inspector')","Condition selection did not open Inspector");
        assertEquals("visible",new JSONTokener(js("getComputedStyle(document.querySelector('.inspector')).overflowY")).nextValue().toString());
        assertEquals("true",js("document.documentElement.scrollWidth<=innerWidth+1"));
        assertEquals(portrait ? "visible" : "auto",new JSONTokener(js("getComputedStyle(document.querySelector('.editor .side')).overflowY")).nextValue().toString());
        if(!portrait)assertEquals("true",js("Math.abs(document.querySelector('.inspector').getBoundingClientRect().width-420)<1"));
        js("(()=>{const side=document.querySelector('.editor .side');if(getComputedStyle(side).overflowY==='auto')side.scrollTop=side.scrollHeight;else window.scrollTo(0,document.documentElement.scrollHeight);return true})()");
        waitJs("(()=>{const e=document.querySelector('.inspector .remove'),r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return r.top>=0&&r.bottom<=innerHeight&&(e===hit||e.contains(hit))})()","Last Inspector action is cut off or covered");
        capture(portrait ? "inspector-portrait" : "inspector-landscape");
    }
    @Test public void updateUsesTheNewEmbeddedWebAndRetainsCode3Data()throws Exception {
        assertEquals(4,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());sameData();
        MainActivity first=(MainActivity)ins.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        orient(first,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);click(R.id.open_moli);Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        String html=Beta2JourneyTest.get("/",Beta2JourneyTest.cookie()).text;
        assertEquals(InstrumentationRegistry.getArguments().getString("expectedIndex"),Beta2JourneyTest.digest(html));
        assertNotEquals(proof().getString("oldIndex","missing"),Beta2JourneyTest.digest(html));
        for(String kind:new String[]{"Js","Css"}){
            Matcher matcher=Pattern.compile("(?:src|href)=\"(/assets/[^\"]+\\."+(kind.equals("Js") ? "js" : "css")+")\"").matcher(html);assertTrue("Asset reference missing: "+kind,matcher.find());
            Beta2JourneyTest.Reply asset=Beta2JourneyTest.get(matcher.group(1),Beta2JourneyTest.cookie());assertEquals(200,asset.code);
            assertEquals("Served asset differs: "+kind,InstrumentationRegistry.getArguments().getString("expected"+kind),Beta2JourneyTest.digest(asset.text));
        }
        waitUi(()->{String url=((MainActivity)current()).web.getUrl();return url!=null&&url.contains("?view=");},"Native WebView authentication did not settle");
        route("automatismes/code4-layout-fixture");waitJs("!!document.querySelector('.editor')","Editor did not render");
        assertEquals("auto",new JSONTokener(js("getComputedStyle(document.querySelector('.rail ul')).overflowY")).nextValue().toString());
        js("(()=>{window.scrollTo(0,60);const rail=document.querySelector('.rail ul');rail.scrollTop=rail.scrollHeight;return true})()");
        assertEquals("true",js("scrollY>0&&document.querySelector('.rail ul').scrollTop>0"));
        double rail=Double.parseDouble(js("document.querySelector('.rail ul').scrollTop"));
        js("(()=>{document.querySelector('.rail a[href=\"#/dehors\"]').click();return true})()");
        waitJs("!!document.querySelector('.dehors')&&scrollY===0","New section did not start at the top");
        assertEquals(rail,Double.parseDouble(js("document.querySelector('.rail ul').scrollTop")),1);capture("rail-navigation");
        inspector(false);
        MainActivity[] current={null};main(()->current[0]=(MainActivity)current());orient(current[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        waitUi(()->{String url=((MainActivity)current()).web.getUrl();return url!=null&&url.contains("?view=");},"Rotated WebView authentication did not settle");inspector(true);
        sameData();assertFalse(new JSONObject(Beta2JourneyTest.get("/api/automations/code4-layout-fixture",Beta2JourneyTest.cookie()).text).getBoolean("live"));
        main(()->current().finish());service(MoliService.STOP);sameData();
        System.out.println("Code3 to code4 update passed: new HTML/JS/CSS served, fixed navigation/Inspector rendered, original config/key/home/automation retained.");
    }
}
