package io.moli.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Native consent screen. External browser only; no exported callback, tokens,
 * client-secret input, URL in saved state, or implicit account connection. */
public final class NetatmoOAuthActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final NetatmoOAuthUi model=new NetatmoOAuthUi();
    NetatmoOAuthUi.Backend backend=input->NetatmoOAuthStore.request(this,input);
    private TextView status,explanation;
    private CheckBox consent;
    private Spinner instance;
    private Button connect,check,cancel,disconnect;
    private boolean busy,foreground,wasPending;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        wasPending=saved!=null&&saved.getBoolean("awaiting",false);
        LinearLayout body=TrialUi.page(this);TrialUi.title(this,body,R.string.netatmo_title);
        TrialUi.text(this,body,R.string.netatmo_read_help);
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
        connect=TrialUi.button(this,body,R.string.netatmo_connect,true,()->{boolean approved=consent.isChecked();work(()->model.begin(backend,approved),url->{
            try {startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));wasPending=true;}
            catch(ActivityNotFoundException|SecurityException unavailable){cancelSession();status.setText(R.string.netatmo_browser_missing);}
        });});
        check=TrialUi.button(this,body,R.string.netatmo_check,false,this::refresh);
        cancel=TrialUi.button(this,body,R.string.netatmo_cancel,false,this::cancelSession);
        disconnect=TrialUi.button(this,body,R.string.netatmo_disconnect,false,()->new AlertDialog.Builder(this)
            .setMessage(R.string.netatmo_disconnect_help).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.netatmo_disconnect,(dialog,which)->work(()->{
                model.apply(backend.call(model.request("disconnect",true)));return "";
            },ignored->{render();status.setText(R.string.netatmo_revocation);})).show());
        TrialUi.button(this,body,R.string.back,false,this::finish);
        render();
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
        explanation.setText(model.setupRequired?R.string.netatmo_setup:R.string.netatmo_server_ready);
        connect.setEnabled(!busy&&!model.setupRequired&&consent.isChecked()&&!model.state.equals("pending")&&!model.state.equals("saving")&&EngineHost.state()==EngineHost.State.STOPPED);
        check.setEnabled(!busy);cancel.setEnabled(!busy&&(model.state.equals("pending")||model.state.equals("saving")));
        disconnect.setEnabled(!busy&&model.credentials&&EngineHost.state()==EngineHost.State.STOPPED);
        instance.setEnabled(!busy&&!model.state.equals("pending")&&!model.state.equals("saving"));
        if(!model.instances.isEmpty()&&(instance.getAdapter()==null||instance.getAdapter().getCount()!=model.instances.size()))instance.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,model.instances));
        if(!model.instances.isEmpty())instance.setSelection(model.instances.indexOf(model.selected));
        status.setText(switch(model.state){case "pending"->R.string.netatmo_pending;case "saving"->R.string.netatmo_saving;case "denied"->R.string.netatmo_denied;case "expired"->R.string.netatmo_expired;case "stored"->R.string.netatmo_stored;default->model.credentials?R.string.netatmo_stored:R.string.netatmo_disconnected;});
        if(model.setupRequired)status.setText(model.credentials?R.string.netatmo_existing_setup:R.string.netatmo_disconnected);
        else if(EngineHost.state()!=EngineHost.State.STOPPED)status.setText(R.string.netatmo_stop_first);
    }
    private static int error(String code){
        if(code==null)return R.string.netatmo_error;
        return switch(code){case "oauth_setup"->R.string.netatmo_setup;case "oauth_consent"->R.string.netatmo_consent;case "oauth_expired"->R.string.netatmo_expired;case "oauth_denied"->R.string.netatmo_denied;case "storage"->R.string.netatmo_storage;case "running"->R.string.netatmo_stop_first;case "account_initialize"->R.string.integration_initialize;default->R.string.netatmo_error;};
    }
    @Override protected void onResume(){super.onResume();foreground=true;refresh();}
    @Override protected void onPause(){foreground=false;super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("awaiting",wasPending||model.state.equals("pending")||model.state.equals("saving"));super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
