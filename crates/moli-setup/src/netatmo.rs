//! Portable personal account setup. The host serializes I/O and stops its engine for writes.
type Result<T> = std::result::Result<T, &'static str>;
fn now() -> u64 {
    moli_core::now_ms()
}
fn instances(data: &Path) -> Result<Value> {
    let raw = std::fs::read_to_string(data.join("moli.toml")).map_err(|_| "storage")?;
    let config: toml::Value = toml::from_str(&raw).map_err(|_| "configuration")?;
    Ok(json!(
        config
            .get("driver")
            .and_then(toml::Value::as_array)
            .into_iter()
            .flatten()
            .filter(|d| d.get("kind").and_then(toml::Value::as_str) == Some("netatmo"))
            .filter_map(|d| d.get("id").and_then(toml::Value::as_str))
            .collect::<Vec<_>>()
    ))
}
use moli_netatmo::oauth::{
    Error, Grant, Home, SCOPE,
    personal::{
        APPLICATION_SECRET, Application, REDIRECT, ROTATION_SECRET, Session, rotation_fingerprint,
    },
};
use moli_runtime::{MasterKey, forget_secret, read_secret, set_secret};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
};

#[derive(Default)]
struct Entry {
    session: Session,
    homes: Vec<Home>,
    verified: Option<(String, u64)>,
    account: moli_netatmo::oauth::account::Account,
}
fn read(data: &Path, instance: &str, key: &MasterKey, name: &str) -> Result<Option<String>> {
    read_secret(
        &data.join("secrets.enc"),
        key,
        &format!("{instance}/{name}"),
    )
    .map_err(|_| "storage")
}
async fn put(data: &Path, instance: &str, key: &MasterKey, name: &str, text: &str) -> Result<()> {
    set_secret(
        &data.join("secrets.enc"),
        key,
        &format!("{instance}/{name}"),
        text,
    )
    .await
    .map_err(|_| "storage")
}
async fn forget(data: &Path, instance: &str, key: &MasterKey, name: &str) -> Result<()> {
    forget_secret(
        &data.join("secrets.enc"),
        key,
        &format!("{instance}/{name}"),
    )
    .await
    .map_err(|_| "storage")?;
    Ok(())
}
fn application(data: &Path, instance: &str, key: &MasterKey) -> Result<Application> {
    Application::parse(&read(data, instance, key, APPLICATION_SECRET)?.ok_or("oauth_setup")?)
        .map_err(Error::code)
}
fn current_home(data: &Path, instance: &str) -> Result<Option<String>> {
    let raw = std::fs::read_to_string(data.join("moli.toml")).map_err(|_| "storage")?;
    let config: toml::Value = toml::from_str(&raw).map_err(|_| "configuration")?;
    Ok(config
        .get("driver")
        .and_then(toml::Value::as_array)
        .into_iter()
        .flatten()
        .find(|d| d["id"].as_str() == Some(instance))
        .and_then(|d| d.get("options"))
        .and_then(|o| o.get("home_id"))
        .and_then(toml::Value::as_str)
        .map(str::to_owned))
}
fn snapshot(data: &Path, instance: &str, key: &MasterKey, entry: &mut Entry) -> Result<Value> {
    let app = read(data, instance, key, APPLICATION_SECRET)?
        .map(|v| Application::parse(&v).map_err(Error::code))
        .transpose()?;
    let home = current_home(data, instance)?;
    let verified = entry.verified.as_ref().is_some_and(|(id, at)| {
        Some(id) == home.as_ref() && now().checked_sub(*at).is_some_and(|age| age < 300_000)
    });
    let saved = read(data, instance, key, "oauth_tokens")?
        .map(|s| Grant::from_personal_vault(&s).map_err(Error::code))
        .transpose()?;
    let marker = read(data, instance, key, ROTATION_SECRET)?;
    let fingerprint = saved
        .as_ref()
        .map(rotation_fingerprint)
        .transpose()
        .map_err(Error::code)?;
    let revoked = marker
        .as_ref()
        .zip(fingerprint.as_ref())
        .is_some_and(|(m, f)| m == &format!("revoked:{f}"));
    let ambiguous = marker
        .as_ref()
        .zip(fingerprint.as_ref())
        .is_some_and(|(m, f)| m == f);
    let mut out = json!({"setup_required":app.is_none(),"experimental":true,"state":entry.session.state(now()),"scope":SCOPE,
        "credential_present":saved.is_some(),"expires_at":saved.as_ref().map(Grant::expires_at),
        "write_allowed":saved.as_ref().is_some_and(Grant::write_allowed),"revoked":revoked,"ambiguous":ambiguous,
        "instances":instances(data)?,"selected":instance,"homes":entry.homes,"selected_home":home,"read_verified":verified});
    if let Some(app) = app {
        out["client_id"] = json!(app.client_id());
        out["redirect_uri"] = json!(REDIRECT);
    }
    Ok(out)
}
struct Vault<'a> {
    data: &'a Path,
    instance: &'a str,
    key: &'a MasterKey,
}
impl moli_netatmo::oauth::account::Vault for Vault<'_> {
    fn get(&self, name: &str) -> moli_netatmo::oauth::Result<Option<String>> {
        read(self.data, self.instance, self.key, name).map_err(|_| Error::Storage)
    }
    fn put<'a>(
        &'a self,
        name: &'a str,
        value: &'a str,
    ) -> moli_runtime::BoxFuture<'a, moli_netatmo::oauth::Result<()>> {
        Box::pin(async move {
            put(self.data, self.instance, self.key, name, value)
                .await
                .map_err(|_| Error::Storage)
        })
    }
    fn remove<'a>(
        &'a self,
        name: &'a str,
    ) -> moli_runtime::BoxFuture<'a, moli_netatmo::oauth::Result<()>> {
        Box::pin(async move {
            forget(self.data, self.instance, self.key, name)
                .await
                .map_err(|_| Error::Storage)
        })
    }
}
async fn grant(data: &Path, instance: &str, key: &MasterKey, entry: &mut Entry) -> Result<Grant> {
    entry
        .account
        .ensure(
            &Vault {
                data,
                instance,
                key,
            },
            now(),
            false,
        )
        .await
        .map_err(Error::code)
}
fn prepare_configuration(
    raw: &str,
    instance: &str,
    _app_id: &str,
    home: &str,
    enable: bool,
) -> Result<String> {
    use toml_edit::{ArrayOfTables, DocumentMut, Item, Table, value};
    let mut doc = raw.parse::<DocumentMut>().map_err(|_| "configuration")?;
    if doc.get("driver").is_none() {
        doc["driver"] = Item::ArrayOfTables(ArrayOfTables::new());
    }
    let drivers = doc["driver"]
        .as_array_of_tables_mut()
        .ok_or("configuration")?;
    if !drivers
        .iter()
        .any(|d| d.get("id").and_then(Item::as_str) == Some(instance))
    {
        let mut row = Table::new();
        row["id"] = value(instance);
        row["kind"] = value("netatmo");
        drivers.push(row);
    }
    let row = drivers
        .iter_mut()
        .find(|d| d.get("id").and_then(Item::as_str) == Some(instance))
        .ok_or("configuration")?;
    if row.get("kind").and_then(Item::as_str) != Some("netatmo") {
        return Err("configuration");
    }
    let enabled_decor = row
        .get("enabled")
        .and_then(Item::as_value)
        .map(|v| v.decor().clone());
    row["enabled"] = value(enable);
    if let Some(decor) = enabled_decor {
        *row["enabled"]
            .as_value_mut()
            .ok_or("configuration")?
            .decor_mut() = decor;
    }
    if row.get("options").is_none() {
        row["options"] = Item::Table(Table::new());
    }
    let options = row["options"].as_table_like_mut().ok_or("configuration")?;
    if options
        .get("home_id")
        .and_then(Item::as_str)
        .is_some_and(|old| old != home)
    {
        return Err("oauth_home_conflict");
    }
    if options.get("home_id").is_none() {
        options.insert("home_id", value(home));
    }

    let next = doc.to_string();

    Ok(next)
}
async fn provision(
    input: &Value,
    data: &Path,
    instance: &str,
    key: &MasterKey,
    entry: &mut Entry,
) -> Result<()> {
    if matches!(entry.session.state(now()), "pending" | "saving") {
        return Err("oauth_state");
    }
    if read(data, instance, key, "oauth_tokens")?.is_some() {
        return Err("oauth_existing_credentials");
    }
    let object = input["application"]
        .as_object()
        .filter(|v| v.len() == 4)
        .ok_or("input")?;
    let app = Application::new(
        object
            .get("client_id")
            .and_then(Value::as_str)
            .ok_or("input")?,
        object
            .get("client_secret")
            .and_then(Value::as_str)
            .ok_or("input")?,
        object.get("experimental") == Some(&json!(true)),
    )
    .map_err(Error::code)?
    .with_write(object.get("write_thermostat") == Some(&json!(true)));
    put(
        data,
        instance,
        key,
        APPLICATION_SECRET,
        &app.bundle().map_err(Error::code)?,
    )
    .await?;
    entry.homes.clear();
    entry.verified = None;

    Ok(())
}
async fn operate(
    input: &Value,
    data: &Path,
    instance: &str,
    key: &MasterKey,
    entry: &mut Entry,
) -> Result<Value> {
    let action = input["operation"].as_str().ok_or("input")?;
    if matches!(
        action,
        "provision" | "begin" | "disconnect" | "prepare_home"
    ) && input["consent"] != true
    {
        return Err("oauth_consent");
    }
    match action {
        "status" => (),
        "provision" => provision(input, data, instance, key, entry).await?,
        "begin" => {
            let url = entry
                .session
                .begin(&application(data, instance, key)?, now())
                .map_err(Error::code)?;
            let mut result = snapshot(data, instance, key, entry)?;
            result["authorization_url"] = json!(url);
            return Ok(result);
        }
        "complete" => {
            let callback = input["callback"]
                .as_str()
                .filter(|s| s.len() <= 4096)
                .ok_or("input")?;
            entry
                .session
                .complete(&application(data, instance, key)?, callback, now())
                .await
                .map_err(Error::code)?;
            save_grant(data, instance, key, entry).await?;
        }
        "check" => {
            if entry.session.state(now()) == "saving" {
                save_grant(data, instance, key, entry).await?;
            }
        }
        "cancel" => entry.session.cancel(),
        "disconnect" => {
            entry.session.cancel();
            entry.account = moli_netatmo::oauth::account::Account::default();
            entry.homes.clear();
            entry.verified = None;
            forget(data, instance, key, "oauth_tokens").await?;
            forget(data, instance, key, ROTATION_SECRET).await?;
            let mut result = snapshot(data, instance, key, entry)?;
            result["remote_revocation_required"] = json!(true);
            return Ok(result);
        }
        "homes" => {
            entry.verified = None;
            entry.homes = grant(data, instance, key, entry)
                .await?
                .homes()
                .await
                .map_err(Error::code)?;
        }
        "prepare_home" => {
            entry.verified = None;
            let id = input["home_id"].as_str().ok_or("input")?;
            if !entry.homes.iter().any(|home| home.id == id) {
                return Err("oauth_home");
            }
            grant(data, instance, key, entry)
                .await?
                .verify_home(id)
                .await
                .map_err(Error::code)?;
            let raw = std::fs::read_to_string(data.join("moli.toml")).map_err(|_| "storage")?;
            let next = prepare_configuration(
                &raw,
                instance,
                application(data, instance, key)?.client_id(),
                id,
                input["enable"].as_bool().ok_or("input")?,
            )?;
            entry.verified = Some((id.into(), now()));
            return Ok(json!({"configuration":next,"expected_configuration":raw}));
        }
        _ => return Err("input"),
    }
    snapshot(data, instance, key, entry)
}
async fn save_grant(data: &Path, instance: &str, key: &MasterKey, entry: &mut Entry) -> Result<()> {
    if entry.session.state(now()) != "saving" {
        return Err("oauth_expired");
    }
    let bundle = entry
        .session
        .grant()
        .ok_or("oauth_state")?
        .vault_bundle()
        .map_err(Error::code)?;
    put(data, instance, key, "oauth_tokens", &bundle).await?;
    forget(data, instance, key, ROTATION_SECRET).await?;
    entry.session.stored().map_err(Error::code)
}
pub fn run(input: &Value) -> Result<Value> {
    let data = Path::new(input["data"].as_str().ok_or("input")?);
    let key = MasterKey::new(input["master"].as_str().ok_or("input")?.into());
    let instance = input["instance"].as_str().unwrap_or("netatmo-energy");
    if instance.is_empty()
        || instance.len() > 100
        || !instance
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
    {
        return Err("input");
    }
    run_for(input, data, instance, &key)
}
fn run_for(input: &Value, data: &Path, instance: &str, key: &MasterKey) -> Result<Value> {
    static SESSIONS: OnceLock<Mutex<BTreeMap<(PathBuf, String), Entry>>> = OnceLock::new();
    let mut sessions = SESSIONS
        .get_or_init(|| Mutex::new(BTreeMap::new()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let id = (data.to_path_buf(), instance.to_owned());
    if sessions.len() >= 8 && !sessions.contains_key(&id) {
        sessions.retain(|_, entry| matches!(entry.session.state(now()), "pending" | "saving"));
    }
    if sessions.len() >= 8 && !sessions.contains_key(&id) {
        return Err("oauth_state");
    }
    let entry = sessions.entry(id).or_default();
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "internal")?;
    runtime.block_on(operate(input, data, instance, key, entry))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn canonical_home_selection_preserves_other_integrations_and_never_serializes_credentials() {
        let text = "# keep\n[[driver]]\nid='plug'\nkind='profile'\nenabled=false\n[driver.options]\nprofile='meross-mss310'\nhost='192.168.50.21'\n\n[[driver]]\nid='energy'\nkind='netatmo'\nenabled=false # keep flag\n[driver.options]\nhome_id='home-test'\npoll_seconds=60\ntopology_seconds=600\nmax_age_seconds=600\n";
        let next = prepare_configuration(text, "energy", "app-test", "home-test", false).unwrap();
        assert!(next.starts_with(text.split("[[driver]]\nid='energy'").next().unwrap()));
        assert!(next.contains("enabled=false # keep flag"));
        assert!(next.contains("poll_seconds=60"));
        assert!(next.contains("topology_seconds=600"));
        assert!(!next.contains("app-test"));
        assert!(!next.contains("client_secret"));
        assert!(prepare_configuration(text, "energy", "app-test", "another-home", true).is_err());
    }
    #[tokio::test]
    async fn durable_account_status_survives_a_new_session_and_does_not_return_secrets() {
        let data = std::env::temp_dir().join(format!("moli-energy-status-{}", std::process::id()));
        std::fs::create_dir_all(&data).unwrap();
        std::fs::write(data.join("moli.toml"),"[[driver]]\nid='energy'\nkind='netatmo'\nenabled=false\n[driver.options]\nhome_id='home-test'\n").unwrap();
        let key = MasterKey::new("22".repeat(32));
        let app = Application::new("app-test", "synthetic-secret", true).unwrap();
        put(
            &data,
            "energy",
            &key,
            APPLICATION_SECRET,
            &app.bundle().unwrap(),
        )
        .await
        .unwrap();
        put(&data,"energy",&key,"oauth_tokens",&json!({"access_token":"access-test","refresh_token":"refresh-test","expires_at":now()/1000+3600,"write":false}).to_string()).await.unwrap();
        let restored = snapshot(&data, "energy", &key, &mut Entry::default()).unwrap();
        assert_eq!(restored["credential_present"], true);
        assert_eq!(restored["setup_required"], false);
        assert_eq!(restored["selected_home"], "home-test");
        assert_eq!(restored["state"], "idle");
        for secret in ["synthetic-secret", "access-test", "refresh-test"] {
            assert!(!restored.to_string().contains(secret));
        }
        std::fs::remove_dir_all(data).unwrap();
    }
}
