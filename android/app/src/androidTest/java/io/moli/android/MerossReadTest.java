package io.moli.android;

import android.content.*;
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
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Synthetic LAN responders inside a disposable emulator; never a real plug. */
@RunWith(AndroidJUnit4.class)
public final class MerossReadTest {
    private interface Check { boolean ready() throws Exception; }
    private static void until(Check check) throws Exception {
        long end=SystemClock.elapsedRealtime()+100000;
        while(true) { if(check.ready())return; if(SystemClock.elapsedRealtime()>=end)fail("MSS310 observation timed out"); Thread.sleep(100); }
    }
    static JSONObject get(String path) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(EngineHost.origin()+path).openConnection();
        c.setConnectTimeout(2000);c.setReadTimeout(3000);c.setRequestProperty("Cookie","moli_android="+EngineHost.token());
        try { assertEquals(200,c.getResponseCode());return new JSONObject(ProfileStore.bounded(c.getInputStream(),524288)); } finally { c.disconnect(); }
    }
    private static String js(ActivityScenario<MainActivity> ui,String code) throws Exception {
        AtomicReference<String> result=new AtomicReference<>();ui.onActivity(a->a.web.evaluateJavascript(code,result::set));until(()->result.get()!=null);return result.get();
    }
    static String address() throws Exception {
        for(NetworkInterface net:Collections.list(NetworkInterface.getNetworkInterfaces()))
            for(InetAddress ip:Collections.list(net.getInetAddresses()))if(ip instanceof Inet4Address && ip.isSiteLocalAddress() && !ip.isLoopbackAddress())return ip.getHostAddress();
        throw new IOException("Private emulator interface required");
    }
    static JSONObject device(int fixture) throws Exception {
        JSONArray ds=get("/api/devices").getJSONArray("devices");String nativeId=String.format(Locale.ROOT,"%032x",fixture);
        for(int i=0;i<ds.length();i++) { JSONObject d=ds.getJSONObject(i); if(d.getString("id").endsWith(":"+nativeId) && d.optBoolean("online"))return d; }
        return null;
    }
    private static void saved(ActivityScenario<EquipmentSettingsActivity> ui) throws Exception {
        until(()->{AtomicReference<Boolean> ok=new AtomicReference<>(false);ui.onActivity(a->ok.set(a.start.isEnabled() && a.message.getText().toString().equals(a.getString(R.string.equipment_saved))));return ok.get();});
    }
    @Test public void multipleSignedReadersProtectKeysPersistAndIsolateFailures() throws Exception {
        assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        FreshEquipmentFixture.create(app);assertEquals(EngineHost.State.STOPPED,EngineHost.state());
        if(!ProtectedKey.exists(app))ProtectedKey.create(app,"4826");
        app.getSharedPreferences("host",Context.MODE_PRIVATE).edit().putBoolean("wanted",false).commit();ProfileStore.remove(app,"weather");
        String ip=address();
        try(Fixture one=new Fixture(1);Fixture two=new Fixture(2);Fixture wrong=new Fixture(3);Fixture four=new Fixture(4)) {
            Fixture[] fixtures={one,two,wrong,four};
            try {
                try(ActivityScenario<EquipmentSettingsActivity> ui=ActivityScenario.launch(EquipmentSettingsActivity.class)) {
                    until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(a.save.isEnabled() && a.hasWindowFocus()));return ready.get();});
                    ui.onActivity(a->{
                        for(int i=0;i<4;i++) {a.meross.add.performClick();MerossForm.Row r=a.meross.entries.get(i);r.name.setText("Fixture "+(i+1));r.host.setText(ip);r.port.setText(String.valueOf(fixtures[i].port()));r.read.setChecked(true);r.key.setText((i==2?"b":"a").repeat(32));assertFalse(r.key.isSaveEnabled());assertTrue(r.key.isLongClickable());
                            if(i==0){ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);r.key.setText("");clipboard.setPrimaryClip(ClipData.newPlainText("synthetic","a".repeat(32)));assertTrue(r.key.onTextContextMenuItem(android.R.id.paste));assertEquals("a".repeat(32),r.key.getText().toString());clipboard.setPrimaryClip(ClipData.newPlainText("",""));}}
                        assertFalse(a.start.isEnabled());a.save.performClick();assertFalse(a.meross.entries.get(0).key.isEnabled());
                    }); saved(ui);
                    for(Fixture f:fixtures)assertEquals(0,f.reads);
                    String config=ProfileStore.read(new File(ProfileStore.data(app),"android-equipment.json"),16384);
                    assertFalse(config.contains("a".repeat(32)));assertFalse(config.contains("b".repeat(32)));assertFalse(config.contains("\"key\""));
                    byte[] vault=java.nio.file.Files.readAllBytes(new File(ProfileStore.data(app),"secrets.enc").toPath());
                    assertFalse(new String(vault,StandardCharsets.ISO_8859_1).contains("a".repeat(32)));
                    ui.recreate();
                    until(()->{AtomicReference<Boolean> ready=new AtomicReference<>(false);ui.onActivity(a->ready.set(a.save.isEnabled() && a.meross.entries.size()==4));return ready.get();});
                    ui.onActivity(a->{for(MerossForm.Row r:a.meross.entries)assertEquals("",r.key.getText().toString());a.save.performClick();});saved(ui); // Blank preserves encrypted keys.
                    ui.onActivity(a->a.start.performClick());
                }
                until(()->EngineHost.state()==EngineHost.State.RUNNING);
                try {
                    until(()->{for(Fixture f:fixtures)f.check();return device(1)!=null && device(2)!=null && device(4)!=null && wrong.errors>0;});
                } catch(AssertionError failed) {
                    // Disposable fixture-only diagnostics, never credentials or headers.
                    throw new AssertionError("Synthetic read counters="+one.reads+","+two.reads+","+wrong.reads+","+four.reads+
                            " errors="+wrong.errors+" drivers="+get("/api/devices").getJSONArray("drivers"),failed);
                }
                JSONObject d=device(1);String stable=d.getString("id");JSONObject values=d.getJSONObject("state");
                assertEquals("Fixture 1",d.getString("native_name"));
                for(int i=0;i<d.getJSONArray("points").length();i++)assertFalse(d.getJSONArray("points").getJSONObject(i).getJSONObject("access").optBoolean("write"));
                assertEquals(123.456,values.getJSONObject("power").getDouble("value"),0.0001);
                assertEquals(0.789,values.getJSONObject("current").getDouble("value"),0.0001);
                assertEquals(231.4,values.getJSONObject("voltage").getDouble("value"),0.0001);
                assertEquals(987.0,values.getJSONObject("energy_recent").getDouble("value"),0.0001);
                assertEquals("2026-10-21",values.getJSONObject("energy_date").getString("value"));
                assertTrue(values.getJSONObject("on").getBoolean("value"));assertEquals(76,values.getJSONObject("signal").getInt("value"));
                assertTrue(device(2).getJSONObject("state").getJSONObject("voltage").isNull("value"));
                assertEquals(246.912,device(2).getJSONObject("state").getJSONObject("power").getDouble("value"),0.0001);
                assertFalse(device(2).getJSONObject("state").getJSONObject("on").getBoolean("value"));assertNull(device(3));
                try(AutoCloseable display=TouchRegression.tablet();ActivityScenario<MainActivity> home=ActivityScenario.launch(MainActivity.class)) {
                home.onActivity(MainActivity::showDevices);
                    until(()->"true".equals(js(home,"document.querySelectorAll('[data-equipment=meross]').length===4 && document.querySelectorAll('[data-equipment=meross][data-equipment-status=fresh]').length===3")));
                    assertEquals("true",js(home,"(()=>{const cards=[...document.querySelectorAll('[data-equipment=meross][data-equipment-status=fresh]')];return cards.every(c=>c.querySelectorAll('[data-equipment-received]').length===1 && !c.querySelector('details').open && c.querySelector('details dl') && !c.querySelector(':scope > dl small'))})()"));
                    DevicesNavigationFixture.running(home);
                    TouchRegression.verifyDevices(home);home.onActivity(MainActivity::showDevices);
                    long lease=EngineHost.currentLease();four.available=false;
                    until(()->"true".equals(js(home,"document.querySelectorAll('[data-equipment=meross][data-equipment-status=fresh]').length===2")));
                    assertEquals(lease,EngineHost.currentLease());assertNotNull(device(1));
                }
                EquipmentMonitor.publish(app,EquipmentStore.read(app),get("/api/devices"),null);
                app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP));until(()->EngineHost.state()==EngineHost.State.STOPPED);
                String preserved=EquipmentStore.read(app).toString(),master=ProtectedKey.open(app).getString("master");
                try(ActivityScenario<MainActivity> settings=ActivityScenario.launch(MainActivity.class)) {
                    DevicesNavigationFixture.stopped(settings,app);TouchRegression.maintenance(settings);}
                assertEquals(preserved,EquipmentStore.read(app).toString());assertEquals(master,ProtectedKey.open(app).getString("master"));
                four.available=true;
                app.startForegroundService(new Intent(app,MoliService.class));until(()->EngineHost.state()==EngineHost.State.RUNNING);
                until(()->device(1)!=null && device(4)!=null);assertEquals(stable,device(1).getString("id"));
                assertEquals(4,EquipmentStore.read(app).getJSONArray("meross").length());
                for(Fixture f:fixtures)f.check();
                String journal=TrialLog.export(app);assertFalse(journal.contains("a".repeat(32)));assertFalse(journal.contains("b".repeat(32)));
            } finally {
                app.startService(new Intent(app,MoliService.class).setAction(MoliService.STOP));until(()->EngineHost.state()==EngineHost.State.STOPPED);
                EngineHost.maintain(()->{EquipmentStore.save(app,new JSONObject());return null;}).get();
            }
        }
        MerossManualFixture.verify();
        ObservationFixture.verify();
    }
    static final class Fixture implements AutoCloseable {
        final int id;final ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("0.0.0.0"));final Thread worker;
        final boolean manual;
        volatile boolean running=true,available=true,on,badAck;volatile int reads,errors,sets;volatile Throwable failure;
        Fixture(int id)throws Exception {this(id,false);}
        Fixture(int id,boolean manual)throws Exception {this.id=id;this.manual=manual;on=id!=2;server.setSoTimeout(500);worker=new Thread(this::serve,"mss310-fixture");worker.start();}
        int port(){return server.getLocalPort();}void check(){if(failure!=null)throw new AssertionError("Only explicitly enabled signed fixture requests allowed",failure);}
        void serve(){while(running)try(Socket socket=server.accept()){
            if(!available)continue; // Deliberate connection loss, not a synthetic successful response.
            socket.setSoTimeout(4000);InputStream in=socket.getInputStream();ByteArrayOutputStream h=new ByteArrayOutputStream();int b;
            while((b=in.read())!=-1){h.write(b);byte[] a=h.toByteArray();int n=a.length;if(n>16384)throw new IOException("Header bound");if(n>=4 && a[n-4]==13 && a[n-3]==10 && a[n-2]==13 && a[n-1]==10)break;}
            String header=h.toString(StandardCharsets.US_ASCII.name());if(!header.startsWith("POST /config HTTP/1.1"))throw new IOException("Unexpected endpoint");
            int size=0;for(String line:header.split("\\r\\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(line.substring(15).trim());if(size<=0 || size>8192)throw new IOException("Body bound");
            byte[] raw=new byte[size];new DataInputStream(in).readFully(raw);JSONObject request=new JSONObject(new String(raw,StandardCharsets.UTF_8)),rh=request.getJSONObject("header");
            String method=rh.getString("method");
            if(!"GET".equals(method)&&!(manual&&"SET".equals(method)))throw new IOException("Write forbidden");
            String ns=rh.getString("namespace"),file;
            switch(ns){case "Appliance.System.All":file="all";break;case "Appliance.Control.Electricity":file="electricity";break;case "Appliance.Control.ConsumptionX":file="consumption";break;case "Appliance.System.Runtime":file="runtime";break;case "Appliance.System.Ability":if(!manual)throw new IOException("Ability not enabled");file="ability";break;case "Appliance.Control.ToggleX":if(!manual||!method.equals("SET"))throw new IOException("ToggleX not enabled");file="set";break;default:throw new IOException("Namespace forbidden");}
            String signed=rh.getString("messageId")+"a".repeat(32)+rh.getLong("timestamp");StringBuilder expected=new StringBuilder();for(byte v:MessageDigest.getInstance("MD5").digest(signed.getBytes(StandardCharsets.UTF_8)))expected.append(String.format(Locale.ROOT,"%02x",v&255));
            JSONObject response;
            if(!expected.toString().equals(rh.getString("sign"))){errors++;response=new JSONObject().put("header",new JSONObject().put("method","ERROR").put("namespace",ns)).put("payload",new JSONObject().put("error",new JSONObject().put("code",5001)));}
            else {
                if(method.equals("SET")){
                    if(!file.equals("set"))throw new IOException("Wrong SET namespace");
                    JSONObject payload=request.getJSONObject("payload"),toggle=payload.getJSONObject("togglex");
                    if(payload.length()!=1||toggle.length()!=2||toggle.getInt("channel")!=0)throw new IOException("Unexpected write");
                    int value=toggle.getInt("onoff");if(value<0||value>1)throw new IOException("Wrong value");on=value==1;sets++;response=new JSONObject().put("payload",new JSONObject());
                }else{
                    reads++;
                    if(file.equals("ability"))response=new JSONObject().put("payload",new JSONObject().put("ability",new JSONObject().put("Appliance.Control.ToggleX",new JSONObject())));
                    else{String text=ProfileStore.bounded(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("mss310/"+file+".json"),8192);response=new JSONObject(text);}
                }
                if(file.equals("all"))response.getJSONObject("payload").getJSONObject("all").getJSONObject("system").getJSONObject("hardware").put("uuid",String.format(Locale.ROOT,"%032x",id));
                if(id==2 && file.equals("electricity")){response.getJSONObject("payload").getJSONObject("electricity").remove("voltage");response.getJSONObject("payload").getJSONObject("electricity").put("power",246912);}
                if(file.equals("all"))response.getJSONObject("payload").getJSONObject("all").getJSONObject("digest").getJSONArray("togglex").getJSONObject(0).put("onoff",on?1:0);
                long timestamp=System.currentTimeMillis()/1000;String message=rh.getString("messageId");String signature=message+"a".repeat(32)+timestamp;StringBuilder sign=new StringBuilder();for(byte v:MessageDigest.getInstance("MD5").digest(signature.getBytes(StandardCharsets.UTF_8)))sign.append(String.format(Locale.ROOT,"%02x",v&255));
                response.put("header",new JSONObject().put("namespace",ns).put("method",method+"ACK").put("messageId",message).put("timestamp",timestamp).put("payloadVersion",1).put("from","/appliance/"+String.format(Locale.ROOT,"%032x",id)+"/publish").put("sign",method.equals("SET")&&badAck?"invalid":sign.toString()));
            }
            byte[] body=response.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+(available?"200 OK":"503 Unavailable")+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
        }catch(SocketTimeoutException idle){}catch(Exception error){if(running){failure=error;running=false;}}}
        @Override public void close()throws Exception{running=false;server.close();worker.join(2000);}
    }
}
