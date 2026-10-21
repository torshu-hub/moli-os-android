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
