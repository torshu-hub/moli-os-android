#!/bin/sh
# J1 fixtures only, on a fresh disposable emulator. Never targets code39.
set -eu
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then
    echo 'Fresh emulator required; existing installation is left untouched' >&2; exit 1
fi
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
./gradlew --no-daemon -PmoliAbis=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
mkdir -p app/build/reports/beta2/screenshots
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
adb shell am instrument -w -r -e class io.moli.android.Beta2JourneyTest#nativeLifecycleConfigurationAuthAndOfficialDashboard "$runner" > app/build/reports/beta2/journey.txt
cat app/build/reports/beta2/journey.txt
adb exec-out run-as "$app" cat files/screenshots/official-dashboard.png > app/build/reports/beta2/screenshots/official-dashboard.png
adb exec-out run-as "$app" cat files/screenshots/render.txt > app/build/reports/beta2/render.txt
cat app/build/reports/beta2/render.txt
grep -Fq 'OK (1 test)' app/build/reports/beta2/journey.txt
adb shell am force-stop "$app"
./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=2 :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w -r -e class io.moli.android.Beta2JourneyTest#updateKeepsTheNativeConfigurationAndWrappedKey "$runner" > app/build/reports/beta2/update.txt
cat app/build/reports/beta2/update.txt
grep -Fq 'OK (1 test)' app/build/reports/beta2/update.txt
