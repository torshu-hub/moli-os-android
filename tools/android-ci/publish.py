"""Trusted publication only, with write permission and no signing secret or checkout."""
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
from policy import CERTIFICATE, IDENTITY, READY_BRANCH, REPOSITORY, candidate_metadata, sha
from protocol import api, digest, previous_release, StripAuthRedirect

def verified_release(root):
    manifest=json.loads((root/'candidate.json').read_text())
    previous=previous_release();metadata=candidate_metadata(manifest['metadata'],previous)
    source=sha(manifest['source_sha'])
    if api('/git/ref/heads/'+READY_BRANCH)['object']['sha']!=source:
        raise ValueError('Superseded ready milestone; publication stopped')
    if manifest['previous_source_sha']!=previous['source_sha']: raise ValueError('Previous delivery changed; regenerate candidate evidence')
    apk=root/f"moli-os-{metadata['versionName']}-arm64.apk"
    sdk=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
    cert=subprocess.check_output([str(sdk/'apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
    if re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})',cert)!=[CERTIFICATE]:
        raise ValueError('Final certificate does not match the existing beta')
    subprocess.run([str(sdk/'zipalign'),'-c','-P','16','4',str(apk)],check=True)
    package=subprocess.check_output([str(sdk/'aapt'),'dump','badging',str(apk)],text=True)
    if not package.startswith(f"package: name='{IDENTITY}' versionCode='{metadata['versionCode']}' versionName='{metadata['versionName']}'"):
        raise ValueError('Signed metadata mismatch')
    checksum,name=(root/'SHA256SUMS').read_text().split()
    if name!=apk.name or checksum!=digest(apk.read_bytes()) or (root/'CERTIFICATE-SHA256.txt').read_text().strip()!=CERTIFICATE:
        raise ValueError('Signed package digest/fingerprint mismatch')
    ledger=dict(metadata,source_sha=source,versionCode=metadata['versionCode'],certificate_sha256=CERTIFICATE,
        apk_sha256=checksum,validation_run=manifest['run_id'],delivery_run=int(os.environ['GITHUB_RUN_ID']),runtime_sha256=manifest['runtime_sha256'])
    (root/'beta-release.json').write_text(json.dumps(ledger,sort_keys=True)+'\n')
    return ledger,apk

def main():
    root=Path('release');ledger,apk=verified_release(root)
    tag='moli-beta-code-'+str(ledger['versionCode'])
    # Atomic reservation: a repeated/conflicting code must fail, never overwrite.
    api('/git/refs',method='POST',data={'ref':'refs/tags/'+tag,'sha':ledger['source_sha']})
    release=api('/releases',method='POST',data={'tag_name':tag,'target_commitish':ledger['source_sha'],
        'name':'Moli OS '+ledger['versionName'],'body':json.dumps(ledger,sort_keys=True),'draft':True,'prerelease':True})
    for file in [apk,root/'SHA256SUMS',root/'CERTIFICATE-SHA256.txt',root/'INSTALLATION.txt',root/'beta-release.json']:
        if not re.fullmatch('[a-zA-Z0-9_.-]+',file.name): raise ValueError('Invalid public asset name')
        request=urllib.request.Request('https://uploads.github.com/repos/'+REPOSITORY+'/releases/'+str(release['id'])+'/assets?name='+file.name,
            data=file.read_bytes(),method='POST',headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Content-Type':'application/octet-stream'})
        with urllib.request.build_opener(StripAuthRedirect()).open(request,timeout=120) as response: receipt=json.load(response)
        if receipt['size']!=file.stat().st_size or receipt.get('digest')!='sha256:'+digest(file.read_bytes()): raise ValueError('Asset upload length mismatch')
    api('/releases/'+str(release['id']),method='PATCH',data={'draft':False})
    with open(os.environ['GITHUB_STEP_SUMMARY'],'a') as output:
        output.write(f"Moli OS {ledger['versionName']} · code {ledger['versionCode']}\n\n")
        output.write(f"[APK ARM64](https://github.com/{REPOSITORY}/releases/download/{tag}/{apk.name})\n\n")
        output.write('Install over the existing beta. Do not uninstall or clear its data.\n')
    print('Published verified beta:',tag)

if __name__=='__main__':main()
