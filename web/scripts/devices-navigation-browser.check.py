"""Synthetic browser coverage for persistent navigation and the device projection."""
from pathlib import Path
import subprocess,time,json,shutil,os
from playwright.sync_api import sync_playwright
root=Path(__file__).resolve().parents[1]
html=root/'.devices-navigation-test.html';entry=root/'.devices-navigation-test.js';component=root/'.devices-navigation-test.svelte'
html.write_text('<div id="test"></div><script type="module" src="/.devices-navigation-test.js"></script>')
component.write_text('''<script>
import Maison from './src/maison/Maison.svelte';
let route=$state(location.hash.slice(2));
window.addEventListener('hashchange',()=>route=location.hash.slice(2));
</script><Maison {route}/>
''')
entry.write_text('''
import './scripts/fixtures/observation-fixture.js';
import {mount} from 'svelte';
import Maison from './.devices-navigation-test.svelte';
import {home,hub} from './src/maison/lib/home.svelte.js';
import {setLanguage} from './src/lib/i18n.svelte.js';
let missing=true,language='fr',stale=false,authorizations=1;
window.commandCalls=0;
const fixture=()=>{
 const ts=Date.now()-(stale?120000:1000),sample=value=>({value,ts});
 const devices=[1,2,3,4].map(n=>{
 const instance='android-meross-'+String(n).padStart(32,'0');
 return {id:instance+':'+String(n+100).padStart(32,'0'),instance,model:'MSS310',manufacturer:'Meross',native_name:'Synthetic plug '+n,online:true,label:{room:'Fixture room'},
  points:[{key:'on',access:{read:true,write:n<=authorizations},kind:'binary'},{key:'power',unit:'W',access:{read:true,write:false},kind:'numeric'}],state:{reported_model:sample('MSS310'),on:sample(n===1),power:sample(10+n)}};});
 const drivers=devices.map(d=>({instance:d.instance,kind:'profile',integration:'meross-mss310',devices:1,points:3,offline:0,status:{state:'running'}}));
 return {devices,drivers,diagnostics:[],stats:{devices:4,points:12,rss_bytes:1000000,uptime_ms:1000},guard:{protected_rooms:[],dashboard:'trusted'}};
};
const config=()=>missing?{}:{android:{meross:fixture().devices.map((d,n)=>({id:d.instance.slice(15),name:d.native_name,enabled:true,uuid:d.id.slice(d.instance.length+1),manual_control:n<authorizations,critical_load:n===3}))}};
window.fetch=async (url,options={})=>{
 const path=String(url).split('?')[0],snap=fixture();
 if(options.method&&options.method!=='GET'){window.commandCalls++;throw Error('No writes in navigation coverage');}
 let body=path==='/api/home'?config():path==='/api/devices'?snap:path==='/api/system'?{...snap,platform:'android',bricks:[],surfaces:[],perf:[],integrations:[]}:
  path==='/api/session'?{human:false,language,login_required:false,setup:false}:path==='/api/journal'?[]:path==='/api/assistant/exchanges'?{exchanges:[]}:{...snap,people:[],invites:[]};
 return new Response(JSON.stringify(body),{status:200,headers:{'content-type':'application/json'}});
};
window.EventSource=class {addEventListener(type,fn){if(type==='snapshot')queueMicrotask(()=>fn({data:JSON.stringify(fixture())}));}close(){}};
window.fixture=async(l,m,s=false,a=1)=>{
 language=l;missing=m;stale=s;authorizations=a;await setLanguage(l);const snap=fixture();home.config=config();home.platform='android';home.snapshotHealthy=true;home.now=Date.now();
 hub.connected=true;hub.observedAt=Date.now();hub.drivers=snap.drivers;hub.devices=Object.fromEntries(snap.devices.map(d=>[d.id,d]));window.fixtureObservation?.('on');
};
window.forceLongName=()=>{Object.values(hub.devices)[0].label.name='SyntheticLongDeviceName'.repeat(8);};
window.installObservationFixture(fixture);
mount(Maison,{target:document.querySelector('#test')});
window.ready=()=>home.platform==='android';
''')
server=subprocess.Popen(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','4184'],cwd=root,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:
 with sync_playwright() as p:
  browser=p.chromium.launch(**({'executable_path':shutil.which('chromium')} if shutil.which('chromium') else {}),args=['--no-sandbox'])
  page=browser.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  for _ in range(40):
   try:page.goto('http://127.0.0.1:4184/.devices-navigation-test.html#/appareils');break
   except Exception:time.sleep(.2)
  page.wait_for_function('window.ready?.()');page.wait_for_selector('[data-equipment=meross]')
  for width in [360,720,800,1280]:
   page.set_viewport_size({'width':width,'height':900})
   for language in ['fr','en']:
    for missing in [True,False]:
     page.evaluate('([l,m])=>window.fixture(l,m)',[language,missing]);page.evaluate("location.hash='#/appareils'")
     page.wait_for_timeout(70)
     assert page.locator('[data-equipment=meross]').count()==4,(width,language,missing)
     assert page.locator('[data-meross-manual]').count()==1
     assert page.locator('[data-configure-meross]').count()==4
     assert page.locator('[data-equipment=meross] h3').first.inner_text()=='Synthetic plug 1'
     for target in ['#/appareils','#/systeme']:
      link=page.locator('.rail-quick a[href="'+target+'"]');assert link.is_visible()
      box=link.bounding_box();assert box['x']>=0 and box['x']+box['width']<=width and box['y']>=0 and box['y']+box['height']<=900,(target,box,width)
     if width<=760:
      assert page.locator('.nav-scroll-hint').is_visible()
      assert page.evaluate("(()=>{let n=document.querySelector('.rail ul');return n.scrollWidth>n.clientWidth})()")
     assert page.evaluate('document.documentElement.scrollWidth<=innerWidth'),(width,language,missing,'devices overflow')
     page.locator('.rail-quick a[href="#/systeme"]').click();page.wait_for_selector('[data-system-graph]');page.evaluate('window.forceLongName()');page.wait_for_timeout(70)
     assert page.evaluate('document.documentElement.scrollWidth<=innerWidth'),(width,language,missing,'system overflow')
     assert not page.locator('[data-system-render-error]').count()
     page.locator('.rail-quick a[href="#/appareils"]').click()
     page.evaluate('([l,m])=>window.fixture(l,m,true)',[language,missing]);page.wait_for_timeout(40)
     assert '11 W' not in page.locator('[data-equipment=meross] > dl').first.inner_text()
     assert page.evaluate('window.commandCalls')==0
  for width in [360,720,1280]:
   page.set_viewport_size({'width':width,'height':900})
   for language in ['fr','en']:
    for missing in [True,False]:
     for count in [0,1,2,4]:
      page.evaluate('([l,m,n])=>window.fixture(l,m,false,n)',[language,missing,count]);page.wait_for_timeout(70)
      assert page.locator('[data-meross-manual]').count()==count,(width,language,missing,count)
      assert page.locator('[data-meross-on]:not(:disabled)').count()==count
      assert page.locator('[data-configure-meross]').count()==4
      assert page.locator('.critical-use').count()==(0 if missing else 1)
      assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
      assert page.evaluate('window.commandCalls')==0
  page.evaluate("window.fixture('fr',false,true,4)");page.wait_for_timeout(70)
  assert page.locator('[data-meross-manual]').count()==4
  assert page.locator('[data-meross-on]:not(:disabled)').count()==0
  for language in ['fr','en']:
   for width,height in [(720,1100),(1280,800)]:
    page.set_viewport_size({'width':width,'height':height});page.evaluate('l=>window.fixture(l,false,false,4)',language);page.evaluate("window.fixtureObservation('on')")
    page.wait_for_selector('[data-observation-source] option[value^="android-netatmo"]',state='attached')
    page.locator('[data-observation-source]').select_option('android-netatmo-personal:synthetic-thermostat')
    target=page.locator('[data-observation-target] option').nth(1).get_attribute('value');page.locator('[data-observation-target]').select_option(target)
    page.locator('[data-observation-save]').click();page.wait_for_function("document.querySelector('[data-observation-intention]')?.textContent==='ON'")
    assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
    page.evaluate("window.fixtureObservation('off')");page.locator('[data-observation-refresh]').click();page.wait_for_function("document.querySelector('[data-observation-intention]')?.textContent==='OFF'")
    for mode in ['unknown','null','boolean','old','future','missing','source_offline','target_offline','identity','coherence']:
     page.evaluate('m=>window.fixtureObservation(m)',mode);page.locator('[data-observation-refresh]').click();page.wait_for_timeout(100)
     assert page.locator('[data-observation-intention]').inner_text() not in ['ON','OFF'],(language,width,mode)
    page.evaluate("window.fixtureObservation('on');window.restartObservationFixture()");page.locator('[data-observation-refresh]').click();page.wait_for_function("document.querySelector('[data-observation-intention]')?.textContent==='ON'")
    page.reload();page.wait_for_function("document.querySelector('[data-observation-intention]')?.textContent==='ON'")
    assert page.locator('[data-observation-source]').input_value()=='android-netatmo-personal:synthetic-thermostat'
    assert page.evaluate('window.observationFixtureState.commands')==0
  assert not errors,errors
  page.set_viewport_size({'width':720,'height':1000});page.evaluate("window.fixture('fr',true)");page.wait_for_timeout(70)
  if os.environ.get('MOLI_NAV_SCREENSHOT'):page.screenshot(path=os.environ['MOLI_NAV_SCREENSHOT'],full_page=False)
  browser.close()
 print('PASS FR/EN 360/720/800/1280: missing/present home projection, four named plugs, no implicit control, stale masking, persistent System/My devices, scrolling rail, graph layout, zero writes; 0/1/2/4 independent controls with critical-use labels; explicit Netatmo/Meross observation selection, invalid/stale/offline/restart masking, persistent choice, strict zero-command observation.')
finally:
 server.terminate();server.wait(timeout=10)
 for file in [html,entry,component]:file.unlink(missing_ok=True)
