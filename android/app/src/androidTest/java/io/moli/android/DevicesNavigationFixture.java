package io.moli.android;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.*;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Discoverable navigation only, using the existing four synthetic GET-only plugs. */
final class DevicesNavigationFixture {
    private static Button visibleButton(View root,String text) {
        if(root instanceof Button && root.isShown() && text.contentEquals(((Button)root).getText()))return (Button)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){
            Button found=visibleButton(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;
        }
        return null;
    }
    private static void ready(ActivityScenario<MainActivity> ui)throws Exception {
        // Activity.finish/rotation returns before the resumed window is drawn.
        // Keep the visibility assertion; wait for its actual UI precondition.
        TouchRegression.until(()->{
            AtomicReference<Boolean> shown=new AtomicReference<>(false);
            ui.onActivity(a->shown.set(a.hasWindowFocus()&&visibleButton(a.getWindow().getDecorView(),a.getString(R.string.my_devices))!=null));
            return shown.get();
        });
    }
    private static void devices(ActivityScenario<MainActivity> ui)throws Exception {
        ready(ui);
        ui.onActivity(a->{Button b=visibleButton(a.getWindow().getDecorView(),a.getString(R.string.my_devices));assertNotNull("Visible My devices entry",b);b.performClick();});
        TouchRegression.until(()->"true".equals(TouchRegression.js(ui,"location.hash==='#/appareils' && document.querySelectorAll('[data-equipment=meross]').length===4")));
    }
    private static void orientation(ActivityScenario<MainActivity> ui,int requested,int expected)throws Exception {
        ui.onActivity(a->a.setRequestedOrientation(requested));
        TouchRegression.until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(a.hasWindowFocus() && a.getResources().getConfiguration().orientation==expected));return ready.get();});
        TouchRegression.until(()->"true".equals(TouchRegression.js(ui,"location.hash==='#/appareils' && document.querySelectorAll('[data-equipment=meross]').length===4")));
    }
    static void running(ActivityScenario<MainActivity> ui)throws Exception {
        devices(ui);
        orientation(ui,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,Configuration.ORIENTATION_LANDSCAPE);
        orientation(ui,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,Configuration.ORIENTATION_PORTRAIT);
        ui.recreate();
        TouchRegression.until(()->"true".equals(TouchRegression.js(ui,"location.hash==='#/appareils' && document.querySelectorAll('[data-equipment=meross]').length===4")));
        assertEquals("true",TouchRegression.js(ui,"(()=>{let a=document.querySelector('.rail-quick a[href=\"#/systeme\"]');if(!a)return false;let r=a.getBoundingClientRect();if(r.left<0||r.right>innerWidth||r.top<0||r.bottom>innerHeight)return false;a.click();return true})()"));
        TouchRegression.until(()->"true".equals(TouchRegression.js(ui,"location.hash==='#/systeme' && !!document.querySelector('a[href=\"#/appareils\"]')")));
        devices(ui);
        assertEquals("true",TouchRegression.js(ui,"(()=>{let a=document.querySelector('[data-configure-meross]');if(!a)return false;a.click();return true})()"));
        TouchRegression.screen(EquipmentSettingsActivity.class);
        TouchRegression.until(()->{
            AtomicReference<Boolean> ready=new AtomicReference<>(false);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                EquipmentSettingsActivity a=(EquipmentSettingsActivity)TouchRegression.current();
                ready.set(a!=null && a.meross.entries.size()==4 && a.meross.entries.get(0).control.isShown());
            });return ready.get();
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            EquipmentSettingsActivity a=(EquipmentSettingsActivity)TouchRegression.current();
            assertFalse(a.meross.entries.get(0).control.isEnabled());assertEquals("",a.meross.entries.get(0).key.getText().toString());a.finish();
        });
        devices(ui);
        ui.onActivity(MainActivity::showDevices);
        TouchRegression.evidence("DEVICES native header -> four named cards -> Configure -> direct Meross form; System reachable; portrait/landscape/recreation retained route; no command");
    }
    static void stopped(ActivityScenario<MainActivity> ui,Context app)throws Exception {
        String preserved=EquipmentStore.read(app).toString();
        ready(ui);
        ui.onActivity(a->{Button b=visibleButton(a.getWindow().getDecorView(),a.getString(R.string.my_devices));assertNotNull(b);b.performClick();});
        TouchRegression.screen(EquipmentSettingsActivity.class);
        TouchRegression.childButton(R.string.meross_devices);
        TouchRegression.until(()->{
            AtomicReference<Boolean> ready=new AtomicReference<>(false);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                EquipmentSettingsActivity a=(EquipmentSettingsActivity)TouchRegression.current();ready.set(a!=null && a.save.isEnabled() && a.meross.entries.size()==4);
            });return ready.get();
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            EquipmentSettingsActivity a=(EquipmentSettingsActivity)TouchRegression.current();MerossForm.Row row=a.meross.entries.get(0);
            assertTrue(row.control.isShown());assertTrue("Fresh local identity permits explicit confirmation",row.control.isEnabled());assertFalse(row.control.isChecked());
            row.control.performClick();assertNotNull(a.meross.controlDialog);assertTrue(a.meross.controlDialog.isShowing());
            a.meross.controlDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(row.control.isChecked());a.finish();
        });
        assertEquals(preserved,EquipmentStore.read(app).toString());
        TouchRegression.evidence("DEVICES native welcome -> My devices -> Meross -> manual confirmation cancelled; four associations unchanged; no save or SET");
    }
    private DevicesNavigationFixture(){}
}
