import assert from 'node:assert/strict';
import { createServer } from 'vite';
globalThis.document = {querySelector: () => null, documentElement: {lang:'fr'}};
const server = await createServer({ server: { middlewareMode: true }, appType: 'custom' });
try {
  const { default: Receipt } = await server.ssrLoadModule('/src/maison/ui/Receipt.svelte');
  const { default: Equipment } = await server.ssrLoadModule('/src/maison/ui/AndroidEquipment.svelte');
  const { default: Sources } = await server.ssrLoadModule('/src/maison/ui/AndroidSources.svelte');
  const { render } = await server.ssrLoadModule('svelte/server');
  const { setLanguage } = await server.ssrLoadModule('/src/lib/i18n.svelte.js');
  const { home, hub } = await server.ssrLoadModule('/src/maison/lib/home.svelte.js');
  const ts = 1800000000000;
  for (const lang of ['fr','en']) {
    await setLanguage(lang);
    const draw = props => render(Receipt,{props:{ts,now:ts+12000,state:'fresh',present:true,...props}}).body;
    assert.match(draw({}),/>12 s</);
    assert.ok(!draw({}).includes('<time'));
    assert.ok(!draw({}).includes('datetime='));
    assert.match(draw({now:ts+13000}),/>13 s</);
    assert.ok(!draw({}).includes('class="notice"'));
    assert.match(draw({now:ts+120000}),/>2 min 0 s</);
    assert.match(draw({now:ts+7200000}),/>2 h</);
    for(const state of ['stale','unavailable','checking']) assert.match(draw({state}),new RegExp(`data-receipt-state="${state}"`));
    assert.match(draw({ts:null}),/data-receipt-state="unknown"/);
    assert.match(draw({ts:null,present:false}),/data-receipt-state="waiting"/);
    assert.match(draw({now:ts-1}),/data-receipt-state="unknown"/);
    assert.ok(!draw({now:ts-1}).includes('>0 s<'));
    assert.ok(!draw({ts:null}).includes('<time'));
    home.config={android:{printer:true,printer_id:'printer:synthetic',gateway:false,meross:[],tablet:'tablet:synthetic',weather:'weather:synthetic'}};
    home.now=ts+12000;home.snapshotHealthy=true;hub.connected=true;hub.observedAt=ts;
    hub.drivers=[{instance:'android-printer',status:{state:'running'}}];
    hub.devices={'printer:synthetic':{id:'printer:synthetic',instance:'android-printer',online:true,points:[{key:'responding',label:'Responding',access:{read:true,write:false}}],state:{responding:{value:true,ts}}}};
    let html=render(Equipment).body;
    assert.match(html,new RegExp(`data-equipment-received="${ts}"`));
    assert.match(html,/data-equipment-details/);
    const visible=html.replace(/<[^>]*>/g,'');
    assert.ok(!visible.includes('Dernière réception :') && !visible.includes('Last received:'));
    assert.ok(!html.includes('Données récentes') && !html.includes('Fresh data'));
    home.now=ts+90000;html=render(Equipment).body;
    assert.match(html,/data-equipment-status="stale"/);
    assert.match(html,/data-receipt-state="stale"/);
    assert.match(render(Sources).body,/data-tablet-status="unavailable"/);
  }
  console.log('Receipt + Android card rendering: FR/EN, age, stale/offline/unknown/missing/future, details and diagnostic hooks verified.');
} finally { await server.close(); }
