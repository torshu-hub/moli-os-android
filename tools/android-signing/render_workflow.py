"""Maintainer: render one self-contained manual workflow; no signing credentials needed."""
from pathlib import Path
import textwrap
root=Path(__file__).resolve().parents[2]
base=root/'tools/android-signing'
head='''name: Sign owner beta

on:
  workflow_dispatch:
    inputs:
      source_sha:
        description: 'Reviewed 40-character commit with successful Android CI'
        required: true
        type: string
        default: 'fde277b8340cb9fea7fd40b75053c3a42a467d88'
      previous_source_sha:
        description: 'Previous signed source commit; empty only for the first beta'
        required: false
        type: string

permissions:
  contents: read
  actions: read

concurrency:
  group: owner-beta-signing
  cancel-in-progress: false

jobs:
  build:
    if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && github.actor == github.repository_owner && github.repository == 'torshu-hub/moli-os-android'
    runs-on: ubuntu-24.04
    timeout-minutes: 45
    outputs:
      metadata: ${{ steps.metadata.outputs.json }}
      version: ${{ steps.metadata.outputs.version }}
    steps:
      - name: Require the exact already-validated source commit
        env:
          SOURCE_SHA: ${{ inputs.source_sha }}
          PREVIOUS_SHA: ${{ inputs.previous_source_sha }}
          GH_TOKEN: ${{ github.token }}
          REPOSITORY: ${{ github.repository }}
          REPOSITORY_ID: ${{ github.repository_id }}
        shell: bash
        run: |
          python3 -I - <<'PYCODE'
          import json, os, re, urllib.request
          sha=os.environ['SOURCE_SHA']; previous=os.environ['PREVIOUS_SHA']
          if not re.fullmatch('[a-f0-9]{40}',sha) or (previous and not re.fullmatch('[a-f0-9]{40}',previous)):
              raise SystemExit('A full immutable commit SHA is required')
          req=urllib.request.Request('https://api.github.com/repos/'+os.environ['REPOSITORY']+'/actions/runs?head_sha='+sha+'&per_page=100',headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json'})
          with urllib.request.urlopen(req,timeout=30) as response: runs=json.load(response)['workflow_runs']
          required={'check','audit','Source hygiene','Android home and integrations'}
          latest={}
          for run in runs:
              if run['head_sha']==sha and str(run['head_repository']['id'])==os.environ['REPOSITORY_ID']:
                  latest.setdefault(run['name'],run)
          if any(name not in latest or latest[name]['status']!='completed' or latest[name]['conclusion']!='success' for name in required):
              raise SystemExit('Source CI is missing or unsuccessful; no signing access')
          print('Validated immutable source:',sha)
          PYCODE
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262
        with:
          ref: ${{ inputs.source_sha }}
          persist-credentials: false
      - uses: actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020
        with:
          node-version: 24.19.0
      - uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3
        with:
          distribution: temurin
          java-version: '21'
      - name: Install the already-pinned toolchains
        run: |
          rustup toolchain install 1.99.1 --profile minimal --target aarch64-linux-android,x86_64-linux-android
          "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "ndk;28.2.13676358" "platforms;android-35" "build-tools;35.0.0"
      - name: Build the embedded dashboard
        working-directory: web
        run: npm ci --no-audit --no-fund && npm run build
      - name: Restore unchanged native inputs
        id: native-cache
        uses: actions/cache/restore@0057852bfaa89a56745cba8c7296529d2fc39830
        with:
          path: |
            android/app/src/main/jniLibs/arm64-v8a/libmoli_android.so
            android/app/src/main/jniLibs/x86_64/libmoli_android.so
          key: moli-jni-v1-${{ runner.os }}-${{ runner.arch }}-rust1.99.1-ndk28.2.13676358-api28-${{ hashFiles('Cargo.toml', 'Cargo.lock', '.cargo/**', 'crates/**', 'vendor/**', 'android/app/src/main/assets/*.toml', 'integrations/**', 'locales/**', 'moli.example.toml', 'web/dist/**', 'scripts/build-android.sh') }}
      - name: Compile native libraries only if missing
        if: steps.native-cache.outputs.cache-hit != 'true'
        run: |
          sh scripts/build-android.sh arm64-v8a
          sh scripts/build-android.sh x86_64
      - name: Require increasing release metadata
        id: metadata
        env:
          SOURCE_SHA: ${{ inputs.source_sha }}
          PREVIOUS_SHA: ${{ inputs.previous_source_sha }}
        run: |
          set -euo pipefail
          if [ -n "$PREVIOUS_SHA" ]; then
            git cat-file -e "$PREVIOUS_SHA^{commit}" 2>/dev/null || git fetch --no-tags --depth=1 origin "$PREVIOUS_SHA"
            python3 scripts/check-android-beta.py --delivery --previous-ref "$PREVIOUS_SHA"
          else
            [ "$SOURCE_SHA" = fde277b8340cb9fea7fd40b75053c3a42a467d88 ] || { echo 'Previous signed source is required'; exit 1; }
            python3 scripts/check-android-beta.py
          fi
          python3 - <<'PYCODE'
          import json, os
          from pathlib import Path
          values=dict(line.split('=',1) for line in Path('android/beta.properties').read_text().splitlines() if line and not line.startswith('#'))
          with open(os.environ['GITHUB_OUTPUT'],'a') as output:
              output.write('json='+json.dumps(values,separators=(',',':'))+'\\n')
              output.write('version='+values['versionName']+'\\n')
          PYCODE
      - name: Build unsigned beta and disposable emulator inputs without secrets
        working-directory: android
        run: |
          set -euo pipefail
          mkdir -p ../sign-inputs
          chmod +x gradlew
          ./gradlew --no-daemon :app:assembleBeta
          cp app/build/outputs/apk/beta/app-beta-unsigned.apk ../sign-inputs/beta-arm64.apk
          python3 ../scripts/verify-android-apk.py ../sign-inputs/beta-arm64.apk
          (cd .. && python3 scripts/check-android-beta.py --apk sign-inputs/beta-arm64.apk)
          ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.preview.automatic.v0130 -PmoliVersionCode=16 -PmoliVersionName=0.13.0-preview
          cp app/build/outputs/apk/debug/app-debug.apk ../sign-inputs/source.apk
          cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ../sign-inputs/source-test.apk
          ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.beta -PmoliUpdateFixture=true -PmoliVersionCode=17 -PmoliVersionName=0.14.0-beta.1-fixture
          cp app/build/outputs/apk/debug/app-debug.apk ../sign-inputs/beta-a.apk
          cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ../sign-inputs/beta-test.apk
          ./gradlew --no-daemon :app:assembleDebug -PmoliAbis=x86_64 -PmoliAppSuffix=.beta -PmoliUpdateFixture=true -PmoliVersionCode=18 -PmoliVersionName=0.14.0-beta.2-fixture
          cp app/build/outputs/apk/debug/app-debug.apk ../sign-inputs/beta-b.apk
          ./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.preview.receiver
          cp app/build/outputs/apk/debug/app-debug.apk ../sign-inputs/legacy.apk
          cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ../sign-inputs/legacy-test.apk
      - uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02
        with:
          name: beta-build-inputs
          path: sign-inputs/*.apk
          retention-days: 1

  sign:
    needs: build
    if: github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && github.actor == github.repository_owner
    environment: android-beta-signing
    runs-on: ubuntu-24.04
    timeout-minutes: 15
    steps:
      # No checkout, build script, dependency installer or repository code in this job.
      - uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3
        with:
          distribution: temurin
          java-version: '21'
      - name: Prepare pinned SDK before exposing signing credentials
        run: '"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "build-tools;35.0.0"'
      - uses: actions/download-artifact@d3f86a106a0bac45b974a628896c90dbdf5c8093
        with:
          name: beta-build-inputs
          path: ${{ runner.temp }}/beta-inputs
      - name: Sign only after the owner verified the NordPass recovery copy
        env:
          BETA_SIGNING_BUNDLE: ${{ secrets.BETA_SIGNING_BUNDLE }}
          BETA_METADATA: ${{ needs.build.outputs.metadata }}
          UNSIGNED_APK: ${{ runner.temp }}/beta-inputs/beta-arm64.apk
          SIGNED_OUTPUT: ${{ runner.temp }}/beta-signed
          SIGN_EMULATOR_INPUTS: 'true'
        shell: bash
        run: |
          python3 -I - <<'PYCODE'
'''
signer=(base/'sign_step.py').read_text()
tail='''          PYCODE
      - uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02
        with:
          name: beta-signed-verification-inputs
          path: ${{ runner.temp }}/beta-signed/
          retention-days: 1

  verify-and-deliver:
    needs: [build, sign]
    runs-on: ubuntu-24.04
    timeout-minutes: 20
    steps:
      - uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3
        with:
          distribution: temurin
          java-version: '21'
      - uses: actions/download-artifact@d3f86a106a0bac45b974a628896c90dbdf5c8093
        with:
          name: beta-signed-verification-inputs
          path: signed
      - name: Enable the disposable emulator
        run: sudo chmod a+rw /dev/kvm
      - name: Prepare trusted signed update proof
        shell: bash
        run: |
          cat > verify-beta-signed.sh <<'SIGNED_PROOF'
'''
proof=(base/'verify_prebuilt.sh').read_text()
emulator='''          SIGNED_PROOF
      - name: Verify migration and update with the actual beta certificate
        uses: ReactiveCircus/android-emulator-runner@4c44018e59b437e86cdfc41da381398f93ed8808
        with:
          api-level: 35
          arch: x86_64
          target: google_apis
          disable-animations: true
          script: sh verify-beta-signed.sh
'''
end='''      - name: Deliver only after successful signed update proof
        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02
        with:
          name: moli-os-${{ needs.build.outputs.version }}-arm64
          path: signed/release/
          retention-days: 14
      - name: Retain only public or synthetic proof
        if: always()
        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02
        with:
          name: beta-certificate-update-proof
          path: proof/*.txt
          retention-days: 14
'''
# YAML block scalars preserve one trusted copy of each executable body on main.
workflow=head+textwrap.indent(signer,'          ')+tail+textwrap.indent(proof,'          ')+emulator+end
(root/'.github/workflows/android-beta-sign.yml').write_text(workflow)
