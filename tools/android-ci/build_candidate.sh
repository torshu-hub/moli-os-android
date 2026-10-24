#!/bin/sh
# Secret-free packaging. Never invokes the distribution signer.
set -eu
mkdir -p android-artifacts/reports
previous=$(python3 -c 'import json;print(json.load(open("android-ci-plan.json"))["previous"]["source_sha"])')
python3 scripts/test-android-beta-metadata.py
python3 scripts/check-android-beta.py --delivery --previous-ref "$previous"
cd android
chmod +x gradlew
if [ "$REUSED" != true ]; then
    ./gradlew --no-daemon :app:testDebugUnitTest :app:lintBeta
fi
./gradlew --no-daemon :app:assembleBeta
apk=app/build/outputs/apk/beta/app-beta-unsigned.apk
python3 ../scripts/verify-android-apk.py "$apk"
(cd .. && python3 scripts/check-android-beta.py --apk "android/$apk")
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 "$apk"
if "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify "$apk" >/dev/null 2>&1; then
    echo 'Candidate must be unsigned; no debug signer fallback' >&2; exit 1
fi
