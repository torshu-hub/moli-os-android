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

/** One code7 → code8 update, synthetic discovery only; no LAN transport is started. */
@RunWith(AndroidJUnit4.class)
public final class Code8DeliveryTest {
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
        File dir=new File(app.getFilesDir(),"code8-captures");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(ins.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}
    }
    static final class Fixture implements DiscoverySearch {
        Listener listener;boolean closed;
        public void start(Listener listener){this.listener=listener;}
        public void close(){closed=true;if(listener!=null)listener.finished(End.CANCELLED);}
        void end(End end){closed=true;listener.finished(end);}
    }
    static final class Source implements NetworkPanel.Factory {
        Fixture last;int started;boolean ethernet,unavailable;
        final Context app;
        Source(Context app){this.app=app;}
        public NetworkPanel.Target current()throws Exception {
            if(unavailable)throw new IOException("synthetic unavailable");
            String label=app.getString(ethernet?R.string.network_ethernet:R.string.network_wifi)+" · 192.168.50.40/26";
            return new NetworkPanel.Target(label,ethernet?"fixture-ethernet":"fixture-wifi",false,()->{started++;last=new Fixture();return last;});
        }
    }
    private NetworkPanel panel(){return ((ManagementActivity)current()).networkPanel;}
    private boolean state(int label){return ((TextView)current().findViewById(R.id.network_status)).getText().toString().contains(app.getString(label));}
    private View tag(String value){return current().getWindow().getDecorView().findViewWithTag(value);}
    private void tapTag(String value)throws Exception {
        waitUi(()->tag(value)!=null&&tag(value).isShown(),"Missing result "+value);
        main(()->{View v=tag(value);v.requestRectangleOnScreen(new android.graphics.Rect(0,0,v.getWidth(),v.getHeight()),true);});ins.waitForIdleSync();
        int[] xy=new int[2];main(()->{View v=tag(value);v.getLocationOnScreen(xy);xy[0]+=v.getWidth()/2;xy[1]+=v.getHeight()/2;});long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,xy[0],xy[1],0),up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,xy[0],xy[1],0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{ins.getUiAutomation().injectInputEvent(down,true);ins.getUiAutomation().injectInputEvent(up,true);}finally{down.recycle();up.recycle();}ins.waitForIdleSync();
    }
    private void emit(Fixture fixture)throws Exception {
        NetworkEvidence.Entry announcement=NetworkEvidence.ssdp("192.168.50.1","HTTP/1.1 200 OK\r\nLOCATION: http://192.168.50.1/root.xml\r\n\r\n");
        NetworkEvidence.Entry gateway=DiscoveryXml.describe(announcement,"<root><device><friendlyName>Fixture gateway</friendlyName><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType><serviceList><service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType></service></serviceList></device></root>");
        main(()->{
            fixture.listener.found(announcement);fixture.listener.found(gateway);
            fixture.listener.found(new NetworkEvidence.Entry("192.168.50.7",443,"",NetworkEvidence.Kind.TCP,NetworkEvidence.Proof.OPEN_PORT,"",""));
            NetworkEvidence.Entry ipp=NetworkEvidence.mdns("192.168.50.20",631,"Fixture printer","_ipp._tcp.",null);
            fixture.listener.found(ipp);fixture.listener.found(ipp);
            fixture.listener.found(new NetworkEvidence.Entry("192.168.50.20",80,"",NetworkEvidence.Kind.TCP,NetworkEvidence.Proof.OPEN_PORT,"",""));
            fixture.listener.found(NetworkEvidence.mdns("192.168.50.35",80,"Fixture web service","_http._tcp.",null));
        });
        waitUi(()->((LinearLayout)current().findViewById(R.id.network_results)).getChildCount()==4,"IP deduplication not rendered");
    }
    private void platformRules()throws Exception {
        assertFalse(LocalDiscovery.eligible(new android.net.NetworkCapabilities()));
        NetworkEvidence.Entry a=NetworkEvidence.ssdp("192.168.50.1","HTTP/1.1 200 OK\r\nLOCATION: http://192.168.50.1/root.xml\r\n");
        assertEquals("Fixture & gateway",DiscoveryXml.describe(a,"<root><device><friendlyName>Fixture &amp; gateway</friendlyName></device></root>").name);
        for(String xml:new String[]{"<!DOCTYPE root [<!ENTITY x SYSTEM 'http://example.org/'>]><root/>","<root><device>","<root/><root/>","<other/>","x".repeat(32769)}){
            try{DiscoveryXml.describe(a,xml);fail("Malformed fixture description accepted: "+xml.substring(0,Math.min(80,xml.length())));}catch(Exception expected){}
        }
        android.content.pm.PackageInfo pkg=app.getPackageManager().getPackageInfo(app.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS);
        java.util.List<String> permissions=java.util.Arrays.asList(pkg.requestedPermissions);
        assertTrue(permissions.contains("android.permission.ACCESS_NETWORK_STATE"));assertTrue(permissions.contains("android.permission.CHANGE_WIFI_MULTICAST_STATE"));
        assertFalse(permissions.contains("android.permission.ACCESS_FINE_LOCATION"));assertFalse(permissions.contains("android.permission.NEARBY_WIFI_DEVICES"));
    }
    @Test public void upgradeAndDiscoverSyntheticNetworkWithoutChangingMoliData()throws Exception {
        assertEquals("io.moli.android.app.beta2.test",app.getPackageName());
        assertEquals(8,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        Code8UpgradeSeedTest.sameData(app);platformRules();
        Source source=new Source(app);ins.startActivitySync(app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
        main(()->panel().state.factory=source);
        Activity[] a={null};main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        click(R.id.navigation_network);
        main(()->{assertEquals(0,source.started);for(int id:new int[]{R.id.navigation_home,R.id.navigation_integrations,R.id.navigation_network,R.id.navigation_journal,R.id.navigation_moli})assertTrue(current().findViewById(id).isShown());});
        click(R.id.network_search);assertEquals(1,source.started);emit(source.last);main(()->source.last.end(DiscoverySearch.End.COMPLETE));
        waitUi(()->state(R.string.network_complete),"Search did not finish");capture("network-landscape");
        tapTag("network-ip:192.168.50.7");
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> copy=ins.getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(app.getString(R.string.network_copy));
        assertFalse(copy.isEmpty());assertTrue(copy.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));ins.waitForIdleSync();
        main(()->assertEquals("192.168.50.7",app.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString()));
        click(R.id.network_search);Fixture cancelled=source.last;emit(cancelled);click(R.id.network_cancel);assertTrue(cancelled.closed);
        main(()->cancelled.listener.found(new NetworkEvidence.Entry("192.168.50.99",80,"Late fixture",NetworkEvidence.Kind.TCP,NetworkEvidence.Proof.OPEN_PORT,"","")));
        main(()->assertEquals(4,panel().state.results.size()));
        click(R.id.network_search);emit(source.last);main(()->{source.ethernet=true;source.last.end(DiscoverySearch.End.NETWORK_CHANGED);});
        waitUi(()->state(R.string.network_changed),"Network change not explicit");main(()->assertEquals(0,panel().state.results.size()));
        click(R.id.network_search);Fixture rotating=source.last;emit(rotating);capture("network-searching-landscape");int searches=source.started;
        main(()->a[0]=current());orient(a[0],ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);assertTrue(rotating.closed);
        waitUi(()->state(R.string.network_cancelled),"Rotation did not stop foreground search");main(()->{assertEquals(searches,source.started);assertEquals(4,panel().state.results.size());});capture("network-portrait");
        tapTag("network-ip:192.168.50.20");capture("network-details-portrait");ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);ins.waitForIdleSync();
        click(R.id.network_search);main(()->source.last.end(DiscoverySearch.End.UNAVAILABLE));waitUi(()->state(R.string.network_unavailable),"Unavailable status missing");
        main(()->source.unavailable=true);click(R.id.network_search);main(()->{assertEquals(0,panel().state.results.size());source.unavailable=false;});
        click(R.id.network_search);Fixture leaving=source.last;emit(leaving);click(R.id.navigation_home);assertTrue(leaving.closed);
        assertEquals(EngineHost.State.STOPPED,EngineHost.state());Code8UpgradeSeedTest.sameData(app);
    }
}
