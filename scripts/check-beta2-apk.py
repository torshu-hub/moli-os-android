#!/usr/bin/env python3
"""Unsigned Beta 2 candidate inspection; never signs or reads a keystore."""
from pathlib import Path
import os
import re
import subprocess
import sys
import zipfile

apk = Path(sys.argv[1])
sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '35.0.0'
package = subprocess.check_output([str(sdk / 'aapt'), 'dump', 'badging', str(apk)], text=True)
assert package.startswith("package: name='io.moli.android.app.beta2' versionCode='1' versionName='0.1.0-beta2.j1'"), 'Wrong Beta 2 identity/version'
assert "native-code: 'arm64-v8a'" in package, 'Not an ARM64 candidate'
with zipfile.ZipFile(apk) as archive:
    native = [n for n in archive.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert native == ['lib/arm64-v8a/libmoli_android.so'], native
    assert len(archive.read(native[0])) > 1_000_000, 'Missing real embedded engine'
    assert not any('Trial' in n or 'android-integrations.json' in n for n in archive.namelist())
    assert 'assets/LICENSE' in archive.namelist()
    assert not any(re.match(r'META-INF/.*\.(RSA|DSA|EC)$', n) for n in archive.namelist())
subprocess.run([str(sdk / 'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
print('Unsigned ARM64 Beta 2 candidate: identity, native engine, notices and ZIP alignment verified')
