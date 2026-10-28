import assert from 'node:assert/strict';
import { createServer } from 'vite';
// The shared browser model reads the bundle script at module load.
globalThis.document={querySelector:()=>null,documentElement:{}};
const server=await createServer({server:{middlewareMode:true},appType:'custom'});
try{
 const {default:Manual}=await server.ssrLoadModule('/src/maison/ui/MerossManual.svelte');
 const {render}=await server.ssrLoadModule('svelte/server');
 const {setLanguage}=await server.ssrLoadModule('/src/lib/i18n.svelte.js');
 const {home,hub}=await server.ssrLoadModule('/src/maison/lib/home.svelte.js');
 const uuid='00000000000000000000000000000001',instance='synthetic-reader';
 const data={id:`${instance}:${uuid}`,instance,online:true,points:[{key:'on',access:{write:true}}]};
 hub.connected=true;home.snapshotHealthy=true;hub.drivers=[{instance,status:{state:'running'}}];
 for(const [language,labels] of [['fr',['Allumer','Éteindre','Aucun minuteur Moli']],['en',['Turn on','Turn off','No Moli timer']]]){
  await setLanguage(language);
  const html=render(Manual,{props:{data,uuid,name:'Synthetic plug',fresh:true}}).body;
  for(const label of labels)assert.ok(html.includes(label),label);
  assert.ok(!html.includes('maison.meross_manual.'));
  assert.equal((html.match(/<button\b/g)||[]).length,2);
  assert.ok(!/<button[^>]*disabled/.test(html),'fresh bound controls available');
  for(const props of [{fresh:false},{uuid:'00000000000000000000000000000002'},{data:{...data,online:false}}]){
   const disabled=render(Manual,{props:{data,uuid,name:'Synthetic plug',fresh:true,...props}}).body;
   assert.equal((disabled.match(/<button[^>]*disabled/g)||[]).length,2);
  }
 }
 console.log('Manual Meross UI: FR/EN, bound fresh target, unavailable/stale/mismatched identity disabled.');
}finally{await server.close();delete globalThis.document;}
