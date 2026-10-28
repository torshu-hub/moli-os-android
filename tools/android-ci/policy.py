"""Conservative Android release policy; also embedded in the trusted delivery gate."""
import hashlib
import re

REPOSITORY = 'torshu-hub/moli-os-android'
READY_BRANCH = 'android/beta-ready'
IDENTITY = 'io.moli.android.app.beta'
CERTIFICATE = 'ce51eb0755d1e4fd5043d833108f0ed374f44cdac8734e4cf63f2efe1bc3bf37'
BOOTSTRAP = {'source_sha': 'fde277b8340cb9fea7fd40b75053c3a42a467d88', 'versionCode': 17, 'certificate_sha256': CERTIFICATE}
PROOF_SHA = '014ba7baa92865d51e0d56055737f7af4f392ff4'
PROOF_RUN = 40025382394
ALL_CLASSES = 'AutomaticMerossTest ProfileImportTest EngineDiagnosticsTest WeatherObservationTest BackNavigationTest HomeSetupTest EquipmentReadTest MerossReadTest NetworkDiscoveryTest MerossAccountTest UsabilityJourneyTest GuidedEquipmentTest'.split()
TOOLCHAINS = {'rust': '1.99.1', 'ndk': '28.2.13676358', 'api': 28, 'sdk': 35, 'node': '24.19.0', 'gradle': '8.11.1', 'java': 21}

def sha(value):
    if not isinstance(value, str) or not re.fullmatch('[a-f0-9]{40}', value):
        raise ValueError('Immutable source SHA required')
    return value

def runtime_path(path):
    # Exclusion list, not an extensible allowlist: unknown inputs stay relevant.
    return not (path.startswith(('docs/', '.github/', '.claude/', 'tools/android-ci/', 'tools/android-signing/')) or
                path in ('README.md', 'AGENTS.md') or path.startswith('scripts/test-android-'))

def fingerprint(entries):
    lines = [f"{x['mode']} {x['type']} {x['sha']} {x['path']}\n" for x in entries if runtime_path(x['path'])]
    return hashlib.sha256(''.join(sorted(lines)).encode()).hexdigest()

def select(paths):
    flags = {k: False for k in ('rust', 'web', 'image', 'update', 'pipeline')}
    tests = set()
    for p in paths:
        if not runtime_path(p):
            flags['pipeline'] |= not p.startswith('docs/') and p not in ('README.md', 'AGENTS.md')
        elif p == 'android/beta.properties':
            # Identity, permission and strict monotonic version checks run separately.
            pass
        elif p.startswith(('web/', 'locales/')):
            flags['web'] = True
            tests.update(('BackNavigationTest', 'HomeSetupTest', 'EquipmentReadTest', 'MerossReadTest'))
        elif p.startswith(('crates/', 'vendor/', '.cargo/')) or p in ('Cargo.toml', 'Cargo.lock', 'rust-toolchain.toml'):
            flags['rust'] = True
            tests.update(('ProfileImportTest', 'EquipmentReadTest', 'MerossReadTest'))
            if p in ('Cargo.toml','Cargo.lock','rust-toolchain.toml') or p.startswith('.cargo/') or any(x in p for x in ('moli-android/', 'moli-runtime/', 'moli-history/', 'moli-api/', 'moli-core/')):
                flags['update'] = True; tests.update(ALL_CLASSES)
        elif p.startswith('integrations/'):
            tests.update(('ProfileImportTest', 'EquipmentReadTest', 'MerossReadTest'))
        elif p.startswith('android/'):
            if any(x in p for x in ('Backup', 'ProtectedKey', 'ProfileStore', 'EquipmentStore', 'MerossAccountStore', 'EngineHost', 'NativeServer', '.gradle', 'AndroidManifest', 'res/xml/', 'src/androidTest/')):
                flags['update'] = True; tests.update(ALL_CLASSES)
            elif 'Meross' in p:
                tests.update(('MerossReadTest', 'MerossAccountTest', 'UsabilityJourneyTest', 'AutomaticMerossTest'))
            elif 'MainActivity' in p or 'MoliService' in p or 'EngineHealth' in p:
                tests.update(('EngineDiagnosticsTest', 'BackNavigationTest', 'EquipmentReadTest'))
            elif 'res/values' in p:
                tests.update(('BackNavigationTest', 'HomeSetupTest'))
            else:
                flags['update'] = True; tests.update(ALL_CLASSES)
        elif p.startswith(('docker/',)) or p in ('Dockerfile', '.dockerignore'):
            flags['image'] = True
        else:
            # Unknown build/storage/security input: fail toward the complete suite.
            flags.update(rust=True, web=True, image=True, update=True)
            tests.update(ALL_CLASSES)
    return dict(flags, classes=[c for c in ALL_CLASSES if c in tests])

def candidate_metadata(value, previous):
    if set(value) != {'applicationId', 'versionCode', 'versionName'} or value['applicationId'] != IDENTITY:
        raise ValueError('Unexpected beta identity or metadata')
    code = str(value['versionCode'])
    if not re.fullmatch('[1-9][0-9]*', code) or not int(previous['versionCode']) < int(code) <= 2100000000:
        raise ValueError('A delivery requires a strictly increasing versionCode')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-beta\.[1-9][0-9]*', value['versionName']):
        raise ValueError('Invalid version name')
    return dict(value, versionCode=int(code))

def properties(text):
    result = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'): continue
        key, value = line.split('=', 1)
        if key in result: raise ValueError('Duplicate beta property')
        result[key] = value
    return result
