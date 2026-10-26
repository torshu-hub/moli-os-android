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
    static final class Summary {
        long generation;int active,rows;
        final Step[] stages=new Step[STAGES.length];
        Summary(){for(int i=0;i<stages.length;i++)stages[i]=step(null);}
    }
    static Summary aggregate(JSONArray rows) {
        Summary out=new Summary();
        if(rows==null)return out;
        for(int n=0;n<Math.min(64,rows.length());n++) {
            JSONObject row=rows.optJSONObject(n);if(row==null||!"meross".equals(row.optString("kind")))continue;
            long epoch=time(row.optLong("counter_epoch"));if(epoch==0)continue;
            // Registry entries and their epochs live for the whole hub lifetime,
            // including supervised restarts. Later arrivals cannot reset totals.
            out.generation=out.rows++==0?epoch:Math.min(out.generation,epoch);
            if(row.optBoolean("driver_started")&&row.optBoolean("target_selected"))out.active++;
            JSONObject stages=row.optJSONObject("stages");
            for(int i=0;i<STAGES.length;i++)merge(out.stages[i],step(stages==null?null:stages.optJSONObject(STAGES[i])));
        }
        return out;
    }
    private static void merge(Step total,Step next) {
        // Pending wins while any source is pending; otherwise show the latest
        // terminal observation. Cumulative errors from every source remain visible.
        if(next.state==State.PENDING || total.state!=State.PENDING &&
                (next.attemptAt>total.attemptAt || next.attemptAt==total.attemptAt && next.state==State.FAILED)) {
            total.state=next.state;total.reason=next.reason;total.duration=next.duration;
        }
        if(next.errorAt>=total.errorAt&&next.errorAt>0)total.lastError=next.lastError;
        total.attempts=count(total.attempts+next.attempts);total.successes=count(total.successes+next.successes);total.failures=count(total.failures+next.failures);
        total.attemptAt=Math.max(total.attemptAt,next.attemptAt);total.successAt=Math.max(total.successAt,next.successAt);total.errorAt=Math.max(total.errorAt,next.errorAt);
        total.interruptions=count(total.interruptions+next.interruptions);
        for(Reason reason:next.errors.keySet())total.errors.put(reason,count(total.errors.getOrDefault(reason,0L)+next.errors.get(reason)));
    }
    static void observe(Context c,JSONObject config,JSONObject snapshot)throws JSONException {
        int selected=0;JSONArray plugs=config.optJSONArray("meross");if(plugs!=null)for(int i=0;i<Math.min(8,plugs.length());i++){JSONObject p=plugs.optJSONObject(i);if(p!=null&&p.optBoolean("manual_control"))selected++;}
        Summary summary=aggregate(snapshot.optJSONArray("diagnostics"));
        TrialLog.diagnostic(c,Topic.MEROSS_MANUAL_SELECTION,selected==0?State.DISABLED:summary.active>=selected?State.OK:State.STORED,Reason.NONE,selected,summary.active,0,0,0,0,0,0,0);
        if(summary.rows==0)return;
        for(int i=0;i<STAGES.length;i++){
            Step s=summary.stages[i];
            TrialLog.diagnostic(c,TOPICS[i],s.state,s.reason,0,0,summary.generation,s.attempts,s.successes,s.failures,s.attemptAt,s.successAt,s.errorAt);
            TrialLog.diagnosticDetail(c,TOPICS[i],summary.generation,s.lastError,s.duration,s.interruptions,s.errors);
        }
    }
    private MerossDiagnostics(){}
}
