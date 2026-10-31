# Meross MSS310 — local measurements and ON/OFF

One built-in Moli profile, distinct from EM06. Reuses code39's protocol,
normalisation, fixtures and optional cloud client. The normal profile driver
owns polling, reconnection, availability, history and commands. Writes use the
Hub, its existing guards and journal. The writable `on` point and sensors are
available to native Moli automations; no rule is installed or enabled.

| Capability | Manufacturer / meross_lan | Moli implementation |
|---|---|---|
| Identity | System.All hardware UUID/model | Signed GET/ACK, key/message/UUID checks, stable instance + UUID |
| ON/OFF | ToggleX channel 0 | Hub command, signed SETACK and signed state readback, no blind retry |
| W / A / V | Electricity: mW / mA / decivolts | code39 conversions, 30 s |
| Energy | ConsumptionX daily Wh records | Latest valid device record + date, 5 min; no lifetime index |
| Wi-Fi / hardware | Runtime signal %, System.All hardware/firmware | Optional typed points, unknown when absent; signal 1 min |
| Account key | signIn, region redirect, MFA, devList | Reused portable client, voluntary provisioning only |

Protocol reference: [meross_lan](https://github.com/krahabb/meross_lan/tree/bafedb4f8563153f9e3494292073551bb28f5f9a),
`devices/mss.py` and `merossclient/cloudapi.py`, plus their maintained versions
consulted 2026-10-31. [Meross](https://www.meross.com/) product features are not
a public stable local API guarantee. Upstream Moli's profile catalogue consulted
that date has EM06 but no MSS310 equivalent. MIT attribution is in `NOTICE` and
`licenses/meross-lan-MIT.txt`; no Home Assistant runtime is embedded.

Account session and instance keys use Moli's encrypted vault. No password is
retained. Disconnecting the account keeps local keys. Android has no equipment
database or scheduler; `moli.toml` remains canonical. Linux uses the same profile
and normal secret tool. Native Android form/Keystore adaptation is separate.

Only System.All/Electricity/ConsumptionX/Runtime GET and explicit ToggleX SET are
used. No Countdown/Timer/schedule changes; Meross application settings remain.
Starting, provisioning or enabling the driver sends no SET. An uncertain command
is reported and followed by GET, never replayed automatically. Existing native
Hub rules apply equally to UI, API and validated automations, without an extra
Meross permission or confirmation.

Inherited measurement fixtures are synthetic. `moli_latest` in the consumption
fixture is derived for catalogue replay; live code recomputes it from raw daily
records. Daily consumption is `semantic = "other"`, never a monotonic energy
meter. Existing Moli history stores these points, without another accumulator.
No firmware timezone/reset offset correction or energy estimate is invented.

Tests cover conversions, missing values, signature/identity/ACK, normal Hub
commands from API/UI/automation, no writes at startup, repeated commands, four
configurations and stable identity after renaming/reassociation. No real account
or device was accessed by the developer. Cloud/firmware interoperability and
physical switching remain for the owner's voluntary hardware tests.

Account provisioning reuses code39 UUID/IP matching: one user action reuses the
code8 LAN discovery and sends one signed identity GET per candidate (four at a
time). Unique inventory matches are shown by account name, IP and UUID; missing
or ambiguous plugs are not inferred. One add action rechecks the found targets
and commits their canonical configuration together, preserving existing IDs,
names and enabled flags. No persistent IP inventory or additional discovery
engine is introduced. Local matching does not restart the running Moli engine.
