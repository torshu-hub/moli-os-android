"""GitHub read-only provenance. No artifact/project code is executed by this module."""
import base64
import hashlib
import json
import os
import urllib.request
import urllib.error
from policy import REPOSITORY, READY_BRANCH, BOOTSTRAP, CERTIFICATE, PROOF_SHA, PROOF_RUN, fingerprint, sha

class StripAuthRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.startswith('https://'):
            raise ValueError('HTTPS redirect required')
        result = super().redirect_request(req, fp, code, msg, headers, newurl)
        result.remove_header('Authorization')
        return result

def api(path, binary=False, method=None, data=None):
    if path and (not path.startswith('/') or path.startswith('//') or '\n' in path):
        raise ValueError('Invalid API path')
    req = urllib.request.Request('https://api.github.com/repos/'+REPOSITORY+path,
        headers={'Authorization': 'Bearer '+os.environ.get('GH_TOKEN', ''), 'Accept': 'application/vnd.github+json'},
        method=method, data=None if data is None else json.dumps(data).encode())
    if data is not None: req.add_header('Content-Type', 'application/json')
    with urllib.request.build_opener(StripAuthRedirect()).open(req, timeout=60) as response:
        body = response.read(100_000_001 if binary else 8_000_001)
    if len(body) > (100_000_000 if binary else 8_000_000):
        raise ValueError('Bounded GitHub response exceeded')
    return body if binary else json.loads(body)

def tree(source):
    result = api('/git/trees/'+sha(source)+'?recursive=1')
    if result.get('truncated'): raise ValueError('Complete input tree required')
    return [x for x in result['tree'] if x['type'] != 'tree']

def previous_release():
    releases = []
    for page in range(1,11):
        batch = api('/releases?per_page=100&page='+str(page)); releases.extend(batch)
        if len(batch)<100: break
    else: raise ValueError('Release ledger exceeds bounded pagination')
    delivered = []
    for release in releases:
        tag = release['tag_name']
        if release['draft'] or not tag.startswith('moli-beta-code-'): continue
        if not tag.removeprefix('moli-beta-code-').isdigit(): raise ValueError('Invalid delivery tag')
        delivered.append((int(tag.removeprefix('moli-beta-code-')), release))
    if not delivered:
        # Bootstrap is an existing, owner-signed and verified delivery, not a new key.
        return dict(BOOTSTRAP)
    code, release = max(delivered, key=lambda p:p[0])
    value = json.loads(release['body'])
    if value['versionCode'] != code or value['certificate_sha256'] != CERTIFICATE:
        raise ValueError('Release ledger identity mismatch')
    sha(value['source_sha'])
    ref = api('/git/ref/tags/'+release['tag_name'])
    if ref['object']['type'] != 'commit' or ref['object']['sha'] != value['source_sha']:
        raise ValueError('Release tag does not pin its source')
    return value

def check_run(run, source, owner_id, *, candidate=False):
    if run['head_sha'] != source or run['head_repository']['full_name'] != REPOSITORY or run['repository']['full_name'] != REPOSITORY:
        raise ValueError('Fork or source provenance mismatch')
    if run['status'] != 'completed' or run['conclusion'] != 'success': raise ValueError('Successful validation required')
    if run['actor']['id'] != owner_id or run['triggering_actor']['id'] != owner_id:
        raise ValueError('Owner-originated validation required')
    if candidate and (run['event'] != 'push' or run['head_branch'] != READY_BRANCH or run['path'] != '.github/workflows/android-integrations.yml'):
        raise ValueError('Explicit trusted ready-branch promotion required')

REQUIRED_WORKFLOWS = {'check':'.github/workflows/check.yml','audit':'.github/workflows/audit.yml',
    'Source hygiene':'.github/workflows/source-hygiene.yml','Android profile import':'.github/workflows/android-integrations.yml'}
def latest_checks(rows):
    latest={}
    for row in sorted(rows,key=lambda x:x['id'],reverse=True):
        if row['event']=='push' and row['head_branch']==READY_BRANCH and row['name'] in REQUIRED_WORKFLOWS and row['path']==REQUIRED_WORKFLOWS[row['name']]:
            latest.setdefault(row['name'],row)
    return latest

def reusable_proof(source, owner_id):
    if fingerprint(tree(source)) != fingerprint(tree(PROOF_SHA)): return None
    run = api('/actions/runs/'+str(PROOF_RUN))
    check_run(run, PROOF_SHA, owner_id)
    if run['path'] != '.github/workflows/android-integrations.yml': raise ValueError('Baseline workflow mismatch')
    return {'source_sha': PROOF_SHA, 'run_id': PROOF_RUN}

def file_bytes(source, path):
    result = api('/contents/'+path+'?ref='+sha(source))
    if result.get('encoding') != 'base64': raise ValueError('Bounded UTF8 source required')
    return base64.b64decode(result['content'], validate=False)

def digest(data): return hashlib.sha256(data).hexdigest()
