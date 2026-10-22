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
    Ok(result)
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
