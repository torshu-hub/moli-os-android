<script>
 import {onMount} from 'svelte';
 import HeatingTrial from './HeatingTrial.svelte';
 import DailyHeating from './DailyHeating.svelte';
 import {home,hub} from '../lib/home.svelte.js';
 import {t} from '../../lib/i18n.svelte.js';
 import {useReceiptClock} from '../lib/receipt-clock.svelte.js';
 import {reportUi,UI_TOPIC} from '../lib/ui-diagnostics.js';
 import {observationRequest,effectiveObservation,diagnosticState,reasons,receiptMatches,observationWitness} from '../lib/observation.js';
 let settings=$state(null),source=$state(''),target=$state(''),dirty=$state(false),editRevision=0;
 let result=$state(null),busy=$state(false),failure=$state(false),active=$state(true);
 let sequence=0,destroyed=false,retry=null,lastLive='';
 const receipt=useReceiptClock();
 const sourceDevice=$derived(hub.devices[source]),targetDevice=$derived(hub.devices[target]);
 const connected=$derived(hub.connected&&(home.platform!=='android'||home.snapshotHealthy)&&sourceDevice?.online===true&&targetDevice?.online===true&&[sourceDevice,targetDevice].every(d=>hub.drivers.some(driver=>driver.instance===d?.instance&&driver.status?.state==='running')));
 const view=$derived(effectiveObservation(result,{now:receipt.now??home.now,connected:!settings?.selection||connected,active,dirty,coherent:receiptMatches(result,sourceDevice,targetDevice)}));
 const shown=$derived(failure?{...view,state:'unknown',reason:'transport',demand:'unknown',intention:null}:view);
 const date=n=>n?new Date(n).toLocaleString():'—';
 const missing=(items,id)=>!!id&&!items?.some(c=>c.id===id);
 function later(){clearTimeout(retry);retry=setTimeout(()=>refresh(),500);}
 function apply(next){
  if(settings&&(settings.epoch!==next.epoch||settings.revision!==next.revision))result=null;
  settings=next;
  if(!dirty){source=next.selection?.source||'';target=next.selection?.target||'';editRevision=next.revision;}
 }
 function edit(){if(!dirty)editRevision=settings?.revision??0;dirty=true;result=null;failure=false;sequence++;}
 async function refresh(){
  if(busy||destroyed||document.hidden)return;
  busy=true;const current=++sequence;
  try{
   const next=await observationRequest();if(current!==sequence||destroyed)return;
   apply(next);failure=false;
   if(dirty)return;
   const observed=await observationRequest('/observe','POST',{revision:next.revision,epoch:next.epoch});
   if(current!==sequence||destroyed||dirty)return;
   if(observed.epoch!==next.epoch||observed.revision!==next.revision){result=null;later();return;}
   result=observed;
   if(['restarted','selection_changed'].includes(observed.reason))later();
  }catch{if(current===sequence&&!destroyed){failure=true;result=null;}}
  finally{busy=false;}
 }
 async function save(clear=false){
  if(busy||!settings?.storage_available)return;
  busy=true;const current=++sequence;result=null;failure=false;
  try{
   const next=await observationRequest('','PUT',{revision:dirty?editRevision:settings.revision,source:clear?null:source,target:clear?null:target});
   if(current!==sequence||destroyed)return;
   dirty=false;apply(next);later();
  }catch{if(current===sequence&&!destroyed){failure=true;result=null;}}
  finally{busy=false;}
 }
 onMount(()=>{
  destroyed=false;active=!document.hidden;refresh();const timer=setInterval(refresh,15000);
  const visibility=()=>{active=!document.hidden;sequence++;result=null;if(active)later();};
  document.addEventListener('visibilitychange',visibility);
  return()=>{destroyed=true;sequence++;clearInterval(timer);clearTimeout(retry);document.removeEventListener('visibilitychange',visibility);};
 });
 $effect(()=>{const key=JSON.stringify([settings?.epoch,settings?.revision,connected,observationWitness(sourceDevice,targetDevice)]);if(settings?.selection&&!dirty&&key!==lastLive){lastLive=key;later();}});
 $effect(()=>{if(settings)reportUi(UI_TOPIC.observation,diagnosticState(shown),Math.max(0,reasons.indexOf(shown.reason)),Math.min(4096,shown.transitions||0));});
</script>
<section class="card observation" data-observation data-observation-state={shown.state}>
 <h2>{t('maison.observation.title')}</h2>
 <p>{t('maison.observation.mode')}</p>
 <p>{t('maison.observation.help')}</p>
 {#if settings}
  <div class="choices">
   <label>{t('maison.observation.source')}<select data-observation-source bind:value={source} onchange={edit} disabled={busy||!settings.storage_available}>
    <option value="">{t('maison.observation.choose_source')}</option>
    {#if missing(settings.sources,source)}<option value={source}>{settings.selection?.source_name||t('maison.observation.saved_source')} · {t('maison.observation.offline')}</option>{/if}
    {#each settings.sources as c (c.id)}<option value={c.id}>{c.name} · {c.model} · {t('maison.observation.'+(c.online?'online':'offline'))}</option>{/each}
   </select></label>
   <label>{t('maison.observation.target')}<select data-observation-target bind:value={target} onchange={edit} disabled={busy||!settings.storage_available}>
    <option value="">{t('maison.observation.choose_target')}</option>
    {#if missing(settings.targets,target)}<option value={target}>{settings.selection?.target_name||t('maison.observation.saved_target')} · {t('maison.observation.offline')}</option>{/if}
    {#each settings.targets as c (c.id)}<option value={c.id}>{c.name} · {c.model} · {t('maison.observation.'+(c.online?'online':'offline'))}</option>{/each}
   </select></label>
  </div>
  <div class="actions">
   <button data-observation-save disabled={busy||!dirty||!source||!target||!settings.storage_available} onclick={()=>save()}>{t('maison.observation.save')}</button>
   <button data-observation-refresh disabled={busy||dirty} onclick={refresh}>{t('maison.observation.refresh')}</button>
   {#if settings.selection}<button data-observation-clear disabled={busy||!settings.storage_available} onclick={()=>save(true)}>{t('maison.observation.clear')}</button>{/if}
  </div>
  {#if !settings.storage_available}<p role="status">{t('maison.observation.reason.storage')}</p>{/if}
  <details><summary>{t('maison.observation.details')}</summary><dl>
   <dt>{t('maison.observation.source')}</dt><dd>{settings.sources.find(c=>c.id===source)?.instance||'—'}<small>{source||'—'}</small></dd>
   <dt>{t('maison.observation.target')}</dt><dd>{settings.targets.find(c=>c.id===target)?.instance||'—'}<small>{target||'—'}</small></dd>
  </dl></details>
 {/if}
 <div class="readings" role="status" aria-live="polite">
  <div><span>{t('maison.observation.demand')}</span><strong data-observation-demand>{t('maison.observation.value.'+shown.demand)}</strong></div>
  <div><span>{t('maison.observation.intention')}</span><strong data-observation-intention>{shown.intention===true?t('maison.observation.value.on'):shown.intention===false?t('maison.observation.value.off'):t('maison.observation.no_intention')}</strong></div>
  <div><span>{t('maison.observation.real_state')}</span><strong data-observation-actual>{connected&&typeof targetDevice?.state?.on?.value==='boolean'&&targetDevice.state.on.ts<=(receipt.now??home.now)&&(receipt.now??home.now)-targetDevice.state.on.ts<65000?t('maison.observation.value.'+(targetDevice.state.on.value?'on':'off')):t('maison.observation.value.unknown')}</strong></div>
 </div>
 <p data-observation-reason>{t('maison.observation.reason.'+shown.reason)}</p>
 <p class="muted">{t('maison.observation.observed_at')} : <time>{date(shown.observed_at)}</time><br/>{t('maison.observation.collected_at')} : <time>{date(shown.collected_at)}</time></p>
 <DailyHeating {settings} {dirty} now={receipt.now??home.now}/>
 <HeatingTrial {settings} {shown} {dirty} now={receipt.now??home.now}/>
</section>
<style>
 .observation{margin:16px 0 24px;min-width:0;overflow-wrap:anywhere;}h2{margin-top:0;}
 .choices,.readings{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,260px),1fr));gap:16px;}
 label{display:grid;gap:8px;min-width:0;}select{width:100%;min-width:0;min-height:48px;font:inherit;}
 .actions{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0;}button,summary{min-height:44px;}
 .readings{margin-top:16px;}.readings span,.readings strong{display:block;}.readings strong{font-size:1.35rem;}
 dl{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,2fr);gap:8px;}dd{margin:0;}small{display:block;}
</style>
