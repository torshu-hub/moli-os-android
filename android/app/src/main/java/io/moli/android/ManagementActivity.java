package io.moli.android;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Service controls and a single minimal catalogue form; all state is reread from TOML. */
public final class ManagementActivity extends Activity {
    private LinearLayout root,form;
    private TextView status;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String snapshot;
    private EditText instance;
    private final Map<String,EditText> fields=new LinkedHashMap<>();
    private Button save;
    private final Runnable refresh=new Runnable(){public void run(){
        EngineHost.State state=EngineHost.state();status.setText(state==EngineHost.State.RUNNING?R.string.running:state==EngineHost.State.STOPPED?R.string.stopped:state==EngineHost.State.ERROR?R.string.operation_failed:R.string.working);
        handler.postDelayed(this,500);
    }};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);root=HostUi.screen(this);HostUi.text(this,root,R.string.management);status=HostUi.text(this,root,R.string.stopped);
        HostUi.button(this,root,R.string.start,()->HostUi.command(this,null));
        HostUi.button(this,root,R.string.stop,()->HostUi.command(this,MoliService.STOP));
        HostUi.button(this,root,R.string.restart,()->HostUi.command(this,MoliService.RESTART));
        HostUi.button(this,root,R.string.integrations,this::load);
        HostUi.button(this,root,R.string.export,()->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Moli-OS-Beta2-journal.txt"),73));
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);scroll.addView(form);
    }
    private <T> void run(Future<T> task,java.util.function.Consumer<T> done){new Thread(()->{try{T result=task.get();runOnUiThread(()->{if(!isDestroyed())done.accept(result);});}catch(Exception failure){runOnUiThread(()->HostUi.error(this));}},"moli-management").start();}
    private void load(){
        if(!ProtectedKey.exists(this)){HostUi.error(this,new IllegalStateException("start_first"));return;}
        try{run(EngineHost.inspect(()->{
            ConfigStore store=new ConfigStore(this);String text=store.read();
            JSONObject result=ConfigStore.nativeCall(new JSONObject().put("action","form").put("text",text));result.put("text",text);return result;
        }),this::catalogue);}catch(Exception failure){HostUi.error(this,failure);}
    }
    private void catalogue(JSONObject result){
        try{form.removeAllViews();snapshot=result.getString("text");JSONObject metadata=result.getJSONObject("manifest").getJSONObject("integration");
            Button open=new Button(this);open.setText(getString(R.string.catalogue_entry,metadata.getString("name"),metadata.getJSONArray("brands").getString(0)));form.addView(open);open.setOnClickListener(v->parameters(result));
        }catch(Exception failure){HostUi.error(this,failure);}
    }
    private void parameters(JSONObject result){
        try{form.removeAllViews();fields.clear();HostUi.text(this,form,R.string.parameters);
            JSONArray instances=result.getJSONArray("instances");
            // Every configured instance has its own form, preserving its stable id.
            if(instances.length()>1){for(int i=0;i<instances.length();i++){JSONObject item=instances.getJSONObject(i);Button b=new Button(this);b.setText(item.getString("id"));form.addView(b);b.setOnClickListener(v->edit(result,item));}}
            else edit(result,instances.length()==1?instances.getJSONObject(0):null);
        }catch(Exception failure){HostUi.error(this,failure);}
    }
    private void edit(JSONObject result,JSONObject selected){
        try{form.removeAllViews();fields.clear();HostUi.text(this,form,R.string.manual_variables);
            HostUi.text(this,form,R.string.instance);instance=new EditText(this);form.addView(instance);
            instance.setText(selected==null?result.getJSONObject("manifest").getJSONObject("integration").getString("id"):selected.getString("id"));instance.setEnabled(selected==null);
            JSONArray vars=result.getJSONObject("manifest").getJSONObject("needs").getJSONArray("vars");
            JSONObject current=selected==null?null:selected.optJSONObject("vars");
            for(int i=0;i<vars.length();i++){String name=vars.getString(i);TextView label=new TextView(this);label.setText(name);form.addView(label);EditText field=new EditText(this);field.setSingleLine(true);field.setInputType(InputType.TYPE_CLASS_TEXT);field.setSaveEnabled(false);if(current!=null)field.setText(current.optString(name,""));form.addView(field);fields.put(name,field);}
            save=HostUi.button(this,form,R.string.save,this::save);
        }catch(Exception failure){HostUi.error(this,failure);}
    }
    private void save(){
        try{JSONObject vars=new JSONObject();for(Map.Entry<String,EditText> e:fields.entrySet())vars.put(e.getKey(),e.getValue().getText().toString());String id=instance.getText().toString(),before=snapshot;
            save.setEnabled(false);
            Future<String> task=EngineHost.maintain(this,()->{ConfigStore store=new ConfigStore(this);store.save(before,id,vars);GeneralJournal.record(this,GeneralJournal.Event.CONFIGURATION_SAVED);return store.read();});
            new Thread(()->{try{String next=task.get();runOnUiThread(()->{if(!isDestroyed()){snapshot=next;form.removeAllViews();HostUi.text(this,form,R.string.saved);}});}catch(Exception failure){GeneralJournal.record(this,GeneralJournal.Event.CONFIGURATION_REJECTED);runOnUiThread(()->{if(!isDestroyed()){save.setEnabled(true);HostUi.error(this,failure);}});}},"moli-save").start();
        }catch(Exception failure){if(save!=null)save.setEnabled(true);HostUi.error(this,failure);}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=73||result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri uri=data.getData();new Thread(()->{try{if(!"content".equals(uri.getScheme()))throw new java.io.IOException();String text=EngineHost.inspect(()->GeneralJournal.export(this)).get(10,TimeUnit.SECONDS);try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException();out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}runOnUiThread(()->android.widget.Toast.makeText(this,R.string.exported,Toast.LENGTH_LONG).show());}catch(Exception failure){runOnUiThread(()->HostUi.error(this));}},"moli-export").start();
    }
    @Override public void onStart(){super.onStart();handler.post(refresh);}
    @Override public void onStop(){handler.removeCallbacks(refresh);super.onStop();}
}
