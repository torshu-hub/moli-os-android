package io.moli.android;

import org.json.*;
import java.util.*;

/** Durable compact summaries within the existing trial journal. Closed schema:
 * no identifier, sample value, provider text, URL or exception can be stored. */
final class TrialDiagnostics {
    enum Topic { NETATMO_CONFIGURATION, NETATMO_AUTHORIZATION, NETATMO_HOME, NETATMO_DRIVER,
        NETATMO_TOKENS, NETATMO_HOMESDATA, NETATMO_HOMESTATUS, NETATMO_PARSING, NETATMO_PUBLICATION,
        NETATMO_DATA, NETATMO_BOILER_FIELD, UI_DEVICES, UI_SYSTEM, NETATMO_TILE, UI_SCRIPT, UI_ASSISTANT }
    enum State { UNOBSERVED, ABSENT, DISABLED, PENDING, STORED, OK, FAILED, STALE }
    enum Reason { NONE, TRANSPORT, AUTHORIZATION, CREDENTIALS, AMBIGUOUS, RATE_LIMIT, STORAGE, INVALID_RESPONSE, PROVIDER, TIMEOUT, RENDER, HTTP, ENGINE_STOPPED }
    static final class Entry {
        State state=State.UNOBSERVED,terminal=State.UNOBSERVED;Reason reason=Reason.NONE,terminalReason=Reason.NONE;
        long a,b,attempts,successes,failures,lastAttempt,lastSuccess,lastError,lastSeen;
        long generation,sourceAttempts,sourceSuccesses,sourceFailures;
        JSONObject json()throws JSONException{return new JSONObject().put("state",state.name()).put("terminal",terminal.name()).put("reason",reason.name()).put("terminalReason",terminalReason.name())
            .put("a",a).put("b",b).put("attempts",attempts).put("successes",successes).put("failures",failures)
            .put("lastAttempt",lastAttempt).put("lastSuccess",lastSuccess).put("lastError",lastError).put("lastSeen",lastSeen)
            .put("generation",generation).put("sourceAttempts",sourceAttempts).put("sourceSuccesses",sourceSuccesses).put("sourceFailures",sourceFailures);}
    }
    final EnumMap<Topic,Entry> entries=new EnumMap<>(Topic.class);
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
        e.state=state;e.reason=reason;e.lastSeen=now;if(state!=State.PENDING){e.terminal=state;e.terminalReason=reason;e.a=a;e.b=b;}
        return changed;
    }
    private static long delta(long incoming,long previous){incoming=count(incoming);return incoming>=previous?incoming-previous:incoming;}
    JSONObject json()throws JSONException{JSONObject out=new JSONObject();for(Topic t:entries.keySet())out.put(t.name(),entries.get(t).json());return out;}
    static TrialDiagnostics parse(JSONObject root)throws JSONException{
        TrialDiagnostics out=new TrialDiagnostics();if(root==null)return out;
        for(Topic t:Topic.values()){
            JSONObject x=root.optJSONObject(t.name());if(x==null)continue;
            Entry e=new Entry();
            try{e.state=State.valueOf(x.optString("state"));e.terminal=State.valueOf(x.optString("terminal"));e.reason=Reason.valueOf(x.optString("reason"));e.terminalReason=Reason.valueOf(x.optString("terminalReason",e.reason.name()));}catch(IllegalArgumentException ignored){continue;}
            e.a=count(x.optLong("a"));e.b=count(x.optLong("b"));e.attempts=count(x.optLong("attempts"));e.successes=count(x.optLong("successes"));e.failures=count(x.optLong("failures"));
            e.lastAttempt=time(x.optLong("lastAttempt"));e.lastSuccess=time(x.optLong("lastSuccess"));e.lastError=time(x.optLong("lastError"));e.lastSeen=time(x.optLong("lastSeen"));
            e.generation=time(x.optLong("generation"));e.sourceAttempts=count(x.optLong("sourceAttempts"));e.sourceSuccesses=count(x.optLong("sourceSuccesses"));e.sourceFailures=count(x.optLong("sourceFailures"));out.entries.put(t,e);
        }return out;
    }
    String text(long now){
        StringBuilder out=new StringBuilder("\nRésumé diagnostic au moment de l’export — observations locales en cache, sans requête fournisseur.\n");
        out.append("STORED = autorisation enregistrée, pas une validation distante actuelle. a/b = compteurs ou indicateurs, jamais les mesures.\n");
        out.append("CONFIGURATION a=configurées b=activées ; AUTHORIZATION a=coffres avec jetons b=API confirmées ; HOME a=maisons sélectionnées ; DATA a=températures b=consignes ; BOILER_FIELD a=booléens retournés b=indéterminés ; PUBLICATION a=appareils publiés b=visibles dans /api/devices ; TILE a=cartes rendues b=cartes fraîches. Les dates décrivent des réponses cloud, pas une flamme réelle.\n");
        for(Topic topic:Topic.values()){
            Entry e=entries.get(topic);out.append(topic).append(" : ");
            if(e==null){out.append("UNOBSERVED\n");continue;}
            out.append(e.state).append(" cause=").append(e.reason).append(" a=").append(e.a).append(" b=").append(e.b)
                .append(" tentatives=").append(e.attempts).append(" succès=").append(e.successes).append(" échecs=").append(e.failures)
                .append(" dernière_tentative=").append(stamp(e.lastAttempt)).append(" dernier_succès=").append(stamp(e.lastSuccess))
                .append(" dernière_erreur=").append(stamp(e.lastError)).append(" âge_observation_s=").append(e.lastSeen>0?Math.max(0,(now-e.lastSeen)/1000):-1);
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
