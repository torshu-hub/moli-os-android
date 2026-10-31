# Netatmo Energy

Native portable Rust driver: `crates/moli-netatmo`. Account setup is separate from
Energy reads and guarded commands. Rooms/modules retain provider home/room/module
IDs; no display name or network address becomes an identity.

See [contract, cadence, OAuth and commands](../../docs/components/netatmo-energy.md).
API status is collected every 30 seconds, topology every 5 minutes by default.
Cloud collection is not a guarantee of physical measurement latency.

The personal Android OAuth route is an explicit experimental opt-in, not a
supplier-approved confidential mobile client. Credentials are user-supplied after
installation and held only in the existing encrypted vault. No registration,
secret, account, webhook, household rule or actuator simulation is distributed.

Original model/OAuth/vault adapter code is ported from this fork at
`1ef31980ca1d072ff3f6237d9b11e228be0779ed` (Apache-2.0). Official Energy OpenAPI
1.1.2 and OAuth contracts were rechecked on 2026-10-31; endpoint/scopes are cited
in the component documentation. Home Assistant is a behavioral reference only;
no Python source is copied. Fixtures are entirely synthetic OAuth/API contracts,
not captured household data or a physical simulator.
