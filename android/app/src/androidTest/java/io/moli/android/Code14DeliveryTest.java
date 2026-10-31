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

/** One code13 → code14 update; private account setup and durable recovery without Netatmo traffic. */
@RunWith(AndroidJUnit4.class)
public final class Code14DeliveryTest {
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
        File dir=new File(app.getFilesDir(),"code14-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    }

    private View tag(String value){return current().getWindow().getDecorView().findViewWithTag(value);}
    private void clickTag(String value)throws Exception {
        waitUi(()->tag(value)!=null&&tag(value).isEnabled(),"Native action missing: "+value);
        main(()->{View v=tag(value);v.requestRectangleOnScreen(new android.graphics.Rect(0,-40,v.getWidth(),v.getHeight()+40),true);});
        ins.waitForIdleSync();main(()->tag(value).performClick());ins.waitForIdleSync();
    }
    private int countTag(View view,String expected) {
        int n=expected.equals(view.getTag())?1:0;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)n+=countTag(((ViewGroup)view).getChildAt(i),expected);return n;
    }
    @Test public void upgradeNetatmoAndPreserveData()throws Exception {
        assertEquals(14,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        Code12UpgradeSeedTest.sameData(app);
        String canonical=new ConfigStore(app).read();
        ins.startActivitySync(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        click(R.id.navigation_integrations);clickTag("netatmo");
        waitUi(()->tag("netatmo:setup")!=null && tag("netatmo:setup").isEnabled(),"Netatmo setup missing");
        capture("netatmo-account-landscape");
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);
            JSONObject status=store.netatmo(new JSONObject().put("operation","status"));
            assertTrue(status.getBoolean("setup_required"));assertFalse(status.getBoolean("credential_present"));
            JSONObject application=new JSONObject().put("client_id","app-test").put("client_secret","synthetic-personal-secret").put("experimental",true).put("write_thermostat",true);
            JSONObject saved=store.netatmo(new JSONObject().put("operation","provision").put("consent",true).put("application",application));
            assertFalse(saved.getBoolean("setup_required"));assertFalse(saved.toString().contains("synthetic-personal-secret"));
            assertFalse(new String(java.nio.file.Files.readAllBytes(new File(store.directory,"secrets.enc").toPath()),java.nio.charset.StandardCharsets.ISO_8859_1).contains("synthetic-personal-secret"));
            assertEquals(canonical,store.read());
            JSONObject request=store.netatmo(new JSONObject().put("operation","begin").put("consent",true));
            android.net.Uri uri=android.net.Uri.parse(request.getString("authorization_url"));
            assertEquals("api.netatmo.com",uri.getHost());assertEquals("read_thermostat write_thermostat",uri.getQueryParameter("scope"));
            assertEquals("http://localhost/moli-netatmo",uri.getQueryParameter("redirect_uri"));
            store.netatmo(new JSONObject().put("operation","cancel"));return null;
        }).get(15,TimeUnit.SECONDS);
        clickTag("netatmo:refresh");waitUi(()->tag("netatmo:connect")!=null,"Stored application not reloaded");
        Activity[] a={null};main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        waitUi(()->tag("netatmo:connect")!=null,"Netatmo state lost on rotation");capture("netatmo-account-portrait");
        clickTag("integration:files");waitUi(()->tag("integration:file-text")!=null,"Netatmo manifest not viewable");
        main(()->assertTrue(((TextView)tag("integration:file-text")).getText().toString().contains("moli-netatmo")));
        assertEquals(canonical,new ConfigStore(app).read());
        assertEquals(app.getSharedPreferences(Code12UpgradeSeedTest.PREFS,Context.MODE_PRIVATE).getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        File dir=new File(app.getFilesDir(),"code14-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,"update-proof.txt"))){out.write("code13 -> code14; all existing synthetic files/config/master identical at update; four Meross instances retained; Netatmo account page/rotation/original manifest; explicit experimental read/write authorization URL only; no provider traffic or physical commands.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    @Test public void verifyDurableApplicationAfterProcessRestart()throws Exception {
        JSONObject status=EngineHost.inspect(()->new ConfigStore(app).netatmo(new JSONObject().put("operation","status"))).get(15,TimeUnit.SECONDS);
        assertFalse(status.getBoolean("setup_required"));assertEquals("app-test",status.getString("client_id"));assertFalse(status.getBoolean("credential_present"));
        assertEquals("idle",status.getString("state"));assertFalse(status.toString().contains("synthetic-personal-secret"));
        ins.startActivitySync(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        click(R.id.navigation_integrations);clickTag("netatmo");
        waitUi(()->tag("netatmo:connect")!=null,"Durable application lost with volatile UI state");
        main(()->assertNull(tag("netatmo:setup")));capture("netatmo-reloaded");
    }
}
