<script>
  import { home, hub, device, nameOf, clock, num } from '../lib/home.svelte.js';
  import { netatmoStatus, netatmoAge } from '../lib/netatmo-status.js';
  import {useReceiptClock} from '../lib/receipt-clock.svelte.js';
  import { t } from '../../lib/i18n.svelte.js';

  let { id, name = null } = $props();
  const d = $derived(device(id));
  const receiptClock=useReceiptClock();
  const now=$derived(receiptClock.now??home.now);
  const quality = $derived(netatmoStatus(d, hub.drivers.find(r => r.instance === d?.instance), hub.connected && (!home.config?.android || home.snapshotHealthy), now));
  const current = $derived(quality === 'cloud_recent');
  const collected = $derived(d?.state?.collected_at?.value);
  const server = $derived(d?.state?.server_at?.value);
  const age=$derived(netatmoAge(collected,now));
  const primary=$derived((d?.points??[]).filter(p=>['temperature','setpoint_temperature'].includes(p.key)||p.key==='declared_demand'&&['NATherm1','OTM'].includes(d?.model)));
  const mode=$derived(current?d?.state?.mode?.value:null);
  const rows = $derived((d?.points ?? []).filter(p => !['freshness','collected_at','server_at','measurement_at','max_age_seconds','error','boiler_status'].includes(p.key)));
  function shown(p) {
    const v = d?.state?.[p.key]?.value;
    if (!current) return p.key === 'declared_demand' ? t('pilotes.netatmo.unknown') : '—';
    if (p.key === 'declared_demand') return t('pilotes.netatmo.' + (v==='on'?'demand_on':v==='off'?'demand_off':'unknown'));
    if (v == null) return t('pilotes.netatmo.missing');
    if (typeof v === 'number') return `${num(v, Number.isInteger(v) ? 0 : 1)}${p.unit ? ' ' + p.unit : ''}`;
    return String(v);
  }
</script>

<section class="card" data-netatmo-status={quality}>
  <div class="card-head"><h3>{nameOf(id, name)}</h3><span class="chip" class:warning={!current}>{t('pilotes.netatmo.'+(current?'current_short':quality==='stale'?'stale_short':'unavailable_short'))}</span></div>
  {#if primary.length}<div class="readings">{#each primary as p (p.key)}<div><small>{p.key==='declared_demand'?t('pilotes.netatmo.declared_demand'):p.label}</small><strong>{shown(p)}</strong></div>{/each}</div>{/if}
  {#if mode!=null}<p class="mode">{t('pilotes.netatmo.mode')} : {String(mode)}</p>{/if}
  <p class="muted receipt">{t('pilotes.netatmo.'+age.key,{count:age.count,seconds:age.seconds})}</p>
  {#if !current}<p role="status">{t('pilotes.netatmo.'+(quality==='error'?'status_error':quality))}</p>{/if}
  <details><summary>{t('pilotes.netatmo.details')}</summary>
    <p class="muted">{t('pilotes.netatmo.time_caution')}</p>
    {#if d?.state?.error?.value}<p role="status">{d.state.error.value}</p>{/if}
    {#if Number.isFinite(collected)&&collected>0}<p>{t('pilotes.netatmo.received',{time:clock(collected),age:Math.max(0,Math.floor((now-collected)/1000))})}</p>{/if}
    {#if Number.isFinite(server)&&server>0}<p>{t('pilotes.netatmo.cloud_received',{time:clock(server),age:Math.max(0,Math.floor((now-server)/1000))})}</p>{/if}
    <dl>{#each rows as p (p.key)}<dt>{p.label}</dt><dd>{shown(p)}</dd>{/each}</dl>
  </details>
</section>

<style>
  dl { display:grid; grid-template-columns:1fr 1fr; gap:8px 16px; }
  dt { color:var(--ink-3); }
  dd { margin:0; text-align:right; }
  h3 {margin:0;font-size:1rem;overflow-wrap:anywhere;} .warning {color:var(--alert);}
  .readings {display:flex;flex-wrap:wrap;gap:18px;margin-top:14px;} small {display:block;color:var(--ink-3);} strong {display:block;font-size:1.7rem;line-height:1.5;}
  p {margin:8px 0;} .receipt {font-size:.85rem;} summary {min-height:44px;display:flex;align-items:center;cursor:pointer;}
</style>
