//! Explicit local destinations for the Android read-only equipment form.
use serde_json::{Value, json};

fn host(value: &str) -> Result<String, &'static str> {
    let ip = value
        .parse::<std::net::Ipv4Addr>()
        .map_err(|_| "equipment_ip")?;
    if !ip.is_private() {
        return Err("equipment_ip");
    }
    Ok(ip.to_string())
}

fn port(value: u64) -> Result<u16, &'static str> {
    u16::try_from(value)
        .ok()
        .filter(|p| *p != 0)
        .ok_or("equipment_port")
}

fn path(value: &str) -> Result<&str, &'static str> {
    if value.len() > 256
        || !value.starts_with('/')
        || value.starts_with("//")
        || value.split('/').any(|s| s == "." || s == "..")
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"/._-".contains(&b))
    {
        return Err("equipment_path");
    }
    Ok(value)
}

pub fn validate(input: &Value) -> Result<Value, &'static str> {
    if !input.is_object() {
        return Err("configuration");
    }
    let mut result = json!({});
    if !input["printer"].is_null() {
        let printer = &input["printer"];
        let endpoint = path(printer["path"].as_str().ok_or("equipment_path")?)?;
        if !matches!(endpoint, "/ipp" | "/ipp/print" | "/ipp/printer")
            && !endpoint
                .strip_prefix("/printers/")
                .is_some_and(|queue| !queue.is_empty() && !queue.contains('/'))
        {
            return Err("equipment_path");
        }
        result["printer"] = json!({"host":host(printer["host"].as_str().ok_or("equipment_ip")?)?,
            "port":port(printer["port"].as_u64().ok_or("equipment_port")?)?,
            "path":endpoint});
    }
    if !input["gateway"].is_null() {
        let location = input["gateway"]["location"]
            .as_str()
            .ok_or("equipment_url")?;
        if location.len() > 320 {
            return Err("equipment_url");
        }
        let rest = location.strip_prefix("http://").ok_or("equipment_url")?;
        let (authority, tail) = rest.split_once('/').ok_or("equipment_url")?;
        let (address, number) = match authority.split_once(':') {
            Some((address, number)) => (
                address,
                number.parse::<u64>().map_err(|_| "equipment_port")?,
            ),
            None => (authority, 80),
        };
        let address = host(address)?;
        let number = port(number)?;
        let tail = format!("/{tail}");
        path(&tail)?;
        if !tail.to_ascii_lowercase().ends_with(".xml") {
            return Err("equipment_url");
        }
        result["gateway"] = json!({"location":format!("http://{address}:{number}{tail}")});
    }
    if let Some(entries) = input.get("meross") {
        result["meross"] = meross(entries)?;
    }
    Ok(result)
}

fn meross(entries: &Value) -> Result<Value, &'static str> {
    let entries = entries
        .as_array()
        .filter(|a| a.len() <= 8)
        .ok_or("meross_settings")?;
    let mut ids = std::collections::HashSet::new();
    let mut uuids = std::collections::HashSet::new();
    let mut addresses = std::collections::HashSet::new();
    let mut checked = vec![];
    for entry in entries {
        let id = entry["id"]
            .as_str()
            .filter(|s| valid_id(s))
            .ok_or("meross_settings")?;
        let name = entry["name"]
            .as_str()
            .filter(|s| !s.trim().is_empty() && s.len() <= 160 && !s.chars().any(char::is_control))
            .ok_or("meross_settings")?;
        let ip = host(entry["host"].as_str().ok_or("equipment_ip")?)?;
        let number = port(match entry.get("port") {
            Some(v) => v.as_u64().ok_or("equipment_port")?,
            None => 80,
        })?;
        if !ids.insert(id) || !addresses.insert((ip.clone(), number)) {
            return Err("meross_duplicate");
        }
        checked.push(json!({"id":id,"name":name.trim(),"host":ip,"port":number,"enabled":entry["enabled"].as_bool().unwrap_or(false)}));
        if let Some(uuid) = entry.get("uuid") {
            let uuid = uuid
                .as_str()
                .filter(|s| valid_id(s))
                .ok_or("meross_settings")?;
            if !uuids.insert(uuid) {
                return Err("meross_duplicate");
            }
            checked.last_mut().ok_or("meross_settings")?["uuid"] = json!(uuid);
        }
    }
    Ok(json!(checked))
}

pub fn drivers(input: &Value) -> Result<Vec<Value>, &'static str> {
    let checked = validate(input)?;
    let mut result = vec![];
    if checked["printer"].is_object() {
        let mut options = checked["printer"].clone();
        options["read_only"] = json!(true);
        result.push(json!({"id":"android-printer","kind":"ipp","options":options}));
    }
    if checked["gateway"].is_object() {
        result.push(json!({"id":"android-gateway","kind":"igd","options":{
            "location":checked["gateway"]["location"],"discovery":false}}));
    }
    if let Some(entries) = checked["meross"].as_array() {
        for entry in entries.iter().filter(|e| e["enabled"] == true) {
            let mut driver = json!({"id":format!("android-meross-{}",entry["id"].as_str().ok_or("meross_settings")?),"kind":"profile","options":{
                "profile":"meross-mss310","host":entry["host"],"port":entry["port"],"name":entry["name"],"discovery":false}});
            if entry["uuid"].is_string() {
                driver["options"]["vars"] = json!({"expected_uuid":entry["uuid"]});
            }
            result.push(driver);
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn equipment_is_local_explicit_and_read_only_even_if_flags_are_supplied() {
        let input = json!({"printer":{"host":"192.168.1.20","port":631,"path":"/ipp/print","read_only":false},
            "gateway":{"location":"http://192.168.1.1:5000/rootDesc.xml","discovery":true}});
        let ds = drivers(&input).unwrap();
        assert_eq!(ds[0]["kind"], "ipp");
        assert_eq!(ds[0]["options"]["read_only"], true);
        assert_eq!(ds[1]["options"]["discovery"], false);
        assert_eq!(drivers(&json!({})).unwrap().len(), 0);
        for address in [
            "127.0.0.1",
            "example.invalid",
            "8.8.8.8",
            "169.254.169.254",
            "239.255.255.250",
            "::1",
            "user@example.com",
        ] {
            let mut bad = input.clone();
            bad["printer"]["host"] = json!(address);
            assert!(drivers(&bad).is_err());
        }
        for bad_path in [
            "/reset",
            "//evil.invalid/x",
            "/../reset",
            "/status?reset=1",
            "/x#fragment",
            "/x\r\nInjected",
        ] {
            let mut bad = input.clone();
            bad["printer"]["path"] = json!(bad_path);
            assert!(drivers(&bad).is_err());
        }
        for bad_url in [
            "https://192.168.1.1/x",
            "http://8.8.8.8/x",
            "http://user:pass@192.168.1.1/x",
            "http://192.168.1.1/x?reset=1",
            "http://192.168.1.1/reboot",
        ] {
            assert!(drivers(&json!({"gateway":{"location":bad_url}})).is_err());
        }
    }
}

fn valid_id(id: &str) -> bool {
    id.len() == 32
        && id
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

/// Values go directly into the existing portable encrypted vault, never config.
/// Java serializes this operation with engine lifetime; the JNI guard also rejects a live engine.
pub fn store_keys(input: &Value) -> Result<Value, &'static str> {
    use moli_runtime::{MasterKey, has_secret, set_secret};
    let data = std::path::Path::new(input["data"].as_str().ok_or("input")?);
    let master = MasterKey::new(input["master"].as_str().ok_or("input")?.to_owned());
    let updates = input["keys"]
        .as_array()
        .filter(|a| a.len() <= 8)
        .ok_or("meross_key")?;
    let mut checked = vec![];
    let vault = data.join("secrets.enc");
    for update in updates {
        let id = update["id"]
            .as_str()
            .filter(|s| valid_id(s))
            .ok_or("meross_key")?;
        let value = update["key"].as_str().ok_or("meross_key")?;
        if !value.is_empty() && (value.len() != 32 || !value.bytes().all(|b| b.is_ascii_hexdigit()))
        {
            return Err("meross_key");
        }
        let name = format!("android-meross-{id}/key");
        if value.is_empty()
            && update["required"] == true
            && !has_secret(&vault, &master, &name).map_err(|_| "meross_key")?
        {
            return Err("meross_key");
        }
        checked.push((name, value));
    }
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "internal")?;
    for (name, value) in checked.into_iter().filter(|(_, v)| !v.is_empty()) {
        runtime
            .block_on(set_secret(&vault, &master, &name, value))
            .map_err(|_| "storage")?;
    }
    Ok(json!({"saved":true}))
}

#[cfg(test)]
mod meross_tests {
    use super::*;
    #[test]
    fn multiple_mss310_keep_ids_and_never_copy_key_or_control_flags_to_configuration() {
        let mut plugs = vec![];
        for i in 1..=4 {
            plugs.push(json!({"id":format!("{i:032x}"),"name":format!("Fixture {i}"),"host":format!("192.168.1.{}",40+i),"enabled":true,"key":"never-copy-this","write":true,"discovery":true}));
        }
        let input = json!({"meross":plugs});
        let checked = validate(&input).unwrap();
        let ds = drivers(&checked).unwrap();
        assert_eq!(ds.len(), 4);
        for d in ds {
            assert_eq!(d["options"]["profile"], "meross-mss310");
            assert_eq!(d["options"]["discovery"], false);
            assert!(d["options"].get("key").is_none());
        }
        assert!(!checked.to_string().contains("never-copy-this"));
        let mut duplicate = input.clone();
        duplicate["meross"][1]["id"] = duplicate["meross"][0]["id"].clone();
        assert!(validate(&duplicate).is_err());
        let mut duplicate = input.clone();
        duplicate["meross"][1]["host"] = duplicate["meross"][0]["host"].clone();
        assert!(validate(&duplicate).is_err());
        let mut disabled = input;
        disabled["meross"][0]["enabled"] = json!(false);
        assert_eq!(drivers(&disabled).unwrap().len(), 3);
    }
    #[test]
    fn keys_are_portable_encrypted_and_blank_only_keeps_an_existing_key() {
        let dir =
            std::env::temp_dir().join(format!("moli-android-mss-vault-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let master = moli_runtime::MasterKey::generate().unwrap();
        let id = "00000000000000000000000000000001";
        let key = "a".repeat(32); // Public synthetic test value, never a device key.
        let mut input = json!({"data":dir,"master":master.expose_for_file(),"keys":[{"id":id,"key":"","required":true}]});
        assert!(store_keys(&input).is_err());
        input["keys"][0]["key"] = json!(key);
        store_keys(&input).unwrap();
        let raw = std::fs::read(dir.join("secrets.enc")).unwrap();
        assert!(!raw.windows(32).any(|s| s == key.as_bytes()));
        input["keys"][0]["key"] = json!("");
        store_keys(&input).unwrap();
        let name = format!("android-meross-{id}/key");
        assert!(moli_runtime::has_secret(&dir.join("secrets.enc"), &master, &name).unwrap());
        let wrong = moli_runtime::MasterKey::generate().unwrap();
        input["master"] = json!(wrong.expose_for_file());
        assert!(store_keys(&input).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
