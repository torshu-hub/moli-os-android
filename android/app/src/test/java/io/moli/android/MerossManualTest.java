package io.moli.android;
import org.json.*;import org.junit.Test;import static org.junit.Assert.*;
import static io.moli.android.TrialDiagnostics.*;
public final class MerossManualTest {
 @Test public void diagnosticReductionKeepsEvidenceWithoutTargetOrPayloadAndCoalescesSnapshots()throws Exception{
  long now=System.currentTimeMillis();JSONObject raw=new JSONObject().put("status","timeout").put("attempts",2).put("successes",1).put("failures",1).put("last_error_at",now).put("last_error","timeout").put("errors",new JSONObject().put("timeout",1).put("secret@example.com",999)).put("payload","private-provider-text").put("uuid","private-target");
  MerossDiagnostics.Step s=MerossDiagnostics.step(raw);assertEquals(State.FAILED,s.state);assertEquals(Reason.TIMEOUT,s.reason);
  TrialDiagnostics d=TrialDiagnostics.empty();for(int i=0;i<10;i++){
   d.observe(now+i,Topic.MEROSS_READBACK,s.state,s.reason,0,0,1000,s.attempts,s.successes,s.failures,s.attemptAt,s.successAt,s.errorAt);
   d.detail(Topic.MEROSS_READBACK,1000,s.lastError,s.duration,s.interruptions,s.errors);
  }
  assertEquals(2,d.entries.get(Topic.MEROSS_READBACK).attempts);assertEquals(1,d.entries.get(Topic.MEROSS_READBACK).failures);
  d=TrialDiagnostics.parse(d.json());String text=d.text(now);assertTrue(text.contains("MEROSS_READBACK : FAILED cause=TIMEOUT"));
  for(String privateValue:new String[]{"secret@example.com","private-target","private-provider-text"})assertFalse(text.contains(privateValue));
  d.observe(now+20,Topic.MEROSS_READBACK,State.PENDING,Reason.NONE,0,0,1000,3,1,1,now+20,0,now);d.engineStopped(now+21);
  assertEquals(State.CANCELLED,d.entries.get(Topic.MEROSS_READBACK).state);assertEquals(Reason.TIMEOUT,d.entries.get(Topic.MEROSS_READBACK).lastErrorReason);
 }
 @Test public void identityProposalRequiresOneFreshOnlineModelFromConfiguredReaderAndIsMemoryOnly()throws Exception{
  long now=System.currentTimeMillis();String id="00000000000000000000000000000001",uuid="00000000000000000000000000000002",instance="android-meross-"+id;
  JSONObject cfg=new JSONObject().put("meross",new JSONArray().put(new JSONObject().put("id",id).put("enabled",true)));
  JSONObject device=new JSONObject().put("instance",instance).put("id",instance+":"+uuid).put("online",true).put("state",new JSONObject().put("reported_model",new JSONObject().put("ts",now).put("value","mss310")));
  JSONObject snapshot=new JSONObject().put("devices",new JSONArray().put(device)).put("drivers",new JSONArray().put(new JSONObject().put("instance",instance).put("status",new JSONObject().put("state","running"))));
  EquipmentMonitor.rememberIdentities(cfg,snapshot,now);assertEquals(uuid,EquipmentMonitor.identity(id));
  snapshot.getJSONArray("devices").put(new JSONObject(device.toString()));EquipmentMonitor.rememberIdentities(cfg,snapshot,now);assertEquals("",EquipmentMonitor.identity(id));
  snapshot.put("devices",new JSONArray().put(device));EquipmentMonitor.rememberIdentities(cfg,snapshot,now+65001);assertEquals("",EquipmentMonitor.identity(id));
  device.put("online",false);EquipmentMonitor.rememberIdentities(cfg,snapshot,now);assertEquals("",EquipmentMonitor.identity(id));
  device.put("online",true);EquipmentMonitor.rememberIdentities(cfg,snapshot,now);assertEquals(uuid,EquipmentMonitor.identity(id));
  EquipmentMonitor.rememberIdentities(new JSONObject(),snapshot,now);assertEquals("",EquipmentMonitor.identity(id));
 }
}
