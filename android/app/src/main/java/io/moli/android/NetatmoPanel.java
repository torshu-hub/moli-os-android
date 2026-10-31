package io.moli.android;
import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;
import org.json.*;

/** Account setup only. The portable Rust driver owns OAuth renewal and all Energy traffic/commands. */
final class NetatmoPanel {
    static final class State {
        JSONObject snapshot;String instance="netatmo-energy",error;Future<JSONObject> work;
        long checked;boolean reload;
    }
    final Activity activity;final State state;final Runnable render,reload;
    NetatmoPanel(Activity activity,State state,Runnable render,Runnable reload){this.activity=activity;this.state=state;this.render=render;this.reload=reload;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private String str(int id){return activity.getString(id);}
    private TextView text(LinearLayout parent,String label,int size){TextView v=new TextView(activity);v.setText(label);v.setTextSize(size);v.setTextColor(activity.getColor(R.color.management_ink));v.setPadding(dp(8),dp(8),dp(8),dp(8));parent.addView(v);return v;}
    private Button button(LinearLayout parent,int label,String tag,Runnable action){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setBackgroundResource(R.drawable.management_catalogue_row);b.setTag(tag);b.setEnabled(state.work==null);b.setOnClickListener(v->action.run());parent.addView(b);return b;}
    void select(JSONObject item)throws JSONException{
        JSONArray instances=item.getJSONArray("instances");boolean found=false;for(int i=0;i<instances.length();i++)if(state.instance.equals(instances.getJSONObject(i).getString("id")))found=true;if(!found&&instances.length()>0)state.instance=instances.getJSONObject(0).getString("id");
        if(state.snapshot!=null&&!state.instance.equals(state.snapshot.optString("selected")))state.snapshot=null;
        if(state.snapshot==null)load();
    }
    void load(){try{action(new JSONObject().put("operation","status"),false,false);}catch(JSONException ignored){}}
    private void action(JSONObject request,boolean maintenance,boolean changed){
        if(state.work!=null)return;
        state.error=null;state.reload=changed;
        Context app=activity.getApplicationContext();
        try {
            request.put("instance",state.instance);
            Callable<JSONObject> job=()->{
                JSONObject reply=new ConfigStore(app).netatmo(request);
                if(EngineHost.state()==EngineHost.State.RUNNING)try{reply.put("hub",WeatherBinding.snapshot());}catch(Exception unavailable){reply.put("hub_unavailable",true);}
                return reply;
            };
            state.work=maintenance?EngineHost.maintain(app,job):EngineHost.inspect(job);
        }catch(Exception error){state.error=str(R.string.netatmo_storage);}
        new Handler(Looper.getMainLooper()).post(render);
    }
    void complete(){
        if(state.work==null||!state.work.isDone())return;
        try{
            state.snapshot=state.work.get();state.checked=SystemClock.elapsedRealtime();
            String url=state.snapshot.optString("authorization_url","");
            if(!url.isEmpty()){
                android.net.Uri uri=android.net.Uri.parse(url);
                if(!"https".equals(uri.getScheme())||!"api.netatmo.com".equals(uri.getHost())||!"/oauth2/authorize".equals(uri.getPath()))throw new IllegalStateException("oauth_input");
                activity.startActivity(new Intent(Intent.ACTION_VIEW,uri));
            }
        }catch(Exception error){
            Throwable cause=error instanceof ExecutionException?error.getCause():error;
            String code=cause==null?"":cause.getMessage();state.error=str(errorLabel(code));
        }
        state.work=null;state.checked=SystemClock.elapsedRealtime();
        boolean changed=state.reload;state.reload=false;
        if(changed)reload.run();
        render.run();
    }
    static int errorLabel(String code){
        if("storage".equals(code))return R.string.netatmo_storage;
        if("rate_limit".equals(code))return R.string.netatmo_quota;
        if("oauth_network".equals(code))return R.string.netatmo_network;
        if("oauth_provider".equals(code))return R.string.netatmo_provider;
        if("oauth_client_rejected".equals(code))return R.string.netatmo_application_rejected;
        if("oauth_ambiguous".equals(code))return R.string.netatmo_ambiguous;
        if("oauth_denied".equals(code)||"oauth_code_rejected".equals(code))return R.string.netatmo_reauthorize;
        if("oauth_existing_credentials".equals(code))return R.string.netatmo_disconnect_first;
        if("oauth_home_conflict".equals(code))return R.string.netatmo_home_conflict;
        return R.string.netatmo_failed;
    }
    void show(LinearLayout parent,JSONObject item)throws JSONException{
        select(item);
        JSONObject s=state.snapshot;
        text(parent,str(R.string.netatmo_account),20);
        if(state.work!=null)text(parent,str(R.string.working),14);
        if(state.error!=null)text(parent,state.error,14).setTextColor(activity.getColor(R.color.management_error));
        if(s==null){button(parent,R.string.netatmo_refresh,"netatmo:refresh",this::load);return;}
        boolean connected=s.optBoolean("credential_present");
        boolean pending="pending".equals(s.optString("state"));
        int status=pending?R.string.netatmo_pending:s.optBoolean("revoked")?R.string.netatmo_reauthorize:s.optBoolean("ambiguous")?R.string.netatmo_ambiguous:!connected?R.string.netatmo_not_authorized:s.optLong("expires_at")<=System.currentTimeMillis()/1000+60?R.string.netatmo_renewing:R.string.netatmo_authorized;
        text(parent,str(status),16).setTag("netatmo:account-state");
        text(parent,str(s.optBoolean("write_allowed")?R.string.netatmo_read_write:R.string.netatmo_read_only),13);
        if(!s.isNull("selected_home"))text(parent,activity.getString(R.string.netatmo_selected_home,s.optString("selected_home")),14);
        JSONObject hub=s.optJSONObject("hub");
        if(hub!=null){
            JSONArray drivers=hub.optJSONArray("drivers");if(drivers!=null)for(int i=0;i<drivers.length();i++){
                JSONObject driver=drivers.getJSONObject(i);if(state.instance.equals(driver.optString("instance"))){JSONObject statusInfo=driver.optJSONObject("status");if(statusInfo!=null&&!statusInfo.optString("reason").isEmpty())text(parent,statusInfo.optString("reason"),14);}
            }
            JSONArray devices=hub.optJSONArray("devices");
            if(devices!=null)for(int i=0;i<devices.length();i++){
                JSONObject device=devices.getJSONObject(i);
                if(!state.instance.equals(device.optString("instance")))continue;
                String name=device.optString("native_name",device.optString("id"));JSONObject label=device.optJSONObject("label");if(label!=null&&!label.optString("name").isEmpty())name=label.optString("name");
                text(parent,name,15);
                JSONObject values=device.optJSONObject("state");
                if(values!=null){JSONObject collected=values.optJSONObject("collected_at");long at=collected==null?0:collected.optLong("value");
                    if(at>0)text(parent,activity.getString(R.string.netatmo_age,Math.max(0,(System.currentTimeMillis()-at)/1000)),13);
                    JSONObject error=values.optJSONObject("error");if(error!=null&&!error.optString("value").isEmpty())text(parent,error.optString("value"),13);
                }
            }
        }
        button(parent,R.string.netatmo_refresh,"netatmo:refresh",this::load);
        if(!connected){
            if(s.optBoolean("setup_required"))button(parent,R.string.netatmo_setup,"netatmo:setup",this::applicationDialog);
            else {if(!pending)button(parent,R.string.netatmo_connect,"netatmo:connect",this::consentDialog);button(parent,R.string.netatmo_callback,"netatmo:callback",this::callbackDialog);if(pending)button(parent,R.string.netatmo_cancel,"netatmo:cancel",()->{try{action(new JSONObject().put("operation","cancel"),false,false);}catch(JSONException ignored){}});}
        }else{
            button(parent,R.string.netatmo_homes,"netatmo:homes",()->{try{action(new JSONObject().put("operation","homes"),true,false);}catch(JSONException ignored){}});
        }
        JSONArray homes=s.optJSONArray("homes");
        if(homes!=null)for(int i=0;i<homes.length();i++){
            JSONObject home=homes.getJSONObject(i);Button choose=button(parent,R.string.netatmo_select_home,"netatmo:home:"+home.getString("id"),()->{
                try{action(new JSONObject().put("operation","prepare_home").put("consent",true).put("home_id",home.getString("id")).put("enable",existingEnabled(item)),true,true);}catch(JSONException ignored){}
            });choose.setText(home.getString("name")+" · "+str(R.string.netatmo_select_home));
        }
        if(connected||s.optBoolean("ambiguous")||s.optBoolean("revoked"))button(parent,R.string.netatmo_disconnect,"netatmo:disconnect",()->new AlertDialog.Builder(activity).setMessage(R.string.netatmo_disconnect_help).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.netatmo_disconnect,(d,w)->{try{action(new JSONObject().put("operation","disconnect").put("consent",true),true,false);}catch(JSONException ignored){}}).show());
    }
    private boolean existingEnabled(JSONObject item){JSONArray instances=item.optJSONArray("instances");return instances==null||instances.length()==0||instances.optJSONObject(0).optBoolean("enabled",true);}
    private EditText input(LinearLayout parent,int label,boolean secret){
        text(parent,str(label),14);EditText field=new EditText(activity);field.setSingleLine(true);field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));parent.addView(field);return field;
    }
    private LinearLayout form(){LinearLayout parent=new LinearLayout(activity);parent.setOrientation(LinearLayout.VERTICAL);parent.setPadding(dp(24),dp(8),dp(24),dp(8));return parent;}
    private void secure(AlertDialog dialog){activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.setOnDismissListener(d->activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));}
    private void applicationDialog(){
        LinearLayout form=form();text(form,str(R.string.netatmo_experiment),14);
        EditText client=input(form,R.string.netatmo_client,false),secret=input(form,R.string.netatmo_secret,true);
        CheckBox experimental=new CheckBox(activity);experimental.setText(R.string.netatmo_opt_in);form.addView(experimental);
        CheckBox write=new CheckBox(activity);write.setText(R.string.netatmo_write_consent);form.addView(write);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.netatmo_setup).setView(form).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.save,null).create();
        dialog.setOnShowListener(d->{secure(dialog);Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);save.setEnabled(false);experimental.setOnCheckedChangeListener((v,on)->save.setEnabled(on));save.setOnClickListener(v->{try{
            JSONObject application=new JSONObject().put("client_id",client.getText().toString().trim()).put("client_secret",secret.getText().toString()).put("experimental",experimental.isChecked()).put("write_thermostat",write.isChecked());
            client.setText("");secret.setText("");dialog.dismiss();action(new JSONObject().put("operation","provision").put("consent",true).put("application",application),true,false);
        }catch(JSONException ignored){}});});dialog.show();
    }
    private void consentDialog(){
        LinearLayout form=form();text(form,str(R.string.netatmo_redirect_help),14);
        CheckBox read=new CheckBox(activity);read.setText(R.string.netatmo_read_consent);form.addView(read);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.netatmo_connect).setView(form).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.netatmo_open_browser,null).create();
        dialog.setOnShowListener(d->{Button open=dialog.getButton(AlertDialog.BUTTON_POSITIVE);open.setEnabled(false);read.setOnCheckedChangeListener((v,on)->open.setEnabled(on));open.setOnClickListener(v->{dialog.dismiss();try{action(new JSONObject().put("operation","begin").put("consent",read.isChecked()),true,false);}catch(JSONException ignored){}});});dialog.show();
    }
    private void callbackDialog(){
        LinearLayout form=form();EditText callback=input(form,R.string.netatmo_callback_help,true);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.netatmo_callback).setView(form).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->{String value=callback.getText().toString();callback.setText("");try{action(new JSONObject().put("operation","complete").put("callback",value),true,false);}catch(JSONException ignored){}}).create();
        dialog.setOnShowListener(d->secure(dialog));dialog.show();
    }
}
