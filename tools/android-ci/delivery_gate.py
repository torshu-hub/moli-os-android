"""Trusted main-only gate, embedded inline. Never imports or executes candidate code."""
import io
import json
import os
import time
import urllib.error
import zipfile
from pathlib import Path
from policy import ALL_CLASSES, TOOLCHAINS, candidate_metadata, properties, select
from protocol import api, check_run, digest, file_bytes, fingerprint, previous_release, reusable_proof, tree, sha, READY_BRANCH, REPOSITORY, latest_checks, REQUIRED_WORKFLOWS

# Filled by the renderer. These are approved CI orchestration bytes, not app source.
PIPELINE_PINS = {}
UPDATE_FILES=['beta-preview-export.txt','beta-legacy-export.txt','beta-migration-a.txt','beta-update-b.txt','beta-after-refusals.txt','beta-legacy-restore.txt']

def verify_bundle(data, artifact, run, previous, entries, expected_plan):
    if artifact.get('expired') or artifact['workflow_run']['head_sha']!=run['head_sha'] or artifact['workflow_run']['id']!=run['id']:
        raise ValueError('Artifact provenance mismatch')
    if artifact.get('digest')!='sha256:'+digest(data): raise ValueError('GitHub artifact digest mismatch')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names=archive.namelist()
        if len(names)!=len(set(names)) or len(names)>64 or sum(x.file_size for x in archive.infolist())>100_000_000:
            raise ValueError('Bounded, unique artifact entries required')
        for name in names:
            if name not in ('unsigned.apk','candidate.json') and not (name.startswith('proof/') and name.removeprefix('proof/') in [c+'.txt' for c in ALL_CLASSES]+UPDATE_FILES):
                raise ValueError('Unexpected artifact path')
        manifest=json.loads(archive.read('candidate.json'))
        if set(manifest)!={'format','repository','source_sha','run_id','run_attempt','runtime_sha256','toolchains','previous_source_sha','metadata','plan','unsigned_sha256','proofs','jvm_tests'}:
            raise ValueError('Unexpected candidate manifest fields')
        if manifest['format']!=1 or manifest['repository']!=REPOSITORY or manifest['source_sha']!=run['head_sha'] or manifest['run_id']!=run['id'] or manifest['run_attempt']!=run['run_attempt']:
            raise ValueError('Candidate source/run mismatch')
        if manifest['toolchains']!=TOOLCHAINS or manifest['runtime_sha256']!=fingerprint(entries) or manifest['previous_source_sha']!=previous['source_sha']:
            raise ValueError('Input fingerprint, toolchain or previous delivery mismatch')
        if manifest['plan']!=expected_plan: raise ValueError('Validation policy or reused evidence mismatch')
        metadata=candidate_metadata(manifest['metadata'],previous)
        wanted=[c+'.txt' for c in expected_plan['classes']]+(UPDATE_FILES if expected_plan['update'] else [])
        if set(manifest['proofs'])!=set(wanted) or set(names)!={'unsigned.apk','candidate.json',*('proof/'+n for n in wanted)}:
            raise ValueError('Incomplete or extra evidence')
        for name in wanted:
            proof=archive.read('proof/'+name)
            if len(proof)>262144 or digest(proof)!=manifest['proofs'][name] or b'OK (1 test)' not in proof or b'FAILURES!!!' in proof or b'INSTRUMENTATION_FAILED' in proof:
                raise ValueError('Instrumentation validation failed')
        if not expected_plan['reused'] and manifest['jvm_tests']<24: raise ValueError('JVM preservation/security checks required')
        apk=archive.read('unsigned.apk')
        if len(apk)>90_000_000 or digest(apk)!=manifest['unsigned_sha256']: raise ValueError('APK digest mismatch')
    return manifest,metadata,apk

def main():
    event=json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    if os.environ['GITHUB_REF']!='refs/heads/main' or event['repository']['full_name']!=REPOSITORY:
        raise ValueError('Trusted default branch required')
    owner=event['repository']['owner']['id']
    head=api('/git/ref/heads/'+READY_BRANCH)['object']['sha'];sha(head)
    if os.environ['GITHUB_EVENT_NAME']=='workflow_run':
        run=api('/actions/runs/'+str(event['workflow_run']['id']))
        if run['head_sha']!=head:
            print('Obsolete ready milestone; no signature requested');return
    else:
        if event.get('sender',{}).get('id')!=owner: raise ValueError('Owner activation or fallback required')
        runs=api('/actions/runs?event=push&branch='+READY_BRANCH+'&head_sha='+head+'&per_page=100')['workflow_runs']
        choices=[x for x in runs if x['path']=='.github/workflows/android-integrations.yml']
        if not choices or choices[0]['status']!='completed':
            print('Ready validation not finished; workflow_run will retry automatically');return
        run=api('/actions/runs/'+str(choices[0]['id']))
    check_run(run,head,owner,candidate=True)
    for path,expected in PIPELINE_PINS.items():
        if digest(file_bytes(head,path))!=expected: raise ValueError('CI policy changed: one-time trusted workflow review required')
    # Independent source checks can finish in parallel with Android packaging.
    for attempt in range(121):
        allruns=api('/actions/runs?head_sha='+head+'&per_page=100')['workflow_runs']
        latest=latest_checks(allruns);required=REQUIRED_WORKFLOWS
        if all(name in latest and latest[name]['status']=='completed' for name in required):break
        if attempt==120: raise ValueError('Source checks did not complete')
        time.sleep(5)
    for name in required: check_run(api('/actions/runs/'+str(latest[name]['id'])),head,owner)
    if latest['Android home and integrations']['id']!=run['id']: raise ValueError('Superseded candidate validation')
    previous=previous_release()
    # Tags reserve versionCodes even if an interrupted draft publication remains.
    metadata=candidate_metadata(properties(file_bytes(head,'android/beta.properties').decode()),previous)
    try: api('/git/ref/tags/moli-beta-code-'+str(metadata['versionCode']))
    except urllib.error.HTTPError as error:
        if error.code!=404: raise
    else: raise ValueError('This versionCode was already reserved or delivered')
    delta=api('/compare/'+sha(previous['source_sha'])+'...'+head)
    # Compare API caps changed files at 300. Fall back to the full suite rather than skip.
    if delta['status'] not in ('ahead','identical'): raise ValueError('Delivered source must be an ancestor')
    plan=select([p for x in delta['files'] for p in (x['filename'],x.get('previous_filename',x['filename']))]) if len(delta['files'])<300 else select(['unknown-large-change'])
    proof=reusable_proof(head,owner)
    if proof: plan.update(rust=False,web=False,image=False,update=False,classes=[])
    plan.update(candidate=True,source_sha=head,previous=previous,reused=proof)
    artifacts=api('/actions/runs/'+str(run['id'])+'/artifacts?per_page=100')['artifacts']
    choices=[a for a in artifacts if a['name']=='android-beta-candidate']
    if len(choices)!=1: raise ValueError('Exactly one immutable candidate artifact required')
    artifact=choices[0];data=api('/actions/artifacts/'+str(artifact['id'])+'/zip',binary=True)
    manifest,observed,apk=verify_bundle(data,artifact,run,previous,tree(head),plan)
    if observed!=metadata: raise ValueError('APK metadata does not match the immutable source')
    out=Path('validated-beta');out.mkdir();(out/'unsigned.apk').write_bytes(apk)
    (out/'candidate.json').write_text(json.dumps(manifest,sort_keys=True)+'\n')
    with open(os.environ['GITHUB_OUTPUT'],'a') as output:
        output.write('approved=true\nmetadata='+json.dumps(dict(metadata,versionCode=str(metadata['versionCode'])),separators=(',',':'))+'\n')
        output.write('unsigned_sha256='+manifest['unsigned_sha256']+'\nversion='+metadata['versionName']+'\n')
    print('Approved exact unsigned bytes and validated source:',head)

if __name__=='__main__':main()
