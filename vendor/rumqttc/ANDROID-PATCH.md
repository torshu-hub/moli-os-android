# Narrow dependency patch over rumqttc 0.25.1

Source: the unmodified crates.io rumqttc 0.25.1 release, Apache-2.0.
Security change in Cargo.toml and Cargo.toml.orig: rustls-webpki 0.102.8 -> 0.103.16.
Upstream tls.rs uses webpki solely in Error::WebPki; TLS certificate validation
continues through tokio-rustls/rustls. No verification or MQTT implementation
has been weakened or replaced. This removes the unsupported old webpki branch
instead of suppressing RUSTSEC-2026-0382. The same dependency fixes older
webpki advisories, so their former exceptions have also been removed.

Keep the upstream source and license to make this two-line patch reproducible.
Remove the path patch when a released upstream rumqttc uses patched webpki.

Packaging only: author names are retained but public email contact metadata is
omitted to satisfy the repository hygiene rule. Registry cache markers and the
standalone upstream lockfile are omitted; the workspace lockfile is authoritative.
All upstream source files and the Apache license remain unchanged.

Upstream Git commit: `f1e9e8d558783f942993046679cdf3c8c3a3d36b`. License copied from that exact upstream commit.
