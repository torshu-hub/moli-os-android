package io.moli.android;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;

/** Automatically started preview sessions. Latest bounded journal, private file, voluntary export. */
final class TrialLog {
    private static Context app;
    private static TrialJournal journal;
    private static int diagnosticDepth;
    private static long diagnosticSaved;
    static synchronized void attach(Context context) {
        app=context.getApplicationContext();
        if(journal!=null) return;
        try { File f=file(); journal=f.exists()?TrialJournal.parse(ProfileStore.bounded(new AtomicFile(f).openRead(),131072)):new TrialJournal(); }
        catch(Exception ignored) { journal=new TrialJournal();journal.incomplete=true; }
    }
    private static File file() { return new File(app.getFilesDir(),"trial-session.json"); }
    private static void save() { try { ProfileStore.atomic(file(),journal.json()); } catch(Exception ignored) { journal.incomplete=true; } }
    static synchronized void begin(Context c) {
        attach(c); if(journal.active) return;
        journal=new TrialJournal(); journal.salt=ProtectedKey.randomHex(); journal.active=true; record(TrialJournal.Event.SESSION_START,0,0);
    }
    static synchronized void end(Context c) { attach(c); if(!journal.active) return; record(TrialJournal.Event.SESSION_END,0,0); journal.active=false; save(); }
    static synchronized boolean active() { return journal!=null && journal.active; }
    static synchronized int count() { return journal==null?0:journal.events.length(); }
    static synchronized void record(TrialJournal.Event event,long a,long b) {
        if(app==null || journal==null || !journal.active) return;
        try {
            if(event==TrialJournal.Event.ENGINE_STOPPED||event==TrialJournal.Event.ENGINE_FAILED)journal.diagnostics.engineStopped(System.currentTimeMillis());
            if(event==TrialJournal.Event.ENGINE_STARTED || event==TrialJournal.Event.ENGINE_STOPPED || event==TrialJournal.Event.ENGINE_FAILED)
                journal.action(System.currentTimeMillis(),event,TrialJournal.Source.MOTEUR,event==TrialJournal.Event.ENGINE_FAILED?TrialJournal.Outcome.INDISPONIBLE:TrialJournal.Outcome.REUSSI,event==TrialJournal.Event.ENGINE_FAILED?TrialJournal.Error.REPONSE:TrialJournal.Error.AUCUNE,a,b,"");
            else journal.add(System.currentTimeMillis(),event,a,b);
            save(); } catch(Exception ignored) { /* Fixed schema only. */ }
    }
    static synchronized void action(Context c,TrialJournal.Event event,TrialJournal.Source source,TrialJournal.Outcome outcome,TrialJournal.Error error,long a,long b,String identity) {
        action(c,event,source,outcome,error,a,b,identity,null,0);
    }
    static synchronized void action(Context c,TrialJournal.Event event,TrialJournal.Source source,TrialJournal.Outcome outcome,TrialJournal.Error error,long a,long b,String identity,String candidate,long receipt) {
        attach(c);if(!journal.active)return;
        try {
            String pseudonym=pseudonym(identity),peer=pseudonym(candidate);
            if(event==TrialJournal.Event.EQUIPMENT_READ){
                for(int i=journal.events.length()-1;i>=0;i--){org.json.JSONObject previous=journal.events.getJSONObject(i);
                    if(event.name().equals(previous.optString("event")) && pseudonym.equals(previous.optString("subject"))){
                        if(outcome.name().equals(previous.optString("result")) && a==previous.optLong("a") && b==previous.optLong("b") && (receipt==previous.optLong("receipt") || System.currentTimeMillis()-previous.optLong("time")<60000))return;break;
                    }
                }
            }
            journal.action(System.currentTimeMillis(),event,source,outcome,error,a,b,pseudonym,peer,receipt);save();
        }catch(Exception ignored){ /* No raw identifiers, exception text or salt in export. */ }
    }
    private static String pseudonym(String identity)throws Exception {
        if(identity==null || identity.isEmpty())return "";
        if(journal.salt.isEmpty())journal.salt=ProtectedKey.randomHex();
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(journal.salt.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        byte[] digest=mac.doFinal(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(int i=0;i<8;i++)hex.append(String.format(java.util.Locale.ROOT,"%02x",digest[i]&255));return hex.toString();
    }
    static synchronized void diagnostic(Context c,TrialDiagnostics.Topic topic,TrialDiagnostics.State state,TrialDiagnostics.Reason reason,long a,long b,long generation,long attempts,long successes,long failures,long attemptAt,long successAt,long errorAt){
        attach(c);if(!journal.active)return;
        try{
            long now=System.currentTimeMillis();
            boolean changed=journal.diagnostics.observe(now,topic,state,reason,a,b,generation,attempts,successes,failures,attemptAt,successAt,errorAt);
            if(changed&&journal.diagnostics.trace(topic,state,now))
                journal.add(now,TrialJournal.Event.DIAGNOSTIC_CHANGED,topic.ordinal(),state.ordinal());
            if(diagnosticDepth==0&&(changed||now-diagnosticSaved>=15000)){save();diagnosticSaved=now;}
        }catch(Exception ignored){}
    }
    interface DiagnosticWork{void run()throws org.json.JSONException;}
    static synchronized void polling(Context c,org.json.JSONObject snapshot)throws org.json.JSONException{
        attach(c);if(!journal.active)return;
        journal.diagnostics.polling=TrialDiagnostics.Polling.merge(journal.diagnostics.polling,snapshot);
    }
    static synchronized void diagnosticDetail(Context c,TrialDiagnostics.Topic topic,long generation,TrialDiagnostics.Reason reason,long duration,long interruptions,java.util.EnumMap<TrialDiagnostics.Reason,Long> errors){
        attach(c);if(journal.active)journal.diagnostics.detail(topic,generation,reason,duration,interruptions,errors);
    }
    static synchronized void diagnosticBatch(Context c,DiagnosticWork work)throws org.json.JSONException{
        attach(c);if(!journal.active)return;diagnosticDepth++;
        try{work.run();}finally{diagnosticDepth--;if(diagnosticDepth==0){save();diagnosticSaved=System.currentTimeMillis();}}
    }
    static void ui(Context c,int topic,int state,int a,int b){
        if(topic<1||topic>6||state<1||state>9||a<0||a>4096||b<0||b>4096)return;
        TrialDiagnostics.Topic key=switch(topic){case 1->TrialDiagnostics.Topic.UI_DEVICES;case 2->TrialDiagnostics.Topic.UI_SYSTEM;case 3->TrialDiagnostics.Topic.NETATMO_TILE;case 4->TrialDiagnostics.Topic.UI_SCRIPT;case 6->TrialDiagnostics.Topic.UI_MEROSS_COMMAND;default->TrialDiagnostics.Topic.UI_ASSISTANT;};
        TrialDiagnostics.State value=switch(state){case 1->TrialDiagnostics.State.PENDING;case 2->TrialDiagnostics.State.OK;case 8->TrialDiagnostics.State.ABSENT;case 9->TrialDiagnostics.State.STALE;default->TrialDiagnostics.State.FAILED;};
        TrialDiagnostics.Reason reason=switch(state){case 3->TrialDiagnostics.Reason.HTTP;case 4->TrialDiagnostics.Reason.TIMEOUT;case 5->TrialDiagnostics.Reason.INVALID_RESPONSE;case 6->TrialDiagnostics.Reason.RENDER;case 7->TrialDiagnostics.Reason.TRANSPORT;default->TrialDiagnostics.Reason.NONE;};
        diagnostic(c,key,value,reason,a,b,-1,0,0,0,0,0,0);
    }
    static TrialJournal.Error error(String code) {
        if(code==null)return TrialJournal.Error.AUCUNE;
        return switch(code) {
            case "account_identity" -> TrialJournal.Error.IDENTITE;
            case "account_inventory_missing" -> TrialJournal.Error.INVENTAIRE_ABSENT;
            case "account_uuid_different" -> TrialJournal.Error.UUID_DIFFERENT;
            case "account_key" -> TrialJournal.Error.CLE_REFUSEE;
            case "account_auth" -> TrialJournal.Error.AUTHENTIFICATION;
            case "account_mfa" -> TrialJournal.Error.MFA;
            case "account_expired" -> TrialJournal.Error.AUTHENTIFICATION;
            case "account_rate" -> TrialJournal.Error.LIMITE;
            case "account_denied","account_wifi" -> TrialJournal.Error.AUTORISATION;
            case "storage","account_initialize" -> TrialJournal.Error.COFFRE;
            case "running" -> TrialJournal.Error.MOTEUR_ACTIF;
            case "account_protocol" -> TrialJournal.Error.INCOMPATIBLE;
            case "account_region","account_redirect" -> TrialJournal.Error.REPONSE;
            case "account_input","equipment_ip","equipment_port","meross_settings","meross_duplicate" -> TrialJournal.Error.SAISIE;
            default -> TrialJournal.Error.RESEAU;
        };
    }
    static String export(Context c) throws Exception {
        String text;
        synchronized(TrialLog.class) { attach(c); text=journal.text(); }
        android.content.pm.PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
        long[] health=EngineHealth.snapshot();
        int webview=0;try{android.content.pm.PackageInfo provider=android.webkit.WebView.getCurrentWebViewPackage();if(provider!=null)webview=Integer.parseInt(provider.versionName.split("\\.")[0]);}catch(Exception ignored){}
        return "version="+info.versionName+" code="+info.getLongVersionCode()+" package="+c.getPackageName()+" webview_major="+Math.max(0,webview)+"\n"
                +"current_engine="+EngineHost.state().name()+"\n"
                +"engine_starts="+c.getSharedPreferences("diagnostics",Context.MODE_PRIVATE).getLong("starts",0)+"\n"
                +"health_success="+health[2]+" health_failure="+health[3]+" max_check_gap_ms="+health[4]+"\n"
                +"Un intervalle sans contrôle ne prouve pas une panne. Lire le moteur ne force pas une requête fournisseur.\n"
                +"Aucun identifiant, secret, IP/MAC, URL privée, nom réseau ou position. Pseudonymes propres à cette session.\n"
                +text;
    }
}
