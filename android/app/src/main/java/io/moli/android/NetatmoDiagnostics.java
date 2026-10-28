package io.moli.android;

import android.content.Context;
import org.json.*;
import static io.moli.android.TrialDiagnostics.*;

/** Reduces the authenticated local snapshot to flags/counts. Never exports the
 * instance, home/module names, IDs, sample values, errors or provider payload. */
final class NetatmoDiagnostics {
    static void observe(Context c,JSONObject snapshot)throws JSONException {
        TrialLog.diagnosticBatch(c,()->observeSnapshot(c,snapshot));
    }
    private static void observeSnapshot(Context c,JSONObject snapshot)throws JSONException {
        JSONArray diagnostics=snapshot.optJSONArray("diagnostics"),devices=snapshot.optJSONArray("devices");
        if(diagnostics==null)return;
        long configured=0,enabled=0,credentials=0,homes=0,started=0,expired=0,confirmed=0,generation=0,returned=0,seen=0,temperatures=0,setpoints=0,boiler=0,unknown=0,fresh=0;
        String[] stages={"authorization","renewal","homesdata","homestatus","parsing","publication"};
        Topic[] topics={Topic.NETATMO_AUTHORIZATION,Topic.NETATMO_TOKENS,Topic.NETATMO_HOMESDATA,Topic.NETATMO_HOMESTATUS,Topic.NETATMO_PARSING,Topic.NETATMO_PUBLICATION};
        long[][] counts=new long[6][9];String[] outcomes=new String[6];Reason[] lastErrors=new Reason[6];
        java.util.ArrayList<java.util.EnumMap<Reason,Long>> errors=new java.util.ArrayList<>();for(int k=0;k<6;k++)errors.add(new java.util.EnumMap<>(Reason.class));
        long now=System.currentTimeMillis();
        JSONObject polling=new JSONObject();long minPeriod=0,maxPeriod=0,nextDue=0,pollCycles=0,pollAutomatic=0,pollManual=0,pollSuccess=0,pollFailure=0,lastCycle=0,lastAuto=0,gap=0,late=0,delay=0,finished=0,duration=0,interruptions=0,pollErrorAt=0,cycleDue=0;Reason lastPollError=Reason.NONE;boolean inFlight=false;Reason pollReason=Reason.NONE;String trigger="UNOBSERVED";
        for(int i=0;i<Math.min(64,diagnostics.length());i++){
            JSONObject row=diagnostics.optJSONObject(i);if(row==null||!"netatmo".equals(row.optString("kind")))continue;
            configured++;if(row.optBoolean("enabled"))enabled++;if(row.optBoolean("credential_present"))credentials++;if(row.optBoolean("target_selected"))homes++;if(row.optBoolean("driver_started"))started++;if(row.optBoolean("token_expired"))expired++;
            if(row.optLong("last_response_at")>0)confirmed++;generation=Math.max(generation,row.optLong("counter_epoch",row.optLong("started_at")));
            JSONObject p=row.optJSONObject("polling");if(p!=null&&p.optLong("period_seconds")>0){
                long period=p.optLong("period_seconds");minPeriod=minPeriod==0?period:Math.min(minPeriod,period);maxPeriod=Math.max(maxPeriod,period);
                long due=p.optLong("next_due_at");if(due>0)nextDue=nextDue==0?due:Math.min(nextDue,due);
                pollCycles+=p.optLong("cycles");pollAutomatic+=p.optLong("automatic_cycles");pollManual+=p.optLong("manual_cycles");pollSuccess+=p.optLong("successes");pollFailure+=p.optLong("failures");
                if(p.optLong("last_cycle_at")>=lastCycle){lastCycle=p.optLong("last_cycle_at");trigger=switch(p.optString("trigger")){case "initial"->"INITIAL";case "deadline"->"DEADLINE";case "wall_deadline"->"WALL_DEADLINE";case "manual"->"MANUAL";default->"UNOBSERVED";};}
                lastAuto=Math.max(lastAuto,p.optLong("last_automatic_at"));gap=Math.max(gap,p.optLong("last_gap_ms"));late=Math.max(late,p.optLong("late_ms"));delay=Math.max(delay,p.optLong("delay_seconds"));inFlight|=p.optBoolean("in_flight");
                Reason r=reason(p.optString("reason"));if(r!=Reason.NONE)pollReason=r;
                finished=Math.max(finished,p.optLong("last_finished_at"));duration=Math.max(duration,p.optLong("last_duration_ms"));interruptions+=p.optLong("interruptions");cycleDue=Math.max(cycleDue,p.optLong("cycle_due_at"));
                if(p.optLong("last_error_at")>=pollErrorAt){pollErrorAt=p.optLong("last_error_at");lastPollError=reason(p.optString("last_error"));}
            }
            JSONObject values=row.optJSONObject("summary");if(values!=null){returned+=values.optLong("devices");boiler+=values.optLong("demand_present");unknown+=values.optLong("demand_unknown");}
            JSONObject steps=row.optJSONObject("stages");
            for(int k=0;k<6;k++){
                JSONObject step=steps==null?null:steps.optJSONObject(stages[k]);if(step==null)continue;
                counts[k][0]+=Math.max(0,step.optLong("attempts"));counts[k][1]+=Math.max(0,step.optLong("successes"));counts[k][2]+=Math.max(0,step.optLong("failures"));
                counts[k][3]=Math.max(counts[k][3],step.optLong("last_attempt_at"));counts[k][4]=Math.max(counts[k][4],step.optLong("last_success_at"));counts[k][5]=Math.max(counts[k][5],step.optLong("last_error_at"));
                if(step.optLong("last_error_at")>=counts[k][6]){counts[k][6]=step.optLong("last_error_at");lastErrors[k]=reason(step.optString("last_error"));}
                counts[k][7]=Math.max(counts[k][7],step.optLong("last_duration_ms"));counts[k][8]+=step.optLong("interruptions");
                JSONObject reasons=step.optJSONObject("errors");if(reasons!=null)for(Reason r:Reason.values()){long n=reasons.optLong(r.name().toLowerCase(java.util.Locale.ROOT));if(n>0)errors.get(k).put(r,errors.get(k).getOrDefault(r,0L)+n);}
                String result=step.optString("status");if(outcomes[k]==null||"ok".equals(outcomes[k])||"unobserved".equals(outcomes[k]))outcomes[k]=result;
            }
            if(devices!=null)for(int d=0;d<devices.length();d++){
                JSONObject device=devices.optJSONObject(d);if(device==null||!row.optString("instance").equals(device.optString("instance")))continue;seen++;
                JSONObject state=device.optJSONObject("state");if(state==null)continue;
                boolean current=device.optBoolean("online")&&"cloud_recent".equals(value(state,"freshness"));
                long collected=number(state,"collected_at"),server=number(state,"server_at"),age=number(state,"max_age_seconds")*1000;
                current=current&&age>0&&collected>0&&server>0&&collected<=now&&server<=now&&now-collected<=age&&now-server<=age;
                if(current){fresh++;if(value(state,"temperature") instanceof Number)temperatures++;if(value(state,"setpoint_temperature") instanceof Number)setpoints++;}
            }
        }
        flag(c,Topic.NETATMO_CONFIGURATION,configured>0?(enabled>0?State.OK:State.DISABLED):State.ABSENT,configured,enabled);
        flag(c,Topic.NETATMO_HOME,homes>0?State.STORED:State.ABSENT,homes,configured);
        flag(c,Topic.NETATMO_DRIVER,started>0?State.OK:State.ABSENT,started,enabled);
        flag(c,Topic.NETATMO_DATA,seen==0?State.ABSENT:fresh>0?State.OK:State.STALE,temperatures,setpoints);
        flag(c,Topic.NETATMO_BOILER_FIELD,boiler>0?State.OK:State.ABSENT,boiler,unknown);
        polling.put("period_min",minPeriod).put("period_max",maxPeriod).put("delay_seconds",delay).put("cycles",pollCycles).put("automatic_cycles",pollAutomatic).put("manual_cycles",pollManual).put("successes",pollSuccess).put("failures",pollFailure)
            .put("last_cycle_at",lastCycle).put("last_automatic_at",lastAuto).put("next_due_at",nextDue).put("last_gap_ms",gap).put("late_ms",late).put("trigger",trigger).put("reason",pollReason.name()).put("in_flight",inFlight).put("observed_at",minPeriod>0?now:0).put("generation",generation).put("last_finished_at",finished).put("last_duration_ms",duration).put("interruptions",interruptions).put("last_error_at",pollErrorAt).put("last_error",lastPollError.name()).put("cycle_due_at",cycleDue);
        TrialLog.polling(c,polling);
        TrialLog.diagnostic(c,Topic.NETATMO_POLLING,minPeriod==0?State.UNOBSERVED:inFlight?State.PENDING:pollReason==Reason.CANCELLED?State.CANCELLED:pollReason==Reason.NONE?State.OK:State.FAILED,pollReason,minPeriod,delay,generation,pollCycles,pollSuccess,pollFailure,lastCycle,pollReason==Reason.NONE?finished:0,pollErrorAt);
        TrialLog.diagnosticDetail(c,Topic.NETATMO_POLLING,generation,lastPollError,duration,interruptions,new java.util.EnumMap<>(Reason.class));
        for(int k=0;k<6;k++){
            String outcome=outcomes[k]==null?"unobserved":outcomes[k];
            State state="ok".equals(outcome)?State.OK:"pending".equals(outcome)?State.PENDING:"unobserved".equals(outcome)?State.UNOBSERVED:"cancelled".equals(outcome)?State.CANCELLED:State.FAILED;
            if(k==0&&credentials>0&&(counts[0][0]==0||"ok".equals(outcome)))state=confirmed>0?State.OK:State.STORED;
            if(k==1&&counts[1][0]==0)state=expired>0?State.STALE:State.UNOBSERVED;
            long a=k==0?credentials:k==5?returned:0,b=k==0?confirmed:k==5?seen:0;
            TrialLog.diagnostic(c,topics[k],state,reason(outcome),a,b,generation,counts[k][0],counts[k][1],counts[k][2],counts[k][3],counts[k][4],counts[k][5]);
            TrialLog.diagnosticDetail(c,topics[k],generation,lastErrors[k]==null?Reason.NONE:lastErrors[k],counts[k][7],counts[k][8],errors.get(k));
        }
    }
    private static Object value(JSONObject state,String key){JSONObject item=state.optJSONObject(key);return item==null?null:item.opt("value");}
    private static long number(JSONObject state,String key){Object v=value(state,key);return v instanceof Number?((Number)v).longValue():0;}
    static Reason reason(String value){return switch(value){case "transport"->Reason.TRANSPORT;case "timeout"->Reason.TIMEOUT;case "dns"->Reason.DNS;case "connect"->Reason.CONNECT;case "tls"->Reason.TLS;case "headers"->Reason.HEADERS;case "body"->Reason.BODY;case "cycle_timeout"->Reason.CYCLE_TIMEOUT;case "cancelled"->Reason.CANCELLED;case "authorization"->Reason.AUTHORIZATION;case "credentials"->Reason.CREDENTIALS;case "ambiguous"->Reason.AMBIGUOUS;case "rate_limit"->Reason.RATE_LIMIT;case "storage"->Reason.STORAGE;case "invalid_response"->Reason.INVALID_RESPONSE;case "provider"->Reason.PROVIDER;default->Reason.NONE;};}
    private static void flag(Context c,Topic topic,State state,long a,long b){TrialLog.diagnostic(c,topic,state,Reason.NONE,a,b,0,0,0,0,0,0,0);}
    private NetatmoDiagnostics(){}
}
