#!/bin/sh
# Disposable CI emulator only. Proves replacement rather than uninstall/reinstall.
set -eu
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'A disposable emulator is required' >&2; exit 1; }
app=io.moli.android.app.preview
receiver=$app.receiver
for package in "$app" "$receiver"; do
    if adb shell pm path "$package" | grep -q '^package:'; then
        echo 'Expected a fresh disposable emulator; existing app left untouched' >&2
        exit 1
    fi
done
mkdir -p ../android-artifacts
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
run_test() {
    package=$1
    class=$2
    method=$3
    log=$4
    adb shell am instrument -w -r -e class "io.moli.android.$class#$method" \
        "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$log"
    cat "$log"
    grep -Fq 'OK (1 test)' "$log"
}
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliVersionCode=3 -PmoliVersionName=0.3.0-preview
cp app/build/outputs/apk/debug/app-debug.apk "$scratch/before.apk"
adb install "$scratch/before.apk"
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
run_test "$app" UpdateContinuityTest prepareBeforeUpdate ../android-artifacts/update-before.txt
# Rescue copy leaves the app before replacement. Only this encrypted archive crosses UIDs.
adb exec-out run-as "$app" cat files/transfer.molibak > "$scratch/rescue.molibak"
./gradlew --no-daemon :app:assembleDebug -PmoliAbis=x86_64
adb install -r app/build/outputs/apk/debug/app-debug.apk
# No -d or data clearing: a downgrade must be refused while preserving the installation.
if adb install -r "$scratch/before.apk" > ../android-artifacts/downgrade.txt 2>&1; then
    echo 'Downgrade unexpectedly accepted' >&2
    exit 1
fi
grep -Fq 'INSTALL_FAILED_VERSION_DOWNGRADE' ../android-artifacts/downgrade.txt
run_test "$app" UpdateContinuityTest verifyAfterUpdate ../android-artifacts/update-after.txt
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.preview.receiver
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell run-as "$receiver" mkdir -p files
adb shell -T run-as "$receiver" tee files/transfer.molibak < "$scratch/rescue.molibak" > /dev/null
run_test "$receiver" RecoveryTransferTest importFreshIntoIndependentUid ../android-artifacts/update-rescue.txt
