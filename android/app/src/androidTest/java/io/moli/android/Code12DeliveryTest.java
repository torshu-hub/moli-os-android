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

/** One versionCode 11 → 12 update and native account flow; no cloud or LAN traffic is started. */
@RunWith(AndroidJUnit4.class)
public final class Code12DeliveryTest {
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
        File dir=new File(app.getFilesDir(),"code12-captures");dir.mkdirs();
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
    @Test public void upgradeIntegrationFilesAndNetworkPreserveFourPlugs()throws Exception {
        assertEquals(12,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        Code12UpgradeSeedTest.sameData(app);
        ins.startActivitySync(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        click(R.id.navigation_integrations);
        waitUi(()->tag("meross-mss310")!=null,"Catalogue missing");
        main(()->{assertEquals(1,countTag(current().getWindow().getDecorView(),"package:meross-mss310"));assertNull(tag("configure:existing-plug-1"));});
        capture("integrations-landscape");clickTag("meross-mss310");
        waitUi(()->tag("integration:files")!=null&&tag("configure:existing-plug-4")!=null,"Integration detail missing");
        capture("meross-devices-landscape");
        clickTag("configure:existing-plug-1");
        main(()->{assertEquals("Configured plug 1",((EditText)tag("field:name")).getText().toString());assertEquals("192.168.50.21",((EditText)tag("field:host")).getText().toString());});
        click(R.id.management_back);clickTag("integration:files");
        waitUi(()->tag("integration:file-text")!=null,"Files not loaded");
        main(()->{
            String text=((TextView)tag("integration:file-text")).getText().toString();
            assertTrue(text.contains("existing-plug-1"));assertTrue(text.contains("existing-plug-4"));assertFalse(text.contains("printer-fixture"));
            assertTrue(text.contains("integration.toml"));assertTrue(text.contains("profile.toml"));assertTrue(text.contains("[[request]]"));
            assertFalse(tag("integration:file-text") instanceof EditText);
        });
        capture("meross-files-landscape");Activity[] a={null};main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        waitUi(()->tag("integration:file-text")!=null,"Files lost after rotation");capture("meross-files-portrait");
        click(R.id.management_back);click(R.id.management_back);clickTag("host");clickTag("integration:files");
        waitUi(()->tag("integration:file-text")!=null,"Native package files unavailable");
        main(()->{String text=((TextView)tag("integration:file-text")).getText().toString();assertTrue(text.contains(app.getString(R.string.configuration_native)));assertTrue(text.contains(app.getString(R.string.configuration_no_instance)));});
        click(R.id.navigation_network);
        waitUi(()->current().findViewById(R.id.network_results)!=null,"Network page unavailable");
        main(()->{
            ManagementActivity management=(ManagementActivity)current();
            management.networkPanel.state.results.add(new NetworkEvidence.Entry("192.168.50.21",0,"",NetworkEvidence.Kind.PRESENCE,NetworkEvidence.Proof.ECHO,"",""));
            management.networkPanel.state.results.add(new NetworkEvidence.Entry("192.168.50.77",0,"tablet.local",NetworkEvidence.Kind.PRESENCE,NetworkEvidence.Proof.PTR,"",""));
            try {java.lang.reflect.Method render=ManagementActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(management);}catch(Exception error){throw new AssertionError(error);}
        });
        waitUi(()->tag("network-ip:192.168.50.21")!=null,"Configured address was filtered");
        main(()->{
            assertEquals(2,((ManagementActivity)current()).networkPanel.state.results.size());
            assertTrue(tag("network-ip:192.168.50.21").getContentDescription().toString().contains("Configured plug 1"));
            assertNotNull(tag("network-ip:192.168.50.77"));
        });
        capture("network-portrait");
        Code12UpgradeSeedTest.sameData(app);
        File dir=new File(app.getFilesDir(),"code12-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,"update-proof.txt"))){out.write("code11 -> code12; four disabled existing MSS310; config, data and master key unchanged; integration hierarchy, files, rotation and discovered-host association checked; no real account or LAN calls\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
}
