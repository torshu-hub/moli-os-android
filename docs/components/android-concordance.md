# Android configuration concordance

The original Rust engine and dashboard remain the functional authority. Android
reconciles its adapter records with effective `moli.toml` and `home.json` while
the engine is stopped. This is development code on the existing draft PR; no
APK, tablet migration, physical command or signing change is implied.

## Ownership and precedence

| Data | Rule |
| --- | --- |
| Moli server/guard/energy/automation/fixtures/assistant and non-Android drivers | Retain settings, original TOML types and ordering. Embedded Android listening/storage overrides still apply at runtime. |
| Reserved Android instances | Three-way comparison: previous Android proposal, current Moli configuration and next Android proposal. Moli-only edits survive; compatible Android edits apply; incompatible edits block. |
| Meross | UUID and internal instance ID are bindings. Name, room, address and list order are not identity. Duplicate/rebound UUIDs are refused. Manual entries without UUID remain without UUID. |
| `home.json` | Separate JSON authority for rooms, metadata, favorites, custom fields and existing `outdoor.weather`. Only the adapter projection is merged. Arrays are units, never matched by name/position for device identity. |
| Approvals, trust, vault, history, Keystore/PIN | No deletion, implicit rekey or approval regeneration. Built-in tablet preparation may add its immutable file/hash approval, retaining existing approvals. Existing explicit key-setting operations remain separate vault operations. |

## Removal means retained inactive configuration

The existing remove/uncheck controls now revoke **reads**, not identities or data.
Printer and gateway records, and removed Meross rows, are retained with
`enabled=false`. Profiles/demo move from the active selection into `retained`
metadata; `inactive` records the explicit user action. Their driver instances stay
in `moli.toml`, disabled, with original options, IDs and approvals. Re-enabling
uses the same instance and stored key. Moli removal of a previously managed entry
likewise retains an inactive record. No irreversible purge is performed.

An absent preference/file alone is still not permission to delete or reconstruct
an instance. Missing/invalid activation consent is refused. Without provenance,
an enabled legacy instance opposite to Android's selection still blocks. Legacy
omitted TOML activation means true, consistently with the engine and v1 records.
The inventory reports retained instances as configured/disabled; the engine does
not register their equipment drivers. No socket on/off command is sent.

## Effective values in the existing forms

A successful preparation projects supported Moli fields back into Android's
selection and equipment stores in the **same transaction** as configuration,
home and provenance. This includes equipment names/destinations/activation and
weather coordinates/location. Unknown equipment metadata is retained by internal
ID, including after a reorder. Approved profile metadata is preserved.

Before editing with the engine stopped, Home and Equipment synchronize through
the process maintenance queue. A Moli-only disabled instance therefore appears
unchecked, and checking it again expresses a new activation request. The new
proposal baseline reflects these projected form values, allowing the next form
edit without treating the Moli edit as an unresolved concurrent change.

The forms compare a bounded digest of configuration/home/provenance/preferences
before saving. A change since loading rejects the save before altering those
files. Home rooms and weather selection are committed together. Equipment saved
while another surface changes configuration must be reloaded. Equipment opened
while running reloads after stop; Home asks to stop and reopen for an editable
snapshot. Neither opens a second configuration UI.

Manual settings that the form cannot faithfully represent (for example a custom
profile path or unsupported transport options) remain in Moli. Ordinary engine
preparation preserves them; strict form preparation blocks editing and displays
the localized preservation message. It does not guess a replacement. Opaque
options outside the form stay in the effective configuration and are not
silently reset by editing an unrelated field.

## Restored paths and portable records

On restore into a different app/UID/data generation, only recognized Android
paths are relocated: exact hashed copies under `android-profiles/<sha256>.toml`
and `android-demo.json` for their reserved instances. A single consistent previous
root and matching generated provenance/profile digest are required. Pre-baseline
legacy adoption additionally requires the otherwise exact generated entries.
Active selected bytes are checked at the new location before preparing them.
Inactive profiles can follow the proven root without loading or approving their
bytes; reactivation checks the selected bytes again.

Mixed old roots, traversal/noncanonical paths, unapproved digests or a customized
import/tablet path block instead of being overwritten. A newly approved profile
may replace the previous generated path when the baseline proves that path. Manual/external paths and
machine settings are never rewritten globally. Trust is by approved content hash;
historical approval paths remain documentary and the trust file is retained.
The exact initial configuration texts in `android-concordance.legacy.json` are
never rebased/refreshed. Android-to-Linux recovery still requires the future host
to choose its own storage/listening paths; no Pi execution is claimed.

## Transactions, migration and interruption

`android-concordance.json` remains version 1. It records the generated proposal
and adapter-home projection; the baseline from earlier increments is accepted.
First safe adoption saves exact original TOML/home text in
`android-concordance.legacy.json`. Ambiguous legacy entries, invalid configuration,
or missing configuration/home beside established provenance block startup.
A developer-assisted stopped-engine return to those original texts is possible;
removing a baseline alone is not conflict resolution or a safe APK downgrade.

The stopped-engine undo journal now also covers selection, previous selection
and equipment preferences, alongside TOML, home, provenance and the demo fixture.
Inputs/old bytes are bounded and checked before journaling. A synced undo journal
precedes synced file replacements/atomic renames and directory sync. Removing the
journal and syncing the directory is the commit point. Unchanged bytes are not
rewritten. This provides stopped-engine consistency, not hot reloading.

Exceptions attempt rollback. If storage remains exhausted, the journal stays;
the next data access/export must recover the complete old state or stop.
Recovery validates all entries and old/new preconditions before writing. External
edits or a corrupt journal block recovery. Partial `.prepare-new` files are never
active. Recovery itself can be interrupted and retried. This is process-loss and
fault-injection coverage, not hardware power-loss or S5e endurance qualification.
No key, raw configuration or arbitrary error payload is written to debug reports.

## Focused evidence and future beta

- Rust fixtures cover legacy/v1 adoption, IDs/UUIDs, disable/re-enable, concurrent
  edits, reverse projection and subsequent form edits, logical removals, approved
  profile replacement, active/inactive path relocation and manual-path refusal.
- JVM failures cover every journal/commit boundary including the newly included
  preferences, disk exhaustion, corruption and UTF-8/bounds. Metadata tests retain
  unknown fields by internal ID rather than row position.
- The existing encrypted install-r continuity proof also runs real Java/JNI
  preparation, logical removal/re-enable, stale-snapshot rejection and encrypted
  recovery into another generation, preserving vault/keys/SQLite/approvals.
- A fresh isolated preview exercises actual Home/Equipment activities: Moli
  values appear, edits from those values apply, an intervening change blocks save,
  and an unsupported manual profile leaves the form read-only. No equipment
  driver is started; only loopback HTTP is used for the disabled inventory proof.
- The existing focused workflow builds one emulator JNI ABI, reuses strict caches,
  and retains reports only. No ARM64 delivery candidate, release or distribution
  signing is part of these checks. Prior S5e/inventory proofs are reused.

Before a future tablet beta: require these checks on the delivery revision, select
one ARM64 candidate under the existing policy and verify the authorized update
identity/certificate/version. Only then: stop the engine, install over 0.17 without
uninstall/clear, check stable device bindings/rooms/settings, and restart twice.
A preservation/read-only message calls for reviewed resolution, not a reset.
No tablet action or intermediate APK publication is requested by this increment.

## Beta 0.18.0 candidate (code 23)

The delivery candidate uses the existing `io.moli.android.app.beta` identity and
beta certificate. The release circuit verifies its unsigned artifact and evidence
before signing those same bytes. No device migration or endurance result is implied.

S5e check: stop the engine, install the verified signed APK over 0.17 without
uninstalling or clearing data, then reopen/start. Check the same equipment
associations, room/settings and integration inventory; restart the engine twice
and check them again. A configuration conflict must preserve the installation:
record its message and stop rather than resetting or recreating devices.
