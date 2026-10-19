#!/bin/sh
# Disposable emulator only, after instrumentation has created synthetic app data.
set -eu
app=io.moli.android.recovery
activity=$app/io.moli.android.MainActivity
adb shell am start -W -n "$activity"
sleep 3
adb shell dumpsys activity services "$app" > /tmp/moli-emulator-services.txt
rg -q 'isForeground=true' /tmp/moli-emulator-services.txt
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell pidof "$app"
adb shell dumpsys activity services "$app" > /tmp/moli-emulator-services.txt
rg -q 'isForeground=true' /tmp/moli-emulator-services.txt
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
# Force-stop is explicit Android policy, so the app must not auto-restart itself.
adb shell am force-stop "$app"
sleep 2
if adb shell pidof "$app"; then
    echo 'Process unexpectedly running after force-stop' >&2
    exit 1
fi
adb shell am start -W -n "$activity"
sleep 3
adb shell dumpsys activity services "$app" > /tmp/moli-emulator-services.txt
rg -q 'isForeground=true' /tmp/moli-emulator-services.txt
adb shell am force-stop "$app"
echo 'Emulator foreground service, short screen sleep, force-stop and manual return passed.'
