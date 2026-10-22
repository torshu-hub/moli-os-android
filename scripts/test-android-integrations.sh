#!/bin/sh
# Disposable CI emulator only. Never installs on a physical device.
set -eu
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'A disposable emulator is required' >&2; exit 1; }
app=io.moli.android.app.preview.usability.v0110
if adb shell pm path "$app" | grep -q '^package:'; then
    echo 'Expected a fresh emulator; existing installation left untouched' >&2; exit 1
fi
# Foreground UI checks only, after the disposable-emulator guard above.
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.preview.usability.v0110
# Verify actual packaged fixture bytes, including on a clean CI checkout.
python3 - <<'PYFIXTURE'
from pathlib import Path
from zipfile import ZipFile
with ZipFile('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') as apk:
    fixtures = list(Path('../integrations/meross-mss310/fixtures').glob('*.json'))
    assert len(fixtures) == 4, 'Expected four MSS310 fixture inputs'
    for fixture in fixtures:
        assert apk.read('assets/mss310/' + fixture.name) == fixture.read_bytes(), fixture.name
print('MSS310 test APK: four exact synthetic fixtures packaged')
PYFIXTURE
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
failed=0
for class in ProfileImportTest EngineDiagnosticsTest WeatherObservationTest BackNavigationTest HomeSetupTest EquipmentReadTest MerossReadTest NetworkDiscoveryTest MerossAccountTest UsabilityJourneyTest; do
    log=../android-artifacts/reports/$class.txt
    # System Back and clipboard APIs require an interactive, unlocked display.
    # Keep each independent class on that same documented precondition.
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell wm dismiss-keyguard
    adb shell input keyevent KEYCODE_HOME
    # Collect independent results without turning a failed assertion green.
    if ! adb shell am instrument -w -r -e class "io.moli.android.$class" "$app.test/androidx.test.runner.AndroidJUnitRunner" > "$log"; then failed=1; fi
    cat "$log"
    if ! grep -Fq 'OK (1 test)' "$log"; then
        failed=1
        # Bounded system-state evidence only; no app data, credentials or screenshots.
        { adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|mShowingLockscreen|isKeyguardShowing';
          adb shell dumpsys power | grep -E 'mWakefulness=|mInteractive='; } > "../android-artifacts/reports/$class-focus.txt" || true
    fi
done

adb exec-out run-as "$app" cat files/weather-evidence.txt > ../android-artifacts/reports/weather-evidence.txt
cat ../android-artifacts/reports/weather-evidence.txt

[ "$failed" = 0 ]
