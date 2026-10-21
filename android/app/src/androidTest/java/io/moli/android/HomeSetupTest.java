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
import java.util.concurrent.atomic.*;

@RunWith(AndroidJUnit4.class)
public final class HomeSetupTest {
    private interface Check { boolean ready() throws Exception; }
    private static void until(Check check) throws Exception {
        long end=android.os.SystemClock.elapsedRealtime()+65000;
        while(!check.ready() && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(100);
        assertTrue("Home setup observation within timeout",check.ready());
    }
    private static JSONObject get(String path) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(EngineHost.origin()+path).openConnection();
        c.setConnectTimeout(2000);c.setReadTimeout(3000);c.setRequestProperty("Cookie","moli_android="+EngineHost.token());
        try {assertEquals(200,c.getResponseCode());return new JSONObject(ProfileStore.bounded(c.getInputStream(),524288));}
        finally {c.disconnect();}
    }
    @Test public void configureWithoutTomlAndObserveTabletInExistingDashboard() throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        if(!ProtectedKey.exists(app)) ProtectedKey.create(app,"4826");
        assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        try(ActivityScenario<HomeSettingsActivity> ui=ActivityScenario.launch(HomeSettingsActivity.class)) {
            until(() -> {AtomicBoolean ready=new AtomicBoolean();ui.onActivity(a->ready.set(a.save.isEnabled()));return ready.get();});
            ui.onActivity(a->{a.weather.setChecked(true);a.label.setText("Lieu test public");a.latitude.setText("91");a.longitude.setText("4.8357");a.rooms.setText("Salon\nBureau\nSalon");a.save.performClick();assertEquals(a.getString(R.string.home_invalid),a.message.getText().toString());});
            ui.onActivity(a->{a.latitude.setText("45,764");a.save.performClick();});
            until(()->{AtomicBoolean done=new AtomicBoolean();ui.onActivity(a->done.set(a.message.getText().toString().equals(a.getString(R.string.home_saved))));return done.get();});
            assertEquals(45.764,ProfileStore.state(app).getJSONObject("weather").getJSONObject("location").getDouble("latitude"),0.00001);
            assertEquals(2,ProfileStore.home(app).getJSONArray("rooms").length());
            ui.recreate();
            until(()->{AtomicBoolean ready=new AtomicBoolean();ui.onActivity(a->ready.set(a.save.isEnabled() && "Lieu test public".equals(a.label.getText().toString())));return ready.get();});
        }
        try {
            app.startForegroundService(new Intent(app,MoliService.class));until(()->EngineHost.state()==EngineHost.State.RUNNING);
            JSONObject home=get("/api/home"); assertEquals("Lieu test public",home.getJSONObject("android").getString("location"));assertEquals(2,home.getJSONArray("rooms").length());
            until(()->{
                JSONArray ds=get("/api/devices").getJSONArray("devices");
                for(int i=0;i<ds.length();i++){JSONObject d=ds.getJSONObject(i);if(!"android-tablet:tablet".equals(d.getString("id")))continue;
                    JSONObject state=d.getJSONObject("state");
                    if(!d.optBoolean("online") || state.optJSONObject("battery")==null)return false;
                    double battery=state.getJSONObject("battery").optDouble("value"); assertTrue(battery>=0 && battery<=100);
                    assertEquals(TabletReading.json(app).getString("network"),state.getJSONObject("network").getString("value"));
                    for(int j=0;j<d.getJSONArray("points").length();j++) assertFalse(d.getJSONArray("points").getJSONObject(j).getJSONObject("access").optBoolean("write"));
                    return true;
                }return false;
            });
            long lease=EngineHost.currentLease();
            try(ActivityScenario<MainActivity> main=ActivityScenario.launch(MainActivity.class)) {
                until(()->{AtomicReference<String> dom=new AtomicReference<>();main.onActivity(a->a.web.evaluateJavascript("document.body.innerText.includes('Cette tablette') && document.body.innerText.includes('Lieu test public')",dom::set));
                    long end=android.os.SystemClock.elapsedRealtime()+1000;while(dom.get()==null && android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(10);return "true".equals(dom.get());});
                AtomicLong first = new AtomicLong();
                until(() -> {
                    AtomicReference<String> sample = new AtomicReference<>();
                    main.onActivity(a -> a.web.evaluateJavascript("(()=>{const t=document.querySelector('[data-tablet-receipt]');return t?.dataset.tabletStatus==='fresh' ? Number(t.dataset.tabletReceipt) : 0})()", sample::set));
                    long end=android.os.SystemClock.elapsedRealtime()+1000;while(sample.get()==null && android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(10);
                    if(sample.get()!=null && sample.get().matches("[0-9]+")) first.set(Long.parseLong(sample.get()));
                    return first.get()>0;
                });
                long initial=first.get();
                until(() -> {
                    AtomicReference<String> renewed=new AtomicReference<>();
                    main.onActivity(a -> a.web.evaluateJavascript("(()=>{const t=document.querySelector('[data-tablet-receipt]');return t?.dataset.tabletStatus==='fresh' && Number(t.dataset.tabletReceipt)>"+initial+"})()", renewed::set));
                    long end=android.os.SystemClock.elapsedRealtime()+1000;while(renewed.get()==null && android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(10);
                    return "true".equals(renewed.get());
                });
                assertEquals(lease,EngineHost.currentLease());
            }
        } finally {app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP));until(()->EngineHost.state()==EngineHost.State.STOPPED);}
    }
}
