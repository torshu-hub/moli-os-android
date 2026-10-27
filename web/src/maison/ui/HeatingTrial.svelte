<script>
 import {home,hub,refreshSession} from '../lib/home.svelte.js';
 import {t} from '../../lib/i18n.svelte.js';
 import {readJson} from '../lib/ui-diagnostics.js';
 import {observationPath} from '../lib/observation.js';
 import {onMount} from 'svelte';
 let {settings,shown,dirty,now}= $props();
 let sequence=0,destroyed=false;
 let pending=$state(null),status=$state(''),busy=$state(false),checks=$state({});
 const fields=['vessel_pressure','relay_enclosure','thermostat_input','loss_behavior','supervised'];
 const ready=$derived(!dirty&&shown.state==='ready'&&settings?.selection);
 const valid=$derived(ready&&pending&&pending.revision===settings.revision&&pending.demand===shown.demand&&now<pending.expires_at);
 const confirmed=$derived(fields.every(k=>checks[k]===true));
 const request=(suffix,body)=>readJson(observationPath+'/trial/'+suffix,{timeout:9000,valid:r=>r?.autonomous===false&&typeof r.state==='string',fetcher:(url,opts)=>fetch(url,{...opts,method:'POST',headers:{'content-type':'application/json','x-moli-origin':'ui'},...(body?{body:JSON.stringify(body)}:{})})});
 async function prepare(){if(!ready||!confirmed||busy)return;busy=true;const attempt=++sequence;pending=null;status='';try{const r=await request('prepare',{revision:settings.revision,epoch:settings.epoch});if(attempt===sequence&&!destroyed)pending=r.prepared;}catch{if(attempt===sequence&&!destroyed)status='trial_failure';}finally{busy=false;}}
 async function apply(){if(!valid||!confirmed||busy||!hub.session?.human)return;busy=true;const attempt=++sequence,seen=pending;pending=null;try{const r=await request('execute',{revision:settings.revision,epoch:settings.epoch,fingerprint:seen.fingerprint,...Object.fromEntries(fields.map(k=>[k,checks[k]===true]))});if(attempt===sequence&&!destroyed)status=r.state==='confirmed'?'trial_confirmed':r.state==='cancelled'?'trial_cancelled':'trial_failure';}catch{if(attempt===sequence&&!destroyed)status='trial_failure';}finally{checks={};busy=false;}}
 async function cancel(){sequence++;pending=null;checks={};status='trial_cancelled';await request('cancel').catch(()=>{if(!destroyed)status='trial_failure';});}
 function identify(){home.held={label:t('maison.observation.real_title'),reason:'automation',custom:()=>{}};}
 onMount(()=>{destroyed=false;refreshSession();const hide=()=>{if(document.hidden&&(pending||busy))cancel();};document.addEventListener('visibilitychange',hide);return()=>{document.removeEventListener('visibilitychange',hide);if(pending||busy)cancel();destroyed=true;sequence++;};});
</script>
<div class="trial" data-heating-trial>
 <h3>{t('maison.observation.real_title')}</h3><p>{t('maison.observation.autonomous_blocked')}</p>
 <p>{t('maison.observation.real_help')}</p>
 {#if home.platform==='android'}
  <a class="native" href="/android/heating-trial" data-heating-native>{t('maison.observation.real_open')}</a>
 {:else}
  <p>{t('maison.observation.physical_warning')}</p>
  {#each fields as field}<label><input type="checkbox" bind:checked={checks[field]} disabled={busy}/>{t('maison.observation.'+field)}</label>{/each}
  <button data-heating-prepare disabled={!ready||!confirmed||busy} onclick={prepare}>{t('maison.observation.prepare')}</button>
  {#if pending}<p>{t('maison.observation.version')} : <code>{pending.fingerprint}</code> — {t('maison.observation.value.'+pending.demand)}</p>{/if}
  {#if !hub.session?.human}<button onclick={identify}>{t('maison.observation.pin')}</button>{/if}
  <button data-heating-apply disabled={!valid||!confirmed||busy||!hub.session?.human} onclick={apply}>{t('maison.observation.apply')}</button>
 {/if}
 <button data-heating-cancel onclick={cancel}>{t('maison.observation.cancel')}</button>
 {#if status}<p role="status">{t('maison.observation.'+status)}</p>{/if}
 <p>{t('maison.observation.stop_warning')}</p>
</div>
<style>.trial{border-top:1px solid var(--line,#ccd);margin-top:20px;padding-top:12px;}label{display:flex;align-items:flex-start;gap:10px;margin:12px 0;}input{min-width:24px;min-height:24px;}button,.native{display:inline-block;min-height:44px;margin:8px 8px 8px 0;}code{overflow-wrap:anywhere;}</style>
