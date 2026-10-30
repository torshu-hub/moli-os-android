#!/bin/sh
# Targeted code2 UX on a disposable tablet-size emulator; never addresses code39.
set -eu
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then
    echo 'Fresh emulator required; existing installation is left untouched' >&2; exit 1
fi
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
# Configuration/key continuity from J1 to code2 is already proven at 47eea9b.
# This presentation-only revision exercises the changed native UX, not that old fixture again.
./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=2 :app:assembleDebug :app:assembleDebugAndroidTest
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
mkdir -p app/build/reports/code2/screenshots
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
# Pixel Launcher's background ANR on this tablet image is outside the app under test.
# This targets only the launcher in the verified fresh QEMU instance, never Moli.
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am instrument -w -r -e class io.moli.android.ManagementJourneyTest "$runner" > app/build/reports/code2/journey.txt
cat app/build/reports/code2/journey.txt
for name in landscape-home-stopped landscape-home-running landscape-ime-latitude landscape-ime-longitude portrait-draft landscape-saved landscape-journal journal-export-picker official-dashboard-unchanged failure; do
    target="app/build/reports/code2/screenshots/$name.png"
    adb exec-out run-as "$app" cat "files/code2-captures/$name.png" > "$target" 2>/dev/null || rm -f "$target"
done
grep -Fq 'OK (1 test)' app/build/reports/code2/journey.txt
