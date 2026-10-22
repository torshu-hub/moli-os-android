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
    private boolean ready, busy;
    private int revision, savedRevision;
    CheckBox printer, gateway;
    EditText host, port, path, location;
    TextView message;
    Button save, start;
    private TextView stateText;
    MerossForm meross;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            EngineHost.State state = EngineHost.state();
            boolean stopped = state == EngineHost.State.STOPPED;
            save.setEnabled(ready && !busy && stopped);
            start.setEnabled(ready && !busy && stopped && revision == savedRevision);
            for (android.view.View field : new android.view.View[]{printer,gateway,host,port,path,location}) field.setEnabled(ready && !busy && stopped);
            meross.setEnabled(ready && !busy && stopped);
            stateText.setText(stopped ? R.string.equipment_stopped : R.string.equipment_stop_first);
            handler.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density); body.setPadding(pad,pad,pad,pad);
        scroll.addView(body);
        text(body, R.string.equipment_title);
        text(body, R.string.equipment_help);
        button(body, R.string.network_title, () -> startActivity(new Intent(this,LocalNetworkActivity.class)));
        stateText = text(body, R.string.equipment_stop_first);
        button(body, R.string.stop, () -> startService(new Intent(this, MoliService.class).setAction(MoliService.STOP)));
        printer = new CheckBox(this); printer.setText(R.string.equipment_printer); body.addView(printer);
        host = field(body, R.string.equipment_host, false);
        port = field(body, R.string.equipment_port, true); port.setText("631");
        path = field(body, R.string.equipment_path, false); path.setText("/ipp/print");
        text(body, R.string.equipment_printer_help);
        gateway = new CheckBox(this); gateway.setText(R.string.equipment_gateway); body.addView(gateway);
        location = field(body, R.string.equipment_location, false);
        text(body, R.string.equipment_gateway_help);
        meross = new MerossForm(this,body,this::edited);
        message = text(body, R.string.equipment_consent);
        save = button(body, R.string.equipment_save, this::save); save.setEnabled(false);
        start = button(body, R.string.equipment_start, () -> {
            if (EngineHost.state() != EngineHost.State.STOPPED || busy || !ready || revision != savedRevision) return;
            getSharedPreferences("host", MODE_PRIVATE).edit().putBoolean("wanted",true).apply();
            startForegroundService(new Intent(this, MoliService.class));
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        }); start.setEnabled(false);
        button(body, R.string.home_dashboard, this::finish);
        setContentView(scroll);
        android.text.TextWatcher changed = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int a,int before,int count) { edited(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        };
        for (EditText field : new EditText[]{host,port,path,location}) field.addTextChangedListener(changed);
        printer.setOnCheckedChangeListener((button,checked) -> edited());
        gateway.setOnCheckedChangeListener((button,checked) -> edited());
        io.execute(() -> {
            try {
                JSONObject selected = EquipmentStore.read(this);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    JSONObject p = selected.optJSONObject("printer"), g = selected.optJSONObject("gateway");
                    printer.setChecked(p != null); gateway.setChecked(g != null);
                    if (p != null) { host.setText(p.optString("host")); port.setText(p.optString("port")); path.setText(p.optString("path")); }
                    if (g != null) location.setText(g.optString("location"));
                    try { meross.load(selected.optJSONArray("meross")); ready = true; applySuggestion(); }
                    catch (Exception invalid) { message.setText(R.string.equipment_initialize); }
                });
            } catch (Exception failure) { runOnUiThread(() -> { if (!isDestroyed()) message.setText(R.string.equipment_initialize); }); }
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
        edited(); message.setText(R.string.network_suggestion);
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
        } catch (Exception invalid) { message.setText(R.string.equipment_invalid); return; }
        try {
            final org.json.JSONArray keys = meross.keys();
            Future<Void> job = EngineHost.maintain(() -> { EquipmentStore.save(this,selected,keys); return null; });
            busy = true; save.setEnabled(false); start.setEnabled(false);
            meross.setEnabled(false);
            for (android.view.View field : new android.view.View[]{printer,gateway,host,port,path,location}) field.setEnabled(false);
            io.execute(() -> {
                boolean ok;
                try { job.get(); ok = true; } catch (Exception failure) { ok = false; }
                boolean result = ok;
                runOnUiThread(() -> { if (!isDestroyed()) { busy = false; if (result) { savedRevision = savingRevision; meross.clearKeys(); } message.setText(result ? R.string.equipment_saved : R.string.meross_save_failed); } });
            });
        } catch (Exception changed) { message.setText(R.string.equipment_stop_first); }
    }
    private void edited() {
        if (ready) { revision++; start.setEnabled(false); message.setText(R.string.equipment_unsaved); }
    }
    private TextView text(LinearLayout body,int id) { TextView t = new TextView(this); t.setText(id); t.setTextSize(16); body.addView(t); return t; }
    private EditText field(LinearLayout body,int id,boolean numeric) {
        text(body,id); EditText e = new EditText(this); e.setHint(id); e.setSingleLine(true);
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER); body.addView(e); return e;
    }
    private Button button(LinearLayout body,int id,Runnable action) { Button b = new Button(this); b.setText(id); b.setOnClickListener(v -> action.run()); body.addView(b); return b; }
    @Override public void onStart() { super.onStart(); handler.post(refresh); }
    @Override public void onStop() { handler.removeCallbacks(refresh); meross.clearKeys(); super.onStop(); }
    @Override public void onDestroy() { handler.removeCallbacks(refresh); io.shutdown(); super.onDestroy(); }
}
