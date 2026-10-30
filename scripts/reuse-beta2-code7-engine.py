#!/usr/bin/env python3
"""Native-only code8: reuse the verified code7 JNI byte-for-byte, never signing keys."""
from pathlib import Path
import hashlib, os, subprocess, urllib.request, zipfile
apk=Path('android/app/build/code7-validated.apk');apk.parent.mkdir(parents=True,exist_ok=True)
with urllib.request.urlopen('https://github.com/torshu-hub/moli-os-android/releases/download/moli-beta2-code-7/moli-os-0.7.0-beta2.code7-arm64.apk',timeout=60) as response:
    apk.write_bytes(response.read(100_000_001))
assert hashlib.sha256(apk.read_bytes()).hexdigest()=='a75283161895890311e957f654c3a66257f6ca17a79393f24d90677ee9c8d834'
sdk=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
verified=subprocess.check_output([str(sdk/'apksigner'),'verify','--print-certs',str(apk)],text=True)
assert 'Signer #1 certificate SHA-256 digest: ce51eb0755d1e4fd5043d833108f0ed374f44cdac8734e4cf63f2efe1bc3bf37' in verified
with zipfile.ZipFile(apk) as z:
    target=Path('android/app/src/main/jniLibs/arm64-v8a/libmoli_android.so');target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(z.read('lib/arm64-v8a/libmoli_android.so'))
print('Verified signed code7 JNI reused unchanged; no engine rebuild')
