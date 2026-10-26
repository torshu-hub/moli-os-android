# Setup

Android: Equipment → Stop → Add MSS310. Enter its name, private IPv4 address and local
HTTP port (normally 80). Enter its already available local Meross key in the masked
field; never put a real key in a report, screenshot, profile or GitHub. No cloud login
or key extraction is provided. Approve reading, Save, then Start and view Home.
Repeat for each plug; one profile supports up to eight independently configured plugs.
An empty key field preserves a previously encrypted key. Disable a plug and Save to
revoke reads; changing a name/address preserves its instance ID. Removing an entry
revokes its reads but does not delete history or orphaned encrypted vault entries.

The Android key input disables view-state persistence/autofill and personalized IME
learning; the screen blocks screenshots. Keys go directly into Moli's existing encrypted
`secrets.enc` under `<instance>/key`. The master key stays wrapped by Android Keystore;
existing password-encrypted backup/restore carries the vault and portable master key.
No plaintext key is put in TOML, home settings or exported trial journals. Local HTTP
messages are signed with the existing protocol; HTTP itself is not encrypted.

Linux: built-in `meross-mss310`, host/port/name options and `discovery=false` for an exact
destination. Set `<instance>/key` through the existing secret tool, never TOML. Future
Linux recovery remains possible through standard engine data; no Pi work is included.

## SET simulation

See [the simulation notes](../../docs/components/meross-set-simulation.md).
The canonical profile remains read-only; the external synthetic loopback variant
does not authorize physical commands.


## Manual opt-in beta

Existing installations stay read-only. On Android, stop the engine, choose one
non-critical plug in My devices, confirm its name/address/UUID, enable manual
controls, Save and Start. A fresh identity already seen by the local reader may
be proposed if the legacy settings have no UUID; no old association is replaced.
Only the selected plug gets Turn on / Turn off, through Moli's existing guard.
Success requires a signed readback after SETACK; a timeout never resends a SET.
Stop, uncheck, Save and Start to revoke controls. This does not turn a plug off.
Do not test the Finder/boiler without separate owner approval.

Linux can opt into `meross-mss310-manual` with `vars.manual_uuid`, an explicit
private IPv4 destination, `discovery=false` and the existing encrypted key. Any
existing `expected_uuid` must match. No additional Linux deployment is included.
The built-in variant reuses this package; external secret-bearing LAN profiles
remain blocked by the original trust/host rules and the SET loopback constraint.

The owner performs the physical test personally. Keep Meross's existing 30-minute
limit if configured; no timer, countdown, routine or schedule is written by Moli.
No guarantee of offline timer operation is made. Complete procedure, diagnostics
and limitations: [manual beta](../../docs/components/meross-manual-beta.md).
