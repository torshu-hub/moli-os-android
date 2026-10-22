package io.moli.android;

import android.app.Activity;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Bounded multi-device editor. Passwords are never saved by Android view state. */
final class MerossForm {
    private final Activity activity;
    private final LinearLayout rows;
    private final Runnable changed;
    final ArrayList<Row> entries = new ArrayList<>();
    final Button add;
    private boolean loading, enabled;
    MerossForm(Activity a, LinearLayout body, Runnable changed) {
        activity=a; this.changed=changed;
        text(body,R.string.meross_title); text(body,R.string.meross_help);
        rows=new LinearLayout(a); rows.setOrientation(LinearLayout.VERTICAL); body.addView(rows);
        add=new Button(a); add.setText(R.string.meross_add); body.addView(add);
        add.setOnClickListener(v -> { if(entries.size()<8) { append(new JSONObject()); changed.run(); setEnabled(enabled); } });
    }
    void load(JSONArray selected) throws Exception {
        loading=true; entries.clear(); rows.removeAllViews();
        if(selected!=null) for(int i=0;i<selected.length();i++) append(selected.getJSONObject(i));
        loading=false;
    }
    private void append(JSONObject input) {
        Row row=new Row(input); entries.add(row); rows.addView(row.body);
    }
    boolean suggest(String host) {
        if(entries.size()>=8) return false;
        for(Row row:entries) if(row.host.getText().toString().trim().equals(host)) return false;
        JSONObject input=new JSONObject();
        try { input.put("host",host).put("enabled",false); } catch(JSONException impossible) { return false; }
        append(input); edited(); setEnabled(enabled); return true;
    }
    JSONArray settings() throws Exception {
        JSONArray result=new JSONArray();
        for(Row r:entries) { JSONObject entry=new JSONObject().put("id",r.id).put("name",r.name.getText().toString().trim())
                .put("host",r.host.getText().toString().trim()).put("port",Integer.parseInt(r.port.getText().toString().trim())).put("enabled",r.read.isChecked()); if(!r.uuid.isEmpty())entry.put("uuid",r.uuid); result.put(entry); }
        return result;
    }
    JSONArray keys() throws Exception {
        JSONArray result=new JSONArray();
        for(Row r:entries) result.put(new JSONObject().put("id",r.id).put("key",r.key.getText().toString().trim()).put("required",r.read.isChecked()));
        return result;
    }
    void clearKeys() { boolean before=loading; loading=true; for(Row r:entries) r.key.setText(""); loading=before; }
    void setEnabled(boolean value) {
        enabled=value; add.setEnabled(value && entries.size()<8);
        for(Row r:entries) for(View v:new View[]{r.name,r.host,r.port,r.key,r.read,r.remove}) v.setEnabled(value);
    }
    private void edited() { if(!loading) changed.run(); }
    private void text(LinearLayout body,int id) { TextView t=new TextView(activity); t.setText(id); body.addView(t); }
    private EditText field(LinearLayout body,int label,boolean password) {
        text(body,label); EditText f=new EditText(activity); f.setSingleLine(true); body.addView(f);
        f.setFilters(new InputFilter[]{new InputFilter.LengthFilter(password?32:160)});
        if(password) {
            f.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
            f.setSaveEnabled(false); f.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            f.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            // Keep explicit Paste available; masked password fields already
            // suppress copying the secret. The app never writes a key to clipboard.
            f.setLongClickable(true);
        }
        return f;
    }
    final class Row {
        final String id,uuid;
        final LinearLayout body=new LinearLayout(activity);
        final CheckBox read=new CheckBox(activity);
        final EditText name,host,port,key;
        final Button remove=new Button(activity);
        Row(JSONObject input) {
            id=input.optString("id",UUID.randomUUID().toString().replace("-","")); uuid=input.optString("uuid","");
            body.setOrientation(LinearLayout.VERTICAL); body.setPadding(0,16,0,16);
            if(!uuid.isEmpty()) {TextView bound=new TextView(activity);bound.setText(activity.getString(R.string.account_uuid_bound,uuid));body.addView(bound);}
            read.setText(R.string.meross_read); read.setChecked(input.optBoolean("enabled")); body.addView(read);
            name=field(body,R.string.meross_name,false); name.setText(input.optString("name",""));
            host=field(body,R.string.equipment_host,false); host.setText(input.optString("host",""));
            port=field(body,R.string.equipment_port,false); port.setInputType(InputType.TYPE_CLASS_NUMBER); port.setText(input.optString("port","80"));
            key=field(body,R.string.meross_key,true); key.setHint(R.string.meross_key_hint);
            remove.setText(R.string.meross_remove); body.addView(remove);
            TextWatcher watcher=new TextWatcher() {
                public void beforeTextChanged(CharSequence s,int a,int c,int after) {}
                public void onTextChanged(CharSequence s,int a,int b,int c) { edited(); }
                public void afterTextChanged(Editable e) {}
            };
            for(EditText f:new EditText[]{name,host,port,key}) f.addTextChangedListener(watcher);
            read.setOnCheckedChangeListener((v,c) -> edited());
            remove.setOnClickListener(v -> { key.setText(""); entries.remove(this); rows.removeView(body); edited(); setEnabled(enabled); });
        }
    }
}
