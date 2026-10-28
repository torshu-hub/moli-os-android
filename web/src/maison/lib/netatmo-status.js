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

export function netatmoInstances(devices,drivers,diagnostics) {
  const rows=new Map();
  for(const row of diagnostics||[])if(row.kind==='netatmo')rows.set(row.instance,{...row,devices:[]});
  for(const driver of drivers||[])if(driver.kind==='netatmo'&&!rows.has(driver.instance))rows.set(driver.instance,{instance:driver.instance,configured:true,enabled:true,devices:[]});
  for(const device of Object.values(devices||{}))if(device.manufacturer==='Netatmo'||rows.has(device.instance)){
    if(!rows.has(device.instance))rows.set(device.instance,{instance:device.instance,configured:true,devices:[]});
    rows.get(device.instance).devices.push(device);
  }
  return [...rows.values()];
}
export function netatmoOverview(row,driver,connected,now) {
  if(!row?.configured)return 'not_configured';
  if(row.enabled===false)return 'disabled';
  if(row.credential_present===false)return 'credentials_required';
  if(!connected)return 'engine_unavailable';
  const failed=Object.entries(row.stages||{}).find(([,s])=>s.last_error_at>0&&s.last_error_at>=row.last_attempt_at&&s.status!=='ok'&&s.status!=='pending');
  if(failed){const status=failed[1].status;return 'error_'+(failed[0]==='renewal'&&status==='credentials'?'renewal':['timeout','dns','connect','tls','headers','body','cycle_timeout'].includes(status)?'transport':status);}
  if((row.devices||[]).some(d=>netatmoStatus(d,driver,connected,now)==='cloud_recent'))return 'cloud_recent';
  if((row.devices||[]).length)return 'no_current_data';
  return row.last_publish_at>0?'no_devices':'waiting_first_read';
}

export function netatmoAge(at,now) {
  if(!Number.isFinite(at)||at<=0||at>now)return {key:'age_unknown',count:0};
  const seconds=Math.floor((now-at)/1000);
  return seconds<60?{key:'age_seconds',count:seconds}:seconds<120?{key:'age_minutes_seconds',count:1,seconds:seconds%60}:seconds<3600?{key:'age_minutes',count:Math.floor(seconds/60)}:{key:'age_hours',count:Math.floor(seconds/3600)};
}
export const netatmoCardPriority=d=>d?.model==='room'?0:['NATherm1','OTM'].includes(d?.model)?1:2;
