#!/bin/sh
# Fixed test package on a fresh disposable emulator, never owner/tablet data.
set -eu
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then
    echo 'Fresh emulator required; existing installation left untouched' >&2; exit 1
fi
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
git fetch --depth=1 https://github.com/torshu-hub/moli-os-android.git 3982fce81ec6ea3a3275343a555bef46f6000029
git -C .. archive 3982fce81ec6ea3a3275343a555bef46f6000029 android LICENSE NOTICE licenses | tar -x -C "$fixture"
mkdir -p "$fixture/android/app/src/main/jniLibs/x86_64"
cp app/src/main/jniLibs/x86_64/libmoli_android.so "$fixture/android/app/src/main/jniLibs/x86_64/"
./gradlew -p "$fixture/android" --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=2 :app:assembleDebug
./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=3 :app:assembleDebug :app:assembleDebugAndroidTest
adb install "$fixture/android/app/build/outputs/apk/debug/app-debug.apk"
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
adb shell am force-stop com.google.android.apps.nexuslauncher
mkdir -p app/build/reports/code3/screenshots
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
adb shell am instrument -w -r -e class io.moli.android.Code2WeatherSeedTest "$runner" > app/build/reports/code3/before.txt
cat app/build/reports/code3/before.txt
grep -Fq 'OK (1 test)' app/build/reports/code3/before.txt
adb shell am force-stop "$app"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am instrument -w -r -e class io.moli.android.Code3WeatherUpdateTest "$runner" > app/build/reports/code3/after.txt
cat app/build/reports/code3/after.txt
for name in apk-home-landscape apk-home-portrait official-dehors-weather engine-running-portrait engine-running-landscape engine-stopped; do
    target="app/build/reports/code3/screenshots/$name.png"
    adb exec-out run-as "$app" cat "files/code3-captures/$name.png" > "$target" 2>/dev/null || rm -f "$target"
done
grep -Fq 'OK (1 test)' app/build/reports/code3/after.txt
