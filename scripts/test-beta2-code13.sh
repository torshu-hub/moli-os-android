#!/bin/sh
# One code12 versionCode 12 -> corrective versionCode 13 update in a disposable emulator, with existing synthetic seed data.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$root/target}"
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then echo 'Fresh emulator required; existing installation left untouched' >&2; exit 1; fi
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
base=8579aff9c4abafeec729490789110355d4bb4dd2
git fetch --no-tags --depth=1 https://github.com/torshu-hub/moli-os-android.git "$base"
git archive "$base" | tar -x -C "$fixture"
cp -a web/dist "$fixture/web/dist"
(cd "$fixture" && cargo +1.99.1 clean --release -p moli-api -p moli-android --target x86_64-linux-android && sh scripts/build-android.sh x86_64)
(cd android && ./gradlew -p "$fixture/android" --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=12 :app:assembleDebug)
# The old fixture and code9 share a target directory. Cargo can otherwise keep
# build.rs output containing absolute include_str! paths into the old fixture.
cargo +1.99.1 clean --release -p moli-android --target x86_64-linux-android
sh scripts/build-android.sh x86_64
python3 - <<'PY'
from pathlib import Path
manifest = Path('integrations/meross-mss310/integration.toml').read_bytes()
assert manifest in Path('android/app/src/main/jniLibs/x86_64/libmoli_android.so').read_bytes(), 'The emulator JNI must embed the code9 catalogue'
PY
(cd android && ./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=13 :app:assembleDebug :app:assembleDebugAndroidTest)
mkdir -p android/app/build/reports/code13/screenshots
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
adb shell am instrument -w -r -e class io.moli.android.Code13UpgradeSeedTest#seedRealCode12 "$runner" > android/app/build/reports/code13/before.txt
cat android/app/build/reports/code13/before.txt
grep -Fq 'OK (1 test)' android/app/build/reports/code13/before.txt
adb shell am force-stop "$app"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am instrument -w -r -e class io.moli.android.Code13DeliveryTest#upgradeAndCorrectiveJourneys "$runner" > android/app/build/reports/code13/after.txt
cat android/app/build/reports/code13/after.txt
for name in integrations-mixed-landscape integrations-mixed-portrait osm-tile-webview failure; do
    target="android/app/build/reports/code13/screenshots/$name.png"
    adb exec-out run-as "$app" cat "files/code13-captures/$name.png" > "$target" 2>/dev/null || rm -f "$target"
    if [ -f "$target" ]; then python3 -c 'import sys;from pathlib import Path;p=Path(sys.argv[1]);p.unlink() if not p.read_bytes().startswith(bytes.fromhex("89504e470d0a1a0a")) else None' "$target"; fi
done
grep -Fq 'OK (1 test)' android/app/build/reports/code13/after.txt
adb exec-out run-as "$app" cat files/code13-captures/update-proof.txt > android/app/build/reports/code13/update-proof.txt
