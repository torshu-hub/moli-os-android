//! Private native setup only, serialized with the engine stopped for writes.
use moli_hyundai::{
    Config,
    account::{Account, SECRET},
    auth::{ApiMode, LoginFailure, PendingLogin},
};
use moli_runtime::{MasterKey, forget_secret, read_secret, set_secret};
use serde_json::{Value, json};
use std::path::Path;
use toml_edit::{ArrayOfTables, DocumentMut, Item, Table, value};
type Result<T> = std::result::Result<T, &'static str>;
fn secret(data: &Path, key: &MasterKey, id: &str, name: &str) -> Result<Option<String>> {
    read_secret(&data.join("secrets.enc"), key, &format!("{id}/{name}")).map_err(|_| "storage")
}
fn options(raw: &str, id: &str) -> Result<Config> {
    let v: toml::Value = toml::from_str(raw).map_err(|_| "configuration")?;
    v.get("driver")
        .and_then(toml::Value::as_array)
        .into_iter()
        .flatten()
        .find(|d| d.get("id").and_then(toml::Value::as_str) == Some(id))
        .map_or(Ok(Config::default()), |d| {
            if d["kind"].as_str() != Some("hyundai") {
                return Err("conflict");
            }
            d.get("options")
                .cloned()
                .unwrap_or(toml::Value::Table(toml::Table::new()))
                .try_into()
                .map_err(|_| "configuration")
        })
}
fn status(raw: &str, data: &Path, key: &MasterKey, id: &str) -> Result<Value> {
    let account = secret(data, key, id, SECRET)?
        .map(|s| Account::parse(&s).map_err(|_| "storage"))
        .transpose()?;
    let config = options(raw, id)?;
    let mut out = account.map_or(json!({"connected":false,"vehicles":[]}), |a| a.public());
    let pending = secret(data, key, id, "authorized_session")?
        .map(|s| serde_json::from_str::<moli_hyundai::Session>(&s).map_err(|_| "storage"))
        .transpose()?;
    out["authorized_pending_discovery"] = json!(pending.is_some());
    if let Some(pending) = pending {
        out["pending_api_mode"] = json!(pending.api_mode);
        out["authorized_expires_at"] = json!(pending.expires);
    }
    out["diagnostic"] = secret(data, key, id, "login_diagnostic")?
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .unwrap_or(Value::Null);
    out["options"] = json!(config);
    out["selected"] = json!(id);
    Ok(out)
}
async fn failure(
    raw: &str,
    data: &Path,
    key: &MasterKey,
    id: &str,
    error: LoginFailure,
) -> Result<Value> {
    if let moli_hyundai::Error::RateLimit(seconds) = error.error {
        set_secret(
            &data.join("secrets.enc"),
            key,
            &format!("{id}/login_blocked_until"),
            &moli_core::now_ms()
                .saturating_add(seconds.saturating_mul(1000).max(60_000))
                .to_string(),
        )
        .await
        .map_err(|_| "storage")?;
    }
    set_secret(
        &data.join("secrets.enc"),
        key,
        &format!("{id}/login_diagnostic"),
        &serde_json::to_string(&error.diagnostic).map_err(|_| "storage")?,
    )
    .await
    .map_err(|_| "storage")?;
    status(raw, data, key, id)
}
fn prepare(raw: &str, id: &str, config: &Config, enable: bool) -> Result<String> {
    config.validate().map_err(|_| "configuration")?;
    let mut doc = raw.parse::<DocumentMut>().map_err(|_| "configuration")?;
    if doc.get("driver").is_none() {
        doc["driver"] = Item::ArrayOfTables(ArrayOfTables::new());
    }
    let rows = doc["driver"]
        .as_array_of_tables_mut()
        .ok_or("configuration")?;
    if !rows.iter().any(|r| r["id"].as_str() == Some(id)) {
        let mut row = Table::new();
        row["id"] = value(id);
        row["kind"] = value("hyundai");
        rows.push(row);
    }
    let row = rows
        .iter_mut()
        .find(|r| r["id"].as_str() == Some(id))
        .ok_or("configuration")?;
    if row["kind"].as_str() != Some("hyundai") {
        return Err("conflict");
    }
    row["enabled"] = value(enable);
    let text = toml::to_string(config).map_err(|_| "configuration")?;
    let options = text.parse::<DocumentMut>().map_err(|_| "configuration")?;
    row["options"] = Item::Table(options.as_table().clone());
    Ok(doc.to_string())
}
#[allow(clippy::too_many_lines)] // Serialized native setup dispatch keeps the vault transaction together.
pub fn run(input: &Value) -> Result<Value> {
    let data = Path::new(input["data"].as_str().ok_or("input")?);
    let key = MasterKey::new(input["master"].as_str().ok_or("input")?.into());
    let id = input["instance"].as_str().unwrap_or("hyundai-eu");
    if id.is_empty()
        || id.len() > 100
        || !id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
    {
        return Err("input");
    }
    let raw = std::fs::read_to_string(data.join("moli.toml")).map_err(|_| "storage")?;
    let operation = input["operation"].as_str().ok_or("input")?;
    if operation == "status" {
        return status(&raw, data, &key, id);
    }
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "internal")?;
    runtime.block_on(async {
        match operation {
            "login" | "begin_login" | "complete_login" | "discover" | "cancel_login" => {
                if input["consent"] != true {
                    return Err("setup_not_confirmed");
                }
                if operation == "cancel_login" {
                    forget_secret(
                        &data.join("secrets.enc"),
                        &key,
                        &format!("{id}/browser_login"),
                    )
                    .await
                    .map_err(|_| "storage")?;
                    if let Some(cause) = input["failure"].as_str() {
                        return failure(&raw, data, &key, id, LoginFailure::browser(cause)).await;
                    }
                    return status(&raw, data, &key, id);
                }
                let now = moli_core::now_ms();
                let blocked = secret(data, &key, id, "login_blocked_until")?
                    .and_then(|s| s.parse::<u64>().ok())
                    .unwrap_or(0);
                let last = secret(data, &key, id, "login_attempt")?
                    .and_then(|s| s.parse::<u64>().ok())
                    .unwrap_or(0);
                if now < blocked
                    || (matches!(operation, "login" | "begin_login")
                        && now.saturating_sub(last) < 60_000)
                {
                    return failure(
                        &raw,
                        data,
                        &key,
                        id,
                        LoginFailure::local(
                            "cooldown",
                            moli_hyundai::Error::RateLimit(
                                blocked.saturating_sub(now).div_ceil(1000).max(60),
                            ),
                        ),
                    )
                    .await;
                }
                if matches!(operation, "login" | "begin_login") {
                    set_secret(
                        &data.join("secrets.enc"),
                        &key,
                        &format!("{id}/login_attempt"),
                        &now.to_string(),
                    )
                    .await
                    .map_err(|_| "storage")?;
                }
                if matches!(operation, "login" | "begin_login") {
                    for name in ["login_diagnostic", "authorized_session"] {
                        forget_secret(&data.join("secrets.enc"), &key, &format!("{id}/{name}"))
                            .await
                            .map_err(|_| "storage")?;
                    }
                }
                let pin = input["pin"]
                    .as_str()
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned);
                let mode: ApiMode = serde_json::from_value(
                    input.get("api_mode").cloned().unwrap_or(json!("europe")),
                )
                .map_err(|_| "configuration")?;
                if operation == "begin_login" {
                    match PendingLogin::begin(mode, pin).await {
                        Ok(ticket) => {
                            set_secret(
                                &data.join("secrets.enc"),
                                &key,
                                &format!("{id}/browser_login"),
                                &serde_json::to_string(&ticket).map_err(|_| "storage")?,
                            )
                            .await
                            .map_err(|_| "storage")?;
                            let mut out = status(&raw, data, &key, id)?;
                            out["browser_login"] = ticket.public();
                            return Ok(out);
                        }
                        Err(e) => return failure(&raw, data, &key, id, e).await,
                    }
                }
                let session = match operation {
                    "login" => {
                        Account::password_authorization(
                            input["username"].as_str().ok_or("input")?,
                            input["password"].as_str().ok_or("input")?,
                            pin,
                            mode,
                        )
                        .await
                    }
                    "complete_login" => {
                        let saved = secret(data, &key, id, "browser_login")?
                            .ok_or("callback_invalid_or_expired")?;
                        let ticket: PendingLogin =
                            serde_json::from_str(&saved).map_err(|_| "storage")?;
                        let callback = input["callback"].as_str().ok_or("input")?;
                        if let Err(e) = ticket.validate_callback(callback) {
                            return failure(
                                &raw,
                                data,
                                &key,
                                id,
                                LoginFailure::local("browser_callback", e),
                            )
                            .await;
                        }
                        // One-use authorization code: consume before its potentially ambiguous exchange.
                        forget_secret(
                            &data.join("secrets.enc"),
                            &key,
                            &format!("{id}/browser_login"),
                        )
                        .await
                        .map_err(|_| "storage")?;
                        ticket.finish(callback).await
                    }
                    "discover" => {
                        let value = secret(data, &key, id, "authorized_session")?
                            .ok_or("authentication")?;
                        let s: moli_hyundai::Session =
                            serde_json::from_str(&value).map_err(|_| "storage")?;
                        if s.expires <= now / 1000 + 30 {
                            return failure(
                                &raw,
                                data,
                                &key,
                                id,
                                LoginFailure::local(
                                    "fleet_discovery",
                                    moli_hyundai::Error::Expired,
                                ),
                            )
                            .await;
                        }
                        Ok(s)
                    }
                    _ => return Err("input"),
                };
                let session = match session {
                    Ok(s) => s,
                    Err(e) => return failure(&raw, data, &key, id, e).await,
                };
                set_secret(
                    &data.join("secrets.enc"),
                    &key,
                    &format!("{id}/authorized_session"),
                    &serde_json::to_string(&session).map_err(|_| "storage")?,
                )
                .await
                .map_err(|_| "storage")?;
                let account = match Account::discover(session).await {
                    Ok(a) => a,
                    Err(e) => return failure(&raw, data, &key, id, e).await,
                };
                set_secret(
                    &data.join("secrets.enc"),
                    &key,
                    &format!("{id}/{SECRET}"),
                    &account.encode().map_err(|_| "storage")?,
                )
                .await
                .map_err(|_| "storage")?;
                for name in ["authorized_session", "login_diagnostic"] {
                    forget_secret(&data.join("secrets.enc"), &key, &format!("{id}/{name}"))
                        .await
                        .map_err(|_| "storage")?;
                }
                status(&raw, data, &key, id)
            }
            "configure" => {
                let config: Config = serde_json::from_value(input["options"].clone())
                    .map_err(|_| "configuration")?;
                let account = secret(data, &key, id, SECRET)?.ok_or("authentication")?;
                let account = Account::parse(&account).map_err(|_| "storage")?;
                if config
                    .vehicles
                    .iter()
                    .any(|k| !account.vehicles.iter().any(|v| v.key == *k))
                {
                    return Err("configuration");
                }
                if (config.commands_enabled || config.location_enabled) && input["consent"] != true
                {
                    return Err("setup_not_confirmed");
                }
                let text = prepare(&raw, id, &config, true)?;
                Ok(json!({"expected_configuration":raw,"configuration":text}))
            }
            "disconnect" => {
                if input["consent"] != true {
                    return Err("setup_not_confirmed");
                }
                for name in [
                    SECRET,
                    "browser_login",
                    "authorized_session",
                    "login_diagnostic",
                ] {
                    forget_secret(&data.join("secrets.enc"), &key, &format!("{id}/{name}"))
                        .await
                        .map_err(|_| "storage")?;
                }
                status(&raw, data, &key, id)
            }
            _ => Err("input"),
        }
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn config_preserves_other_drivers_and_never_embeds_credentials() {
        let raw = "# retained\n[[driver]]\nid='netatmo'\nkind='netatmo'\n[driver.options]\nhome_id='synthetic'\n";
        let next = prepare(raw, "hyundai-eu", &Config::default(), true).unwrap();
        assert!(next.starts_with(raw));
        assert!(next.contains("hyundai"));
        assert!(!next.contains("password"));
        assert!(!next.contains("pin"));
        assert!(!Config::default().commands_enabled);
        assert!(!Config::default().location_enabled);
    }
    #[test]
    fn owner_login_is_explicit_and_browser_callback_diagnostics_do_not_expose_private_data() {
        let data = std::env::temp_dir().join(format!("moli-hyundai-auth-{}", std::process::id()));
        std::fs::create_dir_all(&data).unwrap();
        std::fs::write(data.join("moli.toml"), "# preserved configuration\n").unwrap();
        let mut input = json!({"data":data,"master":"22".repeat(32),"operation":"begin_login","api_mode":"gspa","pin":"0000","consent":false});
        assert_eq!(run(&input).unwrap_err(), "setup_not_confirmed");
        input["consent"] = json!(true);
        let begun = run(&input).unwrap();
        assert!(
            begun["browser_login"]["authorization_url"]
                .as_str()
                .unwrap()
                .starts_with("https://idpconnect-eu.hyundai.com/")
        );
        input["operation"] = json!("status");
        let status = run(&input).unwrap();
        assert!(status.get("browser_login").is_none());
        assert!(!status.to_string().contains("0000"));
        assert!(!status.to_string().contains("authorization_url"));
        input["operation"] = json!("complete_login");
        input["callback"] = json!(
            "https://oneapp.hyundai.com/redirect?code=synthetic-private-code&state=synthetic-wrong-state"
        );
        let failed = run(&input).unwrap();
        assert_eq!(failed["diagnostic"]["phase"], "browser_callback");
        assert_eq!(failed["diagnostic"]["cause"], "callback_invalid_or_expired");
        assert!(!failed.to_string().contains("synthetic-private-code"));
        input["operation"] = json!("begin_login");
        let cooldown = run(&input).unwrap();
        assert_eq!(cooldown["diagnostic"]["phase"], "cooldown");
        assert_eq!(cooldown["diagnostic"]["cause"], "rate_limit");
        assert_eq!(
            std::fs::read_to_string(data.join("moli.toml")).unwrap(),
            "# preserved configuration\n"
        );
        std::fs::remove_dir_all(data).unwrap();
    }
}
