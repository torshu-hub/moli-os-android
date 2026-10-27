import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validDaily,validWatch,effectiveWatch} from './daily-heating.js';
const watch={active:true,fingerprint:'version',events:3,publications:2,clocks:1,omitted:0,last_at:900,reason:'would_on',intention:true,actual:false,valid_until:1200,history:[{at:900,reason:'would_on',intention:true,actual:false,repeats:3}]};
const value={revision:1,epoch:42,state:'disabled',readiness:'ready',activation_available:false,reason:'release_locked',already_on_may_remain_on:true,draft:{id:'candidate',fingerprint:'version'},observation:null};
test('daily UI refuses any response claiming physical activation or a different exact version',()=>{
 assert.equal(validDaily(value),true);assert.equal(validDaily({...value,observation:watch}),true);
 for(const patch of [{state:'in_service'},{activation_available:true},{already_on_may_remain_on:false},{reason:'approved'},{revision:-1},{epoch:null},{draft:{id:'candidate'}},{observation:{...watch,fingerprint:'other-version'}}])assert.equal(validDaily({...value,...patch}),false);
});
test('continuous observation accepts only bounded closed counters and simulated decisions',()=>{
 assert.equal(validWatch(watch),true);
 for(const patch of [{active:'true'},{events:-1},{reason:'arbitrary payload'},{intention:'ON'},{actual:1},{reason:'source_pending'},{valid_until:null},{active:false},{history:Array(33).fill(watch.history[0])},{history:[{...watch.history[0],repeats:-1}]}])assert.equal(validWatch({...watch,...patch}),false);
});
test('lost transport, expired data and changed selection immediately hide any old current intention',()=>{
 const daily={...value,observation:watch};const options={now:1000,current:true,failed:false};
 assert.equal(effectiveWatch(daily,options).intention,true);
 for(const patch of [{now:1200},{now:undefined},{current:false},{failed:true}])assert.equal(effectiveWatch(daily,{...options,...patch}).intention,null);
 assert.equal(effectiveWatch(daily,{...options,failed:true}).active,false);
});
test('Android report retains closed observation categories and merges both demand directions',async()=>{
 const {dailyReportReasons,dailyReportReason}=await import('./daily-heating.js');
 const {readFile}=await import('node:fs/promises');
 const text=await readFile(new URL('../../../../android/app/src/main/java/io/moli/android/TrialDiagnostics.java',import.meta.url),'utf8');
 assert.deepEqual(text.match(/enum DailyObservationReason \{([^}]+)\}/)[1].split(',').map(s=>s.trim().toLowerCase()),dailyReportReasons);
 assert.equal(dailyReportReason('would_on'),dailyReportReason('would_off'));
 assert.equal(dailyReportReasons[dailyReportReason('would_on')],'simulated');
 assert.equal(dailyReportReason('sensitive arbitrary string'),0);
});
