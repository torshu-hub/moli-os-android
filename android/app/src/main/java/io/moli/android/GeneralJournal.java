package io.moli.android;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Code39's fixed-enum redaction policy, detached from all Trial screens. */
final class GeneralJournal {
    enum Event { ENGINE_STARTED, ENGINE_STOPPED, ENGINE_FAILED, CONFIGURATION_SAVED, CONFIGURATION_REJECTED }
    private static AtomicFile file(Context context){return new AtomicFile(new File(context.getFilesDir(),"android-journal.json"));}
    static synchronized void record(Context context,Event event){
        try{JSONArray events=read(context);while(events.length()>=256)events.remove(0);events.put(new JSONObject().put("ts",System.currentTimeMillis()).put("event",event.name()));AtomicFile target=file(context);FileOutputStream out=target.startWrite();try{out.write(events.toString().getBytes(StandardCharsets.UTF_8));target.finishWrite(out);}catch(Exception e){target.failWrite(out);}}
        catch(Exception ignored){ /* An export reports unavailable storage, never arbitrary exception text. */ }
    }
    private static JSONArray read(Context context) throws Exception {AtomicFile target=file(context);if(!target.getBaseFile().exists()&&!new File(target.getBaseFile()+".bak").exists())return new JSONArray();return new JSONArray(new String(target.readFully(),StandardCharsets.UTF_8));}
    static String redactedEngineEntry(String line) {
        try{JSONObject e=new JSONObject(line);String kind=e.getJSONObject("action").getString("kind"),result=e.getJSONObject("outcome").getString("result"),origin=e.getString("origin");
            if(!Arrays.asList("command","label","approval","automation","address").contains(kind)||!Arrays.asList("ok","err","pending").contains(result)||!Arrays.asList("ui","household","api","mcp","cli","system","assistant","automation").contains(origin))return "";
            return Instant.ofEpochMilli(e.getLong("ts"))+" | "+origin+" | "+kind+" | "+result+" | ms="+Math.max(0,e.getLong("duration_ms"))+"\n";
        }catch(Exception ignored){return "";}
    }
    static String redactedHealth(String text) throws Exception {
        JSONObject health=new JSONObject(text);StringBuilder out=new StringBuilder();
        out.append("journal_failures=").append(Math.max(0,health.optLong("journal_failures"))).append('\n');
        JSONArray drivers=health.getJSONArray("drivers");
        for(int i=0;i<drivers.length();i++){
            JSONObject status=drivers.getJSONObject(i).getJSONObject("status");String state=status.getString("state");
            if(Arrays.asList("starting","running","backoff","waiting","stopped").contains(state)){
                out.append("driver#").append(i+1).append(" | ").append(state);
                if(state.equals("backoff"))out.append(" | retry_ms=").append(Math.max(0,status.optLong("retry_in_ms")));
                out.append('\n');
            }
        }
        return out.toString();
    }
    private static String health() {
        if(EngineHost.state()!=EngineHost.State.RUNNING)return "";
        java.net.HttpURLConnection connection=null;
        try{
            connection=(java.net.HttpURLConnection)new java.net.URL(EngineHost.origin()+"/api/health").openConnection();
            connection.setConnectTimeout(2000);connection.setReadTimeout(2000);connection.setRequestProperty("Cookie","moli_android="+EngineHost.token());
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>65536)throw new IOException();bytes.write(buffer,0,n);}
                return redactedHealth(bytes.toString("UTF-8"));
            }
        }catch(Exception ignored){return "health=unavailable\n";}finally{if(connection!=null)connection.disconnect();}
    }
    static synchronized String export(Context context) throws Exception {
        StringBuilder out=new StringBuilder("Moli OS Android Beta 2 — journal TXT\n");
        out.append("Version: ").append(BuildConfig.VERSION_NAME).append("\n");
        JSONArray host=read(context);
        for(int i=0;i<host.length();i++){JSONObject entry=host.getJSONObject(i);try{Event event=Event.valueOf(entry.getString("event"));out.append(Instant.ofEpochMilli(entry.getLong("ts"))).append(" | ").append(event.name()).append('\n');}catch(IllegalArgumentException ignored){}}
        if(ProtectedKey.exists(context)){
            File dir=ProtectedKey.dataDirectory(context,ProtectedKey.open(context));
            // Read only a bounded tail, and export whitelisted envelope fields.
            File journal=new File(dir,"journal.jsonl");
            if(journal.exists()){try(RandomAccessFile in=new RandomAccessFile(journal,"r")){
                long start=Math.max(0,in.length()-1_048_576);in.seek(start);if(start>0)in.readLine();String line;int count=0;
                while((line=in.readLine())!=null && count++<10000)out.append(redactedEngineEntry(line));
            }}
        }
        out.append(health());
        return out.toString();
    }
    private GeneralJournal(){}
}
