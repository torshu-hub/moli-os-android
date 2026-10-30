#!/usr/bin/env python3
"""Verify the reviewed Web bytes inside the packaged RustEmbed library."""
from pathlib import Path
import hashlib
import json
import re
import sys
import zipfile

apk = Path(sys.argv[1])
dist = Path(__file__).resolve().parents[1] / "web/dist"
proof_path = Path(sys.argv[2])
with zipfile.ZipFile(apk) as archive:
    library = archive.read("lib/arm64-v8a/libmoli_android.so")
entries = []
for path in sorted(dist.rglob("*")):
    if not path.is_file():
        continue
    data = path.read_bytes()
    assert data in library, f"Packaged library lacks current Web asset: {path.relative_to(dist)}"
    entries.append({"path": path.relative_to(dist).as_posix(),
                    "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})
html = (dist / "index.html").read_text()
assert re.search(r'/assets/[^"]+\.js', html)
assert re.search(r'/assets/[^"]+\.css', html)
proof_path.parent.mkdir(parents=True, exist_ok=True)
proof_path.write_text(json.dumps({"embedding": "unchanged official RustEmbed",
    "jni_sha256": hashlib.sha256(library).hexdigest(), "assets": entries}, indent=2) + "\n")
print(f"All {len(entries)} current Web assets are present byte-for-byte in the packaged ARM64 library")
