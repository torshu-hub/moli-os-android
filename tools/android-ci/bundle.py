"""Package the already-built unsigned APK and exact validation evidence, without keys."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
from policy import ALL_CLASSES, REPOSITORY, TOOLCHAINS, candidate_metadata, fingerprint, properties
from protocol import digest

UPDATE_FILES = ['beta-preview-export.txt','beta-legacy-export.txt','beta-migration-a.txt','beta-update-b.txt','beta-after-refusals.txt','beta-legacy-restore.txt']

def expected_reports(plan):
    return [c+'.txt' for c in plan['classes']] + (UPDATE_FILES if plan['update'] else [])

def check_report(name, data):
    if len(data)>262144 or b'OK (1 test)' not in data or b'FAILURES!!!' in data or b'INSTRUMENTATION_FAILED' in data:
        raise ValueError('Missing or failed instrumentation proof: '+name)

def local_tree():
    output = subprocess.check_output(['git','ls-tree','-r','HEAD'],text=True)
    entries=[]
    for line in output.splitlines():
        info,path=line.split('\t',1);mode,kind,blob=info.split()
        entries.append({'mode':mode,'type':kind,'sha':blob,'path':path})
    return entries

def main():
    plan=json.loads(Path('android-ci-plan.json').read_text())
    if not plan['candidate']: raise ValueError('Ready milestone required')
    metadata=candidate_metadata(properties(Path('android/beta.properties').read_text()),plan['previous'])
    root=Path('android-candidate');root.mkdir(exist_ok=False)
    apk=Path('android/app/build/outputs/apk/beta/app-beta-unsigned.apk')
    shutil.copyfile(apk,root/'unsigned.apk')
    proofs={}
    for name in expected_reports(plan):
        data=Path('android-artifacts/reports',name).read_bytes();check_report(name,data)
        (root/'proof').mkdir(exist_ok=True);(root/'proof'/name).write_bytes(data);proofs[name]=digest(data)
    units=0
    if not plan['reused']:
        for path in Path('android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
            result=ET.parse(path).getroot()
            if int(result.get('failures',0))+int(result.get('errors',0)): raise ValueError('Failed JVM proof')
            units+=int(result.get('tests',0))
        if units<24: raise ValueError('Existing JVM security/data checks are required')
    manifest={'format':1,'repository':REPOSITORY,'source_sha':plan['source_sha'],'run_id':int(os.environ['GITHUB_RUN_ID']),
        'run_attempt':int(os.environ['GITHUB_RUN_ATTEMPT']),'runtime_sha256':fingerprint(local_tree()),'toolchains':TOOLCHAINS,
        'previous_source_sha':plan['previous']['source_sha'],'metadata':metadata,'plan':plan,
        'unsigned_sha256':digest(apk.read_bytes()),'proofs':proofs,'jvm_tests':units}
    (root/'candidate.json').write_text(json.dumps(manifest,sort_keys=True)+'\n')
    print('Bounded unsigned candidate and validation evidence prepared:',metadata['versionName'])

if __name__=='__main__':main()
