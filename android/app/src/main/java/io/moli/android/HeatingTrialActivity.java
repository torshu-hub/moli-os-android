package io.moli.android;

import android.app.*;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.util.concurrent.*;
import org.json.*;

/** One foreground confirmation; no saved consent, automatic replay or new transport. */
public final class HeatingTrialActivity extends Activity {
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private HeatingTrialConnection client;
    private JSONObject settings,ticket;
    private TextView selected,message;
    private EditText pin;
    private Button apply,refresh;
    private AlertDialog confirmation;
    private volatile boolean foreground;
    private boolean busy,interrupted;
    private long lease,visit;
    private String origin,token;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout body=TrialUi.page(this);TrialUi.title(this,body,R.string.heating_real_title);
        TrialUi.text(this,body,R.string.heating_real_help);
        selected=TrialUi.text(this,body,R.string.heating_loading);
        refresh=TrialUi.button(this,body,R.string.heating_refresh,false,this::refresh);
        pin=new EditText(this);pin.setId(R.id.heating_pin);pin.setSaveEnabled(false);pin.setHint(R.string.heating_pin_optional);pin.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);pin.setSingleLine(true);body.addView(pin);
        apply=TrialUi.button(this,body,R.string.heating_apply,true,this::prepare);apply.setId(R.id.heating_prepare);
        message=TrialUi.text(this,body,R.string.heating_loading);message.setId(R.id.heating_message);TrialUi.text(this,body,R.string.heating_stop_warning);
        TrialUi.button(this,body,R.string.back,false,this::finish);
        origin=EngineHost.origin();token=EngineHost.token();lease=EngineHost.currentLease();
        try{client=new HeatingTrialConnection(origin,token);}catch(Exception invalid){message.setText(R.string.heating_unavailable);}
    }
    private boolean current(){return foreground&&EngineHost.state()==EngineHost.State.RUNNING&&lease==EngineHost.currentLease()&&origin!=null&&origin.equals(EngineHost.origin());}
    private void update(){
        apply.setEnabled(current()&&!busy&&settings!=null&&"ready".equals(settings.optString("state")));
        refresh.setEnabled(current()&&!busy&&settings!=null&&!settings.isNull("selection"));
    }
    private int reason(String code){
        return switch(code){
            case "source_stale","source_unpublished"->R.string.heating_reason_source_stale;
            case "target_stale","target_unknown"->R.string.heating_reason_target_stale;
            case "unknown_demand"->R.string.heating_reason_unknown;
            case "target_permission"->R.string.heating_reason_permission;
            case "source_offline","target_offline"->R.string.heating_reason_offline;
            case "source_pending"->R.string.heating_reason_pending;
            case "source_error"->R.string.heating_reason_source_error;
            case "identity","coherence"->R.string.heating_reason_identity;
            case "expired"->R.string.heating_expired_trial;
            case "demand_changed","version_changed"->R.string.heating_reason_changed;
            case "cancelled"->R.string.heating_trial_cancelled;
            case "confirmation","human_session"->R.string.heating_reason_human;
            case "preflight"->R.string.heating_reason_preflight;
            case "unconfigured"->R.string.heating_unavailable;
            default->R.string.heating_trial_failure;
        };
    }
    private int detail(String code){return switch(code){
        case "timeout","cycle_timeout"->R.string.heating_detail_timeout;
        case "invalid_response","ambiguous"->R.string.heating_detail_identity;
        case "credentials","authorization"->R.string.heating_detail_credentials;
        case "cancelled"->R.string.heating_trial_cancelled;
        default->R.string.heating_detail_network;
    };}
    private String failure(Exception error){return getString(reason(error instanceof HeatingTrialConnection.Refused?error.getMessage():"transport"));}
    private String value(boolean on){return getString(on?R.string.heating_value_on:R.string.heating_value_off);}
    private void show(JSONObject read){
        settings=read;JSONObject selection=read.optJSONObject("selection");
        if(selection==null){selected.setText(R.string.heating_unavailable);return;}
        String demand=read.optString("demand");
        String text=getString(R.string.heating_selected,selection.optString("source_name"),selection.optString("target_name"),demand.equals("on")?value(true):demand.equals("off")?value(false):getString(R.string.heating_value_unknown));
        if(read.has("actual"))text+="\n"+getString(R.string.heating_real_state)+" : "+value(read.optBoolean("actual"));
        if(read.has("collected_at"))text+="\n"+getString(R.string.heating_fresh_age,Math.max(0,(System.currentTimeMillis()-read.optLong("collected_at"))/1000));
        selected.setText(text);apply.setText(getString(R.string.heating_apply_target,selection.optString("target_name")));
    }
    private void load(){
        if(client==null||!current())return;final long seen=visit;busy=true;update();
        work.execute(()->{try{JSONObject read=client.status();runOnUiThread(()->{if(!current()||seen!=visit)return;show(read);message.setText(interrupted?R.string.heating_return:read.optString("state").equals("already_applied")?R.string.heating_already:read.optString("state").equals("ready")?R.string.heating_ready:reason(read.optString("reason")));interrupted=false;busy=false;update();});}
            catch(Exception e){runOnUiThread(()->{if(!current()||seen!=visit)return;message.setText(failure(e));busy=false;update();});}});
    }
    private JSONObject input()throws JSONException{return new JSONObject().put("revision",settings.getLong("revision")).put("epoch",settings.getLong("epoch"));}
    private void prepare(){
        if(!current()||busy||settings==null)return;busy=true;ticket=null;update();final long seen=visit;
        work.execute(()->{try{JSONObject result=client.prepare(input());runOnUiThread(()->{if(!current()||seen!=visit){revoke();return;}busy=false;ticket=result.optJSONObject("prepared");
            if(ticket==null){message.setText(R.string.heating_already);update();return;}
            TrialLog.ui(this,8,2,1,0);message.setText(R.string.heating_await_confirmation);
            confirmation=new AlertDialog.Builder(this).setTitle(R.string.heating_await_confirmation).setMessage(selected.getText()+"\n\n"+getString(R.string.heating_confirm_once)).setNegativeButton(android.R.string.cancel,(d,w)->{revoke();message.setText(R.string.heating_trial_cancelled);update();}).setPositiveButton(R.string.heating_apply,(d,w)->execute()).create();
            confirmation.setOnCancelListener(d->{revoke();message.setText(R.string.heating_trial_cancelled);update();});confirmation.show();confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setId(R.id.heating_apply);update();});}
            catch(Exception e){runOnUiThread(()->{if(!current()||seen!=visit)return;message.setText(failure(e));TrialLog.ui(this,8,5,4,0);busy=false;update();});}});
    }
    private void execute(){
        if(!current()||busy||ticket==null)return;
        if(ticket.optLong("expires_at")<=System.currentTimeMillis()){revoke();message.setText(R.string.heating_expired_trial);return;}
        final JSONObject pending=ticket;ticket=null;busy=true;final long seen=visit;
        final String override=pin.getText().toString();pin.setText("");message.setText(R.string.heating_sending);TrialLog.ui(this,8,1,2,0);update();
        work.execute(()->{String outcome;int logState=5;
            try{if(!current()||seen!=visit)throw new IllegalStateException();
                JSONObject request=input().put("fingerprint",pending.getString("fingerprint")).put("confirmed",true);
                client.login(override.isEmpty()?ProtectedKey.open(this).getString("pin"):override);
                if(!current()||seen!=visit)throw new IllegalStateException();
                JSONObject result=client.execute(request);String state=result.optString("state");
                outcome=getString(state.equals("confirmed")?R.string.heating_trial_confirmed:state.equals("already_applied")?R.string.heating_already:state.equals("sent")?R.string.heating_sent:state.equals("cancelled")?R.string.heating_trial_cancelled:reason(result.optString("reason")));
                if(state.equals("confirmed"))logState=2;
                if(!state.equals("confirmed")&&!state.equals("already_applied")&&!result.isNull("technical"))outcome+="\n"+getString(detail(result.optString("technical")));
            }catch(Exception e){outcome=failure(e);}finally{client.logout();}
            TrialLog.ui(this,8,logState,logState==2?3:4,0);final String result=outcome;
            runOnUiThread(()->{if(!current()||seen!=visit)return;message.setText(result);busy=false;update();});});
    }
    private void refresh(){
        if(!current()||busy||settings==null)return;busy=true;update();final long seen=visit;
        work.execute(()->{try{JSONObject result=client.refresh(input());String state=result.optString("status");
            String text=state.equals("queued")?getString(R.string.heating_refresh_queued):state.equals("busy")?getString(R.string.heating_reason_pending):state.equals("limited")?getString(R.string.heating_refresh_limited,Math.max(1,(result.optLong("retry_at")-System.currentTimeMillis()+999)/1000)):getString(R.string.heating_unavailable);
            JSONObject observed=client.status();
            runOnUiThread(()->{if(!current()||seen!=visit)return;show(observed);message.setText(text);busy=false;update();});
            // Observe completion only; this never asks for another cloud cycle.
            if(state.equals("queued")||state.equals("busy")){long old=settings.optLong("collected_at");for(int i=0;i<6;i++){Thread.sleep(1000);if(!current()||seen!=visit)return;JSONObject read=client.status();if(read.optLong("collected_at")>old||"refused".equals(read.optString("state"))&&!"source_pending".equals(read.optString("reason"))){runOnUiThread(()->{if(!current()||seen!=visit)return;show(read);message.setText(read.optString("state").equals("ready")?R.string.heating_ready:read.optString("state").equals("already_applied")?R.string.heating_already:reason(read.optString("reason")));update();});break;}}}
        }catch(Exception e){runOnUiThread(()->{if(!current()||seen!=visit)return;message.setText(failure(e));busy=false;update();});}});
    }
    private void revoke(){if(ticket!=null)TrialLog.ui(this,8,2,5,0);ticket=null;if(origin!=null&&token!=null)new Thread(()->{try{new HeatingTrialConnection(origin,token).cancel();}catch(Exception ignored){}},"moli-trial-revoke").start();}
    @Override public void onStart(){super.onStart();foreground=true;visit++;busy=false;load();update();}
    @Override public void onStop(){foreground=false;visit++;interrupted=ticket!=null||busy;if(confirmation!=null)confirmation.dismiss();revoke();pin.setText("");super.onStop();}
    @Override public void onDestroy(){work.shutdown();super.onDestroy();}
}
