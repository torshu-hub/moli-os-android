# Android development preview

This APK hosts the **real Moli OS Rust engine and embedded Svelte dashboard**.
There is no Docker, Termux, remote server, or replacement control engine.
The Android shell uses the platform Java SDK to keep the first host small;
the engine is a JNI library, not a writable executable launched with exec.

## Build

Use Linux x86-64, JDK 17 or 21, Rust 1.99.1, Node 24.19.0, Gradle 8.11.1,
Android SDK platform/build-tools 35 and NDK 28.2.13676358. The workspace's
older declared Rust minimum does not cover all existing syntax; use the pinned
toolchain. No device is needed to build.

```sh
rustup target add --toolchain 1.99.1 aarch64-linux-android
cd web
npm ci --no-audit --no-fund
npm run build
cd ..
sh scripts/build-android.sh arm64-v8a
cd android
./gradlew --no-daemon :app:assembleDebug :app:lintDebug
cd ..
python3 scripts/verify-android-apk.py android/app/build/outputs/apk/debug/app-debug.apk
```

The debug APK contains only arm64-v8a by default. API 28 is provisional, not a
claim about an uninspected device. Packaging, signing and emulator tests do not
prove compatibility with any particular physical tablet. The debug signature
is for testing and not a long-term production update identity.

The CI also builds an x86_64 library and a separate emulator-only APK using
`-PmoliAbis=x86_64`. It tests JNI, local authentication, embedded assets,
stop/restart and Android Keystore in an isolated emulator with synthetic data.
Do not run these instrumentation tests on an existing household installation.

## Runtime and trust

The Activity starts a non-exported foreground service. Its notification offers
Stop; closing the Activity does not own the engine lifetime. Reboot/force-stop
does not automatically restart it. Android/Samsung power management still needs
physical testing. No wake locks or battery-optimization exemptions are enabled.
On recent Android, the user can choose whether to allow notifications.

The Rust library reuses the Linux engine's workers and shutdown flushes with an
external cancellation token. It binds an ephemeral **127.0.0.1** socket before
spawning workers, never the LAN. A random per-service token is installed as an
HttpOnly/SameSite cookie in the private WebView and checked on every request,
including assets and SSE. The authenticated client is classified as LAN rather
than machine-local CLI. The application token does not replace the human PIN,
approval guard or CSRF/Host checks. There is no JavaScript-to-native bridge.
Navigation and subresources are restricted to the current local origin.

Data and moli.toml remain in the app's private files/moli directory. The Android
host overrides storage/listening paths at startup rather than trusting machine
paths copied from another platform. No real driver is configured by default.
Linux-only host/ARP presence integrations are not enabled by this preview.

## Keys and future migration

At first start the user chooses the dashboard PIN. A random portable 256-bit
Moli master key and that bootstrap PIN are wrapped with AES-GCM under Android
Keystore and stored in noBackupFilesDir. The master key is passed to Rust only
in memory; Android does not use the engine's plaintext master.key fallback.
Existing encrypted stores without their wrapping material are never silently
rekeyed. Android automatic backups are disabled.

The core vault remains Moli's existing secrets.enc format and the master key is
not itself a non-exportable Keystore key. This preserves a route to Linux/Pi
migration. **Recovery-key export and complete portable backup UI are not yet
implemented**: do not keep irreplaceable household data in this preview. An APK
uninstall or Keystore loss can make its vault unrecoverable. Never copy a
plaintext master key or real backups into Git or CI artifacts. Backup coverage,
manifest integrity/versioning and restore-path adaptation remain separate work.
