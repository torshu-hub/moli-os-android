# Android beta identity, updates and recovery

## Prepared identity; distribution is not enabled

`android/beta.properties` is the single source of beta metadata:
`io.moli.android.app.beta`, label **Moli OS Bêta / Moli OS Beta**, initially
`0.14.0-beta.1`, version code **17**. The identity and label never contain a version.
`:app:assembleBeta` produces an **unsigned, non-debuggable ARM64 APK**, Android
9/API 28 minimum and API 35 target, with the existing permissions. Debug overrides
cannot change beta output metadata. Production `io.moli.android.app` and historical
preview IDs remain separate; no existing application is renamed or uninstalled.

The beta has deliberately **no signing configuration or debug fallback**. It is
not yet an installable distribution. No new temporary preview is delivered.
`check-android-beta.py` validates fixed identity, metadata, permissions and ABI;
comparison with a previous reference rejects decreasing codes or a new name with
an unchanged code. Before any future distribution, run its `--delivery
--previous-ref <previous-distributed-commit>` guard: every delivered beta must have
a strictly greater code. Rebuilding an unchanged commit for verification is allowed.

## Emulator proof

`scripts/test-android-beta-update.sh` refuses physical devices and existing test
installations. Its two beta APKs use the same ephemeral **Gradle debug signer in
one disposable job**, package `.beta` and test-only labels/version names. The
`.beta` debug identity requires explicit `moliUpdateFixture=true`; production beta
remains unsigned. Neither test APK nor its private signing key is uploaded,
cached as signing material, or offered for installation. These are fixtures,
not a durable distribution key or a promise of cross-job updates.

The test creates a fresh 0.13 preview with format-2 random credentials, a synthetic
encrypted account/session/inventory of four plugs and local keys, saved equipment,
configuration, SQLite and recordings. Only an authenticated encrypted recovery
archive crosses to a fresh beta UID. The untouched Rust reader checks the synthetic
vault; fixture encryption is confined to androidTest code, with no production test
hook or actual cloud/device operation.

It installs beta A (17), restores before a fresh key is created, then installs B
(18) **using `adb install -r`, without uninstall, clearing, export or re-login**.
Assertions compare UID, signing certificate, every data-file hash, wrapped-key
bytes, unwrapped master/bootstrap hashes, data-generation selection and preferences;
Rust reopens the account/inventory and confirms all four local keys remain present.
An ordinary downgrade and a differently signed APK must be refused; data is
checked again afterwards. A separate legacy numeric-PIN archive is also restored
in an independent UID. Both source formats use synthetic data; this does not
claim a physical tablet update or actual Meross refresh. Successful fixtures are
removed only after all checks on that disposable emulator, never between A and B.

The existing home/navigation/read-only integration suite follows in the same
emulator job. Only redacted reports, public metadata/checksums and synthetic
screenshots are retained. No runtime archive, keystore or signed test APK is a CI
artifact. The JNI cache remains keyed solely by unchanged native inputs.

## One-time migration, then normal updates

**Initial beta installation (after stable signing approval):** stop the old engine,
export an encrypted backup if its data is useful, keep its password separately.
Install Moli OS Bêta alongside the preview. Before its first Search/My devices,
use Settings → Backup/transfer to restore. The old app and its only useful data
must remain until recovery is verified. No PIN is requested for a fresh format-2
installation; legacy numeric credentials remain supported. Do not run both engines.

**Subsequent beta update:** stop Moli, retain a current encrypted backup as a
precaution, install the newer signed APK over **Moli OS Bêta**, then reopen it.
An ordinary update retains private files and Android Keystore; it needs no archive
restore, new PIN or Meross login. Engine restart stays voluntary. Android Settings
can authorize the browser/file manager to install an APK; no Play account or PC is
needed for ordinary installation. There is no in-app automatic installer.

**Signature refusal:** stop; do not uninstall, clear data or force an install.
Check package/version and the published certificate fingerprint. **Data-format
incompatibility:** preserve the existing installation and backup, report the exact
error; require a tested migration before shipment. APK downgrade is not data rollback.
No schema change is introduced here. Existing archives, guarded restore, old
credentials and Android automatic-backup disabling are unchanged.

## Future stable signing: explicit owner approval still required

Recommended custody: **owner-held encrypted PKCS12/keystore backup**, password in
a separate password manager, and a controlled CI copy for repeatable signing.
Choose one RSA 3072-bit signing key with a long validity, keep the public certificate
fingerprint pinned and publish it with future APK checksums. This key is entirely
separate from the Moli master key and Android Keystore wrapping key. Its loss can
prevent in-place updates; an encrypted Moli backup cannot replace a signing key.

No PC is required in principle for the one-time setup: an owner-controlled browser
workspace (for example a private GitHub Codespace **only if included quota is
available, paid usage disabled and its use separately approved**) can generate it
outside Git and let the owner download the password-protected recovery file
privately. An existing trusted personal computer is an alternative. **Do not
create such a workspace/key now.** No key, password, base64 copy or private recovery
file may enter Git, CI artifacts/cache/logs, screenshots, PRs or this chat. Private
browser downloads are for the owner's custody, not build artifacts. Confirm that
the recovery copy is actually held before enabling distribution.

For later CI signing, the owner would create an `android-beta-signing` GitHub
Environment on the public code repo, limited to the protected trusted branch and
requiring owner review. Store keystore and passwords only as environment secrets;
use the GitHub web interface, never paste them into this conversation. A separate,
reviewed `workflow_dispatch` signing job must accept only an approved immutable
commit and a verified unsigned APK. No `pull_request` or `pull_request_target` job
may use the environment. The signing job must not run untrusted repository/build
scripts after secrets become available; use pinned SDK signing tools, least token
permissions, a private temporary directory and cleanup, no signing-key cache.
Only the signed APK, public certificate and checksums may be retained/published.
A sole owner must be able to approve their own manually dispatched environment
review; do not enable a self-review ban that makes that arrangement impossible.
Branch/environment controls are real GitHub settings, not substitutes in a README.

This requires explicit decisions on key creation/custody, the owner recovery copy,
CI secret setup and the trusted signing workflow/branch. No environment, secret,
key, paid workspace, public release or merge is configured by this preparation.
The eventual first signed build must be checked against the pinned fingerprint
and its next signed update tested before inviting users to rely on continuity.

## Android background limitations

No wake lock, global battery exemption, boot receiver or unattended restart is
introduced. A notification does not prove continuous engine activity while asleep.
Samsung prolonged sleep still needs owner-observed evidence when convenient;
this update test does not repeat or replace it. No physical commands or Pi work.
