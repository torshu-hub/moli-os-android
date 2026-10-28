"""Prepare the reviewed main-only delivery workflow. Never activates it or accesses a key."""
import hashlib
from pathlib import Path
import yaml

ROOT=Path(__file__).resolve().parents[2]
class Dumper(yaml.SafeDumper):
    def ignore_aliases(self,data):return True
def string(dumper,value):
    return dumper.represent_scalar('tag:yaml.org,2002:str',value,style='|' if '\n' in value else None)
Dumper.add_representer(str,string)

def inline(*paths):
    parts=[]
    for path in paths:
        source=(ROOT/path).read_text()
        parts.append('\n'.join(line for line in source.splitlines() if not line.startswith(('from policy import ', 'from protocol import '))))
    return 'python3 -I - <<\'PYCODE\'\n'+'\n\n'.join(parts)+'\nPYCODE\n'

pins={str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest()
      for folder in ['tools/android-ci','tools/android-signing'] for path in (ROOT/folder).glob('*.py')
      if path.name not in ['render_workflow.py'] and not path.name.startswith('test_')}
for path in ['.github/workflows/'+p+'.yml' for p in ['android-integrations','check','audit','source-hygiene']]+['tools/android-ci/build_candidate.sh','scripts/test-android-integrations.sh','scripts/test-android-beta-update.sh','scripts/check-android-beta.py','scripts/test-android-beta-metadata.py','scripts/verify-android-apk.py']:
    pins[path]=hashlib.sha256((ROOT/path).read_bytes()).hexdigest()
gate=inline('tools/android-ci/policy.py','tools/android-ci/protocol.py','tools/android-ci/delivery_gate.py')
gate=gate.replace('PIPELINE_PINS = {}','PIPELINE_PINS = '+repr(dict(sorted(pins.items()))))
JAVA='actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3'
UPLOAD='actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02'
DOWNLOAD='actions/download-artifact@d3f86a106a0bac45b974a628896c90dbdf5c8093'
sdk=[{'uses':JAVA,'with':{'distribution':'temurin','java-version':'21'}},{'name':'Prepare SDK before secret access','run':'"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "build-tools;35.0.0"'}]
main="github.ref == 'refs/heads/main' && github.repository == 'torshu-hub/moli-os-android'"
workflow={'name':'Sign owner beta','on':{'workflow_run':{'workflows':['Android profile import'],'types':['completed'],'branches':['android/beta-ready']},'workflow_dispatch':None},
    'permissions':{'contents':'read','actions':'read'},'concurrency':{'group':'owner-beta-signing','cancel-in-progress':False},'jobs':{
    'gate':{'if':main,'runs-on':'ubuntu-24.04','timeout-minutes':15,'outputs':{x:'${{ steps.gate.outputs.'+x+' }}' for x in ['approved','metadata','version','unsigned_sha256']},'steps':[{'id':'gate','name':'Verify owner milestone, checks, policy and exact artifact','env':{'GH_TOKEN':'${{ github.token }}'},'run':gate},{'uses':UPLOAD,'if':"steps.gate.outputs.approved == 'true'",'with':{'name':'validated-beta','path':'validated-beta/*','retention-days':1,'if-no-files-found':'error'}}]},
    'sign':{'needs':'gate','if':"needs.gate.outputs.approved == 'true' && "+main,'runs-on':'ubuntu-24.04','timeout-minutes':10,'environment':'android-beta-signing','steps':[*sdk,{'uses':DOWNLOAD,'with':{'name':'validated-beta','path':'validated-beta'}},{'name':'Sign validated bytes with existing owner certificate','env':{'BETA_SIGNING_BUNDLE':'${{ secrets.BETA_SIGNING_BUNDLE }}','EXPECTED_CERTIFICATE':'ce51eb0755d1e4fd5043d833108f0ed374f44cdac8734e4cf63f2efe1bc3bf37','UNSIGNED_SHA256':'${{ needs.gate.outputs.unsigned_sha256 }}','BETA_METADATA':'${{ needs.gate.outputs.metadata }}','UNSIGNED_APK':'validated-beta/unsigned.apk','SIGNED_OUTPUT':'signed'},'run':inline('tools/android-signing/sign_step.py')},{'run':'cp validated-beta/candidate.json signed/release/candidate.json'},{'uses':UPLOAD,'with':{'name':'signed-beta-internal','path':'signed/release/*','retention-days':1,'if-no-files-found':'error'}}]},
    'publish':{'needs':['gate','sign'],'if':main,'permissions':{'contents':'write','actions':'read'},'runs-on':'ubuntu-24.04','timeout-minutes':10,'steps':[*sdk,{'uses':DOWNLOAD,'with':{'name':'signed-beta-internal','path':'release'}},{'name':'Independently verify and publish complete prerelease','env':{'GH_TOKEN':'${{ github.token }}'},'run':inline('tools/android-ci/policy.py','tools/android-ci/protocol.py','tools/android-ci/publish.py')},{'uses':UPLOAD,'with':{'name':'moli-os-${{ needs.gate.outputs.version }}-arm64','path':'release/*','retention-days':14,'if-no-files-found':'error'}}]}}}
(ROOT/'.github/workflows/android-beta-sign.yml').write_text('# PREPARED: owner approval is required before copying this file to main.\n'+yaml.dump(workflow,Dumper=Dumper,sort_keys=False,width=120))
