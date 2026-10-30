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
    private enum Page { HOME, INTEGRATIONS, PARAMETERS, JOURNAL }
    // Transient presentation state, retained across rotation; never a driver configuration store.
    private static final class Screen {
        Page page = Page.HOME;
        JSONObject catalogue, selected;
        String snapshot, id = "", error;
        final Map<String,String> draft = new LinkedHashMap<>();
        boolean existing, saved, advanced, activation;
        int catalogueScroll=-1;
        Future<JSONObject> loading;
        Future<String> saving;
    }
    private Screen screen;
    private LinearLayout root, navigation, body, content, footer, advancedFields;
    private Button moliDestination;
    private LinearLayout engineCard;
    private ImageView engineSymbol;
    private FrameLayout engineBadge;
    private EngineHost.State styledState;
    private ScrollView scroll;
    private TextView status, title, feedback;
    private Button primary, restart, save, back;
    private EditText instance;
    private final Map<String,EditText> fields = new LinkedHashMap<>();
    private final Map<Page,Button> destinations = new EnumMap<>(Page.class);
    private final List<Button> configurationActions=new ArrayList<>(), activationActions=new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean landscape, commandPending;
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
    private int color(int id) { return getColor(id); }
    private LinearLayout column() {
        LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private android.graphics.drawable.Drawable surface(int fill,int stroke,int radius) {
        android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();
        shape.setColor(color(fill)); shape.setCornerRadius(dp(radius));
        if(stroke!=0) shape.setStroke(dp(1),color(stroke));
        return shape;
    }
    private TextView text(LinearLayout parent,CharSequence value,int size) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(color(R.color.management_ink)); view.setPadding(0,dp(6),0,dp(6));
        parent.addView(view); return view;
    }
    private TextView heading(LinearLayout parent,int label,int size) {
        TextView view=text(parent,getString(label),size);
        view.setTypeface(null,android.graphics.Typeface.BOLD); view.setAccessibilityHeading(true); return view;
    }
    private Button button(LinearLayout parent,int label,Runnable action) {
        Button view=new Button(this); view.setText(label); view.setAllCaps(false); view.setTextSize(15);
        view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        view.setPadding(dp(20),dp(10),dp(20),dp(10));
        view.setBackgroundResource(R.drawable.management_button_secondary);
        view.setTextColor(getColorStateList(R.color.management_button_text));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(8);
        parent.addView(view,params); view.setOnClickListener(v->action.run()); return view;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout view=column(); view.setPadding(dp(24),dp(18),dp(24),dp(22));
        view.setBackground(surface(R.color.management_card,R.color.management_border,18));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.bottomMargin=dp(20);
        parent.addView(view,params); return view;
    }
    private void build() {
        root=new LinearLayout(this); root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.management_background)); setContentView(root);
        navigation=new LinearLayout(this); navigation.setId(R.id.management_navigation);
        navigation.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        navigation.setGravity(landscape ? Gravity.TOP : Gravity.CENTER);
        navigation.setPadding(dp(12),dp(12),dp(12),dp(12));
        navigation.setBackgroundColor(color(R.color.management_navy));
        if(landscape) {
            TextView brand=heading(navigation,R.string.management,22); brand.setTextColor(Color.WHITE);
            brand.setPadding(dp(12),dp(14),dp(8),dp(32));
        }
        destination(Page.HOME,R.string.overview,R.drawable.ic_management_home,R.id.navigation_home);
        destination(Page.INTEGRATIONS,R.string.integrations,R.drawable.ic_management_integrations,R.id.navigation_integrations);
        destination(Page.JOURNAL,R.string.journal,R.drawable.ic_management_journal,R.id.navigation_journal);
        moliDestination=navigationButton(R.string.moli_os,R.drawable.ic_management_moli,R.id.navigation_moli,this::openMoli);
        if(landscape) root.addView(navigation,new LinearLayout.LayoutParams(dp(224),-1));
        body=column(); body.setPadding(dp(landscape ? 32 : 20),dp(12),dp(landscape ? 32 : 20),dp(12));
        root.addView(body,new LinearLayout.LayoutParams(landscape ? 0 : -1,landscape ? -1 : 0,1));
        if(!landscape) root.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); body.addView(header);
        back=button(header,R.string.back,this::goBack); back.setId(R.id.management_back);
        back.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(48)));
        title=text(header,"",26); title.setTypeface(null,android.graphics.Typeface.BOLD);
        title.setAccessibilityHeading(true); title.setPadding(dp(12),dp(12),0,dp(16));
        title.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        scroll=new ScrollView(this); scroll.setId(R.id.management_scroll); scroll.setFillViewport(true);
        scroll.setClipToPadding(false); body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=column(); content.setPadding(0,dp(12),0,dp(4)); scroll.addView(content);
        footer=column(); body.addView(footer);
        if(Build.VERSION.SDK_INT>=30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null) controller.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            root.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));
                ensureFocusVisible(); return insets;
            });
        }
    }
    private Button navigationButton(int label,int icon,int id,Runnable action) {
        Button view=new Button(this); view.setId(id); view.setText(label); view.setAllCaps(false);
        view.setTextSize(landscape ? 16 : 12); view.setMinHeight(dp(56)); view.setMinimumWidth(0);
        view.setPadding(dp(8),dp(8),dp(8),dp(8)); view.setCompoundDrawablePadding(dp(landscape ? 14 : 4));
        android.graphics.drawable.Drawable drawable=getDrawable(icon).mutate(); drawable.setTint(Color.WHITE);
        drawable.setBounds(0,0,dp(22),dp(22));
        view.setCompoundDrawablesRelative(landscape ? drawable : null,landscape ? null : drawable,null,null);
        view.setGravity(landscape ? Gravity.START|Gravity.CENTER_VERTICAL : Gravity.CENTER);
        view.setTextColor(getColorStateList(R.color.management_nav_text));
        view.setBackgroundResource(R.drawable.management_navigation_item);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(landscape ? -1 : 0,-2,landscape ? 0 : 1);
        if(landscape) params.bottomMargin=dp(8);
        navigation.addView(view,params); view.setOnClickListener(v->action.run()); return view;
    }
    private void destination(Page page,int label,int icon,int id) {
        destinations.put(page,navigationButton(label,icon,id,()->navigate(page)));
    }
    private void renderHome() {
        LinearLayout engine=card(content); engineCard=engine; styledState=null; engine.setId(R.id.engine_card);
        heading(engine,R.string.engine_card_title,18);
        LinearLayout stateRow=new LinearLayout(this); stateRow.setGravity(Gravity.CENTER_VERTICAL); engine.addView(stateRow);
        engineBadge=new FrameLayout(this);
        LinearLayout.LayoutParams badge=new LinearLayout.LayoutParams(dp(64),dp(64));badge.setMarginEnd(dp(18));stateRow.addView(engineBadge,badge);
        engineSymbol=new ImageView(this);engineSymbol.setImageResource(R.drawable.ic_engine_power);
        engineSymbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        engineBadge.addView(engineSymbol,new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER));
        status=text(stateRow,"",24); status.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));status.setId(R.id.engine_status);
        status.setTypeface(null,android.graphics.Typeface.BOLD);
        LinearLayout actions=new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL); engine.addView(actions);
        primary=button(actions,R.string.start,this::command); primary.setId(R.id.engine_action);
        primary.setBackgroundResource(R.drawable.management_button_primary); primary.setTextColor(getColorStateList(R.color.management_primary_text));
        primary.setLayoutParams(new LinearLayout.LayoutParams(0,dp(52),1));
        restart=button(actions,R.string.restart,()->sendCommand(MoliService.RESTART));
        restart.setBackgroundResource(R.drawable.management_button_restart);
        restart.setTextColor(getColorStateList(R.color.management_restart_text));
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,dp(52),1); second.setMarginStart(dp(12)); restart.setLayoutParams(second);
        // Layout placeholders only: no values, measurements or parallel monitoring service.
        int[] labels={R.string.memory_tile,R.string.storage_tile,R.string.integrations,R.string.device_tile};
        for(int i=0;i<labels.length;i+=2) {
            LinearLayout row=new LinearLayout(this); content.addView(row);
            for(int j=i;j<i+2;j++) {
                LinearLayout tile=column(); tile.setPadding(dp(20),dp(20),dp(20),dp(20)); tile.setMinimumHeight(dp(104));
                tile.setBackground(surface(R.color.management_card,R.color.management_border,16));
                heading(tile,labels[j],16);
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1); params.bottomMargin=dp(16);
                if(j==i) params.setMarginEnd(dp(16)); row.addView(tile,params);
            }
        }
    }
    private void openMoli() {
        startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    private void navigate(Page page) {
        if(screen.saving!=null || screen.loading!=null) return;
        hideKeyboard(); remember();
        if(page!=Page.INTEGRATIONS)screen.catalogueScroll=-1;
        screen.page=page;
        if(page==Page.INTEGRATIONS) { screen.error=null; load(); }
        render();
    }
    private void goBack() {
        if(screen.saving!=null || screen.loading!=null) return;
        if(screen.page==Page.PARAMETERS) navigate(Page.INTEGRATIONS);
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
        if(status!=null) {
            status.setText(label); status.setContentDescription(getString(R.string.engine_state,getString(label)));
            if(styledState!=state) {
                int tone=state==EngineHost.State.RUNNING ? R.color.management_success : state==EngineHost.State.ERROR ? R.color.management_error : state==EngineHost.State.STOPPED ? R.color.management_muted : R.color.management_pending;
                int fill=state==EngineHost.State.RUNNING ? R.color.engine_running_background : state==EngineHost.State.ERROR ? R.color.engine_error_background : state==EngineHost.State.STOPPED ? R.color.management_card : R.color.engine_pending_background;
                status.setTextColor(color(tone)); engineSymbol.setColorFilter(color(tone));
                engineBadge.setBackground(surface(fill,tone,32)); engineCard.setBackground(surface(fill,R.color.management_border,18));
                primary.setBackgroundResource(state==EngineHost.State.RUNNING ? R.drawable.management_button_stop : R.drawable.management_button_primary);
                primary.setTextColor(getColorStateList(state==EngineHost.State.RUNNING ? R.color.management_stop_text : R.color.management_primary_text));
                styledState=state;
            }
        }
        boolean idle=screen.saving==null && screen.loading==null;
        boolean actionable=state==EngineHost.State.STOPPED || state==EngineHost.State.ERROR || state==EngineHost.State.RUNNING;
        if(primary!=null) {
            primary.setText(state==EngineHost.State.RUNNING ? R.string.stop : R.string.start);
            primary.setEnabled(idle && actionable && !commandPending);
            restart.setVisibility(state==EngineHost.State.RUNNING ? View.VISIBLE : View.GONE);
            restart.setEnabled(idle && state==EngineHost.State.RUNNING && !commandPending);
        }
        moliDestination.setEnabled(idle);
        if(save!=null) save.setEnabled(idle && (state==EngineHost.State.STOPPED || state==EngineHost.State.RUNNING) && !commandPending);
        back.setEnabled(idle);
        for(Button view:destinations.values()) view.setEnabled(idle);
        boolean writable=idle && (state==EngineHost.State.STOPPED || state==EngineHost.State.RUNNING) && screen.snapshot!=null;
        for(Button view:configurationActions) view.setEnabled(writable);
        for(Button view:activationActions) view.setEnabled(writable);
    }
    private void load() {
        Context app=getApplicationContext();
        screen.loading=EngineHost.inspect(()->{
            String value=ProtectedKey.exists(app) ? new ConfigStore(app).disk.read("moli.toml",ConfigurationTransaction.FILE_LIMIT) : null;
            JSONObject result=ConfigStore.nativeCall(new JSONObject().put("action","catalogue").put("text",value==null ? "" : value));
            result.put("text",value==null ? JSONObject.NULL : value); return result;
        });
    }
    private void complete() {
        if(screen.loading!=null && screen.loading.isDone()) {
            try { screen.catalogue=screen.loading.get(); screen.snapshot=screen.catalogue.isNull("text") ? null : screen.catalogue.getString("text"); }
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
        return catalogueLabel(screen.selected.getJSONObject("manifest").getJSONObject("integration"));
    }
    private String brands(JSONObject metadata) throws JSONException {
        JSONArray values=metadata.getJSONArray("brands");List<String> names=new ArrayList<>();
        for(int i=0;i<values.length();i++)names.add(values.getString(i));
        return String.join(" / ",names);
    }
    private String catalogueLabel(JSONObject metadata) throws JSONException {
        String provider=brands(metadata);
        return provider.isEmpty() ? metadata.getString("name") : getString(R.string.catalogue_entry,provider,metadata.getString("name"));
    }
    private String onboardingSummary(String source) {
        StringBuilder first=new StringBuilder();
        for(String raw:source.split("\n")) {
            String line=raw.trim();
            if(line.isEmpty() || line.startsWith("#"))continue;
            if(first.length()>0 && line.matches("[0-9]+\\..*"))break;
            if(first.length()>0)first.append(' ');
            first.append(line.replaceFirst("^[0-9]+\\.\\s*","").replace("`",""));
        }
        return first.toString();
    }
    private void renderCatalogue() throws JSONException {
        JSONArray packages=screen.catalogue.getJSONArray("packages");
        text(content,getString(R.string.catalogue_count,packages.length()),16);
        text(content,getString(R.string.activation_help),14);
        if(screen.snapshot==null)text(content,getString(R.string.start_first),16);
        Map<String,List<JSONObject>> groups=new TreeMap<>();
        for(int i=0;i<packages.length();i++) {
            JSONObject item=packages.getJSONObject(i),metadata=item.getJSONObject("manifest").getJSONObject("integration");
            int count=metadata.getJSONArray("brands").length();
            String group=count==1 ? brands(metadata) : getString(count==0 ? R.string.no_declared_brand : R.string.multiple_brands);
            groups.computeIfAbsent(group,key->new ArrayList<>()).add(item);
        }
        for(Map.Entry<String,List<JSONObject>> group:groups.entrySet()) {
            TextView label=text(content,group.getKey(),21);label.setTypeface(null,android.graphics.Typeface.BOLD);label.setAccessibilityHeading(true);
            label.setTag("provider:"+group.getKey());
            for(JSONObject item:group.getValue()) {
                JSONObject manifest=item.getJSONObject("manifest"),metadata=manifest.getJSONObject("integration");
                String profile=metadata.getString("id");boolean configurable=item.getBoolean("configurable");
                LinearLayout integration=card(content);integration.setTag("package:"+profile);
                TextView name=text(integration,catalogueLabel(metadata),18);name.setTypeface(null,android.graphics.Typeface.BOLD);
                name.setTextColor(color(configurable ? R.color.management_ink : R.color.management_muted));
                if(!configurable) {
                    JSONObject needs=manifest.getJSONObject("needs");List<String> missing=new ArrayList<>();
                    if(!"profile".equals(metadata.getString("driver")))missing.add(getString(R.string.native_setup_missing));
                    for(String field:new String[]{"secrets","options","vars"}) {
                        JSONArray values=needs.getJSONArray(field);List<String> names=new ArrayList<>();
                        for(int i=0;i<values.length();i++)names.add(values.getString(i));
                        if(!names.isEmpty())missing.add(getString(field.equals("secrets") ? R.string.required_secrets : field.equals("options") ? R.string.required_options : R.string.required_variables,String.join(", ",names)));
                    }
                    missing.add(onboardingSummary(item.getString("onboarding")));
                    text(integration,getString(R.string.android_setup_unavailable,String.join(" · ",missing)),14).setTextColor(color(R.color.management_muted));
                }
                JSONArray instances=item.getJSONArray("instances");
                if(instances.length()==0) {
                    text(integration,getString(R.string.not_configured),14);
                    catalogueActions(integration,item,null,configurable);
                } else for(int i=0;i<instances.length();i++) {
                    JSONObject selected=instances.getJSONObject(i);
                    text(integration,getString(R.string.instance_configuration,selected.getString("id"),getString(selected.getBoolean("enabled") ? R.string.driver_enabled : R.string.driver_disabled)),16);
                    catalogueActions(integration,item,selected,configurable);
                }
            }
        }
    }
    private void catalogueActions(LinearLayout parent,JSONObject item,JSONObject selected,boolean configurable) throws JSONException {
        String profile=item.getJSONObject("manifest").getJSONObject("integration").getString("id");
        LinearLayout actions=new LinearLayout(this);parent.addView(actions);
        Button configure=button(actions,R.string.configure,()->{
            screen.catalogueScroll=scroll.getScrollY();
            screen.selected=item;screen.saved=false;screen.activation=false;screen.error=null;edit(selected);
        });
        configure.setLayoutParams(new LinearLayout.LayoutParams(0,dp(52),1));
        configure.setTag(selected==null ? profile : "configure:"+selected.getString("id"));
        if(profile.equals("open-meteo") && root.findViewById(R.id.catalogue_entry)==null)configure.setId(R.id.catalogue_entry);
        configure.setEnabled(configurable);
        if(configurable)configurationActions.add(configure);
        boolean enabled=selected!=null && selected.getBoolean("enabled");
        Button activate=button(actions,enabled ? R.string.disable_driver : R.string.enable_driver,()->setEnabled(selected));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(52),1);params.setMarginStart(dp(12));activate.setLayoutParams(params);
        activate.setEnabled(selected!=null);
        activate.setTag(selected==null ? "unconfigured-toggle:"+profile : "toggle:"+selected.getString("id"));
        if(selected!=null)activationActions.add(activate);
    }
    private void setEnabled(JSONObject selected) {
        if(selected==null || screen.saving!=null || screen.loading!=null)return;
        Context app=getApplicationContext();
        try {
            String before=screen.snapshot,id=selected.getString("id");boolean enabled=!selected.getBoolean("enabled");
            screen.catalogueScroll=scroll.getScrollY();
            screen.activation=true;screen.saved=false;screen.error=null;
            screen.saving=EngineHost.maintain(app,()->{
                try {
                    ConfigStore store=new ConfigStore(app);store.setEnabled(before,id,enabled);
                    GeneralJournal.record(app,GeneralJournal.Event.CONFIGURATION_SAVED);return store.read();
                } catch(Exception failure){GeneralJournal.record(app,GeneralJournal.Event.CONFIGURATION_REJECTED);throw failure;}
            });
        } catch(Exception failure){screen.error=getString(HostUi.errorMessage(failure));}
        render();
    }
    private void render() {
        fields.clear(); instance=null; save=null; status=null; primary=null; restart=null;
        configurationActions.clear();activationActions.clear();
        content.removeAllViews(); footer.removeAllViews();
        back.setVisibility(screen.page==Page.HOME ? View.GONE : View.VISIBLE);
        Page selected=(screen.page==Page.PARAMETERS) ? Page.INTEGRATIONS : screen.page;
        for(Map.Entry<Page,Button> item:destinations.entrySet()) {
            boolean active=item.getKey()==selected;
            item.getValue().setSelected(active);
            item.getValue().setTypeface(null,active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        title.setText(screen.page==Page.HOME ? R.string.management : screen.page==Page.JOURNAL ? R.string.journal : R.string.integrations);
        try {
            if(screen.page==Page.HOME) {
                renderHome();
            } else if(screen.page==Page.JOURNAL) {
                LinearLayout journal=card(content);
                heading(journal,R.string.export,18);
                text(journal,getString(R.string.journal_help),16);
                button(journal,R.string.export,this::exportJournal);
            } else {
                if(screen.saved) {
                    text(content,getString(screen.activation ? R.string.activation_saved : R.string.saved),18).setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                    if(!screen.activation && screen.selected!=null) {
                        String profile=screen.selected.getJSONObject("manifest").getJSONObject("integration").getString("id");
                        if("open-meteo".equals(profile) || "open-meteo-air".equals(profile))text(content,getString(R.string.outdoor_binding_help),14);
                    }
                }
                if(screen.loading!=null) text(content,getString(R.string.working),16);
                else if(screen.catalogue!=null) {
                    if(screen.page==Page.INTEGRATIONS) {
                        renderCatalogue();
                    } else renderForm();
                }
            }
        } catch(JSONException failure) { screen.error=getString(R.string.operation_failed); }
        feedback=text(content,screen.error==null ? "" : screen.error,16); feedback.setId(R.id.form_feedback);
        feedback.setTextColor(color(R.color.management_error));
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setVisibility(screen.error==null ? View.GONE : View.VISIBLE);
        if(screen.page==Page.INTEGRATIONS && screen.error!=null) {
            content.removeView(feedback);content.addView(feedback,0);screen.catalogueScroll=0;
        }
        if(screen.error!=null && screen.page==Page.PARAMETERS) {
            for(EditText field:fields.values()) field.setError(screen.error);
        }
        if(screen.page==Page.INTEGRATIONS && screen.catalogueScroll>=0)scroll.post(()->scroll.scrollTo(0,screen.catalogueScroll));
        else scroll.scrollTo(0,0);
        updateControls();
    }
    private void edit(JSONObject selected) {
        try {
            screen.existing=selected!=null;
            screen.id=selected==null ? screen.selected.getJSONObject("manifest").getJSONObject("integration").getString("id") : selected.getString("id");
            screen.draft.clear();
            JSONObject current=selected==null ? null : selected.optJSONObject("vars");
            JSONArray vars=screen.selected.getJSONObject("manifest").getJSONObject("needs").getJSONArray("vars");
            for(int i=0;i<vars.length();i++) { String name=vars.getString(i); screen.draft.put(name,current==null ? "" : current.optString(name,"")); }
            screen.advanced=false; screen.page=Page.PARAMETERS; render();
        } catch(JSONException failure) { HostUi.error(this,failure); }
    }
    private void renderForm() throws JSONException {
        title.setText(entry());
        text(content,onboardingSummary(screen.selected.getString("onboarding")),14).setTextColor(color(R.color.management_muted));
        JSONArray vars=screen.selected.getJSONObject("manifest").getJSONObject("needs").getJSONArray("vars");
        text(content,getString(vars.length()==0 ? R.string.no_required_parameters : R.string.manual_variables),14).setTextColor(color(R.color.management_muted));
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
        Button advanced=button(content,R.string.advanced_options,()->{
            screen.advanced=!screen.advanced;
            advancedFields.setVisibility(screen.advanced ? View.VISIBLE : View.GONE);
        });
        advanced.setId(R.id.advanced_options);
        advancedFields=column(); content.addView(advancedFields);
        advancedFields.setVisibility(screen.advanced ? View.VISIBLE : View.GONE);
        LinearLayout mainFields=content; content=advancedFields;
        instance=field(getString(R.string.instance),screen.id,R.id.integration_instance);
        instance.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        instance.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        content=mainFields;
        save=button(footer,screen.saving==null ? R.string.save : R.string.saving,this::save);
        save.setId(R.id.save_configuration);
        save.setBackgroundResource(R.drawable.management_button_primary); save.setTextColor(getColorStateList(R.color.management_primary_text));
        instance.setEnabled(!screen.existing && screen.saving==null);
        for(EditText field:fields.values()) field.setEnabled(screen.saving==null);
    }
    private EditText field(String label,String value,int id) {
        TextView caption=text(content,label,16); caption.setLabelFor(id);
        EditText edit=new EditText(this); edit.setId(id); edit.setSaveEnabled(false);
        edit.setSingleLine(true); edit.setText(value); edit.setMinHeight(dp(52));
        edit.setTextSize(18); edit.setTextColor(color(R.color.management_ink));
        edit.setHintTextColor(color(R.color.management_muted));
        edit.setPadding(dp(14),dp(10),dp(14),dp(10));
        edit.setBackground(surface(R.color.management_card,R.color.management_border,10));
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
            final String before=screen.snapshot,id=screen.id,profile=screen.selected.getJSONObject("manifest").getJSONObject("integration").getString("id");
            screen.saving=EngineHost.maintain(app,()->{
                try {
                    ConfigStore store=new ConfigStore(app); store.save(before,profile,id,vars);
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
