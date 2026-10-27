import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validObservation,effectiveObservation,validSettings,reasons,receiptMatches} from './observation.js';
const now=1000000;
const ready={revision:1,epoch:1,state:'ready',reason:'none',demand:'on',intention:true,observed_at:now,collected_at:now-1000,valid_until:now+2000,transitions:2,witness:[['on',now],['cloud_recent',now],[now-1000,now],[300,now],['mss310',now]]};
test('only a coherent, current, server-validated intention can be displayed',()=>{
 assert.equal(validObservation(ready),true);
 assert.equal(effectiveObservation(ready,{now}).intention,true);
 for(const options of [{now:now+2000},{now,dirty:true},{now,active:false},{now,connected:false},{now,coherent:false}])assert.equal(effectiveObservation(ready,options).intention,null);
 for(const patch of [{intention:'on'},{demand:false},{demand:'off'},{state:'unknown'},{valid_until:now-1},{observed_at:undefined},{collected_at:null},{reason:'vendor-secret'}])assert.equal(validObservation({...ready,...patch}),false);
 const off={...ready,demand:'off',intention:false,witness:[['off',now],...ready.witness.slice(1)]};assert.equal(validObservation(off),true);assert.equal(effectiveObservation(off,{now}).intention,false);
});
test('blocked and offline observations never retain the last applicable intention',()=>{
 for(const reason of ['source_offline','target_offline','source_stale','target_stale','future_date','invalid_demand','source_missing','selection_changed','restarted','coherence']){
  const blocked={...ready,state:'stale',reason,demand:'unknown',intention:null,valid_until:null,witness:null};assert.equal(validObservation(blocked),true);
  assert.equal(effectiveObservation(blocked,{now,connected:false}).intention,null);assert.equal(effectiveObservation(blocked,{now,connected:false}).reason,reason);
 }
});
test('selection metadata is bounded and separate from authorization',()=>{
 const settings={revision:0,epoch:1,selection:null,storage_available:true,sources:[],targets:[]};assert.equal(validSettings(settings),true);
 const selected={source:'source:thermostat',target:'target:00000000000000000000000000000001',source_name:'Thermostat',target_name:'Synthetic plug'};
 assert.equal(validSettings({...settings,selection:selected}),true);
 assert.equal(validSettings({...settings,selection:{source:42,target:selected.target}}),false);
 assert.equal(validSettings({...settings,targets:new Array(65).fill({id:selected.target,name:'Fixture',instance:'target',online:true})}),false);
});

test('new sample values invalidate a receipt; identical renewed samples do not create a false transition',()=>{
 const source={state:Object.fromEntries(['declared_demand','freshness','collected_at','max_age_seconds'].map((k,i)=>[k,{value:ready.witness[i][0],ts:now+1}]))};
 const target={state:{reported_model:{value:'mss310',ts:now+1}}};
 assert.equal(receiptMatches(ready,source,target),true);
 source.state.declared_demand.value='unknown';assert.equal(receiptMatches(ready,source,target),false);
 source.state.declared_demand.value=false;assert.equal(receiptMatches(ready,source,target),false);
});

test('Android trial-report reason names match the closed web protocol',async()=>{
 const {readFile}=await import('node:fs/promises');
 const text=await readFile(new URL('../../../../android/app/src/main/java/io/moli/android/TrialDiagnostics.java',import.meta.url),'utf8');
 const names=text.match(/enum ObservationReason \{([^}]+)\}/)[1].split(',').map(s=>s.trim().toLowerCase());
 assert.deepEqual(names,reasons);
});
