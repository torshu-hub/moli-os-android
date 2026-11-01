package io.moli.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Explicit REST configuration only: no polling, file writes or actuator calls. */
public final class AutomationActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private JSONArray rules=new JSONArray(),devices=new JSONArray();
    private boolean busy;
    private String origin,cookies;
    private interface Job {void run()throws Exception;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,16,24,16);ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);}
    @Override protected void onResume(){super.onResume();load();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private void text(String value){TextView v=new TextView(this);v.setText(value);v.setTextSize(16);v.setPadding(0,12,0,12);root.addView(v);}
    private void button(int label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setEnabled(!busy);b.setOnClickListener(v->action.run());root.addView(b);}
    private void submit(Job job){
        if(busy)return;
        origin=EngineHost.origin();String token=EngineHost.token();
        if(origin==null||token==null){root.removeAllViews();text(getString(R.string.auto_engine));button(R.string.auto_reload,this::load);return;}
        String savedCookies=CookieManager.getInstance().getCookie(origin);cookies="moli_android="+token;
        if(savedCookies!=null)for(String cookie:savedCookies.split(";"))if(cookie.trim().startsWith("moli_session="))cookies+="; "+cookie.trim();
        busy=true;root.removeAllViews();text(getString(R.string.working));
        worker.submit(()->{String error=null;try{job.run();}catch(Exception failure){if(failure instanceof Conflict)try{read();}catch(Exception ignored){}error=failure instanceof Conflict?getString(R.string.auto_conflict):getString(R.string.auto_error);}final String message=error;runOnUiThread(()->{if(isDestroyed())return;busy=false;show();if(message!=null)text(message);});});
    }
    static class RequestFailure extends IOException {
        final int status;
        RequestFailure(int status){this.status=status;}
    }
    private static final class Conflict extends RequestFailure {Conflict(){super(409);}}
    JSONObject request(String method,String path,JSONObject body,String fingerprint)throws Exception {
        if(!Objects.equals(origin,EngineHost.origin()))throw new IOException();
        HttpURLConnection c=(HttpURLConnection)new URL(origin+path).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestMethod(method);c.setRequestProperty("Cookie",cookies);
        if(fingerprint!=null)c.setRequestProperty("If-Match",fingerprint);
        try{
            if(body!=null){c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();if(status==409)throw new Conflict();if(status!=200)throw new RequestFailure(status);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>2_097_152)throw new IOException();out.write(buffer,0,n);}return new JSONObject(out.toString("UTF-8"));
            }
        }finally{c.disconnect();}
    }
    private void read()throws Exception{devices=request("GET","/api/devices",null,null).getJSONArray("devices");rules=request("GET","/api/automations",null,null).getJSONArray("automations");}
    void load(){submit(this::read);}
    private void editor(String id){startActivity(new Intent(this,MainActivity.class).putExtra("automation",id));}
    private void show(){
        root.removeAllViews();text(getString(R.string.auto_title));text(getString(R.string.auto_help));button(R.string.auto_reload,this::load);
        for(int i=0;i<rules.length();i++){
            JSONObject view=rules.optJSONObject(i);if(view==null)continue;JSONObject a=view.optJSONObject("automation");if(a==null)continue;
            try{String[] pair=DemandAutomation.wiring(a.getJSONObject("graph"));if(pair!=null){for(int j=0;j<devices.length();j++){JSONObject d=devices.getJSONObject(j);String id=d.getString("id");if(pair[0].equals(id+"/declared_demand")||pair[1].equals(id+"/on")){JSONObject state=d.optJSONObject("state"),sample=state==null?null:state.optJSONObject(pair[0].equals(id+"/declared_demand")?"declared_demand":"on");Object value=sample==null?null:sample.opt("value");int label=Boolean.TRUE.equals(value)||"on".equals(value)?R.string.auto_on:Boolean.FALSE.equals(value)||"off".equals(value)?R.string.auto_off:R.string.auto_unknown;text(name(d)+" — "+getString(label));}}}}catch(JSONException ignored){}
            text(a.optString("name")+" — "+getString(view.optBoolean("live")?R.string.auto_active:R.string.auto_inactive));
            button(R.string.auto_editor,()->editor(a.optString("id")));
            try{if(DemandAutomation.wiring(a.getJSONObject("graph"))!=null)button(R.string.auto_edit,()->form(view));}catch(JSONException ignored){}
            if(a.optBoolean("enabled"))button(R.string.auto_disable,()->submit(()->{request("POST","/api/automations/"+a.getString("id")+"/enabled",new JSONObject().put("on",false).put("fingerprint",view.getString("fingerprint")),null);read();}));
        }
        button(R.string.auto_new,()->form(null));
    }
    private String name(JSONObject d){return DemandAutomation.name(d);}
    private void form(JSONObject existing){
        root.removeAllViews();text(getString(R.string.auto_new));
        List<String> sources=new ArrayList<>(),targets=new ArrayList<>(),sourceNames=new ArrayList<>(),targetNames=new ArrayList<>();
        for(int i=0;i<devices.length();i++){
            JSONObject d=devices.optJSONObject(i);if(d==null)continue;
            if("Netatmo".equalsIgnoreCase(d.optString("manufacturer"))&&DemandAutomation.point(d,"declared_demand",false)){sources.add(d.optString("id")+"/declared_demand");sourceNames.add(name(d));}
            if("Meross".equalsIgnoreCase(d.optString("manufacturer"))&&"MSS310".equalsIgnoreCase(d.optString("model"))&&DemandAutomation.point(d,"on",true)){targets.add(d.optString("id")+"/on");targetNames.add(name(d));}
        }
        if(sources.isEmpty()||targets.isEmpty()){text(getString(R.string.auto_no_devices));button(R.string.auto_reload,this::load);return;}
        text(getString(R.string.auto_source));Spinner source=new Spinner(this);source.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,sourceNames));root.addView(source);
        text(getString(R.string.auto_target));Spinner target=new Spinner(this);target.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,targetNames));root.addView(target);
        if(existing!=null)try{String[] pair=DemandAutomation.wiring(existing.getJSONObject("automation").getJSONObject("graph"));if(pair!=null){if(!sources.contains(pair[0])||!targets.contains(pair[1])){text(getString(R.string.auto_no_devices));button(R.string.auto_back,this::show);return;}source.setSelection(sources.indexOf(pair[0]));target.setSelection(targets.indexOf(pair[1]));}}catch(JSONException ignored){}
        text(getString(R.string.auto_draft));
        button(R.string.auto_save,()->{
            String from=sources.get(source.getSelectedItemPosition()),to=targets.get(target.getSelectedItemPosition());
            submit(()->{
                if(existing!=null){
                    JSONObject a=new JSONObject(existing.getJSONObject("automation").toString());
                    a.put("graph",DemandAutomation.retarget(a.getJSONObject("graph"),from,to));
                    request("PUT","/api/automations/"+a.getString("id"),a,existing.getString("fingerprint"));read();return;
                }
                read();
                // A matching native graph is reused, never duplicated on repeated save.
                JSONObject graph=DemandAutomation.graph(from,to);
                for(int i=0;i<rules.length();i++){
                    JSONObject a=rules.getJSONObject(i).getJSONObject("automation");
                    String[] pair=DemandAutomation.wiring(a.getJSONObject("graph"));if(pair!=null&&from.equals(pair[0])&&to.equals(pair[1]))return;
                }
                request("POST","/api/automations",new JSONObject().put("name",getString(R.string.auto_rule_name)).put("mode","queued").put("enabled",false).put("graph",graph),null);read();
            });
        });
        button(R.string.auto_back,this::show);
    }
}
