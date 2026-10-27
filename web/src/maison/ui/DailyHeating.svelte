<script>
 import {onMount} from 'svelte';
 import {t} from '../../lib/i18n.svelte.js';
 import {dailyRequest,effectiveWatch,dailyReportReason} from '../lib/daily-heating.js';
 import {reportUi,UI_TOPIC,UI_STATE} from '../lib/ui-diagnostics.js';
 let {settings,dirty,now=Date.now()}=$props();
 let daily=$state(null),busy=$state(false),failed=$state(false),alive=true;
 async function load(action){
  if(busy||document.hidden)return;
  busy=true;
  try{
   const next=await dailyRequest(action?settings:undefined,action,daily?.draft?.fingerprint);
   if(alive){daily=next;failed=false;}
  }catch{if(alive)failed=true;}
  finally{busy=false;}
 }
 const current=$derived(!dirty&&daily?.revision===settings?.revision&&daily?.epoch===settings?.epoch);
 const watch=$derived(effectiveWatch(daily,{current,failed,now}));
 const reason=code=>t('maison.observation.watch_reason.'+code);
 $effect(()=>{if(daily||failed)reportUi(UI_TOPIC.dailyHeating,failed?UI_STATE.transport:current&&daily.readiness==='ready'?UI_STATE.ready:UI_STATE.empty,dailyReportReason(watch?.reason),Math.min(4096,watch?.events||0));});
 onMount(()=>{alive=true;load();const timer=setInterval(()=>load(),15000);const visible=()=>{if(!document.hidden){failed=true;load();}};document.addEventListener('visibilitychange',visible);return()=>{alive=false;clearInterval(timer);document.removeEventListener('visibilitychange',visible);};});
</script>
<div class="daily" data-daily-heating="disabled" data-daily-observation={failed||!current?'unknown':watch?.active?'active':'stopped'}>
 <h3>{t('maison.observation.daily_name')}</h3>
 <p role="status">{t('maison.observation.daily_disabled')} · {t('maison.observation.daily_'+(failed||!current?'refused':daily?.readiness||'disabled'))}</p>
 <p>{t('maison.observation.daily_lock')}</p>
 <div class="actions">
  <button data-daily-prepare disabled={busy||dirty||daily?.observation?.active||!settings?.selection||!settings?.storage_available} onclick={()=>load('prepare')}>{t('maison.observation.daily_prepare')}</button>
  <button data-daily-start disabled={busy||dirty||failed||!current||daily?.readiness!=='ready'||!!daily?.observation?.active} onclick={()=>load('start')}>{t('maison.observation.watch_start')}</button>
  <button data-daily-stop disabled={busy||!daily?.observation?.active} onclick={()=>load('stop')}>{t('maison.observation.watch_stop')}</button>
 </div>
 <p>{t('maison.observation.watch_help')}</p>
 {#if watch}
  <div role="status" aria-live="polite" data-daily-decision>
   <strong>{t('maison.observation.'+(failed||!current?'watch_unknown':watch.active?'watch_active':'watch_stopped'))}</strong>
   <p>{reason(watch.reason)}</p>
   <p>{t('maison.observation.watch_count',{events:watch.events,publications:watch.publications,clocks:watch.clocks})}</p>
   <p>{t('maison.observation.real_state')} : {watch.actual===null?t('maison.observation.value.unknown'):t('maison.observation.value.'+(watch.actual?'on':'off'))}</p>
  </div>
  <details><summary>{t('maison.observation.watch_history')}</summary>
   <p>{t('maison.observation.watch_omitted',{count:watch.omitted})}</p>
   <ol>{#each watch.history.slice().reverse() as row}<li><time>{new Date(row.at).toLocaleTimeString()}</time> · {reason(row.reason)} × {row.repeats}</li>{/each}</ol>
  </details>
 {/if}
 {#if daily?.draft}<details><summary>{t('maison.observation.watch_version')}</summary><p>{daily.draft.id}</p><code>{daily.draft.fingerprint}</code></details>{/if}
 <p>{t('maison.observation.daily_loss')}</p>
</div>
<style>.daily{margin-top:20px;min-width:0;overflow-wrap:anywhere;}.actions{display:flex;flex-wrap:wrap;gap:12px;}button,summary{min-height:44px;}ol{padding-left:20px;}code{white-space:normal;}</style>
