package io.moli.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Local setup, fixed public weather provider; no GPS or device commands. */
public final class HomeSettingsActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    EditText label, latitude, longitude, rooms;
    CheckBox weather;
    TextView message;
    Button save;
    private String configurationStamp;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density); body.setPadding(pad,pad,pad,pad); scroll.addView(body);
        text(body, R.string.home_settings, 23); text(body,R.string.home_settings_help,16);
        button(body,R.string.stop,() -> startService(new Intent(this,MoliService.class).setAction(MoliService.STOP)));
        weather = new CheckBox(this); weather.setText(R.string.home_weather_enabled); body.addView(weather);
        label = field(body,R.string.home_location,false); latitude = field(body,R.string.home_latitude,true); longitude = field(body,R.string.home_longitude,true);
        text(body,R.string.home_location_help,15);
        rooms = field(body,R.string.home_rooms,false); rooms.setSingleLine(false); rooms.setMinLines(3);
        text(body,R.string.home_rooms_help,15);
        message = new TextView(this); body.addView(message);
        save = button(body,R.string.home_save,this::save); save.setEnabled(false);
        button(body,R.string.start,() -> {
            if (!ProtectedKey.exists(this)) { message.setText(R.string.integration_initialize); return; }
            getSharedPreferences("host",MODE_PRIVATE).edit().putBoolean("wanted",true).apply();
            startForegroundService(new Intent(this,MoliService.class));
        });
        button(body,R.string.home_dashboard,this::finish);
        setContentView(scroll);
        io.execute(() -> {
            try {
                ProfileStore.synchronize(this);
                String expected=ProfileStore.stamp(this);
                JSONObject selected=ProfileStore.state(this), active=selected.optJSONObject("weather"), retained=selected.optJSONObject("retained");
                JSONObject w=active!=null?active:retained==null?null:retained.optJSONObject("weather"), location=w==null?null:w.optJSONObject("location");
                JSONArray rs=ProfileStore.home(this).optJSONArray("rooms"); StringBuilder names=new StringBuilder();
                if(rs!=null) for(int i=0;i<rs.length();i++) names.append(rs.getJSONObject(i).getString("name")).append('\n');
                String roomNames=names.toString(), loadedStamp=expected; ProfileStore.checkStamp(this,expected);
                boolean editable=EngineHost.state()==EngineHost.State.STOPPED;
                runOnUiThread(() -> { if(isDestroyed()) return; weather.setChecked(active!=null);
                    if(location!=null) { label.setText(location.optString("label")); latitude.setText(location.optString("latitude")); longitude.setText(location.optString("longitude")); }
                    else if(w!=null) { label.setText("Paris"); latitude.setText("48.8566"); longitude.setText("2.3522"); }
                    rooms.setText(roomNames); configurationStamp=loadedStamp; save.setEnabled(editable);
                    for(android.view.View v:new android.view.View[]{weather,label,latitude,longitude,rooms})v.setEnabled(editable);
                    if(!editable)message.setText(R.string.configuration_reopen);
                });
            } catch(Exception failure) { runOnUiThread(() -> { message.setText(R.string.configuration_blocked); save.setEnabled(false); }); }
        });
    }
    private void save() {
        if(EngineHost.state()!=EngineHost.State.STOPPED) { message.setText(R.string.integration_stop_first); return; }
        final boolean enabled=weather.isChecked(); final String name=label.getText().toString().trim(), names=rooms.getText().toString();
        final double lat,lon;
        try {
            lat=enabled?Double.parseDouble(latitude.getText().toString().trim().replace(',','.')):0;
            lon=enabled?Double.parseDouble(longitude.getText().toString().trim().replace(',','.')):0;
            if(enabled && (name.isEmpty() || name.length()>80 || !Double.isFinite(lat) || Math.abs(lat)>90 || !Double.isFinite(lon) || Math.abs(lon)>180)) throw new IllegalArgumentException();
            java.util.Set<String> unique=new java.util.HashSet<>();
            for(String line:names.split("\\n")) { String n=line.trim(); if(!n.isEmpty()) { if(n.length()>60) throw new IllegalArgumentException(); unique.add(n); } }
            if(unique.size()>32) throw new IllegalArgumentException();
        } catch(Exception invalid) { message.setText(R.string.home_invalid); return; }
        try {
            Future<Void> job=EngineHost.maintain(() -> {
                ProfileStore.checkStamp(this,configurationStamp);
                ProfileStore.homeSettings(this,names,enabled,name,lat,lon);
                configurationStamp=ProfileStore.stamp(this);
                return null;
            }); save.setEnabled(false);
            io.execute(() -> { boolean ok; int error=R.string.home_failed; try {job.get();ok=true;} catch(Exception failure){ok=false;if(ProfileStore.conflict(failure))error=R.string.configuration_blocked;} final boolean result=ok; final int errorCode=error;
                runOnUiThread(() -> { if(!isDestroyed()) {message.setText(result?R.string.home_saved:errorCode);save.setEnabled(true);} });
            });
        } catch(IllegalStateException busy) { message.setText(R.string.integration_stop_first); }
    }
    private EditText field(LinearLayout body,int title,boolean numeric) {
        text(body,title,16); EditText e=new EditText(this); e.setSingleLine(true); e.setHint(title);
        if(numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        body.addView(e); return e;
    }
    private void text(LinearLayout body,int title,int size) {TextView t=new TextView(this);t.setText(title);t.setTextSize(size);body.addView(t);}
    private Button button(LinearLayout body,int title,Runnable action) {Button b=new Button(this);b.setText(title);b.setOnClickListener(v->action.run());body.addView(b);return b;}
    @Override public void onDestroy() {io.shutdown();super.onDestroy();}
}
