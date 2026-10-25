<script>
  import { home, hub, device, nameOf, clock, num } from '../lib/home.svelte.js';
  import { netatmoStatus } from '../lib/netatmo-status.js';
  import { t } from '../../lib/i18n.svelte.js';

  let { id, name = null } = $props();
  const d = $derived(device(id));
  const quality = $derived(netatmoStatus(d, hub.drivers.find(r => r.instance === d?.instance), hub.connected && (!home.config?.android || home.snapshotHealthy), home.now));
  const current = $derived(quality === 'cloud_recent');
  const collected = $derived(d?.state?.collected_at?.value);
  const server = $derived(d?.state?.server_at?.value);
  const rows = $derived((d?.points ?? []).filter(p => !['freshness','collected_at','server_at','measurement_at','max_age_seconds','error','boiler_status'].includes(p.key)));
  function shown(p) {
    const v = d?.state?.[p.key]?.value;
    if (!current) return p.key === 'declared_demand' ? t('pilotes.netatmo.unknown') : '—';
    if (p.key === 'declared_demand') return t('pilotes.netatmo.' + (['on','off','missing'].includes(v) ? v : 'unknown'));
    if (v == null) return t('pilotes.netatmo.missing');
    if (typeof v === 'number') return `${num(v, Number.isInteger(v) ? 0 : 1)}${p.unit ? ' ' + p.unit : ''}`;
    return String(v);
  }
</script>

<section class="card" data-netatmo-status={quality}>
  <div class="card-head"><h2>{nameOf(id, name)}</h2><span class="chip">{t('pilotes.netatmo.read_only')}</span></div>
  <p>{t('pilotes.netatmo.' + (quality === 'error' ? 'status_error' : quality))}</p>
  <p class="muted">{t('pilotes.netatmo.time_caution')}</p>
  {#if Number.isFinite(collected) && collected > 0}
    <p class="muted">{t('pilotes.netatmo.received', { time: clock(collected), age: Math.max(0, Math.floor((home.now - collected) / 1000)) })}</p>
  {/if}
  {#if Number.isFinite(server) && server > 0}
    <p class="muted">{t('pilotes.netatmo.cloud_received', { time: clock(server), age: Math.max(0, Math.floor((home.now - server) / 1000)) })}</p>
  {/if}
  {#if d?.state?.error?.value}<p role="status">{d.state.error.value}</p>{/if}
  <dl>
    {#each rows as p (p.key)}<dt>{p.label}</dt><dd>{shown(p)}</dd>{/each}
  </dl>
</section>

<style>
  dl { display:grid; grid-template-columns:1fr 1fr; gap:8px 16px; }
  dt { color:var(--ink-3); }
  dd { margin:0; text-align:right; }
</style>
