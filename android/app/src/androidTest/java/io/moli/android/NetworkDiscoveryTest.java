package io.moli.android;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Synthetic announcements through the actual Rust parser and Android UI. No LAN/account access. */
@RunWith(AndroidJUnit4.class)
public final class NetworkDiscoveryTest {
    private interface Check { boolean ready() throws Exception; }
    private static void until(Check check) throws Exception {
        long until=SystemClock.elapsedRealtime()+15000;
        while(!check.ready()) { if(SystemClock.elapsedRealtime()>until) fail("Discovery UI within deadline"); Thread.sleep(50); }
    }
    private static Button button(View root,String label) {
        if(root instanceof Button && ((Button)root).getText().toString().equals(label)) return (Button)root;
        if(root instanceof ViewGroup) for(int i=0;i<((ViewGroup)root).getChildCount();i++) { Button b=button(((ViewGroup)root).getChildAt(i),label); if(b!=null) return b; }
        return null;
    }
    static final class Fixture implements DiscoverySearch {
        Listener listener;
        volatile boolean closed;
        public void start(Listener l) { listener=l; }
        public void close() { closed=true; listener.finished(End.CANCELLED); }
        void complete(End reason) { listener.finished(reason); }
    }
    @Test public void evidenceCopyConsentCancellationAndSavedConfiguration() throws Exception {
        assertTrue("Disposable emulator only",android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        NetworkEvidence.Entry ipp=NetworkEvidence.mdns("192.168.1.20",631,"Fictitious printer","_ipp._tcp.","ipp/print".getBytes(StandardCharsets.UTF_8));
        NetworkEvidence.Entry http=new NetworkEvidence.Entry("192.168.1.21",8080,"Fictitious HTTP service",NetworkEvidence.Kind.HTTP,NetworkEvidence.Proof.OPEN_PORT,"","");
        JSONObject request=new JSONObject().put("source","192.168.1.1").put("packet","HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1:8080/root.xml\r\n\r\n");
        assertFalse(ProfileStore.nativeResult(request.toString(),5).getBoolean("igd"));
        request.put("description","<root><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType><friendlyName>Fictitious gateway</friendlyName><service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/wan</controlURL></service></device></root>");
        JSONObject gateway=ProfileStore.nativeResult(request.toString(),5); assertTrue(gateway.getBoolean("igd"));
        NetworkEvidence.Entry igd=new NetworkEvidence.Entry("192.168.1.1",8080,gateway.getString("name"),NetworkEvidence.Kind.IGD,NetworkEvidence.Proof.DESCRIPTION,"",gateway.getString("location"));
        try { ProfileStore.nativeResult(new JSONObject(request.toString()).put("source","192.168.1.2").toString(),5); fail("Cross-host description refused"); } catch(ProfileStore.Rejected expected) {}
        Fixture first=new Fixture();
        try(ActivityScenario<LocalNetworkActivity> ui=ActivityScenario.launch(LocalNetworkActivity.class)) {
            ui.onActivity(a -> { assertTrue(a.results.list().isEmpty()); assertFalse(a.cancel.isEnabled()); a.begin(first); });
            first.listener.found(ipp); first.listener.found(ipp); first.listener.found(http); first.listener.found(igd);
            until(() -> { AtomicReference<Boolean> done=new AtomicReference<>(false); ui.onActivity(a -> done.set(a.cards.getChildCount()==3 && a.hasWindowFocus())); return done.get(); });
            ui.onActivity(a -> {
                assertFalse(a.search.isEnabled()); assertTrue(a.cancel.isEnabled());
                Button copy=button(a.cards,a.getString(R.string.network_copy)); assertNotNull(copy); copy.performClick();
                assertEquals(ipp.ip,a.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString());
                assertEquals(NetworkEvidence.Kind.HTTP,a.results.list().get(1).kind); // No invented MSS310 identification.
                a.cancel.performClick();
            });
            until(() -> { AtomicReference<Boolean> done=new AtomicReference<>(false); ui.onActivity(a -> done.set(a.search.isEnabled())); return done.get(); });
            assertTrue(first.closed);
            first.listener.found(new NetworkEvidence.Entry("192.168.1.99",80,"Late",NetworkEvidence.Kind.HTTP,NetworkEvidence.Proof.OPEN_PORT,"",""));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            ui.onActivity(a -> assertEquals(3,a.cards.getChildCount()));
            Fixture changed=new Fixture(); ui.onActivity(a -> a.begin(changed)); changed.listener.found(ipp); changed.complete(DiscoverySearch.End.NETWORK_CHANGED);
            until(() -> { AtomicReference<Boolean> done=new AtomicReference<>(false); ui.onActivity(a -> done.set(a.search.isEnabled() && a.cards.getChildCount()==0 && a.state.getText().toString().equals(a.getString(R.string.network_changed)))); return done.get(); });
            Fixture missing=new Fixture(); ui.onActivity(a -> a.begin(missing)); missing.complete(DiscoverySearch.End.UNAVAILABLE);
            until(() -> { AtomicReference<Boolean> done=new AtomicReference<>(false); ui.onActivity(a -> done.set(a.state.getText().toString().equals(a.getString(R.string.network_unavailable)))); return done.get(); });
            Fixture expired=new Fixture(); ui.onActivity(a -> a.begin(expired)); expired.listener.found(ipp); expired.complete(DiscoverySearch.End.COMPLETE);
            until(() -> { AtomicReference<Boolean> done=new AtomicReference<>(false); ui.onActivity(a -> done.set(a.search.isEnabled() && a.progress.getProgress()==20)); return done.get(); });
            Fixture leaving=new Fixture(); ui.onActivity(a -> a.begin(leaving));
            ui.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); assertTrue(leaving.closed);
        }
        assertEquals(1,app.getSharedPreferences("network-discovery",Context.MODE_PRIVATE).getAll().size());
        assertTrue(app.getSharedPreferences("network-discovery",Context.MODE_PRIVATE).getLong("last",0)>0);
        try {
            try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(LocalNetworkActivity.selection(app,ipp,"ipp"))) {
                until(() -> { AtomicReference<Boolean> ready=new AtomicReference<>(false); ui.onActivity(a -> ready.set(a.host.getText().toString().equals(ipp.ip))); return ready.get(); });
                ui.onActivity(a -> { assertFalse(a.printer.isChecked()); assertFalse(a.start.isEnabled()); assertEquals("631",a.port.getText().toString()); assertEquals("/ipp/print",a.path.getText().toString()); a.printer.setChecked(true); a.save.performClick(); });
                until(() -> { AtomicReference<Boolean> ready=new AtomicReference<>(false); ui.onActivity(a -> ready.set(a.message.getText().toString().equals(a.getString(R.string.equipment_saved)))); return ready.get(); });
                ui.recreate();
                until(() -> { AtomicReference<Boolean> ready=new AtomicReference<>(false); ui.onActivity(a -> ready.set(a.printer.isChecked() && a.host.getText().toString().equals(ipp.ip))); return ready.get(); });
            }
            try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(LocalNetworkActivity.selection(app,http,"meross"))) {
                until(() -> { AtomicReference<Boolean> ready=new AtomicReference<>(false); ui.onActivity(a -> ready.set(a.meross.entries.size()==1)); return ready.get(); });
                ui.onActivity(a -> {
                    MerossForm.Row row=a.meross.entries.get(0); assertEquals(http.ip,row.host.getText().toString()); assertEquals("80",row.port.getText().toString());
                    assertFalse(row.read.isChecked()); assertTrue(row.key.getText().toString().isEmpty()); assertTrue(row.name.getText().toString().isEmpty()); assertFalse(a.start.isEnabled());
                });
                assertFalse(EquipmentStore.read(app).has("meross") && EquipmentStore.read(app).getJSONArray("meross").length()>0);
            }
            try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(LocalNetworkActivity.selection(app,igd,"igd"))) {
                until(() -> { AtomicReference<Boolean> ready=new AtomicReference<>(false); ui.onActivity(a -> ready.set(a.location.getText().toString().equals(igd.location))); return ready.get(); });
                ui.onActivity(a -> { assertFalse(a.gateway.isChecked()); assertFalse(a.start.isEnabled());assertTrue(a.gateway.isShown());assertFalse(a.printer.isShown());assertFalse(a.meross.add.isShown()); });
            }
            // A user may explicitly try IPP on an HTTP-only candidate; no protocol/model is invented.
            try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(LocalNetworkActivity.selection(app,http,"ipp"))){
                until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(a.host.getText().toString().equals(http.ip)&&a.save.isEnabled()));return ready.get();});
                ui.onActivity(a->{assertTrue(a.printer.isShown());assertFalse(a.gateway.isShown());assertFalse(a.meross.add.isShown());assertFalse(a.printer.isChecked());assertEquals("631",a.port.getText().toString());assertEquals("/ipp/print",a.path.getText().toString());assertEquals(a.getString(R.string.ipp_unrecognized),a.message.getText().toString());});
            }
            assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        } finally { EngineHost.maintain(() -> { EquipmentStore.save(app,new JSONObject()); return null; }).get(); }
    }
}
