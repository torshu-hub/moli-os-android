#!/bin/sh
# One code16 -> code17 update in a disposable emulator.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$root/target}"
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then echo 'Fresh emulator required; existing installation left untouched' >&2; exit 1; fi
node --test web/src/maison/lib/climate-capabilities.test.js
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
base=c3720870ccf81256730210785060a435b08133bf
git fetch --no-tags --depth=1 https://github.com/torshu-hub/moli-os-android.git "$base"
git archive "$base" | tar -x -C "$fixture"
cp -a web/dist "$fixture/web/dist"
(cd "$fixture" && cargo +1.99.1 clean --release -p moli-netatmo -p moli-api -p moli-android --target x86_64-linux-android && sh scripts/build-android.sh x86_64)
(cd android && ./gradlew -p "$fixture/android" --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=16 :app:assembleDebug)
# Both checkouts share a target directory and package identities. Invalidate
# the changed Netatmo crate in each direction, plus embedded file/catalogue
# output, so neither build can reuse the other checkout's native code or paths.
cargo +1.99.1 clean --release -p moli-netatmo -p moli-api -p moli-android --target x86_64-linux-android
sh scripts/build-android.sh x86_64
python3 - <<'PY'
from pathlib import Path
engine = Path('android/app/src/main/jniLibs/x86_64/libmoli_android.so').read_bytes()
for integration in ('meross-mss310', 'netatmo'):
    manifest = Path(f'integrations/{integration}/integration.toml').read_bytes()
    assert manifest in engine, f'The emulator JNI must embed the current {integration} catalogue'
PY
mkdir -p android/app/src/androidTest/assets/code15-web
cp -a web/dist/. android/app/src/androidTest/assets/code15-web/
expected_index=$(sha256sum web/dist/index.html | cut -d' ' -f1)
(cd android && ./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=17 :app:assembleDebug :app:assembleDebugAndroidTest)
mkdir -p android/app/build/reports/code17/screenshots
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
adb shell am instrument -w -r -e class io.moli.android.Code15UpgradeSeedTest#seedRealCode14 -e expectedSeed 16 "$runner" > android/app/build/reports/code17/before.txt
cat android/app/build/reports/code17/before.txt
grep -Fq 'OK (1 test)' android/app/build/reports/code17/before.txt
adb shell am force-stop "$app"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am instrument -w -r -e class io.moli.android.Code15DeliveryTest#upgradeAndEditOfficialClimateCard -e expectedVersion 17 -e expectedIndex "$expected_index" "$runner" > android/app/build/reports/code17/after.txt
cat android/app/build/reports/code17/after.txt
adb shell am force-stop "$app"
adb shell am instrument -w -r -e class io.moli.android.Code15DeliveryTest#verifySavedAccountAfterRestart -e expectedVersion 17 "$runner" > android/app/build/reports/code17/reopened.txt
cat android/app/build/reports/code17/reopened.txt
for name in climate-empty climate-replaced failure; do
    target="android/app/build/reports/code17/screenshots/$name.png"
    adb exec-out run-as "$app" cat "files/code15-captures/$name.png" > "$target" 2>/dev/null || rm -f "$target"
    if [ -f "$target" ]; then python3 -c 'import sys;from pathlib import Path;p=Path(sys.argv[1]);p.unlink() if not p.read_bytes().startswith(bytes.fromhex("89504e470d0a1a0a")) else None' "$target"; fi
done
grep -Fq 'OK (1 test)' android/app/build/reports/code17/reopened.txt
grep -Fq 'OK (1 test)' android/app/build/reports/code17/after.txt
adb exec-out run-as "$app" cat files/code15-captures/update-proof.txt > android/app/build/reports/code17/update-proof.txt
