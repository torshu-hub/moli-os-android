// Synthetic offline transport for the production vehicle card; never a real account.
window.code21Commands=[];window.code21Reads=[];
const id='hyundai-test:v_synthetic';
const state=Object.fromEntries(Object.entries({soc:68,soh:97,battery_12v:80,odometer:12345,ev_range_km:270,charging:false,quality:'cached_recent',vehicle_at:Date.now(),cloud_at:Date.now(),received_at:Date.now(),location_enabled:false,capabilities:JSON.stringify([{key:'lock',available:true,pin:true,parameters:{}},{key:'start_climate',available:true,pin:true,parameters:{temperature:{min:17,max:27,step:.5},duration:{min:1,max:30}}},{key:'set_navigation',available:false,reason:'reference_gspa_not_implemented',parameters:{}}])}).map(([key,value])=>[key,{value,ts:Date.now()}]));
const device={id,native_name:'Ioniq 5 test',manufacturer:'Hyundai',model:'IONIQ 5',online:true,label:{},points:Object.keys(state).map((key)=>({key,label:key,kind:{type:'text'},access:{read:true,write:false}})),state};
window.EventSource=class extends EventTarget{constructor(){super();setTimeout(()=>this.dispatchEvent(new MessageEvent('snapshot',{data:JSON.stringify({devices:[device],drivers:[],approvals:[]})})),0);}close(){}};
window.fetch=async(path,options={})=>{
 const url=new URL(path,location.href);
 if(options.method==='POST'){if(url.pathname!=='/api/command')throw Error('Unexpected write');window.code21Commands.push(JSON.parse(options.body));return new Response('{}',{status:200});}
 window.code21Reads.push(url.pathname);
 const journal={schema:1,timezone:'Europe/Paris',days:[{day:'2026-11-01',status:'provider_daily',provider:{distance_km:100,consumed_wh:16000,regenerated_wh:3000},kwh_per_100km:16,charges:[],weather:{mean_celsius:10}},{day:'2026-11-02',status:'collection_gap',charges:[]}],intervals:[]};
 const replies={'/api/home':{},'/api/session':{human:true,setup:false,language:'fr'},'/api/health':{stats:{},guard:{protected_rooms:[]}},'/api/bench':{},'/api/journal':[],'/api/ambiances':[],['/api/vehicles/'+encodeURIComponent(id)+'/journal']:journal};
 return new Response(JSON.stringify(replies[url.pathname]??{}),{status:url.pathname in replies?200:404});
};
