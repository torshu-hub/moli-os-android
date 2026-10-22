<script>
  import { home, hub, device, clock, num } from '../lib/home.svelte.js';
  import { equipmentStatus, durationParts } from '../lib/equipment-status.js';
  import { t } from '../../lib/i18n.svelte.js';
  const cfg = $derived(home.config.android);
  const printer = $derived(device(cfg.printer_id));
  const gateway = $derived(Object.values(hub.devices).find(d => d.instance === 'android-gateway'));
  const cards = $derived([
    ...(cfg.printer ? [{kind:'printer', data:printer, key:'responding', age:65000}] : []),
    ...(cfg.gateway ? [{kind:'gateway', data:gateway, key:'connected', age:35000}] : []),
    ...(cfg.meross || []).filter(e => e.enabled).map(e => ({kind:'meross', instance:'android-meross-'+e.id, name:e.name,
      data:Object.values(hub.devices).find(d => d.instance === 'android-meross-'+e.id), key:'reported_model', age:65000})),
  ]);
  const status = (card) => equipmentStatus(card.kind,card.data,
    hub.drivers.find(d => d.instance === (card.instance || (card.kind === 'printer' ? 'android-printer' : 'android-gateway'))),
    hub.connected && home.snapshotHealthy,home.now,hub.observedAt);
  function shown(sample, point) {
    if (!sample || sample.value == null) return t('maison.equipment.not_reported');
    const v = sample.value;
    if(point.key==='uptime'){const parts=durationParts(v);return parts?t('maison.equipment.duration',parts):t('maison.equipment.not_reported');}
    if (point.key === 'energy_recent' && typeof v === 'number' && v >= 1000) return `${num(v/1000,3)} kWh`;
    if (point.key === 'on' && typeof v === 'boolean') return t(v ? 'maison.equipment.mss_on_state' : 'maison.equipment.mss_off_state');
    if (typeof v === 'boolean') return t(v ? 'maison.equipment.yes' : 'maison.equipment.no');
    if (typeof v === 'number') return `${num(v, Number.isInteger(v) ? 0 : point.unit === 'A' ? 3 : 1)}${point.unit ? ' ' + point.unit : ''}`;
    if (point.key === 'state' && ['idle','processing','stopped'].includes(v)) return t('maison.equipment.' + v);
    return String(v);
  }
</script>

<section class="equipment">
  <div class="card-head"><h2>{t('maison.equipment.title')}</h2><a href="/android/equipment">{t('maison.android.settings')}</a></div>
  <p class="muted">{t('maison.equipment.help')}</p>
  {#if !cards.length}<p>{t('maison.equipment.empty')}</p>{/if}
  <div class="devices">
    {#each cards as card (card.instance || card.kind)}
      {@const observed = status(card)}
      <article class="card" data-equipment={card.kind} data-equipment-status={observed.state} data-equipment-instance={card.instance}>
        <h3>{card.name || t('maison.equipment.' + card.kind)}</h3>
        <p class="chip" class:good={observed.state === 'fresh'}>{t('maison.android.' + observed.state)}</p>
        {#if card.data}
          <p>{[card.data.manufacturer,card.data.model].filter(Boolean).join(' ') || card.data.native_name}</p>
          {#if observed.ts}<p class="muted" data-equipment-received={observed.ts}>{t('maison.android.received',{time:clock(observed.ts)})} · {t('maison.equipment.age',{seconds:observed.age})}</p>{/if}
          <dl>
            {#each card.data.points.filter(p => p.access.read && !p.access.write && p.key !== 'responding') as point (point.key)}
              {@const sample = card.data.state?.[point.key]}
              <dt>{card.kind === 'meross' ? t('maison.equipment.mss_'+point.key) : point.label}</dt><dd>{shown(sample,point)}
                {#if sample?.ts}<small>{clock(sample.ts)} · {t('maison.equipment.age',{seconds:Math.max(0,Math.floor((observed.now-sample.ts)/1000))})}</small>{/if}
                {#if sample?.value != null && (sample.ts > home.now || home.now - sample.ts > (card.kind === 'meross' && ['energy_recent','energy_date'].includes(point.key) ? 660000 : card.kind === 'meross' && point.key === 'signal' ? 135000 : card.age))}
                  <small>{t('maison.android.stale')}</small>
                {/if}
              </dd>
            {/each}
          </dl>
        {/if}
        {#if card.kind === 'meross'}<p class="muted">{t('maison.equipment.meross_help')}</p>{/if}
        {#if observed.state==='fresh'}<p class="muted">{t('maison.equipment.read_confirmed')}</p>{/if}
        {#if observed.state !== 'fresh'}<p class="muted">{t(card.kind === 'meross' ? 'maison.equipment.meross_unavailable' : 'maison.equipment.unavailable_help')}</p>{/if}
      </article>
    {/each}
  </div>
</section>
<style>
 .equipment {margin-top:24px;}
 .devices {display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,300px),1fr));gap:20px;}
 dl {display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:8px 16px;overflow-wrap:anywhere;}
 dd {margin:0;text-align:right;} small {display:block;color:var(--muted);} p {margin:12px 0;overflow-wrap:anywhere;} a {min-height:44px;display:inline-flex;align-items:center;}
</style>
