//! Pure evidence parsing. Android owns the user-consented Wi-Fi sockets and cancellation.
use moli_net::upnp::{Location, control_url, tag, unescape};
use serde_json::{Value, json};
use std::net::Ipv4Addr;

pub fn review(input: &Value) -> Result<Value, &'static str> {
    let source = input["source"].as_str().ok_or("discovery_input")?;
    let ip: Ipv4Addr = source.parse().map_err(|_| "discovery_input")?;
    if !ip.is_private() {
        return Err("discovery_input");
    }
    let packet = input["packet"].as_str().ok_or("discovery_input")?;
    if packet.len() > 4096
        || packet
            .lines()
            .next()
            .is_none_or(|l| l.trim() != "HTTP/1.1 200 OK")
    {
        return Err("discovery_input");
    }
    let mut locations = packet.lines().filter_map(|line| {
        let (key, value) = line.split_once(':')?;
        key.eq_ignore_ascii_case("location").then_some(value.trim())
    });
    let location = locations.next().ok_or("discovery_input")?;
    if locations.next().is_some() || location.len() > 320 {
        return Err("discovery_input");
    }
    let url = Location::parse(location).ok_or("discovery_input")?;
    // No DNS, redirects, credentials, cross-device description fetches or URL queries.
    if url.host != ip.to_string() || url.port == 0 || !safe_path(&url.path) {
        return Err("discovery_input");
    }
    let mut result =
        json!({"host":source,"port":url.port,"location":url.to_string(),"name":"","igd":false});
    if let Some(xml) = input["description"].as_str() {
        if xml.len() > 32_768
            || xml.contains("<!DOCTYPE")
            || xml.contains("<!ENTITY")
            || !xml.contains("<root")
        {
            return Err("discovery_input");
        }
        if let Some(name) = tag(xml, "friendlyName") {
            let name = unescape(name);
            if name.len() <= 160 && !name.chars().any(char::is_control) {
                result["name"] = json!(name);
            }
        }
        result["igd"] = json!(
            tag(xml, "deviceType").is_some_and(
                |s| s.starts_with("urn:schemas-upnp-org:device:InternetGatewayDevice:")
            ) && control_url(xml, "urn:schemas-upnp-org:service:WANIPConnection:").is_some()
                && url.path.to_ascii_lowercase().ends_with(".xml")
        );
    }
    Ok(result)
}

fn safe_path(path: &str) -> bool {
    path.len() <= 256
        && path.starts_with('/')
        && !path.starts_with("//")
        && !path.split('/').any(|p| matches!(p, "." | ".."))
        && path
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"/._-".contains(&b))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn advertised_description_is_not_a_model_or_gateway_claim() {
        let mut v = json!({"source":"192.168.1.20","packet":"HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.20:8080/root.xml\r\n\r\n"});
        assert_eq!(review(&v).unwrap()["igd"], false);
        v["description"] = json!(
            "<root><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType><friendlyName>Demo &amp; box</friendlyName><service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/control</controlURL></service></device></root>"
        );
        let result = review(&v).unwrap();
        assert_eq!(result["igd"], true);
        assert_eq!(result["name"], "Demo & box");
    }
    #[test]
    fn rejects_cross_host_public_ambiguous_and_hostile_descriptions() {
        for url in [
            "http://8.8.8.8/root.xml",
            "http://192.168.1.21/root.xml",
            "http://user@192.168.1.20/root.xml",
            "http://192.168.1.20/root.xml?token=x",
            "http://192.168.1.20/../root.xml",
            "https://192.168.1.20/root.xml",
        ] {
            assert!(review(&json!({"source":"192.168.1.20","packet":format!("HTTP/1.1 200 OK\r\nLOCATION: {url}\r\n")})).is_err());
        }
        let mut v = json!({"source":"192.168.1.20","packet":"HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.20/root.xml\r\nLOCATION: http://192.168.1.20/other.xml\r\n"});
        assert!(review(&v).is_err());
        v["packet"] = json!("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.20/root.xml\r\n");
        v["description"] = json!("<!DOCTYPE root><root/>");
        assert!(review(&v).is_err());
    }
}
