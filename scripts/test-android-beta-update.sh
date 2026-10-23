#!/bin/sh
# Disposable emulator only. APKs and signing material are never delivery artifacts.
set -eu
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
preview=io.moli.android.app.preview.automatic.v0130
beta=io.moli.android.app.beta
legacy=io.moli.android.app.preview.receiver
for package in "$preview" "$preview.test" "$beta" "$beta.test" "$legacy" "$legacy.test"; do
    if adb shell pm path "$package" | grep -q '^package:'; then
        echo 'Existing application left untouched; use a fresh emulator' >&2; exit 1
    fi
done
mkdir -p ../android-artifacts/reports
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
run_test() {
    package=$1; method=$2; log=$3
    adb shell am instrument -w -r -e class "io.moli.android.BetaUpdateTest#$method" "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$log"
    cat "$log"
    grep -Fq 'OK (1 test)' "$log"
}
transfer_archive() {
    # Only the encrypted archive crosses UIDs. No wrapping key or plaintext data.
    adb exec-out run-as "$preview" cat "files/$1" > "$scratch/transfer.molibak"
    adb shell run-as "$2" mkdir -p files
    adb shell -T run-as "$2" tee "files/$1" < "$scratch/transfer.molibak" > /dev/null
}
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.preview.automatic.v0130 -PmoliVersionCode=16 -PmoliVersionName=0.13.0-preview
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_test "$preview" exportPreviewWithoutPin ../android-artifacts/reports/beta-preview-export.txt
run_test "$preview" exportLegacyNumericPinFixture ../android-artifacts/reports/beta-legacy-export.txt
# The legacy synthetic document provider has one authority. Remove only the finished
# instrumentation APK, leaving the preview app, UID, files and Keystore untouched.
adb uninstall "$preview.test"
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.beta -PmoliUpdateFixture=true -PmoliVersionCode=17 -PmoliVersionName=0.14.0-beta.1-fixture
cp app/build/outputs/apk/debug/app-debug.apk "$scratch/a.apk"
adb install "$scratch/a.apk"
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
transfer_archive beta-transfer.molibak "$beta"
run_test "$beta" importIntoFreshBetaA ../android-artifacts/reports/beta-migration-a.txt
./gradlew --no-daemon :app:assembleDebug -PmoliAbis=x86_64 -PmoliAppSuffix=.beta -PmoliUpdateFixture=true -PmoliVersionCode=18 -PmoliVersionName=0.14.0-beta.2-fixture
cp app/build/outputs/apk/debug/app-debug.apk "$scratch/b.apk"
adb install -r "$scratch/b.apk"
run_test "$beta" verifyBetaBAfterReplacement ../android-artifacts/reports/beta-update-b.txt
if adb install -r "$scratch/a.apk" > ../android-artifacts/reports/beta-downgrade.txt 2>&1; then echo 'Downgrade unexpectedly accepted' >&2; exit 1; fi
grep -Fq INSTALL_FAILED_VERSION_DOWNGRADE ../android-artifacts/reports/beta-downgrade.txt
# A second, deliberately disposable signer must be refused. This is not a distribution key.
keytool -genkeypair -keystore "$scratch/wrong.jks" -storepass synthetic-only-password -keypass synthetic-only-password -alias fixture -dname 'CN=Disposable update rejection fixture' -keyalg RSA -keysize 2048 -validity 1 > /dev/null 2>&1
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$scratch/wrong.jks" --ks-key-alias fixture --ks-pass pass:synthetic-only-password --key-pass pass:synthetic-only-password --out "$scratch/wrong.apk" "$scratch/b.apk"
if adb install -r "$scratch/wrong.apk" > ../android-artifacts/reports/beta-wrong-signature.txt 2>&1; then echo 'Wrong signer unexpectedly accepted' >&2; exit 1; fi
grep -Fq INSTALL_FAILED_UPDATE_INCOMPATIBLE ../android-artifacts/reports/beta-wrong-signature.txt
run_test "$beta" verifyBetaBAfterReplacement ../android-artifacts/reports/beta-after-refusals.txt
adb uninstall "$beta.test" # Both beta replacement checks have already completed.
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest -PmoliAbis=x86_64 -PmoliAppSuffix=.preview.receiver
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
transfer_archive legacy-transfer.molibak "$legacy"
run_test "$legacy" restoreLegacyPinInIndependentUid ../android-artifacts/reports/beta-legacy-restore.txt
# Remove only packages created by this successful test, on this disposable emulator.
# No uninstall occurs between beta A and B. This permits the existing fresh-install suite next.
adb uninstall "$legacy.test"
for package in "$preview" "$beta" "$legacy"; do adb uninstall "$package"; done
printf '%s\n' 'PASS preview migration, same-UID beta A->B, vault/config/account preserved, wrong signer and downgrade refused, legacy PIN archive' > ../android-artifacts/reports/beta-update-summary.txt
