<script>
 import {onMount} from 'svelte';
 import {t} from '../../lib/i18n.svelte.js';
 import {dailyRequest} from '../lib/daily-heating.js';
 import {reportUi,UI_TOPIC,UI_STATE} from '../lib/ui-diagnostics.js';
 let {settings,dirty}=$props();
 let daily=$state(null),busy=$state(false),failed=$state(false),alive=true;
 async function load(prepare=false){
  if(busy||document.hidden)return;
  busy=true;
  try{
   const next=await dailyRequest(prepare?settings:undefined);
   if(alive){daily=next;failed=false;}
  }catch{if(alive){failed=true;daily=null;}}
  finally{busy=false;}
 }
 const current=$derived(!dirty&&daily?.revision===settings?.revision&&daily?.epoch===settings?.epoch);
 $effect(()=>{if(daily||failed)reportUi(UI_TOPIC.dailyHeating,failed?UI_STATE.transport:current&&daily.readiness==='ready'?UI_STATE.ready:UI_STATE.empty,0,0);});
 onMount(()=>{alive=true;load();const timer=setInterval(()=>load(),15000);return()=>{alive=false;clearInterval(timer);};});
</script>
<div class="daily" data-daily-heating="disabled">
 <h3>{t('maison.observation.daily_name')}</h3>
 <p role="status">{t('maison.observation.daily_disabled')} · {t('maison.observation.daily_'+(failed||!current?'refused':daily?.readiness||'disabled'))}</p>
 <p>{t('maison.observation.daily_lock')}</p>
 <button disabled={busy||dirty||!settings?.selection||!settings?.storage_available} onclick={()=>load(true)}>{t('maison.observation.daily_prepare')}</button>
 <p>{t('maison.observation.daily_loss')}</p>
</div>
<style>.daily{margin-top:20px;}button{min-height:44px;}</style>
