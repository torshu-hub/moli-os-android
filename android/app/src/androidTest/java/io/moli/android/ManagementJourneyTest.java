package io.moli.android;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Changed Android journeys only: no network acceptance test or repeat of the J1 Rust suite. */
@RunWith(AndroidJUnit4.class)
public final class ManagementJourneyTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private ManagementActivity activity;
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private void waitFor(BooleanSupplier condition,String message) throws Exception {
        long end=SystemClock.uptimeMillis()+15000;
        while(SystemClock.uptimeMillis()<end) {
            boolean[] yes={false}; main(()->yes[0]=condition.getAsBoolean());
            if(yes[0]) return; Thread.sleep(100);
        }
        fail(message);
    }
    private <T extends View> T view(int id) { return activity.findViewById(id); }
    private void tap(int id) throws Exception {
        waitFor(()->view(id)!=null && view(id).isShown() && view(id).isEnabled(),"Action unavailable: "+id);
        Rect box=new Rect(); main(()->assertTrue(view(id).getGlobalVisibleRect(box)));
        assertTrue("Tap target clipped",box.width()>30 && box.height()>30);
        long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,box.centerX(),box.centerY(),0);
        MotionEvent up=MotionEvent.obtain(now,now+60,MotionEvent.ACTION_UP,box.centerX(),box.centerY(),0);
        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up); }
        finally { down.recycle(); up.recycle(); }
        instrumentation.waitForIdleSync();
    }
    private void textButton(int label) throws Exception {
        Button[] found={null}; main(()->found[0]=findButton(activity.getWindow().getDecorView(),activity.getString(label)));
        assertNotNull("Button missing: "+label,found[0]); main(()->found[0].performClick());
    }
    private Button findButton(View parent,String text) {
        if(parent instanceof Button && ((Button)parent).getText().toString().equals(text)) return (Button)parent;
        if(parent instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)parent;
            for(int i=0;i<group.getChildCount();i++) { Button child=findButton(group.getChildAt(i),text); if(child!=null)return child; }
        }
        return null;
    }
    private void orientation(int orientation) throws Exception {
        ManagementActivity before=activity;
        int wanted=orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ? android.content.res.Configuration.ORIENTATION_LANDSCAPE : android.content.res.Configuration.ORIENTATION_PORTRAIT;
        boolean changed=before.getResources().getConfiguration().orientation!=wanted;
        main(()->before.setRequestedOrientation(orientation));
        waitFor(()->{
            for(Activity current:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if(current instanceof ManagementActivity && (!changed || current!=before) && current.getResources().getConfiguration().orientation==wanted) {
                    ViewGroup host=current.findViewById(android.R.id.content);
                    if(host.getChildCount()==0 || !(host.getChildAt(0) instanceof LinearLayout)) continue;
                    LinearLayout root=(LinearLayout)host.getChildAt(0);
                    if(root.getOrientation()!=(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL)) continue;
                    if(root.getWidth()<=0 || root.getHeight()<=0) continue;
                    activity=(ManagementActivity)current; return true;
                }
            return false;
        },"Orientation not applied");
        instrumentation.waitForIdleSync();
    }
    private void capture(String name) throws Exception {
        CountDownLatch frame=new CountDownLatch(1);
        main(()->{
            View decor=activity.getWindow().getDecorView();
            decor.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);
            decor.invalidate();
        });
        assertTrue("UI frame not committed before capture",frame.await(5,TimeUnit.SECONDS));
        Thread.sleep(150); // The compositor must present the committed frame before UiAutomation captures it.
        File folder=new File(app.getFilesDir(),"code2-captures"); assertTrue(folder.isDirectory() || folder.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))) {
            assertTrue(instrumentation.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
        }
    }
    private String config() throws Exception { return EngineHost.inspect(()->new ConfigStore(app).read()).get(15,TimeUnit.SECONDS); }
    @Test public void landscapeKeyboardRotationSaveAndService() throws Exception {
        assertEquals("io.moli.android.app.beta2.test",app.getPackageName());
        if(!ProtectedKey.exists(app)) ProtectedKey.create(app,"482913");
        activity=(ManagementActivity)instrumentation.startActivitySync(new Intent(app,ManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        orientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        waitFor(()->((Button)view(R.id.engine_action)).getText().toString().equals(activity.getString(R.string.start)),"Stopped control missing");
        capture("landscape-home-stopped");
        tap(R.id.engine_action); Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        waitFor(()->((TextView)view(R.id.engine_status)).getText().toString().equals(activity.getString(R.string.running)) && view(R.id.engine_action).isEnabled(),"Real running state missing");
        capture("landscape-home-running");
        textButton(R.string.integrations);
        tap(R.id.catalogue_entry);
        waitFor(()->view(R.id.variable_lat)!=null,"Catalogue selection did not immediately open parameters");
        assertNull("Catalogue list remains above form",view(R.id.catalogue_entry));
        String initial=config();
        CountDownLatch release=new CountDownLatch(1);
        Future<Void> maintenance=EngineHost.maintain(app,()->{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("fixture timeout");return null;});
        try {
            waitFor(()->((TextView)view(R.id.engine_status)).getText().toString().equals(activity.getString(R.string.maintenance)) && !view(R.id.engine_action).isEnabled() && !view(R.id.save_configuration).isEnabled(),"Maintenance did not disable inappropriate commands");
        } finally {release.countDown();}
        maintenance.get(30,TimeUnit.SECONDS); Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        main(()->{
            EditText lat=view(R.id.variable_lat); lat.setText("-1.25"); lat.requestFocus();
            activity.getSystemService(InputMethodManager.class).showSoftInput(lat,InputMethodManager.SHOW_IMPLICIT);
        });
        waitFor(()->activity.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()),"Real IME not shown");
        waitFor(()->visibleWithLabel(R.id.variable_lat),"Latitude or its label hidden by landscape IME");
        assertSaveAboveKeyboard(); capture("landscape-ime-latitude");
        main(()->{EditText lon=view(R.id.variable_lon);lon.setText("-2.5");lon.requestFocus();});
        waitFor(()->visibleWithLabel(R.id.variable_lon),"Longitude or its label hidden by landscape IME");
        assertSaveAboveKeyboard(); capture("landscape-ime-longitude");
        orientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        assertDraft("-1.25","-2.5"); capture("portrait-draft");
        orientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        assertDraft("-1.25","-2.5"); assertEquals("Rotation saved implicitly",initial,config());
        // Invalid native driver ID: unchanged Rust validation and transaction, surfaced by the new form.
        main(()->((EditText)view(R.id.integration_instance)).setText("bad:id"));
        tap(R.id.save_configuration);
        waitFor(()->view(R.id.form_feedback)!=null && view(R.id.form_feedback).getVisibility()==View.VISIBLE && ((Button)view(R.id.save_configuration)).isEnabled(),"Native rejection not surfaced next to form");
        assertEquals(initial,config()); assertDraft("-1.25","-2.5");
        main(()->((EditText)view(R.id.integration_instance)).setText("open-meteo"));
        tap(R.id.save_configuration);
        waitFor(()->view(R.id.catalogue_entry)!=null && view(R.id.catalogue_entry).isEnabled(),"No clear return after save");
        waitFor(()->findText(activity.getWindow().getDecorView(),activity.getString(R.string.saved)),"Configuration confirmation missing");
        String next=config(); assertTrue(next.contains("-1.25")); assertTrue(next.contains("-2.5"));
        Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        capture("landscape-saved");
        tap(R.id.catalogue_entry); assertDraft("-1.25","-2.5");
        tap(R.id.management_back); waitFor(()->view(R.id.catalogue_entry)!=null,"Back did not return to integrations");
        textButton(R.string.journal); capture("landscape-journal");
        textButton(R.string.export);
        waitFor(()->{
            android.view.accessibility.AccessibilityNodeInfo window=instrumentation.getUiAutomation().getRootInActiveWindow();
            return window!=null && window.getPackageName()!=null && window.getPackageName().toString().contains("documentsui");
        },"System export picker did not open");
        capture("journal-export-picker");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitFor(()->{
            for(Activity current:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                if(current instanceof ManagementActivity) {activity=(ManagementActivity)current;return true;}
            }
            return false;
        },"Management not resumed after export picker");
        textButton(R.string.overview); tap(R.id.engine_action); Beta2JourneyTest.waitState(EngineHost.State.STOPPED);
        tap(R.id.engine_action); Beta2JourneyTest.waitState(EngineHost.State.RUNNING); assertEquals(next,config());
        // The original WebView is still reached through the existing space selector.
        MainActivity dashboard=(MainActivity)instrumentation.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(dashboard::showDashboard);
        long deadline=SystemClock.uptimeMillis()+30000;boolean displayed=false;
        while(SystemClock.uptimeMillis()<deadline) {
            CountDownLatch latch=new CountDownLatch(1);String[] value={null};
            main(()->dashboard.web.evaluateJavascript("!!document.querySelector('.maison nav.rail') && document.body.innerText.length > 20",v->{value[0]=v;latch.countDown();}));
            if(latch.await(3,TimeUnit.SECONDS) && "true".equals(value[0])) {displayed=true;break;}
            Thread.sleep(200);
        }
        assertTrue("Official dashboard not accessible",displayed); capture("official-dashboard-unchanged");
        main(dashboard::finish); main(activity::finish);
        app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP)); Beta2JourneyTest.waitState(EngineHost.State.STOPPED);
        String report=GeneralJournal.export(app); assertTrue(report.contains("CONFIGURATION_SAVED"));
        assertFalse(report.contains("-1.25")); assertFalse(report.contains("-2.5"));
    }
    private boolean visibleWithLabel(int id) {
        View edit=view(id); Rect editBox=new Rect(),labelBox=new Rect();
        ViewGroup parent=(ViewGroup)edit.getParent(); View label=parent.getChildAt(parent.indexOfChild(edit)-1);
        return edit.getGlobalVisibleRect(editBox) && editBox.height()>=edit.getHeight()-2 && label.getGlobalVisibleRect(labelBox) && labelBox.height()>=label.getHeight()-2;
    }
    private void assertSaveAboveKeyboard() {
        main(()->{
            View save=view(R.id.save_configuration); Rect box=new Rect(); assertTrue(save.getGlobalVisibleRect(box));
            assertEquals("Save is clipped",save.getHeight(),box.height());
            WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();
            int keyboardTop=activity.getWindow().getDecorView().getHeight()-insets.getInsets(WindowInsets.Type.ime()).bottom;
            assertTrue("Save covered by IME",box.bottom<=keyboardTop);
        });
    }
    private void assertDraft(String lat,String lon) { main(()->{
        assertEquals(lat,((EditText)view(R.id.variable_lat)).getText().toString());
        assertEquals(lon,((EditText)view(R.id.variable_lon)).getText().toString());
    }); }
    private boolean findText(View parent,String text) {
        if(parent instanceof TextView && ((TextView)parent).getText().toString().equals(text))return true;
        if(parent instanceof ViewGroup) {ViewGroup group=(ViewGroup)parent;for(int i=0;i<group.getChildCount();i++)if(findText(group.getChildAt(i),text))return true;}
        return false;
    }
}
