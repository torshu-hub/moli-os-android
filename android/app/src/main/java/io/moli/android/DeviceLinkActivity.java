package io.moli.android;

import android.app.Activity;
import android.os.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Two devices and one persistent rule, using the original engine and human session. */
public final class DeviceLinkActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private DeviceLinkConnection client;
    private JSONObject settings;
    private Spinner source,target;
    private Switch enabled;
    private TextView status,message;
    private boolean rendering,busy,dirty;
    private volatile boolean foreground;
    private volatile long visit;
    private long lease;
    private String origin;
    private final Runnable poll=new Runnable(){public void run(){if(foreground){read();handler.postDelayed(this,15000);}}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout body=TrialUi.page(this);TrialUi.title(this,body,R.string.link_title);
        TrialUi.text(this,body,R.string.link_help);
        TrialUi.text(this,body,R.string.link_source);source=new Spinner(this);source.setId(R.id.link_source);body.addView(source);
        TrialUi.text(this,body,R.string.link_target);target=new Spinner(this);target.setId(R.id.link_target);body.addView(target);
        enabled=new Switch(this);enabled.setId(R.id.link_enabled);enabled.setText(R.string.link_enabled);enabled.setMinHeight(TrialUi.dp(this,56));body.addView(enabled);
        status=TrialUi.text(this,body,R.string.link_loading);status.setId(R.id.link_status);message=TrialUi.text(this,body,R.string.link_loading);
        TrialUi.button(this,body,R.string.link_refresh,false,this::refresh);
        TrialUi.button(this,body,R.string.journal_title,false,()->startActivity(new android.content.Intent(this,TrialJournalActivity.class)));
        TrialUi.button(this,body,R.string.back,false,this::finish);
        source.setOnItemSelectedListener(choices);target.setOnItemSelectedListener(choices);
        enabled.setOnCheckedChangeListener((button,on)->{if(!rendering&&!busy)save(on);});
        origin=EngineHost.origin();lease=EngineHost.currentLease();
        try{client=new DeviceLinkConnection(origin,EngineHost.token());}catch(Exception e){message.setText(R.string.link_unavailable);}
        update();
    }
    private final android.widget.AdapterView.OnItemSelectedListener choices=new android.widget.AdapterView.OnItemSelectedListener(){
        public void onNothingSelected(android.widget.AdapterView<?> parent){}
        public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id){
            if(!rendering&&!busy&&settings!=null){String s=id(source,"sources"),t=id(target,"targets");
                if(!s.isEmpty()&&!t.isEmpty()&&(!s.equals(settings.optString("source"))||!t.equals(settings.optString("target")))){dirty=true;if(settings.optBoolean("enabled"))save(false);}}
        }
    };
    private boolean current(long seen){return foreground&&seen==visit&&lease==EngineHost.currentLease()&&EngineHost.state()==EngineHost.State.RUNNING&&origin!=null&&origin.equals(EngineHost.origin());}
    private String id(Spinner spinner,String key){JSONArray items=settings==null?null:settings.optJSONArray(key);JSONObject row=items==null?null:items.optJSONObject(spinner.getSelectedItemPosition());return row==null?"":row.optString("id");}
    private void spinner(Spinner view,JSONArray items,String chosen){
        java.util.ArrayList<String> names=new java.util.ArrayList<>();int selected=0;
        if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);names.add(item.optString("name"));if(item.optString("id").equals(chosen))selected=i;}
        if(names.isEmpty())names.add(getString(R.string.link_none));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,names);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);view.setAdapter(adapter);view.setSelection(selected);
    }
    private String value(JSONObject data,String key){return data.isNull(key)?getString(R.string.link_unknown):getString(data.optBoolean(key)?R.string.link_on:R.string.link_off);}
    private void show(JSONObject data){
        rendering=true;settings=data;
        if(!dirty){spinner(source,data.optJSONArray("sources"),data.optString("source"));spinner(target,data.optJSONArray("targets"),data.optString("target"));}
        enabled.setChecked(data.optBoolean("enabled"));
        String state=data.optString("state");status.setText(getString(state.equals("active")?R.string.link_active:state.equals("waiting")?R.string.link_waiting:R.string.link_disabled)+"\n"+getString(R.string.link_values,value(data,"demand"),value(data,"actual")));
        // Spinner callbacks can be posted after layout; release rendering after them.
        handler.post(()->{rendering=false;update();});update();
    }
    private void update(){boolean ready=!busy&&current(visit)&&settings!=null;source.setEnabled(ready);target.setEnabled(ready);enabled.setEnabled(ready&&(settings.optBoolean("enabled")||!id(source,"sources").isEmpty()&&!id(target,"targets").isEmpty()));}
    private String error(Exception e){String code=e instanceof DeviceLinkConnection.Refused?e.getMessage():"";return getString(code.equals("human_session")?R.string.link_auth:code.equals("permission")?R.string.link_permission:code.equals("changed")?R.string.link_changed:R.string.link_unavailable);}
    private void read(){if(client==null||busy||!current(visit))return;busy=true;update();long seen=visit;
        io.execute(()->{try{JSONObject next=client.read();runOnUiThread(()->{if(!current(seen))return;busy=false;show(next);message.setText("");});}catch(Exception e){runOnUiThread(()->{if(!current(seen))return;busy=false;message.setText(error(e));update();});}});
    }
    private void save(boolean on){if(client==null||busy||settings==null||!current(visit))return;
        String s=id(source,"sources"),t=id(target,"targets");if(!on){if(s.isEmpty())s=settings.optString("source",s);if(t.isEmpty())t=settings.optString("target",t);}if(s.isEmpty()||t.isEmpty())return;
        final JSONObject input=new JSONObject();try{input.put("source",s).put("target",t).put("enabled",on).put("fingerprint",settings.opt("fingerprint"));}catch(JSONException e){return;}
        busy=true;update();long seen=visit;message.setText(R.string.link_saving);
        io.execute(()->{try{if(!current(seen))return;client.login(ProtectedKey.open(this).getString("pin"));if(!current(seen))return;JSONObject next=client.save(input);
            runOnUiThread(()->{if(!current(seen))return;busy=false;dirty=false;show(next);message.setText(R.string.link_saved);});
        }catch(Exception e){runOnUiThread(()->{if(!current(seen))return;busy=false;rendering=true;enabled.setChecked(settings.optBoolean("enabled"));rendering=false;message.setText(error(e));update();});}finally{client.logout();}});
    }
    private void refresh(){if(client==null||busy||!current(visit))return;long seen=visit;String chosen=id(source,"sources");busy=true;update();
        io.execute(()->{try{JSONObject result=client.refresh(new JSONObject().put("source",chosen));runOnUiThread(()->{if(!current(seen))return;busy=false;message.setText(getString(result.optString("status").equals("queued")?R.string.link_refresh_queued:R.string.link_refresh_wait));update();handler.postDelayed(()->read(),1500);});}catch(Exception e){runOnUiThread(()->{if(!current(seen))return;busy=false;message.setText(error(e));update();});}});
    }
    @Override public void onStart(){super.onStart();foreground=true;visit++;busy=false;update();handler.post(poll);}
    @Override public void onStop(){foreground=false;visit++;handler.removeCallbacks(poll);super.onStop();}
    @Override public void onDestroy(){io.shutdown();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
