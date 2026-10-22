//! Stopped-host account provisioning. No credentials cross back through JNI.
use moli_profile::meross_cloud::{self as cloud, Client, Credentials, Device};
use moli_runtime::{MasterKey, forget_secret, read_secret, set_secret};
use ring::rand::{SecureRandom as _, SystemRandom};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

const SESSION: &str = "android-meross-account/session";
type Result<T> = std::result::Result<T, &'static str>;
struct Store {
    data: PathBuf,
    master: MasterKey,
}
impl Store {
    fn path(&self) -> PathBuf {
        self.data.join("secrets.enc")
    }
    fn read(&self) -> Result<Option<Value>> {
        read_secret(&self.path(), &self.master, SESSION)
            .map_err(|_| "storage")?
            .map(|s| {
                if s.len() > 65_536 {
                    return Err("storage");
                }
                serde_json::from_str(&s).map_err(|_| "storage")
            })
            .transpose()
    }
    async fn save(&self, c: &Credentials, items: &[Device], received: bool) -> Result<Value> {
        let state = json!({"credentials":{"host":c.host,"key":c.key,"token":c.token},"items":items,"cached_ms":if received {now()?}else{0}});
        set_secret(&self.path(), &self.master, SESSION, &state.to_string())
            .await
            .map_err(|_| "storage")?;
        Ok(view(Some(&state)))
    }
    async fn forget(&self) -> Result<()> {
        forget_secret(&self.path(), &self.master, SESSION)
            .await
            .map_err(|_| "storage")?;
        Ok(())
    }
    fn budget(&self, action: &str) -> Result<()> {
        let path = self.data.join("android-meross-account-budget.json");
        let mut value = read_json(&path, 16_384)?.unwrap_or_else(|| json!({}));
        let time = now()?;
        let mut history = value[action].as_array().cloned().unwrap_or_default();
        if history.iter().any(|n| n.as_u64().is_none_or(|t| t > time)) {
            return Err("account_rate");
        }
        history.retain(|n| {
            n.as_u64()
                .is_some_and(|t| time.saturating_sub(t) < 3_600_000)
        });
        let (gap, limit) = match action {
            "signin" => (10_000, 10),
            "inventory" => (60_000, 20),
            "associate" => (1_000, 120),
            "identify" => (10_000, 6),
            _ => return Err("input"),
        };
        if history.len() >= limit
            || history
                .last()
                .and_then(Value::as_u64)
                .is_some_and(|t| time - t < gap)
        {
            return Err("account_rate");
        }
        history.push(json!(time));
        value[action] = json!(history);
        write(&path, &value)?;
        Ok(())
    }
}
fn now() -> Result<u64> {
    u64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| "account_clock")?
            .as_millis(),
    )
    .map_err(|_| "account_clock")
}
fn read_json(path: &Path, limit: u64) -> Result<Option<Value>> {
    use std::io::Read as _;
    let file = match std::fs::File::open(path) {
        Ok(file) => file,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err("storage"),
    };
    let mut bytes = vec![];
    file.take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "storage")?;
    if bytes.len() as u64 > limit {
        return Err("storage");
    }
    serde_json::from_slice(&bytes)
        .map(Some)
        .map_err(|_| "storage")
}
fn write(path: &Path, value: &Value) -> Result<()> {
    let parent = path.parent().ok_or("storage")?;
    std::fs::create_dir_all(parent).map_err(|_| "storage")?;
    let temporary = path.with_extension("tmp");
    std::fs::write(&temporary, value.to_string()).map_err(|_| "storage")?;
    std::fs::rename(temporary, path).map_err(|_| "storage")
}
fn saved_credentials(value: &Value) -> Result<Credentials> {
    let c = &value["credentials"];
    cloud::credentials(
        &json!({"key":c["key"],"token":c["token"],"domain":format!("https://{}",c["host"].as_str().ok_or("storage")?)}),
        "",
    )
}
fn view(value: Option<&Value>) -> Value {
    value.map_or_else(
        || json!({"state":"disconnected","items":[]}),
        |v| json!({"state":"connected","items":v["items"],"cached_ms":v["cached_ms"]}),
    )
}
pub fn run(input: &Value) -> Result<Value> {
    let store = Store {
        data: PathBuf::from(input["data"].as_str().ok_or("input")?),
        master: MasterKey::new(input["master"].as_str().ok_or("input")?.into()),
    };
    std::fs::create_dir_all(&store.data).map_err(|_| "storage")?;
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "internal")?;
    runtime.block_on(async {
        tokio::time::timeout(std::time::Duration::from_secs(28), operate(&store, input))
            .await
            .map_err(|_| "account_network")?
    })
}
fn status(store: &Store) -> Result<Value> {
    let mut result = view(store.read()?.as_ref());
    let budget = read_json(
        &store.data.join("android-meross-account-budget.json"),
        16_384,
    )?
    .unwrap_or_else(|| json!({}));
    let time = now()?;
    for (name, gap, limit) in [
        ("signin", 10_000, 10),
        ("inventory", 60_000, 20),
        ("identify", 10_000, 6),
    ] {
        let history: Vec<u64> = budget[name]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_u64)
            .filter(|t| time.saturating_sub(*t) < 3_600_000)
            .collect();
        let wait = if history.len() >= limit {
            history
                .first()
                .copied()
                .unwrap_or(time)
                .saturating_add(3_600_000)
                .saturating_sub(time)
        } else {
            history
                .last()
                .copied()
                .unwrap_or(0)
                .saturating_add(gap)
                .saturating_sub(time)
        };
        result[format!("{name}_wait_ms")] = json!(wait);
        result[format!("{name}_used")] = json!(history.len());
    }
    Ok(result)
}
async fn operate(store: &Store, input: &Value) -> Result<Value> {
    let action = input["action"].as_str().ok_or("input")?;
    if action == "status" {
        return status(store);
    }
    if action == "signin" {
        store.budget(action)?;
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
        store.save(&c, &[], false).await?;
        let fetched = if store.budget("inventory").is_ok() {
            client.devices(&c).await
        } else {
            Err("account_rate")
        };
        return match fetched {
            Ok(items) => store.save(&c, &items, true).await,
            Err("account_expired" | "account_auth") => {
                store.forget().await?;
                Ok(json!({"state":"expired","items":[]}))
            }
            Err(code) => {
                let mut result = view(store.read()?.as_ref());
                result["warning"] = json!(code);
                Ok(result)
            }
        };
    }
    let session = store.read()?;
    if action == "forget" {
        store.forget().await?; // Local deletion succeeds even when server revocation is unavailable.
        let mut revoked = false;
        if input["revoke"] == true
            && let Some(s) = session.as_ref()
            && let Ok(c) = saved_credentials(s)
            && let Ok(client) = Client::saved(&c)
        {
            revoked = client.logout(&c).await.is_ok();
        }
        return Ok(json!({"state":"disconnected","items":[],"revoked":revoked}));
    }
    let session = session.ok_or("account_expired")?;
    let c = saved_credentials(&session)?;
    match action {
        "inventory" => {
            store.budget(action)?;
            let client = Client::saved(&c)?;
            match client.devices(&c).await {
                Ok(items) => store.save(&c, &items, true).await,
                Err("account_expired" | "account_auth") => {
                    store.forget().await?;
                    Ok(json!({"state":"expired","items":[]}))
                }
                Err(code) => Err(code),
            }
        }
        "identify" => identify(store, input, &session, &c).await,
        "associate" => {
            store.budget(action)?;
            let id = input["uuid"]
                .as_str()
                .filter(|s| cloud::uuid(s))
                .ok_or("account_input")?
                .to_ascii_lowercase();
            let items: Vec<Device> =
                serde_json::from_value(session["items"].clone()).map_err(|_| "storage")?;
            let device = items
                .iter()
                .find(|d| d.uuid == id)
                .ok_or("account_identity")?;
            let host = input["host"].as_str().ok_or("equipment_ip")?;
            if !host
                .parse::<std::net::Ipv4Addr>()
                .is_ok_and(|ip| ip.is_private())
            {
                return Err("equipment_ip");
            }
            let port = u16::try_from(input["port"].as_u64().ok_or("equipment_port")?)
                .ok()
                .filter(|n| *n != 0)
                .ok_or("equipment_port")?;
            let found = moli_profile::mss310_identity(host, port, &c.key)
                .await
                .map_err(|_| "account_local")?;
            if found != id {
                return Err("account_identity");
            }
            associate(store, input, device, &c).await
        }
        _ => Err("input"),
    }
}
async fn identify(store: &Store, input: &Value, session: &Value, c: &Credentials) -> Result<Value> {
    let candidates = candidate_hosts(input)?;
    store.budget("identify")?;
    let items: Vec<Device> =
        serde_json::from_value(session["items"].clone()).map_err(|_| "storage")?;
    let mut results = Vec::new();
    for host in candidates {
        let found = tokio::time::timeout(
            std::time::Duration::from_millis(2500),
            moli_profile::mss310_identity(&host, 80, &c.key),
        )
        .await;
        results.push(match found {
            Ok(Ok(uuid)) if items.iter().any(|d| d.uuid == uuid) => {
                json!({"host":host,"uuid":uuid,"result":"matched"})
            }
            Ok(Ok(_)) => json!({"host":host,"result":"different"}),
            _ => json!({"host":host,"result":"unavailable"}),
        });
    }
    Ok(json!({"state":"identified","results":results}))
}
// An explicit, bounded list, never a subnet scan or an IP/model inference.
fn candidate_hosts(input: &Value) -> Result<Vec<String>> {
    let hosts = input["candidates"]
        .as_array()
        .filter(|h| !h.is_empty() && h.len() <= 8)
        .ok_or("account_input")?;
    let mut checked = Vec::new();
    for host in hosts {
        let host = host
            .as_str()
            .filter(|s| {
                s.parse::<std::net::Ipv4Addr>()
                    .is_ok_and(|ip| ip.is_private())
            })
            .ok_or("equipment_ip")?;
        if checked.iter().any(|s| s == host) {
            return Err("account_input");
        }
        checked.push(host.to_owned());
    }
    Ok(checked)
}
async fn associate(
    store: &Store,
    input: &Value,
    device: &Device,
    c: &Credentials,
) -> Result<Value> {
    let path = store.data.join("android-equipment.json");
    let mut config = read_json(&path, 16_384)?.unwrap_or_else(|| json!({}));
    config = super::equipment::validate(&config)?;
    let mut rows = config["meross"].as_array().cloned().unwrap_or_default();
    let index = rows.iter().position(|v| v["uuid"] == device.uuid);
    if rows
        .iter()
        .enumerate()
        .any(|(i, v)| Some(i) != index && v["host"] == input["host"] && v["port"] == input["port"])
    {
        return Err("meross_duplicate");
    }
    if index.is_none() && rows.len() >= 8 {
        return Err("meross_settings");
    }
    let id = if let Some(index) = index {
        rows[index]["id"]
            .as_str()
            .ok_or("meross_settings")?
            .to_owned()
    } else {
        let mut raw = [0u8; 16];
        SystemRandom::new().fill(&mut raw).map_err(|_| "internal")?;
        {
            use std::fmt::Write as _;
            raw.iter().fold(String::with_capacity(32), |mut out, b| {
                let _ = write!(out, "{b:02x}");
                out
            })
        }
    };
    let name = input["name"]
        .as_str()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or(&device.name);
    let row = json!({"id":id,"uuid":device.uuid,"name":name,"host":input["host"],"port":input["port"],"enabled":input["enabled"]==true});
    if let Some(i) = index {
        rows[i] = row;
    } else {
        rows.push(row);
    }
    config["meross"] = json!(rows);
    let checked = super::equipment::validate(&config)?;
    set_secret(
        &store.path(),
        &store.master,
        &format!("android-meross-{id}/key"),
        &c.key,
    )
    .await
    .map_err(|_| "storage")?;
    write(&path, &checked)?;
    Ok(json!({"state":"associated","id":id,"uuid":device.uuid}))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn candidates_are_explicit_bounded_private_and_unique() {
        assert_eq!(
            candidate_hosts(&json!({"candidates":["192.168.1.20","10.0.0.2"]}))
                .unwrap()
                .len(),
            2
        );
        for input in [
            json!({"candidates":[]}),
            json!({"candidates":["8.8.8.8"]}),
            json!({"candidates":["127.0.0.1"]}),
            json!({"candidates":["example.org"]}),
            json!({"candidates":["192.168.1.20","192.168.1.20"]}),
            json!({"candidates":vec!["192.168.1.20";9]}),
        ] {
            assert!(candidate_hosts(&input).is_err());
        }
    }
    #[tokio::test]
    async fn encrypted_session_safe_view_uuid_binding_and_forget_keep_local_keys() {
        let root = std::env::temp_dir().join(format!("moli-account-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let store = Store {
            data: root.clone(),
            master: MasterKey::new("a".repeat(64)),
        };
        let c = Credentials {
            host: "iotx-eu.meross.com".into(),
            key: "b".repeat(32),
            token: "invented-session-token".into(),
        };
        let device = Device {
            uuid: format!("{:032x}", 1),
            name: "Invented plug".into(),
            model: "MSS310".into(),
            hardware: "6.0.0".into(),
        };
        let safe = store
            .save(&c, std::slice::from_ref(&device), true)
            .await
            .unwrap()
            .to_string();
        assert!(!safe.contains(&c.key) && !safe.contains(&c.token));
        // A fresh Store/run reconstructs session and inventory from the encrypted file, without network.
        let input = json!({"data":root,"master":"a".repeat(64),"action":"status"});
        let reopened = std::thread::spawn(move || run(&input))
            .join()
            .unwrap()
            .unwrap();
        assert_eq!(reopened["state"], "connected");
        assert_eq!(reopened["items"][0]["uuid"], device.uuid);
        assert!(!reopened.to_string().contains(&c.key));
        assert!(!reopened.to_string().contains(&c.token));
        let bytes = std::fs::read(store.path()).unwrap();
        assert!(
            !bytes
                .windows(c.token.len())
                .any(|s| s == c.token.as_bytes())
        );
        let input =
            json!({"host":"192.168.1.20","port":80,"name":"Invented local name","enabled":true});
        let bound = associate(&store, &input, &device, &c).await.unwrap();
        let id = bound["id"].as_str().unwrap();
        let config: Value =
            serde_json::from_slice(&std::fs::read(root.join("android-equipment.json")).unwrap())
                .unwrap();
        assert_eq!(config["meross"][0]["uuid"], device.uuid);
        assert!(!config.to_string().contains(&c.key));
        let driver = super::super::equipment::drivers(&config).unwrap();
        assert_eq!(driver[0]["options"]["vars"]["expected_uuid"], device.uuid);
        store.forget().await.unwrap();
        assert!(store.read().unwrap().is_none());
        assert_eq!(
            read_secret(
                &store.path(),
                &store.master,
                &format!("android-meross-{id}/key")
            )
            .unwrap()
            .as_deref(),
            Some(c.key.as_str())
        );
        let wrong = Store {
            data: root.clone(),
            master: MasterKey::new("c".repeat(64)),
        };
        assert!(wrong.read().is_err());
        assert!(store.budget("signin").is_ok());
        assert_eq!(store.budget("signin").unwrap_err(), "account_rate");
        let _ = std::fs::remove_dir_all(root);
    }
}
