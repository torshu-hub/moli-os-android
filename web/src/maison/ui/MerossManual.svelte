<script>
  import {home,hub,act} from '../lib/home.svelte.js';
  import {t} from '../../lib/i18n.svelte.js';
  import {reportUi,UI_TOPIC,UI_STATE} from '../lib/ui-diagnostics.js';
  let {data,uuid,name,fresh=false}=$props();
  let busy=$state(false),result=$state(null);
  const point=$derived(data?.id?`${data.id}/on`:null);
  const identity=$derived(typeof uuid==='string'&&/^[a-f0-9]{32}$/i.test(uuid)&&data?.id?.toLowerCase()===`${data?.instance}:${uuid}`.toLowerCase());
  const driver=$derived(hub.drivers.find(d=>d.instance===data?.instance));
  const available=$derived(identity&&fresh&&data?.online&&data?.points?.some(p=>p.key==='on'&&p.access.write)&&hub.connected&&home.snapshotHealthy&&driver?.status?.state==='running');
  async function send(value){
    if(!available||busy||result==='held')return;
    busy=true;result='pending';reportUi(UI_TOPIC.merossCommand,UI_STATE.loading);
    try{
      const answer=await act(point,value,name,{timeout:8000});
      result=answer==='ok'?'confirmed':answer==='held'?'held':'unknown';
      reportUi(UI_TOPIC.merossCommand,answer==='ok'?UI_STATE.ready:answer==='held'?UI_STATE.loading:UI_STATE.http);
    }finally{busy=false;}
  }
  $effect(()=>{if(result==='held'&&!home.held){result=null;reportUi(UI_TOPIC.merossCommand,UI_STATE.empty);}});
</script>
<section data-meross-manual data-meross-result={result||'idle'}>
  <p><strong>{t('maison.meross_manual.title',{name:name||data?.native_name||''})}</strong></p>
  <p class="muted">{t('maison.meross_manual.limit')}</p>
  <details><summary>{t('maison.meross_manual.target')}</summary><p>{uuid||'—'}</p></details>
  <div class="actions">
    <button data-meross-on disabled={!available||busy||result==='held'} onclick={()=>send(true)}>{t('maison.meross_manual.on')}</button>
    <button data-meross-off disabled={!available||busy||result==='held'} onclick={()=>send(false)}>{t('maison.meross_manual.off')}</button>
  </div>
  {#if result}<p role="status">{t('maison.meross_manual.'+result)}</p>{/if}
  {#if !available}<p role="status">{t('maison.meross_manual.unavailable')}</p>{/if}
  <p class="muted">{t('maison.meross_manual.readonly_help')}</p>
  <a href="/android/equipment">{t('maison.meross_manual.settings')}</a>
</section>
<style>
  .actions{display:flex;flex-wrap:wrap;gap:12px;}button,a,summary{min-height:44px;}button{min-width:96px;}p{overflow-wrap:anywhere;}.muted{color:var(--muted);}a{display:inline-flex;align-items:center;}
</style>
