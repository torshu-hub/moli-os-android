package io.moli.android;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.*;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class Code20NavigationTest {
    final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    final Context app=ins.getTargetContext();
    interface Condition {boolean ok()throws Exception;}
    void waitFor(Condition condition)throws Exception{long end=android.os.SystemClock.uptimeMillis()+30000;while(android.os.SystemClock.uptimeMillis()<end){if(condition.ok())return;Thread.sleep(100);}fail("navigation condition timed out");}
    <T>T ui(Callable<T> call)throws Exception{AtomicReference<T> result=new AtomicReference<>();AtomicReference<Exception> error=new AtomicReference<>();ins.runOnMainSync(()->{try{result.set(call.call());}catch(Exception e){error.set(e);}});if(error.get()!=null)throw error.get();return result.get();}
    <T extends Activity>T resumed(Class<T> type)throws Exception{return ui(()->{for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(type.isInstance(a))return type.cast(a);return null;});}
    String js(MainActivity a,String script)throws Exception{CountDownLatch ready=new CountDownLatch(1);AtomicReference<String> out=new AtomicReference<>();ins.runOnMainSync(()->a.web.evaluateJavascript(script,v->{out.set(v);ready.countDown();}));assertTrue("WebView callback; destroyed="+a.isDestroyed(),ready.await(10,TimeUnit.SECONDS));return out.get();}
    void route(MainActivity a,String route)throws Exception{js(a,"location.hash="+JSONObject.quote(route));waitFor(()->JSONObject.quote("#"+route).equals(js(a,"location.hash")));}
    void back(MainActivity a,String expected)throws Exception{ins.runOnMainSync(a::onBackPressed);waitFor(()->JSONObject.quote("#"+expected).equals(js(a,"location.hash")));}
    View tag(Activity a,String tag)throws Exception{return ui(()->a.findViewById(android.R.id.content).findViewWithTag(tag));}
    void capture(String name)throws Exception{ins.waitForIdleSync();Thread.sleep(300);File dir=new File(app.getFilesDir(),"code20-captures");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,out));}}
    @Test public void integratedDestinationAndHierarchyIgnoreVisitedScreens()throws Exception {
        ConfigStore store=new ConfigStore(app);byte[] rulesBefore=Files.readAllBytes(new File(store.directory,"automations.json").toPath());
        app.startForegroundService(new Intent(app,MoliService.class));Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        ManagementActivity management=(ManagementActivity)ins.startActivitySync(new Intent(app,ManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            ins.runOnMainSync(()->management.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            waitFor(()->resumed(ManagementActivity.class)!=null&&resumed(ManagementActivity.class).getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE);
            ManagementActivity landscape=resumed(ManagementActivity.class);
            assertNull("No home automation entry",tag(landscape,"auto:new"));
            ins.runOnMainSync(()->landscape.findViewById(R.id.navigation_automations).performClick());
            waitFor(()->tag(landscape,"auto:state:draft-link")!=null);
            assertTrue(ui(()->landscape.findViewById(R.id.navigation_automations).isSelected()));
            assertEquals(app.getString(R.string.auto_draft_state),ui(()->((TextView)landscape.findViewById(android.R.id.content).findViewWithTag("auto:state:draft-link")).getText().toString()));
            assertEquals(app.getString(R.string.auto_inactive),ui(()->((TextView)landscape.findViewById(android.R.id.content).findViewWithTag("auto:state:inactive-fixture")).getText().toString()));
            assertEquals(app.getString(R.string.auto_active),ui(()->((TextView)landscape.findViewById(android.R.id.content).findViewWithTag("auto:state:active-fixture")).getText().toString()));
            capture("automations-landscape");
            ins.runOnMainSync(()->landscape.findViewById(android.R.id.content).findViewWithTag("auto:new").performClick());
            waitFor(()->tag(landscape,"auto:cancel")!=null);
            ins.runOnMainSync(()->landscape.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            waitFor(()->resumed(ManagementActivity.class)!=null&&resumed(ManagementActivity.class)!=landscape&&resumed(ManagementActivity.class).getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT);
            ManagementActivity portrait=resumed(ManagementActivity.class);
            assertTrue(ui(()->portrait.findViewById(R.id.navigation_automations).isSelected()));assertNotNull(tag(portrait,"auto:cancel"));
            ins.runOnMainSync(portrait::onBackPressed);waitFor(()->tag(portrait,"auto:state:draft-link")!=null);capture("automations-portrait");
            // Return management to the emulator's natural landscape before
            // opening an unspecified-orientation WebView Activity. Otherwise
            // Android can recreate that Activity during the first JS callback.
            ins.runOnMainSync(()->portrait.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            waitFor(()->resumed(ManagementActivity.class)!=null&&resumed(ManagementActivity.class)!=portrait&&resumed(ManagementActivity.class).getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE);
            ManagementActivity ready=resumed(ManagementActivity.class);
            waitFor(()->tag(ready,"auto:editor:draft-link")!=null);
            ins.runOnMainSync(()->ready.findViewById(android.R.id.content).findViewWithTag("auto:editor:draft-link").performClick());
            waitFor(()->resumed(MainActivity.class)!=null);MainActivity moli=resumed(MainActivity.class);
            waitFor(()->"\"#/automatismes/draft-link\"".equals(js(moli,"location.hash")));
            route(moli,"/pieces");route(moli,"/energie");route(moli,"/automatismes/draft-link");
            back(moli,"/automatismes");back(moli,"/");
            route(moli,"/pieces");route(moli,"/energie/solaire");back(moli,"/energie");back(moli,"/");
            route(moli,"/unknown/child");back(moli,"/");
            route(moli,"/automatismes/draft-link");
            ins.runOnMainSync(()->moli.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            waitFor(()->resumed(MainActivity.class)!=null&&resumed(MainActivity.class)!=moli);
            MainActivity rotated=resumed(MainActivity.class);waitFor(()->"\"#/automatismes/draft-link\"".equals(js(rotated,"location.hash")));
            back(rotated,"/automatismes");back(rotated,"/");ins.runOnMainSync(rotated::onBackPressed);
            waitFor(()->resumed(ManagementActivity.class)!=null);ManagementActivity returned=resumed(ManagementActivity.class);
            assertTrue(ui(()->returned.findViewById(R.id.navigation_automations).isSelected()));assertEquals(EngineHost.State.RUNNING,EngineHost.state());
            ins.runOnMainSync(returned::finish);
        }finally{app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP));Beta2JourneyTest.waitState(EngineHost.State.STOPPED);}
        assertArrayEquals("Opening/navigation never changes existing rules",rulesBefore,Files.readAllBytes(new File(store.directory,"automations.json").toPath()));
    }
}
