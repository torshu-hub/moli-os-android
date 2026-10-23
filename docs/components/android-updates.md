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
   in your own Google account, free CPU runtime. Run creation, enter the password
   in masked prompts and download `moli-beta-signature.p12`. Attach it to the same
   NordPass entry and record the displayed **public** certificate fingerprint.
   Reopen NordPass, **download its attachment again**, then run the notebook's
   verification cell on that downloaded file. This checks that the saved copy and
   password actually work before preparing a GitHub credential. Existing owners
   can use verification alone; it never regenerates their key.
3. **GitHub activation, guided separately:** copy the notebook's opaque credential
   only into environment secret `BETA_SIGNING_BUNDLE`, not a repository secret.
   Environment: `android-beta-signing`, selected deployment **branch `main` only**,
   no tags. Keep updates to that trusted branch owner-controlled. No extra reviewer
   chain is needed: the workflow is manual, owner-only and main-only. The reviewed
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

### One manual workflow, no signing key in a build

`.github/workflows/android-beta-sign.yml` is self-contained so activating it does
not require merging the application PR. GitHub requires this manual workflow on
the default branch ([official documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)).
It is **prepared in the draft branch, not activated on main**.

The owner selects an immutable source commit that already passed Android, audit,
check and hygiene CI; the workflow refuses missing/failed latest results and forks.
The first source is the already-validated `fde277b…`, version 17; subsequent runs
need the previous signed source SHA and strictly increasing versionCode. These are
public references provided with development reports, not secrets.

Three automatic jobs perform one owner-requested operation:

- **Build:** no signing environment or secret. Reuse exact-match JNI cache;
  build the unsigned ARM64 beta and disposable x86_64 fixtures. No repeated
  12-journey integration suite, Rust audit or archive tests for the already-green source.
- **Sign:** fresh runner, environment restricted to main, no checkout or project
  build/dependency scripts. Trusted inline code, SDK 35.0.0 and one step-scoped
  credential decode a private temporary `.p12`, sign and verify the public
  fingerprint. Private material is removed; nothing is cached or uploaded.
- **Verify/deliver:** another runner without secrets reuses the existing six
  migration/update fixture invocations **with the actual reusable certificate**.
  Only after success is the final ARM64 ZIP offered with its checksum, certificate
  fingerprint and short notice. Debuggable emulator APKs are not the user APK.
  Intermediate APK-only artifacts expire in one day; final APK/proof in 14 days.

The single bundle requires explicit owner recovery confirmation. Until the owner
has personally verified their NordPass copy, status is **ATTENTE COPIE UTILISATEUR**:
no real bundle configured, no reusable key generated here, no signed beta delivered.
The earlier A→B emulator proof remains valid and is not rerun now; the actual-key
proof runs only after personal setup. Local signing tests use disposable synthetic
credentials and are not evidence that the owner key or NordPass setup exists.

Only public standard GitHub runners are used, not paid/larger runners. Artifact
storage still shares the account allowance ([GitHub billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions));
retain a zero-spend/blocked paid-usage policy and verify included storage before
activation. No paid service or account spending setting is enabled by this work.
Keep the environment and trusted main settings real in GitHub; text in this file
is not a substitute for them. The assistant cannot access NordPass or read back
an encrypted GitHub environment secret. No key rotation, HSM or enterprise vault
is required for these single-owner beta trials.

## Android background limitations

No wake lock, global battery exemption, boot receiver or unattended restart is
introduced. A notification does not prove continuous engine activity while asleep.
Samsung prolonged sleep still needs owner-observed evidence when convenient;
this update test does not repeat or replace it. No physical commands or Pi work.
