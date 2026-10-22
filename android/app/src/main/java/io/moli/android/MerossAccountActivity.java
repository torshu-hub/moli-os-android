package io.moli.android;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** One foreground task: cached inventory → automatic signed identity → explicit batch consent. */
public final class MerossAccountActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    MerossAccountStore.Backend backend=r->MerossAccountStore.request(this,r);
    interface Source {DiscoverySearch create();}
    Source discovery=()->new LocalDiscovery(this,true,true);
    private DiscoverySearch running;
    private LinearLayout login,cards,body;
    private EditText email,password,otp;
    private Spinner region;
    private CheckBox cloudConsent;
    TextView status,summary;
    Button search,add,refresh;
    AlertDialog consentDialog,addDialog;
    JSONArray inventory=new JSONArray();
    JSONObject settings=new JSONObject();
    final MerossMatches matches=new MerossMatches();
    final LinkedHashMap<String,CheckBox> choices=new LinkedHashMap<>();
    private final LinkedHashSet<String> candidates=new LinkedHashSet<>();
    private List<String> pending=new ArrayList<>();
    private int offset,generation;
    private String scan="";
    boolean active,busy,loaded,connected,foreground;
    private long refreshAt,scanAt;
    private final Runnable tick=new Runnable(){public void run(){
        if(!foreground)return;
        search.setEnabled(connected&&!active&&!busy&&SystemClock.elapsedRealtime()>=scanAt);
        refresh.setEnabled(connected&&!active&&!busy&&SystemClock.elapsedRealtime()>=refreshAt);
        main.postDelayed(this,500);
    }};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);TrialLog.begin(this);
        // Screens containing account input or inventory names remain private. Test captures use a disposable copy.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        body=TrialUi.page(this);TrialUi.title(this,body,R.string.auto_meross_title);
        TrialUi.text(this,body,R.string.auto_meross_help);
        login=TrialUi.card(this,body);TrialUi.text(this,login,R.string.account_consent);
        region=new Spinner(this);region.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"EU","US","AP","Global"}));login.addView(region);
        email=field(login,R.string.account_email,false);password=field(login,R.string.account_password,true);otp=field(login,R.string.account_otp,true);
        cloudConsent=new CheckBox(this);cloudConsent.setText(R.string.account_consent);login.addView(cloudConsent);
        TrialUi.button(this,login,R.string.account_connect,true,()->{
            if(!cloudConsent.isChecked())return;
            try{JSONObject r=new JSONObject().put("email",email.getText().toString().trim()).put("password",password.getText().toString()).put("otp",otp.getText().toString()).put("region",new String[]{"eu","us","ap","global"}[region.getSelectedItemPosition()]);password.setText("");otp.setText("");stopped(()->call("signin",r,this::inventory));}catch(JSONException ignored){}
        });
        status=TrialUi.text(this,body,R.string.account_wait);
        LinearLayout action=TrialUi.card(this,body);
        search=TrialUi.button(this,action,R.string.auto_meross_search,true,this::consentSearch);
        TrialUi.button(this,action,R.string.network_cancel,false,this::cancel);
        summary=TrialUi.text(this,body,R.string.auto_no_results);
        cards=new LinearLayout(this);cards.setOrientation(LinearLayout.VERTICAL);body.addView(cards);
        add=TrialUi.button(this,body,R.string.auto_add,true,this::confirmAdd);add.setEnabled(false);
        TrialUi.button(this,body,R.string.preview_devices,false,()->{startActivity(new Intent(this,MainActivity.class).putExtra("devices",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();});
        refresh=TrialUi.button(this,body,R.string.account_refresh,false,()->stopped(()->call("inventory",new JSONObject(),this::inventory)));
        TrialUi.button(this,body,R.string.preview_settings,false,()->new AlertDialog.Builder(this).setItems(new String[]{getString(R.string.account_forget),getString(R.string.account_manual)},(d,w)->{
            if(w==1)startActivity(new Intent(this,MerossManualActivity.class));
            else new AlertDialog.Builder(this).setMessage(R.string.account_forget_help).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.account_forget,(a,b)->stopped(()->{try{call("forget",new JSONObject().put("revoke",true),this::inventory);}catch(JSONException ignored){}})).show();
        }).show());
        TrialUi.button(this,body,R.string.back,false,this::finish);
        login.setVisibility(View.GONE);
        if(saved!=null)try{matches.add(new JSONArray(saved.getString("results","[]")));}catch(JSONException ignored){}
    }
    private EditText field(LinearLayout group,int label,boolean secret){TrialUi.text(this,group,label);EditText e=new EditText(this);e.setSingleLine(true);e.setHint(label);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);e.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1024)});group.addView(e);return e;}
    private void stopped(Runnable next){
        if(busy||active)return;
        if(EngineHost.state()==EngineHost.State.STOPPED){next.run();return;}
        new AlertDialog.Builder(this).setMessage(R.string.auto_stop_help).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.auto_stop,(d,w)->{
            getSharedPreferences("host",MODE_PRIVATE).edit().putBoolean("wanted",false).apply();startService(new Intent(this,MoliService.class).setAction(MoliService.STOP));
            status.setText(R.string.stopping);int token=generation;
            main.post(new Runnable(){int left=150;public void run(){if(!foreground||isDestroyed()||token!=generation)return;if(EngineHost.state()==EngineHost.State.STOPPED)next.run();else if(--left>0)main.postDelayed(this,200);else status.setText(R.string.failed);}});
        }).show();
    }
    interface Done{void accept(JSONObject result)throws Exception;}
    private void call(String action,JSONObject request,Done done){
        if(busy)return;busy=true;int token=generation;
        try{request.put("action",action);}catch(JSONException ignored){busy=false;return;}
        TrialLog.action(this,event(action),TrialJournal.Source.COMPTE,TrialJournal.Outcome.DEBUT,TrialJournal.Error.AUCUNE,request.optBoolean("enabled")?1:0,0,request.optString("uuid"),request.optString("host"),0);
        io.execute(()->{
            JSONObject result=null;String failure=null;
            try{result=backend.call(request);}catch(Exception e){Throwable c=e;while(c.getCause()!=null&&c.getCause()!=c)c=c.getCause();failure=c instanceof ProfileStore.Rejected?c.getMessage():"account_network";}
            finally{for(String key:new String[]{"email","password","otp","master"})request.remove(key);}
            JSONObject answer=result;String error=failure;
            if(error==null&&answer!=null)record(action,request,answer);else TrialLog.action(this,event(action),TrialJournal.Source.COMPTE,TrialJournal.Outcome.REFUSE,TrialLog.error(error),0,0,request.optString("uuid"),request.optString("host"),0);
            main.post(()->{busy=false;if(isDestroyed()||token!=generation)return;
                if(error!=null){active=false;status.setText(MerossManualActivity.errorText(error));render();return;}
                try{done.accept(answer);}catch(Exception e){active=false;status.setText(R.string.account_protocol);}
            });
        });
    }
    void reload(){loaded=false;call("status",new JSONObject(),this::inventory);}
    private void inventory(JSONObject result)throws Exception{
        connected="connected".equals(result.optString("state"));inventory=result.optJSONArray("items");if(inventory==null)inventory=new JSONArray();
        refreshAt=SystemClock.elapsedRealtime()+result.optLong("inventory_wait_ms");scanAt=SystemClock.elapsedRealtime()+result.optLong("identify_wait_ms");
        login.setVisibility(connected?View.GONE:View.VISIBLE);status.setText(connected?getString(R.string.auto_inventory,inventory.length()):getString(R.string.account_disconnected));
        settings=EquipmentStore.read(this);loaded=true;render();
    }
    private void consentSearch(){if(active||busy||!connected)return;stopped(()->consentDialog=new AlertDialog.Builder(this).setTitle(R.string.auto_meross_search).setMessage(R.string.auto_scan_consent).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.network_search,(d,w)->begin(discovery.create())).show());}
    void begin(DiscoverySearch source){
        if(active||busy)return;active=true;int token=++generation;offset=0;pending.clear();candidates.clear();matches.hosts.clear();while(matches.results.length()>0)matches.results.remove(0);render();status.setText(R.string.auto_discovering);
        running=source;TrialLog.action(this,TrialJournal.Event.NETWORK_SEARCH,TrialJournal.Source.RESEAU,TrialJournal.Outcome.DEBUT,TrialJournal.Error.AUCUNE,1,0,null);
        source.start(new DiscoverySearch.Listener(){
            public void found(NetworkEvidence.Entry e){main.post(()->{if(token==generation&&active&&e.port==80&&NetworkEvidence.privateIp(NetworkEvidence.ipv4(e.ip)))candidates.add(e.ip);});}
            public void warning(){main.post(()->{if(token==generation)summary.setText(R.string.network_partial);});}
            public void finished(DiscoverySearch.End end){main.post(()->{
                if(token!=generation)return;running=null;
                TrialLog.action(MerossAccountActivity.this,TrialJournal.Event.NETWORK_SEARCH,TrialJournal.Source.RESEAU,end==DiscoverySearch.End.COMPLETE?TrialJournal.Outcome.REUSSI:TrialJournal.Outcome.ANNULE,TrialJournal.Error.AUCUNE,candidates.size(),0,null);
                if(end!=DiscoverySearch.End.COMPLETE){active=false;status.setText(end==DiscoverySearch.End.CANCELLED?R.string.auto_cancelled:R.string.account_wifi);return;}
                pending=new ArrayList<>(candidates);nextLease();
            });}
        });
    }
    private void nextLease(){
        if(!foreground||!active)return;
        if(offset>=pending.size()){active=false;scan="";status.setText(R.string.auto_finished);render();return;}
        if(offset>0){active=false;render();new AlertDialog.Builder(this).setMessage(getString(R.string.auto_continue,pending.size()-offset)).setNegativeButton(android.R.string.cancel,(d,w)->status.setText(R.string.auto_partial)).setPositiveButton(R.string.auto_continue_button,(d,w)->{active=true;call("identify_start",new JSONObject(),r->{scan=r.getString("scan");batch(64);});}).show();return;}
        call("identify_start",new JSONObject(),r->{scan=r.getString("scan");batch(64);});
    }
    private void batch(int left)throws Exception{
        if(!active||!foreground)return;
        if(offset>=pending.size()){active=false;scan="";status.setText(R.string.auto_finished);render();return;}
        if(left<=0){nextLease();return;}
        JSONArray list=new JSONArray();int end=Math.min(pending.size(),offset+Math.min(4,left));for(int i=offset;i<end;i++)list.put(pending.get(i));
        status.setText(getString(R.string.auto_progress,offset,pending.size()));int sent=end-offset;
        call("identify",new JSONObject().put("scan",scan).put("candidates",list),r->{matches.add(r.getJSONArray("results"));offset=end;render();batch(left-sent);});
    }
    void cancel(){
        ++generation;active=false;if(running!=null){running.close();running=null;}status.setText(R.string.auto_cancelled);render();
        // Already-issued identity GETs expire within 2.5 s; no further batch or save can follow.
        String old=scan;scan="";if(!old.isEmpty())io.execute(()->{try{backend.call(new JSONObject().put("action","identify_cancel").put("scan",old));}catch(Exception ignored){}});
        TrialLog.action(this,TrialJournal.Event.CANDIDATE_CHECK,TrialJournal.Source.COMPTE,TrialJournal.Outcome.ANNULE,TrialJournal.Error.AUCUNE,offset,pending.size(),null);
    }
    private JSONObject existing(String uuid){JSONArray rows=settings.optJSONArray("meross");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&uuid.equals(row.optString("uuid")))return row;}return null;}
    void render(){
        if(cards==null)return;HashSet<String> checked=new HashSet<>();for(Map.Entry<String,CheckBox> e:choices.entrySet())if(e.getValue().isChecked())checked.add(e.getKey());
        cards.removeAllViews();choices.clear();summary.setText(getString(R.string.auto_summary,matches.found(),inventory.length()));boolean possible=false;
        for(int i=0;i<inventory.length();i++){
            JSONObject item=inventory.optJSONObject(i);if(item==null)continue;String uuid=item.optString("uuid"),host=matches.unique(uuid);JSONObject old=existing(uuid);
            LinearLayout row=TrialUi.card(this,cards);TrialUi.text(this,row,item.optString("name"),21);
            boolean already=old!=null&&host!=null&&host.equals(old.optString("host"))&&old.optBoolean("enabled");
            TrialUi.text(this,row,already?R.string.auto_already:matches.ambiguous(uuid)?R.string.auto_ambiguous:host!=null?R.string.auto_found:active?R.string.auto_pending:R.string.auto_not_found);
            if(host!=null&&!already){possible=true;CheckBox box=new CheckBox(this);box.setText(old==null?R.string.auto_allow_add:R.string.auto_allow_update);box.setChecked(checked.contains(uuid));box.setEnabled(!active);row.addView(box);choices.put(uuid,box);}
        }
        int unknown=0,key=0,unavailable=0;for(int i=0;i<matches.results.length();i++){String r=matches.results.optJSONObject(i).optString("result");if(r.equals("key_rejected"))key++;else if(r.equals("unavailable"))unavailable++;else if(!r.equals("matched"))unknown++;}
        if(matches.results.length()>0)TrialUi.text(this,cards,getString(R.string.auto_other_results,unavailable,key,unknown),15);
        add.setEnabled(possible&&!active&&!busy);
    }
    private void confirmAdd(){
        if(active||busy)return;JSONArray plan=new JSONArray();StringBuilder details=new StringBuilder();
        try{for(int i=0;i<inventory.length();i++){JSONObject item=inventory.getJSONObject(i);String uuid=item.getString("uuid");CheckBox c=choices.get(uuid);String host=matches.unique(uuid);if(c==null||!c.isChecked()||host==null)continue;JSONObject old=existing(uuid);String name=old==null?item.optString("name"):old.optString("name",item.optString("name"));plan.put(new JSONObject().put("uuid",uuid).put("host",host).put("port",80).put("name",name).put("enabled",true));details.append("• ").append(name).append(" — ").append(getString(old==null?R.string.auto_add_label:R.string.auto_update_label)).append('\n');}
            if(plan.length()==0){status.setText(R.string.auto_choose);return;}
            addDialog=new AlertDialog.Builder(this).setTitle(R.string.auto_add).setMessage(getString(R.string.auto_confirm,details.toString())).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.auto_confirm_button,(d,w)->{active=true;saveNext(plan,0,0);}).show();
        }catch(JSONException ignored){status.setText(R.string.account_input);}
    }
    private void saveNext(JSONArray plan,int index,int count){
        if(index>=plan.length()){active=false;try{settings=EquipmentStore.read(this);}catch(Exception ignored){}render();status.setText(getString(R.string.auto_saved,count));
            getSharedPreferences("host",MODE_PRIVATE).edit().putBoolean("wanted",true).apply();startForegroundService(new Intent(this,MoliService.class));return;}
        JSONObject r=plan.optJSONObject(index);status.setText(getString(R.string.auto_saving,index+1,plan.length()));call("associate",r,response->saveNext(plan,index+1,count+1));
    }
    private void record(String action,JSONObject input,JSONObject answer){
        if(action.equals("identify")){JSONArray rows=answer.optJSONArray("results");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);String r=row.optString("result");String error=switch(r){case "matched"->null;case "different"->"account_uuid_different";case "key_rejected"->"account_key";case "unavailable"->"account_local";default->"account_protocol";};TrialLog.action(this,TrialJournal.Event.CANDIDATE_RESULT,TrialJournal.Source.COMPTE,r.equals("matched")?TrialJournal.Outcome.REUSSI:TrialJournal.Outcome.REFUSE,TrialLog.error(error),0,0,row.optString("uuid"),row.optString("host"),0);}}
        JSONArray items=answer.optJSONArray("items");TrialLog.action(this,event(action),TrialJournal.Source.COMPTE,action.equals("associate")?TrialJournal.Outcome.ENREGISTRE:action.equals("status")?TrialJournal.Outcome.CACHE:TrialJournal.Outcome.REUSSI,TrialJournal.Error.AUCUNE,items==null?0:items.length(),0,input.optString("uuid"),input.optString("host"),0);
    }
    private static TrialJournal.Event event(String action){return switch(action){case "signin"->TrialJournal.Event.ACCOUNT_SIGNIN;case "inventory"->TrialJournal.Event.ACCOUNT_INVENTORY;case "associate"->TrialJournal.Event.UUID_CHECK;case "identify","identify_start","identify_cancel"->TrialJournal.Event.CANDIDATE_CHECK;default->TrialJournal.Event.ACCOUNT_STATUS;};}
    @Override public void onStart(){super.onStart();foreground=true;main.post(tick);if(!loaded&&!busy)call("status",new JSONObject(),this::inventory);}
    @Override public void onStop(){foreground=false;main.removeCallbacks(tick);password.setText("");otp.setText("");email.setText("");if(active)cancel();super.onStop();}
    @Override public void onDestroy(){main.removeCallbacksAndMessages(null);if(running!=null)running.close();io.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("results",matches.results.toString());}
}
