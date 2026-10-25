//! Private native OAuth boundary. Personal credentials are accepted only by
//! explicit post-install provisioning; callbacks require the complete bound URL.
//! No broker deployment, callback Intent, arbitrary URL or token import.
use moli_netatmo::oauth::{Broker, Client, Error, SCOPE, State, Unconfigured};
use moli_runtime::{MasterKey, forget_secret, has_secret, set_secret};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
};

type Result<T> = std::result::Result<T, &'static str>;
const DEFAULT: &str = "android-netatmo";
mod personal;

fn instances(data: &Path) -> Result<Value> {
    use std::io::Read as _;
    let file = std::fs::File::open(data.join("moli.toml")).map_err(|_| "configuration")?;
    let mut bytes = Vec::new();
    file.take(524_289)
        .read_to_end(&mut bytes)
        .map_err(|_| "storage")?;
    if bytes.len() > 524_288 {
        return Err("configuration");
    }
    let text = std::str::from_utf8(&bytes).map_err(|_| "configuration")?;
    let config: toml::Value = toml::from_str(text).map_err(|_| "configuration")?;
    let drivers = config.get("driver").map_or(Ok(&[][..]), |d| {
        d.as_array().map(Vec::as_slice).ok_or("configuration")
    })?;
    let mut list = Vec::new();
    let mut seen = std::collections::BTreeSet::new();
    for driver in drivers {
        let id = driver
            .get("id")
            .and_then(toml::Value::as_str)
            .ok_or("configuration")?;
        if id.is_empty()
            || id.len() > 128
            || id.contains([':', '/', '\\'])
            || id.chars().any(char::is_control)
            || !seen.insert(id)
        {
            return Err("configuration");
        }
        let kind = driver
            .get("kind")
            .and_then(toml::Value::as_str)
            .ok_or("configuration")?;
        if kind == "netatmo" {
            let enabled = driver
                .get("enabled")
                .map_or(Ok(true), |v| v.as_bool().ok_or("configuration"))?;
            list.push(json!({"id":id,"enabled":enabled,"configured":true}));
        }
    }
    if list.is_empty() {
        if drivers
            .iter()
            .any(|d| d.get("id").and_then(toml::Value::as_str) == Some(DEFAULT))
        {
            return Err("configuration");
        }
        Ok(json!([{"id":DEFAULT,"enabled":false,"configured":false}]))
    } else {
        Ok(json!(list))
    }
}
fn now() -> u64 {
    moli_core::now_ms()
}
fn validate(input: &Value) -> Result<(PathBuf, String, MasterKey)> {
    let object = input.as_object().ok_or("input")?;
    if object.keys().any(|k| {
        !matches!(
            k.as_str(),
            "data" | "master" | "action" | "instance" | "consent"
        ) && !matches!(
            (input["action"].as_str(), k.as_str()),
            (Some("provision"), "application")
                | (Some("complete"), "callback")
                | (Some("prepare_home"), "home_id" | "enable")
        )
    }) {
        return Err("input");
    }
    let data = PathBuf::from(input["data"].as_str().ok_or("input")?);
    let master = input["master"]
        .as_str()
        .filter(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
        .ok_or("input")?;
    if !data.is_absolute() || !data.is_dir() {
        return Err("input");
    }
    if data
        .join("android-configuration.undo.json")
        .try_exists()
        .map_err(|_| "storage")?
    {
        return Err("configuration_storage");
    }
    let list = instances(&data)?;
    let selected = input["instance"]
        .as_str()
        .unwrap_or_else(|| list[0]["id"].as_str().unwrap_or(DEFAULT));
    if !list
        .as_array()
        .is_some_and(|items| items.iter().any(|d| d["id"] == selected))
        || selected.len() > 128
        || selected.is_empty()
        || selected.contains([':', '/', '\\'])
    {
        return Err("input");
    }
    Ok((data, selected.to_owned(), MasterKey::new(master.to_owned())))
}
fn snapshot(
    data: &Path,
    instance: &str,
    master: &MasterKey,
    client: &mut Client,
    broker: &dyn Broker,
) -> Result<Value> {
    let mut value = json!({"setup_required":broker.policy().is_none(),"state":client.state(now()).code(),"scope":SCOPE,
        "credential_present":has_secret(&data.join("secrets.enc"),master,&format!("{instance}/oauth_tokens")).map_err(|_|"storage")?,
        "instances":instances(data)?,"selected":instance});
    if let Some(policy) = broker.policy() {
        value["client_id"] = json!(policy.client_id());
        value["redirect_uri"] = json!(policy.redirect_uri());
    }
    Ok(value)
}
async fn operate(
    input: &Value,
    data: &Path,
    instance: &str,
    master: &MasterKey,
    client: &mut Client,
    broker: &dyn Broker,
) -> Result<Value> {
    let namespace = format!("{instance}/oauth_tokens");
    match input["action"].as_str().ok_or("input")? {
        "status" => (),
        "begin" => {
            if input["consent"] != true {
                return Err("oauth_consent");
            }
            let url = client.begin(broker, now()).await.map_err(Error::code)?;
            let mut result = snapshot(data, instance, master, client, broker)?;
            result["authorization_url"] = json!(url);
            return Ok(result);
        }
        "check" => {
            if client.check(broker, now()).await.map_err(Error::code)? == State::Saving {
                if client.state(now()) != State::Saving {
                    return Err("oauth_expired");
                }
                let bundle = client
                    .grant()
                    .ok_or("oauth_state")?
                    .vault_bundle()
                    .map_err(Error::code)?;
                // Existing vault encrypts/merges/atomically commits one complete pair.
                // Config, home, labels, associations and Keystore are not rewritten.
                set_secret(&data.join("secrets.enc"), master, &namespace, &bundle)
                    .await
                    .map_err(|_| "storage")?;
                client.stored().map_err(Error::code)?;
            }
        }
        "cancel" => {
            client.cancel(broker).await.map_err(Error::code)?;
        }
        "disconnect" => {
            if input["consent"] != true {
                return Err("oauth_consent");
            }
            // Cancel locally even when the remote transaction is unavailable.
            let _ = client.cancel(broker).await;
            forget_secret(&data.join("secrets.enc"), master, &namespace)
                .await
                .map_err(|_| "storage")?;
            let mut result = snapshot(data, instance, master, client, broker)?;
            result["remote_revocation_required"] = json!(true);
            return Ok(result);
        }
        _ => return Err("input"),
    }
    snapshot(data, instance, master, client, broker)
}
pub(crate) fn run(input: &Value) -> Result<Value> {
    static SESSIONS: OnceLock<Mutex<BTreeMap<(PathBuf, String), Client>>> = OnceLock::new();
    let (data, instance, master) = validate(input)?;
    if input["action"] == "provision"
        || moli_runtime::has_secret(
            &data.join("secrets.enc"),
            &master,
            &format!(
                "{instance}/{}",
                moli_netatmo::oauth::personal::APPLICATION_SECRET
            ),
        )
        .map_err(|_| "storage")?
    {
        return personal::run(input, &data, &instance, &master);
    }
    let mut sessions = SESSIONS
        .get_or_init(|| Mutex::new(BTreeMap::new()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let current = (data.clone(), instance.clone());
    sessions.retain(|key, s| {
        key == &current || matches!(s.state(now()), State::Pending | State::Saving)
    });
    if sessions.len() >= 8 && !sessions.contains_key(&(data.clone(), instance.clone())) {
        return Err("oauth_state");
    }
    let client = sessions
        .entry((data.clone(), instance.clone()))
        .or_default();
    client.state(now());
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "internal")?;
    // Deliberately fail closed: no application, broker or redirect approved yet.
    runtime.block_on(operate(
        input,
        &data,
        &instance,
        &master,
        client,
        &Unconfigured,
    ))
}

#[cfg(test)]
mod tests;
