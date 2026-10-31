package io.moli.android;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Supplier;

/** One native page. Retains only ephemeral facts; never touches Moli configuration. */
final class NetworkPanel {
    interface Factory { Target current()throws Exception; }
    static final class Target {
        final String label,key,own;final boolean limited;final Supplier<DiscoverySearch> create;
        Target(String label,String key,boolean limited,Supplier<DiscoverySearch> create){this(label,key,limited,create,"");}
        Target(String label,String key,boolean limited,Supplier<DiscoverySearch> create,String own){this.label=label;this.key=key;this.limited=limited;this.create=create;this.own=own;}
    }
    static final class State {
        final NetworkEvidence.Results results=new NetworkEvidence.Results();
        Factory factory;
        String label="",key,own="";
        DiscoverySearch.End end;
        boolean limited;
        int scroll;
    }
    private final Activity activity;
    java.util.function.Consumer<String> useMeross;
    java.util.function.Function<NetworkEvidence.Device,String> association=d->"";
    java.util.function.Consumer<NetworkEvidence.Device> openAssociation;
    final State state;
    private final ScrollView scroll;
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout rows;
    private TextView network,status,hint;
    private Button search,cancel;
    private DiscoverySearch running;
    private int generation;
    private boolean pending;
    private final Runnable redraw=()->{pending=false;render();};
    NetworkPanel(Activity activity,State state,ScrollView scroll){
        this.activity=activity;this.state=state;this.scroll=scroll;
        if(state.factory==null){
            Context app=activity.getApplicationContext();
            state.factory=()->{
                LocalDiscovery.Lan lan=LocalDiscovery.current(app);
                return new Target(lan.label(app),lan.network+"|"+lan.iface+"|"+lan.scope.own+"/"+lan.scope.prefix,lan.scope.probesLimited(),()->new LocalDiscovery(app,lan),NetworkEvidence.address(lan.scope.own));
            };
        }
    }
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private TextView text(LinearLayout parent,String value,int size){
        TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(activity.getColor(R.color.management_ink));
        view.setPadding(0,dp(4),0,dp(4));parent.addView(view);return view;
    }
    private Button button(LinearLayout parent,int label,Runnable action){
        Button view=new Button(activity);view.setText(label);view.setAllCaps(false);view.setMinHeight(dp(48));view.setTextSize(15);
        view.setBackgroundResource(R.drawable.management_button_secondary);view.setTextColor(activity.getColorStateList(R.color.management_button_text));
        parent.addView(view,new LinearLayout.LayoutParams(0,dp(48),1));view.setOnClickListener(v->action.run());return view;
    }
    void mount(LinearLayout parent){
        network=text(parent,"",16);network.setId(R.id.network_active);
        LinearLayout actions=new LinearLayout(activity);parent.addView(actions);
        search=button(actions,R.string.network_search,this::begin);search.setId(R.id.network_search);
        search.setBackgroundResource(R.drawable.management_button_primary);search.setTextColor(activity.getColorStateList(R.color.management_primary_text));
        cancel=button(actions,R.string.network_cancel,this::stop);cancel.setId(R.id.network_cancel);
        LinearLayout.LayoutParams cp=(LinearLayout.LayoutParams)cancel.getLayoutParams();cp.setMarginStart(dp(8));
        status=text(parent,"",14);status.setId(R.id.network_status);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        hint=text(parent,activity.getString(R.string.network_limits),13);hint.setTextColor(activity.getColor(R.color.management_muted));hint.setId(R.id.network_limits);
        rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);rows.setId(R.id.network_results);parent.addView(rows);
        refreshTarget();render();scroll.post(()->scroll.scrollTo(0,state.scroll));
    }
    void resume(){
        if(rows==null||running!=null)return;
        String previous=state.key;DiscoverySearch.End end=state.end;refreshTarget();
        if(!Objects.equals(previous,state.key)||end!=state.end)render();
    }
    private Target refreshTarget(){
        try{
            Target target=state.factory.current();
            if(state.key!=null&&!state.key.equals(target.key)){state.results.clear();state.end=DiscoverySearch.End.NETWORK_CHANGED;}
            state.label=target.label;state.key=target.key;state.own=target.own;return target;
        }catch(Exception unavailable){state.own="";state.label=activity.getString(R.string.network_unavailable);state.end=DiscoverySearch.End.UNAVAILABLE;return null;}
    }
    boolean searching(){return running!=null;}
    void begin(){
        if(running!=null)return;
        Target target=refreshTarget();if(target==null){render();return;}
        state.results.clear();state.scroll=0;state.end=null;state.limited=target.limited;
        int token=++generation;running=target.create.get();render();
        try{running.start(new DiscoverySearch.Listener(){
            public void found(NetworkEvidence.Entry entry){
                if(token!=generation||running==null)return;
                if(state.results.add(entry))schedule();
            }
            public void warning(){if(token==generation&&running!=null){state.limited=true;schedule();}}
            public void finished(DiscoverySearch.End reason){
                if(token!=generation||running==null)return;running=null;state.end=reason;
                if(reason==DiscoverySearch.End.NETWORK_CHANGED){state.results.clear();refreshTarget();state.end=reason;}
                schedule();
            }
        });}catch(RuntimeException failed){
            DiscoverySearch failedSearch=running;running=null;generation++;if(failedSearch!=null)failedSearch.close();state.end=DiscoverySearch.End.UNAVAILABLE;render();
        }
    }
    private void schedule(){if(!pending){pending=true;main.postDelayed(redraw,200);}}
    void stop(){
        if(running!=null){DiscoverySearch old=running;running=null;generation++;old.close();state.end=DiscoverySearch.End.CANCELLED;}
        main.removeCallbacks(redraw);pending=false;
        if(rows!=null)render();
    }
    void leave(){state.scroll=scroll.getScrollY();stop();main.removeCallbacksAndMessages(null);}
    void dispose(){rows=null;stop();main.removeCallbacksAndMessages(null);}
    private int endLabel(){
        if(running!=null)return R.string.network_searching;
        if(state.end==null)return R.string.network_ready;
        switch(state.end){
            case COMPLETE:return R.string.network_complete;
            case CANCELLED:return R.string.network_cancelled;
            case NETWORK_CHANGED:return R.string.network_changed;
            default:return R.string.network_unavailable;
        }
    }
    private String service(NetworkEvidence.Entry fact){
        switch(fact.kind){
            case IPP:return "IPP";case HTTP:return "HTTP";case CAST:return "Google Cast";
            case PRESENCE:return activity.getString(R.string.network_presence);case UPNP:return "UPnP";case IGD:return "UPnP IGD";default:return "TCP";
        }
    }
    private String proof(NetworkEvidence.Entry fact){
        int label=fact.proof==NetworkEvidence.Proof.PTR?R.string.network_name_source:fact.proof==NetworkEvidence.Proof.ECHO?R.string.network_echo:fact.proof==NetworkEvidence.Proof.DESCRIPTION?R.string.network_description:
            fact.proof==NetworkEvidence.Proof.OPEN_PORT?R.string.network_port:
            (fact.kind==NetworkEvidence.Kind.UPNP||fact.kind==NetworkEvidence.Kind.IGD)?R.string.network_ssdp:R.string.network_mdns;
        return activity.getString(label);
    }
    private String summary(NetworkEvidence.Device device){
        LinkedHashSet<String> parts=new LinkedHashSet<>();
        for(NetworkEvidence.Entry fact:device.facts)parts.add(service(fact)+" :"+fact.port+" · "+proof(fact));
        return String.join(" ; ",parts);
    }
    private void render(){
        if(rows==null||activity.isDestroyed())return;
        int y=scroll.getScrollY();network.setText(state.label);
        status.setText(activity.getString(R.string.network_count_state,state.results.size(),activity.getString(endLabel())));
        hint.setText(activity.getString(state.end==DiscoverySearch.End.UNAVAILABLE?R.string.network_requirements:state.limited||state.results.limited?R.string.network_partial:R.string.network_limits));
        search.setEnabled(running==null);cancel.setVisibility(running==null?View.GONE:View.VISIBLE);
        rows.removeAllViews();
        if(!state.own.isEmpty())text(rows,activity.getString(R.string.network_self,state.own),14);
        for(NetworkEvidence.Device device:state.results.list()){
            LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(12),dp(8),dp(12),dp(8));row.setMinimumHeight(dp(64));
            row.setBackgroundResource(R.drawable.management_network_row);row.setTag("network-ip:"+device.ip);
            String title=device.ip+" — "+(device.name().isEmpty()?activity.getString(R.string.network_unknown):device.name());
            TextView name=text(row,title,16);name.setTypeface(null,android.graphics.Typeface.BOLD);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String annotation=association.apply(device);
            LinkedHashSet<String> descriptions=new LinkedHashSet<>();for(NetworkEvidence.Entry fact:device.facts)if(!fact.description().isEmpty())descriptions.add(fact.description());
            String compact=String.join(" · ",descriptions);
            if(compact.isEmpty())compact=activity.getString(R.string.network_presence);
            if(!annotation.isEmpty())compact+=" · "+annotation;
            TextView details=text(row,compact,13);details.setTextColor(activity.getColor(R.color.management_muted));details.setSingleLine(true);details.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);details.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.setContentDescription(title+", "+compact);row.setOnClickListener(v->details(device));
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(2);rows.addView(row,rp);
        }
        scroll.post(()->scroll.scrollTo(0,y));
    }
    private void details(NetworkEvidence.Device device){
        StringBuilder facts=new StringBuilder();
        for(NetworkEvidence.Entry fact:device.facts){
            if(facts.length()>0)facts.append("\n\n");facts.append(proof(fact)).append(" · ").append(service(fact));
            if(fact.port>0)facts.append(" :").append(fact.port);
            if(!fact.name.isEmpty())facts.append("\n").append(fact.name);
            if(!fact.description().isEmpty())facts.append("\n").append(fact.description());
            if(!fact.deviceType.isEmpty())facts.append("\n").append(fact.deviceType);
            if(!fact.identity.isEmpty())facts.append("\n").append(fact.identity);
            if(!fact.location.isEmpty())facts.append("\n").append(fact.location);
        }
        AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle(device.ip+" — "+(device.name().isEmpty()?activity.getString(R.string.network_unknown):device.name()))
            .setMessage(facts+"\n\n"+association.apply(device)+"\n\n"+activity.getString(R.string.network_evidence_help))
            .setPositiveButton(R.string.network_copy,(ignored,which)->{
                activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.network_ip),device.ip));
                Toast.makeText(activity,R.string.network_copied,Toast.LENGTH_SHORT).show();
            }).setNegativeButton(R.string.network_close,(ignored,which)->{});
        if(openAssociation!=null && !association.apply(device).isEmpty())dialog.setNeutralButton(R.string.integration_open,(ignored,which)->openAssociation.accept(device));
        else if(useMeross!=null)dialog.setNeutralButton(R.string.meross_use_ip,(ignored,which)->useMeross.accept(device.ip));
        dialog.show();
    }
}
