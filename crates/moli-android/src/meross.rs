//! Android provisioning adapter over the portable Meross protocol and Moli vault.
//! No Android equipment inventory, scheduler or command transport.
use moli_profile::meross_cloud::{self as cloud, Client, Credentials};
use moli_runtime::{MasterKey, forget_secret, read_secret, set_secret};
use serde_json::{Value, json};
use std::path::Path;

const SESSION: &str = "meross-account/session";
type Result<T> = std::result::Result<T, &'static str>;

fn credentials(path: &Path, master: &MasterKey) -> Result<Credentials> {
    let saved = read_secret(path, master, SESSION)
        .map_err(|_| "storage")?
        .ok_or("account_expired")?;
    serde_json::from_str(&saved).map_err(|_| "storage")
}

pub(crate) fn run(input: &Value) -> Result<Value> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "internal")?;
    runtime.block_on(async {
        tokio::time::timeout(std::time::Duration::from_secs(28), operate(input))
            .await
            .map_err(|_| "account_network")?
    })
}

async fn operate(input: &Value) -> Result<Value> {
    let path = Path::new(input["data"].as_str().ok_or("input")?).join("secrets.enc");
    let master = MasterKey::new(input["master"].as_str().ok_or("input")?.into());
    match input["operation"].as_str().ok_or("input")? {
        "status" => Ok(
            json!({"connected":read_secret(&path, &master, SESSION).map_err(|_| "storage")?.is_some()}),
        ),
        "signin" => {
            let region = input["region"].as_str().ok_or("account_input")?;
            let client = Client::new(region)?;
            let c = client
                .signin(
                    input["email"].as_str().ok_or("account_input")?,
                    input["password"].as_str().ok_or("account_input")?,
                    region,
                    input["otp"].as_str().unwrap_or(""),
                )
                .await?;
            set_secret(
                &path,
                &master,
                SESSION,
                &serde_json::to_string(&c).map_err(|_| "internal")?,
            )
            .await
            .map_err(|_| "storage")?;
            // Only display data crosses JNI. The key and token stay in the vault.
            let items = client.devices(&c).await?;
            Ok(json!({"connected":true,"items":items}))
        }
        "inventory" => {
            let c = credentials(&path, &master)?;
            let items = Client::saved(&c)?.devices(&c).await?;
            Ok(json!({"connected":true,"items":items}))
        }
        "forget" => {
            let c = credentials(&path, &master).ok();
            forget_secret(&path, &master, SESSION)
                .await
                .map_err(|_| "storage")?;
            if let Some(c) = c {
                let _ = Client::saved(&c)?.logout(&c).await;
            }
            Ok(json!({"connected":false,"items":[]}))
        }
        "prepare" => {
            let text = input["text"].as_str().ok_or("input")?;
            let id = input["id"].as_str().ok_or("input")?;
            let host = input["options"]["host"].as_str().ok_or("account_local")?;
            if !host
                .parse::<std::net::Ipv4Addr>()
                .is_ok_and(|ip| ip.is_private())
            {
                return Err("account_local");
            }
            let port = input["options"]["port"]
                .as_str()
                .filter(|s| !s.is_empty())
                .unwrap_or("80")
                .parse::<u16>()
                .ok()
                .filter(|n| *n != 0)
                .ok_or("configuration")?;
            let selected = input["account_uuid"].as_str().filter(|s| !s.is_empty());
            let manual = input["key"].as_str().filter(|s| !s.is_empty());
            let key = if let Some(key) = manual {
                key.to_owned()
            } else if selected.is_some() {
                credentials(&path, &master)?.key
            } else {
                read_secret(&path, &master, &format!("{id}/key"))
                    .map_err(|_| "storage")?
                    .ok_or("account_key")?
            };
            if !cloud::uuid(&key) {
                return Err("account_key");
            }
            let uuid = moli_profile::mss310_identity(host, port, &key)
                .await
                .map_err(|e| moli_profile::mss310_identity_error(&e))?;
            if selected.is_some_and(|id| !id.eq_ignore_ascii_case(&uuid)) {
                return Err("account_uuid_different");
            }
            let (candidate, id) = prepare_configuration(text, input, &uuid)?;
            // A verified key is useful even if the subsequent TOML transaction
            // is interrupted. It is never written to the transaction's undo log.
            set_secret(&path, &master, &format!("{id}/key"), &key)
                .await
                .map_err(|_| "storage")?;
            Ok(json!({"text":candidate,"id":id,"uuid":uuid}))
        }
        _ => Err("input"),
    }
}

fn prepare_configuration(text: &str, input: &Value, uuid: &str) -> Result<(String, String)> {
    let catalogue = super::configuration::catalogue(text)?;
    let packages = catalogue["packages"].as_array().ok_or("catalogue")?;
    let package = packages
        .iter()
        .find(|p| p["manifest"]["integration"]["id"] == "meross-mss310")
        .ok_or("catalogue")?;
    let instances = package["instances"].as_array().ok_or("configuration")?;
    let requested = input["id"].as_str().ok_or("input")?;
    if instances.iter().any(|i| {
        i["id"] == requested
            && i["vars"]["expected_uuid"]
                .as_str()
                .is_none_or(|old| !old.eq_ignore_ascii_case(uuid))
    }) {
        return Err("account_uuid_different");
    }
    // Re-associating or renaming a UUID keeps its instance, history and rules.
    let existing = instances.iter().find(|i| {
        i["vars"]["expected_uuid"]
            .as_str()
            .is_some_and(|old| old.eq_ignore_ascii_case(uuid))
    });
    let id = existing.and_then(|i| i["id"].as_str()).unwrap_or(requested);
    let candidate = super::configuration::prepare(
        &json!({"text":text,"profile":"meross-mss310","id":id,"options":input["options"],"vars":{"expected_uuid":uuid}}),
    )?;
    Ok((candidate, id.into()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn four_instances_keep_identity_and_data_across_reassociation_and_rename() {
        let mut text = "# retained\n[history]\nretention_days=30\n".to_owned();
        for n in 1..=4 {
            let input = json!({"id":format!("plug-{n}"),"options":{"host":format!("192.0.2.{n}"),"port":"80","name":format!("Plug {n}")}});
            text = prepare_configuration(&text, &input, &format!("{n:032x}"))
                .unwrap()
                .0;
        }
        let renamed = prepare_configuration(
            &text,
            &json!({"id":"new-name","options":{"host":"192.0.2.10","port":"80","name":"Renamed"}}),
            &format!("{:032x}", 1),
        )
        .unwrap();
        assert_eq!(renamed.1, "plug-1");
        let config: toml::Value = toml::from_str(&renamed.0).unwrap();
        assert_eq!(config["driver"].as_array().unwrap().len(), 4);
        assert_eq!(config["driver"][0]["id"].as_str(), Some("plug-1"));
        assert_eq!(
            config["driver"][0]["options"]["name"].as_str(),
            Some("Renamed")
        );
        assert_eq!(config["history"]["retention_days"].as_integer(), Some(30));
        assert!(
            prepare_configuration(
                &text,
                &json!({"id":"plug-1","options":{}}),
                &format!("{:032x}", 2)
            )
            .is_err()
        );
        assert!(!renamed.0.contains("key"));
    }
}
