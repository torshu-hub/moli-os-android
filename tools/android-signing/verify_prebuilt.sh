#!/bin/sh
# Only signed disposable emulator fixtures; the real signer and secrets are not in this job.
set -eu
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
preview=io.moli.android.app.preview.automatic.v0130
beta=io.moli.android.app.beta
legacy=io.moli.android.app.preview.receiver
for package in "$preview" "$preview.test" "$beta" "$beta.test" "$legacy" "$legacy.test"; do
    if adb shell pm path "$package" | grep -q '^package:'; then echo 'Fresh emulator required; existing data untouched' >&2; exit 1; fi
done
mkdir -p proof
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
run_test() {
    adb shell am instrument -w -r -e class "io.moli.android.BetaUpdateTest#$2" "$1.test/androidx.test.runner.AndroidJUnitRunner" > "proof/$3.txt"
    cat "proof/$3.txt"
    grep -Fq 'OK (1 test)' "proof/$3.txt"
}
transfer() {
    adb exec-out run-as "$preview" cat "files/$1" > "$scratch/archive"
    adb shell run-as "$2" mkdir -p files
    adb shell -T run-as "$2" tee "files/$1" < "$scratch/archive" > /dev/null
}
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install signed/emulator/source.apk
adb install signed/emulator/source-test.apk
run_test "$preview" exportPreviewWithoutPin preview-export
run_test "$preview" exportLegacyNumericPinFixture legacy-export
adb uninstall "$preview.test"
adb install signed/emulator/beta-a.apk
adb install signed/emulator/beta-test.apk
transfer beta-transfer.molibak "$beta"
run_test "$beta" importIntoFreshBetaA migration-a
adb install -r signed/emulator/beta-b.apk
run_test "$beta" verifyBetaBAfterReplacement update-b
if adb install -r signed/emulator/beta-a.apk > proof/downgrade.txt 2>&1; then exit 1; fi
grep -Fq INSTALL_FAILED_VERSION_DOWNGRADE proof/downgrade.txt
run_test "$beta" verifyBetaBAfterReplacement after-refused-downgrade
adb uninstall "$beta.test"
adb install signed/emulator/legacy.apk
adb install signed/emulator/legacy-test.apk
transfer legacy-transfer.molibak "$legacy"
run_test "$legacy" restoreLegacyPinInIndependentUid legacy-restore
# No uninstall of the beta, no clearing, export or restore between A and B.
# The entire disposable VM is discarded by the emulator runner after the proof.
printf '%s\n' 'PASS same approved beta certificate, migration, A->B, UID/Keystore/vault/account/data intact; synthetic fixtures only' > proof/summary.txt
