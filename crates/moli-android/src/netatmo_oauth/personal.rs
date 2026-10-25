//! Personal native adapter. All I/O is serialized by `EngineHost` with the engine
//! stopped. Only private provisioning/complete actions accept sensitive input.
use super::{Result, instances, now};
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
    rotation: Option<Grant>,
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
    let mut out = json!({"setup_required":app.is_none(),"experimental":true,"state":entry.session.state(now()),"scope":SCOPE,
        "credential_present":read(data,instance,key,"oauth_tokens")?.is_some(),"instances":instances(data)?,"selected":instance,
        "homes":entry.homes,"selected_home":home,"read_verified":verified});
    if let Some(app) = app {
        out["client_id"] = json!(app.client_id());
        out["redirect_uri"] = json!(REDIRECT);
    }
    Ok(out)
}
async fn grant(data: &Path, instance: &str, key: &MasterKey, entry: &mut Entry) -> Result<Grant> {
    if let Some(pending) = entry.rotation.as_ref() {
        put(
            data,
            instance,
            key,
            "oauth_tokens",
            &pending.vault_bundle().map_err(Error::code)?,
        )
        .await?;
        entry.rotation = None;
    }
    let previous = Grant::from_personal_vault(
        &read(data, instance, key, "oauth_tokens")?.ok_or("oauth_setup")?,
    )
    .map_err(Error::code)?;
    let marker = rotation_fingerprint(&previous).map_err(Error::code)?;
    if let Some(old) = read(data, instance, key, ROTATION_SECRET)? {
        if old == marker {
            return Err("oauth_ambiguous");
        }
        forget(data, instance, key, ROTATION_SECRET).await?;
    }
    if previous.expires_at() > now() / 1000 + 60 {
        return Ok(previous);
    }
    put(data, instance, key, ROTATION_SECRET, &marker).await?;
    let app = application(data, instance, key)?;
    let renewed = match app.renew(&previous, now()).await {
        Ok(grant) => grant,
        Err(moli_netatmo::oauth::Error::RateLimited(_)) => {
            forget(data, instance, key, ROTATION_SECRET).await?;
            return Err("rate_limit");
        }
        Err(e) => return Err(e.code()),
    };
    entry.rotation = Some(renewed);
    put(
        data,
        instance,
        key,
        "oauth_tokens",
        &entry
            .rotation
            .as_ref()
            .ok_or("oauth_state")?
            .vault_bundle()
            .map_err(Error::code)?,
    )
    .await?;
    entry.rotation = None;
    forget(data, instance, key, ROTATION_SECRET).await?;
    Grant::from_personal_vault(&read(data, instance, key, "oauth_tokens")?.ok_or("oauth_setup")?)
        .map_err(Error::code)
}
fn prepare_configuration(
    raw: &str,
    instance: &str,
    app_id: &str,
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
    row["enabled"] = value(enable);
    if row.get("options").is_none() {
        row["options"] = Item::Table(Table::new());
    }
    let options = row["options"].as_table_mut().ok_or("configuration")?;
    if options
        .get("home_id")
        .and_then(Item::as_str)
        .is_some_and(|old| old != home)
    {
        return Err("oauth_home_conflict");
    }
    options["home_id"] = value(home);
    options["client_id"] = value(app_id);
    let next = doc.to_string();
    moli_os::validate_configuration(&next).map_err(|_| "configuration")?;
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
        .filter(|v| v.len() == 3)
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
    .map_err(Error::code)?;
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
    let action = input["action"].as_str().ok_or("input")?;
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
            entry.rotation = None;
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
pub(super) fn run(input: &Value, data: &Path, instance: &str, key: &MasterKey) -> Result<Value> {
    static SESSIONS: OnceLock<Mutex<BTreeMap<(PathBuf, String), Entry>>> = OnceLock::new();
    let mut sessions = SESSIONS
        .get_or_init(|| Mutex::new(BTreeMap::new()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let id = (data.to_path_buf(), instance.to_owned());
    if sessions.len() >= 8 && !sessions.contains_key(&id) {
        sessions.retain(|_, entry| {
            matches!(entry.session.state(now()), "pending" | "saving") || entry.rotation.is_some()
        });
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
    fn selecting_existing_home_preserves_other_driver_bytes_and_unknown_options() {
        let raw = "# keep header\n[[driver]]\nid='other'\nkind='demo'\nenabled=false\n[driver.options]\nopaque='preserve'\n\n[[driver]]\nid='energy-test'\nkind='netatmo'\nenabled=false\n[driver.options]\nhome_id='home-test'\npoll_seconds=240\nmax_age_seconds=600\n# keep tail\n";
        let next =
            prepare_configuration(raw, "energy-test", "app-test", "home-test", true).unwrap();
        assert!(next.starts_with(raw.split("[[driver]]\nid='energy-test'").next().unwrap()));
        assert!(next.contains("# keep tail"));
        let old: toml::Value = toml::from_str(raw).unwrap();
        let value: toml::Value = toml::from_str(&next).unwrap();
        assert_eq!(old["driver"][0], value["driver"][0]);
        assert_eq!(
            value["driver"][1]["options"]["poll_seconds"].as_integer(),
            Some(240)
        );
        assert_eq!(
            prepare_configuration(raw, "energy-test", "app-test", "different-home", true),
            Err("oauth_home_conflict")
        );
    }
    #[test]
    fn preparing_new_instance_is_explicit_and_does_not_enable_by_default() {
        let raw = "# exact initial\n[server]\ndata_dir='data'\n";
        let next =
            prepare_configuration(raw, "android-netatmo", "app-test", "home-test", false).unwrap();
        assert!(next.starts_with(raw));
        let value: toml::Value = toml::from_str(&next).unwrap();
        assert_eq!(value["driver"][0]["enabled"].as_bool(), Some(false));
    }
    #[test]
    fn personal_provision_is_encrypted_and_preserves_all_existing_configuration() {
        let fixture = super::super::tests::Fixture::new();
        let before = std::fs::read(fixture.root.join("moli.toml")).unwrap();
        let mut input = fixture.request("provision");
        input["application"] = json!({"client_id":"personal-test","client_secret":"synthetic-private-application","experimental":true});
        let output = super::super::run(&input).unwrap();
        assert_eq!(output["setup_required"], false);
        assert_eq!(output["redirect_uri"], REDIRECT);
        assert!(!output.to_string().contains("synthetic-private"));
        assert!(
            !String::from_utf8_lossy(&std::fs::read(fixture.root.join("secrets.enc")).unwrap())
                .contains("synthetic-private")
        );
        assert_eq!(
            before,
            std::fs::read(fixture.root.join("moli.toml")).unwrap()
        );
        let mut begin = fixture.request("begin");
        begin["consent"] = json!(false);
        assert_eq!(super::super::run(&begin), Err("oauth_consent"));
        let result = super::super::run(&fixture.request("begin")).unwrap();
        assert!(
            result["authorization_url"]
                .as_str()
                .unwrap()
                .starts_with("https://api.netatmo.com/oauth2/authorize?")
        );
        super::super::run(&fixture.request("cancel")).unwrap();
    }
}
