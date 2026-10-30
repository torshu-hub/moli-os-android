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
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class Code3WeatherUpdateTest {
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
        File dir=new File(app.getFilesDir(),"code3-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    }
    @Test public void updateRepairsOnlyThePresentationAndShowsRealWeather()throws Exception {
        assertEquals(3,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        SharedPreferences proof=app.getSharedPreferences("code3-update-proof",Context.MODE_PRIVATE);
        ConfigStore store=new ConfigStore(app);
        assertEquals(proof.getString("config","missing"),Beta2JourneyTest.digest(store.read()));
        assertEquals(proof.getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        MainActivity first=(MainActivity)ins.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        orient(first,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);capture("apk-home-landscape");
        click(R.id.open_moli);
        String expected=proof.getString("device","missing");boolean linked=false;long end=System.currentTimeMillis()+75000;
        while(System.currentTimeMillis()<end) {
            if(EngineHost.state()==EngineHost.State.ERROR)fail("Engine failed during presentation reconciliation");
            if(EngineHost.state()==EngineHost.State.RUNNING)try {
                JSONObject home=new JSONObject(Beta2JourneyTest.get("/api/home",Beta2JourneyTest.cookie()).text);
                if(expected.equals(home.optJSONObject("outdoor").optString("weather"))){linked=true;break;}
            }catch(Exception retry){}
            Thread.sleep(200);
        }
        assertTrue("No automatic association after the code2 update",linked);
        Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        assertEquals(proof.getString("config","missing"),Beta2JourneyTest.digest(new ConfigStore(app).read()));
        assertEquals(proof.getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        JSONObject saved=new JSONObject(store.disk.read("home.json",ConfigurationTransaction.FILE_LIMIT));
        assertEquals("Fixture home",saved.getString("title"));assertEquals("air-fixture",saved.getJSONObject("outdoor").getString("air"));
        assertEquals(1,saved.getJSONObject("preserve").getJSONArray("nested").getInt(0));
        assertTrue(saved.getJSONObject("preserve").getJSONArray("nested").getBoolean(1));
        MainActivity[] dashboard={null};main(()->dashboard[0]=(MainActivity)current());
        waitUi(()->{String url=dashboard[0].web.getUrl();return url!=null&&url.contains("?view="+EngineHost.currentLease()+"_");},"Official WebView did not follow the host lease");
        main(()->dashboard[0].web.loadUrl(EngineHost.origin()+"/#/dehors"));
        boolean rendered=false;end=System.currentTimeMillis()+30000;
        while(System.currentTimeMillis()<end) {
            CountDownLatch latch=new CountDownLatch(1);String[] result={null};
            main(()->dashboard[0].web.evaluateJavascript("/[0-9]/.test(document.querySelector('.dehors .sky .temp')?.textContent || '')",v->{result[0]=v;latch.countDown();}));
            if(latch.await(3,TimeUnit.SECONDS)&&"true".equals(result[0])){rendered=true;break;}Thread.sleep(200);
        }
        assertTrue("The unchanged Dehors card still displays dashes instead of real temperature",rendered);
        capture("official-dehors-weather");
        main(()->assertEquals(app.getString(R.string.apk_home),((Button)dashboard[0].findViewById(R.id.apk_home_return)).getText().toString()));
        click(R.id.apk_home_return);orient(dashboard[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);capture("apk-home-portrait");
        click(R.id.open_management);
        Activity[] management={null};waitUi(()->{Activity a=current();if(a instanceof ManagementActivity){management[0]=a;return true;}return false;},"Management destination missing");
        orient(management[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);capture("engine-running-portrait");
        main(()->management[0]=current());orient(management[0],ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);capture("engine-running-landscape");
        click(R.id.engine_action);Beta2JourneyTest.waitState(EngineHost.State.STOPPED);
        waitUi(()->app.getString(R.string.stopped).equals(((TextView)current().findViewById(R.id.engine_status)).getText().toString()),"Stopped state not displayed");capture("engine-stopped");
        main(()->current().finish());
        System.out.println("Observed after update: existing coordinates/key/layout preserved; official Dehors shows numeric weather.");
    }
}
