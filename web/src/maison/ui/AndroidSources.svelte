<script>
  import { home, hub, device, value, num, clock } from '../lib/home.svelte.js';
  import { sourceStatus } from '../lib/source-status.js';
  import { t } from '../../lib/i18n.svelte.js';
  const cfg = $derived(home.config.android);
  const tablet = $derived(device(cfg.tablet));
  const weather = $derived(device(cfg.weather));
  const status = (d, keys, age) => sourceStatus(hub.connected, d, hub.drivers.find((v) => v.instance === d?.instance), keys, home.now, age);
  const w = $derived(status(weather, ['temperature','humidity'], 930000));
  const b = $derived(status(tablet, ['battery','charging','network'], 65000));
  const rooms = $derived(home.config.rooms ?? []);
</script>

<div class="sources">
  <section class="card">
    <div class="card-head"><h2>{t('maison.android.weather')}</h2><a href="/android/settings">{t('maison.android.settings')}</a></div>
    <p>{cfg.location || t('maison.android.no_location')}</p>
    <p class="chip" class:good={w.state === 'fresh'}>{t('maison.android.' + w.state)}</p>
    {#if w.ts}
      <p class="reading">{num(value(cfg.weather,'temperature'),1)} °C · {num(value(cfg.weather,'humidity'))} %</p>
      <p class="muted">{t('maison.android.received', {time: clock(w.ts)})}</p>
    {/if}
    <p class="muted">{t('maison.android.weather_help')}</p>
    <a href="#/dehors">{t('maison.android.weather_detail')}</a>
  </section>
  <section class="card">
    <div class="card-head"><h2>{t('maison.android.tablet')}</h2><span class="chip" class:good={b.state === 'fresh'}>{t('maison.android.' + b.state)}</span></div>
    <p class="reading">{num(value(cfg.tablet,'battery'))} %</p>
    <p>{value(cfg.tablet,'charging') == null ? '—' : t(value(cfg.tablet,'charging') ? 'maison.android.plugged' : 'maison.android.on_battery')}</p>
    <p>{t('maison.android.network')}: {['wifi','ethernet','offline','other'].includes(value(cfg.tablet,'network')) ? t('maison.android.net_' + value(cfg.tablet,'network')) : '—'}</p>
    {#if b.ts}<p class="muted">{t('maison.android.received',{time:clock(b.ts)})}</p>{/if}
    <p class="muted">{t('maison.android.tablet_help')}</p>
  </section>
  <section class="card">
    <div class="card-head"><h2>{t('maison.android.rooms')}</h2><a href="/android/settings">{t('maison.android.settings')}</a></div>
    {#each rooms as room (room.name)}<span class="chip">{room.name}</span>{:else}<p>{t('maison.android.no_rooms')}</p>{/each}
    <p class="muted">{t('maison.android.rooms_help')}</p>
    <a href="#/pieces">{t('maison.android.open_rooms')}</a>
  </section>
</div>
<style>
 .sources { display: grid; grid-template-columns: repeat(auto-fit,minmax(min(100%,270px),1fr)); gap:20px; }
 .reading {font-size:26px;font-weight:650;}
 p {margin:12px 0;}
 .chip {margin:4px;}
 a {min-height:44px;display:inline-flex;align-items:center;}
</style>
