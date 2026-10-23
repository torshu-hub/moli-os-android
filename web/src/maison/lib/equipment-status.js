import { sourceStatus } from './source-status.js';

/** IGD status and uptime come from the same GetStatusInfo response. Uptime also emits SSE when connected is unchanged. */
export function equipmentStatus(kind, device, driver, connected, tick, receivedAt, firstSeen = null) {
  const now=Math.max(tick,receivedAt || 0);
  const uptime=device?.state?.uptime;
  const key=kind==='gateway' && Number.isFinite(uptime?.value) && uptime.value>=0?'uptime':kind==='gateway'?'connected':kind==='printer'?'responding':'reported_model';
  const result=sourceStatus(connected,device,driver,[key],now,kind==='gateway'?35000:65000);
  // Missing first samples and short link interruptions are not a confirmed outage.
  // Keep last values visible, but never label them live while the link is down.
  if(kind==='meross' && result.state==='unavailable' &&
      (result.ts!==null && now>=result.ts && now-result.ts<=65000 ||
       result.ts===null && firstSeen!==null && now>=firstSeen && now-firstSeen<=65000)) result.state='checking';
  return {...result,age:result.ts===null?null:Math.max(0,Math.floor((now-result.ts)/1000)),now};
}
export function sampleMaxAge(kind,key) {
  return kind==='meross' && ['energy_recent','energy_date'].includes(key)?660000:
    kind==='meross' && key==='signal'?135000:kind==='gateway'?35000:65000;
}
export function durationParts(value) {
  if(!Number.isFinite(value) || value<0)return null;
  const seconds=Math.floor(value);return {days:Math.floor(seconds/86400),hours:Math.floor(seconds%86400/3600),minutes:Math.floor(seconds%3600/60)};
}
