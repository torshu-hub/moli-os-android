import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sourceStatus } from './source-status.js';
test('source status distinguishes a received sample, stale data and a lost connection', () => {
 const d = {online:true,state:{temperature:{value:12.3,ts:1000},humidity:{value:67,ts:1000}}}, driver={status:{state:'running'}};
 const read=(connected,now) => sourceStatus(connected,d,driver,['temperature','humidity'],now,900000);
 assert.equal(read(true,2000).state,'fresh');
 assert.equal(read(true,902000).state,'stale');
 assert.equal(read(false,2000).state,'unavailable');
 assert.equal(read(true,999).state,'stale');
 d.state.temperature.value = null; assert.equal(read(true,2000).state,'unavailable');
});

import { equipmentStatus, durationParts, sampleMaxAge } from './equipment-status.js';
test('IGD unchanged connection stays fresh through received uptime and never hides an old/offline receipt',()=>{
 const d={online:true,state:{connected:{value:true,ts:1000},uptime:{value:150,ts:50000}}},driver={status:{state:'running'}};
 let status=equipmentStatus('gateway',d,driver,true,40000,50010);
 assert.equal(status.state,'fresh');assert.equal(status.ts,50000);assert.equal(status.age,0);
 status=equipmentStatus('gateway',d,driver,true,90000,50010);assert.equal(status.state,'stale');assert.equal(status.age,40);
 d.online=false;status=equipmentStatus('gateway',d,driver,true,90000,50010);assert.equal(status.state,'unavailable');assert.equal(status.ts,50000);
 d.online=true;d.state.uptime.ts=95000;assert.equal(equipmentStatus('gateway',d,driver,true,90000,90001).state,'stale'); // A genuine future sample remains invalid.
 assert.deepEqual(durationParts(90061),{days:1,hours:1,minutes:1});assert.equal(durationParts(-1),null);
});
test('Meross distinguishes startup and brief lost confirmation from a significant outage',()=>{
 const driver={status:{state:'running'}};
 assert.equal(equipmentStatus('meross',null,driver,true,999,999,1000).state,'checking');
 assert.equal(equipmentStatus('meross',null,driver,true,50000,50000,1000).state,'checking');
 assert.equal(equipmentStatus('meross',null,driver,true,70000,70000,1000).state,'unavailable');
 const d={online:true,state:{reported_model:{value:'MSS310',ts:100000}}};
 assert.equal(equipmentStatus('meross',d,driver,true,95000,100001).state,'fresh');
 assert.equal(equipmentStatus('meross',d,driver,false,130000,100001).state,'checking');
 assert.equal(equipmentStatus('meross',d,driver,false,166000,100001).state,'unavailable');
 assert.equal(equipmentStatus('meross',d,driver,true,166000,100001).state,'stale');
 assert.equal(equipmentStatus('meross',d,driver,true,99000,99000).state,'stale');
 assert.equal(sampleMaxAge('meross','power'),65000);
 assert.equal(sampleMaxAge('meross','energy_recent'),660000);
 assert.equal(sampleMaxAge('meross','signal'),135000);
});
