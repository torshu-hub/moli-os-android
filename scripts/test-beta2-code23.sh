#!/bin/sh
# One code22 -> code23 update in a disposable emulator.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$root/target}"
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then echo 'Fresh emulator required; existing installation left untouched' >&2; exit 1; fi
node --test web/src/maison/lib/vehicle-svm.test.js
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
base=d1c82f66707f67aa231eefbef54945aa9de65ab4
git fetch --no-tags --depth=1 https://github.com/torshu-hub/moli-os-android.git "$base"
git archive "$base" | tar -x -C "$fixture"
cp -a web/dist "$fixture/web/dist"
(cd "$fixture" && cargo +1.99.1 clean --release -p moli-i18n -p moli-runtime -p moli-history -p moli-api -p moli-os -p moli-android -p moli-hyundai -p moli-net --target x86_64-linux-android && sh scripts/build-android.sh x86_64)
(cd android && ./gradlew -p "$fixture/android" --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=22 :app:assembleDebug)
# Both checkouts share a target directory and package identities. Invalidate
# embedded file/catalogue output, so neither build reuses checkout paths.
# Invalidate the changed shared runtime/history/API and JNI packages across checkouts.
cargo +1.99.1 clean --release -p moli-i18n -p moli-runtime -p moli-history -p moli-api -p moli-os -p moli-android -p moli-hyundai -p moli-net --target x86_64-linux-android
sh scripts/build-android.sh x86_64
python3 - <<'PY'
from pathlib import Path
engine = Path('android/app/src/main/jniLibs/x86_64/libmoli_android.so').read_bytes()
for integration in ('meross-mss310', 'netatmo', 'hyundai'):
    manifest = Path(f'integrations/{integration}/integration.toml').read_bytes()
    assert manifest in engine, f'The emulator JNI must embed the current {integration} catalogue'
PY
mkdir -p android/app/src/androidTest/assets/code15-web
cp -a web/dist/. android/app/src/androidTest/assets/code15-web/
expected_index=$(sha256sum web/dist/index.html | cut -d' ' -f1)
(cd android && ./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=23 :app:assembleDebug :app:assembleDebugAndroidTest)
mkdir -p android/app/build/reports/code23/screenshots
adb shell wm size 1280x720
adb shell wm density 160
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install "$fixture/android/app/build/outputs/apk/debug/app-debug.apk"
adb install android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
adb shell am force-stop com.google.android.apps.nexuslauncher
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
adb shell am instrument -w -r -e class io.moli.android.Code20UpgradeSeedTest#seedCode19WithDraftInactiveAndActiveRules -e expectedSeed 22 "$runner" > android/app/build/reports/code23/before.txt
cat android/app/build/reports/code23/before.txt
grep -Fq 'OK (1 test)' android/app/build/reports/code23/before.txt
adb shell am force-stop "$app"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am instrument -w -r -e class io.moli.android.Code15DeliveryTest#upgradeAndEditOfficialClimateCard -e expectedVersion 23 -e expectedIndex "$expected_index" "$runner" > android/app/build/reports/code23/after.txt
cat android/app/build/reports/code23/after.txt
adb shell am force-stop "$app"
adb shell am instrument -w -r -e class io.moli.android.Code15DeliveryTest#verifySavedAccountAfterRestart -e expectedVersion 23 "$runner" > android/app/build/reports/code23/reopened.txt
cat android/app/build/reports/code23/reopened.txt
for name in climate-empty climate-replaced failure; do
    target="android/app/build/reports/code23/screenshots/$name.png"
    adb exec-out run-as "$app" cat "files/code15-captures/$name.png" > "$target" 2>/dev/null || rm -f "$target"
    if [ -f "$target" ]; then python3 -c 'import sys;from pathlib import Path;p=Path(sys.argv[1]);p.unlink() if not p.read_bytes().startswith(bytes.fromhex("89504e470d0a1a0a")) else None' "$target"; fi
done
grep -Fq 'OK (1 test)' android/app/build/reports/code23/reopened.txt
grep -Fq 'OK (1 test)' android/app/build/reports/code23/after.txt
adb exec-out run-as "$app" cat files/code15-captures/update-proof.txt > android/app/build/reports/code23/update-proof.txt
# The code20 navigation source is unchanged; exercise only new Hyundai UI here.
adb shell am instrument -w -r -e class io.moli.android.Code21HyundaiTest -e expectedIndex "$expected_index" "$runner" > android/app/build/reports/code23/hyundai-ui.txt
cat android/app/build/reports/code23/hyundai-ui.txt
for name in hyundai-native-landscape vehicle-readings-landscape vehicle-commands-landscape vehicle-journal-landscape failure; do
    adb exec-out run-as "$app" cat "files/code21-captures/$name.png" > "android/app/build/reports/code23/screenshots/$name.png" 2>/dev/null || rm -f "android/app/build/reports/code23/screenshots/$name.png"
done
grep -Fq 'OK (1 test)' android/app/build/reports/code23/hyundai-ui.txt

adb shell am instrument -w -r -e class io.moli.android.Code22AuthTest "$runner" > android/app/build/reports/code23/auth-flow.txt
cat android/app/build/reports/code23/auth-flow.txt
grep -Fq 'OK (2 tests)' android/app/build/reports/code23/auth-flow.txt
adb exec-out run-as "$app" cat files/code22-captures/auth-diagnostic-landscape.png > android/app/build/reports/code23/screenshots/auth-diagnostic-landscape.png
