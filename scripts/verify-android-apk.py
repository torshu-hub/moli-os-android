#!/usr/bin/env python3
"""Inspect the actual packaged JNI library; never execute the Android server."""
import argparse
import hashlib
import struct
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
parser.add_argument("--abi", choices=["arm64-v8a", "x86_64"], default="arm64-v8a")
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as archive:
    names = archive.namelist()
    native = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
    expected = f"lib/{args.abi}/libmoli_android.so"
    assert native == [expected], native
    assert "AndroidManifest.xml" in names and "classes.dex" in names
    assert "assets/LICENSE" in names and "assets/NOTICE" in names
    library = archive.read(expected)
assert library[:6] == b"\x7fELF\x02\x01", "Expected little-endian ELF64"
assert struct.unpack_from("<H", library, 18)[0] == {"arm64-v8a": 183, "x86_64": 62}[args.abi]
for method in (b"Java_io_moli_android_NativeServer_start", b"Java_io_moli_android_NativeServer_stop"):
    assert method + b"\0" in library, f"Missing JNI entry point: {method!r}"
phoff = struct.unpack_from("<Q", library, 32)[0]
size, count = struct.unpack_from("<HH", library, 54)
alignments = []
for index in range(count):
    offset = phoff + size * index
    if struct.unpack_from("<I", library, offset)[0] == 1:
        alignment = struct.unpack_from("<Q", library, offset + 48)[0]
        assert alignment >= 16384, f"LOAD segment alignment {alignment}"
        alignments.append(alignment)
assert alignments
print(f"PASS {args.abi}: manifest/DEX, ELF64 machine, JNI symbols, 16 KiB LOAD alignment")
print(f"APK bytes: {args.apk.stat().st_size}")
print(f"SHA256: {hashlib.sha256(args.apk.read_bytes()).hexdigest()}")
