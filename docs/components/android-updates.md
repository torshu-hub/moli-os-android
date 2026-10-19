# Android identity, updates and recovery

The unified app contains the original Rust engine/dashboard, start/stop controls,
encrypted backup/restore, and **App / Updates**. That screen shows the installed
version, package ID and public signing-certificate SHA-256, plus Android notification
and battery-optimization states. It never displays the Moli master key or reads a
signing private key. Android settings open only when the user taps the button.

## Identities and transition

| Build | Application ID | Purpose |
|---|---|---|
| Proposed durable release, currently unsigned | `io.moli.android.app` | One ongoing application after signing approval |
| Unified demo | `io.moli.android.app.preview` | Paired upgrade demonstration; Moli OS Preview |
| Disposable emulator receiver | `io.moli.android.app.preview.receiver` | Pre-update rescue archive verification |

The old `io.moli.android`, `io.moli.android.recovery`, and
`io.moli.android.recovery.receiver` installations remain independent. The unified
app cannot read their private data. A recovery-preview archive can be imported
into the **fresh** unified app before starting it; the original prototype has no
export interface and is not automatically migrated. Never uninstall an existing
installation to resolve an update/signature error.

## Try the paired demo APKs

The **Android update continuity** workflow delivers two ARM64 APKs signed with the
same disposable debug certificate in a single CI job:

1. Install `moli-preview-0.3.0-arm64.apk` (version code 3). Before its first Start,
   optionally restore an existing *synthetic* recovery-preview archive. Otherwise
   create a test PIN and use the dashboard normally.
2. Stop the server and wait for shutdown. Export a recovery archive and retain its
   password separately. Keep the app installed.
3. Install `moli-preview-0.3.1-arm64.apk` (code 4) **over** the first APK using the
   Android installer. Reopen it, check version 0.3.1 in App / Updates, then Start.
   Check the same PIN and test configuration. Explicit Stop remains remembered.

Both builds deliberately share the same source with different version metadata:
this demonstrates Android package replacement and persistence, not a database
schema migration. Different CI jobs can have different debug signers; only use the
pair from **one** artifact. A later preview is not promised to update this one.
If either installer refuses, stop and report the message; do not clear app data,
force a downgrade or remove any installed app. The future durable identity will
receive an encrypted restore into a fresh installation once approved.

Android updates require the same application ID, a compatible signer and increasing
version codes. Installation kills the running process, so the supported procedure
is **stop → encrypted backup → install → reopen → start**. No automatic installer,
boot receiver or background restart is introduced. Backups use the existing portable
format and native data layout. For an incompatible future format, keep the previous
signed APK and recovery archive, and require a tested migration/recovery plan before
shipping; APK downgrade is not a data rollback strategy.

## Durable signing proposal — owner decision required

Recommended first distribution: versioned APKs with release notes, checksums and
public certificate fingerprint, installed manually through Android. No Play account,
paid service or in-app updater is needed. Publishing a release remains a deliberate
step after its checks and owner approval where required.

Reserve `io.moli.android.app`, keep one long-lived signing certificate and monotonically
increase `versionCode`. Create its private keystore **only after explicit owner
approval**, on an owner-controlled trusted machine. Keep an encrypted offline backup
and the password in separate owner-controlled storage. Never put either in Git,
APK artifacts, build caches, logs or chat. The signing key is distinct from the
per-installation Moli encryption key and Android Keystore wrapping key.

Initially CI can build the unsigned release (`:app:assembleRelease`); signing occurs
outside CI using that owner-held keystore. Verify its certificate fingerprint and
install/update test before distribution. This limits production-key exposure but
requires an owner signing step per release. For later unattended distribution, a
separate explicit approval can authorize a keystore/password in a restricted GitHub
environment and an audited signing workflow, with branch/environment restrictions
and a separately held recovery copy. No such environment, secret, permanent key,
public release or signing automation is created by this change.

The release build intentionally has no signing configuration and does not fall back
to a debug signer. Loss of the durable signer can prevent updates; recovery of data
into a separately signed identity then depends on a valid encrypted archive and its
password. Preserve both independently.

## Verification scope

`scripts/test-android-update.sh` requires a disposable emulator and absent test
packages. It configures version 3, records the private file inventory, wrapped-key
hash, public signer and UID, and copies only the encrypted rescue archive out.
It installs version 4 with `adb install -r` (no clearing/uninstall), requires an
ordinary downgrade to be refused, checks unchanged files/preferences/wrapped key,
opens that key with Android Keystore, then starts the actual server and dashboard.
Finally it restores the pre-update archive in a separate UID/Keystore and checks
SQLite, recordings and vault startup. Neither plaintext keys nor runtime archives
are retained in CI artifacts. No claim of crash/power-loss atomicity or real-device
upgrade validation follows from this emulator test.

## Samsung sleep preparation

When the owner is available, use synthetic data and record Android/One UI version,
app version, notification status and the battery state shown in App / Updates.
Keep the initial Samsung settings for the first observation:

- Start and lock the screen for 30–60 minutes; note notification presence, dashboard
  responsiveness and any error on reopening. A visible notification alone does not
  prove the engine worked continuously while asleep.
- If that succeeds, observe a longer idle period (for example overnight) and note
  time and battery change. Without active integrations there is no proof yet of
  scheduled actions or network reliability during Doze.
- Separately, explicitly Stop, close and reopen: it must remain stopped. After an
  owner-initiated reboot or Android force-stop, reopen and start manually as needed.
- If Samsung restricts the app, record its Battery/background-use settings before
  changing anything. The Android exemption indicator does not cover Samsung deep
  sleep lists; change one setting at a time only under owner control.

No wake lock, blanket battery exemption, boot automation, real-equipment operation,
or Linux migration work is included here.
