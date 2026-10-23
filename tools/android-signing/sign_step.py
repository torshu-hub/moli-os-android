"""Trusted signing step, embedded verbatim in the manual workflow. No project imports."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def command(args, **kwargs):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, **kwargs)
    if result.returncode:
        raise ValueError('Signing tool refused the input; no private diagnostic is printed')
    return result.stdout


def sign():
    raw = os.environ.pop('BETA_SIGNING_BUNDLE', '')
    if not raw or len(raw) > 40000:
        raise ValueError('ATTENTE COPIE UTILISATEUR: configure the owner-held signing bundle')
    bundle = json.loads(raw)
    if set(bundle) != {'format', 'p12', 'password', 'certificate_sha256', 'nordpass_confirmed'} or bundle['format'] != 1 or bundle['nordpass_confirmed'] is not True:
        raise ValueError('Owner recovery confirmation is required')
    password, expected = bundle['password'], bundle['certificate_sha256']
    if not isinstance(password, str) or not 24 <= len(password) <= 128 or any(not 33 <= ord(c) <= 126 for c in password) or not re.fullmatch('[a-f0-9]{64}', expected):
        raise ValueError('Invalid signing credentials or public fingerprint')
    blob = base64.b64decode(bundle['p12'], validate=True)
    if not 1000 <= len(blob) <= 20000:
        raise ValueError('Invalid signing container')
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        for value in (password, bundle['p12']):
            print('::add-mask::' + value.replace('%', '%25'), flush=True)
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    source = Path(os.environ['UNSIGNED_APK']).resolve()
    metadata = json.loads(os.environ['BETA_METADATA'])
    code, version = metadata['versionCode'], metadata['versionName']
    if metadata['applicationId'] != 'io.moli.android.app.beta' or not re.fullmatch('[1-9][0-9]*', str(code)) or not 16 < int(code) <= 2100000000 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-beta\.[1-9][0-9]*', version):
        raise ValueError('Invalid beta metadata')
    badging = command([str(sdk/'aapt'), 'dump', 'badging', str(source)])
    prefix = f"package: name='io.moli.android.app.beta' versionCode='{code}' versionName='{version}'"
    if not badging.startswith(prefix) or 'application-debuggable' in badging or "sdkVersion:'28'" not in badging or "targetSdkVersion:'35'" not in badging or "native-code: 'arm64-v8a'" not in badging:
        raise ValueError('Beta APK identity, version, ABI or debugging state mismatch')
    permissions = set(re.findall(r"uses-permission: name='([^']+)'", badging))
    allowed = {'android.permission.'+p for p in ('INTERNET','ACCESS_NETWORK_STATE','CHANGE_WIFI_MULTICAST_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_SPECIAL_USE','POST_NOTIFICATIONS')}
    if permissions != allowed:
        raise ValueError('Unexpected APK permissions')
    verifier = str(sdk/'apksigner')
    if subprocess.run([verifier, 'verify', str(source)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        raise ValueError('The build input must be unsigned')
    output = Path(os.environ['SIGNED_OUTPUT']).resolve()
    if output.exists():
        raise ValueError('Refusing to overwrite an existing output directory')
    output.mkdir(mode=0o700, parents=True)
    release = output/'release'; release.mkdir(mode=0o700)
    apk = release/f'moli-os-{version}-arm64.apk'
    try:
        with tempfile.TemporaryDirectory(prefix='moli-sign-', dir=os.environ.get('RUNNER_TEMP')) as temporary:
            keyfile = Path(temporary)/'beta.p12'
            fd = os.open(keyfile, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(blob)
            child = dict(os.environ, MOLI_KS_PASSWORD=password)
            pairs = [(source, apk)]
            if os.environ.get('SIGN_EMULATOR_INPUTS') == 'true':
                emulator = output/'emulator'; emulator.mkdir(mode=0o700)
                packages = {'source.apk':'io.moli.android.app.preview.automatic.v0130',
                            'source-test.apk':'io.moli.android.app.preview.automatic.v0130.test',
                            'beta-a.apk':'io.moli.android.app.beta', 'beta-b.apk':'io.moli.android.app.beta',
                            'beta-test.apk':'io.moli.android.app.beta.test',
                            'legacy.apk':'io.moli.android.app.preview.receiver',
                            'legacy-test.apk':'io.moli.android.app.preview.receiver.test'}
                for name, package in packages.items():
                    candidate = source.parent/name
                    description = command([str(sdk/'aapt'), 'dump', 'badging', str(candidate)])
                    if not description.startswith("package: name='"+package+"'"):
                        raise ValueError('Unexpected emulator package')
                    pairs.append((candidate, emulator/name))
            for original, signed in pairs:
                command([verifier, 'sign', '--ks', str(keyfile), '--ks-type', 'PKCS12', '--ks-key-alias', 'moli-beta',
                         '--ks-pass', 'env:MOLI_KS_PASSWORD', '--key-pass', 'env:MOLI_KS_PASSWORD',
                         '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'false',
                         '--v4-signing-enabled', 'false', '--out', str(signed), str(original)], env=child)
                certificate = command([verifier, 'verify', '--verbose', '--print-certs', str(signed)])
                found = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})', certificate)
                if found != [expected]:
                    raise ValueError('APK signer does not match the owner recovery fingerprint')
                command([str(sdk/'zipalign'), '-c', '-P', '16', '4', str(signed)])
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        (release/'SHA256SUMS').write_text(digest+'  '+apk.name+'\n')
        (release/'CERTIFICATE-SHA256.txt').write_text(expected+'\n')
        (release/'INSTALLATION.txt').write_text('Moli OS Bêta — signature de test réutilisable.\nPremière installation : restaurer si utile avant Recherche/Mes appareils.\nMise à jour : arrêter Moli puis installer par-dessus, sans désinstaller ni effacer les données.\nEn cas de refus de signature : arrêter et signaler le message.\n')
        print('Signed beta verified; public certificate SHA-256: '+expected)
    except Exception:
        # Remove only this operation's newly created output; never existing owner data.
        import shutil
        shutil.rmtree(output)
        raise


if __name__ == '__main__':
    try:
        sign()
    except Exception:
        print('Signing stopped: missing owner recovery confirmation, invalid metadata/credentials or SDK verification failure. No key was generated.', file=sys.stderr)
        sys.exit(1)
