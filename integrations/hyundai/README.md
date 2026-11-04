# Hyundai Europe CCI / myHyundai

Native portable Rust driver with both Hyundai Europe transports (legacy
CCAPI region 1, CCI/GSPA region 9), portable private account setup, ordinary Moli Hub
points and guarded human commands, vehicle card and persistent Ioniq 5 journal.
No Python runtime, HA server, separate telemetry database or GPS history.

The EU protocol is ported from `hyundai_kia_connect_api` v4.45.0,
`43343489d6183ba0ba2be03c3fc4eacd0ac2d536` (MIT). Earlier functional comparison
used `kia_uvo` v3.22.0, `d56b2bc41c9ecc810297ad16af12e9febdded66f` (MIT);
code24 removes that comparison from production. Protocol tables and notices remain.
Public cipher parameters and affine signing vectors are included with notices;
all account, status, journal and Android test fixtures are synthetic.

Both transports share CCI/OneApp authorization. The recommended private Hyundai
page supports provider terms/verification when requested, captures only the exact
HTTPS callback with a random state, and consumes the encrypted pending ticket
before code exchange. No generic EU OTP endpoint is invented: the pinned EU
classes inherit unsupported OTP methods. The direct password alternative
uses the IDP public RSA key; its password is not persisted. Safe diagnostics
distinguish authorize/redirect, RSA/signin, CCI/CCS, device/fleet, PIN, quota and
network/TLS phases, never remote URLs or response text. Identity requests have
a 35-second total bound, direct login a 120-second bound, without retries.
The public authorize chain also uses the exact legacy identity page
`https://prd.eu-ccapi.hyundai.com:8080/web/v1/user/authorize`. This unauthenticated
302 → 200 was reproduced on 2026-11-02 without account credentials; code22
incorrectly rejected this page before RSA signin. Code23 admits only this
additional HTTPS host/port/path (GET without body in the native client), with
the identity timeout. It does not allow arbitrary CCAPI pages or API redirects.
Every subsequent hop remains checked; cookies stay host-scoped and the OAuth
callback is never fetched. Android applies the same exact navigation exception
and clears this issuer's cookies on closing. No production account or vehicle
was accessed; real sign-in remains to qualify on the owner's device.
An authorized session is saved separately for explicit fleet-discovery recovery. The encrypted vault holds the full rotating token
bundle and optional vehicle PIN. An ambiguous rotation is blocked rather than
replayed; an interrupted CCS exchange resumes from the saved rotated bundle.
No password login retry loop. Only explicit fingerprint gate failures permit
the reference's legacy auth fallback. Credentials never enter ordinary points.

The default cadence reads stored cloud status every 15 minutes. Driving daily
history/diagnostics/software/OTA reads run every six hours. Force refresh is a
separate human-confirmed command with durable cooldown and a 12V guard. The
source distinguishes cache, vehicle and Moli timestamps and retains unknowns.
Vehicle power is kW, auxiliary instantaneous powers W, daily driving energy Wh,
and the provider's battery capacity/remain fields kJ; no SOC-to-kWh inference.
Raw provider fields accompany semantic aliases and source-unit metadata.

Controls: locks; climate with temperature/duration/defrost/steering/seats/rear
heating; charging start/stop, AC/DC limits/current, charge door; per-seat windows
and curtains; charge/departure/climate/off-peak schedules; V2L limit; charge
alarms; battery conditioning; lights/horn; valet; explicit force refresh.
The Hub guard applies and the driver additionally refuses every non-Ui origin,
including Automation/System. PIN verification happens before the final deadline
check. Never retry a remote POST or claim a physical outcome from its cloud ACK.
A person may explicitly query the cloud's latest action status.
There are no automatic mode fallbacks, password retries, OTP resends or real
vehicle calls during CI. Account setup exposes a real API choice and opens the
vehicle/manual-control options after successful discovery, without a redundant
read-only login checkbox. Commands and GPS still default off.

GSPA pre-CCS2 remote controls, navigation destination and new SVM capture are
not implemented by its reference transport and are visibly unavailable. Legacy
CCAPI uses certificate-verified TLS on the exact provider host/port 8080, with
its own app identity/stamp, registration, fleet/cache/history, PIN and typed
control routes. Pre-CCS2 lock/climate/charge and charge limits are implemented through
those distinct routes. Its normalized legacy fields preserve unknowns and
source-specific units/timezone; CCS2 uses the shared vehicle status schema.
Legacy navigation stages a private destination in RAM and sends only a one-use
token through the ordinary guarded Hub command; coordinates never enter
Hub state/history/export. Legacy CCS2 charge and departure-climate settings
are separate explicit actions, respecting the five-second Hub deadline rather
than queuing an unobserved second write. Features absent from the legacy
reference (SVM, charge alarms, battery conditioning) are unavailable there. Stored
SVM uses the newest media set, strips JPEG metadata, and renders the five views
with optional calibrated fisheye dewarp. GPS is opt-in, volatile and human-only;
private previews are not part of Hub persistence/history/exports. Android
protects private previews from screenshots and uses the system document picker
for voluntary non-geolocated journal export.

`history.db` retains typed daily summaries, charge boundaries, between-charge
intervals, weather aggregates, explicitly bound dedicated EV-meter deltas and
owner-entered financial evidence independently of the raw 30-day purge. Repeated
provider days replace totals instead of adding them. Counter resets, missing
days, uncertain session boundaries, incomplete HP/HC splits and price assumptions
stay explicit. Vehicle statistics use the provider's day labels; exact provider
day boundaries/timezone remain to qualify, while observations/weather use Paris.
Whole-home Linky and ordinary Meross sockets are never assumed to measure EV
charging. No Raspberry Pi database has been imported.

Historical delivery proofs remain in the dependent draft PRs. Code24 contracts,
portable operator and compatibility limits are documented in
[the architecture note](../../docs/components/android-code24.md). Real account compatibility, vehicle-specific capability rejection,
renewal over time and physical command results require voluntary qualification
on the owner's S5e. Protocol fixtures and CI do not establish real-car parity.

See [onboarding](onboarding.md) for the on-device setup path and the API/software
MIT notices in `licenses/` and `crates/moli-hyundai/data/`.
