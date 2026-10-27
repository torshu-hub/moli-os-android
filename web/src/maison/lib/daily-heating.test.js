import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validDaily} from './daily-heating.js';
test('daily UI refuses any response claiming activation, service or a different contract',()=>{
 const value={revision:1,epoch:42,state:'disabled',readiness:'ready',activation_available:false,reason:'release_locked',already_on_may_remain_on:true,draft:{id:'candidate',fingerprint:'version'}};
 assert.equal(validDaily(value),true);
 for(const patch of [{state:'in_service'},{activation_available:true},{already_on_may_remain_on:false},{reason:'approved'},{revision:-1},{epoch:null},{draft:{id:'candidate'}}])assert.equal(validDaily({...value,...patch}),false);
});
