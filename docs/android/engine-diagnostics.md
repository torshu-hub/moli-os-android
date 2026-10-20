# Active engine diagnostics and file-profile feasibility

The Android host checks the existing authenticated loopback `/api/health` route
on a separate worker, without HTTP caching or redirects. Requests have connect
and read timeouts and a bounded response body. Only the expected engine identity
is retained; tokens and response details are never logged or persisted.

Application / Updates shows the age of the last success, successes/failures in the
current native lifetime, the largest interval without a check, and a persistent
count of successful host starts. A fresh success (at most 65 seconds old) confirms
that Rust served HTTP and accessed the runtime hub at that moment. A failed last
request invalidates that indicator immediately. The dashboard and notification
alone do not establish this. A request is attempted every 30 seconds while the
service runs, subject to Android scheduling; no wake lock, boot receiver or battery
exemption is added. The notification refreshes on that same schedule.

Checks do not establish uninterrupted operation between samples, driver health,
network reachability, or reliable automation during sleep. Large gaps are evidence
of missing observations, not a diagnosis of their cause. The native lifetime and
successful-start counter distinguish Activity recreation from an engine restart.
Counters for a process killed before persistence may be incomplete. There is no
automatic restart triggered by an HTTP failure: Stop / Start remains explicit.
Opening MainActivity can still resume a previously requested server; compare the
start counter before and after. Force-stop and device boot do not trigger an
unsolicited restart. A failed probe leaves data and the native host untouched.

## Isolated diagnostic APK

Build `-PmoliAppSuffix=.preview.diagnostics`: package
`io.moli.android.app.preview.diagnostics`, label Moli OS Diagnostic / Diagnostics,
version 0.4.0-preview. It installs beside existing previews, with separate data.
It uses a disposable debug signer; it is not an in-place update signed with an older
CI job's key. Do not uninstall an existing preview. Use synthetic data for the
background trial; no backup migration is necessary for that trial.

## Targeted checks

- `:app:testDebugUnitTest --tests io.moli.android.EngineHealthTest`: expected JSON,
  authorization/error/redirect responses, oversized body and non-loopback refusal.
- `EngineDiagnosticsTest`: real Rust HTTP after Activity recreation and closure;
  native shutdown while the Java host still says RUNNING must invalidate health;
  explicit stop/restart and start counter.
- `ProfileFixtureTest`: existing Rust engine accepts a file profile only after its
  exact-byte SHA-256 is approved; a loopback fixture supplies 42.5 °C to the ordinary
  device API. Editing the profile requires approval again and produces no reads.
  The fixture constructs the approval explicitly, modelling human approval; it
  does not exercise or implement a human-facing import/approval screen.
- `scripts/test-android-diagnostics.sh`: disposable emulator gate and only these
  two instrumented tests. No physical device target is permitted by this script.

## Smallest file-profile import increment

The native engine already loads file profiles. Android currently has no profile
check/trust JNI method and no import UI. A subsequent increment can use the Storage
Access Framework to copy a bounded UTF-8 TOML document into staging, call the same
Rust compiler/validator, display origin/hash and every requested host/read/write,
and require explicit approval before committing immutable profile bytes, trust
record and driver configuration under `EngineHost.maintain` with the engine stopped.
Do not auto-approve on import. Reject changed bytes, secrets and writes in the first
read-only iteration; isolate rollback to that staged configuration. An Android
`content://` URI must be copied into private storage, not passed as a Rust path.
The same validation must be used for check and activation, avoiding a second parser.

Start with a local synthetic temperature endpoint, not a household device. HTTP
GET is not a universal safety guarantee: existing Moli path restrictions and a
reviewed endpoint remain necessary. Show both the live value and driver status so
persisted state cannot masquerade as fresh data after approval is withdrawn.

A future catalogue can version profiles with origin, license, SHA-256, supported
engine version, explicit review and rollback. A hash identifies bytes, not a trusted
author. HA/HACS Python components depend on the Home Assistant runtime and cannot
be loaded as Moli TOML; protocol adaptation requires license review and either a
profile or a native Rust driver. Native-driver changes require a new APK. Follow
upstream changes by comparison on a work branch, never automatic merging.

Backup-password minimums remain unchanged. A later masked, session-only random
passphrase helper could reduce test typing, with deliberate user recording and
memory cleared on exit; no fixed shared test password or plaintext persistence.

Android references consulted: [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby),
[document picker / SAF](https://developer.android.com/training/data-storage/shared/documents-files).
