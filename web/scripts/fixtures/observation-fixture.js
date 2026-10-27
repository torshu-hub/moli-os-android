// Synthetic API responses for browser/WebView journeys only. Never imported by
// the shipped dashboard. Non-observation writes are trapped before networking.
window.installObservationFixture=function(base,options={}){
 const original=window.fetch.bind(window),key='moli-synthetic-observation-'+(options.key||'browser');
 let saved;try{saved=JSON.parse(localStorage.getItem(key)||'null');}catch{}
 const state={selection:saved?.selection||null,revision:saved?.revision||0,epoch:777,armed:false,mode:'on',transitions:0,last:'',commands:0,puts:0,observations:0};
 let lastSnapshot;
 const listeners=new Set(),sourceId='android-netatmo-personal:synthetic-thermostat';
 const makeSnapshot=()=>{
  const initial=typeof base==='function'?base():base,now=Date.now(),sample=value=>({value,ts:now});
  const source={id:sourceId,instance:'android-netatmo-personal',native_name:'Synthetic thermostat',model:'NATherm1',manufacturer:'Netatmo',online:state.mode!=='source_offline',
   points:[{key:'declared_demand',access:{read:true,write:false},kind:'text'}],state:{declared_demand:sample(state.mode==='off'?'off':state.mode==='boolean'?false:'on'),freshness:sample('cloud_recent'),collected_at:sample(now-1000),max_age_seconds:sample(300)}};
  const devices=[...initial.devices.filter(d=>d.id!==sourceId).map(d=>({...d,...(options.refreshSamples?{state:Object.fromEntries(Object.entries(d.state||{}).map(([key,sample])=>[key,{...sample,ts:now}]))}:{}),online:state.mode==='target_offline'&&d.id===state.selection?.target?false:d.online})),source];
  return {...initial,devices,drivers:[...initial.drivers.filter(d=>d.instance!==source.instance),{instance:source.instance,kind:'netatmo',integration:'netatmo',devices:1,points:4,offline:0,status:{state:'running'}}]};
 };
 const snapshot=()=>lastSnapshot||(lastSnapshot=makeSnapshot());
 const settings=()=>{
  const snap=snapshot(),choice=d=>({id:d.id,name:d.label?.name||d.native_name,instance:d.instance,model:d.model,online:d.online});
  return {revision:state.revision,epoch:state.epoch,storage_available:true,selection:state.selection,sources:snap.devices.filter(d=>d.model==='NATherm1').map(choice),targets:snap.devices.filter(d=>String(d.model).toLowerCase()==='mss310').map(choice)};
 };
 const emit=()=>{lastSnapshot=makeSnapshot();for(const fn of listeners)fn({data:JSON.stringify(snapshot())});};
 window.EventSource=class{addEventListener(type,fn){if(type==='snapshot'){this.fn=fn;listeners.add(fn);queueMicrotask(()=>fn({data:JSON.stringify(snapshot())}));}}close(){listeners.delete(this.fn);}};
 window.fixtureObservation=mode=>{state.mode=mode;emit();};
 window.restartObservationFixture=()=>{state.epoch++;state.armed=false;emit();};
 window.observationFixtureState=state;
 window.fetch=async(url,opts={})=>{
  const path=String(url).split('?')[0],method=opts.method||'GET';let body;
  if(path==='/api/observations/netatmo-meross'){
   if(method==='PUT'){
    const input=JSON.parse(opts.body);state.puts++;if(Object.keys(input).sort().join()!=='revision,source,target')return new Response('{}',{status:422});
    if(input.revision!==state.revision)return new Response('{}',{status:409});
    if(input.source===null&&input.target===null)state.selection=null;
    else{
     const choices=settings(),s=choices.sources.find(c=>c.id===input.source),t=choices.targets.find(c=>c.id===input.target);
     if(!s||!t)return new Response('{}',{status:422});state.selection={source:s.id,target:t.id,source_name:s.name,target_name:t.name};
    }
    state.revision++;state.armed=false;localStorage.setItem(key,JSON.stringify({selection:state.selection,revision:state.revision}));
   }
   body=settings();
  }else if(path==='/api/observations/netatmo-meross/observe'){
   state.observations++;if(Object.keys(JSON.parse(opts.body)).sort().join()!=='epoch,revision')return new Response('{}',{status:422});const now=Date.now();let reason='none',status='ready',demand=state.mode==='off'?'off':'on',intention=demand==='on',collected=now-1000;
   if(!state.selection){reason='unconfigured';status='unconfigured';}
   else if(!state.armed){state.armed=true;reason='restarted';status='unknown';}
   else{
    const reasons={unknown:'unknown_demand',null:'invalid_demand',boolean:'invalid_demand',old:'source_stale',future:'future_date',missing:'missing_date',source_offline:'source_offline',target_offline:'target_offline',identity:'identity',coherence:'coherence'};
    if(reasons[state.mode]){reason=reasons[state.mode];status=['old','future','missing','source_offline','target_offline'].includes(state.mode)?'stale':'unknown';}
   }
   if(state.mode==='old')collected=now-301000;if(state.mode==='future')collected=now+60000;if(state.mode==='missing')collected=null;
   if(status!=='ready'){demand='unknown';intention=null;}
   const stamp=status+reason+demand+state.revision;if(stamp!==state.last){state.last=stamp;state.transitions++;}
   if(status==='ready')collected=snapshot().devices.at(-1).state.collected_at.value;
   body={revision:state.revision,epoch:state.epoch,state:status,reason,demand,intention,observed_at:now,collected_at:collected,valid_until:status==='ready'?Math.min(now+60000,snapshot().devices.find(d=>d.id===state.selection.target).state.reported_model.ts+65000,collected+300000):null,transitions:state.transitions,witness:status==='ready'?['declared_demand','freshness','collected_at','max_age_seconds'].map(key=>[snapshot().devices.at(-1).state[key].value,snapshot().devices.at(-1).state[key].ts]).concat([[snapshot().devices.find(d=>d.id===state.selection.target).state.reported_model.value,snapshot().devices.find(d=>d.id===state.selection.target).state.reported_model.ts]]):null};
  }else if(path==='/api/observations/netatmo-meross/trial/cancel'){body={state:'cancelled',autonomous:false,prepared:null};
  }else if(method!=='GET'){
   state.commands++;window.commandCalls=(window.commandCalls||0)+1;throw Error('Physical writes forbidden in synthetic observation journey');
  }else if(path==='/api/devices')body=snapshot();
  else return original(url,opts);
  return new Response(JSON.stringify(body),{status:200,headers:{'content-type':'application/json'}});
 };
};
