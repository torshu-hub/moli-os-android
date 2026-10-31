#!/bin/sh
# One code8 -> code9 update in a disposable emulator, with existing synthetic seed data.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$root/target}"
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then echo 'Fresh emulator required; existing installation left untouched' >&2; exit 1; fi
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
base=df756ac5fc453a57d9e40aa00ba4092aa50f5e88
git fetch --no-tags --depth=1 https://github.com/torshu-hub/moli-os-android.git "$base"
git archive "$base" | tar -x -C "$fixture"
cp -a web/dist "$fixture/web/dist"
(cd "$fixture" && cargo +1.99.1 clean --release -p moli-api --target x86_64-linux-android && sh scripts/build-android.sh x86_64)
(cd android && ./gradlew -p "$fixture/android" --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=8 :app:assembleDebug)
sh scripts/build-android.sh x86_64
(cd android && ./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=9 :app:assembleDebug :app:assembleDebugAndroidTest)
mkdir -p android/app/build/reports/code9/screenshots
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
adb shell am instrument -w -r -e class io.moli.android.Code9UpgradeSeedTest#seedRealCode8 "$runner" > android/app/build/reports/code9/before.txt
cat android/app/build/reports/code9/before.txt
grep -Fq 'OK (1 test)' android/app/build/reports/code9/before.txt
adb shell am force-stop "$app"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am instrument -w -r -e class io.moli.android.Code9DeliveryTest#upgradeAndConfigureMerossWithoutTouchingExistingDataOrNetwork "$runner" > android/app/build/reports/code9/after.txt
cat android/app/build/reports/code9/after.txt
for name in meross-form-landscape meross-form-portrait failure; do
    target="android/app/build/reports/code9/screenshots/$name.png"
    adb exec-out run-as "$app" cat "files/code9-captures/$name.png" > "$target" 2>/dev/null || rm -f "$target"
    if [ -f "$target" ]; then python3 -c 'import sys;from pathlib import Path;p=Path(sys.argv[1]);p.unlink() if not p.read_bytes().startswith(bytes.fromhex("89504e470d0a1a0a")) else None' "$target"; fi
done
grep -Fq 'OK (1 test)' android/app/build/reports/code9/after.txt
adb exec-out run-as "$app" cat files/code9-captures/update-proof.txt > android/app/build/reports/code9/update-proof.txt
