package io.moli.android;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Actual package-manager update from J1 Java to code2 Java with a disposable test certificate. */
@RunWith(AndroidJUnit4.class)
public final class ManagementContinuityTest {
    @Test public void seedCode1() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(1,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        assertFalse(ProtectedKey.exists(app)); ProtectedKey.create(app,"482913");
        app.startForegroundService(new Intent(app,MoliService.class)); Beta2JourneyTest.waitState(EngineHost.State.RUNNING);
        app.startForegroundService(new Intent(app,MoliService.class).setAction(MoliService.STOP)); Beta2JourneyTest.waitState(EngineHost.State.STOPPED);
        String original=EngineHost.inspect(()->new ConfigStore(app).read()).get(10,TimeUnit.SECONDS);
        assertTrue(app.getSharedPreferences("code2-update-proof",Context.MODE_PRIVATE).edit()
            .putString("config",Beta2JourneyTest.digest(original))
            .putString("key",Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master"))).commit());
    }
    @Test public void code2KeepsCode1Data() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(2,app.getPackageManager().getPackageInfo(app.getPackageName(),0).getLongVersionCode());
        SharedPreferences proof=app.getSharedPreferences("code2-update-proof",Context.MODE_PRIVATE);
        assertEquals(proof.getString("config","missing"),Beta2JourneyTest.digest(new ConfigStore(app).read()));
        assertEquals(proof.getString("key","missing"),Beta2JourneyTest.digest(ProtectedKey.open(app).getString("master")));
    }
}
