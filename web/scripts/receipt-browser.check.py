"""Targeted real-browser card layout/reactivity checks; synthetic data only."""
from pathlib import Path
import subprocess
import time
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[1]
harness = root / '.receipt-test.html'
entry = root / '.receipt-test.js'
harness.write_text('<div class="maison" id="test"></div><script type="module" src="/.receipt-test.js"></script>')
entry.write_text('''
import {mount} from 'svelte';
import Equipment from './src/maison/ui/AndroidEquipment.svelte';
import Sources from './src/maison/ui/AndroidSources.svelte';
import {home,hub} from './src/maison/lib/home.svelte.js';
import {setLanguage} from './src/lib/i18n.svelte.js';
import './src/maison/maison.css';
window.fixture = async (language, state) => {
 await setLanguage(language);
 const ts=1800000000000;
 home.now=ts+12000; home.snapshotHealthy=true;hub.connected=state!=='offline';hub.observedAt=ts;
 home.config={android:{printer:true,printer_id:'printer:synthetic',gateway:false,meross:[{id:'synthetic',enabled:true,name:'Long equipment title / Nom très long sans raccourcir les associations existantes'}],tablet:'tablet:synthetic',weather:'weather:synthetic'}};
 hub.drivers=['android-printer','android-meross-synthetic','tablet','weather'].map(instance=>({instance,status:{state:'running'}}));
 const time=state==='unknown'?null:state==='stale'?ts-120000:ts;
 const sample=value=>({value,ts:time});
 hub.devices=Object.fromEntries([
 {id:'printer:synthetic',instance:'android-printer',online:true,points:[{key:'responding',label:'Responding',access:{read:true,write:false}}],state:{responding:sample(true)}},
 {id:'meross:synthetic',instance:'android-meross-synthetic',online:true,points:[{key:'power',label:'Power',unit:'W',access:{read:true,write:false}}],state:{reported_model:sample('MSS310'),power:sample(12)}},
 {id:'tablet:synthetic',instance:'tablet',online:true,points:[],state:{battery:sample(50),charging:sample(true),network:sample('wifi')}},
 {id:'weather:synthetic',instance:'weather',online:true,points:[],state:{temperature:sample(12),humidity:sample(50)}}
 ].map(d=>[d.id,d]));
 if(state==='missing')hub.devices={};
};
window.advance=()=>home.now+=120000;
await window.fixture('fr','fresh');
mount(Equipment,{target:document.querySelector('#test')});
mount(Sources,{target:document.querySelector('#test')});
window.ready=true;
''')
server = subprocess.Popen(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','4178'],cwd=root,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:
 with sync_playwright() as p:
  browser=p.chromium.launch(executable_path='/usr/bin/chromium',args=['--no-sandbox'])
  page=browser.new_page()
  for attempt in range(40):
   try: page.goto('http://127.0.0.1:4178/.receipt-test.html'); break
   except Exception: time.sleep(.25)
  page.wait_for_function('window.ready')
  for width in [360,800]:
   page.set_viewport_size({'width':width,'height':1280})
   for language in ['fr','en']:
    for state in ['fresh','stale','unknown','offline','missing']:
     page.evaluate('([l,s])=>window.fixture(l,s)',[language,state])
     page.wait_for_timeout(40)
     assert page.evaluate('document.documentElement.scrollWidth <= window.innerWidth'),(width,language,state,'page overflow')
     assert page.locator('.receipt').count()==4
     assert page.evaluate('''()=>[...document.querySelectorAll('.receipt')].every(r=>{
       const card=r.closest('.card').getBoundingClientRect(), box=r.getBoundingClientRect();
       return box.right<=card.right+1 && box.left>=card.left && r.scrollWidth<=r.clientWidth+1;
     })'''),(width,language,state,'receipt overflow')
     if state=='fresh':
      assert page.locator('.receipt .notice').count()==0
      before=page.locator('[data-equipment-received] time').first.get_attribute('datetime')
      page.evaluate('window.advance()');page.wait_for_timeout(40)
      assert page.locator('[data-equipment-received] time').first.get_attribute('datetime')==before
      assert page.locator('[data-equipment=printer] [data-receipt-state=stale]').count()==1
     elif state=='missing': assert page.locator('.receipt time').count()==0
     else: assert page.locator('.receipt .notice').count()>0
     if width==800 and language=='fr' and state=='fresh':
      page.evaluate("window.fixture('fr','fresh')");page.wait_for_timeout(40)
      page.screenshot(path=str(root.parent/'work/freshness/cards-fr.png'),full_page=True)
  browser.close()
  print('Browser verified: FR/EN × 360/800 portrait × fresh/stale/unknown/offline/missing; no overflow; reactive age preserves receipt timestamp.')
finally:
 server.terminate();server.wait(timeout=10)
 harness.unlink(missing_ok=True);entry.unlink(missing_ok=True)
