#!/usr/bin/env python3
"""Small source guard for code7's acquired forms and the scanner's minimal permissions."""
import re,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
base='2ac0ed2326cf017554c47f186fb4d660c155fc70'
path='android/app/src/main/java/io/moli/android/ManagementActivity.java'
before=subprocess.check_output(['git','show',base+':'+path],text=True);after=Path(path).read_text()
for name in ['renderHome','renderCatalogue','catalogueRow','setEnabled','edit','renderForm','field','remember','save']:
    def body(source):
        start=re.search(r'(?m)^    private [^\n]*? '+re.escape(name)+r'\(',source).start()
        end=source.find('\n    private ',start+1)
        return source[start:end]
    assert body(before)==body(after),name+' must stay exact code7'
ns='{http://schemas.android.com/apk/res/android}'
old=ET.fromstring(subprocess.check_output(['git','show',base+':android/app/src/main/AndroidManifest.xml'],text=True))
new=ET.parse('android/app/src/main/AndroidManifest.xml').getroot()
perms=lambda doc:{p.attrib[ns+'name'] for p in doc.findall('uses-permission')}
assert perms(new)-perms(old)=={'android.permission.ACCESS_NETWORK_STATE','android.permission.CHANGE_WIFI_MULTICAST_STATE'}
assert not perms(old)-perms(new)
print('Code7 forms/home exact; only network-state and Wi-Fi multicast permissions added')
