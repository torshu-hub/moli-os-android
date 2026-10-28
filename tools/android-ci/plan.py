"""Secret-free plan. Ready pushes compare against the last delivered source, not the last edit."""
import json
import os
from pathlib import Path
import subprocess
from policy import READY_BRANCH, PROOF_SHA, fingerprint, select, sha
from protocol import api, previous_release, reusable_proof

def git(*args): return subprocess.check_output(['git', *args], text=True).strip()

def ensure(source):
    sha(source)
    if subprocess.run(['git','cat-file','-e',source+'^{commit}'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
        subprocess.run(['git','fetch','--no-tags','--depth=1','origin',source],check=True)

def main():
    source = git('rev-parse','HEAD')
    candidate = os.environ.get('GITHUB_EVENT_NAME') == 'push' and os.environ.get('GITHUB_REF') == 'refs/heads/'+READY_BRANCH
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    previous = previous_release() if candidate else None
    base = previous['source_sha'] if candidate else event.get('before')
    if not base or base == '0'*40:
        base = event.get('pull_request',{}).get('base',{}).get('sha') or git('rev-parse','HEAD^')
    ensure(base)
    if candidate: subprocess.run(['git','merge-base','--is-ancestor',base,source],check=True)
    paths = git('diff','--name-only','--no-renames',base,source,'--').splitlines()
    plan = select(paths)
    proof = reusable_proof(source, api('')['owner']['id']) if candidate else None
    if proof: plan.update(rust=False,web=False,image=False,update=False,classes=[])
    plan.update(source_sha=source, previous=previous, candidate=candidate, reused=proof)
    Path('android-ci-plan.json').write_text(json.dumps(plan,sort_keys=True)+'\n')
    with open(os.environ['GITHUB_OUTPUT'],'a') as output:
        for key in ('candidate','rust','web','image','update'):
            output.write(key+'='+str(plan[key]).lower()+'\n')
        output.write('emulator='+str(bool(plan['classes']) or plan['update']).lower()+'\n')
        output.write('classes='+' '.join(plan['classes'])+'\n')
        output.write('reused='+str(bool(proof)).lower()+'\n')
    print(json.dumps(plan,sort_keys=True))

if __name__ == '__main__': main()
