package io.moli.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import static org.junit.Assert.*;

/** Touchscreen events, not DOM scrolling/clicks. Used only with local emulator fixtures. */
final class TouchRegression {
    interface Condition { boolean ready() throws Exception; }
    static void until(Condition condition) throws Exception {
        long end=SystemClock.elapsedRealtime()+30000;
        while(!condition.ready()){if(SystemClock.elapsedRealtime()>end)fail("Touch/navigation condition timed out");Thread.sleep(100);}
    }
    static String js(ActivityScenario<MainActivity> ui,String script) throws Exception {
        AtomicReference<String> result=new AtomicReference<>();ui.onActivity(a->a.web.evaluateJavascript(script,result::set));
        until(()->result.get()!=null);return result.get();
    }
    static String shell(String command)throws Exception {
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
    static AutoCloseable tablet()throws Exception {
        assertTrue("Emulator only",android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"));
        String size=override(shell("wm size")),density=override(shell("wm density"));
        shell("wm size 1600x2560");shell("wm density 320");
        return ()->{shell("wm size "+size);shell("wm density "+density);};
    }
    private static String override(String text){for(String line:text.split("\n"))if(line.startsWith("Override"))return line.substring(line.indexOf(':')+1).trim();return "reset";}
    static void event(long start,int action,float x,float y){
        MotionEvent e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{assertTrue("Injected real touchscreen event",InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(e,true));}finally{e.recycle();}
    }
    static void swipe(ActivityScenario<MainActivity> ui)throws Exception {
        AtomicReference<Rect> bounds=new AtomicReference<>();ui.onActivity(a->{Rect r=new Rect();assertTrue(a.web.getGlobalVisibleRect(r));bounds.set(r);});
        Rect r=bounds.get();float x=r.left+r.width()*0.8f,from=r.top+r.height()*0.8f,to=r.top+r.height()*0.2f;
        long start=SystemClock.uptimeMillis();event(start,MotionEvent.ACTION_DOWN,x,from);
        for(int n=1;n<=24;n++){SystemClock.sleep(20);event(start,MotionEvent.ACTION_MOVE,x,from+(to-from)*n/24);}
        event(start,MotionEvent.ACTION_UP,x,to);Thread.sleep(350);
    }
    static void originalMenu(ActivityScenario<MainActivity> ui)throws Exception {
        assertEquals("true",js(ui,"['moli','salon','pieces','plan','carte','cameras','parler','energie','impression','dehors','automatismes','infra','systeme','atelier'].every(p=>document.querySelector('.rail a[href=\"#/'+p+'\"]'))"));
        for(String route:new String[]{"moli","systeme","atelier"}) {
            boolean tapped=false;
            for(int n=0;n<12&&!tapped;n++) {
                JSONObject r=new JSONObject(js(ui,"(()=>{const rail=document.querySelector('.rail').getBoundingClientRect(),r=document.querySelector('.rail a[href=\"#/'+"+JSONObject.quote(route)+"+'\"]').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,top:rail.top,bottom:rail.bottom,w:innerWidth}})()"));
                AtomicReference<float[]> xy=new AtomicReference<>();ui.onActivity(a->{int[] pos=new int[2];a.web.getLocationOnScreen(pos);float scale=(float)(a.web.getWidth()/r.optDouble("w"));xy.set(new float[]{pos[0]+(float)r.optDouble("x")*scale,pos[1],scale});});
                float[] v=xy.get();long start=SystemClock.uptimeMillis();
                if(r.getDouble("y")>r.getDouble("top")+20&&r.getDouble("y")<r.getDouble("bottom")-20) {
                    float y=v[1]+(float)r.getDouble("y")*v[2];event(start,MotionEvent.ACTION_DOWN,v[0],y);SystemClock.sleep(80);event(start,MotionEvent.ACTION_UP,v[0],y);tapped=true;
                } else {
                    float from=v[1]+((float)r.getDouble("bottom")-60)*v[2],to=v[1]+((float)r.getDouble("top")+60)*v[2];
                    event(start,MotionEvent.ACTION_DOWN,v[0],from);for(int k=1;k<=24;k++){SystemClock.sleep(20);event(start,MotionEvent.ACTION_MOVE,v[0],from+(to-from)*k/24);}event(start,MotionEvent.ACTION_UP,v[0],to);Thread.sleep(350);
                }
            }
            assertTrue("Original menu reachable by touch: "+route,tapped);
            until(()->JSONObject.quote("#/"+route).equals(js(ui,"location.hash")));
        }
        until(()->"true".equals(js(ui,"!document.querySelector('.maison')")));
        js(ui,"location.hash='#/'");until(()->"true".equals(js(ui,"document.querySelectorAll('[data-equipment=meross]').length===4")));
        evidence("ORIGINAL MENU: 14 original destinations present; touchscreen opens Moli, scrolls rail to Systeme and Atelier; dashboard restored");
    }
    static void verifyDevices(ActivityScenario<MainActivity> ui)throws Exception {
        until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(a.hasWindowFocus()&&a.web.getHeight()>300));return ready.get();});
        originalMenu(ui);
        ui.onActivity(a->{ViewGroup root=a.findViewById(android.R.id.content);View controls=((ViewGroup)root.getChildAt(0)).getChildAt(0);
            assertTrue("Native engine controls compact",controls.getHeight()<=TrialUi.dp(a,120));
            assertTrue("Dashboard retains most of portrait screen",a.web.getHeight()>a.getResources().getDisplayMetrics().heightPixels*0.7);
            for(int label:new int[]{R.string.start,R.string.stop,R.string.engine_restart,R.string.engine_reconnect}){
                Button b=MerossAccountTest.button(a.getWindow().getDecorView(),a.getString(label));assertNotNull(b);assertTrue(b.isShown());assertTrue("Accessible touch target",b.getHeight()>=TrialUi.dp(a,48));
            }});
        assertEquals("true",js(ui,"document.querySelectorAll('[data-equipment=meross]').length===4 && document.documentElement.scrollHeight>innerHeight+100"));
        js(ui,"window.scrollTo(0,0)");
        ui.onActivity(a->{for(ViewParent p=a.web.getParent();p!=null;p=p.getParent())assertFalse("WebView must not compete with a native scroller",p instanceof ScrollView);
            Rect full=new Rect(),visible=new Rect();a.web.getGlobalVisibleRect(visible);int[] xy=new int[2];a.web.getLocationOnScreen(xy);full.set(xy[0],xy[1],xy[0]+a.web.getWidth(),xy[1]+a.web.getHeight());assertEquals("Entire WebView viewport is visible",full,visible);});
        String target="[...document.querySelectorAll('[data-equipment=meross]')].at(-1).querySelector('summary')";
        int swipes=0;JSONObject rect;
        while(true){rect=new JSONObject(js(ui,"(()=>{const r=("+target+").getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,w:innerWidth,h:innerHeight}})()"));
            if(rect.getDouble("y")>40&&rect.getDouble("y")<rect.getDouble("h")-130)break;
            assertTrue("Last real equipment card reachable with finger swipes",swipes++<24);swipe(ui);
        }
        assertTrue("At least one real swipe needed",swipes>0);assertTrue(Double.parseDouble(js(ui,"scrollY"))>100);
        AtomicReference<float[]> point=new AtomicReference<>();final JSONObject position=rect;
        ui.onActivity(a->{int[] xy=new int[2];a.web.getLocationOnScreen(xy);float scale=(float)(a.web.getWidth()/position.optDouble("w"));point.set(new float[]{xy[0]+(float)position.optDouble("x")*scale,xy[1]+(float)position.optDouble("y")*scale});});
        long start=SystemClock.uptimeMillis();event(start,MotionEvent.ACTION_DOWN,point.get()[0],point.get()[1]);SystemClock.sleep(80);event(start,MotionEvent.ACTION_UP,point.get()[0],point.get()[1]);
        until(()->"true".equals(js(ui,"[...document.querySelectorAll('[data-equipment=meross]')].at(-1).querySelector('details').open")));
        evidence("SCROLL bounded viewport: last MSS310 card and details reached by "+swipes+" swipes + touchscreen tap (1600x2560 portrait)");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        until(()->{AtomicReference<Boolean> home=new AtomicReference<>(false);ui.onActivity(a->home.set(!a.web.isShown()));return home.get();});
    }
    static Activity current(){for(Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(!(a instanceof MainActivity))return a;return null;}
    static void screen(Class<?> type)throws Exception {until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Activity a=current();ready.set(a!=null&&type.isInstance(a)&&a.hasWindowFocus());});return ready.get();});}
    static void childButton(int label){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Activity a=current();assertNotNull(a);Button b=MerossAccountTest.button(a.getWindow().getDecorView(),a.getString(label));assertNotNull(b);assertTrue(b.isShown());b.performClick();});}
    static void backToSettings()throws Exception {InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);screen(PreviewSettingsActivity.class);}
    static void maintenance(ActivityScenario<MainActivity> ui)throws Exception {
        ui.onActivity(a->MerossAccountTest.button(a.getWindow().getDecorView(),a.getString(R.string.preview_settings)).performClick());screen(PreviewSettingsActivity.class);
        childButton(R.string.home_settings);screen(HomeSettingsActivity.class);backToSettings();
        childButton(R.string.app_info);screen(AppInfoActivity.class);backToSettings();
        childButton(R.string.preview_advanced);childButton(R.string.equipment_manual);screen(EquipmentSettingsActivity.class);backToSettings();
        childButton(R.string.maintenance_profiles);screen(IntegrationsActivity.class);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Activity a=current();assertTrue(MerossAccountTest.button(a.getWindow().getDecorView(),a.getString(R.string.integration_import)).isShown());
            for(int label:new int[]{R.string.integration_example,R.string.integration_demo,R.string.integration_weather,R.string.trial_begin,R.string.stop,R.string.start})assertFalse("Historical shortcuts hidden",MerossAccountTest.button(a.getWindow().getDecorView(),a.getString(label)).isShown());});
        backToSettings();childButton(R.string.backup_title);screen(BackupActivity.class);backToSettings();
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    }
    static void evidence(String text){android.os.Bundle b=new android.os.Bundle();b.putString("stream",text+"\n");InstrumentationRegistry.getInstrumentation().sendStatus(2,b);}
    private TouchRegression(){}
}
