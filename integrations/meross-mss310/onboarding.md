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
