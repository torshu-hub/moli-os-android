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
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
cd ..
python3 scripts/verify-android-apk.py android/app/build/outputs/apk/debug/app-debug.apk
```

The default debug APK now uses `io.moli.android.app.preview` (**Moli OS Preview**,
localized), independent of earlier prototypes. The prepared beta identity is
`io.moli.android.app.beta` (fixed label, version metadata in `android/beta.properties`);
`:app:assembleBeta` is non-debuggable and intentionally unsigned pending signing approval.
Production `io.moli.android.app` remains separate and unsigned.
See [identity, paired updates, signing proposal and sleep preparation](android-updates.md).
The debug APK contains only ARM64 by default, with provisional API 28 minimum.
Packaging and emulator results do not substitute for physical device validation.

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

The native home and dashboard header expose Start, Stop and Restart independently
of the WebView. Restart drains the existing engine before starting a new lease;
it never recreates keys or clears configuration. Status uses bounded authenticated
HTTP observations, distinguishing failed checks from checks not yet performed.
Returning from settings reauthenticates/reloads the dashboard, even when a new
engine reuses the same local address; failed page loads retry every five seconds.
Reconnect dashboard reloads only the display, without restarting the engine.
Equipment cards show one compact receipt time, with per-point times under Details.
Meross startup/brief loss of confirmation is labelled as pending, never as fresh;
expired readings remain visibly old and persistent loss remains unavailable.

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

Fresh 0.13 previews open a single trial home: Search, My devices and Export report.
The first deliberate search or device consultation creates independent random
256-bit master and bootstrap credentials, without asking for a PIN. Before that,
Settings → Backup/transfer can restore an existing encrypted archive.
Legacy numeric PIN credentials remain valid and are never automatically rekeyed.
Format-2 random bootstrap credentials survive encrypted archive round trips.
The random portable Moli master key and bootstrap are wrapped with AES-GCM under Android
Keystore and stored in noBackupFilesDir. The master key is passed to Rust only
in memory; Android does not use the engine's plaintext master.key fallback.
Existing encrypted stores without their wrapping material are never silently
rekeyed. Android automatic backups are disabled.

## Recovery preview

Stop the server and wait for shutdown before opening **Backup / Restore**.
Export uses Android’s document picker and encrypts the entire internal data tree,
including SQLite, recordings, unknown future files, configuration, `secrets.enc`,
the portable master key and bootstrap PIN. Choose a unique recovery passphrase
(16–1,024 characters; several random words recommended), confirm it and keep it
separately. This protects much more than the already encrypted vault. File picker
providers may store files remotely; choose the destination you intend.

Restore is deliberately limited to a **fresh installation**, with the engine stopped.
It never overwrites an existing key/data set and never starts the engine. The
archive is verified in a private staging generation, with hashes, inventory,
version, authentication, path and size limits checked before activation. Data
files are synced, then one AtomicFile Keystore-wrapped record selects both the
new generation and its portable key. No plaintext key/ZIP is staged on disk.
An interrupted uncommitted generation cannot become active; abrupt process loss
can leave an inactive private staging directory. No crash/power-loss qualification
is claimed. Ordinary failed imports clean only their own staging files.

Limits: 512 MiB of regular internal files, 10,000 files, depth 13, manifest 4 MiB;
ZIP plaintext stream at most 520 MiB. Symlinks and plaintext `master.key` are refused.
References to external profiles/fixtures are preserved in the configuration but
those external files are not included. Imported machine listening/storage paths
are overridden by the embedded host as before. No Android-to-Linux migration or
Raspberry Pi testing is provided by this preview. The format is specified in
[android-backup-format.md](android-backup-format.md) to keep future recovery independent
of Android Keystore. Original data formats and stable device IDs are preserved.

Use synthetic settings only until recovery has also been exercised through the
file picker on the target tablet. Do not remove the earlier app or enter real
credentials for this test. Durable signing remains a separate owner decision; the paired demo verifies one delivery only.

The process-wide native queue excludes backup/restore from start/stop, displays
starting/stopping states, and changes notification text after readiness. Start is
disabled while stopping. Closing/recreating the Activity does not stop the service;
an explicit stop is remembered on reopening. The WebView/UI polling pauses when
hidden and service polling stops once ready. No wake lock, battery exemption,
reboot receiver or automatic recovery from Android force-stop is added.

The CI additionally transfers **only an encrypted synthetic archive** between two
installed applications with distinct Android UIDs, verifies the receiver initially
has no wrapping-key alias, restores SQLite/files/the vault, and starts the real
engine with the recovered key. No runtime archive is retained in CI artifacts.

CI reuses only the two public-code JNI libraries when Rust sources/dependencies,
embedded assets/catalogues/locales, build script, ABI/toolchain/API settings match.
Android shell changes still rebuild and verify both APKs and run emulator tests.
The cache never contains runtime data, keys or backup files.
