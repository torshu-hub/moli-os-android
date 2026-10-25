<script>
  import { home, hub, clock } from '../lib/home.svelte.js';
  import { netatmoInstances, netatmoOverview, netatmoStatus, netatmoCardPriority, netatmoAge } from '../lib/netatmo-status.js';
  import {useReceiptClock} from '../lib/receipt-clock.svelte.js';
  import { reportUi, UI_TOPIC, UI_STATE } from '../lib/ui-diagnostics.js';
  import { t } from '../../lib/i18n.svelte.js';
  import NetatmoCard from './NetatmoCard.svelte';
  const entries=$derived(netatmoInstances(hub.devices,hub.drivers,hub.diagnostics));
  const visible=$derived(entries.length?entries:[{configured:false,devices:[]}]);
  const connected=$derived(hub.connected&&(!home.config?.android||home.snapshotHealthy));
  const receiptClock=useReceiptClock();const now=$derived(receiptClock.now??home.now);
  const status=(row)=>netatmoOverview(row,hub.drivers.find(d=>d.instance===row.instance),connected,now);
  $effect(()=>{
    const total=entries.reduce((n,e)=>n+e.devices.length,0);
    const fresh=entries.reduce((n,e)=>n+e.devices.filter(d=>netatmoStatus(d,hub.drivers.find(r=>r.instance===e.instance),connected,now)==='cloud_recent').length,0);
    reportUi(UI_TOPIC.netatmo,total?(fresh?UI_STATE.ready:UI_STATE.stale):UI_STATE.empty,total,fresh);
  });
  const time=(at)=>t('pilotes.netatmo.received',{time:clock(at),age:Math.max(0,Math.floor((now-at)/1000))});
</script>
<section data-netatmo-overview>
  <h2>{t('pilotes.netatmo.overview_title')}</h2>
  {#each visible as row,index (row.instance??index)}
    <article class="card" data-netatmo-overview-status={status(row)}>
      <h3>{t('pilotes.netatmo.'+(status(row)==='cloud_recent'?'connected_short':status(row)))}</h3>
      {#if row.last_response_at>0}{@const age=netatmoAge(row.last_response_at,now)}<p class="muted">{t('pilotes.netatmo.'+age.key,{count:age.count,seconds:age.seconds})}</p>{/if}
      <details><summary>{t('pilotes.netatmo.connection_details')}</summary>
      <p class="muted">{t('pilotes.netatmo.time_caution')}</p>
      {#if row.configured}
        <dl>
          <dt>{t('pilotes.netatmo.authorization_saved')}</dt><dd>{t(row.credential_present?'systeme.inventory.yes':'systeme.inventory.no')}</dd>
          <dt>{t('pilotes.netatmo.home_selected')}</dt><dd>{t(row.target_selected?'systeme.inventory.yes':'systeme.inventory.no')}</dd>
          <dt>{t('pilotes.netatmo.reads_enabled')}</dt><dd>{t(row.enabled?'systeme.inventory.yes':'systeme.inventory.no')}</dd>
          <dt>{t('pilotes.netatmo.last_attempt')}</dt><dd>{row.last_attempt_at?time(row.last_attempt_at):t('pilotes.netatmo.not_observed')}</dd>
          <dt>{t('pilotes.netatmo.last_response')}</dt><dd>{row.last_response_at?time(row.last_response_at):t('pilotes.netatmo.not_observed')}</dd>
          {#if row.polling?.period_seconds}<dt>{t('pilotes.netatmo.frequency')}</dt><dd>{t('pilotes.netatmo.frequency_seconds',{count:row.polling.period_seconds})}</dd>{/if}
        </dl>
      {/if}
      {#if row.enabled===false}<p>{t('pilotes.netatmo.disabled_help')}</p>{/if}
      {#if row.last_publish_at>0&&row.summary?.fresh===0}<p>{t('pilotes.netatmo.no_current_data')}</p>{/if}
      {#if home.config?.android}<a href="/android/netatmo">{t('pilotes.netatmo.account_settings')}</a>{/if}
      </details>
    </article>
    <div class="netatmo-devices">
      {#each [...row.devices].sort((a,b)=>netatmoCardPriority(a)-netatmoCardPriority(b)) as d (d.id)}<NetatmoCard id={d.id}/>{/each}
    </div>
  {/each}
</section>
<style>
  h2 {margin:0 0 16px;} h3 {margin:0;} article {margin-bottom:16px;}
  .netatmo-devices {display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,300px),1fr));gap:16px;}
  dl {display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:8px 16px;}
  dd {margin:0;text-align:right;overflow-wrap:anywhere;} dt {color:var(--ink-3);}
  a {min-height:44px;display:inline-flex;align-items:center;}
  summary {min-height:44px;display:flex;align-items:center;cursor:pointer;}
</style>
