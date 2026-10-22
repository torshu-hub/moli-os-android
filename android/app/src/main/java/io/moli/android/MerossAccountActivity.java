package io.moli.android;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Experimental real client, with simulation-only validation clearly displayed; never automatic login. */
public final class MerossAccountActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    MerossAccountStore.Backend backend=request -> MerossAccountStore.request(this,request);
    EditText email,password,otp,host,port,name;
    Spinner region,devices;
    CheckBox cloudConsent,readConsent;
    TextView message,inventoryTime;
    Button connect,refresh,forget,associate,pick;
    JSONArray inventory=new JSONArray();
    boolean busy,loaded,connected;
    private String pendingUuid,displayedUuid;
    private final Runnable state=new Runnable() {
        @Override public void run() {
            boolean stopped=EngineHost.state()==EngineHost.State.STOPPED;
            boolean ready=stopped && ProtectedKey.exists(MerossAccountActivity.this) && !busy;
            connect.setEnabled(ready && !connected && cloudConsent.isChecked());
            refresh.setEnabled(ready && connected); forget.setEnabled(ready && connected);
            associate.setEnabled(ready && connected && inventory.length()>0); pick.setEnabled(ready && connected && inventory.length()>0);
            for(View field:new View[]{email,password,otp,region,cloudConsent,host,port,name,readConsent,devices}) field.setEnabled(ready);
            if(!stopped && !busy) message.setText(R.string.account_stop);
            if(!ProtectedKey.exists(MerossAccountActivity.this)) message.setText(R.string.account_initialize);
            if(ready && !loaded) { loaded=true; submit("status",new JSONObject()); }
            handler.postDelayed(this,400);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);int pad=(int)(16*getResources().getDisplayMetrics().density);body.setPadding(pad,pad,pad,pad);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);setContentView(scroll);
        text(body,R.string.account_title);text(body,R.string.account_help);
        button(body,R.string.stop,() -> startService(new Intent(this,MoliService.class).setAction(MoliService.STOP)));
        cloudConsent=new CheckBox(this);cloudConsent.setText(R.string.account_consent);body.addView(cloudConsent);
        region=new Spinner(this); region.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"EU","US","AP","Global"}));body.addView(region);
        email=field(body,R.string.account_email,false);email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password=field(body,R.string.account_password,true);otp=field(body,R.string.account_otp,true);otp.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        connect=button(body,R.string.account_connect,() -> {
            if(!cloudConsent.isChecked()) return;
            try {
                JSONObject input=new JSONObject().put("email",email.getText().toString().trim()).put("password",password.getText().toString())
                    .put("otp",otp.getText().toString().trim()).put("region",new String[]{"eu","us","ap","global"}[region.getSelectedItemPosition()]);
                password.setText("");otp.setText("");submit("signin",input);
            } catch(Exception invalid) { message.setText(R.string.account_input); }
        });
        refresh=button(body,R.string.account_refresh,() -> submit("inventory",new JSONObject()));
        forget=button(body,R.string.account_forget,() -> new AlertDialog.Builder(this).setTitle(R.string.account_forget).setMessage(R.string.account_forget_help)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.account_forget,(d,w) -> { try {submit("forget",new JSONObject().put("revoke",true));}catch(JSONException ignored){} }).show());
        message=text(body,R.string.account_disconnected);inventoryTime=text(body,R.string.account_no_inventory);
        devices=new Spinner(this);body.addView(devices);devices.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> p) {}
            public void onItemSelected(AdapterView<?> p,View view,int position,long id) {
                JSONObject item=inventory.optJSONObject(position);
                if(item!=null && !item.optString("uuid").equals(displayedUuid)) {displayedUuid=item.optString("uuid");name.setText(item.optString("name"));host.setText("");}
            }
        });
        name=field(body,R.string.meross_name,false);host=field(body,R.string.equipment_host,false);port=field(body,R.string.equipment_port,false);port.setInputType(InputType.TYPE_CLASS_NUMBER);port.setText("80");
        pick=button(body,R.string.account_pick,() -> {
            JSONObject selected=selected();if(selected==null)return;pendingUuid=selected.optString("uuid");
            startActivityForResult(new Intent(this,LocalNetworkActivity.class).putExtra("pick_meross_ip",true),41);
        });
        readConsent=new CheckBox(this);readConsent.setText(R.string.account_read_consent);body.addView(readConsent);
        associate=button(body,R.string.account_associate,() -> {
            JSONObject item=selected();if(item==null)return;
            try {submit("associate",new JSONObject().put("uuid",item.getString("uuid")).put("host",host.getText().toString().trim())
                .put("port",Integer.parseInt(port.getText().toString().trim())).put("name",name.getText().toString().trim()).put("enabled",readConsent.isChecked()));}
            catch(Exception invalid){message.setText(R.string.account_input);}
        });
        button(body,R.string.equipment_title,() -> {startActivity(new Intent(this,EquipmentSettingsActivity.class));finish();});
        button(body,R.string.back,this::finish);
    }
    private JSONObject selected(){return inventory.optJSONObject(devices.getSelectedItemPosition());}
    void submit(String action,JSONObject input) {
        if(busy || EngineHost.state()!=EngineHost.State.STOPPED)return;
        try {input.put("action",action);}catch(JSONException ignored){return;}
        busy=true;message.setText(R.string.account_wait);connect.setEnabled(false);refresh.setEnabled(false);forget.setEnabled(false);associate.setEnabled(false);pick.setEnabled(false);
        for(View f:new View[]{email,password,otp,region,devices,host,port,name,cloudConsent,readConsent})f.setEnabled(false);
        io.execute(() -> {
            JSONObject result=null;String code=null;
            try{result=backend.call(input);}catch(Exception failure){
                Throwable cause=failure;while(cause.getCause()!=null && cause.getCause()!=cause)cause=cause.getCause();
                code=cause instanceof ProfileStore.Rejected?cause.getMessage():"account_network";
            }finally{for(String f:new String[]{"password","otp","master","email"})input.remove(f);}
            JSONObject response=result;String error=code;
            runOnUiThread(() -> {
                if(isDestroyed())return;busy=false;
                if(error!=null){if(error.equals("account_expired")){connected=false;inventory=new JSONArray();devices.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new ArrayList<>()));}message.setText(errorText(error));return;}
                String status=response.optString("state");
                if(status.equals("associated")){message.setText(R.string.account_associated);readConsent.setChecked(false);return;}
                connected=status.equals("connected");inventory=response.optJSONArray("items");if(inventory==null)inventory=new JSONArray();
                ArrayList<String> labels=new ArrayList<>();
                for(int i=0;i<inventory.length();i++){JSONObject item=inventory.optJSONObject(i);if(item!=null)labels.add(item.optString("name")+" — "+item.optString("model")+" / "+item.optString("hardware")+"\nUUID : "+item.optString("uuid"));}
                devices.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
                long received=response.optLong("cached_ms");inventoryTime.setText(received>0?getString(R.string.account_inventory_time,DateFormat.getDateTimeInstance().format(new Date(received))):getString(R.string.account_no_inventory));
                int label=status.equals("expired")?R.string.account_expired:connected?(received<=0?R.string.account_no_inventory:inventory.length()==0?R.string.account_empty:R.string.account_connected):R.string.account_disconnected;
                if(action.equals("forget"))label=response.optBoolean("revoked")?R.string.account_forgotten:R.string.account_forgotten_offline;
                if(response.has("warning"))label=errorText(response.optString("warning"));
                message.setText(label);
            });
        });
    }
    static int errorText(String code) {
        return switch(code){
            case "account_auth" -> R.string.account_auth;
            case "account_mfa" -> R.string.account_mfa;
            case "account_expired" -> R.string.account_expired;
            case "account_rate" -> R.string.account_rate;
            case "account_denied" -> R.string.account_denied;
            case "account_identity" -> R.string.account_identity;
            case "account_local" -> R.string.account_local;
            case "account_wifi" -> R.string.account_wifi;
            case "account_region","account_redirect" -> R.string.account_region;
            case "account_input","equipment_ip","equipment_port" -> R.string.account_input;
            case "account_initialize" -> R.string.account_initialize;
            case "meross_duplicate","meross_settings" -> R.string.network_duplicate;
            case "storage" -> R.string.account_storage;
            case "running" -> R.string.account_stop;
            case "account_protocol" -> R.string.account_protocol;
            default -> R.string.account_network;
        };
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        JSONObject selected=selected();
        if(request==41 && result==RESULT_OK && data!=null && selected!=null && selected.optString("uuid").equals(pendingUuid))host.setText(data.getStringExtra("ip"));
        pendingUuid=null;
    }
    private TextView text(LinearLayout body,int label){TextView t=new TextView(this);t.setText(label);t.setTextSize(16);body.addView(t);return t;}
    private Button button(LinearLayout body,int label,Runnable task){Button b=new Button(this);b.setText(label);b.setOnClickListener(v -> task.run());body.addView(b);return b;}
    private EditText field(LinearLayout body,int label,boolean secret){text(body,label);EditText f=new EditText(this);f.setHint(label);f.setSingleLine(true);f.setSaveEnabled(false);f.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);f.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(secret?1024:254)});if(secret)f.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);body.addView(f);return f;}
    @Override public void onStart(){super.onStart();handler.post(state);}
    @Override public void onStop(){handler.removeCallbacks(state);password.setText("");otp.setText("");email.setText("");super.onStop();}
    @Override public void onDestroy(){handler.removeCallbacks(state);io.shutdown();super.onDestroy();}
}
