#!/bin/sh
# Disposable CI emulator only. Never installs on a physical device.
set -eu
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'A disposable emulator is required' >&2; exit 1; }
app=io.moli.android.app.preview.home.v061
if adb shell pm path "$app" | grep -q '^package:'; then
    echo 'Expected a fresh emulator; existing installation left untouched' >&2; exit 1
fi
# Foreground UI checks only, after the disposable-emulator guard above.
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.preview.home.v061
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
for class in ProfileImportTest EngineDiagnosticsTest WeatherObservationTest HomeSetupTest BackNavigationTest; do
    log=../android-artifacts/reports/$class.txt
    adb shell am instrument -w -r -e class "io.moli.android.$class" "$app.test/androidx.test.runner.AndroidJUnitRunner" > "$log"
    cat "$log"
    grep -Fq 'OK (1 test)' "$log"
done

adb exec-out run-as "$app" cat files/weather-evidence.txt > ../android-artifacts/reports/weather-evidence.txt
cat ../android-artifacts/reports/weather-evidence.txt
