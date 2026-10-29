#!/bin/sh
# Targeted code2 UX on a disposable tablet-size emulator; never addresses code39.
set -eu
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then
    echo 'Fresh emulator required; existing installation is left untouched' >&2; exit 1
fi
adb shell wm size 1600x2560
adb shell wm density 320
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
# Package the actual J1 Java application, reusing the identical x86 JNI engine.
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
# The public fixed source is fetched only to create this disposable test APK.
git fetch --depth=1 https://github.com/torshu-hub/moli-os-android.git 0b188d630eabd3b0bd77c5f56bb64bd06f9800f3
git -C .. archive 0b188d630eabd3b0bd77c5f56bb64bd06f9800f3 android LICENSE NOTICE licenses | tar -x -C "$fixture"
mkdir -p "$fixture/android/app/src/main/jniLibs/x86_64"
cp app/src/main/jniLibs/x86_64/libmoli_android.so "$fixture/android/app/src/main/jniLibs/x86_64/"
./gradlew -p "$fixture/android" --no-daemon -PmoliAbis=x86_64 :app:assembleDebug
./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=2 :app:assembleDebug :app:assembleDebugAndroidTest
adb install "$fixture/android/app/build/outputs/apk/debug/app-debug.apk"
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
mkdir -p app/build/reports/code2/screenshots
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
adb shell am instrument -w -r -e class io.moli.android.ManagementContinuityTest#seedCode1 "$runner" > app/build/reports/code2/seed.txt
cat app/build/reports/code2/seed.txt
grep -Fq 'OK (1 test)' app/build/reports/code2/seed.txt
adb shell am force-stop "$app"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w -r -e class io.moli.android.ManagementContinuityTest#code2KeepsCode1Data "$runner" > app/build/reports/code2/update.txt
cat app/build/reports/code2/update.txt
grep -Fq 'OK (1 test)' app/build/reports/code2/update.txt
adb shell am instrument -w -r -e class io.moli.android.ManagementJourneyTest "$runner" > app/build/reports/code2/journey.txt
cat app/build/reports/code2/journey.txt
for name in landscape-home-stopped landscape-home-running landscape-ime-latitude landscape-ime-longitude portrait-draft landscape-saved landscape-journal journal-export-picker official-dashboard-unchanged; do
    adb exec-out run-as "$app" cat "files/code2-captures/$name.png" > "app/build/reports/code2/screenshots/$name.png" || true
done
grep -Fq 'OK (1 test)' app/build/reports/code2/journey.txt
