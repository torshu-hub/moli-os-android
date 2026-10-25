package io.moli.android;
import org.json.*;import org.junit.Test;import static org.junit.Assert.*;
import static io.moli.android.TrialDiagnostics.*;
public final class TrialDiagnosticsTest {
 @Test public void pollingDeadlineAndQuotaSurviveRoundTripWithoutPrivateFields()throws Exception{
  long now=System.currentTimeMillis();JSONObject raw=new JSONObject().put("period_min",120).put("period_max",120).put("cycles",4).put("automatic_cycles",3).put("next_due_at",now+3600000).put("last_cycle_at",now).put("last_automatic_at",now).put("last_gap_ms",120000).put("reason","RATE_LIMIT").put("trigger","DEADLINE").put("observed_at",now).put("private","secret@example.com");
  TrialDiagnostics d=TrialDiagnostics.empty();d.polling=Polling.parse(raw);
  TrialDiagnostics read=TrialDiagnostics.parse(d.json());assertEquals(now+3600000,read.polling.values.getLong("next_due_at"));
  assertTrue(read.text(now).contains("cycles_automatiques=3"));assertTrue(read.text(now).contains("report=RATE_LIMIT"));assertFalse(read.json().toString().contains("secret@example.com"));
 }
 @Test public void flappingDiagnosticsCoalesceTraceButKeepCounters()throws Exception{
  TrialDiagnostics d=TrialDiagnostics.empty();int traces=0;
  for(int i=1;i<=500;i++){State s=i%2==0?State.OK:State.FAILED;
   if(d.observe(i*100,Topic.UI_SCRIPT,s,Reason.RENDER,0,0,-1,0,0,0,0,0,0)&&d.trace(Topic.UI_SCRIPT,s,i*100))traces++;
  }
  assertEquals(1,traces);assertEquals(250,d.entries.get(Topic.UI_SCRIPT).failures);assertTrue(d.entries.get(Topic.UI_SCRIPT).coalesced>400);
 }
 @Test public void repeatedPollsKeepCountersAndLastTimesWithoutRepeatingTransitions()throws Exception{
  TrialDiagnostics d=TrialDiagnostics.empty();int transitions=0;
  for(int i=1;i<=500;i++){
   if(d.observe(i*1000,Topic.UI_SYSTEM,State.PENDING,Reason.NONE,0,0,-1,0,0,0,0,0,0))transitions++;
   if(d.observe(i*1000+1,Topic.UI_SYSTEM,State.OK,Reason.NONE,3,0,-1,0,0,0,0,0,0))transitions++;
  }
  assertEquals(2,transitions);Entry e=d.entries.get(Topic.UI_SYSTEM);assertEquals(500,e.attempts);assertEquals(500,e.successes);
  assertTrue(d.observe(600000,Topic.UI_SYSTEM,State.FAILED,Reason.TIMEOUT,0,0,-1,0,0,0,0,0,0));
  for(int i=0;i<10;i++){d.observe(600001+i,Topic.UI_SYSTEM,State.PENDING,Reason.NONE,0,0,-1,0,0,0,0,0,0);assertFalse(d.observe(600002+i,Topic.UI_SYSTEM,State.FAILED,Reason.TIMEOUT,0,0,-1,0,0,0,0,0,0));}
  assertEquals(11,d.entries.get(Topic.UI_SYSTEM).failures);
 }
 @Test public void summariesSurviveEventTruncationAndOnlyAcceptFixedFields()throws Exception{
  TrialJournal j=new TrialJournal();
  j.diagnostics.observe(10000,Topic.NETATMO_HOMESTATUS,State.OK,Reason.NONE,0,0,9000,8,7,1,9990,10000,9500);
  for(int i=0;i<400;i++)j.add(10001+i,TrialJournal.Event.ENGINE_READ_OK,i,0);
  JSONObject root=new JSONObject(j.json());root.getJSONObject("diagnostics").getJSONObject("NETATMO_HOMESTATUS").put("raw","secret@example.com callback=private-token");
  root.getJSONObject("diagnostics").put("private-house",new JSONObject().put("payload","secret@example.com"));
  TrialJournal restored=TrialJournal.parse(root.toString());String text=restored.text();
  assertEquals(144,restored.dropped);assertTrue(text.contains("NETATMO_HOMESTATUS : OK"));assertTrue(text.contains("tentatives=8 succès=7 échecs=1"));
  assertFalse(restored.json().contains("secret@example.com"));assertFalse(text.contains("private-house"));assertFalse(text.contains("private-token"));
  restored.diagnostics.observe(20000,Topic.NETATMO_HOMESTATUS,State.OK,Reason.NONE,0,0,19000,1,1,0,20000,20000,0);
  assertEquals(9,restored.diagnostics.entries.get(Topic.NETATMO_HOMESTATUS).attempts);
 }
 @Test public void exportDistinguishesHungUiAndPublishedButUnobservedTile(){
  TrialDiagnostics d=TrialDiagnostics.empty();d.observe(1000,Topic.UI_SYSTEM,State.PENDING,Reason.NONE,0,0,-1,0,0,0,0,0,0);
  d.observe(1000,Topic.NETATMO_PUBLICATION,State.OK,Reason.NONE,2,2,1,1,1,0,1000,1000,0);
  String text=d.text(30000);assertTrue(text.contains("CHARGEMENT_NON_TERMINE"));assertTrue(text.contains("NETATMO_VISIBILITE"));
 }
}
