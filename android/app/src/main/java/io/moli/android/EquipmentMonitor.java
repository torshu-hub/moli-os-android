package io.moli.android;

import android.content.Context;
import android.os.SystemClock;
import org.json.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Reads only the authenticated local engine snapshot, never device endpoints or cloud. */
final class EquipmentMonitor {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static boolean busy;
    private static long last=-15000;
    static synchronized void poll(Context context,Consumer<String> display,boolean force) {
        if(EngineHost.state()!=EngineHost.State.RUNNING || busy || (!force && SystemClock.elapsedRealtime()-last<15000))return;
        busy=true;last=SystemClock.elapsedRealtime();Context app=context.getApplicationContext();long lease=EngineHost.currentLease();String origin=EngineHost.origin(),token=EngineHost.token();
        IO.execute(()->{
            try {
                HttpURLConnection c=(HttpURLConnection)new URL(origin+"/api/devices").openConnection();
                c.setConnectTimeout(2000);c.setReadTimeout(2000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);c.setRequestProperty("Cookie","moli_android="+token);
                JSONObject snapshot;try{if(c.getResponseCode()!=200)throw new java.io.IOException();snapshot=new JSONObject(ProfileStore.bounded(c.getInputStream(),262144));}finally{c.disconnect();}
                if(lease!=EngineHost.currentLease()||EngineHost.state()!=EngineHost.State.RUNNING)return;
                publish(app,EquipmentStore.read(app),snapshot,display);
            }catch(Exception ignored){TrialLog.record(TrialJournal.Event.ENGINE_READ_FAILED,0,0);if(display!=null)display.accept(app.getString(R.string.equipment_read_unavailable));}
            finally{synchronized(EquipmentMonitor.class){busy=false;}}
        });
    }
    static void publish(Context app,JSONObject config,JSONObject snapshot,Consumer<String> display)throws Exception {
        JSONArray rows=new JSONArray();if(config.has("printer"))rows.put(new JSONObject().put("instance","android-printer").put("kind",1));
        if(config.has("gateway"))rows.put(new JSONObject().put("instance","android-gateway").put("kind",2));
        JSONArray plugs=config.optJSONArray("meross");if(plugs!=null)for(int i=0;i<plugs.length();i++){JSONObject p=plugs.getJSONObject(i);if(p.optBoolean("enabled"))rows.put(new JSONObject().put("instance","android-meross-"+p.getString("id")).put("kind",3).put("name",p.optString("name")).put("identity",p.optString("uuid","android-meross-"+p.getString("id"))));}
        StringBuilder text=new StringBuilder();JSONArray devices=snapshot.getJSONArray("devices"),drivers=snapshot.getJSONArray("drivers");long now=System.currentTimeMillis();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String instance=row.getString("instance");int kind=row.getInt("kind");boolean running=false;
            for(int d=0;d<drivers.length();d++){JSONObject driver=drivers.getJSONObject(d);if(instance.equals(driver.optString("instance")))running="running".equals(driver.optJSONObject("status")==null?"":driver.optJSONObject("status").optString("state"));}
            int count=0;boolean identity=false;long receipt=0;
            for(int d=0;d<devices.length();d++){JSONObject device=devices.getJSONObject(d);if(!instance.equals(device.optString("instance")))continue;JSONObject state=device.optJSONObject("state");if(state==null)continue;
                JSONObject uptime=state.optJSONObject("uptime");
                String gate=kind==1?"responding":kind==2?(uptime!=null && !uptime.isNull("value")?"uptime":"connected"):"reported_model";JSONObject sample=state.optJSONObject(gate);
                if(sample!=null && !sample.isNull("value"))receipt=Math.max(receipt,sample.optLong("ts"));
                identity=recent(sample,running,device.optBoolean("online"),now,kind==2?35000:65000);
                if(kind==1 && (sample==null || !sample.optBoolean("value")))identity=false;
                String[] points=kind==1?new String[]{"state","responding"}:kind==2?new String[]{"connected","line_down","line_up"}:new String[]{"on","power","current","voltage","energy_recent","signal"};
                for(String key:points)if(recent(state.optJSONObject(key),running,device.optBoolean("online"),now,key.equals("energy_recent")?660000:key.equals("signal")?135000:kind==2?35000:65000))count++;
            }
            boolean ok=identity && count>0;
            TrialLog.action(app,TrialJournal.Event.EQUIPMENT_READ,TrialJournal.Source.EQUIPEMENTS,ok?TrialJournal.Outcome.FRAIS:TrialJournal.Outcome.ABSENT,TrialJournal.Error.AUCUNE,kind,count,row.optString("identity",instance),null,receipt);
            text.append(kind==1?"IPP":kind==2?"IGD":"MSS310 "+row.optString("name")).append(" — ").append(app.getString(ok?R.string.equipment_read_ok:R.string.equipment_read_unavailable)).append(" (").append(count).append(")");
            if(receipt>0)text.append("\n").append(app.getString(R.string.equipment_receipt,java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(receipt)),Math.max(0,(now-receipt)/1000)));
            text.append('\n');
        }
        if(display!=null)display.accept(text.length()==0?app.getString(R.string.equipment_no_reads):text.toString());
    }
    private static boolean recent(JSONObject sample,boolean running,boolean online,long now,long age){return sample!=null && DataFreshness.recent(running,online,!sample.isNull("value"),sample.optLong("ts"),now,age);}
}
