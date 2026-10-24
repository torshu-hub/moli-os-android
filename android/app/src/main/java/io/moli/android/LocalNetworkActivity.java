package io.moli.android;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;

/** Manual, visible network search. Leaving the page cancels and releases network resources. */
public final class LocalNetworkActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    final NetworkEvidence.Results results=new NetworkEvidence.Results();
    Button search, cancel, useCandidates;
    AlertDialog consentDialog;
    CheckBox probes;
    TextView state, warnings, last;
    ProgressBar progress;
    LinearLayout cards;
    private DiscoverySearch running;
    private long started;
    private int generation;
    private boolean renderPending;
    private final LinkedHashSet<String> candidateIps=new LinkedHashSet<>(),seenCandidates=new LinkedHashSet<>();
    private final Runnable redraw=() -> { renderPending=false; if(!isDestroyed()) render(); };
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(running==null) return;
            int seconds=(int)Math.min(20,(SystemClock.elapsedRealtime()-started)/1000);
            progress.setProgress(seconds); state.setText(getString(R.string.network_searching,seconds,deviceCount()));
            main.postDelayed(this,500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(16*getResources().getDisplayMetrics().density); body.setPadding(pad,pad,pad,pad);
        ScrollView scroll=new ScrollView(this); scroll.addView(body); setContentView(scroll);
        text(body,getString(R.string.network_title)); text(body,getString(R.string.network_help));
        probes=new CheckBox(this); probes.setText(R.string.network_probes);probes.setChecked(getIntent().getBooleanExtra("identify_meross",false)); body.addView(probes);
        search=button(body,R.string.network_search,() -> consentDialog=new AlertDialog.Builder(this).setTitle(R.string.network_title)
                .setMessage(getString(R.string.network_consent)+(getIntent().getBooleanExtra("identify_meross",false)?"\n"+getString(R.string.account_network_consent):"")).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.network_search,(d,w) -> begin(new LocalDiscovery(this,probes.isChecked()))).show());
        cancel=button(body,R.string.network_cancel,() -> { if(running!=null) running.close(); }); cancel.setEnabled(false);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(20); body.addView(progress);
        state=text(body,getString(R.string.network_idle)); warnings=text(body,""); last=text(body,""); updateLast();
        useCandidates=button(body,R.string.account_use_candidates,()->{
            ArrayList<String> ips=new ArrayList<>(candidateIps);
            TrialLog.action(this,TrialJournal.Event.IP_CHOSEN,TrialJournal.Source.RESEAU,TrialJournal.Outcome.REUSSI,TrialJournal.Error.AUCUNE,ips.size(),0,null);
            setResult(RESULT_OK,new Intent().putStringArrayListExtra("candidates",ips).putExtra("identify_now",getIntent().getBooleanExtra("identify_meross",false)));finish();
        });useCandidates.setVisibility(getIntent().getBooleanExtra("pick_meross_ip",false)?android.view.View.VISIBLE:android.view.View.GONE);useCandidates.setEnabled(false);
        cards=new LinearLayout(this); cards.setOrientation(LinearLayout.VERTICAL); body.addView(cards);
        text(body,getString(R.string.network_mac_unavailable));

        button(body,R.string.back,this::finish);
        if(getIntent().getBooleanExtra("identify_meross",false))main.post(()->search.performClick());
    }
    // A package-private source seam exercises the actual UI with deterministic fixtures, never a test intent.
    void begin(DiscoverySearch source) {
        if(running!=null) return;
        int token=++generation;useCandidates.setEnabled(false);candidateIps.clear();seenCandidates.clear(); results.clear(); cards.removeAllViews(); warnings.setText(""); progress.setProgress(0);
        TrialLog.action(this,TrialJournal.Event.NETWORK_SEARCH,TrialJournal.Source.RESEAU,TrialJournal.Outcome.DEBUT,TrialJournal.Error.AUCUNE,probes.isChecked()?1:0,0,null);
        running=source; started=SystemClock.elapsedRealtime(); search.setEnabled(false); probes.setEnabled(false); cancel.setEnabled(true);
        source.start(new DiscoverySearch.Listener() {
            public void found(NetworkEvidence.Entry entry) { main.post(() -> {
                if(token==generation && running!=null && results.add(entry) && !renderPending) {
                    renderPending=true; main.postDelayed(redraw,150);
                }
            }); }
            public void warning() { main.post(() -> { if(token==generation && running!=null) warnings.setText(R.string.network_partial); }); }
            public void finished(DiscoverySearch.End reason) { main.post(() -> {
                if(token!=generation) return;
                running=null; main.removeCallbacks(tick); search.setEnabled(true); probes.setEnabled(true); cancel.setEnabled(false);
                main.removeCallbacks(redraw); renderPending=false;
                if(reason==DiscoverySearch.End.NETWORK_CHANGED || reason==DiscoverySearch.End.UNAVAILABLE) { results.clear(); cards.removeAllViews(); }
                else render();
                int id=reason==DiscoverySearch.End.COMPLETE?R.string.network_done:reason==DiscoverySearch.End.CANCELLED?R.string.network_cancelled:reason==DiscoverySearch.End.NETWORK_CHANGED?R.string.network_changed:R.string.network_unavailable;
                state.setText(getString(id,deviceCount()));
                journalEnd(reason);
                if(reason==DiscoverySearch.End.COMPLETE) progress.setProgress(20);
                getSharedPreferences("network-discovery",MODE_PRIVATE).edit().putLong("last",System.currentTimeMillis()).apply(); updateLast();
            }); }
        });
        main.post(tick);
    }
    private void updateLast() {
        long time=getSharedPreferences("network-discovery",MODE_PRIVATE).getLong("last",0);
        last.setText(time==0?getString(R.string.network_never):getString(R.string.network_last,DateFormat.getDateTimeInstance().format(new Date(time))));
    }
    private void render() {
        cards.removeAllViews();
        java.util.LinkedHashMap<String,ArrayList<NetworkEvidence.Entry>> groups=new java.util.LinkedHashMap<>();
        for(NetworkEvidence.Entry e:results.list())groups.computeIfAbsent(e.ip,k->new ArrayList<>()).add(e);
        for(ArrayList<NetworkEvidence.Entry> group:groups.values()) {
            NetworkEvidence.Entry first=group.get(0);
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(0,24,0,8); cards.addView(card);
            String announced="";for(NetworkEvidence.Entry e:group)if(!e.name.isEmpty() && !announced.contains(e.name))announced+=(announced.isEmpty()?"":" / ")+e.name;
            text(card,first.ip+" — "+(announced.isEmpty()?getString(R.string.network_unknown):announced));
            for(NetworkEvidence.Entry e:group){
                int service=e.kind==NetworkEvidence.Kind.IPP?R.string.network_ipp:e.kind==NetworkEvidence.Kind.HTTP?R.string.network_http:e.kind==NetworkEvidence.Kind.IGD?R.string.network_igd:e.kind==NetworkEvidence.Kind.CAST?R.string.network_cast:R.string.network_upnp;
                int proof=e.proof==NetworkEvidence.Proof.OPEN_PORT?R.string.network_port_proof:e.proof==NetworkEvidence.Proof.DESCRIPTION?R.string.network_description_proof:e.kind==NetworkEvidence.Kind.UPNP?R.string.network_ssdp_proof:R.string.network_mdns_proof;
                text(card,getString(service)+" — port "+e.port+"\n"+getString(proof));
                // Open ports alone do not qualify an IPP/IGD integration.
                if(!getIntent().getBooleanExtra("pick_meross_ip",false) && e.kind==NetworkEvidence.Kind.IPP)button(card,R.string.network_configure_ipp,()->configure(e,"ipp"));
                if(!getIntent().getBooleanExtra("pick_meross_ip",false) && e.kind==NetworkEvidence.Kind.IGD && e.proof==NetworkEvidence.Proof.DESCRIPTION)button(card,R.string.network_configure_igd,()->configure(e,"igd"));
            }
            if(getIntent().getBooleanExtra("pick_meross_ip",false)){
                boolean port80=false;for(NetworkEvidence.Entry e:group)if(e.port==80)port80=true;
                if(port80){if(seenCandidates.add(first.ip) && candidateIps.size()<8)candidateIps.add(first.ip);
                    CheckBox choice=new CheckBox(this);choice.setText(R.string.account_candidate_select);choice.setChecked(candidateIps.contains(first.ip));card.addView(choice);
                    choice.setOnCheckedChangeListener((v,on)->{if(on){if(candidateIps.size()>=8 && !candidateIps.contains(first.ip)){choice.setChecked(false);Toast.makeText(this,R.string.account_candidate_limit,Toast.LENGTH_SHORT).show();return;}candidateIps.add(first.ip);}else candidateIps.remove(first.ip);useCandidates.setEnabled(running==null && !candidateIps.isEmpty());});
                }
            }
            button(card,R.string.network_copy,()->copy(first));
            if(getIntent().getBooleanExtra("pick_meross_ip",false))button(card,R.string.account_pick_ip,()->{
                TrialLog.action(this,TrialJournal.Event.IP_CHOSEN,TrialJournal.Source.RESEAU,TrialJournal.Outcome.REUSSI,TrialJournal.Error.AUCUNE,0,0,first.ip);
                Intent choice=new Intent();if(getIntent().getBooleanExtra("identify_meross",false))choice.putStringArrayListExtra("candidates",new ArrayList<>(java.util.Collections.singletonList(first.ip))).putExtra("identify_now",true);else choice.putExtra("ip",first.ip);setResult(RESULT_OK,choice);finish();
            });
            else button(card,R.string.network_configure_type,()->new AlertDialog.Builder(this).setTitle(R.string.network_type_help)
                .setItems(new String[]{getString(R.string.network_as_printer),getString(R.string.account_find_plugs)},(dialog,which)->{
                    if(which==0)configure(first,"ipp");else startActivity(new Intent(this,MerossAccountActivity.class));
                }).setNegativeButton(android.R.string.cancel,null).show());
        }
        useCandidates.setEnabled(running==null && !candidateIps.isEmpty());
    }
    private int deviceCount(){java.util.HashSet<String> ips=new java.util.HashSet<>();for(NetworkEvidence.Entry e:results.list())ips.add(e.ip);return ips.size();}
    private void journalEnd(DiscoverySearch.End reason){
        long types=0;for(NetworkEvidence.Entry e:results.list())types|=1L<<e.kind.ordinal();
        TrialLog.action(this,TrialJournal.Event.NETWORK_END,TrialJournal.Source.RESEAU,reason==DiscoverySearch.End.COMPLETE?TrialJournal.Outcome.REUSSI:reason==DiscoverySearch.End.CANCELLED?TrialJournal.Outcome.ANNULE:TrialJournal.Outcome.INDISPONIBLE,TrialJournal.Error.AUCUNE,deviceCount(),types,null);
    }

    void copy(NetworkEvidence.Entry e) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getString(R.string.equipment_host),e.ip));
        TrialLog.action(this,TrialJournal.Event.IP_COPY,TrialJournal.Source.RESEAU,TrialJournal.Outcome.REUSSI,TrialJournal.Error.AUCUNE,0,0,e.ip);
        Toast.makeText(this,R.string.network_copied,Toast.LENGTH_SHORT).show();
    }
    void configure(NetworkEvidence.Entry e,String kind) {
        if(running!=null) running.close();
        TrialLog.action(this,TrialJournal.Event.EQUIPMENT_PROPOSED,TrialJournal.Source.RESEAU,TrialJournal.Outcome.REUSSI,TrialJournal.Error.AUCUNE,kind.equals("ipp")?1:kind.equals("igd")?2:3,0,e.ip);
        startActivity(selection(this,e,kind));
    }
    static Intent selection(Context context,NetworkEvidence.Entry e,String kind) {
        Intent i=new Intent(context,EquipmentSettingsActivity.class).putExtra("discovery_kind",kind).putExtra("discovery_ip",e.ip);
        // Meross is a manual model choice: another service's port/name is NOT a Meross endpoint/identity.
        if(kind.equals("meross")) return i;
        i.putExtra("discovery_port",e.port);
        if(kind.equals("ipp")) {
            boolean announced=e.kind==NetworkEvidence.Kind.IPP && e.proof!=NetworkEvidence.Proof.OPEN_PORT;
            i.putExtra("discovery_port",e.kind==NetworkEvidence.Kind.IPP?e.port:631).putExtra("discovery_path",announced?e.path:"/ipp/print").putExtra("ipp_confirmed",announced);
        }
        if(kind.equals("igd")) i.putExtra("discovery_location",e.location);
        return i;
    }
    private TextView text(LinearLayout parent,String value) { TextView t=new TextView(this); t.setText(value); t.setTextSize(16); parent.addView(t); return t; }
    private Button button(LinearLayout parent,int label,Runnable action) { Button b=new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); parent.addView(b); return b; }
    @Override public void onStop() {
        if(running!=null) { running.close(); running=null;journalEnd(DiscoverySearch.End.CANCELLED); }
        generation++; main.removeCallbacks(tick); search.setEnabled(true); probes.setEnabled(true); cancel.setEnabled(false);
        main.removeCallbacks(redraw); renderPending=false;
        // No topology survives leaving the screen, including rotation/process recreation.
        results.clear();candidateIps.clear();seenCandidates.clear(); cards.removeAllViews(); state.setText(R.string.network_idle);
        super.onStop();
    }
}
