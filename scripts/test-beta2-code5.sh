#!/bin/sh
# One isolated code4 → code5 fixture update; never a user's installed package.
set -eu
cd "$(dirname "$0")/.."
root=$(pwd)
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$root/target}"
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Disposable emulator required' >&2; exit 1; }
app=io.moli.android.app.beta2.test
if adb shell pm path "$app" | grep -q '^package:'; then
    echo 'Fresh emulator required; existing installation left untouched' >&2; exit 1
fi
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
base=7ca9fc8e5ef2e0ea373c7cc4540776f2457e0791
# Only the disposable x86 test fixture is rebuilt from its exact old source.
# The released code4 ARM64 APK and the code5 sealed candidate are never rebuilt.
git archive "$base" | tar -x -C "$fixture"
cmp web/package-lock.json "$fixture/web/package-lock.json"
ln -s "$root/web/node_modules" "$fixture/web/node_modules"
(cd "$fixture/web" && npm run build)
(cd "$fixture" && cargo +1.99.1 clean --release -p moli-api --target x86_64-linux-android && sh scripts/build-android.sh x86_64)
(cd android && ./gradlew -p "$fixture/android" --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=4 :app:assembleDebug)
cargo +1.99.1 clean --release -p moli-api --target x86_64-linux-android
sh scripts/build-android.sh x86_64
(cd android && ./gradlew --no-daemon -PmoliAbis=x86_64 -PmoliFixtureVersionCode=5 :app:assembleDebug :app:assembleDebugAndroidTest)
mkdir -p android/app/build/reports/code5/screenshots
python3 - <<'PY'
from pathlib import Path
import hashlib,re
dist=Path('web/dist');html=(dist/'index.html').read_text()
files=['index.html',re.search(r'src="(/assets/[^"]+\.js)"',html)[1][1:],re.search(r'href="(/assets/[^"]+\.css)"',html)[1][1:]]
Path('android/app/build/reports/code5/expected-served-web.txt').write_text('\n'.join(hashlib.sha256((dist/p).read_bytes()).hexdigest() for p in files)+'\n')
PY
adb shell wm size 1280x720
adb shell wm density 160
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install "$fixture/android/app/build/outputs/apk/debug/app-debug.apk"
adb install android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS
adb shell am force-stop com.google.android.apps.nexuslauncher
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"
test_class=io.moli.android.Code5DeliveryTest
adb shell am instrument -w -r -e class "$test_class#seedOnRealCode4" "$runner" > android/app/build/reports/code5/before.txt
cat android/app/build/reports/code5/before.txt
grep -Fq 'OK (1 test)' android/app/build/reports/code5/before.txt
adb shell am force-stop "$app"
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.google.android.apps.nexuslauncher
set -- $(cat android/app/build/reports/code5/expected-served-web.txt)
adb shell am instrument -w -r -e class "$test_class#upgradeNavigationOfficialProfilesAndPortraitRail" -e expectedIndex "$1" -e expectedJs "$2" -e expectedCss "$3" "$runner" > android/app/build/reports/code5/after.txt
cat android/app/build/reports/code5/after.txt
for name in portrait-integrations portrait-rail-actions landscape-full-dashboard failure; do
    target="android/app/build/reports/code5/screenshots/$name.png"
    adb exec-out run-as "$app" cat "files/code5-captures/$name.png" > "$target" 2>/dev/null || rm -f "$target"
    if [ -f "$target" ]; then
        python3 -c 'import sys;from pathlib import Path;p=Path(sys.argv[1]);p.unlink() if not p.read_bytes().startswith(bytes.fromhex("89504e470d0a1a0a")) else None' "$target"
    fi
done
grep -Fq 'OK (1 test)' android/app/build/reports/code5/after.txt
