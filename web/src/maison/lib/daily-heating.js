import {readJson} from './ui-diagnostics.js';
import {observationPath} from './observation.js';
export function validDaily(value){
 return Number.isSafeInteger(value?.revision)&&value.revision>=0&&Number.isSafeInteger(value.epoch)&&value.epoch>=0&&
  value.state==='disabled'&&['disabled','ready','refused'].includes(value.readiness)&&
  value.activation_available===false&&value.reason==='release_locked'&&value.already_on_may_remain_on===true&&
  (value.draft===null||typeof value.draft?.id==='string'&&typeof value.draft.fingerprint==='string');
}
export function dailyRequest(settings){
 return readJson(observationPath+'/daily',{valid:validDaily,timeout:8000,
  fetcher:(url,opts)=>fetch(url,{...opts,method:settings?'POST':'GET',headers:{'content-type':'application/json','x-moli-origin':'ui'},...(settings?{body:JSON.stringify({revision:settings.revision,epoch:settings.epoch})}:{})})});
}
