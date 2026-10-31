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
    private enum Page { HOME, INTEGRATIONS, PARAMETERS, JOURNAL, NETWORK }
    // Transient presentation state, retained across rotation; never a driver configuration store.
    private static final class Screen {
        Page page = Page.HOME;
        final NetworkPanel.State network=new NetworkPanel.State();
        JSONObject catalogue, selected;
        String snapshot, id = "", error;
        final Map<String,String> draft = new LinkedHashMap<>();
        boolean existing, saved, activation;
        int filter;
        int catalogueScroll=-1;
        Future<JSONObject> loading, merossWork;
        JSONObject account;
        String accountUuid="", merossKey="", pendingMerossHost;
        boolean manualMeross, merossReturn, finding, merossScanning, findAfterInventory, identified;
        final MerossMatches matches=new MerossMatches();
        List<String> candidates=new ArrayList<>();int candidateOffset;
        String matchNetwork;
        Future<JSONObject> identityWork;

        Future<String> saving;
    }
    private Screen screen;
    NetworkPanel networkPanel;
    private LinearLayout root, navigation, body, content, footer;
    private Button moliDestination;
    private LinearLayout engineCard;
    private ImageView engineSymbol;
    private FrameLayout engineBadge;
    private EngineHost.State styledState;
    private ScrollView scroll;
    private TextView status, title, feedback;
    private Button primary, restart, save, back;
    private EditText instance, merossKey;
    private final Map<String,EditText> fields = new LinkedHashMap<>();
    private final Map<Page,Button> destinations = new EnumMap<>(Page.class);
    private final List<View> configurationActions=new ArrayList<>(), activationActions=new ArrayList<>(), contextActions=new ArrayList<>();
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
        networkPanel=new NetworkPanel(this,screen.network,scroll);
        networkPanel.useMeross=this::useMerossIp;
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
        destination(Page.NETWORK,R.string.network_destination,R.drawable.ic_management_network,R.id.navigation_network);
        destination(Page.JOURNAL,R.string.journal,R.drawable.ic_management_journal,R.id.navigation_journal);
        moliDestination=navigationButton(R.string.moli_os,R.drawable.ic_management_moli,R.id.navigation_moli,this::openMoli);
        View navigationContainer;
        if(landscape){
            ScrollView rail=new ScrollView(this);rail.setFillViewport(true);rail.setHorizontalScrollBarEnabled(false);rail.addView(navigation);
            navigationContainer=rail;root.addView(rail,new LinearLayout.LayoutParams(dp(224),-1));
        }else{
            HorizontalScrollView rail=new HorizontalScrollView(this);rail.setFillViewport(true);rail.setHorizontalScrollBarEnabled(false);
            rail.addView(navigation,new android.widget.FrameLayout.LayoutParams(Math.max(dp(560),dp(getResources().getConfiguration().screenWidthDp)),-2));navigationContainer=rail;
        }
        body=column(); body.setPadding(dp(landscape ? 32 : 20),dp(12),dp(landscape ? 32 : 20),dp(12));
        root.addView(body,new LinearLayout.LayoutParams(landscape ? 0 : -1,landscape ? -1 : 0,1));
        if(!landscape) root.addView(navigationContainer,new LinearLayout.LayoutParams(-1,-2));
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
        if(screen.page==Page.NETWORK)networkPanel.leave();
        startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    private void navigate(Page page) {
        if(screen.saving!=null || screen.loading!=null || screen.merossWork!=null || screen.finding || screen.identityWork!=null) return;
        if(screen.page==Page.NETWORK)networkPanel.leave();
        hideKeyboard(); remember();
        if(page!=Page.INTEGRATIONS)screen.catalogueScroll=-1;
        screen.page=page;
        if(page==Page.NETWORK)screen.error=null;
        if(page==Page.INTEGRATIONS) { screen.error=null; load(); }
        render();
    }
    private void goBack() {
        if(screen.saving!=null || screen.loading!=null || screen.merossWork!=null || screen.finding || screen.identityWork!=null) return;
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
        boolean idle=screen.saving==null && screen.loading==null && screen.merossWork==null && !screen.finding && screen.identityWork==null;
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
        for(View view:configurationActions) view.setEnabled(writable);
        for(View view:activationActions) view.setEnabled(writable);
        for(View view:contextActions) view.setEnabled(idle);
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
        completeMerossIdentification();
        if(screen.merossWork!=null && screen.merossWork.isDone()) {
            try { remember();screen.account=screen.merossWork.get();screen.error=null; }
            catch(Exception failure) { screen.error=getString(HostUi.errorMessage(failure)); }
            screen.merossWork=null;render();
            if(screen.findAfterInventory){screen.findAfterInventory=false;if(screen.error==null)findMeross();}
        }
        if(screen.loading!=null && screen.loading.isDone()) {
            try { screen.catalogue=screen.loading.get(); screen.snapshot=screen.catalogue.isNull("text") ? null : screen.catalogue.getString("text"); }
            catch(Exception failure) { screen.error=getString(HostUi.errorMessage(failure)); }
            screen.loading=null;
            if(screen.pendingMerossHost!=null && screen.catalogue!=null) {
                try {
                    JSONArray packages=screen.catalogue.getJSONArray("packages");
                    for(int i=0;i<packages.length();i++) {
                        JSONObject item=packages.getJSONObject(i);
                        if(item.getJSONObject("manifest").getJSONObject("integration").getString("id").equals("meross-mss310")) {
                            screen.selected=item;if(!screen.merossReturn)edit(null);
                            screen.draft.put("host",screen.pendingMerossHost);screen.page=Page.PARAMETERS;break;
                        }
                    }
                }catch(JSONException failure){screen.error=getString(HostUi.errorMessage(failure));}
                screen.pendingMerossHost=null;screen.merossReturn=false;
            }
            render();
        }
        if(screen.saving!=null && screen.saving.isDone()) {
            try {
                screen.snapshot=screen.saving.get(); screen.saved=true; screen.error=null;
                screen.page=Page.INTEGRATIONS; screen.draft.clear();screen.merossKey="";screen.accountUuid=""; screen.catalogue=null;
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
    private int description(String id) {
        switch(id) {
            case "alexa": return R.string.description_alexa;
            case "bambu": return R.string.description_bambu;
            case "daikin-brp069": return R.string.description_daikin_brp069;
            case "demo": return R.string.description_demo;
            case "dirigera": return R.string.description_dirigera;
            case "esphome": return R.string.description_esphome;
            case "frigate": return R.string.description_frigate;
            case "helpers": return R.string.description_helpers;
            case "host": return R.string.description_host;
            case "hue": return R.string.description_hue;
            case "igd": return R.string.description_igd;
            case "iopool": return R.string.description_iopool;
            case "ipp": return R.string.description_ipp;
            case "izypower": return R.string.description_izypower;
            case "klipper": return R.string.description_klipper;
            case "meross-mss310": return R.string.description_meross_mss310;
            case "meross-em06": return R.string.description_meross_em06;
            case "moonraker": return R.string.description_moonraker;
            case "open-meteo": return R.string.description_open_meteo;
            case "open-meteo-air": return R.string.description_open_meteo_air;
            case "philips": return R.string.description_philips;
            case "phones": return R.string.description_phones;
            case "presence": return R.string.description_presence;
            case "reolink": return R.string.description_reolink;
            case "shelly": return R.string.description_shelly;
            case "solarman": return R.string.description_solarman;
            case "sonos": return R.string.description_sonos;
            case "tapo": return R.string.description_tapo;
            case "telegram": return R.string.description_telegram;
            case "tempo": return R.string.description_tempo;
            case "tuya": return R.string.description_tuya;
            case "z2m": return R.string.description_z2m;
            default: return R.string.integrations_help;
        }
    }
    private void unavailable(JSONObject item) {
        try {
            JSONObject needs=item.getJSONObject("manifest").getJSONObject("needs");
            List<String> reasons=new ArrayList<>();
            String id=item.getJSONObject("manifest").getJSONObject("integration").getString("id");
            if(needs.getJSONArray("secrets").length()>0 || id.equals("hue") || id.equals("dirigera")) reasons.add(getString(R.string.setup_auth_missing));
            else if(id.equals("igd"))reasons.add(getString(R.string.setup_discovery_pending));
            else if(id.equals("host"))reasons.add(getString(R.string.setup_host_pending));
            else reasons.add(getString(R.string.setup_flow_missing));
            for(String field:new String[]{"options","vars"}) {
                JSONArray values=needs.getJSONArray(field);List<String> names=new ArrayList<>();
                for(int i=0;i<values.length();i++)names.add(values.getString(i));
                if(!names.isEmpty())reasons.add(getString(field.equals("options") ? R.string.required_options : R.string.required_variables,String.join(", ",names)));
            }
            new android.app.AlertDialog.Builder(this).setTitle(catalogueLabel(item.getJSONObject("manifest").getJSONObject("integration")))
                .setMessage(String.join("\n",reasons)).setPositiveButton(android.R.string.ok,null).show();
        } catch(JSONException failure){HostUi.error(this,failure);}
    }
    private void renderCatalogue() throws JSONException {
        JSONArray packages=screen.catalogue.getJSONArray("packages");
        LinearLayout filters=new LinearLayout(this);content.addView(filters);
        int[] labels={R.string.filter_all,R.string.filter_configurable,R.string.filter_incomplete};
        for(int i=0;i<labels.length;i++) {
            final int choice=i;Button filter=button(filters,labels[i],()->{screen.filter=choice;screen.catalogueScroll=0;render();});
            filter.setTag("filter:"+i);filter.setSelected(screen.filter==i);
            filter.setTypeface(null,screen.filter==i ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            filter.setPadding(dp(6),dp(6),dp(6),dp(6));filter.setTextSize(14);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(48),1);if(i>0)params.setMarginStart(dp(6));filter.setLayoutParams(params);
            contextActions.add(filter);
        }
        if(screen.snapshot==null)text(content,getString(R.string.start_first),14);
        Map<String,List<JSONObject>> groups=new TreeMap<>();
        for(int i=0;i<packages.length();i++) {
            JSONObject item=packages.getJSONObject(i),metadata=item.getJSONObject("manifest").getJSONObject("integration");
            boolean configurable=item.getBoolean("configurable");
            if(screen.filter==1&&!configurable || screen.filter==2&&configurable)continue;
            int count=metadata.getJSONArray("brands").length();
            String group=count==1 ? brands(metadata) : getString(count==0 ? R.string.no_declared_brand : R.string.multiple_brands);
            groups.computeIfAbsent(group,key->new ArrayList<>()).add(item);
        }
        for(Map.Entry<String,List<JSONObject>> group:groups.entrySet()) {
            TextView label=text(content,group.getKey(),16);label.setPadding(dp(4),dp(14),0,dp(6));
            label.setTypeface(null,android.graphics.Typeface.BOLD);label.setAccessibilityHeading(true);label.setTag("provider:"+group.getKey());
            for(JSONObject item:group.getValue()) {
                JSONArray instances=item.getJSONArray("instances");
                if(instances.length()==0)catalogueRow(item,null);
                else {
                    for(int i=0;i<instances.length();i++)catalogueRow(item,instances.getJSONObject(i));
                    if(item.getJSONObject("manifest").getJSONObject("integration").getString("id").equals("meross-mss310")) {
                        Button add=button(content,R.string.meross_add,()->{screen.selected=item;edit(null);});
                        add.setTag("meross:add");configurationActions.add(add);
                    }
                }
            }
        }
    }
    private void catalogueRow(JSONObject item,JSONObject selected) throws JSONException {
        JSONObject metadata=item.getJSONObject("manifest").getJSONObject("integration");
        String profile=metadata.getString("id");boolean configurable=item.getBoolean("configurable");
        boolean valid=selected!=null&&selected.getBoolean("valid");
        String id=selected==null ? null : selected.getString("id"),name=catalogueLabel(metadata);
        if(profile.equals("meross-mss310") && selected!=null)name=selected.getJSONObject("options").optString("name",name);
        int state=!configurable ? R.string.setup_incomplete : selected==null ? R.string.setup_needed : valid ? R.string.setup_configured : R.string.setup_invalid;
        String stateText=getString(state);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setTag("package:"+profile);
        row.setBackground(surface(R.color.management_card,0,8));
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(2);content.addView(row,rp);
        LinearLayout body=column();body.setPadding(dp(12),dp(8),dp(4),dp(8));body.setMinimumHeight(dp(64));
        row.addView(body,new LinearLayout.LayoutParams(0,-2,1));
        body.setBackgroundResource(R.drawable.management_catalogue_row);
        android.text.SpannableString title=new android.text.SpannableString(name+"  "+stateText);
        title.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,name.length(),0);
        title.setSpan(new android.text.style.RelativeSizeSpan(0.8f),name.length()+2,title.length(),0);
        title.setSpan(new android.text.style.ForegroundColorSpan(color(state==R.string.setup_invalid ? R.color.management_error : R.color.management_muted)),name.length()+2,title.length(),0);
        TextView label=text(body,title,16);label.setPadding(0,0,0,0);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTextColor(color(configurable ? R.color.management_ink : R.color.management_muted));
        String detail=getString(description(profile))+(id==null ? "" : " · "+id);
        TextView summary=text(body,detail,13);summary.setPadding(0,dp(3),0,0);summary.setSingleLine(true);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setTextColor(color(R.color.management_muted));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);summary.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        body.setContentDescription(name+", "+stateText+", "+detail+(configurable ? ", "+getString(R.string.configure) : ", "+getString(R.string.setup_reason)));
        body.setTag(selected==null ? profile : "configure:"+id);
        body.setOnClickListener(v->{
            if(!configurable){unavailable(item);return;}
            screen.catalogueScroll=scroll.getScrollY();screen.selected=item;screen.saved=false;screen.activation=false;screen.error=null;edit(selected);
        });
        if(profile.equals("open-meteo")&&root.findViewById(R.id.catalogue_entry)==null)body.setId(R.id.catalogue_entry);
        if(configurable)configurationActions.add(body);else contextActions.add(body);
        boolean enabled=selected!=null&&selected.getBoolean("enabled");
        Switch toggle=new Switch(this);toggle.setChecked(enabled);toggle.setShowText(false);toggle.setMinimumWidth(dp(56));toggle.setMinimumHeight(dp(48));
        toggle.setPadding(dp(8),0,dp(12),0);row.addView(toggle,new LinearLayout.LayoutParams(-2,dp(48)));
        toggle.setContentDescription(getString(enabled ? R.string.disable_instance : R.string.enable_instance,id==null ? name : id));
        toggle.setTag(selected==null ? "unconfigured-toggle:"+profile : "toggle:"+id);
        boolean actionable=selected!=null&&(!configurable||valid);
        toggle.setEnabled(actionable);if(actionable)activationActions.add(toggle);
        toggle.setOnClickListener(v->{toggle.setChecked(enabled);setEnabled(selected);});
    }
    private void setEnabled(JSONObject selected) {
        if(selected==null || screen.saving!=null || screen.loading!=null || screen.merossWork!=null || screen.finding || screen.identityWork!=null)return;
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
        fields.clear(); instance=null; merossKey=null; save=null; status=null; primary=null; restart=null;
        configurationActions.clear();activationActions.clear();contextActions.clear();
        content.removeAllViews(); footer.removeAllViews();
        back.setVisibility(screen.page==Page.HOME ? View.GONE : View.VISIBLE);
        Page selected=(screen.page==Page.PARAMETERS) ? Page.INTEGRATIONS : screen.page;
        for(Map.Entry<Page,Button> item:destinations.entrySet()) {
            boolean active=item.getKey()==selected;
            item.getValue().setSelected(active);
            item.getValue().setTypeface(null,active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        title.setText(screen.page==Page.HOME ? R.string.management : screen.page==Page.JOURNAL ? R.string.journal : screen.page==Page.NETWORK ? R.string.network_title : R.string.integrations);
        try {
            if(screen.page==Page.HOME) {
                renderHome();
            } else if(screen.page==Page.NETWORK) {
                networkPanel.mount(content);
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
        else if(screen.page!=Page.NETWORK) scroll.scrollTo(0,0);
        updateControls();
    }
    private void edit(JSONObject selected) {
        try {
            screen.existing=selected!=null;
            screen.id=selected==null ? screen.selected.getJSONObject("manifest").getJSONObject("integration").getString("id") : selected.getString("id");
            screen.draft.clear();screen.merossKey="";screen.accountUuid="";screen.identified=false;
            if(selected==null && screen.selected.getJSONObject("manifest").getJSONObject("integration").getString("id").equals("meross-mss310"))screen.id="meross-"+java.util.UUID.randomUUID().toString().substring(0,8);
            JSONObject options=selected==null ? null : selected.optJSONObject("options");
            JSONArray fields=screen.selected.getJSONArray("fields");
            for(int i=0;i<fields.length();i++) {
                JSONObject spec=fields.getJSONObject(i);String name=spec.getString("name"),location=spec.getString("location");
                JSONObject current=options==null ? null : location.equals("vars") ? options.optJSONObject("vars") : options;
                screen.draft.put(name,current==null ? "" : current.optString(name,""));
            }
            screen.page=Page.PARAMETERS;
            if(screen.selected.getJSONObject("manifest").getJSONObject("integration").getString("id").equals("meross-mss310") && screen.account==null && screen.merossWork==null) {
                Context app=getApplicationContext();screen.merossWork=EngineHost.inspect(()->new ConfigStore(app).meross(new JSONObject().put("operation","status")));
            }
            render();
        } catch(JSONException failure) { HostUi.error(this,failure); }
    }
    private void renderForm() throws JSONException {
        title.setText(entry());
        String profile=screen.selected.getJSONObject("manifest").getJSONObject("integration").getString("id");
        text(content,getString(description(profile)),14).setTextColor(color(R.color.management_muted));
        if(profile.equals("meross-mss310")) {
            renderMeross();
            if(autoMerossReady()) {
                save=button(footer,R.string.meross_add_found,this::addFoundMeross);save.setTag("meross:add-found");
                save.setBackgroundResource(R.drawable.management_button_primary);save.setTextColor(getColorStateList(R.color.management_primary_text));
                if(screen.matches.found()<screen.account.optJSONArray("items").length()) {
                    Button manual=button(content,R.string.meross_manual,()->{screen.manualMeross=true;render();});configurationActions.add(manual);
                }
                return;
            }
        }
        JSONArray specs=screen.selected.getJSONArray("fields");
        if(specs.length()==0)text(content,getString(R.string.no_required_parameters),14);
        if(profile.equals("sonos"))text(content,getString(R.string.sonos_basic_only),14);
        for(int i=0;i<specs.length();i++) {
            JSONObject spec=specs.getJSONObject(i);String name=spec.getString("name");
            if(profile.equals("meross-mss310") && name.equals("expected_uuid")) {
                String uuid=screen.draft.getOrDefault(name,"");
                if(!uuid.isEmpty())text(content,getString(R.string.meross_identity,uuid),13);
                continue;
            }
            boolean lat="lat".equals(name),lon="lon".equals(name);
            String label=lat ? getString(R.string.latitude) : lon ? getString(R.string.longitude) : profile.equals("meross-mss310") && name.equals("name") ? getString(R.string.meross_name) : name;
            if(!spec.getBoolean("required"))label=getString(R.string.optional_parameter,label);
            EditText field=field(label,screen.draft.getOrDefault(name,""),lat ? R.id.variable_lat : lon ? R.id.variable_lon : View.generateViewId());
            field.setTag("field:"+name);
            if(lat || lon) {
                field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);field.setHint(R.string.coordinate_hint);
            } else if(spec.getBoolean("numeric"))field.setInputType(InputType.TYPE_CLASS_NUMBER);
            else field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            if(!spec.getBoolean("required"))field.setHint(R.string.official_default);
            field.setImeOptions((i==specs.length()-1 ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT)|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            fields.put(name,field);configurationActions.add(field);
        }
        if(screen.existing)text(content,getString(R.string.existing_instance,screen.id),13).setTextColor(color(R.color.management_muted));
        else if(!profile.equals("meross-mss310")) {
            instance=field(getString(R.string.instance),screen.id,R.id.integration_instance);
            instance.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            instance.setImeOptions(EditorInfo.IME_ACTION_DONE|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            instance.setEnabled(screen.saving==null);
        }
        save=button(footer,screen.saving==null ? R.string.save : R.string.saving,this::save);
        save.setId(R.id.save_configuration);
        save.setBackgroundResource(R.drawable.management_button_primary); save.setTextColor(getColorStateList(R.color.management_primary_text));
        for(EditText field:fields.values()) field.setEnabled(screen.saving==null);
    }
    private void useMerossIp(String host) {
        screen.pendingMerossHost=host;screen.page=Page.INTEGRATIONS;networkPanel.leave();load();render();
    }
    private void merossAction(JSONObject request) {
        if(screen.merossWork!=null || screen.saving!=null)return;
        remember();hideKeyboard();screen.error=null;screen.identified=false;screen.accountUuid="";
        Context app=getApplicationContext();
        try {screen.merossWork=EngineHost.maintain(app,()->new ConfigStore(app).meross(request));}
        catch(Exception failure){screen.error=getString(HostUi.errorMessage(failure));}
        render();
    }
    private void renderMeross() throws JSONException {
        text(content,getString(R.string.meross_local_help),14);
        LinearLayout actions=new LinearLayout(this);content.addView(actions);
        Button account=button(actions,R.string.meross_account,()->accountDialog(false));configurationActions.add(account);
        Button choose=button(actions,R.string.meross_choose_ip,()->{
            remember();hideKeyboard();
            List<NetworkEvidence.Device> devices=screen.network.results.list();
            try { if(screen.network.factory!=null && !Objects.equals(screen.network.key,screen.network.factory.current().key))devices=Collections.emptyList(); }
            catch(Exception unavailable){devices=Collections.emptyList();}
            if(devices.isEmpty()) {screen.merossReturn=true;navigate(Page.NETWORK);return;}
            final List<NetworkEvidence.Device> candidates=devices;
            String[] labels=new String[candidates.size()];for(int i=0;i<labels.length;i++)labels[i]=candidates.get(i).ip+" — "+candidates.get(i).name();
            new android.app.AlertDialog.Builder(this).setTitle(R.string.meross_choose_ip).setItems(labels,(d,n)->{screen.draft.put("host",candidates.get(n).ip);render();}).show();
        });configurationActions.add(choose);
        for(Button b:new Button[]{account,choose})b.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        if(screen.account!=null && screen.account.optBoolean("connected")) {
            renderMerossMatches();
            if(autoMerossReady())return;
            JSONArray items=screen.account.optJSONArray("items");
            if(items!=null && items.length()>0) {
                String[] labels=new String[items.length()];for(int i=0;i<labels.length;i++)labels[i]=items.getJSONObject(i).getString("name");
                Button select=button(content,R.string.meross_select_account_device,()->{
                    remember();
                    new android.app.AlertDialog.Builder(this).setTitle(R.string.meross_select_account_device).setItems(labels,(d,n)->{
                        try {JSONObject item=items.getJSONObject(n);screen.accountUuid=item.getString("uuid");screen.draft.put("name",item.getString("name"));screen.merossKey="";render();}
                        catch(JSONException failure){HostUi.error(this,failure);}
                    }).show();
                });configurationActions.add(select);
            } else text(content,getString(R.string.meross_no_devices),14);
            Button refresh=button(content,R.string.meross_inventory,()->{try{merossAction(new JSONObject().put("operation","inventory"));}catch(JSONException ignored){}});configurationActions.add(refresh);
            Button forget=button(content,R.string.meross_forget,()->{try{screen.accountUuid="";merossAction(new JSONObject().put("operation","forget"));}catch(JSONException ignored){}});configurationActions.add(forget);
        }
        if(!screen.accountUuid.isEmpty())text(content,getString(R.string.meross_identity,screen.accountUuid),13);
        merossKey=field(getString(R.string.meross_key),screen.merossKey,R.id.meross_key);
        merossKey.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        merossKey.setHint(screen.existing ? R.string.meross_keep_key : R.string.meross_key_hint);
        configurationActions.add(merossKey);
        if(screen.merossWork!=null)text(content,getString(R.string.meross_working),14);
    }
    private boolean autoMerossReady(){return screen.account!=null && screen.account.optBoolean("connected") && screen.account.optJSONArray("items")!=null && screen.identified && !screen.manualMeross && screen.matches.found()>0;}
    private List<String> merossCandidates() throws Exception {
        NetworkPanel.Target network=screen.network.factory.current();
        if(!Objects.equals(network.key,screen.network.key))return Collections.emptyList();
        List<String> result=new ArrayList<>();
        for(NetworkEvidence.Device device:screen.network.results.list())result.add(device.ip);
        return result;
    }
    private void findMeross() {
        if(screen.finding || screen.identityWork!=null || screen.merossWork!=null)return;
        remember();hideKeyboard();screen.error=null;
        if(screen.account==null || !screen.account.optBoolean("connected"))return;
        if(screen.account.optJSONArray("items")==null) {
            try {screen.findAfterInventory=true;merossAction(new JSONObject().put("operation","inventory"));}catch(JSONException ignored){}
            return;
        }
        if(screen.account.optJSONArray("items").length()==0)return;
        screen.matches.hosts.clear();while(screen.matches.results.length()>0)screen.matches.results.remove(0);
        screen.identified=false;screen.manualMeross=false;screen.candidateOffset=0;screen.finding=true;
        try {
            screen.candidates=merossCandidates();
            if(screen.candidates.isEmpty()) {
                screen.merossScanning=true;networkPanel.begin();
            } else {screen.matchNetwork=screen.network.key;nextMerossBatch();}
        }catch(Exception unavailable){screen.finding=false;screen.error=getString(R.string.network_unavailable);}
        render();
    }
    private void cancelMerossFind() {
        screen.finding=false;screen.merossScanning=false;screen.findAfterInventory=false;
        networkPanel.stop();
    }
    private void completeMerossIdentification() {
        if(screen.merossScanning && !networkPanel.searching()) {
            screen.merossScanning=false;
            try {
                if(screen.network.end!=DiscoverySearch.End.COMPLETE)throw new IllegalStateException("account_local");
                screen.candidates=merossCandidates();screen.matchNetwork=screen.network.key;nextMerossBatch();
            }catch(Exception failure){screen.finding=false;screen.error=getString(HostUi.errorMessage(failure));}
            render();
        }
        if(screen.identityWork!=null && screen.identityWork.isDone()) {
            try {
                JSONObject reply=screen.identityWork.get();
                if(screen.finding){
                    if(!Objects.equals(screen.matchNetwork,screen.network.factory.current().key))throw new IllegalStateException("account_local");
                    screen.matches.add(reply.getJSONArray("results"));
                }
            }catch(Exception failure){screen.finding=false;screen.error=getString(HostUi.errorMessage(failure));}
            screen.identityWork=null;
            if(screen.finding)nextMerossBatch();
            remember();render();
        }
    }
    private void nextMerossBatch() {
        if(!screen.finding)return;
        if(screen.candidateOffset>=screen.candidates.size()){screen.finding=false;screen.identified=true;return;}
        try {
            if(!Objects.equals(screen.matchNetwork,screen.network.factory.current().key))throw new IllegalStateException("account_local");
            JSONArray batch=new JSONArray();int end=Math.min(screen.candidates.size(),screen.candidateOffset+4);
            for(int i=screen.candidateOffset;i<end;i++)batch.put(screen.candidates.get(i));
            JSONObject input=new JSONObject().put("operation","identify").put("candidates",batch).put("items",screen.account.getJSONArray("items"));
            Context app=getApplicationContext();screen.candidateOffset=end;
            screen.identityWork=EngineHost.inspect(()->new ConfigStore(app).meross(input));
        }catch(Exception failure){screen.finding=false;screen.error=getString(HostUi.errorMessage(failure));}
    }
    private void renderMerossMatches() throws JSONException {
        Button find=button(content,R.string.meross_find,this::findMeross);find.setTag("meross:find");configurationActions.add(find);
        if(screen.finding) {
            text(content,screen.merossScanning ? getString(R.string.network_searching) : getString(R.string.meross_find_progress,screen.candidateOffset,screen.candidates.size()),14);
            button(content,R.string.network_cancel,()->{cancelMerossFind();render();}).setTag("meross:cancel");
        }
        if(!screen.identified && !screen.finding)return;
        JSONArray items=screen.account.optJSONArray("items");if(items==null)return;
        for(int i=0;i<items.length();i++) {
            JSONObject item=items.getJSONObject(i);String uuid=item.getString("uuid"),host=screen.matches.unique(uuid);
            String state=screen.matches.ambiguous(uuid)?getString(R.string.meross_match_ambiguous):host!=null?host: getString(screen.finding?R.string.meross_match_pending:R.string.meross_match_missing);
            TextView row=text(content,item.getString("name")+" — "+state+(host==null?"":"\n"+getString(R.string.meross_identity,uuid)),14);
            row.setTag("meross:match:"+uuid);
        }
        if(screen.identified) {
            text(content,getString(R.string.meross_match_summary,screen.matches.found(),items.length()),14);
            int unavailable=0,key=0,other=0;
            for(int i=0;i<screen.matches.results.length();i++) {
                String result=screen.matches.results.getJSONObject(i).optString("result");
                if(result.equals("unavailable"))unavailable++;else if(result.equals("key_rejected"))key++;else if(!result.equals("matched"))other++;
            }
            if(unavailable+key+other>0)text(content,getString(R.string.meross_other_results,unavailable,key,other),13);
        }
    }
    private void addFoundMeross() {
        if(screen.saving!=null || screen.finding || screen.identityWork!=null)return;
        try {
            if(!Objects.equals(screen.matchNetwork,screen.network.factory.current().key))throw new IllegalStateException("account_local");
            JSONArray rows=screen.matches.plan(screen.account.getJSONArray("items"));
            if(rows.length()==0)return;
            final String before=screen.snapshot;Context app=getApplicationContext();screen.activation=false;screen.error=null;
            screen.saving=EngineHost.maintain(app,()->{
                try {ConfigStore store=new ConfigStore(app);store.saveFoundMeross(before,rows);GeneralJournal.record(app,GeneralJournal.Event.CONFIGURATION_SAVED);return store.read();}
                catch(Exception failure){GeneralJournal.record(app,GeneralJournal.Event.CONFIGURATION_REJECTED);throw failure;}
            });
        }catch(Exception failure){screen.error=getString(HostUi.errorMessage(failure));}
        render();
    }
    private void accountDialog(boolean otpRequired) {
        otpRequired=otpRequired || (screen.error!=null && screen.error.equals(getString(R.string.meross_error_mfa)));
        LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));
        Spinner region=new Spinner(this);region.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"EU","US","AP","Global"}));form.addView(region);
        EditText email=new EditText(this);email.setHint(R.string.meross_email);email.setSingleLine(true);email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);form.addView(email);
        EditText password=new EditText(this);password.setHint(R.string.meross_password);password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);form.addView(password);
        EditText otp=new EditText(this);otp.setHint(R.string.meross_otp);otp.setInputType(InputType.TYPE_CLASS_NUMBER);if(otpRequired)form.addView(otp);
        for(EditText field:new EditText[]{email,password,otp}) {field.setSaveEnabled(false);field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);}
        ScrollView panel=new ScrollView(this);panel.addView(form);
        new android.app.AlertDialog.Builder(this).setTitle(R.string.meross_account).setView(panel).setPositiveButton(R.string.meross_connect,(dialog,which)->{
            try {merossAction(new JSONObject().put("operation","signin").put("region",new String[]{"eu","us","ap","global"}[region.getSelectedItemPosition()]).put("email",email.getText().toString()).put("password",password.getText().toString()).put("otp",otp.getText().toString()));}
            catch(JSONException failure){HostUi.error(this,failure);}
            email.setText("");password.setText("");otp.setText("");
        }).setNegativeButton(android.R.string.cancel,null).show();
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
        if(merossKey!=null)screen.merossKey=merossKey.getText().toString();
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
            JSONObject vars=new JSONObject(),options=new JSONObject();
            JSONArray specs=screen.selected.getJSONArray("fields");
            for(int i=0;i<specs.length();i++) {
                JSONObject spec=specs.getJSONObject(i);String name=spec.getString("name");
                (spec.getString("location").equals("vars") ? vars : options).put(name,screen.draft.getOrDefault(name,""));
            }
            final String before=screen.snapshot,id=screen.id,key=screen.merossKey,accountUuid=screen.accountUuid,profile=screen.selected.getJSONObject("manifest").getJSONObject("integration").getString("id");
            screen.saving=EngineHost.maintain(app,()->{
                try {
                    ConfigStore store=new ConfigStore(app);
                    if(profile.equals("meross-mss310"))store.saveMeross(before,id,options,key,accountUuid);
                    else store.save(before,profile,id,vars,options);
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
    @Override public void onStart() { super.onStart();if(screen.page==Page.NETWORK)networkPanel.resume();handler.post(refresh); }
    @Override public void onStop() { if(screen.finding || screen.findAfterInventory)cancelMerossFind();if(screen.page==Page.NETWORK)networkPanel.leave();remember(); handler.removeCallbacks(refresh); super.onStop(); }
    @Override public void onDestroy() { networkPanel.dispose();handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
