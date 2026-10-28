/** A live connection and recent real samples are both required. */
export function sourceStatus(connected, device, driver, keys, now, maxAge) {
  const samples = keys.map((key) => device?.state?.[key]);
  const present = samples.every((s) => s && s.value !== null && s.value !== undefined && (typeof s.value !== 'number' || Number.isFinite(s.value)) && Number.isFinite(s.ts) && s.ts > 0);
  const ts = present ? Math.min(...samples.map((s) => s.ts)) : null;
  if (!connected || !device?.online || driver?.status?.state !== 'running' || !present) return { state: 'unavailable', ts };
  return { state: now >= ts && samples.every((s) => s.ts <= now && now - s.ts <= maxAge) ? 'fresh' : 'stale', ts };
}
