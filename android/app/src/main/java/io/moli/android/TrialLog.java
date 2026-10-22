package io.moli.android;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;

/** Explicit trial sessions only. Latest bounded journal, private file, voluntary export. */
final class TrialLog {
    private static Context app;
    private static TrialJournal journal;
    static synchronized void attach(Context context) {
        app=context.getApplicationContext();
        if(journal!=null) return;
        try { File f=file(); journal=f.exists()?TrialJournal.parse(ProfileStore.bounded(new AtomicFile(f).openRead(),65536)):new TrialJournal(); }
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
            if(event==TrialJournal.Event.ENGINE_STARTED || event==TrialJournal.Event.ENGINE_STOPPED || event==TrialJournal.Event.ENGINE_FAILED)
                journal.action(System.currentTimeMillis(),event,TrialJournal.Source.MOTEUR,event==TrialJournal.Event.ENGINE_FAILED?TrialJournal.Outcome.INDISPONIBLE:TrialJournal.Outcome.REUSSI,event==TrialJournal.Event.ENGINE_FAILED?TrialJournal.Error.REPONSE:TrialJournal.Error.AUCUNE,a,b,"");
            else journal.add(System.currentTimeMillis(),event,a,b);
            save(); } catch(Exception ignored) { /* Fixed schema only. */ }
    }
    static synchronized void action(Context c,TrialJournal.Event event,TrialJournal.Source source,TrialJournal.Outcome outcome,TrialJournal.Error error,long a,long b,String identity) {
        attach(c); if(!journal.active)return;
        try {
            String pseudonym="";
            if(identity!=null && !identity.isEmpty()) {
                if(journal.salt.isEmpty())journal.salt=ProtectedKey.randomHex();
                javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(journal.salt.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
                byte[] digest=mac.doFinal(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex=new StringBuilder();for(int i=0;i<8;i++)hex.append(String.format(java.util.Locale.ROOT,"%02x",digest[i]&255));pseudonym=hex.toString();
            }
            if(event==TrialJournal.Event.EQUIPMENT_READ){
                for(int i=journal.events.length()-1;i>=0;i--){org.json.JSONObject previous=journal.events.getJSONObject(i);
                    if(event.name().equals(previous.optString("event")) && pseudonym.equals(previous.optString("subject"))){
                        if(outcome.name().equals(previous.optString("result")) && a==previous.optLong("a") && b==previous.optLong("b"))return;break;
                    }
                }
            }
            journal.action(System.currentTimeMillis(),event,source,outcome,error,a,b,pseudonym);save();
        }catch(Exception ignored){ /* No raw identifiers, exception text or salt in export. */ }
    }
    static TrialJournal.Error error(String code) {
        if(code==null)return TrialJournal.Error.AUCUNE;
        return switch(code) {
            case "account_identity" -> TrialJournal.Error.IDENTITE;
            case "account_auth" -> TrialJournal.Error.AUTHENTIFICATION;
            case "account_mfa" -> TrialJournal.Error.MFA;
            case "account_expired" -> TrialJournal.Error.AUTHENTIFICATION;
            case "account_rate" -> TrialJournal.Error.LIMITE;
            case "account_denied","account_wifi" -> TrialJournal.Error.AUTORISATION;
            case "storage","account_initialize" -> TrialJournal.Error.COFFRE;
            case "running" -> TrialJournal.Error.MOTEUR_ACTIF;
            case "account_protocol","account_region","account_redirect" -> TrialJournal.Error.REPONSE;
            case "account_input","equipment_ip","equipment_port","meross_settings","meross_duplicate" -> TrialJournal.Error.SAISIE;
            default -> TrialJournal.Error.RESEAU;
        };
    }
    static String export(Context c) throws Exception {
        String text;
        synchronized(TrialLog.class) { attach(c); text=journal.text(); }
        android.content.pm.PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
        long[] health=EngineHealth.snapshot();
        return "version="+info.versionName+" package="+c.getPackageName()+"\n"
                +"engine_starts="+c.getSharedPreferences("diagnostics",Context.MODE_PRIVATE).getLong("starts",0)+"\n"
                +"health_success="+health[2]+" health_failure="+health[3]+" max_check_gap_ms="+health[4]+"\n"
                +"Un intervalle sans contrôle ne prouve pas une panne. Lire le moteur ne force pas une requête fournisseur.\n"
                +"Aucun identifiant, secret, IP/MAC, URL privée, nom réseau ou position. Pseudonymes propres à cette session.\n"
                +text;
    }
}
