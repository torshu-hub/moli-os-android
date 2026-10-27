import {readJson} from './ui-diagnostics.js';
import {observationPath} from './observation.js';
export const observationReasons=['warming','would_on','would_off','no_change','source_pending','source_error','source_offline','target_offline','source_stale','target_stale','target_unknown','source_unpublished','identity','unknown_demand','coherence','selection_changed','graph_changed','engine','paused','manual','stopped','throttled'];
// Export categories deliberately merge ON/OFF intentions: the Android report
// carries neither source measurements nor an individual demand direction.
export const dailyReportReasons=['unobserved','warming','simulated','no_change','source_pending','source_error','source_offline','target_offline','source_stale','target_stale','target_unknown','source_unpublished','identity','unknown_demand','coherence','selection_changed','graph_changed','engine','paused','manual','stopped','throttled','transport'];
export const dailyReportReason=reason=>Math.max(0,dailyReportReasons.indexOf(['would_on','would_off'].includes(reason)?'simulated':reason));
const count=n=>Number.isSafeInteger(n)&&n>=0;
const consistentDecision=value=>value.reason==='would_on'?value.intention===true&&value.actual===false:value.reason==='would_off'?value.intention===false&&value.actual===true:value.intention===null;
const maybeBool=value=>value===null||typeof value==='boolean';
export function validWatch(value){
 return value===null||typeof value?.active==='boolean'&&typeof value.fingerprint==='string'&&value.fingerprint.length<=128&&
  ['events','publications','clocks','omitted'].every(key=>count(value[key]))&&
  (value.last_at===null||count(value.last_at))&&observationReasons.includes(value.reason)&&maybeBool(value.intention)&&maybeBool(value.actual)&&consistentDecision(value)&&
  (value.intention===null||value.active===true&&count(value.valid_until)&&value.last_at!==null&&value.valid_until>value.last_at)&&
  (value.valid_until===null||count(value.valid_until))&&Array.isArray(value.history)&&value.history.length<=32&&
  value.history.every(row=>count(row.at)&&count(row.repeats)&&observationReasons.includes(row.reason)&&maybeBool(row.intention)&&maybeBool(row.actual)&&consistentDecision(row));
}
export function validDaily(value){
 return Number.isSafeInteger(value?.revision)&&value.revision>=0&&Number.isSafeInteger(value.epoch)&&value.epoch>=0&&
  value.state==='disabled'&&['disabled','ready','refused'].includes(value.readiness)&&
  value.activation_available===false&&value.reason==='release_locked'&&value.already_on_may_remain_on===true&&validWatch(value.observation)&&
  (value.draft===null||typeof value.draft?.id==='string'&&typeof value.draft.fingerprint==='string')&&
  (!value.observation||value.draft?.fingerprint===value.observation.fingerprint);
}
export function dailyRequest(settings,action,fingerprint){
 const observing=action==='start'||action==='stop';
 return readJson(observationPath+'/daily'+(observing?'/observation':''),{valid:validDaily,timeout:8000,
  fetcher:(url,opts)=>fetch(url,{...opts,method:settings?'POST':'GET',headers:{'content-type':'application/json','x-moli-origin':'ui'},...(settings?{body:JSON.stringify({revision:settings.revision,epoch:settings.epoch,...(observing?{action,fingerprint}:{})})}:{})})});
}
export function effectiveWatch(daily,{current,failed,now}){
 const watch=daily?.observation;
 if(!watch)return null;
 if(!current||failed)return {...watch,reason:'transport',intention:null,actual:null,active:false};
 if(watch.valid_until!==null&&(!Number.isFinite(now)||now>=watch.valid_until))return {...watch,reason:'coherence',intention:null,actual:null};
 return watch;
}
