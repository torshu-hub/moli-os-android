package io.moli.android;

import android.app.*;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.util.concurrent.*;
import org.json.*;

/** Voluntary foreground-only trial. No credentials or permissions are changed. */
public final class HeatingTrialActivity extends Activity {
    static final String[] FIELDS={"vessel_pressure","relay_enclosure","thermostat_input","loss_behavior","supervised"};
    private static final int[] LABELS={R.string.heating_vessel_pressure,R.string.heating_relay_enclosure,R.string.heating_thermostat_input,R.string.heating_loss_behavior,R.string.heating_supervised};
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final CheckBox[] checks=new CheckBox[FIELDS.length];
    private HeatingTrialConnection client;
    private JSONObject settings,ticket;
    private TextView selected,message;
    private EditText pin;
    private Button prepare,apply;
    private volatile boolean foreground;
    private boolean busy;
    private long lease;
    private String origin,token;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout body=TrialUi.page(this);TrialUi.title(this,body,R.string.heating_real_title);
        TrialUi.text(this,body,R.string.heating_autonomous_blocked);TrialUi.text(this,body,R.string.heating_real_help);
        selected=TrialUi.text(this,body,R.string.heating_loading);
        TrialUi.text(this,body,R.string.heating_physical_warning);
        for(int i=0;i<checks.length;i++){checks[i]=new CheckBox(this);checks[i].setSaveEnabled(false);checks[i].setSaveFromParentEnabled(false);checks[i].setText(LABELS[i]);checks[i].setMinHeight(TrialUi.dp(this,52));checks[i].setOnCheckedChangeListener((b,on)->update());body.addView(checks[i]);}
        pin=new EditText(this);pin.setId(R.id.heating_pin);pin.setSaveEnabled(false);pin.setHint(R.string.heating_pin_optional);pin.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);pin.setSingleLine(true);body.addView(pin);
        prepare=TrialUi.button(this,body,R.string.heating_prepare,false,this::prepare);prepare.setId(R.id.heating_prepare);
        apply=TrialUi.button(this,body,R.string.heating_apply,true,this::confirm);apply.setId(R.id.heating_apply);
        TrialUi.button(this,body,R.string.heating_cancel,false,()->{revoke();ticket=null;message.setText(R.string.heating_trial_cancelled);update();});
        message=TrialUi.text(this,body,R.string.heating_loading);TrialUi.text(this,body,R.string.heating_stop_warning);
        TrialUi.button(this,body,R.string.back,false,this::finish);
        origin=EngineHost.origin();token=EngineHost.token();lease=EngineHost.currentLease();
        try{client=new HeatingTrialConnection(origin,token);}catch(Exception invalid){message.setText(R.string.heating_unavailable);update();return;}
        busy=true;update();work.execute(()->{
            try{JSONObject read=client.read();runOnUiThread(()->{if(isDestroyed())return;settings=read;showSelection(null);message.setText(R.string.heating_unavailable);busy=false;update();});}
            catch(Exception unavailable){runOnUiThread(()->{if(isDestroyed())return;message.setText(R.string.heating_unavailable);busy=false;update();});}
        });
    }
    private boolean current(){return foreground&&EngineHost.state()==EngineHost.State.RUNNING&&lease==EngineHost.currentLease()&&origin!=null&&origin.equals(EngineHost.origin());}
    private boolean confirmed(){for(CheckBox check:checks)if(check==null||!check.isChecked())return false;return true;}
    private void update(){
        if(prepare==null||apply==null)return;
        prepare.setEnabled(current()&&!busy&&settings!=null&&!settings.isNull("selection")&&confirmed());
        apply.setEnabled(current()&&!busy&&confirmed()&&ticket!=null&&ticket.optLong("expires_at")>System.currentTimeMillis());
    }
    private void showSelection(String demand){
        JSONObject selection=settings==null?null:settings.optJSONObject("selection");
        if(selection==null){selected.setText(R.string.heating_unavailable);return;}
        selected.setText(getString(R.string.heating_selected,selection.optString("source_name"),selection.optString("target_name"),demand==null?getString(R.string.heating_value_unknown):getString(demand.equals("on")?R.string.heating_value_on:R.string.heating_value_off)));
    }
    private void prepare(){
        if(!current()||busy||!confirmed()||settings==null)return;
        busy=true;ticket=null;update();
        work.execute(()->{
            try{JSONObject input=new JSONObject().put("revision",settings.getLong("revision")).put("epoch",settings.getLong("epoch"));JSONObject result=client.prepare(input);JSONObject pending=result.getJSONObject("prepared");
                runOnUiThread(()->{if(isDestroyed())return;if(!current()){revoke();busy=false;return;}ticket=pending;TrialLog.ui(this,8,2,1,0);showSelection(ticket.optString("demand"));message.setText(getString(R.string.heating_version)+" : "+ticket.optString("fingerprint"));busy=false;update();});
            }catch(Exception refused){runOnUiThread(()->{if(isDestroyed())return;message.setText(R.string.heating_trial_failure);TrialLog.ui(this,8,5,4,0);busy=false;update();});}
        });
    }
    private void confirm(){
        if(!current()||busy||!confirmed()||ticket==null||ticket.optLong("expires_at")<=System.currentTimeMillis()){message.setText(R.string.heating_expired_trial);return;}
        String summary=selected.getText()+"\n"+settings.optJSONObject("selection").optString("target")+"\n\n"+getString(R.string.heating_apply)+"\n"+getString(R.string.heating_stop_warning);
        new AlertDialog.Builder(this).setTitle(R.string.heating_real_title).setMessage(summary).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.heating_apply,(d,w)->execute()).show();
    }
    private void execute(){
        if(!current()||busy||!confirmed()||ticket==null||ticket.optLong("expires_at")<=System.currentTimeMillis())return;
        final JSONObject seen=ticket;ticket=null;busy=true;TrialLog.ui(this,8,1,2,0);update();
        final String override=pin.getText().toString();pin.setText("");
        work.execute(()->{
            int outcome=R.string.heating_trial_failure;
            try{
                if(!current())throw new IllegalStateException();
                JSONObject input=new JSONObject().put("revision",settings.getLong("revision")).put("epoch",settings.getLong("epoch")).put("fingerprint",seen.getString("fingerprint"));
                for(String field:FIELDS)input.put(field,true);
                String credential=override.isEmpty()?ProtectedKey.open(this).getString("pin"):override;
                client.login(credential);
                if(!current())throw new IllegalStateException();
                JSONObject result=client.execute(input);
                if(result.optString("state").equals("confirmed"))outcome=R.string.heating_trial_confirmed;
                else if(result.optString("state").equals("cancelled"))outcome=R.string.heating_trial_cancelled;
            }catch(Exception refused){/* Closed diagnostics only; never log HTTP or credentials. */}
            finally{client.logout();}
            final int state=outcome;
            TrialLog.ui(this,8,state==R.string.heating_trial_confirmed?2:5,state==R.string.heating_trial_confirmed?3:4,0);
            runOnUiThread(()->{if(isDestroyed())return;message.setText(state);busy=false;for(CheckBox c:checks)c.setChecked(false);update();});
        });
    }
    private void revoke(){
        ticket=null;TrialLog.ui(this,8,1,5,0);
        if(origin!=null&&token!=null)new Thread(()->{boolean confirmed=false;try{confirmed=new HeatingTrialConnection(origin,token).cancel();}catch(Exception ignored){}TrialLog.ui(this,8,confirmed?2:7,confirmed?5:4,0);},"moli-trial-revoke").start();
    }
    @Override public void onStart(){super.onStart();foreground=true;update();}
    @Override public void onStop(){foreground=false;revoke();for(CheckBox c:checks)if(c!=null)c.setChecked(false);pin.setText("");super.onStop();}
    @Override public void onDestroy(){work.shutdown();super.onDestroy();}
}
