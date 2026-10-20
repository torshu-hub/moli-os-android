package io.moli.android;

import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Isolated emulator only. No external endpoint or real device configuration. */
@RunWith(AndroidJUnit4.class)
public final class EngineDiagnosticsTest {
    private static void state(EngineHost.State expected) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 30000;
        while (EngineHost.state() != expected && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(100);
        assertEquals(expected, EngineHost.state());
    }
    private static long[] check() throws Exception {
        long[] before = EngineHealth.snapshot();
        EngineHealth.check();
        long until = android.os.SystemClock.elapsedRealtime() + 6000;
        long[] result;
        do { Thread.sleep(50); result = EngineHealth.snapshot(); }
        while (result[2] + result[3] <= before[2] + before[3] && android.os.SystemClock.elapsedRealtime() < until);
        assertTrue(result[2] + result[3] > before[2] + before[3]);
        return result;
    }
    @Test public void liveHttpDetectsNativeLossEvenWhenHostSaysRunning() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!ProtectedKey.exists(context)) ProtectedKey.create(context, "4826");
        context.getSharedPreferences("host", Context.MODE_PRIVATE).edit().putBoolean("wanted", false).commit();
        long starts = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).getLong("starts", 0);
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            activity.onActivity(a -> context.startForegroundService(new Intent(context, MoliService.class)));
            state(EngineHost.State.RUNNING);
            assertEquals(1, check()[0]);
            assertFalse(EngineHealth.request(EngineHost.origin(), "synthetic-wrong-token"));
            long lease = EngineHost.currentLease();
            activity.recreate();
            assertEquals(lease, EngineHost.currentLease());
            assertEquals(1, check()[0]);
        }
        try {
            // UI is gone; service and real Rust HTTP must still answer.
            assertEquals(1, check()[0]);
            assertEquals(starts + 1, context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).getLong("starts", 0));
            NativeServer.stop(); // Simulated native loss in this disposable process only.
            assertEquals(EngineHost.State.RUNNING, EngineHost.state());
            assertEquals(0, check()[0]);
            assertTrue(EngineHealth.snapshot()[3] > 0);
        } finally {
            context.startService(new Intent(context, MoliService.class).setAction(MoliService.STOP));
            state(EngineHost.State.STOPPED);
        }
        assertEquals(0, EngineHealth.snapshot()[0]);
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            assertEquals(EngineHost.State.STOPPED, EngineHost.state());
            activity.onActivity(a -> context.startForegroundService(new Intent(context, MoliService.class)));
            state(EngineHost.State.RUNNING);
            assertEquals(1, check()[0]);
            assertEquals(starts + 2, context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).getLong("starts", 0));
            activity.onActivity(a -> context.startService(new Intent(context, MoliService.class).setAction(MoliService.STOP)));
            state(EngineHost.State.STOPPED);
        }
    }
}
