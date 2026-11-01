# Netatmo Energy — portable driver and Android setup

`moli-netatmo` uses the ordinary Driver/Hub, encrypted Moli vault, guarded command
path, state cache, history and automations. It adds no heating regulation or
Netatmo→Meross rule. Android only owns the private account setup page.

## API contract and cadence

Official Energy OpenAPI **1.1.2**, retrieved 2026-10-31 from the public developer
portal (`POST /api/getdocumentationjson`, form `fileName=Energy`):

- GET `https://api.netatmo.com/api/homestatus`: every **30 seconds** by default.
- GET `/api/homesdata`: every **300 seconds** independently. Cached topology has
  its own `topology_collected_at`; its older server date cannot age a new status.
  A topology refresh failure retains an existing topology and permits status
  reads, except authorization/rate limits that apply to the account.
- Around **132 reads/hour/home**, excluding OAuth, commands and other clients.
  Quotas are shared with other clients of the account (documented 50/10 s and
  500/hour/user). HTTP 429 and both Retry-After forms pause reads and commands.
  Missed periods are skipped; no backlog or automatic command retry is sent.
- Code18 restores normal polling after commands: a successful write never adds
  an accelerated read. It retains the existing next poll (historical upper bound
  `now + poll_seconds`), with no burst or automatic command retry. Program changes
  mark cached topology for refresh at the next normal read.
- Temperatures, room setpoint/mode, home mode, selected schedule, module
  reachability/battery and `heating_power_request` retain their provider meaning.
  Only an actual boolean `boiler_status` on NATherm1/OTM supplies declared demand.
  A percentage or temperature difference is never converted into a boiler state.
- `collected_at` is API collection time, `server_at` is status response time,
  `measurement_at` remains unknown where the provider supplies no physical
  measurement date. A 30-second API cadence is no physical-latency guarantee.
  Missing, stale and failed readings are null/unavailable, never invented OFF.

```toml
[[driver]]
id = "energy"
kind = "netatmo"
enabled = false
[driver.options]
home_id = "home-test"
poll_seconds = 30
topology_seconds = 300
max_age_seconds = 300
```

The existing generic freshness bound is configurable and affects availability
only. No boiler timer, safety threshold or actuator policy belongs to this driver.

## Read failures and diagnostics

A request failure clears measured values and marks freshness/error separately from
physical reachability. It retains the last declared availability separately; measurement freshness
expiration still runs during long API quota/auth pauses.
An explicit `reachable=false` remains offline. The ordinary Moli journal records
read-error category/retry deadline, recovery and availability-quality transitions,
with no device/account identity or credentials. Successful polls are not logged.
Android TXT export retains only allowlisted diagnostic codes.

## Optional reachability and clock fields (code17)

The cached official Energy OpenAPI 1.1.2 schemas `RoomS`, `NATherm1_S`,
`OTM_S`, `NRV_S` and `ServerResponse` declare no required-field list for these
properties; missing fields must not be interpreted as an explicit false.

Read quality does not determine declared connectivity. Only an explicit
`reachable` boolean (including an explicitly unreachable bridge) changes the
Hub availability flag. An absent status/reachable field is unknown; an absent,
invalid or future `time_server` does not declare a device offline. Unconfirmed
measurements remain null. The existing Hub setter/event carries only a boolean:
on unknown replies the driver retains the last flag (or leaves initial `None`),
without asserting a new connection. Native driver-stop handling remains intact.
Freshness expiry invalidates measurements, not physical reachability. This is a
minimal last-declaration compromise, not a claim that the device is connected.

Separate allowlisted `reachability_cause`/`time_cause` metadata and journal codes
identify missing status, absent/explicit reachability and clock quality without
raw responses or identifiers. Repeated identical causes are not re-journaled;
normal confirmed reads restore measured state without reauthorization.

## Clock boundary (code18)

`time_server` uses seconds (Energy 1.1.2 string example `1553777827`); parsing
still accepts integer seconds too, with checked conversion to milliseconds.
A positive offset of at most **1000 ms**, one API timestamp unit, is accepted
with the explicit cause `timestamp_future_le_1s`, never relabeled as an ordinary
`timestamp_recent`. The raw `server_at` remains visible unchanged; only the
internal freshness-expiry reference is capped at collection time, so tolerated
skew cannot make freshness indefinite. This is a bounded clock-resolution
compromise, not evidence of the actual S5e offset or physical measurement time.

Greater offsets remain unconfirmed/null. Their anonymized cause gives only a
bounded category: (1,5] s, (5,30] s, (30,300] s or >300 s. No raw response,
absolute server timestamp or heating value is journaled. Missing/invalid clock
and missing element status keep their distinct causes; identical categories are
not logged on every poll. If the observed offset exceeds the bound, this release
provides the evidence to decide the next step instead of accepting it blindly.

## OAuth and durability

The selected Android route is an **experimental opt-in personal application**.
Netatmo normally requires a confidential server secret; an Android user-supplied
secret is not claimed to satisfy that requirement. A confidential service remains
an alternative design; no server or subscription is installed by this milestone.
There is no embedded registration, secret, account or migrated Beta1 token.

In Android Management → Integrations → Netatmo, configure the personal application
privately, accept the experiment and optionally request `write_thermostat` in
addition to the separately confirmed `read_thermostat`. Register the exact return
`http://localhost/moli-netatmo`, authorize in the official browser and paste the
complete return URL into the masked field. Redirect/state, single use and session
expiry are verified. List the authorized homes, then explicitly connect one;
this creates/updates only its canonical driver block. Other drivers, files,
identities, labels and the master key are retained. Credentials never enter TOML.
The running engine publishes all supported rooms/modules returned for that home.

Application credentials and the entire access/refresh/expiry/write-grant bundle
live under the instance namespace in `secrets.enc`, encrypted by the existing
master key protected by Android Keystore. The stopped-host and runtime paths use
the same `oauth::account::Account` renewal logic. Each path is serialized; Android
stops/joins the engine before provisioning or renewing through its setup page.
There is one refresh in flight, not a second Android token scheduler.

A durable fingerprint precedes a refresh. The new pair is saved together before
any read or command may use it. Vault replacement syncs the encrypted temporary
file and directory, and does not yield between replacement and memory update.
Storage failure retains the returned pair for persistence retry without another
provider refresh. A saved pair plus an old fingerprint recovers after restart.
DNS/connect/TLS failures, provider temporary errors and 429 retain authorization;
confirmed invalid_grant is distinguished from a sent request with an ambiguous
response. A genuinely ambiguous consumed refresh is never silently replayed.
The account page reloads saved state rather than mistaking lost UI memory for a
lost account. Ordinary restart/update does not require a new authorization.

## Guarded capabilities and commands

Only rooms linked by the official topology to NATherm1/OTM/NRV expose controls.
Read consent alone yields read-only points; writes also require the persisted
write grant at dispatch time. Every command goes through Hub validation and its
existing guard/deadline/journal. No command is sent on startup or account binding.

- `setpoint`: text JSON `{"temperature":21,"duration_minutes":90}`; explicit
  duration required, POST `/api/setroomthermpoint` with manual mode and endtime.
- `resume`: true, POST `/api/setroomthermpoint` with room mode `home`.
- `home_mode`: the supported enum `schedule`, `away`, `hg`, POST `/api/setthermmode`.
- `program`: an ID returned for this home, POST `/api/switchhomeschedule`.

The shared climate card reads point ranges, writable capabilities and enum
choices. A timed setpoint shows a duration field; it does not reuse heat-pump
power or cooling modes. Existing heat-pump controls retain their behavior. A
successful provider acknowledgement does not invent a new measured state; the
next status read confirms it. The climate form keeps its initialized or edited
draft independent of subsequent readings, including an empty draft; only an
explicit valid submission sends a setpoint. Unknown command outcome is not
automatically retried.

Sources: [Energy](https://dev.netatmo.com/apidocumentation/energy),
[OAuth](https://dev.netatmo.com/apidocumentation/oauth),
[guidelines](https://dev.netatmo.com/guideline). Existing Rust OAuth/model code is
ported from this fork at `1ef31980ca1d072ff3f6237d9b11e228be0779ed`; no HA Python
code, household automation, account data or upstream publication is imported.
The owner performs first real authorization, capability confirmation and long
term renewal observation. Synthetic HTTP/vault tests do not prove endurance on a
real Netatmo account or issue physical heating commands.
