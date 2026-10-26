/** View projection only. Never grants a capability or changes an association. */
export function merossCards(config,devices) {
 const published=Object.values(devices||{});
 const rows=Array.isArray(config?.meross)?config.meross:published
  .filter(d=>d.instance?.startsWith('android-meross-')&&String(d.model).toLowerCase()==='mss310')
  .map(d=>({id:d.instance.slice('android-meross-'.length),enabled:true,name:d.label?.name||d.native_name,
    manual_control:d.points?.some(p=>p.key==='on'&&p.access?.write),manual_uuid:d.id?.slice(d.instance.length+1)}));
 return rows.filter(e=>e.enabled).map(e=>{
  const instance='android-meross-'+e.id,data=published.find(d=>d.instance===instance);
  return {kind:'meross',instance,name:data?.label?.name||e.name||data?.native_name,manual:e.manual_control===true,
   uuid:e.manual_uuid||e.uuid,data,key:'reported_model',age:65000};
 });
}
export function freshEquipmentSample(sample,device,driver,connected,now,maxAge=65000){
 return !!(connected&&device?.online&&driver?.status?.state==='running'&&sample?.value!=null&&
  Number.isFinite(sample.ts)&&sample.ts>0&&sample.ts<=now&&now-sample.ts<=maxAge);
}
