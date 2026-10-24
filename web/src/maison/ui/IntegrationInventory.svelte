<script>
  import { t } from '../../lib/i18n.svelte.js';
  let { rows, platform } = $props();
</script>

<section class="card inventory" aria-labelledby="inventory-title">
  <h2 id="inventory-title">{t('systeme.inventory.title')}</h2>
  <p class="muted">{t('systeme.inventory.help')}</p>
  {#if platform === 'android'}<p>{t('systeme.inventory.android')}</p>{/if}
  <p class="muted">{t('systeme.inventory.external')}</p>
  {#if rows}
    <div class="scroll">
      <table>
        <thead><tr>
          <th scope="col">{t('systeme.inventory.integration')}</th>
          <th scope="col">{t('systeme.inventory.compiled')}</th>
          <th scope="col">{t('systeme.inventory.configured')}</th>
          <th scope="col">{t('systeme.inventory.runtime')}</th>
          <th scope="col">{t('systeme.inventory.hardware')}</th>
        </tr></thead>
        <tbody>
          {#each rows as row, index (index)}
            <tr>
              <th scope="row">{row.id}<small>{row.kind}</small>
                {#if platform === 'android' && ['host','presence'].includes(row.kind)}<small>{t('systeme.inventory.android_host')}</small>{/if}
              </th>
              <td>{t(row.compiled ? 'systeme.inventory.yes' : 'systeme.inventory.no')}</td>
              <td>
                {#each row.instances as instance}
                  <div>{instance.instance} · {t(instance.runtime_only ? 'systeme.inventory.internal' : instance.enabled ? 'systeme.inventory.enabled' : 'systeme.inventory.disabled')}</div>
                {:else}{t('systeme.inventory.unconfigured')}{/each}
              </td>
              <td>
                {#each row.instances as instance}
                  <div>{instance.instance} · {t('systeme.inventory.' + (['starting','running','backoff','waiting','stopped'].includes(instance.status?.state) ? instance.status.state : 'unknown'))}</div>
                {:else}{t('systeme.inventory.unknown')}{/each}
              </td>
              <td>{t('systeme.inventory.unproven')}</td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {:else}<p>{t('systeme.inventory.unknown')}</p>{/if}
</section>
<style>
  .inventory { margin-bottom: 24px; }
  .scroll { overflow-x: auto; }
  table { width:100%; border-collapse:collapse; text-align:left; }
  th,td { padding:12px; border-bottom:1px solid var(--border, #8884); vertical-align:top; }
  small { display:block; font-weight:normal; }
</style>
