<script>
  import { segments, dewarp } from '../lib/vehicle-svm.js';
  import { t } from '../../lib/i18n.svelte.js';
  let { view } = $props();
  let img = $state(null), canvas = $state(), list = $state([]), chosen = $state(0), rectify = $state(false), unavailable = $state(false);
  function load(event) { img = event.currentTarget; list = segments(view.image_sizes, img.naturalWidth, img.naturalHeight); }
  $effect(() => { if (!canvas || !img || !list.length) return; const s = list[chosen]; canvas.width = s.width; canvas.height = s.height; const ctx = canvas.getContext('2d'); ctx.drawImage(img, s.x, 0, s.width, s.height, 0, 0, s.width, s.height); unavailable = false; if (rectify && chosen < 4) { const fov = Array.isArray(view.angles) && view.angles.length >= 28 ? view.angles[chosen * 7 + 1] : null; const pixels = ctx.getImageData(0, 0, s.width, s.height); const data = dewarp(pixels.data, s.width, s.height, fov); if (data) ctx.putImageData(new ImageData(data, s.width, s.height), 0, 0); else unavailable = true; } });
</script>
<img alt={t('vehicle.svm')} src={`data:${view.content_type};base64,${view.base64}`} onload={load} class:composite={list.length > 0} />
{#if list.length}<nav>{#each list as s, i}<button class:active={chosen === i} onclick={() => chosen = i}>{t(`vehicle.svm_${s.key}`)}</button>{/each}</nav><label><input type="checkbox" bind:checked={rectify} />{t('vehicle.dewarp')}</label><canvas bind:this={canvas}></canvas>{#if unavailable}<p>{t('vehicle.dewarp_unavailable')}</p>{/if}{/if}
<style>img,canvas{max-width:100%;max-height:440px;object-fit:contain}.composite{display:none}nav{display:flex;gap:8px;flex-wrap:wrap}button{min-height:44px}.active{font-weight:bold}label{display:flex;align-items:center;gap:8px;margin:10px 0}</style>
