#!/bin/sh
# One isolated code7 → code8 fixture update; never a user's installed package.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$root/target}"
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then
    echo 'Fresh emulator required; existing installation left untouched' >&2; exit 1
fi
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
base=2ac0ed2326cf017554c47f186fb4d660c155fc70
# Only the disposable x86 test fixture is rebuilt from its exact old source.
# The released code7 ARM64 APK and the code8 sealed candidate are never rebuilt.
git archive "$base" | tar -x -C "$fixture"
# Exact code7 sources; its already built Web is identical to code8's.
cp -a web/dist "$fixture/web/dist"
(cd "$fixture" && cargo +1.99.1 clean --release -p moli-api --target x86_64-linux-android && sh scripts/build-android.sh x86_64)
(cd android && ./gradlew -p "$fixture/android" --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=7 :app:assembleDebug)
# Both APKs use the exact same code7 x86 JNI; only Android presentation changes.
mkdir -p android/app/src/main/jniLibs/x86_64
cp "$fixture/android/app/src/main/jniLibs/x86_64/libmoli_android.so" android/app/src/main/jniLibs/x86_64/

(cd android && ./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=8 :app:assembleDebug :app:assembleDebugAndroidTest)
mkdir -p android/app/build/reports/code8/screenshots
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
adb shell am instrument -w -r -e class io.moli.android.Code8UpgradeSeedTest#seedRealCode7 "$runner" > android/app/build/reports/code8/before.txt
cat android/app/build/reports/code8/before.txt
grep -Fq 'OK (1 test)' android/app/build/reports/code8/before.txt
adb shell am force-stop "$app"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am instrument -w -r -e class io.moli.android.Code8DeliveryTest#upgradeAndDiscoverSyntheticNetworkWithoutChangingMoliData "$runner" > android/app/build/reports/code8/after.txt
cat android/app/build/reports/code8/after.txt
for name in network-landscape network-searching-landscape network-portrait network-details-portrait failure; do
    target="android/app/build/reports/code8/screenshots/$name.png"
    adb exec-out run-as "$app" cat "files/code8-captures/$name.png" > "$target" 2>/dev/null || rm -f "$target"
    if [ -f "$target" ]; then
        python3 -c 'import sys;from pathlib import Path;p=Path(sys.argv[1]);p.unlink() if not p.read_bytes().startswith(bytes.fromhex("89504e470d0a1a0a")) else None' "$target"
    fi
done
grep -Fq 'OK (1 test)' android/app/build/reports/code8/after.txt
