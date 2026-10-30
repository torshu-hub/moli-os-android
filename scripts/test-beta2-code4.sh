#!/bin/sh
# A fresh disposable emulator only. Keep code3 data across the real APK update.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then
    echo 'Fresh emulator required; existing installation left untouched' >&2; exit 1
fi
fixture=$(mktemp -d)
restore_web() {
    if [ -d "$fixture/code4-dist" ]; then rm -rf "$root/web/dist"; mv "$fixture/code4-dist" "$root/web/dist"; fi
    rm -rf "$fixture"
}
trap restore_web EXIT
git archive cfbb88c1411b9ca579c89d5c7f9fce0e243102ad android web locales LICENSE NOTICE licenses | tar -x -C "$fixture"
cmp web/package-lock.json "$fixture/web/package-lock.json"
# Same dependencies; compile the old source once for the actual code3 fixture.
ln -s "$root/web/node_modules" "$fixture/web/node_modules"
(cd "$fixture/web" && npm run build)
mv web/dist "$fixture/code4-dist"
cp -a "$fixture/web/dist" web/dist
# Rust source and engine logic remain code3's; only RustEmbed must be regenerated.
cargo +1.99.1 clean --release -p moli-api --target x86_64-linux-android
sh scripts/build-android.sh x86_64
mkdir -p "$fixture/android/app/src/main/jniLibs/x86_64"
cp android/app/src/main/jniLibs/x86_64/libmoli_android.so "$fixture/android/app/src/main/jniLibs/x86_64/"
rm -rf web/dist
mv "$fixture/code4-dist" web/dist
cargo +1.99.1 clean --release -p moli-api --target x86_64-linux-android
sh scripts/build-android.sh x86_64
(cd android && ./gradlew -p "$fixture/android" --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=3 :app:assembleDebug)
(cd android && ./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=4 :app:assembleDebug :app:assembleDebugAndroidTest)
mkdir -p android/app/build/reports/code4/screenshots
python3 - <<'PY'
from pathlib import Path
import hashlib,re
dist=Path('web/dist');html=(dist/'index.html').read_text()
files=['index.html',re.search(r'src="(/assets/[^"]+\.js)"',html)[1][1:],re.search(r'href="(/assets/[^"]+\.css)"',html)[1][1:]]
Path('android/app/build/reports/code4/expected-served-web.txt').write_text('\n'.join(hashlib.sha256((dist/p).read_bytes()).hexdigest() for p in files)+'\n')
PY
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install "$fixture/android/app/build/outputs/apk/debug/app-debug.apk"
adb install android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
adb shell am force-stop com.google.android.apps.nexuslauncher
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
test_class=io.moli.android.Code4WebIntegrationTest
adb shell am instrument -w -r -e class "$test_class#seedOnRealCode3" "$runner" > android/app/build/reports/code4/before.txt
cat android/app/build/reports/code4/before.txt
grep -Fq 'OK (1 test)' android/app/build/reports/code4/before.txt
adb shell am force-stop "$app"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
set -- $(cat android/app/build/reports/code4/expected-served-web.txt)
adb shell am instrument -w -r -e class "$test_class#updateUsesTheNewEmbeddedWebAndRetainsCode3Data" -e expectedIndex "$1" -e expectedJs "$2" -e expectedCss "$3" "$runner" > android/app/build/reports/code4/after.txt
cat android/app/build/reports/code4/after.txt
for name in rail-navigation inspector-landscape inspector-portrait; do
    target="android/app/build/reports/code4/screenshots/$name.png"
    adb exec-out run-as "$app" cat "files/code4-captures/$name.png" > "$target" 2>/dev/null || rm -f "$target"
done
grep -Fq 'OK (1 test)' android/app/build/reports/code4/after.txt
