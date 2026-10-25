package io.moli.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Private personal experiment. External browser, exact pasted callback and
 * post-install vault provisioning; sensitive fields never enter saved state. */
public final class NetatmoOAuthActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final NetatmoOAuthUi model=new NetatmoOAuthUi();
    NetatmoOAuthUi.Backend backend=input->NetatmoOAuthStore.request(this,input);
    private TextView status,explanation;
    private CheckBox consent;
    private Spinner instance;
    private Spinner homes;
    private EditText callback;
    private Button connect,check,cancel,disconnect,provision,complete,loadHomes,selectHome;
    private boolean busy,foreground,wasPending;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        wasPending=saved!=null&&saved.getBoolean("awaiting",false);
        LinearLayout body=TrialUi.page(this);TrialUi.title(this,body,R.string.netatmo_title);
        TrialUi.text(this,body,R.string.netatmo_read_help);
        TrialUi.text(this,body,R.string.netatmo_personal_experiment);
        explanation=TrialUi.text(this,body,R.string.netatmo_setup);
        instance=new Spinner(this);body.addView(instance);
        instance.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id){
                String chosen=String.valueOf(parent.getItemAtPosition(position));
                if(!chosen.equals(model.selected))try{model.select(chosen);refresh();}catch(IllegalArgumentException ignored){}
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        consent=new CheckBox(this);consent.setText(R.string.netatmo_consent);consent.setSaveEnabled(false);body.addView(consent);
        consent.setOnCheckedChangeListener((button,checked)->render());
        status=TrialUi.text(this,body,R.string.netatmo_wait);
        provision=TrialUi.button(this,body,R.string.netatmo_provision,false,this::provision);
        connect=TrialUi.button(this,body,R.string.netatmo_connect,true,()->{boolean approved=consent.isChecked();work(()->model.begin(backend,approved),url->{
            try {startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));wasPending=true;}
            catch(ActivityNotFoundException|SecurityException unavailable){cancelSession();status.setText(R.string.netatmo_browser_missing);}
        });});
        check=TrialUi.button(this,body,R.string.netatmo_check,false,this::refresh);
        TrialUi.text(this,body,R.string.netatmo_return_help);
        callback=privateInput(body,R.string.netatmo_return_input,true,4096);
        complete=TrialUi.button(this,body,R.string.netatmo_complete,false,()->{
            String value=callback.getText().toString().trim();callback.setText("");
            work(()->{JSONObject request=model.request("complete",false).put("callback",value);model.apply(backend.call(request));return "";},ignored->render());
        });
        loadHomes=TrialUi.button(this,body,R.string.netatmo_load_homes,false,()->work(()->{model.apply(backend.call(model.request("homes",false)));return "";},ignored->render()));
        homes=new Spinner(this);body.addView(homes);
        selectHome=TrialUi.button(this,body,R.string.netatmo_select_home,false,this::selectHome);
        cancel=TrialUi.button(this,body,R.string.netatmo_cancel,false,this::cancelSession);
        disconnect=TrialUi.button(this,body,R.string.netatmo_disconnect,false,()->new AlertDialog.Builder(this)
            .setMessage(R.string.netatmo_disconnect_help).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.netatmo_disconnect,(dialog,which)->work(()->{
                model.apply(backend.call(model.request("disconnect",true)));return "";
            },ignored->{render();status.setText(R.string.netatmo_revocation);})).show());
        TrialUi.button(this,body,R.string.back,false,this::finish);
        render();
    }
    private EditText privateInput(LinearLayout parent,int hint,boolean secret,int limit){
        EditText field=new EditText(this);field.setHint(hint);field.setSingleLine(true);field.setSaveEnabled(false);
        field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(secret?android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(limit)});parent.addView(field);return field;
    }
    private void provision(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);int pad=TrialUi.dp(this,18);form.setPadding(pad,pad,pad,pad);
        TrialUi.text(this,form,getString(R.string.netatmo_redirect_registration,NetatmoOAuthUi.PERSONAL_REDIRECT),15);
        EditText id=privateInput(form,R.string.netatmo_client_id,false,128),secret=privateInput(form,R.string.netatmo_client_secret,true,2048);
        if(!model.clientId.isEmpty())id.setText(model.clientId);
        CheckBox experiment=new CheckBox(this);experiment.setText(R.string.netatmo_personal_accept);experiment.setSaveEnabled(false);form.addView(experiment);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.netatmo_provision).setView(form).setNegativeButton(android.R.string.cancel,(d,w)->{id.setText("");secret.setText("");})
            .setPositiveButton(R.string.netatmo_save_application,(d,w)->{
                String app=id.getText().toString().trim(),value=secret.getText().toString();boolean accepted=experiment.isChecked();id.setText("");secret.setText("");
                if(!accepted){status.setText(R.string.netatmo_personal_accept);return;}
                work(()->{JSONObject application=new JSONObject().put("client_id",app).put("client_secret",value).put("experimental",true);
                    JSONObject request=model.request("provision",true).put("application",application);
                    try{model.apply(backend.call(request));return "";}finally{application.remove("client_secret");request.remove("application");}
                },ignored->render());
            }).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.show();
    }
    private void selectHome(){
        int position=homes.getSelectedItemPosition();if(position<0||position>=model.homeIds.size())return;
        String chosen=model.homeIds.get(position);
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        TrialUi.text(this,form,getString(R.string.netatmo_home_confirm,model.homeNames.get(position)),16);
        CheckBox enabled=new CheckBox(this);enabled.setText(R.string.netatmo_enable_reads);enabled.setChecked(model.enabled);enabled.setSaveEnabled(false);form.addView(enabled);
        new AlertDialog.Builder(this).setTitle(R.string.netatmo_select_home).setView(form).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.netatmo_select_home,(d,w)->{boolean activate=enabled.isChecked();work(()->{
                model.apply(backend.call(model.request("prepare_home",true).put("home_id",chosen).put("enable",activate)));return "";
            },ignored->{render();status.setText(model.enabled?R.string.netatmo_home_active:R.string.netatmo_home_inactive);});}).show();
    }
    private interface Work{String run()throws Exception;}
    private interface Done{void accept(String result);}
    private void work(Work task,Done done){
        if(busy)return;busy=true;render();
        io.execute(()->{
            try {String result=task.run();runOnUiThread(()->{busy=false;if(isDestroyed())return;render();if(foreground)done.accept(result);});}
            catch(Exception failure){
                Throwable cause=failure;while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();
                // Static codes only: no throwable, broker body, callback or URI logging.
                String code=cause instanceof ProfileStore.Rejected||cause instanceof IllegalArgumentException?cause.getMessage():"oauth_network";
                try{model.apply(backend.call(model.request("status",false)));}catch(Exception ignored){}
                runOnUiThread(()->{busy=false;if(isDestroyed())return;render();status.setText(error(code));});
            }
        });
    }
    private void refresh(){work(()->{model.refresh(backend);return "";},ignored->{
        render();if(model.interrupted(wasPending))status.setText(R.string.netatmo_interrupted);
        if(!model.state.equals("pending")&&!model.state.equals("saving"))wasPending=false;
    });}
    private void cancelSession(){work(()->{model.apply(backend.call(model.request("cancel",false)));return "";},ignored->{wasPending=false;render();});}
    private void render(){
        if(status==null||connect==null)return;
        explanation.setText(model.setupRequired?R.string.netatmo_personal_setup:R.string.netatmo_personal_ready);
        connect.setEnabled(!busy&&!model.setupRequired&&consent.isChecked()&&!model.state.equals("pending")&&!model.state.equals("saving")&&EngineHost.state()==EngineHost.State.STOPPED);
        check.setEnabled(!busy);cancel.setEnabled(!busy&&(model.state.equals("pending")||model.state.equals("saving")));
        disconnect.setEnabled(!busy&&model.credentials&&EngineHost.state()==EngineHost.State.STOPPED);
        boolean stopped=EngineHost.state()==EngineHost.State.STOPPED;
        provision.setEnabled(!busy&&!model.credentials&&!model.state.equals("pending")&&!model.state.equals("saving")&&stopped);
        complete.setEnabled(!busy&&model.state.equals("pending")&&stopped);
        callback.setEnabled(complete.isEnabled());
        loadHomes.setEnabled(!busy&&model.credentials&&stopped);
        selectHome.setEnabled(!busy&&!model.homeIds.isEmpty()&&stopped);
        homes.setEnabled(selectHome.isEnabled());
        if(!model.homeNames.isEmpty()){
            int selected=homes.getSelectedItemPosition();homes.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,model.homeNames));
            int current=model.homeIds.indexOf(model.selectedHome);homes.setSelection(current>=0?current:Math.max(0,selected));
        }
        instance.setEnabled(!busy&&!model.state.equals("pending")&&!model.state.equals("saving"));
        if(!model.instances.isEmpty()&&(instance.getAdapter()==null||instance.getAdapter().getCount()!=model.instances.size()))instance.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,model.instances));
        if(!model.instances.isEmpty())instance.setSelection(model.instances.indexOf(model.selected));
        status.setText(switch(model.state){case "pending"->R.string.netatmo_pending;case "saving"->R.string.netatmo_saving;case "denied"->R.string.netatmo_denied;case "expired"->R.string.netatmo_expired;case "stored"->R.string.netatmo_stored;default->model.credentials?R.string.netatmo_stored:R.string.netatmo_disconnected;});
        if(model.setupRequired)status.setText(model.credentials?R.string.netatmo_existing_setup:R.string.netatmo_disconnected);
        if(model.verified)status.setText(R.string.netatmo_connected);
        else if(EngineHost.state()!=EngineHost.State.STOPPED)status.setText(R.string.netatmo_stop_first);
    }
    private static int error(String code){
        if(code==null)return R.string.netatmo_error;
        return switch(code){case "oauth_setup"->R.string.netatmo_personal_setup;case "oauth_consent"->R.string.netatmo_consent;case "oauth_expired"->R.string.netatmo_expired;case "oauth_denied"->R.string.netatmo_denied;case "storage"->R.string.netatmo_storage;case "running"->R.string.netatmo_stop_first;case "account_initialize"->R.string.integration_initialize;
            case "oauth_state","oauth_replay","oauth_input"->R.string.netatmo_callback_invalid;
            case "oauth_client_rejected"->R.string.netatmo_client_rejected;
            case "oauth_code_rejected"->R.string.netatmo_code_rejected;
            case "oauth_redirect_rejected"->R.string.netatmo_redirect_rejected;
            case "oauth_existing_credentials"->R.string.netatmo_replace_blocked;
            case "oauth_home_conflict"->R.string.netatmo_home_conflict;
            case "oauth_ambiguous"->R.string.netatmo_ambiguous;
            case "rate_limit"->R.string.netatmo_quota;
            case "oauth_tokens"->R.string.netatmo_tokens_rejected;
            case "oauth_network"->R.string.netatmo_network;
            default->R.string.netatmo_error;};
    }
    @Override protected void onResume(){super.onResume();foreground=true;refresh();}
    @Override protected void onPause(){foreground=false;super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("awaiting",wasPending||model.state.equals("pending")||model.state.equals("saving"));super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
