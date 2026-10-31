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

/** One code8 → code9 update and native form; no cloud or LAN traffic is started. */
@RunWith(AndroidJUnit4.class)
public final class Code9DeliveryTest {
    final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    final Context app=ins.getTargetContext();
    @org.junit.Rule public final org.junit.rules.TestWatcher failureCapture=new org.junit.rules.TestWatcher(){
        @Override protected void failed(Throwable error,org.junit.runner.Description description){try{capture("failure");}catch(Exception ignored){}}
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
        File dir=new File(app.getFilesDir(),"code9-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    }

    private View tag(String value){return current().getWindow().getDecorView().findViewWithTag(value);}
    private void clickTag(String value)throws Exception {
        waitUi(()->tag(value)!=null&&tag(value).isEnabled(),"Native action missing: "+value);
        main(()->{View v=tag(value);v.requestRectangleOnScreen(new android.graphics.Rect(0,-40,v.getWidth(),v.getHeight()+40),true);});
        ins.waitForIdleSync();main(()->tag(value).performClick());ins.waitForIdleSync();
    }
    private void pairedPresentation(int found)throws Exception {
        // Inject only the existing code39 aggregation examples into transient UI state.
        // No fake cloud, discovery, protocol server or production test hook.
        java.lang.reflect.Field screenField=ManagementActivity.class.getDeclaredField("screen");screenField.setAccessible(true);
        java.lang.reflect.Method render=ManagementActivity.class.getDeclaredMethod("render");render.setAccessible(true);
        main(()->{
            try {
                Object screen=screenField.get(current());Class<?> type=screen.getClass();
                java.lang.reflect.Field account=type.getDeclaredField("account"),identified=type.getDeclaredField("identified"),matches=type.getDeclaredField("matches");
                account.setAccessible(true);identified.setAccessible(true);matches.setAccessible(true);
                JSONArray items=new JSONArray(),rows=new JSONArray();
                for(int n=1;n<=4;n++) {
                    String uuid=String.format("%032x",n);items.put(new JSONObject().put("uuid",uuid).put("name","Account plug "+n));
                    if(n<=found)rows.put(new JSONObject().put("uuid",uuid).put("host","192.168.50."+(20+n)).put("result","matched"));
                }
                account.set(screen,new JSONObject().put("connected",true).put("items",items));identified.setBoolean(screen,true);
                MerossMatches value=(MerossMatches)matches.get(screen);value.hosts.clear();while(value.results.length()>0)value.results.remove(0);value.add(rows);
                assertEquals(found,value.plan(items).length());render.invoke(current());
            }catch(Exception error){throw new AssertionError(error);}
        });ins.waitForIdleSync();
    }
    @Test public void upgradeAndConfigureMerossWithoutTouchingExistingDataOrNetwork()throws Exception {
        assertEquals("io.moli.android.app.beta2.test",app.getPackageName());
        assertEquals(10,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        Code9UpgradeSeedTest.sameData(app);
        long began=SystemClock.uptimeMillis();
        ins.startActivitySync(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        Activity[] a={null};main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        click(R.id.navigation_integrations);clickTag("configure:existing-plug-1");
        waitUi(()->tag("field:host")!=null&&tag("field:host").isEnabled(),"Meross form did not settle");
        assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        pairedPresentation(4);
        main(()->{assertTrue(tag("meross:add-found").isEnabled());assertTrue(((TextView)tag("meross:match:"+String.format("%032x",4))).getText().toString().contains("192.168.50.24"));});
        capture("meross-paired-landscape");
        main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        waitUi(()->tag("meross:add-found")!=null,"Matched results lost after rotation");
        capture("meross-paired-portrait");
        pairedPresentation(3);
        main(()->assertTrue(((TextView)tag("meross:match:"+String.format("%032x",4))).getText().toString().contains(app.getString(R.string.meross_match_missing))));
        capture("meross-missing-portrait");
        clickTag("meross:manual");
        main(()->assertEquals("",((EditText)tag("field:host")).getText().toString()));
        // Reused code39 UUID/IP presentation fixtures only; no account, RPC, new simulator or Save.
        click(R.id.management_back);Code9UpgradeSeedTest.sameData(app);
        try(FileOutputStream out=new FileOutputStream(new File(app.getFilesDir(),"code9-captures/update-proof.txt"))){out.write(("code9 -> code9 corrective (versionCode 10), identical config/key/home/automations/files; UI and rotation verified in "+(SystemClock.uptimeMillis()-began)+" ms\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
}
