# Hyundai Europe CCI / myHyundai

Native portable Rust driver, private Android account setup, ordinary Moli Hub
points and guarded human commands, vehicle card and persistent Ioniq 5 journal.
No Python runtime, HA server, separate telemetry database or GPS history.

The EU protocol is ported from `hyundai_kia_connect_api` v4.45.0,
`43343489d6183ba0ba2be03c3fc4eacd0ac2d536` (MIT); functional coverage compares
`kia_uvo` v3.22.0, `d56b2bc41c9ecc810297ad16af12e9febdded66f` (MIT).
Public cipher parameters and affine signing vectors are included with notices;
all account, status, journal and Android test fixtures are synthetic.

CCI/OneApp login encrypts the password with the IDP's public RSA key; the
password is not persisted. The encrypted vault holds the full rotating token
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

Pre-CCS2 remote controls, navigation destination and new SVM capture are not
implemented by this reference EU transport and are visibly unavailable. Stored
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

The dependent draft PR contains the HA-to-EU-to-Moli coverage matrix and delivery
proofs. Real account compatibility, vehicle-specific capability rejection,
renewal over time and physical command results require voluntary qualification
on the owner's S5e. Protocol fixtures and CI do not establish real-car parity.

See [onboarding](onboarding.md) for the on-device setup path and the API/software
MIT notices in `licenses/` and `crates/moli-hyundai/data/`.
