# Upstream synchronisation — step 2

Pinned official source remains `rudy-io/moli-os@ea3db0a`. Step 1's exact inventory
in [`upstream-sync-step1.json`](../upstream-sync-step1.json) is retained as history.
This step connects functional groups without a global upstream merge/rebase.

- **Native packages:** Shelly, Solarman/Deye and IzyPower are compiled, registered
  in the shared Android/Linux factory and exposed by the existing catalogue/inventory.
  They can be configured with their official driver options and encrypted secrets;
  no instance is created or enabled by this update. Package fixtures/docs are included.
- **Energy:** solar/export meters, flows and period summaries, general energy points,
  and the configured load controller are connected to the existing driver lifecycle.
  The solar page now receives the real energy schema. Loads remain absent unless
  explicitly configured; they use the original hub, guard and journal.
- **Opening/Access guards:** official opener classification, Zigbee lock semantics,
  value-aware routine checks and Access owner checks are connected together. Fork
  command permits, freshness diagnostics, embedded client gate and device-link routes
  are preserved. Targeted code37 ON/OFF/unavailable/stop coverage passes after this change.
- **Widgets:** official LightTile/Slider and RoomCard, empty climate/pool sections,
  light recognition and PIN-error wording are integrated with both translation sets.
  The automation Inspector/Editor layout is unchanged.

## Explicit reports and retained adaptations

The new house-model/provider assistant remains **unconnected as a complete group**.
Its `house_tools.rs::plan/schedule` uses a separate queue of `tokio::spawn`/sleep
commands rather than the original automation engine. Activating it would introduce
another scheduler, which this mission excludes. Its contract/tools/provider source
copies are not available features. The four assistant updates deferred in step 1
remain deferred, along with their startup, CLI, provider examples and UI privacy
wiring. Existing assistant configuration, API keys, Claude compatibility, standard
automation drafting and stale rules are retained. Only the energy enum/summary
compatibility fields and corresponding role translations enter the current assistant.
Moving that new planner onto native automations would be a further design change;
it is not improvised here.

The remaining fork adaptations have concrete roles: Android's shared library/CLI
split and authenticated embedded lifecycle; Netatmo/OAuth, Meross scoped commands and
read diagnostics; stronger stale-timestamp rejection; catalogue inventory and native
rule routes; fork documentation/translation additions; both third-party notices;
pinned CI/signing workflows, security dependency patches and their lockfile versions.
They are retained rather than replaced with the monolithic upstream executable or
older dependency/security policy. Existing JSON keys are preserved while official
changes are reconciled key by key. The lockfile adds only the three new local crates
and their dependency edges, without replacing resolved registry versions.

## Validation and candidate

Development checks cover the 23 new native-driver fixture tests, catalogue registration,
the opening/Access changes, scoped rule compatibility, and seven new energy cases.
Web build/i18n and compilation/lints cover the modified compiled components. No general
local Rust/JVM/Android suite or hardware command is run. Code37/code38 evidence remains
the baseline for unchanged flows. One candidate, 0.27.0-beta.1/code39, uses the existing
mandatory Android preservation/signing pipeline and unchanged package/certificate.
Solarman's MIT notice is included in the APK with the existing notices.

---

# Historical upstream synchronisation — step 1

Reference: official `rudy-io/moli-os` commit `ea3db0a379bbee0a0a3571e9f9c231248e5ed79b`,
fork starting at `3d15431848920cedc604e44a298ee87b4aa68996`, common ancestor
`2cc3b97b5a19f2842d3eeb7bccbb1c4c3f21fc88`. No upstream merge or rebase.
The exact three-tree inventory and per-file outcome are in
[`upstream-sync-step1.json`](../upstream-sync-step1.json).

| Official input | Imported | Deferred |
|---|---|---|
| 47 new files | 14 exact copies | 33 files belonging to the three new native driver/catalogue packages |
| 36 upstream-only changes | 19 exact copies, 2 partial updates | 15 files with dependencies on step 2 |
| 33 files changed on both sides | None | All 33 preserved byte for byte |
| 27 fork-only changes | None | All 27 preserved byte for byte |

## Available in this candidate

- Zigbee contact sensors: true means closed, without reversing the wire value.
- Zigbee brightness: 0–100% in Moli, converted to/from the device's native range;
  devices already reporting percent stay unchanged. Upstream contact/brightness code
  and its tests are reused. The lock-specific branch and its test are deferred together.
- Rooms page: a wide card no longer expands its grid column beyond the viewport.
- Living room: Ambilight is offered only when the television actually has that point.
- Websocket transport: official plain TCP support alongside existing pinned/public TLS.
  No new driver connects to it in this candidate.
- Assistant UI: display a refusal message when the backend provides one; existing
  backend behaviour is unchanged. Official icons and energy/server translations are copied.
- Weather cache invalidation is compiled and tested; no new refresh route is connected.
- Two official floating-point test assertion corrections, Docker context inclusion and
  the third-party licence address exception are copied exactly.

## Copied sources are not enabled features

The new assistant contract, house model/tools, providers and privacy tiers remain
unreferenced modules: enabling them needs the divergent assistant `lib.rs`, `auto.rs`,
translations and related wiring. The original assistant is retained. Contract fixture
files are copied unchanged. No external model call is made to validate them.

Energy's new `load.rs` is unreferenced. SolarReal is compiled through the official
Solaire page update, but only rendered for solar meters; the existing energy schema
cannot configure those meters yet. The energy documentation explicitly marks its
solar/load sections as upstream features awaiting connection. Slider is copied but
not connected to LightTile, whose new text depends on divergent `commun.json` files.
`Semantic::Lock` is only a vocabulary addition; it does not activate the new lock guard.
The official image workflow is copied unchanged, dormant on this working branch;
no image is published by this task.

## Concrete dependencies deferred to step 2

- **Shelly, Solarman, IzyPower (33 new files):** `crates/*` automatically adds new
  crate directories to the Cargo workspace. Their manifests require a changed
  `Cargo.lock`, which is reserved for step 2; copying only their source directories
  fails Cargo metadata because each workspace member must have a manifest. Their
  catalogue packages also require the corresponding driver kinds in divergent
  `moli-os/src/main.rs`; the current catalogue rejects unbuilt native kinds. Defer
  each complete package, rather than advertise an unavailable integration.
- **Assistant (Cargo.toml, house.rs, llm.rs, settings.rs):** new weather dependency
  changes the reserved lockfile; static inventory refers to an undeclared `contract`
  module; endpoint/provider settings need the new `key_name` method and provider
  selection in divergent `lib.rs`. New assistant translations are also divergent.
- **Energy (config.rs, lib.rs, meter.rs, points.rs, report.rs):** new `loads`,
  `flows` and period fields require changes to existing struct literals in divergent
  assistant `lib.rs`; starting loads and accepting the new solar drivers needs the
  divergent executable configuration/startup. Keep the existing energy API coherent.
- **Access and opening guard (people.rs, automations.rs, runtime hub tests,
  SECURITY.md, lock portion of z2m catalog.rs):** `would_hold` gains a value argument
  and opener classification is exported by divergent runtime files. Access state
  must be supplied by divergent API `lib.rs`. Do not publish the new safety promise
  without its guard, or copy tests for behaviour that is not present.
- **LightTile and RoomCard:** new visible text is in the divergent French/English
  `commun.json` catalogues. Keep the current translated widgets until reconciliation.

## Validation and delivery

Development checks cover the changed Zigbee catalogue, the new weather invalidation
method, compilation/lints of affected compiled Rust components, and web build/i18n.
No general local Rust/JVM/Android suite, hardware command or repeated code37 scenario.
The unchanged mandatory beta workflows validate any distributed APK. Android package,
signing, accounts/configuration, native Netatmo→Meross rule and general TXT journal
are retained; code37's earlier evidence remains the baseline for those flows.
