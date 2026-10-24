#!/bin/sh
# Targeted UI proof in a fresh disposable preview, after encrypted update checks.
set -eu
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.preview
for package in "$app" "$app.test"; do
    if adb shell pm path "$package" | grep -q '^package:'; then echo 'Existing application left untouched' >&2; exit 1; fi
done
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.preview
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p ../android-artifacts/reports
adb shell am instrument -w -r -e class io.moli.android.ConcordanceFormsTest "$app.test/androidx.test.runner.AndroidJUnitRunner" > ../android-artifacts/reports/concordance-forms.txt
cat ../android-artifacts/reports/concordance-forms.txt
grep -Fq 'OK (1 test)' ../android-artifacts/reports/concordance-forms.txt
