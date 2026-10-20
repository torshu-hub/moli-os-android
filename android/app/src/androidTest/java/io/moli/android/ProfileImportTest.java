package io.moli.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public final class ProfileImportTest {
    private interface Condition { boolean ready() throws Exception; }
    private static void until(Condition c) throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+30000;
        while (!c.ready() && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(100);
        assertTrue("Expected state within timeout",c.ready());
    }
    private static Button button(View view,String label) {
        if (view instanceof Button && label.contentEquals(((Button)view).getText())) return (Button)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=button(((ViewGroup)view).getChildAt(i),label); if (found!=null) return found;
        }
        return null;
    }
    private static void click(IntegrationsActivity a,int label) { Button b=button(a.getWindow().getDecorView(),a.getString(label)); assertNotNull(b); b.performClick(); }
    private static void uiContains(ActivityScenario<IntegrationsActivity> s,String value,boolean review) throws Exception {
        until(() -> { AtomicBoolean found=new AtomicBoolean(); s.onActivity(a -> found.set((review?a.review:a.values).getText().toString().contains(value))); return found.get(); });
    }
    @Test public void documentReviewApprovalPersistenceAndAlterationThroughUi() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context app=instrumentation.getTargetContext();
        if (!ProtectedKey.exists(app)) ProtectedKey.create(app,"4826");
        app.getSharedPreferences("host",Context.MODE_PRIVATE).edit().putBoolean("wanted",false).commit();
        String example=ProfileStore.bounded(app.getAssets().open("synthetic-temperature.toml"),ProfileStore.LIMIT);
        Uri uri=Uri.parse("content://io.moli.synthetic.documents/profile");
        Instrumentation.ActivityMonitor picker=instrumentation.addMonitor(new IntentFilter(Intent.ACTION_OPEN_DOCUMENT),
                new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(uri)),true);
        try (ActivityScenario<IntegrationsActivity> ui=ActivityScenario.launch(IntegrationsActivity.class)) {
            ui.onActivity(a -> a.choose.performClick());
            uiContains(ui,"SHA-256",true); assertEquals(1,picker.getHits());
            assertFalse(ProfileStore.state(app).has("import"));
            ProfileStore.atomic(ProfileStore.stage(app),example+"\n# changed after review\n");
            ui.onActivity(a -> a.approve.performClick());
            until(() -> { AtomicBoolean found=new AtomicBoolean(); ui.onActivity(a -> found.set(a.message.getText().toString().equals(a.getString(R.string.integration_changed)))); return found.get(); });
            assertFalse(ProfileStore.state(app).has("import"));
            ui.onActivity(a -> a.choose.performClick()); uiContains(ui,"SHA-256",true);
            ui.onActivity(a -> a.approve.performClick()); until(() -> ProfileStore.state(app).has("import"));
            until(() -> EngineHost.state()==EngineHost.State.STOPPED);
            ui.onActivity(a -> { click(a,R.string.integration_demo); a.confirmationDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); });
            until(() -> ProfileStore.state(app).optBoolean("demo")); until(() -> EngineHost.state()==EngineHost.State.STOPPED);
            ui.onActivity(a -> click(a,R.string.integration_rollback)); until(() -> !ProfileStore.state(app).optBoolean("demo"));
            until(() -> EngineHost.state()==EngineHost.State.STOPPED);
            ui.onActivity(a -> click(a,R.string.integration_rollback)); until(() -> ProfileStore.state(app).optBoolean("demo"));
            until(() -> EngineHost.state()==EngineHost.State.STOPPED);
            ui.onActivity(a -> click(a,R.string.start)); until(() -> EngineHost.state()==EngineHost.State.RUNNING);
            uiContains(ui,"42.5",false); uiContains(ui,"Demo / Simulation",false);
            long generation=EngineHost.currentLease(); ui.recreate();
            uiContains(ui,"42.5",false); assertEquals(generation,EngineHost.currentLease());
            ui.onActivity(a -> click(a,R.string.stop)); until(() -> EngineHost.state()==EngineHost.State.STOPPED);
            // A real engine restart reconstructs the private absolute paths and fixture port.
            ui.onActivity(a -> click(a,R.string.start)); until(() -> EngineHost.state()==EngineHost.State.RUNNING); uiContains(ui,"42.5",false);
            ui.onActivity(a -> click(a,R.string.stop)); until(() -> EngineHost.state()==EngineHost.State.STOPPED);
            String hash=ProfileStore.state(app).getJSONObject("import").getString("sha256");
            File selected=new File(ProfileStore.data(app),"android-profiles/"+hash+".toml");
            ProfileStore.atomic(selected,example+"\n# altered installed bytes\n");
            ui.onActivity(a -> click(a,R.string.start)); until(() -> EngineHost.state()==EngineHost.State.ERROR);
            assertTrue(EngineHost.profileFailure);
            ui.onActivity(a -> click(a,R.string.stop)); until(() -> EngineHost.state()==EngineHost.State.STOPPED);
            // Removing only the invalid profile leaves Demo and engine data intact.
            ui.onActivity(a -> click(a,R.string.integration_remove_import)); until(() -> !ProfileStore.state(app).has("import"));
            assertTrue(ProfileStore.state(app).optBoolean("demo"));
        } finally {
            instrumentation.removeMonitor(picker);
            EngineHost.stop(EngineHost.currentLease()); until(() -> EngineHost.state()==EngineHost.State.STOPPED);
        }
    }
}
