package io.moli.android;

import android.content.Context;
import org.json.*;
import java.util.*;
import static io.moli.android.TrialDiagnostics.*;

/** Compact command evidence in the existing trial report. Never stores a target,
 * address, secret, desired value, device measurement or provider message. */
final class MerossDiagnostics {
    private static final String[] STAGES={"command_preflight","command_send","command_ack","command_readback","parsing"};
    private static final Topic[] TOPICS={Topic.MEROSS_PREFLIGHT,Topic.MEROSS_SEND,Topic.MEROSS_ACK,Topic.MEROSS_READBACK,Topic.MEROSS_IDENTITY};
    static final class Step {
        State state;Reason reason,lastError;long attempts,successes,failures,attemptAt,successAt,errorAt,duration,interruptions;
        final EnumMap<Reason,Long> errors=new EnumMap<>(Reason.class);
    }
    static Step step(JSONObject raw) {
        if(raw==null)raw=new JSONObject();Step s=new Step();String outcome=raw.optString("status","unobserved");
        s.state=switch(outcome){case "ok"->State.OK;case "pending"->State.PENDING;case "unobserved"->State.UNOBSERVED;case "cancelled"->State.CANCELLED;default->State.FAILED;};
        s.reason=NetatmoDiagnostics.reason(outcome);s.lastError=NetatmoDiagnostics.reason(raw.optString("last_error"));
        s.attempts=count(raw.optLong("attempts"));s.successes=count(raw.optLong("successes"));s.failures=count(raw.optLong("failures"));
        s.attemptAt=time(raw.optLong("last_attempt_at"));s.successAt=time(raw.optLong("last_success_at"));s.errorAt=time(raw.optLong("last_error_at"));s.duration=count(raw.optLong("last_duration_ms"));s.interruptions=count(raw.optLong("interruptions"));
        JSONObject errors=raw.optJSONObject("errors");if(errors!=null)for(Reason r:Reason.values()){long n=count(errors.optLong(r.name().toLowerCase(Locale.ROOT)));if(n>0)s.errors.put(r,n);}
        return s;
    }
    static void observe(Context c,JSONObject config,JSONObject snapshot)throws JSONException {
        int selected=0;JSONArray plugs=config.optJSONArray("meross");if(plugs!=null)for(int i=0;i<Math.min(8,plugs.length());i++){JSONObject p=plugs.optJSONObject(i);if(p!=null&&p.optBoolean("manual_control"))selected++;}
        JSONArray rows=snapshot.optJSONArray("diagnostics");JSONObject row=null;
        if(rows!=null)for(int i=0;i<Math.min(64,rows.length());i++){JSONObject r=rows.optJSONObject(i);if(r!=null&&"meross".equals(r.optString("kind"))){row=r;break;}}
        boolean active=row!=null&&row.optBoolean("driver_started")&&row.optBoolean("target_selected");
        TrialLog.diagnostic(c,Topic.MEROSS_MANUAL_SELECTION,selected==0?State.DISABLED:active?State.OK:State.STORED,Reason.NONE,selected,active?1:0,0,0,0,0,0,0,0);
        if(row==null)return;long generation=row.optLong("counter_epoch");JSONObject stages=row.optJSONObject("stages");
        for(int i=0;i<STAGES.length;i++){
            Step s=step(stages==null?null:stages.optJSONObject(STAGES[i]));
            TrialLog.diagnostic(c,TOPICS[i],s.state,s.reason,0,0,generation,s.attempts,s.successes,s.failures,s.attemptAt,s.successAt,s.errorAt);
            TrialLog.diagnosticDetail(c,TOPICS[i],generation,s.lastError,s.duration,s.interruptions,s.errors);
        }
    }
    private MerossDiagnostics(){}
}
