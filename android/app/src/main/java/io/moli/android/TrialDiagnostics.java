package io.moli.android;

import org.json.*;
import java.util.*;

/** Durable compact summaries within the existing trial journal. Closed schema:
 * no identifier, sample value, provider text, URL or exception can be stored. */
final class TrialDiagnostics {
    enum Topic { NETATMO_CONFIGURATION, NETATMO_AUTHORIZATION, NETATMO_HOME, NETATMO_DRIVER,
        NETATMO_TOKENS, NETATMO_HOMESDATA, NETATMO_HOMESTATUS, NETATMO_PARSING, NETATMO_PUBLICATION,
        NETATMO_DATA, NETATMO_BOILER_FIELD, UI_DEVICES, UI_SYSTEM, NETATMO_TILE, UI_SCRIPT, UI_ASSISTANT, NETATMO_POLLING }
    enum State { UNOBSERVED, ABSENT, DISABLED, PENDING, STORED, OK, FAILED, STALE, CANCELLED }
    enum Reason { NONE, TRANSPORT, AUTHORIZATION, CREDENTIALS, AMBIGUOUS, RATE_LIMIT, STORAGE, INVALID_RESPONSE, PROVIDER, TIMEOUT, RENDER, HTTP, ENGINE_STOPPED, DNS, CONNECT, TLS, HEADERS, BODY, CYCLE_TIMEOUT, CANCELLED }
    static final class Entry {
        State state=State.UNOBSERVED,terminal=State.UNOBSERVED;Reason reason=Reason.NONE,terminalReason=Reason.NONE;
        long a,b,attempts,successes,failures,lastAttempt,lastSuccess,lastError,lastSeen;
        long generation,sourceAttempts,sourceSuccesses,sourceFailures;
        long tracedAt,coalesced;
        Reason lastErrorReason=Reason.NONE;
        long duration,interruptions,errorGeneration;
        final EnumMap<Reason,Long> errors=new EnumMap<>(Reason.class),sourceErrors=new EnumMap<>(Reason.class);
        JSONObject json()throws JSONException{return new JSONObject().put("state",state.name()).put("terminal",terminal.name()).put("reason",reason.name()).put("terminalReason",terminalReason.name())
            .put("a",a).put("b",b).put("attempts",attempts).put("successes",successes).put("failures",failures)
            .put("lastAttempt",lastAttempt).put("lastSuccess",lastSuccess).put("lastError",lastError).put("lastSeen",lastSeen)
            .put("generation",generation).put("sourceAttempts",sourceAttempts).put("sourceSuccesses",sourceSuccesses).put("sourceFailures",sourceFailures).put("tracedAt",tracedAt).put("coalesced",coalesced)
            .put("lastErrorReason",lastErrorReason.name()).put("duration",duration).put("interruptions",interruptions).put("errorGeneration",errorGeneration).put("errors",reasonsJson(errors)).put("sourceErrors",reasonsJson(sourceErrors));}
    }
    final EnumMap<Topic,Entry> entries=new EnumMap<>(Topic.class);
    boolean trace(Topic topic,State state,long now){
        Entry e=entries.get(topic);if(e==null)return false;
        if(state==State.PENDING)return e.lastAttempt==e.lastSeen&&e.attempts<=1;
        if(e.tracedAt==0||now-e.tracedAt>=60000){e.tracedAt=now;return true;}
        e.coalesced=count(e.coalesced+1);return false;
    }
    enum Trigger { INITIAL, DEADLINE, WALL_DEADLINE, UNOBSERVED }
    static final class Polling {
        static final String[] COUNTS={"period_min","period_max","delay_seconds","cycles","automatic_cycles","successes","failures","interruptions","last_gap_ms","late_ms","last_duration_ms","total_cycles","total_automatic_cycles","total_successes","total_failures","total_interruptions"};
        static final String[] TIMES={"last_cycle_at","last_automatic_at","next_due_at","observed_at","generation","last_finished_at","last_error_at","last_automatic_observed_at","cycle_due_at"};
        final JSONObject values=new JSONObject();Reason reason=Reason.NONE,lastError=Reason.NONE;Trigger trigger=Trigger.UNOBSERVED;boolean inFlight;
        static Polling parse(JSONObject raw)throws JSONException {
            Polling p=new Polling();if(raw==null)return p;
            for(String key:COUNTS)p.values.put(key,count(raw.optLong(key)));
            for(String key:TIMES){long n=raw.optLong(key);p.values.put(key,key.equals("next_due_at")?Math.max(0,Math.min(System.currentTimeMillis()+604860000,n)):time(n));}
            try{p.reason=Reason.valueOf(raw.optString("reason","NONE"));}catch(IllegalArgumentException ignored){}
            try{p.lastError=Reason.valueOf(raw.optString("last_error","NONE"));}catch(IllegalArgumentException ignored){}
            try{p.trigger=Trigger.valueOf(raw.optString("trigger","UNOBSERVED"));}catch(IllegalArgumentException ignored){}
            p.inFlight=raw.optBoolean("in_flight");return p;
        }
        static Polling merge(Polling old,JSONObject raw)throws JSONException{
            if(raw.optLong("observed_at")==0)return old;
            Polling p=parse(raw);boolean reset=p.values.optLong("generation")!=old.values.optLong("generation");
            for(String key:new String[]{"cycles","automatic_cycles","successes","failures","interruptions"})
                p.values.put("total_"+key,count(old.values.optLong("total_"+key)+delta(p.values.optLong(key),reset?0:old.values.optLong(key))));
            p.values.put("last_automatic_observed_at",Math.max(old.values.optLong("last_automatic_observed_at"),p.values.optLong("last_automatic_at")));
            if(p.values.optLong("last_error_at")<old.values.optLong("last_error_at")){p.values.put("last_error_at",old.values.optLong("last_error_at"));p.lastError=old.lastError;}
            return p;
        }
        JSONObject json()throws JSONException{return new JSONObject(values.toString()).put("reason",reason.name()).put("last_error",lastError.name()).put("trigger",trigger.name()).put("in_flight",inFlight);}
        String text(long now){
            if(values.optLong("observed_at")==0)return "CADENCE_NETATMO : UNOBSERVED\n";
            return "CADENCE_NETATMO : fréquence_configurée_s="+values.optLong("period_min")+".."+values.optLong("period_max")
                +" cycles_moteur="+values.optLong("cycles")+" cycles_automatiques="+values.optLong("automatic_cycles")
                +" total_cycles_observés="+values.optLong("total_cycles")+" total_automatiques_observés="+values.optLong("total_automatic_cycles")
                +" dernier_automatique_observé="+stamp(values.optLong("last_automatic_observed_at"))
                +" dernier_cycle="+stamp(values.optLong("last_cycle_at"))+" dernier_automatique="+stamp(values.optLong("last_automatic_at"))
                +" intervalle_effectif_max_ms="+values.optLong("last_gap_ms")+" prochaine_échéance="+stamp(values.optLong("next_due_at"))
                +" délai_programmé_s="+values.optLong("delay_seconds")+" retard_ms="+values.optLong("late_ms")
                +" échéance_du_cycle="+stamp(values.optLong("cycle_due_at"))+" fin_cycle="+stamp(values.optLong("last_finished_at"))+" durée_cycle_ms="+values.optLong("last_duration_ms")
                +" total_interruptions_observées="+values.optLong("total_interruptions")+" dernière_cause_erreur="+lastError+" dernière_erreur="+stamp(values.optLong("last_error_at"))
                +" déclencheur="+trigger+" report="+reason+" en_cours="+inFlight+" âge_snapshot_s="+Math.max(0,(now-values.optLong("observed_at"))/1000)
                +(inFlight&&now-values.optLong("last_cycle_at")>45000?" CYCLE_NON_TERMINE":"")+"\n";
        }
    }
    Polling polling=new Polling();
    void engineStopped(long now)throws JSONException{
        for(Topic t:Topic.values()){
            Entry e=entries.get(t);if(e!=null&&e.state==State.PENDING){e.state=State.CANCELLED;e.reason=Reason.ENGINE_STOPPED;e.interruptions=count(e.interruptions+1);e.lastSeen=now;}
        }
        if(polling.inFlight){
            polling.values.put("interruptions",count(polling.values.optLong("interruptions")+1)).put("total_interruptions",count(polling.values.optLong("total_interruptions")+1)).put("last_finished_at",now);
            polling.reason=Reason.CANCELLED;
        }
        polling.inFlight=false;polling.values.put("next_due_at",0);
    }
    static long count(long n){return Math.max(0,Math.min(1_000_000_000L,n));}
    static long time(long n){return Math.max(0,Math.min(System.currentTimeMillis()+300000,n));}
    boolean observe(long now,Topic topic,State state,Reason reason,long a,long b,long generation,long attempts,long successes,long failures,long attemptAt,long successAt,long errorAt){
        Entry e=entries.computeIfAbsent(topic,k->new Entry());a=count(a);b=count(b);
        boolean changed=state!=State.PENDING&&(e.terminal!=state||e.terminalReason!=reason||e.a!=a||e.b!=b);
        if(state==State.PENDING&&e.lastSeen==0)changed=true;
        if(generation<0){
            if(state==State.PENDING){e.attempts=count(e.attempts+1);e.lastAttempt=now;}
            else if(state==State.OK||state==State.STORED){e.successes=count(e.successes+1);e.lastSuccess=now;}
            else if(state==State.FAILED){e.failures=count(e.failures+1);e.lastError=now;}
        }else{
            boolean reset=e.generation!=generation;
            e.attempts=count(e.attempts+delta(attempts,reset?0:e.sourceAttempts));
            e.successes=count(e.successes+delta(successes,reset?0:e.sourceSuccesses));
            e.failures=count(e.failures+delta(failures,reset?0:e.sourceFailures));
            e.generation=generation;e.sourceAttempts=count(attempts);e.sourceSuccesses=count(successes);e.sourceFailures=count(failures);
            e.lastAttempt=Math.max(e.lastAttempt,time(attemptAt));e.lastSuccess=Math.max(e.lastSuccess,time(successAt));e.lastError=Math.max(e.lastError,time(errorAt));
        }
        if(state==State.FAILED){e.lastErrorReason=reason;}
        e.state=state;e.reason=reason;e.lastSeen=now;if(state!=State.PENDING){e.terminal=state;e.terminalReason=reason;e.a=a;e.b=b;}
        return changed;
    }
    void detail(Topic topic,long generation,Reason lastError,long duration,long interruptions,EnumMap<Reason,Long> incoming){
        Entry e=entries.get(topic);if(e==null)return;
        if(lastError!=Reason.NONE)e.lastErrorReason=lastError;e.duration=count(duration);e.interruptions=count(interruptions);
        for(Reason r:Reason.values()){
            long n=incoming.getOrDefault(r,0L),previous=e.errorGeneration==generation?e.sourceErrors.getOrDefault(r,0L):0;
            e.errors.put(r,count(e.errors.getOrDefault(r,0L)+delta(n,previous)));e.sourceErrors.put(r,count(n));
        }
        e.errorGeneration=generation;
    }
    private static JSONObject reasonsJson(EnumMap<Reason,Long> counts)throws JSONException{JSONObject out=new JSONObject();for(Reason r:counts.keySet())if(counts.get(r)>0)out.put(r.name(),counts.get(r));return out;}
    private static void readReasons(JSONObject raw,EnumMap<Reason,Long> out){if(raw!=null)for(Reason r:Reason.values())if(raw.has(r.name()))out.put(r,count(raw.optLong(r.name())));}
    private static long delta(long incoming,long previous){incoming=count(incoming);return incoming>=previous?incoming-previous:incoming;}
    JSONObject json()throws JSONException{JSONObject out=new JSONObject().put("polling",polling.json());for(Topic t:entries.keySet())out.put(t.name(),entries.get(t).json());return out;}
    static TrialDiagnostics parse(JSONObject root)throws JSONException{
        TrialDiagnostics out=new TrialDiagnostics();if(root==null)return out;
        out.polling=Polling.parse(root.optJSONObject("polling"));
        for(Topic t:Topic.values()){
            JSONObject x=root.optJSONObject(t.name());if(x==null)continue;
            Entry e=new Entry();
            try{e.state=State.valueOf(x.optString("state"));e.terminal=State.valueOf(x.optString("terminal"));e.reason=Reason.valueOf(x.optString("reason"));e.terminalReason=Reason.valueOf(x.optString("terminalReason",e.reason.name()));}catch(IllegalArgumentException ignored){continue;}
            e.a=count(x.optLong("a"));e.b=count(x.optLong("b"));e.attempts=count(x.optLong("attempts"));e.successes=count(x.optLong("successes"));e.failures=count(x.optLong("failures"));
            e.lastAttempt=time(x.optLong("lastAttempt"));e.lastSuccess=time(x.optLong("lastSuccess"));e.lastError=time(x.optLong("lastError"));e.lastSeen=time(x.optLong("lastSeen"));
            e.generation=time(x.optLong("generation"));e.sourceAttempts=count(x.optLong("sourceAttempts"));e.sourceSuccesses=count(x.optLong("sourceSuccesses"));e.sourceFailures=count(x.optLong("sourceFailures"));out.entries.put(t,e);
            e.tracedAt=time(x.optLong("tracedAt"));e.coalesced=count(x.optLong("coalesced"));
            try{e.lastErrorReason=Reason.valueOf(x.optString("lastErrorReason","NONE"));}catch(IllegalArgumentException ignored){}
            e.duration=count(x.optLong("duration"));e.interruptions=count(x.optLong("interruptions"));e.errorGeneration=time(x.optLong("errorGeneration"));readReasons(x.optJSONObject("errors"),e.errors);readReasons(x.optJSONObject("sourceErrors"),e.sourceErrors);
        }return out;
    }
    String text(long now){
        StringBuilder out=new StringBuilder("\nRésumé diagnostic au moment de l’export — observations locales en cache, sans requête fournisseur.\n");
        out.append("STORED = autorisation enregistrée, pas une validation distante actuelle. a/b = compteurs ou indicateurs, jamais les mesures.\n");
        out.append(polling.text(now));
        out.append("La cadence décrit le dernier instantané du moteur ; une échéance passée n’est pas une preuve de blocage si le moteur est arrêté ou l’observation ancienne. WALL_DEADLINE = reprise sur échéance UTC (suspension ou correction d’horloge possible), sans rattrapage en rafale.\n");
        out.append("CONFIGURATION a=configurées b=activées ; AUTHORIZATION a=coffres avec jetons b=API confirmées ; HOME a=maisons sélectionnées ; DATA a=températures b=consignes ; BOILER_FIELD a=booléens retournés b=indéterminés ; PUBLICATION a=appareils publiés b=visibles dans /api/devices ; TILE a=cartes rendues b=cartes fraîches. Les dates décrivent des réponses cloud, pas une flamme réelle.\n");
        for(Topic topic:Topic.values()){
            Entry e=entries.get(topic);out.append(topic).append(" : ");
            if(e==null){out.append("UNOBSERVED\n");continue;}
            out.append(e.state).append(" cause=").append(e.reason).append(" a=").append(e.a).append(" b=").append(e.b)
                .append(" tentatives=").append(e.attempts).append(" succès=").append(e.successes).append(" échecs=").append(e.failures)
                .append(" dernière_tentative=").append(stamp(e.lastAttempt)).append(" dernier_succès=").append(stamp(e.lastSuccess))
                .append(" dernière_erreur=").append(stamp(e.lastError)).append(" âge_observation_s=").append(e.lastSeen>0?Math.max(0,(now-e.lastSeen)/1000):-1);
            if(e.lastError>0)out.append(" dernière_cause_erreur=").append(e.lastErrorReason);
            if(e.duration>0)out.append(" durée_dernière_étape_ms=").append(e.duration);
            if(e.interruptions>0)out.append(" interruptions_moteur=").append(e.interruptions);
            for(Reason r:e.errors.keySet())if(e.errors.get(r)>0)out.append(" erreurs_").append(r).append('=').append(e.errors.get(r));
            if(e.coalesced>0)out.append(" transitions_regroupées=").append(e.coalesced);
            if(e.state==State.PENDING&&e.lastAttempt>0&&now-e.lastAttempt>15000)out.append(" CHARGEMENT_NON_TERMINE");
            out.append('\n');
        }
        Entry published=entries.get(Topic.NETATMO_PUBLICATION),tile=entries.get(Topic.NETATMO_TILE);
        if(published!=null&&published.b>0&&(tile==null||tile.a==0))out.append("NETATMO_VISIBILITE : appareils présents dans le hub, accueil/tuile non observé ou non rendu.\n");
        return out.toString();
    }
    private static String stamp(long at){return at>0?java.time.Instant.ofEpochMilli(at).toString():"non_observé";}
    private TrialDiagnostics(){}
    static TrialDiagnostics empty(){return new TrialDiagnostics();}
}
