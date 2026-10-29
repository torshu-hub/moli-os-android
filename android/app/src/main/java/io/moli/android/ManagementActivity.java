package io.moli.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native presentation only. Catalogue, configuration and lifecycle remain in J1. */
public final class ManagementActivity extends Activity {
    private enum Page { HOME, INTEGRATIONS, INSTANCES, PARAMETERS, JOURNAL }
    // Transient presentation state, retained across rotation; never a driver configuration store.
    private static final class Screen {
        Page page = Page.HOME;
        JSONObject catalogue;
        String snapshot, id = "", error;
        final Map<String,String> draft = new LinkedHashMap<>();
        boolean existing, saved;
        Future<JSONObject> loading;
        Future<String> saving;
    }
    private Screen screen;
    private LinearLayout root, navigation, body, content, footer;
    private ScrollView scroll;
    private TextView status, title, feedback;
    private Button primary, restart, save, back;
    private EditText instance;
    private final Map<String,EditText> fields = new LinkedHashMap<>();
    private final Map<Page,Button> destinations = new EnumMap<>(Page.class);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean landscape, keyboard, commandPending;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            complete();
            updateControls();
            handler.postDelayed(this,250);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Object retained = getLastNonConfigurationInstance();
        screen = retained instanceof Screen ? (Screen)retained : new Screen();
        landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        build();
        render();
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private TextView text(LinearLayout parent, CharSequence value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(29,43,54));
        view.setPadding(0,dp(8),0,dp(8)); parent.addView(view);
        return view;
    }
    private Button button(LinearLayout parent, int label, Runnable action) {
        Button view = HostUi.button(this,parent,label,action);
        view.setAllCaps(false); view.setMinHeight(dp(48));
        return view;
    }
    private void build() {
        root = new LinearLayout(this);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247,249,250));
        setContentView(root);
        navigation = column(); navigation.setPadding(dp(12),dp(8),dp(12),dp(8));
        navigation.setBackgroundColor(Color.rgb(231,239,242));
        root.addView(navigation,new LinearLayout.LayoutParams(landscape ? dp(208) : -1,landscape ? -1 : -2));
        if(landscape) text(navigation,getString(R.string.management),20);
        LinearLayout engine = landscape ? column() : new LinearLayout(this);
        engine.setGravity(Gravity.CENTER_VERTICAL); navigation.addView(engine);
        status = text(engine,"",16); status.setId(R.id.engine_status);
        if(!landscape) status.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        primary = button(engine,R.string.start,this::command); primary.setId(R.id.engine_action);
        restart = button(engine,R.string.restart,()->sendCommand(MoliService.RESTART));
        restart.setVisibility(landscape ? View.VISIBLE : View.GONE);
        LinearLayout links = landscape ? column() : new LinearLayout(this);
        navigation.addView(links);
        destination(links,Page.HOME,R.string.overview);
        destination(links,Page.INTEGRATIONS,R.string.integrations);
        destination(links,Page.JOURNAL,R.string.journal);
        if(landscape) {
            button(navigation,R.string.moli_os,this::openMoli);
        }
        body = column(); body.setPadding(dp(16),0,dp(16),dp(8));
        root.addView(body,new LinearLayout.LayoutParams(landscape ? 0 : -1,landscape ? -1 : 0,1));
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(heading);
        back = button(heading,R.string.back,this::goBack); back.setId(R.id.management_back);
        title = text(heading,"",20); title.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        title.setPadding(dp(12),dp(8),0,dp(8)); title.setAccessibilityHeading(true);
        scroll = new ScrollView(this); scroll.setId(R.id.management_scroll);
        scroll.setFillViewport(true);
        body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content = column(); content.setPadding(0,0,0,dp(12)); scroll.addView(content);
        footer = column(); body.addView(footer);
        if(Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));
                keyboard=insets.isVisible(WindowInsets.Type.ime());
                // Leave room for the form and fixed Save action on short landscape windows.
                restart.setVisibility(landscape && !keyboard ? View.VISIBLE : View.GONE);
                ensureFocusVisible(); return insets;
            });
        }
    }
    private void destination(LinearLayout parent,Page page,int label) {
        Button view=button(parent,label,()->navigate(page)); destinations.put(page,view);
        if(!landscape) view.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
    }
    private void openMoli() {
        startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
    private void navigate(Page page) {
        if(screen.saving!=null || screen.loading!=null) return;
        hideKeyboard(); remember();
        screen.page=page;
        if(page==Page.INTEGRATIONS) { screen.error=null; load(); }
        render();
    }
    private void goBack() {
        if(screen.saving!=null || screen.loading!=null) return;
        if(screen.page==Page.PARAMETERS || screen.page==Page.INSTANCES) navigate(Page.INTEGRATIONS);
        else if(screen.page!=Page.HOME) navigate(Page.HOME);
        else finish();
    }
    @Override public void onBackPressed() { goBack(); }
    private void command() {
        EngineHost.State state=EngineHost.state();
        if(state==EngineHost.State.STOPPED || state==EngineHost.State.ERROR) sendCommand(null);
        else if(state==EngineHost.State.RUNNING) sendCommand(MoliService.STOP);
    }
    private void sendCommand(String action) {
        if(commandPending || screen.saving!=null) return;
        commandPending=true; updateControls();
        try { HostUi.command(this,action); }
        catch(RuntimeException failure) { HostUi.error(this,failure); }
        // The service processes the intent on the main queue. Display only EngineHost's state.
        handler.postDelayed(()->{commandPending=false;updateControls();},500);
    }
    private void updateControls() {
        EngineHost.State state=EngineHost.state();
        int label;
        switch(state) {
            case STARTING: label=R.string.starting; break;
            case RUNNING: label=R.string.running; break;
            case STOPPING: label=R.string.stopping; break;
            case ERROR: label=R.string.engine_error; break;
            case MAINTENANCE: label=R.string.maintenance; break;
            default: label=R.string.stopped;
        }
        status.setText(label); status.setContentDescription(getString(R.string.engine_state,getString(label)));
        boolean idle=screen.saving==null && screen.loading==null;
        boolean actionable=state==EngineHost.State.STOPPED || state==EngineHost.State.ERROR || state==EngineHost.State.RUNNING;
        primary.setText(state==EngineHost.State.RUNNING ? R.string.stop : R.string.start);
        primary.setEnabled(idle && actionable && !commandPending);
        restart.setEnabled(idle && state==EngineHost.State.RUNNING && !commandPending);
        if(save!=null) save.setEnabled(idle && (state==EngineHost.State.STOPPED || state==EngineHost.State.RUNNING) && !commandPending);
        back.setEnabled(idle);
        for(Button view:destinations.values()) view.setEnabled(idle);
    }
    private void load() {
        if(!ProtectedKey.exists(this)) { screen.error=getString(R.string.start_first); return; }
        Context app=getApplicationContext();
        screen.loading=EngineHost.inspect(()->{
            String value=new ConfigStore(app).read();
            JSONObject result=ConfigStore.nativeCall(new JSONObject().put("action","form").put("text",value));
            result.put("text",value); return result;
        });
    }
    private void complete() {
        if(screen.loading!=null && screen.loading.isDone()) {
            try { screen.catalogue=screen.loading.get(); screen.snapshot=screen.catalogue.getString("text"); }
            catch(Exception failure) { screen.error=getString(HostUi.errorMessage(failure)); }
            screen.loading=null; render();
        }
        if(screen.saving!=null && screen.saving.isDone()) {
            try {
                screen.snapshot=screen.saving.get(); screen.saved=true; screen.error=null;
                screen.page=Page.INTEGRATIONS; screen.draft.clear(); screen.catalogue=null;
                load();
            } catch(Exception failure) { screen.error=getString(HostUi.errorMessage(failure)); }
            screen.saving=null; render();
        }
    }
    private String entry() throws JSONException {
        JSONObject metadata=screen.catalogue.getJSONObject("manifest").getJSONObject("integration");
        return getString(R.string.catalogue_entry,metadata.getString("name"),metadata.getJSONArray("brands").getString(0));
    }
    private void render() {
        fields.clear(); instance=null; save=null;
        content.removeAllViews(); footer.removeAllViews();
        back.setVisibility(screen.page==Page.HOME ? View.GONE : View.VISIBLE);
        Page selected=(screen.page==Page.PARAMETERS || screen.page==Page.INSTANCES) ? Page.INTEGRATIONS : screen.page;
        for(Map.Entry<Page,Button> item:destinations.entrySet()) {
            boolean active=item.getKey()==selected;
            item.getValue().setSelected(active);
            item.getValue().setTypeface(null,active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            item.getValue().setBackgroundTintList(android.content.res.ColorStateList.valueOf(active ? Color.rgb(198,221,227) : Color.rgb(231,239,242)));
        }
        title.setText(screen.page==Page.HOME ? R.string.management : screen.page==Page.JOURNAL ? R.string.journal : R.string.integrations);
        try {
            if(screen.page==Page.HOME) {
                text(content,getString(R.string.management_intro),18);
                button(content,R.string.integrations,()->navigate(Page.INTEGRATIONS));
                button(content,R.string.export,this::exportJournal);
                button(content,R.string.moli_os,this::openMoli);
            } else if(screen.page==Page.JOURNAL) {
                text(content,getString(R.string.journal_help),16);
                button(content,R.string.export,this::exportJournal);
            } else {
                if(screen.saved) { text(content,getString(R.string.saved),18).setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); }
                if(screen.loading!=null) text(content,getString(R.string.working),16);
                else if(screen.catalogue!=null) {
                    if(screen.page==Page.INTEGRATIONS) {
                        text(content,getString(R.string.integrations_help),16);
                        Button open=new Button(this); open.setAllCaps(false); open.setText(entry()); open.setMinHeight(dp(64));
                        open.setId(R.id.catalogue_entry); content.addView(open);
                        open.setOnClickListener(v->selectIntegration());
                    } else if(screen.page==Page.INSTANCES) {
                        title.setText(entry()); text(content,getString(R.string.select_instance),16);
                        JSONArray instances=screen.catalogue.getJSONArray("instances");
                        for(int i=0;i<instances.length();i++) {
                            JSONObject item=instances.getJSONObject(i);
                            Button choose=new Button(this); choose.setAllCaps(false); choose.setText(item.getString("id")); content.addView(choose);
                            choose.setOnClickListener(v->edit(item));
                        }
                    } else renderForm();
                }
            }
        } catch(JSONException failure) { screen.error=getString(R.string.operation_failed); }
        feedback=text(content,screen.error==null ? "" : screen.error,16); feedback.setId(R.id.form_feedback);
        feedback.setTextColor(Color.rgb(151,37,34));
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(screen.error==null ? View.GONE : View.VISIBLE);
        if(screen.error!=null && screen.page==Page.PARAMETERS) {
            for(EditText field:fields.values()) field.setError(screen.error);
        }
        scroll.scrollTo(0,0); updateControls();
    }
    private void selectIntegration() {
        try {
            screen.saved=false; screen.error=null;
            JSONArray instances=screen.catalogue.getJSONArray("instances");
            if(instances.length()>1) { screen.page=Page.INSTANCES; render(); }
            else edit(instances.length()==1 ? instances.getJSONObject(0) : null);
        } catch(JSONException failure) { HostUi.error(this,failure); }
    }
    private void edit(JSONObject selected) {
        try {
            screen.existing=selected!=null;
            screen.id=selected==null ? screen.catalogue.getJSONObject("manifest").getJSONObject("integration").getString("id") : selected.getString("id");
            screen.draft.clear();
            JSONObject current=selected==null ? null : selected.optJSONObject("vars");
            JSONArray vars=screen.catalogue.getJSONObject("manifest").getJSONObject("needs").getJSONArray("vars");
            for(int i=0;i<vars.length();i++) { String name=vars.getString(i); screen.draft.put(name,current==null ? "" : current.optString(name,"")); }
            screen.page=Page.PARAMETERS; render();
        } catch(JSONException failure) { HostUi.error(this,failure); }
    }
    private void renderForm() throws JSONException {
        title.setText(entry());
        text(content,getString(R.string.parameters),18);
        text(content,getString(R.string.manual_variables),14);
        instance=field(getString(R.string.instance),screen.id,R.id.integration_instance);
        instance.setEnabled(!screen.existing);
        instance.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        JSONArray vars=screen.catalogue.getJSONObject("manifest").getJSONObject("needs").getJSONArray("vars");
        for(int i=0;i<vars.length();i++) {
            String name=vars.getString(i);
            boolean lat="lat".equals(name),lon="lon".equals(name);
            String label=lat ? getString(R.string.latitude) : lon ? getString(R.string.longitude) : name;
            EditText field=field(label,screen.draft.getOrDefault(name,""),lat ? R.id.variable_lat : lon ? R.id.variable_lon : View.generateViewId());
            if(lat || lon) {
                field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
                field.setHint(R.string.coordinate_hint);
            }
            field.setImeOptions((i==vars.length()-1 ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT)|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            fields.put(name,field);
            if(i==vars.length()-1) field.setOnEditorActionListener((v,action,event)->{
                if(action==EditorInfo.IME_ACTION_DONE) { hideKeyboard(); return true; } return false;
            });
        }
        save=button(footer,screen.saving==null ? R.string.save : R.string.saving,this::save);
        save.setId(R.id.save_configuration);
        instance.setEnabled(!screen.existing && screen.saving==null);
        for(EditText field:fields.values()) field.setEnabled(screen.saving==null);
    }
    private EditText field(String label,String value,int id) {
        TextView caption=text(content,label,16); caption.setLabelFor(id);
        EditText edit=new EditText(this); edit.setId(id); edit.setSaveEnabled(false);
        edit.setSingleLine(true); edit.setText(value); edit.setMinHeight(dp(48));
        content.addView(edit,new LinearLayout.LayoutParams(-1,-2));
        edit.setOnFocusChangeListener((v,focused)->{if(focused) ensureFocusVisible();});
        return edit;
    }
    private void ensureFocusVisible() {
        scroll.postDelayed(()->{
            View focus=getCurrentFocus();
            if(focus instanceof EditText) {
                // Include the preceding label, rather than only the edit's cursor rectangle.
                Rect rectangle=new Rect(0,-dp(40),focus.getWidth(),focus.getHeight()+dp(8));
                focus.requestRectangleOnScreen(rectangle,false);
            }
        },120);
    }
    private void remember() {
        if(instance!=null) screen.id=instance.getText().toString();
        for(Map.Entry<String,EditText> item:fields.entrySet()) screen.draft.put(item.getKey(),item.getValue().getText().toString());
    }
    private void hideKeyboard() {
        View focus=getCurrentFocus();
        if(focus!=null) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(),0);
    }
    private void save() {
        if(save==null || !save.isEnabled()) return;
        remember(); hideKeyboard(); screen.error=null;
        Context app=getApplicationContext();
        try {
            JSONObject vars=new JSONObject();
            for(Map.Entry<String,String> item:screen.draft.entrySet()) vars.put(item.getKey(),item.getValue());
            final String before=screen.snapshot,id=screen.id;
            screen.saving=EngineHost.maintain(app,()->{
                try {
                    ConfigStore store=new ConfigStore(app); store.save(before,id,vars);
                    GeneralJournal.record(app,GeneralJournal.Event.CONFIGURATION_SAVED); return store.read();
                } catch(Exception failure) {
                    GeneralJournal.record(app,GeneralJournal.Event.CONFIGURATION_REJECTED); throw failure;
                }
            });
        } catch(Exception failure) { screen.error=getString(HostUi.errorMessage(failure)); }
        render();
    }
    private void exportJournal() {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE,"Moli-OS-Beta2-journal.txt"),73);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=73 || result!=RESULT_OK || data==null || data.getData()==null) return;
        android.net.Uri uri=data.getData(); Context app=getApplicationContext();
        new Thread(()->{
            try {
                if(!"content".equals(uri.getScheme())) throw new java.io.IOException();
                String report=EngineHost.inspect(()->GeneralJournal.export(app)).get(10,TimeUnit.SECONDS);
                try(java.io.OutputStream out=app.getContentResolver().openOutputStream(uri,"wt")) {
                    if(out==null) throw new java.io.IOException();
                    out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                runOnUiThread(()->{if(!isDestroyed()) Toast.makeText(this,R.string.exported,Toast.LENGTH_LONG).show();});
            } catch(Exception failure) { runOnUiThread(()->HostUi.error(this)); }
        },"moli-export").start();
    }
    @Override public Object onRetainNonConfigurationInstance() { remember(); return screen; }
    @Override public void onStart() { super.onStart(); handler.post(refresh); }
    @Override public void onStop() { remember(); handler.removeCallbacks(refresh); super.onStop(); }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
