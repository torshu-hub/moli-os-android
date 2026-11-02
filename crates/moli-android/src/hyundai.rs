//! Private native setup only, serialized with the engine stopped for writes.
use moli_hyundai::{
    Config,
    account::{Account, SECRET},
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
    out["options"] = json!(config);
    out["selected"] = json!(id);
    Ok(out)
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
pub(crate) fn run(input: &Value) -> Result<Value> {
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
            "login" => {
                if input["consent"] != true {
                    return Err("consent");
                }
                let now = moli_core::now_ms();
                let last = secret(data, &key, id, "login_attempt")?
                    .and_then(|s| s.parse::<u64>().ok())
                    .unwrap_or(0);
                let blocked = secret(data, &key, id, "login_blocked_until")?
                    .and_then(|s| s.parse::<u64>().ok())
                    .unwrap_or(0);
                if now < blocked || now.saturating_sub(last) < 60_000 {
                    return Err("rate_limit");
                }
                set_secret(
                    &data.join("secrets.enc"),
                    &key,
                    &format!("{id}/login_attempt"),
                    &now.to_string(),
                )
                .await
                .map_err(|_| "storage")?;
                let pin = input["pin"]
                    .as_str()
                    .filter(|p| !p.is_empty())
                    .map(str::to_owned);
                let account = Account::login(
                    input["username"].as_str().ok_or("input")?,
                    input["password"].as_str().ok_or("input")?,
                    pin,
                )
                .await;
                if let Err(moli_hyundai::Error::RateLimit(seconds)) = &account {
                    set_secret(
                        &data.join("secrets.enc"),
                        &key,
                        &format!("{id}/login_blocked_until"),
                        &now.saturating_add(seconds.saturating_mul(1000).max(60_000))
                            .to_string(),
                    )
                    .await
                    .map_err(|_| "storage")?;
                }
                let account = account.map_err(moli_hyundai::Error::code)?;
                set_secret(
                    &data.join("secrets.enc"),
                    &key,
                    &format!("{id}/{SECRET}"),
                    &account.encode().map_err(|_| "storage")?,
                )
                .await
                .map_err(|_| "storage")?;
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
                    return Err("consent");
                }
                let text = prepare(&raw, id, &config, true)?;
                Ok(json!({"expected_configuration":raw,"configuration":text}))
            }
            "disconnect" => {
                if input["consent"] != true {
                    return Err("consent");
                }
                forget_secret(&data.join("secrets.enc"), &key, &format!("{id}/{SECRET}"))
                    .await
                    .map_err(|_| "storage")?;
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
}
