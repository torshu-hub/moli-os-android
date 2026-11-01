package io.moli.android;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.Future;
import org.json.*;

/** Embedded management page; only explicit reads and the existing REST actions. */
final class AutomationPanel {
    static final class State {
        JSONObject snapshot,editing;
        boolean form;
        String source="",target="",error;
        Future<JSONObject> work;
    }
    private final Activity activity;
    private final State state;
    private final Runnable render;
    private LinearLayout root;
    private interface Job {JSONObject run(AutomationRest api)throws Exception;}
    AutomationPanel(Activity activity,State state,Runnable render){this.activity=activity;this.state=state;this.render=render;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private String str(int id){return activity.getString(id);}
    private TextView text(LinearLayout parent,String label,int size){TextView v=new TextView(activity);v.setText(label);v.setTextSize(size);v.setTextColor(activity.getColor(R.color.management_ink));v.setPadding(0,dp(5),0,dp(5));parent.addView(v);return v;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(activity);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(20),dp(12),dp(20),dp(12));c.setBackgroundResource(R.drawable.management_catalogue_row);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);root.addView(c,p);return c;}
    private Button button(LinearLayout parent,int label,String tag,Runnable action){Button b=new Button(activity);b.setText(label);b.setTag(tag);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setBackgroundResource(R.drawable.management_catalogue_row);b.setTextColor(activity.getColorStateList(R.color.management_button_text));b.setEnabled(state.work==null);parent.addView(b);b.setOnClickListener(v->action.run());return b;}
    private LinearLayout actions(LinearLayout parent){LinearLayout row=new LinearLayout(activity);row.setOrientation(activity.getResources().getConfiguration().screenWidthDp>=600?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);parent.addView(row);return row;}
    private static JSONObject read(AutomationRest api)throws Exception{return new JSONObject().put("devices",api.request("GET","/api/devices",null,null).getJSONArray("devices")).put("automations",api.request("GET","/api/automations",null,null).getJSONArray("automations"));}
    private void submit(Job job){
        if(state.work!=null)return;
        String origin=EngineHost.origin(),token=EngineHost.token();
        if(origin==null||token==null){state.error=str(R.string.auto_engine);render.run();return;}
        AutomationRest api=new AutomationRest(origin,token,CookieManager.getInstance().getCookie(origin));
        state.error=null;
        state.work=EngineHost.inspect(()->{
            try{return job.run(api);}catch(AutomationRest.RequestFailure failure){if(failure.status==409)return read(api).put("conflict",true);throw failure;}
        });
        render.run();
    }
    void load(){submit(AutomationPanel::read);}
    void complete(){
        if(state.work==null||!state.work.isDone())return;
        try{state.snapshot=state.work.get();state.error=state.snapshot.optBoolean("conflict")?str(R.string.auto_conflict):null;state.form=false;state.editing=null;}
        catch(Exception failure){state.error=str(R.string.auto_error);}
        state.work=null;render.run();
    }
    boolean back(){if(state.work!=null)return true;if(!state.form)return false;state.form=false;state.editing=null;render.run();return true;}
    private JSONArray devices(){return state.snapshot==null?new JSONArray():state.snapshot.optJSONArray("devices");}
    private String name(String point){for(int i=0;i<devices().length();i++){JSONObject d=devices().optJSONObject(i);if(d!=null&&point.startsWith(d.optString("id")+"/"))return DemandAutomation.name(d);}return str(R.string.auto_endpoint_missing);}
    private void editor(String id){activity.startActivity(new Intent(activity,MainActivity.class).putExtra("automation",id));}
    private void form(JSONObject existing){state.form=true;state.editing=existing;state.source="";state.target="";if(existing!=null)try{String[] pair=DemandAutomation.wiring(existing.getJSONObject("automation").getJSONObject("graph"));if(pair!=null){state.source=pair[0];state.target=pair[1];}}catch(JSONException ignored){}render.run();}
    void mount(LinearLayout parent)throws JSONException {
        root=parent;
        if(state.error!=null)text(root,state.error,14).setTextColor(activity.getColor(R.color.management_error));
        if(state.work!=null){text(root,str(R.string.working),14);return;}
        if(state.form){formView();return;}
        LinearLayout tools=actions(root);button(tools,R.string.auto_new,"auto:new",()->form(null));button(tools,R.string.auto_reload,"auto:reload",this::load);
        text(root,str(R.string.auto_help),14);
        if(state.snapshot==null)return;
        JSONArray rules=state.snapshot==null?new JSONArray():state.snapshot.optJSONArray("automations");
        if(rules==null||rules.length()==0){text(root,str(R.string.auto_empty),16);return;}
        for(int i=0;i<rules.length();i++){
            JSONObject view=rules.getJSONObject(i),a=view.getJSONObject("automation");LinearLayout row=card();
            TextView title=text(row,a.getString("name"),18);title.setTypeface(null,android.graphics.Typeface.BOLD);
            TextView badge=text(row,str(AutomationPresentation.state(view)),14);badge.setTag("auto:state:"+a.getString("id"));badge.setTextColor(activity.getColor(view.optBoolean("live")?R.color.management_success:R.color.management_muted));
            String[] pair=DemandAutomation.wiring(a.getJSONObject("graph"));
            if(pair!=null)text(row,name(pair[0])+" → "+name(pair[1]),15);
            LinearLayout controls=actions(row);
            if(pair!=null)button(controls,R.string.auto_edit,"auto:edit:"+a.getString("id"),()->form(view));
            button(controls,R.string.auto_editor,"auto:editor:"+a.getString("id"),()->editor(a.optString("id")));
            if(a.optBoolean("enabled"))button(controls,R.string.auto_disable,"auto:disable:"+a.getString("id"),()->submit(api->{api.request("POST","/api/automations/"+a.getString("id")+"/enabled",new JSONObject().put("on",false).put("fingerprint",view.getString("fingerprint")),null);return read(api);}));
        }
    }
    private void formView()throws JSONException {
        LinearLayout form=card();text(form,str(state.editing==null?R.string.auto_new:R.string.auto_edit),18);
        List<String> sources=new ArrayList<>(),targets=new ArrayList<>(),sourceNames=new ArrayList<>(),targetNames=new ArrayList<>();
        for(int i=0;i<devices().length();i++){
            JSONObject d=devices().getJSONObject(i);
            if("Netatmo".equalsIgnoreCase(d.optString("manufacturer"))&&DemandAutomation.point(d,"declared_demand",false)){sources.add(d.getString("id")+"/declared_demand");sourceNames.add(DemandAutomation.name(d));}
            if("Meross".equalsIgnoreCase(d.optString("manufacturer"))&&"MSS310".equalsIgnoreCase(d.optString("model"))&&DemandAutomation.point(d,"on",true)){targets.add(d.getString("id")+"/on");targetNames.add(DemandAutomation.name(d));}
        }
        if(sources.isEmpty()||targets.isEmpty()||(state.editing!=null&&(!sources.contains(state.source)||!targets.contains(state.target)))){text(form,str(R.string.auto_no_devices),16);button(form,R.string.auto_back,"auto:cancel",()->back());return;}
        text(form,str(R.string.auto_source),14);spinner(form,sources,sourceNames,true);
        text(form,str(R.string.auto_target),14);spinner(form,targets,targetNames,false);
        text(form,str(R.string.auto_draft),14);
        LinearLayout controls=actions(form);button(controls,R.string.auto_save,"auto:save",this::save);button(controls,R.string.auto_back,"auto:cancel",()->back());
    }
    private void spinner(LinearLayout parent,List<String> ids,List<String> names,boolean source){
        Spinner picker=new Spinner(activity);picker.setTag(source?"auto:source":"auto:target");picker.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,names));parent.addView(picker);
        String selected=source?state.source:state.target;int index=Math.max(0,ids.indexOf(selected));picker.setSelection(index);
        if(source)state.source=ids.get(index);else state.target=ids.get(index);
        picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(source)state.source=ids.get(position);else state.target=ids.get(position);}});
    }
    private void save(){
        final String from=state.source,to=state.target;final JSONObject existing=state.editing;
        submit(api->{
            if(existing!=null){JSONObject a=new JSONObject(existing.getJSONObject("automation").toString());a.put("graph",DemandAutomation.retarget(a.getJSONObject("graph"),from,to));api.request("PUT","/api/automations/"+a.getString("id"),a,existing.getString("fingerprint"));return read(api);}
            JSONObject snapshot=read(api);JSONArray rules=snapshot.getJSONArray("automations");
            for(int i=0;i<rules.length();i++){String[] pair=DemandAutomation.wiring(rules.getJSONObject(i).getJSONObject("automation").getJSONObject("graph"));if(pair!=null&&from.equals(pair[0])&&to.equals(pair[1]))return snapshot;}
            api.request("POST","/api/automations",new JSONObject().put("name",str(R.string.auto_rule_name)).put("mode","queued").put("enabled",false).put("graph",DemandAutomation.graph(from,to)),null);return read(api);
        });
    }
}
