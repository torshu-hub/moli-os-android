#!/bin/sh
# Only disposable synthetic emulator data. Never run against an existing household app.
set -eu
sender=io.moli.android.recovery
receiver=io.moli.android.recovery.receiver
mkdir -p ../android-artifacts
run_test() {
    package=$1
    method=$2
    log=$3
    adb shell am instrument -w -r -e class "io.moli.android.RecoveryTransferTest#$method" \
        "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$log"
    cat "$log"
    rg -q 'OK \(1 test\)' "$log"
}
run_test "$sender" exportSynthetic ../android-artifacts/recovery-export-test.txt
transfer=$(mktemp)
trap 'rm -f "$transfer"' EXIT
# Only the encrypted archive crosses UIDs. Never transfer a wrapped key or plaintext data.
adb exec-out run-as "$sender" cat files/transfer.molibak > "$transfer"
./gradlew --no-daemon :app:installDebug :app:installDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.recovery.receiver
adb shell run-as "$receiver" mkdir -p files
adb push "$transfer" /data/local/tmp/moli-synthetic-transfer.molibak
adb shell run-as "$receiver" cp /data/local/tmp/moli-synthetic-transfer.molibak files/transfer.molibak
run_test "$receiver" importFreshIntoIndependentUid ../android-artifacts/recovery-import-test.txt
adb shell rm /data/local/tmp/moli-synthetic-transfer.molibak
