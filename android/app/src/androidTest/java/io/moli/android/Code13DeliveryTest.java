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

/** One code12 → code13 update; canonical activation, network menu and WebView tile policy. */
@RunWith(AndroidJUnit4.class)
public final class Code13DeliveryTest {
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
        File dir=new File(app.getFilesDir(),"code13-captures");dir.mkdirs();
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
    private void groupState(String text,boolean checked)throws Exception {
        waitUi(()->tag("group-state:meross-mss310") instanceof TextView && ((TextView)tag("group-state:meross-mss310")).getText().toString().equals(text) && tag("group-toggle:meross-mss310").isEnabled(),"Group state missing: "+text);
        main(()->assertEquals(checked,((Switch)tag("group-toggle:meross-mss310")).isChecked()));
    }
    @Test public void upgradeAndCorrectiveJourneys()throws Exception {
        assertEquals(13,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        Code12UpgradeSeedTest.sameData(app);
        ins.startActivitySync(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        click(R.id.navigation_integrations);
        groupState(app.getString(R.string.integration_none_active,0,4),false);
        main(()->{assertEquals(1,countTag(current().getWindow().getDecorView(),"package:meross-mss310"));assertNull(tag("group-toggle:host"));});
        clickTag("group-toggle:meross-mss310");groupState(app.getString(R.string.integration_all_active,4,4),true);
        assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        clickTag("meross-mss310");
        waitUi(()->tag("toggle:existing-plug-4")!=null,"Individual switches missing without cloud account");
        main(()->assertTrue(((Switch)tag("toggle:existing-plug-1")).isChecked()));
        clickTag("toggle:existing-plug-1");
        groupState(app.getString(R.string.integration_mixed_active,3,4),false);
        clickTag("meross-mss310");
        main(()->assertFalse(((Switch)tag("toggle:existing-plug-1")).isChecked()));
        click(R.id.management_back);groupState(app.getString(R.string.integration_mixed_active,3,4),false);capture("integrations-mixed-landscape");
        Activity[] activity={null};main(()->activity[0]=current());orient(activity[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        groupState(app.getString(R.string.integration_mixed_active,3,4),false);capture("integrations-mixed-portrait");
        clickTag("group-toggle:meross-mss310");groupState(app.getString(R.string.integration_all_active,4,4),true);
        clickTag("group-toggle:meross-mss310");groupState(app.getString(R.string.integration_none_active,0,4),false);
        Code12UpgradeSeedTest.sameData(app);
        // Stale input and a candidate rejected by the official validator cannot change any file.
        EngineHost.maintain(app,()->{
            ConfigStore store=new ConfigStore(app);String before=store.read();
            try{store.setPackageEnabled(before+"# stale", "meross-mss310",true);fail("Stale group accepted");}catch(IllegalStateException expected){assertEquals("conflict",expected.getMessage());}
            assertEquals(before,store.read());
            // Invalid option deliberately injected only into the expected snapshot, then restored.
            String invalid=before.replace("00000000000000000000000000000001", "invalid");store.transaction.commitExpected(before,invalid);
            try{store.setPackageEnabled(invalid,"meross-mss310",true);fail("Invalid group accepted");}catch(IllegalStateException expected){assertEquals("configuration",expected.getMessage());}
            assertEquals(invalid,store.read());store.transaction.commitExpected(invalid,before);return null;
        }).get(15,TimeUnit.SECONDS);
        Code12UpgradeSeedTest.sameData(app);
        click(R.id.navigation_network);
        main(()->{
            ManagementActivity management=(ManagementActivity)current();NetworkPanel panel=management.networkPanel;
            panel.state.results.add(new NetworkEvidence.Entry("192.168.50.21",0,"",NetworkEvidence.Kind.PRESENCE,NetworkEvidence.Proof.ECHO,"",""));
            panel.state.results.add(new NetworkEvidence.Entry("192.168.50.77",0,"phone.local",NetworkEvidence.Kind.PRESENCE,NetworkEvidence.Proof.PTR,"",""));
            for(NetworkEvidence.Device device:panel.state.results.list()) {
                AlertDialog dialog=panel.details(device);
                assertEquals(View.VISIBLE,dialog.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility());
                assertEquals(View.VISIBLE,dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getVisibility());
                if(device.ip.endsWith(".77"))assertEquals(View.GONE,dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());
                else assertEquals(app.getString(R.string.integration_open),dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
                dialog.dismiss();
            }
            assertEquals(2,panel.state.results.size());
        });
        // A network address may be selected only after explicitly entering the Meross picker.
        click(R.id.navigation_integrations);clickTag("meross-mss310");clickTag("configure:existing-plug-1");
        waitUi(()->tag("meross:choose-ip")!=null && tag("meross:choose-ip").isEnabled(),"Explicit Meross picker missing");
        main(()->((ManagementActivity)current()).networkPanel.state.results.clear());
        clickTag("meross:choose-ip");
        waitUi(()->current().findViewById(R.id.network_results)!=null,"Explicit picker did not open Network");
        main(()->{
            NetworkPanel panel=((ManagementActivity)current()).networkPanel;
            NetworkEvidence.Device phone=new NetworkEvidence.Device("192.168.50.77",java.util.Collections.emptyList());
            AlertDialog dialog=panel.details(phone);
            assertEquals(app.getString(R.string.meross_use_ip),dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        });
        waitUi(()->tag("field:host") instanceof EditText && ((EditText)tag("field:host")).getText().toString().equals("192.168.50.77"),"Explicit IP selection lost");
        click(R.id.navigation_network);
        waitUi(()->current().findViewById(R.id.network_results)!=null,"General Network missing");
        main(()->{
            NetworkPanel panel=((ManagementActivity)current()).networkPanel;
            AlertDialog dialog=panel.details(new NetworkEvidence.Device("192.168.50.77",java.util.Collections.emptyList()));
            assertEquals(View.GONE,dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());dialog.dismiss();
        });
        Code12UpgradeSeedTest.sameData(app);
        String canonicalBeforeEngine=new ConfigStore(app).read();
        app.startForegroundService(new Intent(app,MoliService.class));Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        ins.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitUi(()->current() instanceof MainActivity && ((MainActivity)current()).web.getVisibility()==View.VISIBLE && ((MainActivity)current()).web.getUrl()!=null && ((MainActivity)current()).web.getUrl().startsWith(EngineHost.origin()),"Dashboard unavailable");
        java.util.concurrent.atomic.AtomicReference<String> loaded=new java.util.concurrent.atomic.AtomicReference<>();
        main(()->{
            MainActivity dashboard=(MainActivity)current();
            for(String url:new String[]{"https://tile.openstreetmap.org/0/0/0.png","https://tile.openstreetmap.org:443/1/0/0.png"}) {
                android.webkit.WebResourceRequest tile=request(url,false,"GET");
                assertTrue(dashboard.resourceAllowed(tile));assertNull(dashboard.web.getWebViewClient().shouldInterceptRequest(dashboard.web,tile));
                assertTrue(dashboard.web.getWebViewClient().shouldOverrideUrlLoading(dashboard.web,request(url,true,"GET")));
                assertFalse(dashboard.resourceAllowed(request(url,true,"GET")));
            }
            for(String url:new String[]{"http://tile.openstreetmap.org/0/0/0.png","https://tile.openstreetmap.org:444/0/0/0.png","https://other.example.org/0/0/0.png","https://tile.openstreetmap.org.evil.example/0/0/0.png","https://tile.openstreetmap.org/","https://tile.openstreetmap.org/0/0/0.png?x=1","https://tile.openstreetmap.org/0/0/0.png#x","https://user"+(char)64+"tile.openstreetmap.org/0/0/0.png","https://tile.openstreetmap.org/%30/0/0.png","file:///0/0/0.png","content://tile.openstreetmap.org/0/0/0.png","http://127.0.0.1:1/"})
                assertFalse(url,dashboard.resourceAllowed(request(url,false,"GET")));
            assertFalse(dashboard.resourceAllowed(request("https://tile.openstreetmap.org/0/0/0.png",false,"POST")));
            assertFalse(dashboard.web.getSettings().getAllowFileAccess());assertFalse(dashboard.web.getSettings().getAllowContentAccess());
            assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW,dashboard.web.getSettings().getMixedContentMode());
        });
        waitUi(()->{
            ((MainActivity)current()).web.evaluateJavascript("document.readyState",loaded::set);
            return "\"complete\"".equals(loaded.get());
        },"Official document did not finish");
        main(()->((MainActivity)current()).web.evaluateJavascript("window.code13Tile='waiting';const tile=new Image();tile.onload=()=>window.code13Tile=tile.naturalWidth===256?'loaded':'size';tile.onerror=()=>window.code13Tile='failed';tile.src='https://tile.openstreetmap.org/0/0/0.png';document.body.append(tile)",null));
        loaded.set(null);
        waitUi(()->{((MainActivity)current()).web.evaluateJavascript("window.code13Tile",loaded::set);return "\"loaded\"".equals(loaded.get());},"OSM tile did not load: "+loaded.get());
        capture("osm-tile-webview");
        app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP));Beta2JourneyTest.waitState(EngineHost.State.STOPPED);
        main(()->assertFalse(((MainActivity)current()).resourceAllowed(request("https://tile.openstreetmap.org/0/0/0.png",false,"GET"))));
        assertEquals(canonicalBeforeEngine,new ConfigStore(app).read());
        assertEquals(app.getSharedPreferences(Code12UpgradeSeedTest.PREFS,Context.MODE_PRIVATE).getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
        File dir=new File(app.getFilesDir(),"code13-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,"update-proof.txt"))){out.write("code12 -> code13; same synthetic four-plug configuration/files/master; one package per tile; none/all/mixed and individual activation without cloud; stale/invalid rejected; network menus; exact HTTPS OSM images loaded, external navigation still blocked. No physical device commands.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
    static android.webkit.WebResourceRequest request(String url,boolean main,String method) {
        return new android.webkit.WebResourceRequest(){
            public android.net.Uri getUrl(){return android.net.Uri.parse(url);}
            public boolean isForMainFrame(){return main;} public boolean isRedirect(){return false;}
            public boolean hasGesture(){return false;} public String getMethod(){return method;}
            public java.util.Map<String,String> getRequestHeaders(){return java.util.Collections.emptyMap();}
        };
    }
}
