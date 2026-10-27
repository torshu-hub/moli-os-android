"""Locked daily preparation UI, synthetic local API only; no equipment IO."""
from pathlib import Path
import json, shutil, subprocess, time
from playwright.sync_api import sync_playwright
root = Path(__file__).resolve().parents[1]
html = root / '.daily-heating-test.html'
entry = root / '.daily-heating-test.js'
component = root / '.daily-heating-test.svelte'
html.write_text('<div id="test"></div><script type="module" src="/.daily-heating-test.js"></script>')
component.write_text('''<script>
import DailyHeating from './src/maison/ui/DailyHeating.svelte';
let settings=$state({revision:1,epoch:42,selection:{source:'source:synthetic',target:'target:synthetic'},storage_available:true});
let dirty=$state(false);
window.changeDailySelection=()=>{settings={...settings,revision:settings.revision+1};dirty=true;};
</script><DailyHeating {settings} {dirty}/>
''')
entry.write_text('''
import {mount} from 'svelte';
import Daily from './.daily-heating-test.svelte';
import {setLanguage} from './src/lib/i18n.svelte.js';
await setLanguage(new URL(location.href).searchParams.get('lang')||'fr');
window.dailyPosts=[];let prepared=false,active=false;
window.dailyFailure=false;
window.fetch=async(url,opts={})=>{
 if(window.dailyFailure)throw Error('Synthetic transport failure');
 if(!['/api/observations/netatmo-meross/daily','/api/observations/netatmo-meross/daily/observation'].includes(url))throw Error('Unexpected API');
 if(opts.method==='POST'){
  const body=JSON.parse(opts.body);window.dailyPosts.push(body);
  if(url.endsWith('/observation'))active=body.action==='start';else prepared=true;
 }
 const watch=prepared&&window.dailyPosts.some(p=>p.action)?{active,fingerprint:'version',events:2,publications:1,clocks:1,omitted:0,last_at:Date.now(),reason:active?'would_on':'stopped',intention:active?true:null,actual:active?false:null,valid_until:active?Date.now()+60000:null,history:[{at:Date.now(),reason:'would_on',intention:true,actual:false,repeats:2}]}:null;
 const value={revision:1,epoch:42,state:'disabled',readiness:prepared?'ready':'disabled',activation_available:false,reason:'release_locked',already_on_may_remain_on:true,draft:prepared?{id:'synthetic',fingerprint:'version'}:null,observation:watch};
 return new Response(JSON.stringify(value),{status:200,headers:{'content-type':'application/json'}});
};
mount(Daily,{target:document.querySelector('#test')});window.ready=true;
''')
server = subprocess.Popen(['node','node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','4188'],cwd=root,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:
 with sync_playwright() as p:
  browser=p.chromium.launch(**({'executable_path':shutil.which('chromium')} if shutil.which('chromium') else {}),args=['--no-sandbox'])
  page=browser.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  for width in [360,800]:
   page.set_viewport_size({'width':width,'height':900})
   for language in ['fr','en']:
    url=f'http://127.0.0.1:4188/.daily-heating-test.html?lang={language}'
    for attempt in range(40):
     try:page.goto(url);break
     except Exception:time.sleep(.2)
    page.wait_for_function('window.ready')
    button=page.locator('[data-daily-prepare]')
    button.wait_for();button.click()
    expected='Scénario prêt à examiner' if language=='fr' else 'Scenario ready for review'
    page.wait_for_function('(text)=>document.querySelector("[data-daily-heating]").textContent.includes(text)',arg=expected)
    assert page.locator('[data-daily-heating=disabled]').count()==1
    assert page.locator('[data-daily-heating] button').count()==3
    page.locator('[data-daily-start]').click()
    page.wait_for_selector('[data-daily-observation=active]')
    assert page.locator('[data-daily-decision]').inner_text().find('ON')>=0
    assert page.evaluate('window.dailyPosts')==[{'revision':1,'epoch':42},{'revision':1,'epoch':42,'action':'start','fingerprint':'version'}]
    page.evaluate("window.dailyFailure=true;document.dispatchEvent(new Event('visibilitychange'))")
    page.wait_for_selector('[data-daily-observation=unknown]')
    assert page.locator('[data-daily-start]').is_disabled()
    assert page.locator('[data-daily-decision]').inner_text().find('ON')<0
    page.evaluate("window.dailyFailure=false;document.dispatchEvent(new Event('visibilitychange'))")
    page.wait_for_selector('[data-daily-observation=active]')
    assert len(page.evaluate('window.dailyPosts'))==2
    page.locator('[data-daily-stop]').click()
    page.wait_for_selector('[data-daily-observation=stopped]')
    assert page.evaluate('window.dailyPosts')[-1]['action']=='stop'
    assert len(page.evaluate('window.dailyPosts'))==3
    assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
    page.evaluate('window.changeDailySelection()');page.wait_for_timeout(50)
    assert button.is_disabled()
    assert expected not in page.locator('[data-daily-heating]').inner_text()
    assert len(page.evaluate('window.dailyPosts'))==3
  assert not errors,errors
  browser.close()
 print('Daily heating browser: FR/EN, 360/800 px, explicit dry start/stop, loss/recovery, exact version and changed selection PASS')
finally:
 server.terminate();server.wait(timeout=10)
 for file in [html,entry,component]:file.unlink(missing_ok=True)
