#!/usr/bin/env python3
"""Fixed beta identity/version guard; no key or secret access."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

IDENTITY = 'io.moli.android.app.beta'
PATH = 'android/beta.properties'

def parse(text):
    values = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        if key in values:
            raise ValueError('Duplicate beta metadata')
        values[key] = value
    if set(values) != {'applicationId', 'versionCode', 'versionName'}:
        raise ValueError('Unexpected beta metadata')
    if values['applicationId'] != IDENTITY:
        raise ValueError('Beta identity must remain fixed')
    if not re.fullmatch(r'[1-9][0-9]*', values['versionCode']) or not 16 < int(values['versionCode']) <= 2100000000:
        raise ValueError('Invalid beta versionCode')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-beta\.[1-9][0-9]*', values['versionName']):
        raise ValueError('Invalid beta versionName')
    return values

def compare(current, previous, delivery=False):
    code = int(current['versionCode'])
    old = int(previous['versionCode']) if previous else 16
    if code < old or (code == old and (delivery or current != previous)):
        raise ValueError('A new beta delivery requires a strictly increasing versionCode')

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--previous-ref')
    p.add_argument('--delivery', action='store_true')
    p.add_argument('--apk')
    a = p.parse_args()
    current = parse(Path(PATH).read_text())
    previous = None
    if a.previous_ref:
        # Missing metadata is allowed only on the initial, pre-beta commit.
        names = subprocess.check_output(['git', 'ls-tree', '--name-only', a.previous_ref, '--', PATH], text=True)
        if names.strip():
            previous = parse(subprocess.check_output(['git', 'show', a.previous_ref + ':' + PATH], text=True))
    elif a.delivery:
        raise ValueError('Delivery comparison needs the previously distributed reference')
    compare(current, previous, a.delivery)
    if a.apk:
        tool = str(Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/aapt')
        text = subprocess.check_output([tool, 'dump', 'badging', a.apk], text=True)
        expected = "package: name='{}' versionCode='{}' versionName='{}'".format(IDENTITY,current['versionCode'],current['versionName'])
        if not text.startswith(expected) or 'application-debuggable' in text:
            raise ValueError('APK identity/version/debug state does not match beta metadata')
        if "sdkVersion:'28'" not in text or "targetSdkVersion:'35'" not in text or "native-code: 'arm64-v8a'" not in text:
            raise ValueError('Unexpected Android/ABI compatibility')
        permissions = set(re.findall(r"uses-permission: name='([^']+)'", text))
        expected_permissions = {'android.permission.' + s for s in ['INTERNET','ACCESS_NETWORK_STATE','CHANGE_WIFI_MULTICAST_STATE','FOREGROUND_SERVICE','FOREGROUND_SERVICE_SPECIAL_USE','POST_NOTIFICATIONS']}
        if permissions != expected_permissions:
            raise ValueError('Beta permissions changed')
    print(json.dumps({'beta': current, 'distribution_signing': 'not configured'}))

if __name__ == '__main__':
    main()
