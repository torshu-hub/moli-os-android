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
    Button search, cancel;
    CheckBox probes;
    TextView state, warnings, last;
    ProgressBar progress;
    LinearLayout cards;
    private DiscoverySearch running;
    private long started;
    private int generation;
    private boolean renderPending;
    private final Runnable redraw=() -> { renderPending=false; if(!isDestroyed()) render(); };
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(running==null) return;
            int seconds=(int)Math.min(20,(SystemClock.elapsedRealtime()-started)/1000);
            progress.setProgress(seconds); state.setText(getString(R.string.network_searching,seconds,results.list().size()));
            main.postDelayed(this,500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(16*getResources().getDisplayMetrics().density); body.setPadding(pad,pad,pad,pad);
        ScrollView scroll=new ScrollView(this); scroll.addView(body); setContentView(scroll);
        text(body,getString(R.string.network_title)); text(body,getString(R.string.network_help));
        probes=new CheckBox(this); probes.setText(R.string.network_probes); body.addView(probes);
        search=button(body,R.string.network_search,() -> new AlertDialog.Builder(this).setTitle(R.string.network_title)
                .setMessage(R.string.network_consent).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.network_search,(d,w) -> begin(new LocalDiscovery(this,probes.isChecked()))).show());
        cancel=button(body,R.string.network_cancel,() -> { if(running!=null) running.close(); }); cancel.setEnabled(false);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(20); body.addView(progress);
        state=text(body,getString(R.string.network_idle)); warnings=text(body,""); last=text(body,""); updateLast();
        cards=new LinearLayout(this); cards.setOrientation(LinearLayout.VERTICAL); body.addView(cards);
        button(body,R.string.back,this::finish);
    }
    // A package-private source seam exercises the actual UI with deterministic fixtures, never a test intent.
    void begin(DiscoverySearch source) {
        if(running!=null) return;
        int token=++generation; results.clear(); cards.removeAllViews(); warnings.setText(""); progress.setProgress(0);
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
                state.setText(getString(id,results.list().size()));
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
        for(NetworkEvidence.Entry e:results.list()) {
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(0,24,0,8); cards.addView(card);
            text(card,e.ip+":"+e.port+(e.name.isEmpty()?"":" — "+e.name));
            int service=e.kind==NetworkEvidence.Kind.IPP?R.string.network_ipp:e.kind==NetworkEvidence.Kind.HTTP?R.string.network_http:e.kind==NetworkEvidence.Kind.IGD?R.string.network_igd:e.kind==NetworkEvidence.Kind.CAST?R.string.network_cast:R.string.network_upnp;
            int proof=e.proof==NetworkEvidence.Proof.OPEN_PORT?R.string.network_port_proof:e.proof==NetworkEvidence.Proof.DESCRIPTION?R.string.network_description_proof:e.kind==NetworkEvidence.Kind.UPNP?R.string.network_ssdp_proof:R.string.network_mdns_proof;
            text(card,getString(service)+"\n"+getString(proof));
            button(card,R.string.network_copy,() -> copy(e));
            if(e.kind==NetworkEvidence.Kind.IPP) button(card,R.string.network_configure_ipp,() -> configure(e,"ipp"));
            if(e.kind==NetworkEvidence.Kind.IGD) button(card,R.string.network_configure_igd,() -> configure(e,"igd"));
            button(card,R.string.network_configure_meross,() -> new AlertDialog.Builder(this).setTitle(R.string.meross_title)
                    .setMessage(R.string.network_meross_warning).setNegativeButton(android.R.string.cancel,null)
                    .setPositiveButton(R.string.network_continue,(d,w) -> configure(e,"meross")).show());
        }
    }
    void copy(NetworkEvidence.Entry e) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getString(R.string.equipment_host),e.ip));
        Toast.makeText(this,R.string.network_copied,Toast.LENGTH_SHORT).show();
    }
    void configure(NetworkEvidence.Entry e,String kind) {
        if(running!=null) running.close();
        startActivity(selection(this,e,kind));
    }
    static Intent selection(Context context,NetworkEvidence.Entry e,String kind) {
        Intent i=new Intent(context,EquipmentSettingsActivity.class).putExtra("discovery_kind",kind).putExtra("discovery_ip",e.ip);
        // Meross is a manual model choice: another service's port/name is NOT a Meross endpoint/identity.
        if(kind.equals("meross")) return i;
        i.putExtra("discovery_port",e.port);
        if(kind.equals("ipp")) i.putExtra("discovery_path",e.path);
        if(kind.equals("igd")) i.putExtra("discovery_location",e.location);
        return i;
    }
    private TextView text(LinearLayout parent,String value) { TextView t=new TextView(this); t.setText(value); t.setTextSize(16); parent.addView(t); return t; }
    private Button button(LinearLayout parent,int label,Runnable action) { Button b=new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); parent.addView(b); return b; }
    @Override public void onStop() {
        if(running!=null) { running.close(); running=null; }
        generation++; main.removeCallbacks(tick); search.setEnabled(true); probes.setEnabled(true); cancel.setEnabled(false);
        main.removeCallbacks(redraw); renderPending=false;
        // No topology survives leaving the screen, including rotation/process recreation.
        results.clear(); cards.removeAllViews(); state.setText(R.string.network_idle);
        super.onStop();
    }
}
