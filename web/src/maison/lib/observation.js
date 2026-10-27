// Display validation only. All admission and graph evaluation stay in Rust.
import {readJson,UI_STATE} from './ui-diagnostics.js';
export const observationPath='/api/observations/netatmo-meross';
export const reasons=['none','unconfigured','storage','selection_changed','restarted','source_missing','target_missing','identity','source_offline','target_offline','driver_stopped','source_unpublished','invalid_demand','unknown_demand','missing_date','future_date','source_stale','target_stale','coherence','engine','loading','disconnected','pending_selection','hidden','transport','expired'];
const number=n=>Number.isSafeInteger(n)&&n>=0;
const id=s=>typeof s==='string'&&s.length>0&&s.length<=256&&s.includes(':');
export function validSettings(s){
 const choice=c=>id(c?.id)&&typeof c.name==='string'&&typeof c.instance==='string'&&typeof c.online==='boolean';
 return number(s?.revision)&&number(s.epoch)&&typeof s.storage_available==='boolean'&&
  ['sources','targets'].every(k=>Array.isArray(s[k])&&s[k].length<=64&&s[k].every(choice))&&
  (s.selection===null||id(s.selection?.source)&&id(s.selection?.target)&&typeof s.selection?.source_name==='string'&&typeof s.selection?.target_name==='string');
}
export function validObservation(r){
 if(!number(r?.revision)||!number(r.epoch)||!number(r.observed_at)||!number(r.transitions)||!reasons.includes(r.reason)||
  !['ready','unknown','stale','unconfigured'].includes(r.state)||!['on','off','unknown'].includes(r.demand))return false;
 if(r.collected_at!==null&&!number(r.collected_at))return false;
 if(r.state==='ready'&&(!Array.isArray(r.witness)||r.witness.length!==5||!r.witness.every(p=>Array.isArray(p)&&p.length===2&&number(p[1]))))return false;
 if(r.state==='ready'&&(r.witness[0][0]!==r.demand||r.witness[1][0]!=='cloud_recent'||r.witness[2][0]!==r.collected_at))return false;
 if(r.state==='ready')return r.reason==='none'&&typeof r.intention==='boolean'&&r.demand===(r.intention?'on':'off')&&number(r.valid_until)&&r.valid_until>r.observed_at&&r.valid_until<=r.observed_at+3600000&&number(r.collected_at)&&r.collected_at>0&&r.collected_at<=r.observed_at;
 return r.reason!=='none'&&r.intention===null&&r.valid_until===null&&r.witness===null;
}
export function effectiveObservation(result,{now=Date.now(),connected=true,active=true,dirty=false,coherent=true}={}){
 const blocked=reason=>({state:'unknown',reason,demand:'unknown',intention:null,transitions:result?.transitions||0,collected_at:result?.collected_at??null,observed_at:result?.observed_at??null});
 if(dirty)return blocked('pending_selection');if(!active)return blocked('hidden');if(!connected&&(!result||result.state==='ready'))return blocked('disconnected');
 if(!result)return blocked('loading');
 if(result.state==='ready'&&!coherent)return blocked('coherence');
 if(!validObservation(result))return blocked('transport');
 if(result.observed_at>now+5000)return blocked('future_date');
 if(result.state==='ready'&&now>=result.valid_until)return {...blocked('expired'),state:'stale'};
 return result;
}
export async function observationRequest(suffix='',method='GET',body){
 return readJson(observationPath+suffix,{timeout:8000,valid:suffix?validObservation:validSettings,
  fetcher:(url,opts)=>fetch(url,{...opts,method,headers:{'content-type':'application/json','x-moli-origin':'ui'},...(body?{body:JSON.stringify(body)}:{})})});
}
export function diagnosticState(view){return view.state==='ready'?UI_STATE.ready:view.state==='stale'?UI_STATE.stale:view.state==='unconfigured'?UI_STATE.empty:view.reason==='loading'?UI_STATE.loading:view.reason==='transport'?UI_STATE.transport:UI_STATE.empty;}

export function observationWitness(source,target){return ['declared_demand','freshness','collected_at','max_age_seconds'].map(key=>[source?.state?.[key]?.value??null,source?.state?.[key]?.ts??null]).concat([[target?.state?.reported_model?.value??null,target?.state?.reported_model?.ts??null]]);}
export function receiptMatches(result,source,target){return result?.state!=='ready'||observationWitness(source,target).every((pair,i)=>pair[0]===result.witness?.[i]?.[0]&&number(pair[1])&&pair[1]>=result.witness[i][1]);}
