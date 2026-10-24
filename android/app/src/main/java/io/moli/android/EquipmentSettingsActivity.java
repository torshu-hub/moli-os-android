package io.moli.android;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Configures only user-approved read operations; discovery suggestions never activate a driver. */
public final class EquipmentSettingsActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ready, busy, reopenAfterStop;
    private int revision, savedRevision;
    CheckBox printer, gateway;
    EditText host, port, path, location;
    TextView message;
    Button save, start, test;
    private TextView reading;
    private LinearLayout manual,printerGroup,gatewayGroup,merossGroup,chooser;
    private String mode="";
    private String storedConfig="", configurationStamp;
    private TextView stateText;
    MerossForm meross;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            EngineHost.State state = EngineHost.state();
            boolean stopped = state == EngineHost.State.STOPPED;
            if(stopped && reopenAfterStop && !busy){reopenAfterStop=false;recreate();return;}
            save.setEnabled(ready && !busy && stopped);
            start.setEnabled(ready && !busy && stopped && revision == savedRevision);
            for (android.view.View field : new android.view.View[]{printer,gateway,host,port,path,location}) field.setEnabled(ready && !busy && stopped);
            meross.setEnabled(ready && !busy && stopped);
            if(state!=EngineHost.State.RUNNING)reading.setText(R.string.equipment_read_inactive);
            stateText.setText(stopped ? R.string.equipment_stopped : R.string.equipment_stop_first);
            test.setEnabled(ready && !busy && (state==EngineHost.State.RUNNING || stopped && revision==savedRevision));
            if(state==EngineHost.State.RUNNING)EquipmentMonitor.poll(EquipmentSettingsActivity.this,text->runOnUiThread(()->{if(!isDestroyed())reading.setText(text);}),false);
            handler.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout body=TrialUi.page(this);
        TrialUi.title(this,body,R.string.equipment_title);
        chooser=TrialUi.card(this,body);
        button(chooser,R.string.equipment_add_printer,()->focus("ipp"));
        button(chooser,R.string.equipment_add_gateway,()->focus("igd"));
        button(chooser,R.string.account_title,()->startActivity(new Intent(this,MerossAccountActivity.class)));
        stateText = text(body, R.string.equipment_stop_first);
        button(body, R.string.stop, () -> startService(new Intent(this, MoliService.class).setAction(MoliService.STOP)));
        manual=new LinearLayout(this);manual.setOrientation(LinearLayout.VERTICAL);body.addView(manual);manual.setVisibility(android.view.View.GONE);
        button(chooser,R.string.equipment_manual,()->new android.app.AlertDialog.Builder(this).setTitle(R.string.advanced_tools).setItems(new String[]{getString(R.string.meross_title),getString(R.string.integration_title)},(d,w)->{if(w==0)focus("meross");else startActivity(new Intent(this,IntegrationsActivity.class));}).show());
        printerGroup=group(manual);gatewayGroup=group(manual);merossGroup=group(manual);
        printer = new CheckBox(this); printer.setText(R.string.equipment_printer); printerGroup.addView(printer);
        host = field(printerGroup, R.string.equipment_host, false);
        port = field(printerGroup, R.string.equipment_port, true); port.setText("631");
        path = field(printerGroup, R.string.equipment_path, false); path.setText("/ipp/print");
        text(printerGroup, R.string.equipment_printer_help);
        gateway = new CheckBox(this); gateway.setText(R.string.equipment_gateway); gatewayGroup.addView(gateway);
        location = field(gatewayGroup, R.string.equipment_location, false);
        text(gatewayGroup, R.string.equipment_gateway_help);
        meross = new MerossForm(this,merossGroup,this::edited);
        message = text(body, R.string.equipment_consent);
        save = button(body, R.string.equipment_save, this::save); save.setEnabled(false);
        start = button(body, R.string.equipment_start, () -> {
            if (EngineHost.state() != EngineHost.State.STOPPED || busy || !ready || revision != savedRevision) return;
            getSharedPreferences("host", MODE_PRIVATE).edit().putBoolean("wanted",true).apply();
            startForegroundService(new Intent(this, MoliService.class));
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        }); start.setEnabled(false);
        reading=text(body,R.string.equipment_no_reads);
        test=button(body,R.string.equipment_test,()->{
            if(EngineHost.state()==EngineHost.State.STOPPED && ready && !busy && revision==savedRevision){
                getSharedPreferences("host",MODE_PRIVATE).edit().putBoolean("wanted",true).apply();startForegroundService(new Intent(this,MoliService.class));reading.setText(R.string.equipment_read_pending);
            }else if(EngineHost.state()==EngineHost.State.RUNNING)EquipmentMonitor.poll(this,value->runOnUiThread(()->{if(!isDestroyed())reading.setText(value);}),true);
        });
        button(body, R.string.home_dashboard, this::finish);

        android.text.TextWatcher changed = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int a,int before,int count) { edited(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        };
        for (EditText field : new EditText[]{host,port,path,location}) field.addTextChangedListener(changed);
        printer.setOnCheckedChangeListener((button,checked) -> edited());
        gateway.setOnCheckedChangeListener((button,checked) -> edited());
        if(saved!=null && saved.getString("mode")!=null && !saved.getString("mode").isEmpty())focus(saved.getString("mode"));
        io.execute(() -> {
            try {
                ProfileStore.synchronize(this);
                String expected=ProfileStore.stamp(this);
                JSONObject selected = EquipmentStore.read(this);
                storedConfig=selected.toString(); ProfileStore.checkStamp(this,expected); configurationStamp=expected;
                reopenAfterStop=EngineHost.state()!=EngineHost.State.STOPPED;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    JSONObject p = selected.optJSONObject("printer"), g = selected.optJSONObject("gateway");
                    printer.setChecked(p != null && p.optBoolean("enabled",true)); gateway.setChecked(g != null && g.optBoolean("enabled",true));
                    if (p != null) { host.setText(p.optString("host")); port.setText(p.optString("port")); path.setText(p.optString("path")); }
                    if (g != null) location.setText(g.optString("location"));
                    try { meross.load(selected.optJSONArray("meross")); ready = true; applySuggestion(); }
                    catch (Exception invalid) { message.setText(R.string.configuration_blocked); }
                });
            } catch (Exception failure) { runOnUiThread(() -> { if (!isDestroyed()) message.setText(R.string.configuration_blocked); }); }
        });
    }
    private void applySuggestion() {
        Intent intent=getIntent(); String kind=intent.getStringExtra("discovery_kind"), ip=intent.getStringExtra("discovery_ip");
        if(kind==null || !NetworkEvidence.privateIp(NetworkEvidence.ipv4(ip))) return;
        if(kind.equals("meross")) {
            if(!meross.suggest(ip)) { message.setText(R.string.network_duplicate); return; }
        } else if(kind.equals("ipp")) {
            int number=intent.getIntExtra("discovery_port",0); if(number<1 || number>65535) return;
            printer.setChecked(false); host.setText(ip); port.setText(String.valueOf(number));
            path.setText(NetworkEvidence.ippPath(intent.getStringExtra("discovery_path")));
        } else if(kind.equals("igd")) {
            String url=intent.getStringExtra("discovery_location");
            try {
                JSONObject checked=new JSONObject().put("gateway",new JSONObject().put("location",url));
                ProfileStore.nativeResult(checked.toString(),3);
                if(!new java.net.URI(url).getHost().equals(ip)) return;
                gateway.setChecked(false); location.setText(url);
            } catch(Exception invalid) { return; }
        } else return;
        focus(kind);edited();message.setText(kind.equals("ipp") && !intent.getBooleanExtra("ipp_confirmed",false)?R.string.ipp_unrecognized:R.string.network_suggestion);
        intent.removeExtra("discovery_kind"); // Rotation must not append the same suggestion twice.
    }
    private void save() {
        if (EngineHost.state() != EngineHost.State.STOPPED || busy || !ready) return;
        final JSONObject selected = new JSONObject();
        final int savingRevision = revision;
        try {
            if (printer.isChecked()) selected.put("printer",new JSONObject()
                    .put("host",host.getText().toString().trim()).put("port",Integer.parseInt(port.getText().toString().trim()))
                    .put("path",path.getText().toString().trim()));
            if (gateway.isChecked()) selected.put("gateway",new JSONObject().put("location",location.getText().toString().trim()));
            selected.put("meross",meross.settings());
            ProfileStore.nativeResult(selected.toString(),3); // Pure Rust validation, no network access.
        } catch (Exception invalid) { message.setText(R.string.equipment_invalid);TrialLog.action(this,TrialJournal.Event.EQUIPMENT_SAVED,TrialJournal.Source.EQUIPEMENTS,TrialJournal.Outcome.REFUSE,TrialJournal.Error.SAISIE,0,0,null); return; }
        try {
            final org.json.JSONArray keys = meross.keys();
            Future<Void> job = EngineHost.maintain(() -> { ProfileStore.checkStamp(this,configurationStamp); EquipmentStore.save(this,selected,keys); configurationStamp=ProfileStore.stamp(this); return null; });
            busy = true; save.setEnabled(false); start.setEnabled(false);
            meross.setEnabled(false);
            for (android.view.View field : new android.view.View[]{printer,gateway,host,port,path,location}) field.setEnabled(false);
            io.execute(() -> {
                boolean ok; int error=R.string.meross_save_failed;
                try { job.get(); ok = true; } catch (Exception failure) { ok = false; if(ProfileStore.conflict(failure))error=R.string.configuration_blocked; }
                boolean result = ok; final int errorCode=error;
                int kind=mode.equals("ipp")?1:mode.equals("igd")?2:mode.equals("meross")?3:0;
                TrialLog.action(this,TrialJournal.Event.EQUIPMENT_SAVED,TrialJournal.Source.EQUIPEMENTS,result?TrialJournal.Outcome.ENREGISTRE:TrialJournal.Outcome.REFUSE,result?TrialJournal.Error.AUCUNE:TrialJournal.Error.COFFRE,kind,kind==1?(selected.has("printer")?1:0):kind==2?(selected.has("gateway")?1:0):0,kind==1?"android-printer":kind==2?"android-gateway":null);
                runOnUiThread(() -> { if (!isDestroyed()) { busy = false; if (result) { savedRevision = savingRevision;storedConfig=selected.toString(); meross.clearKeys(); } message.setText(result ? R.string.equipment_saved : errorCode); } });
            });
        } catch (Exception changed) { message.setText(R.string.equipment_stop_first); }
    }
    private void edited() {
        if (ready) { revision++; start.setEnabled(false); message.setText(R.string.equipment_unsaved); }
    }
    private LinearLayout group(LinearLayout parent){return TrialUi.card(this,parent);}
    void focus(String kind){mode=kind;chooser.setVisibility(android.view.View.GONE);manual.setVisibility(android.view.View.VISIBLE);printerGroup.setVisibility(kind.equals("ipp")?android.view.View.VISIBLE:android.view.View.GONE);gatewayGroup.setVisibility(kind.equals("igd")?android.view.View.VISIBLE:android.view.View.GONE);merossGroup.setVisibility(kind.equals("meross")?android.view.View.VISIBLE:android.view.View.GONE);message.setText(kind.equals("ipp")?R.string.ipp_unrecognized:kind.equals("igd")?R.string.gateway_setup_help:R.string.meross_help);}
    private TextView text(LinearLayout body,int id) { return TrialUi.text(this,body,id); }
    private EditText field(LinearLayout body,int id,boolean numeric) {
        text(body,id); EditText e = new EditText(this); e.setHint(id); e.setSingleLine(true);
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER); body.addView(e); return e;
    }
    private Button button(LinearLayout body,int id,Runnable action) { return TrialUi.button(this,body,id,id==R.string.equipment_save,action); }
    @Override public void onStart() { super.onStart(); handler.post(refresh);
        if(ready && !busy){ready=false;io.execute(()->{try{
            ProfileStore.synchronize(this);
            String expected=ProfileStore.stamp(this);
            JSONObject selected=EquipmentStore.read(this); ProfileStore.checkStamp(this,expected); configurationStamp=expected; reopenAfterStop=EngineHost.state()!=EngineHost.State.STOPPED;
            runOnUiThread(()->{if(isDestroyed() || busy)return;try{
                if(selected.toString().equals(storedConfig)){ready=true;return;}
                JSONObject p=selected.optJSONObject("printer"),g=selected.optJSONObject("gateway");
                printer.setChecked(p!=null && p.optBoolean("enabled",true));gateway.setChecked(g!=null && g.optBoolean("enabled",true));if(p!=null){host.setText(p.optString("host"));port.setText(p.optString("port"));path.setText(p.optString("path"));}if(g!=null)location.setText(g.optString("location"));
                meross.load(selected.optJSONArray("meross"));storedConfig=selected.toString();revision=savedRevision=0;ready=true;message.setText(R.string.equipment_reloaded);
            }catch(Exception invalid){message.setText(R.string.configuration_blocked);}});
        }catch(Exception invalid){runOnUiThread(()->{if(!isDestroyed())message.setText(R.string.configuration_blocked);});}});}

    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("mode",mode);}
    @Override public void onStop() { handler.removeCallbacks(refresh); meross.clearKeys(); super.onStop(); }
    @Override public void onDestroy() { handler.removeCallbacks(refresh); io.shutdown(); super.onDestroy(); }
}
