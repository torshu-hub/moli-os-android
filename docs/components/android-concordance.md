# Android configuration concordance — first increment

The Rust engine and original dashboard remain the functional authority. This
increment replaces unconditional Android regeneration during `ProfileStore.prepare`
with a validated, stopped-engine reconciliation. It is development code on the
existing draft PR; it does not migrate a tablet or publish a beta.

## Ownership and precedence

| Data | Rule |
| --- | --- |
| `moli.toml` server/guard/energy/automation/fixtures/assistant and non-Android drivers | Moli-owned, retained, including original TOML types on unchanged leaves. The embedded host still owns listening/storage at runtime. |
| Reserved Android driver instances | Three-way merge of last Android proposal, current Moli values, next Android proposal, recursively by field. Moli-only edits survive; Android-only edits apply; equal edits agree; incompatible edits block before writing. |
| `android-tablet` and synthetic `android-import` loopback port | Android lease, renewed at each preparation only for the same approved profile and loopback host. A changed profile/host blocks. |
| Meross identity | Hardware UUID and internal instance ID are bindings. Name, room, address and list order are not identity. Renames keep the same ID/UUID; duplicate UUIDs, rebinding or UUID loss are refused. Manual entries without UUID remain without UUID. |
| Missing Android selection | Does not authorize deleting an existing reserved instance. Preparation blocks and preserves it. Explicit removal/disable resolution is a remaining increment. |
| `home.json` | Remains JSON, separately owned by Moli. Rooms, metadata, favorites, custom Android keys and existing `outdoor.weather` remain. Only the adapter projection is reconciled; its device labels/enabled state derive from retained drivers. Arrays are atomic values, never matched by name or position. |
| Profile copies/approvals/trust, equipment/selection records, encrypted vault, history, Keystore/PIN | Retained. The new preparation transaction never rewrites them. Existing approval/settings operations retain their behavior; preparing the shipped tablet profile may add its immutable copy/trust entry, without removing previous approvals. |

A versioned, private `android-concordance.json` stores only the Android proposal
(driver settings and adapter-home projection), not the effective whole Moli
configuration. It contains no credentials. Subsequent preparations compare against
that proposal, so a retained Moli edit is not silently adopted as an Android edit.
There is no automatic reverse synchronization of Moli edits into Android forms yet.

## Existing installations and reversibility

Without a baseline, only exact legacy generated instances can be adopted, except
for the private loopback lease port. An absent generated instance may be added;
an existing unmatched/customized instance, inconsistent home projection, malformed
baseline or invalid configuration blocks startup. A missing/empty configuration or missing `home.json`
beside an established baseline also blocks. The files are preserved, not replaced
with a new empty installation.

On first successful adoption, `android-concordance.legacy.json` retains the exact
pre-adoption `moli.toml` and `home.json` text (null means originally absent). It is
never refreshed on later restarts. It is private configuration data, included in
the existing encrypted recovery archive, not an export/report or debug log.
This provides a developer-assisted route back to the original files with the
engine stopped; no automatic downgrade or destructive rollback UI is introduced.
Keep the original encrypted archive before a future physical upgrade. Removing a
baseline alone is not a conflict resolution and does not make an older APK safe.

## Multi-file writes and interruption

The transaction covers `moli.toml`, `home.json`, the baseline, first-adoption
snapshot and the demo fixture when needed. Every changed target is validated and
bounded before an undo journal is durably written. Each file is synced and
atomically renamed in the same directory, then the directory is synced. Removing
the journal, followed by directory sync, is the commit point. Unchanged files
are not rewritten. This is stopped-engine consistency, not live hot reloading.

On exception, rollback is attempted. If storage is exhausted, the durable journal
stays. The next data access or encrypted export must recover the complete old
state before continuing. A process crash before journal removal likewise rolls
back; after removal all replacements are complete. Recovery validates every
journal entry and all old/new preconditions before touching files. Unexpected
external edits or a malformed journal stop recovery. Partial `.prepare-new` files
are never active configurations. A crash during rollback can be retried.

The native home displays a localized preservation/blocking message; it does not
start drivers from a conflicted proposal. Raw configuration and errors are not
logged. Keys/secrets never enter the journal, baseline, or instrumentation output.
Process-interruption and disk-full injection are covered; hardware power-loss and
Galaxy Tab S5e migration/endurance are not claimed.

## Validation and future beta scenario

- Rust fixtures: four MSS310 + IPP + IGD, demo, approved weather/import/tablet,
  non-Android settings, protected-room metadata; legacy upgrade/restart/idempotence,
  Moli-only edits, conflict, rename/reorder, manual no-UUID, TOML type preservation,
  invalid input and duplicate UUID.
- JVM disk tests: interruption at every journal/replacement/commit boundary,
  repeated recovery, exhausted storage, corrupt journal, unrelated edits, bounds
  and invalid UTF-8; untouched synthetic approvals/vault/history bytes.
- Android `BetaUpdateTest` retains encrypted transfer, Keystore/UID/PIN, SQLite,
  same-signer install-r, wrong-signer/downgrade rejection and independent-UID
  recovery checks. After replacement it also exercises actual Java/JNI prepare,
  directory fsync, Moli edits, preserved approvals/selection/identity and conflict
  on an isolated synthetic context, without starting equipment drivers.
- The focused PR workflow builds only one emulator JNI ABI and test APKs, reuses
  strict caches and the existing update script, and retains reports only. It has
  read-only repository permissions, no signing secret, no ARM64 delivery candidate
  and no distribution/release step. The protected signer is unchanged.

Before any later beta delivery: require the targeted preservation CI to pass,
resolve removal/disable and restored absolute-path ambiguities if needed, and
select one delivery candidate under the existing release policy. A future owner
check is: stop engine → install the authorized same-certificate beta over 0.17
without uninstall/clear → verify four stable bindings, names, rooms and existing
settings → restart twice → inspect inventory. On a blocking message, preserve the
files and use the previous selection or reviewed resolution; do not reset data.
No tablet update or user action is requested for this development increment.
