<script>
  import { home, hub, device, clock, num } from '../lib/home.svelte.js';
  import { equipmentStatus, durationParts, sampleMaxAge } from '../lib/equipment-status.js';
  import { t } from '../../lib/i18n.svelte.js';
  import {merossCards,freshEquipmentSample} from '../lib/android-equipment.js';
  let {standalone=false}=$props();
  const firstSeen = Date.now();
  import Receipt from './Receipt.svelte';
  import MerossManual from './MerossManual.svelte';
  import { useReceiptClock } from '../lib/receipt-clock.svelte.js';
  const receiptClock = useReceiptClock();
  const receiptNow = $derived(receiptClock.now ?? home.now);
  const cfg = $derived(home.config?.android||{});
  const printer = $derived(device(cfg.printer_id));
  const gateway = $derived(Object.values(hub.devices).find(d => d.instance === 'android-gateway'));
  const cards = $derived([
    ...(cfg.printer ? [{kind:'printer', data:printer, key:'responding', age:65000}] : []),
    ...(cfg.gateway ? [{kind:'gateway', data:gateway, key:'connected', age:35000}] : []),
    ...merossCards(cfg,hub.devices),
  ]);
  const status = (card) => equipmentStatus(card.kind,card.data,
    hub.drivers.find(d => d.instance === (card.instance || (card.kind === 'printer' ? 'android-printer' : 'android-gateway'))),
    hub.connected && home.snapshotHealthy,receiptNow,hub.observedAt,firstSeen);
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

<section class="equipment" class:standalone data-my-devices>
  <div class="card-head">{#if standalone}<h1>{t('maison.equipment.title')}</h1>{:else}<h2>{t('maison.equipment.title')}</h2>{/if}<a href="/android/equipment">{t('maison.android.settings')}</a></div>
  {#if cards.some(c=>c.kind!=='meross')}<p class="muted">{t('maison.equipment.help')}</p>{/if}
  {#if !cards.length}<p>{t('maison.equipment.empty')}</p>{/if}
  <div class="devices">
    {#each cards as card (card.instance || card.kind)}
      {@const observed = status(card)}
      <article class="card" data-equipment={card.kind} data-equipment-status={observed.state} data-equipment-instance={card.instance}>
        <div class="equipment-head"><h3>{card.name || t('maison.equipment.' + card.kind)}</h3>
          <div data-equipment-received={observed.ts || undefined}><Receipt ts={observed.ts} now={observed.now} state={observed.state} present={!!card.data} /></div>
        </div>
        {#if card.data}
          <p>{[card.data.manufacturer,card.data.model].filter(Boolean).join(' ') || card.data.native_name}{#if card.data.label?.room || card.data.native_room} · {card.data.label?.room || card.data.native_room}{/if}</p>
          <dl>
            {#each card.data.points.filter(p => p.access.read && (!p.access.write||card.kind==='meross'&&p.key==='on') && p.key !== 'responding' && (card.kind!=='meross'||['on','power'].includes(p.key))) as point (point.key)}
              {@const sample = card.data.state?.[point.key]}
              <dt>{card.kind === 'meross' ? t('maison.equipment.mss_'+point.key) : point.label}</dt><dd>{shown(card.kind==='meross'&&!freshEquipmentSample(sample,card.data,hub.drivers.find(d=>d.instance===card.instance),hub.connected&&home.snapshotHealthy,observed.now)?null:sample,point)}
                {#if sample?.value != null && (!Number.isFinite(sample.ts) || sample.ts <= 0 || sample.ts > observed.now || observed.now - sample.ts > sampleMaxAge(card.kind,point.key))}
                  <small>{t(!Number.isFinite(sample.ts) || sample.ts <= 0 || sample.ts > observed.now ? 'maison.receipt.unknown' : 'maison.receipt.stale')}</small>
                {/if}
              </dd>
            {/each}
          </dl>
          <details data-equipment-details><summary>{t('maison.equipment.details')}</summary>
            <dl>
              {#each card.data.points.filter(p => p.access.read && (!p.access.write||card.kind==='meross'&&p.key==='on')) as point (point.key)}
                {@const sample=card.data.state?.[point.key]}
                <dt>{card.kind==='meross'?t('maison.equipment.mss_'+point.key):point.label}</dt>
                <dd>{shown(sample,point)} · {sample?.ts ? clock(sample.ts) : t('maison.equipment.not_reported')}
                  {#if sample?.ts}<small>{t('maison.equipment.age',{seconds:Math.max(0,Math.floor((observed.now-sample.ts)/1000))})}</small>{/if}
                </dd>
              {/each}
            </dl>
            {#if card.kind==='meross'&&!card.manual}<p>{t('maison.equipment.meross_help')}</p>{/if}
          </details>
        {/if}
        {#if card.kind === 'meross'}
          {#if card.critical}<p class="critical-use">{t('maison.meross_manual.critical')}</p>{:else if !card.criticalKnown}<p class="muted">{t('maison.meross_manual.critical_unknown')}</p>{/if}
          <a class="configure" href="/android/equipment?section=meross" data-configure-meross>{t('maison.android.settings')}</a>
          {#if card.manual}<MerossManual data={card.data} uuid={card.uuid} name={card.name} fresh={observed.state==='fresh'}/>{:else}<p class="muted">{t('maison.equipment.readonly')}</p>{/if}
        {/if}
        {#if ['stale','unavailable'].includes(observed.state)}<details><summary>{t('maison.receipt.help')}</summary><p>{t(card.kind === 'meross' ? 'maison.equipment.meross_unavailable' : 'maison.equipment.unavailable_help')}</p></details>{/if}
      </article>
    {/each}
  </div>
</section>
<style>
 .critical-use{font-weight:650;}
 .equipment-head {display:flex;align-items:flex-start;gap:12px;}
 .equipment-head h3 {flex:1;min-width:0;margin:0;overflow-wrap:anywhere;}
 .equipment-head > div {flex:0 1 6rem;min-width:0;}
 .equipment {margin-top:24px;} .equipment.standalone{margin-top:0;} .card-head{flex-wrap:wrap;} .configure{font-weight:650;}
 .devices {display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,300px),1fr));gap:20px;}
 dl {display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:8px 16px;overflow-wrap:anywhere;}
 dd {margin:0;text-align:right;} small {display:block;color:var(--muted);} p {margin:12px 0;overflow-wrap:anywhere;} a {min-height:44px;display:inline-flex;align-items:center;}
 summary {min-height:44px;cursor:pointer;display:flex;align-items:center;}
</style>
