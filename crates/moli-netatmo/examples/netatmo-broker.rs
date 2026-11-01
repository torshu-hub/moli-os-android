//! Opt-in confidential host, bound to loopback behind an approved HTTPS proxy.
//! It opens existing credentials only. No application, key, account or domain
//! is created. This example is never included in an Android build.
#[cfg(not(target_os = "android"))]
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    use moli_core::InstanceId;
    use moli_netatmo::oauth::{
        Policy,
        confidential::service::{Service, endpoint::router},
    };
    use moli_runtime::{Hub, HubOptions, MasterKey};
    use serde::Deserialize;
    use std::{io::Read as _, path::PathBuf};
    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Config {
        data_dir: PathBuf,
        origin: String,
        client_id: String,
        account: String,
        device_instance: String,
        port: u16,
    }
    let path = std::env::args_os()
        .nth(1)
        .ok_or_else(|| anyhow::anyhow!("configuration path required"))?;
    let mut bytes = Vec::new();
    std::fs::File::open(path)?
        .take(16385)
        .read_to_end(&mut bytes)?;
    anyhow::ensure!(bytes.len() <= 16384, "configuration too large");
    let config: Config = serde_json::from_slice(&bytes)
        .map_err(|_| anyhow::anyhow!("invalid broker configuration"))?;
    anyhow::ensure!(
        !config.account.is_empty()
            && config.account.len() <= 128
            && config
                .account
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_')),
        "invalid account namespace"
    );
    let key_path = std::env::var_os("MOLI_MASTER_KEY_FILE")
        .ok_or_else(|| anyhow::anyhow!("existing MOLI_MASTER_KEY_FILE required"))?;
    let mut key = String::new();
    std::fs::File::open(key_path)?
        .take(129)
        .read_to_string(&mut key)?;
    let hub = Hub::new(HubOptions {
        secrets: Some((
            config.data_dir.join("secrets.enc"),
            MasterKey::new(key.trim().to_owned()),
        )),
        ..HubOptions::default()
    })?;
    let policy = Policy::new(
        &config.client_id,
        &format!("{}/oauth/netatmo/callback", config.origin),
    )
    .map_err(|_| anyhow::anyhow!("invalid approved OAuth policy"))?;
    let listener =
        tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, config.port)).await?;
    let service = Service::open(
        policy,
        hub,
        InstanceId::from(config.account),
        &config.device_instance,
        moli_core::now_ms(),
    )
    .map_err(|_| anyhow::anyhow!("broker vault, binding or credentials unavailable"))?;
    let app = router(service, &config.origin)
        .map_err(|_| anyhow::anyhow!("invalid approved HTTPS origin"))?;
    axum::serve(listener, app)
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;
    Ok(())
}
#[cfg(target_os = "android")]
fn main() {}
