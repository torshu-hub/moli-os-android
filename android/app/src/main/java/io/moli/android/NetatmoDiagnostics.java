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
        long[][] counts=new long[6][6];String[] outcomes=new String[6];
        long now=System.currentTimeMillis();
        for(int i=0;i<Math.min(64,diagnostics.length());i++){
            JSONObject row=diagnostics.optJSONObject(i);if(row==null||!"netatmo".equals(row.optString("kind")))continue;
            configured++;if(row.optBoolean("enabled"))enabled++;if(row.optBoolean("credential_present"))credentials++;if(row.optBoolean("target_selected"))homes++;if(row.optBoolean("driver_started"))started++;if(row.optBoolean("token_expired"))expired++;
            if(row.optLong("last_response_at")>0)confirmed++;generation=Math.max(generation,row.optLong("started_at"));
            JSONObject values=row.optJSONObject("summary");if(values!=null){returned+=values.optLong("devices");boiler+=values.optLong("demand_present");unknown+=values.optLong("demand_unknown");}
            JSONObject steps=row.optJSONObject("stages");
            for(int k=0;k<6;k++){
                JSONObject step=steps==null?null:steps.optJSONObject(stages[k]);if(step==null)continue;
                counts[k][0]+=Math.max(0,step.optLong("attempts"));counts[k][1]+=Math.max(0,step.optLong("successes"));counts[k][2]+=Math.max(0,step.optLong("failures"));
                counts[k][3]=Math.max(counts[k][3],step.optLong("last_attempt_at"));counts[k][4]=Math.max(counts[k][4],step.optLong("last_success_at"));counts[k][5]=Math.max(counts[k][5],step.optLong("last_error_at"));
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
        for(int k=0;k<6;k++){
            String outcome=outcomes[k]==null?"unobserved":outcomes[k];
            State state="ok".equals(outcome)?State.OK:"pending".equals(outcome)?State.PENDING:"unobserved".equals(outcome)?State.UNOBSERVED:State.FAILED;
            if(k==0&&credentials>0&&(counts[0][0]==0||"ok".equals(outcome)))state=confirmed>0?State.OK:State.STORED;
            if(k==1&&counts[1][0]==0)state=expired>0?State.STALE:State.UNOBSERVED;
            long a=k==0?credentials:k==5?returned:0,b=k==0?confirmed:k==5?seen:0;
            TrialLog.diagnostic(c,topics[k],state,reason(outcome),a,b,generation,counts[k][0],counts[k][1],counts[k][2],counts[k][3],counts[k][4],counts[k][5]);
        }
    }
    private static Object value(JSONObject state,String key){JSONObject item=state.optJSONObject(key);return item==null?null:item.opt("value");}
    private static long number(JSONObject state,String key){Object v=value(state,key);return v instanceof Number?((Number)v).longValue():0;}
    private static Reason reason(String value){return switch(value){case "transport"->Reason.TRANSPORT;case "authorization"->Reason.AUTHORIZATION;case "credentials"->Reason.CREDENTIALS;case "ambiguous"->Reason.AMBIGUOUS;case "rate_limit"->Reason.RATE_LIMIT;case "storage"->Reason.STORAGE;case "invalid_response"->Reason.INVALID_RESPONSE;case "provider"->Reason.PROVIDER;default->Reason.NONE;};}
    private static void flag(Context c,Topic topic,State state,long a,long b){TrialLog.diagnostic(c,topic,state,Reason.NONE,a,b,0,0,0,0,0,0,0);}
    private NetatmoDiagnostics(){}
}
