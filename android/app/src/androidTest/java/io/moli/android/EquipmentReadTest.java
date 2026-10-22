package io.moli.android;

import android.content.*;
import android.app.Activity;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.util.function.Consumer;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Native IPP/IGD against fixtures hosted inside a disposable emulator only. */
@RunWith(AndroidJUnit4.class)
public final class EquipmentReadTest {
    private interface Check { boolean ready() throws Exception; }
    private static void until(Check check) throws Exception {
        long end = SystemClock.elapsedRealtime() + 65000;
        while (true) { if (check.ready()) return; if (SystemClock.elapsedRealtime() >= end) fail("Equipment observation within timeout"); Thread.sleep(100); }
    }
    private static JSONObject get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(EngineHost.origin()+path).openConnection();
        c.setConnectTimeout(2000); c.setReadTimeout(3000); c.setRequestProperty("Cookie","moli_android="+EngineHost.token());
        try { assertEquals(200,c.getResponseCode()); return new JSONObject(ProfileStore.bounded(c.getInputStream(),524288)); }
        finally { c.disconnect(); }
    }
    private static String js(ActivityScenario<MainActivity> ui,String code) throws Exception {
        AtomicReference<String> result = new AtomicReference<>(); ui.onActivity(a -> a.web.evaluateJavascript(code,result::set));
        until(() -> result.get()!=null); return result.get();
    }
    private static void saved(EquipmentScreen ui) throws Exception {
        until(() -> { AtomicReference<Boolean> ok = new AtomicReference<>(false); ui.onActivity(a -> ok.set(a.message.getText().toString().equals(a.getString(R.string.equipment_saved)) && a.start.isEnabled())); return ok.get(); });
    }
    private static String localAddress() throws Exception {
        for (NetworkInterface net : Collections.list(NetworkInterface.getNetworkInterfaces()))
            for (InetAddress address : Collections.list(net.getInetAddresses()))
                if (address instanceof Inet4Address && address.isSiteLocalAddress() && !address.isLoopbackAddress()) return address.getHostAddress();
        throw new IOException("Disposable emulator needs a private IPv4 interface");
    }
    @Test public void approveReadOnlyEquipmentAndObserveNativeResponsesWithoutCommands() throws Exception {
        assertTrue("Disposable emulator only", android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"));
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(EngineHost.State.STOPPED, EngineHost.state());
        if (!ProtectedKey.exists(app)) ProtectedKey.create(app,"4826");
        app.getSharedPreferences("host",Context.MODE_PRIVATE).edit().putBoolean("wanted",false).commit();
        ProfileStore.remove(app,"weather");
        String address = localAddress();
        try (Fixture fixture = new Fixture(); ActivityScenario<MainActivity> home = ActivityScenario.launch(MainActivity.class)) {
            try {
                try (EquipmentScreen ui = EquipmentScreen.open(home)) {
                    until(() -> { AtomicReference<Boolean> ready = new AtomicReference<>(false); ui.onActivity(a -> ready.set(a.save.isEnabled())); return ready.get(); });
                    ui.onActivity(a -> {
                        a.printer.setChecked(true); a.host.setText("8.8.8.8"); a.port.setText(String.valueOf(fixture.port())); a.path.setText("/ipp/print");
                        a.save.performClick(); assertEquals(a.getString(R.string.equipment_invalid),a.message.getText().toString());
                        assertFalse(a.start.isEnabled()); a.host.setText(address); a.gateway.setChecked(true);
                        a.location.setText("http://"+address+":"+fixture.port()+"/root.xml"); a.save.performClick();
                    });
                    saved(ui); assertEquals(0,fixture.count());
                    ui.recreate(); savedAfterRecreation(ui,address); assertEquals(0,fixture.count());
                    ui.onActivity(a -> a.start.performClick());
                }
                until(() -> EngineHost.state()==EngineHost.State.RUNNING);
                home.onActivity(MainActivity::showDevices);
                until(() -> {
                    fixture.check(); JSONArray devices = get("/api/devices").getJSONArray("devices"); boolean printer=false,gateway=false;
                    for (int i=0;i<devices.length();i++) {
                        JSONObject d = devices.getJSONObject(i); String instance=d.getString("instance");
                        if (!instance.equals("android-printer") && !instance.equals("android-gateway")) continue;
                        if (!d.optBoolean("online")) continue;
                        for (int j=0;j<d.getJSONArray("points").length();j++) assertFalse(d.getJSONArray("points").getJSONObject(j).getJSONObject("access").optBoolean("write"));
                        JSONObject state=d.getJSONObject("state");
                        if (instance.equals("android-printer") && state.optJSONObject("responding")!=null) {
                            assertFalse(d.optBoolean("printer")); // No PrintSink registered, even after model/ink upsert.
                            assertEquals("MG3600 series",d.getString("model")); // Existing fixture, NOT an MG6600 compatibility claim.
                            assertEquals(0,state.getJSONObject("ink_1").getInt("value"));
                            assertEquals("idle",state.getJSONObject("state").getString("value")); printer=true;
                        }
                        if (instance.equals("android-gateway") && state.optJSONObject("line_down")!=null) {
                            assertTrue(state.getJSONObject("connected").getBoolean("value"));
                            assertEquals(100.0,state.getJSONObject("line_down").getDouble("value"),0.01); gateway=true;
                        }
                    }
                    return printer && gateway;
                });
                until(() -> "true".equals(js(home,"document.querySelector('[data-equipment=printer]')?.dataset.equipmentStatus==='fresh' && document.querySelector('[data-equipment=gateway]')?.dataset.equipmentStatus==='fresh' && document.body.innerText.includes('MG3600 series')")));
                long lease = EngineHost.currentLease();
                fixture.printerAvailable=false;
                until(() -> { fixture.check(); return "true".equals(js(home,"document.querySelector('[data-equipment=printer]')?.dataset.equipmentStatus==='unavailable'")); });
                assertEquals(lease,EngineHost.currentLease());
                app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP)); until(() -> EngineHost.state()==EngineHost.State.STOPPED);
                try (EquipmentScreen ui = EquipmentScreen.open(home)) {
                    savedAfterRecreation(ui,address);
                    ui.onActivity(a -> { a.printer.setChecked(false); a.gateway.setChecked(false); a.save.performClick(); });
                    saved(ui); int before=fixture.count(); ui.onActivity(a -> a.start.performClick());
                    until(() -> EngineHost.state()==EngineHost.State.RUNNING);
                home.onActivity(MainActivity::showDevices);
                    JSONArray drivers=get("/api/devices").getJSONArray("drivers");
                    for(int i=0;i<drivers.length();i++) assertFalse(drivers.getJSONObject(i).getString("instance").startsWith("android-printer") || drivers.getJSONObject(i).getString("instance").equals("android-gateway"));
                    Thread.sleep(1200); assertEquals(before,fixture.count());
                }
                fixture.check();
                assertTrue("Only fixture reads reached the wire",fixture.count()>0);
            } finally {
                app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP)); until(() -> EngineHost.state()==EngineHost.State.STOPPED);
                EngineHost.maintain(() -> { EquipmentStore.save(app,new JSONObject()); return null; }).get();
            }
        }
    }
    private static void savedAfterRecreation(EquipmentScreen ui,String address) throws Exception {
        until(() -> { AtomicReference<Boolean> ready=new AtomicReference<>(false); ui.onActivity(a -> ready.set(a.start.isEnabled() && address.equals(a.host.getText().toString()) && a.printer.isChecked() && a.gateway.isChecked())); return ready.get(); });
    }
    /** ActivityScenario's invoker always adds CLEAR_TASK, even with an explicit
     * Intent. Open the child like the app does and observe its actual lifecycle. */
    private static final class EquipmentScreen implements AutoCloseable {
        private static EquipmentSettingsActivity current() {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if (activity instanceof EquipmentSettingsActivity) return (EquipmentSettingsActivity)activity;
            return null;
        }
        static EquipmentScreen open(ActivityScenario<MainActivity> home) throws Exception {
            home.onActivity(a -> a.startActivity(new Intent(a,EquipmentSettingsActivity.class)));
            until(() -> {
                AtomicReference<Boolean> visible=new AtomicReference<>(false);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> visible.set(current()!=null));
                return visible.get();
            });
            return new EquipmentScreen();
        }
        void onActivity(Consumer<EquipmentSettingsActivity> action) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                EquipmentSettingsActivity activity=current(); assertNotNull("Configuration screen resumed",activity); action.accept(activity);
            });
        }
        void recreate() throws Exception {
            AtomicReference<EquipmentSettingsActivity> before=new AtomicReference<>();
            onActivity(a -> { before.set(a); a.recreate(); });
            until(() -> {
                AtomicReference<Boolean> changed=new AtomicReference<>(false);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> changed.set(current()!=null && current()!=before.get()));
                return changed.get();
            });
        }
        @Override public void close() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { EquipmentSettingsActivity activity=current(); if(activity!=null) activity.finish(); });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0,8,InetAddress.getByName("0.0.0.0"));
        final Thread worker;
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        volatile boolean running=true,printerAvailable=true;
        volatile Throwable error;
        Fixture() throws Exception { server.setSoTimeout(500); worker=new Thread(this::serve,"local-equipment-fixture"); worker.start(); }
        int port() { return server.getLocalPort(); }
        int count() { return requests.size(); }
        void check() { if(error!=null) throw new AssertionError("Fixture accepts only declared read operations",error); }
        void serve() {
            while(running) {
                try (Socket socket=server.accept()) {
                    socket.setSoTimeout(4000); InputStream in=socket.getInputStream(); ByteArrayOutputStream header=new ByteArrayOutputStream();
                    int b; while((b=in.read())!=-1) { header.write(b); if(header.size()>16384) throw new IOException("Header too large"); byte[] a=header.toByteArray(); int n=a.length; if(n>=4 && a[n-4]==13 && a[n-3]==10 && a[n-2]==13 && a[n-1]==10) break; }
                    String headers=header.toString(StandardCharsets.US_ASCII.name()); String[] first=headers.split("\\r\\n",2)[0].split(" ");
                    int length=0; for(String line:headers.split("\\r\\n")) if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(15).trim());
                    if(length<0 || length>65536) throw new IOException("Body too large"); byte[] body=new byte[length]; new DataInputStream(in).readFully(body);
                    byte[] answer; int status=200;
                    if(first[0].equals("POST") && first[1].equals("/ipp/print")) {
                        if(body.length<8 || body[2]!=0 || body[3]!=11) throw new IOException("Forbidden IPP operation"); requests.add("Get-Printer-Attributes");
                        try(InputStream asset=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("mg3600-attributes.ipp")) {
                            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int n; while((n=asset.read(buffer))!=-1) bytes.write(buffer,0,n); answer=bytes.toByteArray();
                        }
                        System.arraycopy(body,4,answer,4,4);
                        if(!printerAvailable) { status=503; answer=new byte[0]; }
                    } else if(first[0].equals("GET") && first[1].equals("/root.xml")) {
                        requests.add("Description"); answer=("<root><device><friendlyName>Fixture gateway</friendlyName><serialNumber>fixture-gateway</serialNumber><serviceList><service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/wan</controlURL></service><service><serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType><controlURL>/common</controlURL></service></serviceList></device></root>").getBytes(StandardCharsets.UTF_8);
                    } else if(first[0].equals("POST") && (first[1].equals("/wan") || first[1].equals("/common"))) {
                        String action=null; for(String candidate:new String[]{"GetStatusInfo","GetExternalIPAddress","GetCommonLinkProperties"}) if(headers.contains("#"+candidate+"\"")) action=candidate;
                        if(action==null) throw new IOException("Forbidden SOAP action"); requests.add(action);
                        String fields=action.equals("GetStatusInfo")?"<NewConnectionStatus>Connected</NewConnectionStatus><NewUptime>1234</NewUptime>":action.equals("GetExternalIPAddress")?"<NewExternalIPAddress>198.51.100.20</NewExternalIPAddress>":"<NewPhysicalLinkStatus>Up</NewPhysicalLinkStatus><NewLayer1DownstreamMaxBitRate>100000000</NewLayer1DownstreamMaxBitRate><NewLayer1UpstreamMaxBitRate>10000000</NewLayer1UpstreamMaxBitRate>";
                        answer=("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><response>"+fields+"</response></s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
                    } else throw new IOException("Unexpected fixture request");
                    OutputStream out=socket.getOutputStream(); out.write(("HTTP/1.1 "+status+" Fixture\r\nContent-Length: "+answer.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.write(answer); out.flush();
                } catch(SocketTimeoutException timeout) { /* Wait for the next native poll. */ }
                catch(Exception failure) { if(running) { error=failure; running=false; } }
            }
        }
        @Override public void close() throws Exception { running=false; server.close(); worker.join(2000); }
    }
}
