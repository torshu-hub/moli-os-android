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

/** One code6 → code7 upgrade: actual catalogue/instance activation and unified embedded rail. */
@RunWith(AndroidJUnit4.class)
public final class Code7DeliveryTest {
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
        File dir=new File(app.getFilesDir(),"code7-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    }
    private void service(String action)throws Exception {
        Intent intent=new Intent(app,MoliService.class);if(action!=null)intent.setAction(action);
        app.startForegroundService(intent);Beta2JourneyTest.waitState(action==null ? EngineHost.State.RUNNING : EngineHost.State.STOPPED);
    }
    private SharedPreferences proof(){return app.getSharedPreferences("code7-update-proof",Context.MODE_PRIVATE);}
    private void sameData()throws Exception {
        ConfigStore store=new ConfigStore(app);
        assertEquals(proof().getString("config","missing"),Beta2JourneyTest.digest(store.read()));
        assertEquals(proof().getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        for(String file:new String[]{"home.json","automations.json"})assertEquals(file,proof().getString(file,"missing"),Beta2JourneyTest.digest(store.disk.read(file,ConfigurationTransaction.FILE_LIMIT)));
    }

    @Test public void seedOnRealCode6()throws Exception {
        assertEquals(6,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
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
            JSONObject automation=new JSONObject().put("id","code7-inert-fixture").put("name","Condition de test code6").put("enabled",false).put("graph",graph);
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
    private void retainUserData()throws Exception{
        ConfigStore store=new ConfigStore(app);
        assertEquals(proof().getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        for(String file:new String[]{"home.json","automations.json"})assertEquals(file,proof().getString(file,"missing"),Beta2JourneyTest.digest(store.disk.read(file,ConfigurationTransaction.FILE_LIMIT)));
    }
    private void textField(String name,String value)throws Exception {
        main(()->((EditText)tag("field:"+name)).setText(value));
    }
    private int rows() {
        int[] result={0};walk(current().getWindow().getDecorView(),v->{if(v.getTag() instanceof String && ((String)v.getTag()).startsWith("package:"))result[0]++;});return result[0];
    }
    private void walk(View view,java.util.function.Consumer<View> inspect) {
        inspect.accept(view);if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)walk(((android.view.ViewGroup)view).getChildAt(i),inspect);
    }
    private boolean containsText(String text) {
        boolean[] found={false};walk(current().getWindow().getDecorView(),v->{if(v instanceof TextView && ((TextView)v).getText().toString().contains(text))found[0]=true;});return found[0];
    }
    private void touchTag(String value)throws Exception {
        waitUi(()->tag(value)!=null&&tag(value).isEnabled(),"Native touch unavailable: "+value);
        main(()->{View v=tag(value);v.requestRectangleOnScreen(new android.graphics.Rect(0,0,v.getWidth(),v.getHeight()),true);});
        ins.waitForIdleSync();Thread.sleep(150);float[] xy=new float[2];
        main(()->{View v=tag(value);android.graphics.Rect box=new android.graphics.Rect();assertTrue(v.getGlobalVisibleRect(box));xy[0]=box.exactCenterX();xy[1]=box.exactCenterY();});
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,xy[0],xy[1],0),up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,xy[0],xy[1],0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{assertTrue(ins.getUiAutomation().injectInputEvent(down,true));assertTrue(ins.getUiAutomation().injectInputEvent(up,true));}finally{down.recycle();up.recycle();}ins.waitForIdleSync();
    }
    @Test public void upgradeCompactFormsAndPerInstanceSwitches()throws Exception {
        assertEquals(7,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());sameData();
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);store.save(store.read(),"tempo","tempo-fixture",new JSONObject());store.setEnabled(store.read(),"tempo-fixture",false);
            return null;
        }).get(30,TimeUnit.SECONDS);
        ins.startActivitySync(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        Activity[] a={null};main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        click(R.id.navigation_integrations);settled("weather-one");
        int count=packageFor(catalogue(),"open-meteo").getJSONArray("instances").length();assertEquals(2,count);
        main(()->{
            assertEquals(32,rows());assertNotNull(tag("provider:Open-Meteo"));
            assertFalse(tag("unconfigured-toggle:ipp").isEnabled());assertFalse(tag("unconfigured-toggle:hue").isEnabled());
            assertTrue(tag("toggle:weather-one") instanceof Switch);
            assertEquals(tag("configure:weather-one").getParent(),tag("toggle:weather-one").getParent());
            assertTrue(containsText(app.getString(R.string.setup_configured)));assertTrue(containsText(app.getString(R.string.setup_incomplete)));
        });
        touchTag("hue");ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);ins.waitForIdleSync();
        clickTag("filter:1");main(()->{assertNull(tag("package:hue"));assertNotNull(tag("package:ipp"));assertEquals(10,rows());});
        clickTag("filter:2");main(()->{assertNull(tag("package:ipp"));assertNotNull(tag("package:hue"));assertEquals(22,rows());});clickTag("filter:0");
        touchTag("configure:weather-one");main(()->{
            assertEquals("0",((EditText)current().findViewById(R.id.variable_lat)).getText().toString());
            assertNull(current().findViewById(R.id.advanced_options));assertNull(current().findViewById(R.id.integration_instance));
        });click(R.id.management_back);
        touchTag("configure:air-fixture");main(()->{assertEquals("0",((EditText)current().findViewById(R.id.variable_lon)).getText().toString());assertFalse(containsText("Même position"));});capture("air-form");click(R.id.management_back);
        touchTag("configure:tempo-fixture");main(()->assertNull(tag("field:host")));click(R.id.management_back);
        touchTag("toggle:weather-one");settled("weather-one");assertFalse(enabled("open-meteo","weather-one"));assertFalse(enabled("open-meteo","weather-two"));
        main(()->assertNotNull(tag("configure:weather-one"))); // switch did not open a form
        touchTag("toggle:weather-two");settled("weather-two");assertTrue(enabled("open-meteo","weather-two"));assertFalse(enabled("open-meteo","weather-one"));
        String before=config(),concurrent=before+"\n# concurrent fixture edit retained\n";
        EngineHost.maintain(app,()->{new ConfigStore(app).transaction.commitExpected(before,concurrent);return null;}).get(15,TimeUnit.SECONDS);
        touchTag("toggle:weather-two");
        waitUi(()->{TextView error=current().findViewById(R.id.form_feedback);return error!=null&&error.getVisibility()==View.VISIBLE&&error.getText().toString().equals(app.getString(R.string.configuration_conflict));},"Stale snapshot not rejected");
        assertEquals(concurrent,config());click(R.id.navigation_integrations);settled("weather-two");
        // Exercise the real simple-option form and rotation, not a network/device.
        touchTag("ipp");textField("host","192.0.2.1");textField("port","65536");
        String unchanged=config();click(R.id.save_configuration);
        waitUi(()->current().findViewById(R.id.form_feedback).getVisibility()==View.VISIBLE,"Invalid typed option not rejected");assertEquals(unchanged,config());
        textField("port","631");textField("path","/ipp/print");
        main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        main(()->{assertEquals("192.0.2.1",((EditText)tag("field:host")).getText().toString());assertEquals("631",((EditText)tag("field:port")).getText().toString());});capture("ipp-form-portrait");
        click(R.id.save_configuration);settled("ipp");
        assertTrue(packageFor(catalogue(),"ipp").getJSONArray("instances").getJSONObject(0).getBoolean("valid"));
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);store.setEnabled(store.read(),"ipp",false);
            for(String name:new String[]{"daikin-brp069","moonraker","klipper","solarman","sonos"}){
                JSONObject options=new JSONObject().put("host","192.0.2.1");if(name.equals("moonraker"))options.put("port","7125");
                if(name.equals("solarman"))options.put("serial","123456789").put("slave","1");
                store.save(store.read(),name,name+"-fixture",new JSONObject(),options);store.setEnabled(store.read(),name+"-fixture",false);
            }
            return null;
        }).get(30,TimeUnit.SECONDS);
        click(R.id.navigation_integrations);settled("weather-one");
        for(String name:new String[]{"daikin-brp069","moonraker","klipper","solarman","sonos"}){
            assertEquals(name,1,packageFor(catalogue(),name).getJSONArray("instances").length());
            assertTrue(packageFor(catalogue(),name).getJSONArray("instances").getJSONObject(0).getBoolean("valid"));
        }
        touchTag("configure:solarman-fixture");main(()->assertEquals("123456789",((EditText)tag("field:serial")).getText().toString()));capture("solarman-form-portrait");click(R.id.management_back);
        clickTag("filter:1");main(()->{View row=tag("package:open-meteo");row.requestRectangleOnScreen(new android.graphics.Rect(0,-40,row.getWidth(),row.getHeight()),true);});capture("catalogue-portrait");
        main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);settled("weather-one");
        main(()->{assertNull(tag("package:hue"));View row=tag("package:open-meteo");row.requestRectangleOnScreen(new android.graphics.Rect(0,-40,row.getWidth(),row.getHeight()),true);});capture("catalogue-landscape");
        clickTag("filter:0");main(()->((ScrollView)current().findViewById(R.id.management_scroll)).scrollTo(0,0));capture("catalogue-all-landscape");
        EngineHost.maintain(app,()->{ConfigStore store=new ConfigStore(app);for(String id:new String[]{"weather-two","air-fixture"})store.setEnabled(store.read(),id,false);return null;}).get(20,TimeUnit.SECONDS);
        String retained=config();service(null);
        String html=Beta2JourneyTest.get("/",Beta2JourneyTest.cookie()).text;
        assertEquals("Code6 Web unchanged",proof().getString("oldIndex","missing"),Beta2JourneyTest.digest(html));
        assertEquals(InstrumentationRegistry.getArguments().getString("expectedIndex"),Beta2JourneyTest.digest(html));
        for(String kind:new String[]{"Js","Css"}){Matcher matcher=Pattern.compile("(?:src|href)=\"(/assets/[^\"]+\\."+(kind.equals("Js")?"js":"css")+")\"").matcher(html);assertTrue(matcher.find());assertEquals(InstrumentationRegistry.getArguments().getString("expected"+kind),Beta2JourneyTest.digest(Beta2JourneyTest.get(matcher.group(1),Beta2JourneyTest.cookie()).text));}
        service(MoliService.STOP);assertEquals(retained,config());retainUserData();
        assertTrue(config().contains("# retained off"));assertTrue(config().contains("# concurrent fixture edit retained"));
    }
}
