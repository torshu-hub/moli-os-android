/** A recent cloud reply is not a dated physical measurement. Never trust a
 * cached Running flag without the live hub and both explicit source times. */
export function netatmoStatus(device, driver, connected, now) {
  const v = (key) => device?.state?.[key]?.value;
  const collected = v('collected_at');
  const server = v('server_at');
  const maxAge = v('max_age_seconds') * 1000;
  const recent = (ts) => Number.isFinite(ts) && ts > 0 && ts <= now && now - ts <= maxAge;
  if (!connected || driver?.status?.state !== 'running') return 'unconfirmed';
  const quality = v('freshness');
  if (quality !== 'cloud_recent') return ['offline','missing','error','stale'].includes(quality) ? quality : 'unconfirmed';
  if (!Number.isFinite(maxAge) || maxAge <= 0 || !recent(collected) || !recent(server)) return 'stale';
  return device?.online ? 'cloud_recent' : 'unconfirmed';
}
