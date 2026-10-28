import {test} from 'node:test';import assert from 'node:assert/strict';
import {netatmoInstances,netatmoOverview,netatmoStatus,netatmoAge,netatmoCardPriority} from './netatmo-status.js';
import {readJson,UI_STATE} from './ui-diagnostics.js';
import {systemFacts,validSystem} from './system-status.js';

test('association and enabled instance remain visible with no device; running is not a read',()=>{
 const d={instance:'fixture',kind:'netatmo',configured:true,enabled:true,credential_present:true,target_selected:true,stages:{}};
 const rows=netatmoInstances({},[{instance:'fixture',kind:'netatmo',status:{state:'running'}}],[d]);
 assert.equal(rows.length,1);assert.equal(netatmoOverview(rows[0],{status:{state:'running'}},true,10000),'waiting_first_read');
 assert.equal(netatmoOverview({...rows[0],enabled:false},null,true,10000),'disabled');
 assert.equal(netatmoOverview({...rows[0],last_attempt_at:9000,stages:{homesdata:{status:'transport',last_error_at:9500}}},null,true,10000),'error_transport');
 for(const status of ['timeout','dns','connect','tls','headers','body','cycle_timeout'])assert.equal(netatmoOverview({...rows[0],last_attempt_at:9000,stages:{homesdata:{status,last_error_at:9500}}},null,true,10000),'error_transport');
});
test('automatic cards use real instance identity and hide expired/currently disconnected demand',()=>{
 const d={id:'fixture:room-test',instance:'fixture',manufacturer:'Netatmo',online:true,state:{freshness:{value:'cloud_recent'},max_age_seconds:{value:120},server_at:{value:10000},collected_at:{value:11000}}};
 const driver={instance:'fixture',kind:'netatmo',status:{state:'running'}};
 const rows=netatmoInstances({[d.id]:d},[driver],[]);assert.equal(rows[0].devices[0],d);
 assert.equal(netatmoStatus(d,driver,true,20000),'cloud_recent');
 assert.equal(netatmoStatus(d,driver,true,140000),'stale');
 assert.equal(netatmoStatus(d,driver,false,20000),'unconfirmed');
});
test('deadline covers a hung fetch and a hung JSON body, classifies HTTP and rejects bad schema',async()=>{
 for(const fetcher of [()=>new Promise(()=>{}),async()=>({ok:true,json:()=>new Promise(()=>{})})])
  await assert.rejects(readJson('/api/system',{timeout:5,fetcher}),e=>e.code===UI_STATE.timeout);
 await assert.rejects(readJson('/api/system',{fetcher:async()=>({ok:false,status:403})}),e=>e.code===UI_STATE.http);
 await assert.rejects(readJson('/api/system',{fetcher:async()=>({ok:true,json:async()=>({private:'untrusted'})}),valid:validSystem}),e=>e.code===UI_STATE.invalid);
});
test('system graph facts work without Array.at or findLast',()=>{
 const perf=[{clients:{total:0},moli:{mem_mib:1}},{clients:{total:1}}];perf.at=undefined;perf.findLast=undefined;
  assert.deepEqual(systemFacts({perf}),{last:{clients:{total:1}},idle:{clients:{total:0},moli:{mem_mib:1}}});
  assert.equal(systemFacts(null).last,undefined);
});
test('compact communication age preserves unknown and future dates and orders temperature before accessories',()=>{
 assert.deepEqual(netatmoAge(10000,10000),{key:'age_seconds',count:0});
 assert.deepEqual(netatmoAge(10000,71000),{key:'age_minutes_seconds',count:1,seconds:1});
 for(const [seconds,expected] of [[59,{key:'age_seconds',count:59}],[60,{key:'age_minutes_seconds',count:1,seconds:0}],[90,{key:'age_minutes_seconds',count:1,seconds:30}],[119,{key:'age_minutes_seconds',count:1,seconds:59}],[120,{key:'age_minutes',count:2}]])assert.deepEqual(netatmoAge(10000,10000+seconds*1000),expected);
 assert.deepEqual(netatmoAge(10000,3610000),{key:'age_hours',count:1});
 assert.equal(netatmoAge(null,10000).key,'age_unknown');assert.equal(netatmoAge(11000,10000).key,'age_unknown');
 assert.deepEqual([{model:'NRV'},{model:'NATherm1'},{model:'room'}].sort((a,b)=>netatmoCardPriority(a)-netatmoCardPriority(b)).map(d=>d.model),['room','NATherm1','NRV']);
});
