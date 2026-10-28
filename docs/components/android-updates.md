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

## Reusable beta signing — owner backup first

The chosen scope is a **single-user test/beta key**, not an official production
release key. Keep one password-protected PKCS12 file, alias `moli-beta`, RSA 3072,
SHA-256 certificate, 10,000-day validity. One NordPass entry may hold both its
32-character random password and its file attachment. Never regenerate it for a
new version. Losing both copies prevents ordinary updates; the Moli data backup
cannot replace the signing key. A later production identity remains a separate decision.

### Three owner actions, phone/tablet only

1. **NordPass:** create “Moli OS — Signature bêta”, generate/save a random 32-character
   ASCII password. If this entry already holds a `.p12`, skip new-key creation.
2. **Private Colab session:** open the [prepared notebook](../../tools/android-signing/moli-beta-key.ipynb)
   in your own Google account, free CPU runtime. Run **0 — Initialize** (definitions
   only, no key creation), then **1 — Create once**, enter the password
   in masked prompts and download `moli-beta-signature.p12`. Attach it to the same
   NordPass entry and record the displayed **public** certificate fingerprint.
   Reopen NordPass, **download its attachment again**, then run the notebook's
   verification cell on that downloaded file. This checks that the saved copy and
   password actually work before preparing a GitHub credential. Existing owners
   start a fresh session with **0 — Initialize**, then **2 — Verify**, skipping
   creation entirely. Verification always asks for the public fingerprint saved
   in NordPass; it never regenerates their key or trusts a replacement fingerprint.
3. **GitHub activation, guided separately:** copy the notebook's opaque credential
   only into environment secret `BETA_SIGNING_BUNDLE`, not a repository secret.
   Environment: `android-beta-signing`, selected deployment **branch `main` only**,
   no tags. Keep updates to that trusted branch owner-controlled. No extra reviewer
   chain is needed. Secret access stays main-only; the currently active workflow is manual. The reviewed
   single workflow file must first be placed on default `main` by an explicitly
   authorized owner action; **this preparation does not change main or merge PR #7**.

The Colab link must use the reviewed immutable Git commit, not a moving branch.
The notebook is a small Python utility in a Google VM private to the owner's
account, **not computation exclusively on the phone**. It uses `cryptography`,
does not mount Drive or access NordPass/GitHub accounts, and prints only public
fingerprints. No private key is created in Codex. The encrypted file downloads
privately to the owner; it is never a GitHub build artifact. The verification
cell attests personal NordPass retrieval, validates the same certificate/password,
then offers a copy button for one credential bundle. That bundle includes the
password: paste it only into the GitHub secret, replace the clipboard afterwards,
and close/delete the private Colab runtime. Do not save notebook outputs to GitHub
or share that session. Outputs are excluded from saved copies by notebook metadata.
No paid Colab plan, GPU, Codespace, Play account, PC or new signing app is needed;
free Colab availability is not guaranteed. If its crypto library is unavailable,
stop before entering any password and ask for guidance rather than using another key.

NordPass Android [official attachment instructions](https://support.nordpass.com/hc/en-us/articles/43785604725137-Attach-files-to-NordPass-items-on-Mobile)
confirm attach/download support for Premium. [Colab's FAQ](https://research.google.com/colaboratory/faq.html)
describes its free tier and private account runtimes. The mobile browser journey
has not been physically tested here. Do not send a `.p12`, bundle, password or
screenshot containing them to chat, issue, PR, Git, CI logs or public artifacts.

### Prepared automatic delivery, without keys in a build

**Trigger correction is pending.** The main-only signer is installed and manual
fallback deliveries work. Its automatic subscription used a different name from
the registered candidate workflow. The generated `.github/workflows/android-beta-sign.yml`
on this correction branch is the self-contained, reviewable deployment diff. No key, environment, ruleset or main
change is performed by this preparation. The existing certificate is pinned;
never recreate the key.

A release milestone is a trusted owner-originated push to `android/beta-ready`,
not every development commit. Promote only the finished work commit, after the
previous delivery has finished. Use the existing authenticated owner GitHub App
or owner GitHub session: a push made by `GITHUB_TOKEN` does not trigger the next
workflow. Work continues on `android/profile-import`, with PR #7 kept in draft.

1. **Secret-free candidate:** cumulative changes since the last delivered source
   determine tests. Web/UI changes run relevant JS/i18n/navigation checks; driver
   changes test their paths; storage, JNI, dependencies, Gradle or unknown inputs
   keep the preservation/full Android checks. Rust checks and audit run independently,
   once. Ordinary PR edits only run applicable lightweight checks; heavy APK/emulator
   jobs run at a ready milestone. Update tests use disposable emulator certificates.
2. **Build once:** ARM64 JNI caches include exact source, embedded dashboard and
   Rust/NDK/API versions, without broad fallback keys. x86_64 has a separate cache
   and is built only when emulator tests apply. The unsigned ARM64 APK is assembled
   once. The immutable artifact contains APK SHA-256, source/run/attempt, toolchains,
   runtime-input fingerprint, metadata, test plan and selected instrumentation logs.
3. **Main-only gate:** `workflow_run` receives the successful ready run. Trusted
   inline code independently verifies repository, owner actor, branch, immutable
   commit, latest successful checks, approved CI-script hashes, v4 artifact digest,
   exact evidence and APK digest. It never executes candidate code. A fork, changed
   policy, incomplete/failed evidence or stale milestone is refused before the key
   is available. Missing caches are never treated as test results.
4. **Isolated signer:** separate main-only environment, no checkout, Gradle, app
   scripts or emulator. Sign exactly the validated bytes with the existing key,
   verify package/ABI/permissions, alignment and pinned certificate. Private material
   is temporary, removed and never uploaded or cached. No dependency installation
   occurs in the secret-bearing step.
5. **Verified delivery:** a separate job, without the key, independently checks SDK
   signature, package, version and hashes, then publishes a GitHub prerelease with
   the ARM64 APK, checksum, public certificate and installation notice. This is the
   only job requesting `contents: write`. It atomically reserves a monotonically
   increasing `moli-beta-code-N` tag, stages assets in a draft and publishes only a
   complete release. The JSON release ledger binds versionCode to source and hash.
   The initial ledger is the already signed code 17; same/lower codes are rejected.

**Single activation decision:** approve installing only the corrected protected
workflow on main, with its regenerated pipeline pins and existing permissions.
Publication remains in the isolated job; no new write permission is requested. Keep `android-beta-signing` restricted to **main only**,
its existing secret and all current protections. No application PR merge, environment
expansion, new secret or repeated per-version dispatch is required. Installing the corrected file does not process the already delivered milestone;
the next successful genuine ready workflow triggers it automatically. Manual dispatch remains only an owner
fallback, without SHA fields. If environment required reviewers are enabled later,
their approval remains a real GitHub gate and is not bypassed.

GitHub requires the `workflow_run` handler on the default branch. It grants access
to privileged tokens even after unprivileged runs, so no downloaded source/scripts
are executed in the handler. Provenance uses authenticated GitHub APIs, immutable
v4 artifact digests and independent source/policy checks. This is not an artifact
attestation: no new OIDC or attestation-write permission is requested. Trust remains
in the owner-controlled main workflow and GitHub Actions infrastructure.

Delivery concurrency never cancels an existing signing operation. A conflicting
version, superseded milestone or interrupted draft publication fails closed. Do
not move/delete its reserved tag or overwrite a release automatically; inspect the
failed run before owner-approved recovery. CI policy/toolchain changes require a
reviewed protected-workflow update because their hashes are pinned; ordinary app
changes do not. No signing-key rotation or automatic app installation is introduced.

### Reusing the existing 0.14.1 proof

Android run `40025382394`, source `014ba7baa92865d51e0d56055737f7af4f392ff4`,
already passed six migration/preservation and twelve journey instrumentations.
For this pipeline-only change, independently compare all tracked runtime/build/test
inputs with that source, excluding only documented orchestration/docs paths. If
identical, the successful owner-originated baseline remains the validation proof;
no emulator/JVM/Rust repetition. A changed or unknown runtime input disables reuse.
The unsigned APK still needs to be retained once, since the old CI retained reports
rather than a delivery APK. Final signature/certificate/metadata verification always
runs. The prior actual-certificate update proof remains valid; repeat preservation
checks when storage, identity, JNI, migration or dependency changes warrant them.

Local validation commands (no owner credentials):

```
python3 tools/android-signing/check_static.py
python3 -m unittest discover -s tools/android-ci -p 'test_*.py' -v
python3 tools/android-signing/test_signing.py  # existing unsigned APK, disposable key only
sh -n tools/android-ci/build_candidate.sh scripts/test-android-integrations.sh
sh scripts/leak-check.sh
```

Only standard public GitHub runners are used. Artifacts expire after 1–14 days;
public prerelease assets persist. No paid service, larger runner or billing setting
is enabled. Runtime cache hits and runner scheduling affect latency; a 5–10 minute
small-update target is not a guarantee. Install the delivered APK **over** the same
beta, without uninstalling or clearing data; stop the engine first. Signature refusal
is a reason to stop, never to delete the installed application's data.

## Android background limitations

No wake lock, global battery exemption, boot receiver or unattended restart is
introduced. A notification does not prove continuous engine activity while asleep.
Samsung prolonged sleep still needs owner-observed evidence when convenient;
this update test does not repeat or replace it. No physical commands or Pi work.

## Prepared trigger-name correction after code 23

GitHub registers `.github/workflows/android-integrations.yml` (workflow 390860558)
as **Android profile import**. Candidate runs used **Android home and integrations**,
while the protected signer subscribed to that new name. Keep the registered name
as the single current candidate name in the candidate YAML, `workflow_run`
subscription, required source checks and latest-candidate lookup. Regenerate the
protected inline workflow and its SHA-256 pipeline pins from the reviewed sources;
changing only the subscription would leave provenance checks inconsistent.

This branch prepares that coordinated change; it does not activate it. One owner
approval is needed to install **only** the generated
`.github/workflows/android-beta-sign.yml` on protected `main`, with its matching
pipeline pins. Incorporate the companion pipeline files into the non-protected
application work branch before the next ready milestone. Preserve the existing
main-only signing environment, key, certificate and permissions.

Code 23 is already delivered: do not dispatch, rebuild, repromote or republish it.
The obsolete main-push bootstrap hook is removed so installing this correction
does not attempt to process the already delivered ready head. The only automatic
entry is completion of the candidate workflow; existing provenance and monotonic
version guards remain unchanged. Verify automatic event delivery on the next genuine milestone
with a higher code. Local fixtures validate the handler/provenance logic, not
GitHub's live event routing. Manual dispatch remains an exceptional fallback;
future successful owner-originated ready milestones should trigger signing and
publication without a recurring owner click.
