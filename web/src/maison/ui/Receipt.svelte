<script>
  import { t } from '../../lib/i18n.svelte.js';
  let { ts = null, now, state, present = false } = $props();
  const known = $derived(Number.isFinite(ts) && ts > 0);
  const valid = $derived(known && Number.isFinite(now) && ts <= now);
  const seconds = $derived(valid ? Math.floor((now-ts)/1000) : null);
  const age = $derived(seconds === null ? null : seconds < 60 ? `${seconds} s` : seconds < 3600 ? `${Math.floor(seconds/60)} min ${seconds%60} s` : seconds < 86400 ? `${Math.floor(seconds/3600)} h` : t('maison.receipt.days',{count:Math.floor(seconds/86400)}));
  const label = $derived(!known ? (present ? 'unknown' : 'waiting') : !valid ? 'unknown' : state);
</script>

<div class="receipt" class:warning={label !== 'fresh'} data-receipt-state={label}>
  {#if age !== null}<span aria-label={t('maison.receipt.elapsed',{age})}>{age}</span>{/if}
  {#if label !== 'fresh'}<span class="notice">{t('maison.receipt.'+label)}</span>{/if}
  {#if !valid && present && state === 'unavailable'}<span class="notice">{t('maison.receipt.unavailable')}</span>{/if}
</div>

<style>
  .receipt {flex:0 1 6rem;min-width:0;margin-left:auto;text-align:right;font-size:13px;line-height:1.4;color:var(--muted);font-variant-numeric:tabular-nums;overflow-wrap:anywhere;}
  span {display:block;}
  .notice {font-weight:600;color:var(--ink);}
  .warning .notice::before {content:'! ';}
</style>
