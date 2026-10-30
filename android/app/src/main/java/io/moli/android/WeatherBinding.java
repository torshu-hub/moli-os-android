package io.moli.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** One-time presentation wiring to a device actually published by the official engine.
 * No weather fetcher or driver configuration lives here. Existing choices are preserved. */
final class WeatherBinding {
    private static long activeLease=-1;
    private static boolean active;
    static synchronized void start(Context context,long lease) {
        if(EngineHost.state()!=EngineHost.State.RUNNING || (active && activeLease==lease)) return;
        active=true; activeLease=lease;
        attempt(context.getApplicationContext(),lease,0);
    }
    private static synchronized void finished(long lease) { if(activeLease==lease) active=false; }
    private static boolean current(long lease) {
        return EngineHost.state()==EngineHost.State.RUNNING && EngineHost.currentLease()==lease;
    }
    private static void attempt(Context app,long lease,int tries) {
        EngineHost.inspect(()->{
            if(!current(lease)) {finished(lease);return null;}
            boolean retry=false;
            try {
                ConfigStore store=new ConfigStore(app);
                String before=store.disk.read("home.json",ConfigurationTransaction.FILE_LIMIT);
                if(hasChoice(before)) {finished(lease);return null;}
                JSONObject form=ConfigStore.nativeCall(new JSONObject().put("action","form").put("text",store.read()));
                // Never choose arbitrarily among multiple configured weather instances.
                if(form.getJSONArray("instances").length()!=1) {finished(lease);return null;}
                JSONObject devices=snapshot();
                String next=linkedHome(before,form,devices);
                if(next!=null && current(lease)) {
                    // Reuse J1 maintenance: stop, atomically write the presentation file,
                    // resume. The existing host lease refresh reloads the original UI.
                    EngineHost.maintain(app,()->{
                        try {
                            ConfigStore latest=new ConfigStore(app);
                            String actual=latest.disk.read("home.json",ConfigurationTransaction.FILE_LIMIT);
                            if(!Objects.equals(before,actual)) throw new IOException("configuration_conflict");
                            if(next.getBytes(StandardCharsets.UTF_8).length>ConfigurationTransaction.FILE_LIMIT) throw new IOException("configuration_storage");
                            latest.disk.write("home.json",next);
                            GeneralJournal.record(app,GeneralJournal.Event.CONFIGURATION_SAVED);
                            return null;
                        } catch(Exception failure) {
                            GeneralJournal.record(app,GeneralJournal.Event.CONFIGURATION_REJECTED);throw failure;
                        }
                    });
                } else retry=true;
            } catch(JSONException failure) {
                // Malformed home.json is retained verbatim, never replaced with defaults.
                GeneralJournal.record(app,GeneralJournal.Event.CONFIGURATION_REJECTED);
            } catch(Exception failure) {retry=current(lease);}
            if(retry && tries<44 && current(lease)) {
                // Bounded local startup reconciliation only; the Rust driver owns polling.
                new Handler(Looper.getMainLooper()).postDelayed(()->attempt(app,lease,tries+1),1000);
            } else finished(lease);
            return null;
        });
    }
    static boolean hasChoice(String text) throws JSONException {
        JSONObject root=text==null ? new JSONObject() : new JSONObject(text);
        if(root.has("outdoor") && !(root.get("outdoor") instanceof JSONObject)) throw new JSONException("configuration");
        JSONObject out=root.optJSONObject("outdoor");
        return out!=null && out.has("weather") && !out.isNull("weather") && !"".equals(out.opt("weather"));
    }
    /** Return null until a unique configured device has actual numeric weather data. */
    static String linkedHome(String before,JSONObject form,JSONObject snapshot) throws JSONException {
        if(hasChoice(before)) return null;
        JSONArray instances=form.getJSONArray("instances");
        if(instances.length()!=1) return null;
        String instance=instances.getJSONObject(0).getString("id"),id=null;
        JSONArray devices=snapshot.getJSONArray("devices");
        for(int i=0;i<devices.length();i++) {
            JSONObject device=devices.getJSONObject(i);
            if(!instance.equals(device.optString("instance")) || !"Open-Meteo".equals(device.optString("manufacturer"))) continue;
            JSONObject state=device.optJSONObject("state"),temperature=state==null ? null : state.optJSONObject("temperature");
            if(!device.optBoolean("online") || temperature==null || !(temperature.opt("value") instanceof Number)) continue;
            if(id!=null) return null;
            id=device.getString("id");
        }
        if(id==null) return null;
        JSONObject root=before==null ? new JSONObject() : new JSONObject(before);
        JSONObject outdoor=root.optJSONObject("outdoor");
        if(outdoor==null) {outdoor=new JSONObject();root.put("outdoor",outdoor);}
        outdoor.put("weather",id);
        return root.toString(2)+"\n";
    }
    private static JSONObject snapshot() throws Exception {
        String origin=EngineHost.origin(),token=EngineHost.token();
        if(origin==null || token==null) throw new IOException("busy");
        HttpURLConnection connection=(HttpURLConnection)new URL(origin+"/api/devices").openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(1500); connection.setReadTimeout(1500);
        connection.setRequestProperty("Cookie","moli_android="+token);
        try {
            if(connection.getResponseCode()!=200) throw new IOException("configuration");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int count;
                while((count=in.read(buffer))!=-1) {
                    if(out.size()+count>1_048_576) throw new IOException("configuration");
                    out.write(buffer,0,count);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {connection.disconnect();}
    }
    private WeatherBinding() {}
}
