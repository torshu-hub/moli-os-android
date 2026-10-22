package io.moli.android;

import android.content.*;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real system Back dispatch and WebView routes; disposable emulator, no provider calls. */
@RunWith(AndroidJUnit4.class)
public final class BackNavigationTest {
    private interface Check { boolean ready() throws Exception; }
    private static void until(Check check) throws Exception {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (true) {
            if (check.ready()) return;
            if (SystemClock.elapsedRealtime() >= end) fail("Navigation condition within timeout");
            Thread.sleep(50);
        }
    }
    private static String js(ActivityScenario<MainActivity> ui, String script) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        ui.onActivity(a -> a.web.evaluateJavascript(script, result::set));
        until(() -> result.get() != null);
        return result.get();
    }
    private static void back(ActivityScenario<MainActivity> ui) throws Exception {
        // RESUMED alone does not mean the window can receive system keys,
        // especially immediately after recreating/resuming it in CI.
        until(() -> {
            AtomicReference<Boolean> focused = new AtomicReference<>(false);
            ui.onActivity(a -> focused.set(a.hasWindowFocus()));
            return focused.get();
        });
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static void resumed(ActivityScenario<MainActivity> ui) {
        assertEquals(Lifecycle.State.RESUMED, ui.getState());
    }
    private static void home(ActivityScenario<MainActivity> ui) throws Exception {
        until(() -> "true".equals(js(ui, "(location.hash==='' || location.hash==='#/') && !!document.querySelector('[data-tablet-receipt]')")));
        resumed(ui);
    }
    private static void open(ActivityScenario<MainActivity> ui, String route) throws Exception {
        assertEquals("true", js(ui, "(()=>{const a=document.querySelector('a[href=\"#/" + route + "\"]');if(!a)return false;a.click();return true})()"));
        until(() -> "true".equals(js(ui, "location.hash==='#/" + route + "' && !document.querySelector('[data-tablet-receipt]')")));
    }
    private static Button button(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
    @Test public void backReturnsHomeThenConfirmsExitWithoutStoppingEngine() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(EngineHost.State.STOPPED, EngineHost.state());
        if (!ProtectedKey.exists(app)) ProtectedKey.create(app, "4826");
        app.getSharedPreferences("host", Context.MODE_PRIVATE).edit().putBoolean("wanted", false).commit();
        ProfileStore.remove(app, "weather"); // No remote call is needed for navigation.
        try {
            try (ActivityScenario<MainActivity> ui = ActivityScenario.launch(MainActivity.class)) {
                // Use the real Start control: it both starts AND binds the UI.
                // Sending a service intent alone leaves this initially stopped UI unbound.
                ui.onActivity(a -> {
                    Button start = button(a.getWindow().getDecorView(), a.getString(R.string.start));
                    assertNotNull(start); assertTrue(start.isEnabled()); start.performClick();
                });
                until(() -> EngineHost.state() == EngineHost.State.RUNNING);
                long lease = EngineHost.currentLease();
                home(ui);
                open(ui, "dehors"); back(ui); home(ui);
                back(ui); resumed(ui); // First Back at Home only warns.
                open(ui, "pieces");
                ui.onActivity(MainActivity::onBackPressed); // Legacy dispatch used before API 33.
                home(ui);
                back(ui); resumed(ui); // Changing page has cancelled the previous warning.
                Thread.sleep(2200);
                back(ui); resumed(ui); // An expired warning cannot close the interface.
                ui.moveToState(Lifecycle.State.CREATED);
                ui.moveToState(Lifecycle.State.RESUMED);
                back(ui); resumed(ui); // Leaving the screen also cancels confirmation.
                back(ui); until(() -> ui.getState() == Lifecycle.State.DESTROYED);
                assertEquals(lease, EngineHost.currentLease());
                assertEquals(EngineHost.State.RUNNING, EngineHost.state());
                assertTrue("Real Rust HTTP still answers after closing UI", EngineHealth.request(EngineHost.origin(), EngineHost.token()));
            }
            try (ActivityScenario<MainActivity> reopened = ActivityScenario.launch(MainActivity.class)) {
                home(reopened);
                back(reopened); resumed(reopened);
                assertTrue(EngineHealth.request(EngineHost.origin(), EngineHost.token()));
            }
        } finally {
            app.startService(new Intent(app, MoliService.class).setAction(MoliService.STOP));
            until(() -> EngineHost.state() == EngineHost.State.STOPPED);
        }
    }
}
