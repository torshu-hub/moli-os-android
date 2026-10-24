package io.moli.android;

import android.content.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.concurrent.TimeUnit;

/** Live public API observation, separately reported from deterministic fixtures. */
@RunWith(AndroidJUnit4.class)
public final class WeatherObservationTest {
    @Test public void observePublicHttpsWithoutTreatingAnOutageAsAFixtureSuccess() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!ProtectedKey.exists(app)) ProtectedKey.create(app,"4826");
        EngineHost.maintain(() -> { ProfileStore.weather(app); return null; }).get(10,TimeUnit.SECONDS);
        String evidence="NETWORK_UNAVAILABLE: no fresh weather received during bounded observation\n";
        try (ActivityScenario<IntegrationsActivity> ui=ActivityScenario.launch(IntegrationsActivity.class)) {
            ui.onActivity(a -> app.startForegroundService(new Intent(app,MoliService.class)));
            long until=android.os.SystemClock.elapsedRealtime()+30000;
            while (EngineHost.state()!=EngineHost.State.RUNNING && EngineHost.state()!=EngineHost.State.ERROR && android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(100);
            assertEquals(EngineHost.State.RUNNING,EngineHost.state());
            do {
                HttpURLConnection c=(HttpURLConnection)new URL(EngineHost.origin()+"/api/devices").openConnection();
                c.setConnectTimeout(2000);c.setReadTimeout(2000);c.setRequestProperty("Cookie","moli_android="+EngineHost.token());
                JSONObject snap;
                try { assertEquals(200,c.getResponseCode()); snap=new JSONObject(ProfileStore.bounded(c.getInputStream(),262144)); }
                finally { c.disconnect(); }
                JSONArray devices=snap.getJSONArray("devices");
                for(int i=0;i<devices.length();i++) {
                    JSONObject d=devices.getJSONObject(i);
                    if (!"android-weather".equals(d.optString("instance"))) continue;
                    JSONObject state=d.getJSONObject("state"), temp=state.optJSONObject("temperature"), humidity=state.optJSONObject("humidity");
                    if (temp!=null && humidity!=null && !temp.isNull("value") && !humidity.isNull("value")
                            && d.optBoolean("online") && System.currentTimeMillis()-temp.getLong("ts")<30000) {
                        evidence="LIVE_HTTPS_OBSERVED via Rust Open-Meteo profile; public Paris test coordinates\n"
                                +"temperature="+temp.get("value")+" humidity="+humidity.get("value")+" received_ms="+temp.getLong("ts")+"\n";
                    }
                }
                if (evidence.startsWith("LIVE_")) break;
                Thread.sleep(500);
            } while(android.os.SystemClock.elapsedRealtime()<until);
            long lease=EngineHost.currentLease();
            ui.onActivity(a -> a.probe.performClick());
            long end=android.os.SystemClock.elapsedRealtime()+20000;
            java.util.concurrent.atomic.AtomicReference<String> outcome=new java.util.concurrent.atomic.AtomicReference<>("pending");
            do { Thread.sleep(100); ui.onActivity(a -> outcome.set(a.probeOutcome)); }
            while("pending".equals(outcome.get()) && android.os.SystemClock.elapsedRealtime()<end);
            assertNotEquals("pending",outcome.get()); assertEquals(lease,EngineHost.currentLease());
            java.util.concurrent.atomic.AtomicReference<JSONObject> probe=new java.util.concurrent.atomic.AtomicReference<>();
            ui.onActivity(a -> probe.set(a.probeObservation));
            evidence += probe.get()==null ? "NO_LIVE_PROBE_OBSERVED: one-time check did not return fresh weather\n" :
                    "LIVE_ONE_SHOT_HTTPS_OBSERVED temperature="+probe.get().getDouble("temperature")+" humidity="+probe.get().getDouble("humidity")+" received_ms="+probe.get().getLong("received_ms")+"\n";
            ui.onActivity(a -> a.probe.performClick());
            end=android.os.SystemClock.elapsedRealtime()+5000;
            do { Thread.sleep(100); ui.onActivity(a -> outcome.set(a.probeOutcome)); }
            while("pending".equals(outcome.get()) && android.os.SystemClock.elapsedRealtime()<end);
            assertEquals("limited",outcome.get()); assertEquals(lease,EngineHost.currentLease());
        } finally {
            app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP));
            long until=android.os.SystemClock.elapsedRealtime()+30000;
            while(EngineHost.state()!=EngineHost.State.STOPPED && android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(100);
            assertEquals(EngineHost.State.STOPPED,EngineHost.state());
            Files.write(new File(app.getFilesDir(),"weather-evidence.txt").toPath(),evidence.getBytes(StandardCharsets.UTF_8));
        }
    }
}
