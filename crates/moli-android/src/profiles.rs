//! Bounded Android onboarding policy over the existing profile compiler.
use moli_profile::{Call, Profile, Source};
use serde_json::{Value, json};

pub fn review(text: &str) -> Result<Value, &'static str> {
    if text.len() > 65_536 {
        return Err("size");
    }
    let parsed = Profile::parse(text).map_err(|_| "syntax")?;
    if !parsed.writes.is_empty()
        || parsed.profile.secret.is_some()
        || parsed.requests.iter().any(|r| !r.headers.is_empty())
    {
        return Err("permissions");
    }
    let compiled = parsed.compile(Source::File).map_err(|_| "validation")?;
    if compiled.requests.len() != 1 || compiled.points.len() > 64 || compiled.meta.name.len() > 256
    {
        return Err("size");
    }
    let mode = if text == include_str!("../../../android/app/src/main/assets/tablet.toml") {
        "tablet"
    } else if compiled.meta.host.as_deref() == Some("api.open-meteo.com")
        && compiled.meta.https
        && compiled.meta.port.unwrap_or(443) == 443
    {
        if compiled.requests[0].every.as_secs() < 900 {
            return Err("permissions");
        }
        let shipped = Profile::parse(include_str!(
            "../../../integrations/open-meteo/profile.toml"
        ))
        .map_err(|_| "internal")?;
        if compiled
            .requests
            .iter()
            .any(|r| r.path != shipped.requests[0].path)
            || compiled.vars.iter().any(|v| v != "lat" && v != "lon")
        {
            return Err("destination");
        }
        "weather"
    } else if compiled.meta.host.is_none()
        && !compiled.meta.https
        && compiled.meta.port.is_none()
        && compiled.vars.is_empty()
        && compiled.requests.iter().all(|r| r.path == "/status")
    {
        "synthetic"
    } else {
        return Err("destination");
    };
    if compiled
        .requests
        .iter()
        .any(|r| !matches!(r.call, Call::Get))
    {
        return Err("permissions");
    }
    Ok(json!({"name": compiled.meta.name, "id": compiled.meta.id,
        "sha256": moli_profile::sha256(text), "mode": mode,
        "points": compiled.points.len(),
        "every": compiled.requests.iter().map(|r| r.every.as_secs()).max().unwrap_or(30),
        "reads": compiled.requests.iter().map(|r| json!({"path": r.path, "seconds": r.every.as_secs()})).collect::<Vec<_>>() }))
}

pub fn configuration(input: &str) -> Result<String, &'static str> {
    let input: Value = serde_json::from_str(input).map_err(|_| "configuration")?;
    let original = input["config"].as_str().ok_or("configuration")?;
    let data = input["data"].as_str().ok_or("configuration")?;
    let mut config: toml::Value = toml::from_str(original).map_err(|_| "configuration")?;
    let table = config.as_table_mut().ok_or("configuration")?;
    let drivers = table
        .entry("driver")
        .or_insert_with(|| toml::Value::Array(vec![]))
        .as_array_mut()
        .ok_or("configuration")?;
    // Preserve every unmanaged driver and all other engine settings.
    drivers.retain(|d| {
        !d.get("id")
            .and_then(toml::Value::as_str)
            .is_some_and(|id| id.starts_with("android-meross-"))
            && !matches!(
                d.get("id").and_then(toml::Value::as_str),
                Some(
                    "android-import"
                        | "android-demo"
                        | "android-weather"
                        | "android-tablet"
                        | "android-printer"
                        | "android-gateway"
                )
            )
    });
    for driver in crate::equipment::drivers(input.get("equipment").unwrap_or(&json!({})))? {
        drivers.push(toml::Value::try_from(driver).map_err(|_| "configuration")?);
    }
    if input["demo"].as_bool() == Some(true) {
        drivers.push(
            toml::Value::try_from(json!({"id":"android-demo","kind":"demo",
            "options":{"fixture":format!("{data}/android-demo.json"),"tick_s":5}}))
            .map_err(|_| "configuration")?,
        );
    }
    for (key, id) in [
        ("import", "android-import"),
        ("weather", "android-weather"),
        ("tablet", "android-tablet"),
    ] {
        if input[key].is_null() {
            continue;
        }
        let text = input[key]["text"].as_str().ok_or("configuration")?;
        let info = review(text)?;
        let sha = info["sha256"].as_str().ok_or("configuration")?;
        if input[key]["sha256"].as_str() != Some(sha) {
            return Err("changed");
        }
        let mut options = json!({"profile":format!("{data}/android-profiles/{sha}.toml")});
        if info["mode"] == "weather" {
            options["host"] = json!("api.open-meteo.com");
            options["port"] = json!(443);
            options["vars"] = coordinates(&input[key])?;
        } else {
            let port = input["port"]
                .as_u64()
                .filter(|p| *p > 0 && *p <= 65535)
                .ok_or("configuration")?;
            options["host"] = json!("127.0.0.1");
            options["port"] = json!(port);
        }
        drivers.push(
            toml::Value::try_from(json!({"id":id,"kind":"profile","options":options}))
                .map_err(|_| "configuration")?,
        );
    }
    toml::to_string(&config).map_err(|_| "configuration")
}

// Only finite numeric coordinates reach the existing URL-template transport.
fn coordinates(input: &Value) -> Result<Value, &'static str> {
    if input["location"].is_null() {
        return Ok(json!({"lat":"48.8566","lon":"2.3522"})); // Legacy approved demo.
    }
    let lat = input["location"]["latitude"]
        .as_f64()
        .filter(|v| v.is_finite() && (-90.0..=90.0).contains(v))
        .ok_or("location")?;
    let lon = input["location"]["longitude"]
        .as_f64()
        .filter(|v| v.is_finite() && (-180.0..=180.0).contains(v))
        .ok_or("location")?;
    Ok(json!({"lat":lat.to_string(),"lon":lon.to_string()}))
}

/// Explicit, approved public-weather probe; does not change the running driver.
pub fn weather_probe(input: &str) -> Result<Value, &'static str> {
    let input: Value = serde_json::from_str(input).map_err(|_| "input")?;
    let text = input["text"].as_str().ok_or("input")?;
    let info = review(text)?;
    if info["mode"] != "weather" || info["sha256"] != input["sha256"] {
        return Err("changed");
    }
    let trust =
        std::path::Path::new(input["data"].as_str().ok_or("input")?).join(moli_profile::TRUST_FILE);
    let compiled = Profile::parse(text)
        .map_err(|_| "syntax")?
        .compile(Source::File)
        .map_err(|_| "validation")?;
    let config = moli_profile::Config {
        profile: "open-meteo".into(),
        host: Some("api.open-meteo.com".into()),
        port: Some(443),
        vars: serde_json::from_value(coordinates(&input)?).map_err(|_| "location")?,
        discovery: false,
        name: None,
    };
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "internal")?;
    let result = runtime.block_on(async {
        if !moli_profile::is_trusted(&trust, info["sha256"].as_str().ok_or("input")?).await.map_err(|_| "changed")? {
            return Err("changed");
        }
        let result = tokio::time::timeout(std::time::Duration::from_secs(12),
            moli_profile::read_only_once(&compiled, &config)).await.map_err(|_| "network")?.map_err(|_| "network")?;
        let temperature = result.get("temperature").and_then(moli_core::Value::as_f64).ok_or("response")?;
        let humidity = result.get("humidity").and_then(moli_core::Value::as_f64).ok_or("response")?;
        let received = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_err(|_| "internal")?.as_millis();
        Ok(json!({"temperature":temperature,"humidity":humidity,"received_ms":received,"source":"live_https"}))
    });
    runtime.shutdown_timeout(std::time::Duration::from_millis(500));
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    const SAMPLE: &str =
        include_str!("../../../android/app/src/main/assets/synthetic-temperature.toml");
    #[test]
    fn review_uses_compiler_and_restricts_permissions_and_destinations() {
        assert_eq!(review(SAMPLE).unwrap()["mode"], "synthetic");
        assert!(review(&SAMPLE.replace("[profile]", "[profile]\nhost = '192.0.2.1'")).is_err());
        assert!(review(&SAMPLE.replace("[profile]", "[profile]\nsecret = 'key'")).is_err());
        assert!(review(&SAMPLE.replace("/status", "/switch")).is_err());
        assert!(review(&SAMPLE.replace("/status", "/status?turn=on")).is_err());
        assert!(review("broken").is_err());
        assert_eq!(review(&"x".repeat(65_537)), Err("size"));
        assert!(
            review(&SAMPLE.replace(
                "every = \"5s\"",
                "every = \"5s\"\nheaders = { Authorization = 'no' }"
            ))
            .is_err()
        );
    }
    #[test]
    fn configurable_weather_is_numeric_bounded_and_keeps_tablet_read_only() {
        let weather = include_str!("../../../integrations/open-meteo/profile.toml");
        let tablet = include_str!("../../../android/app/src/main/assets/tablet.toml");
        let mut spec = json!({"config":"", "data":"/synthetic", "port":1234,
            "weather":{"text":weather,"sha256":moli_profile::sha256(weather),"location":{"latitude":45.764,"longitude":4.8357}},
            "tablet":{"text":tablet,"sha256":moli_profile::sha256(tablet)}});
        let parsed: toml::Value =
            toml::from_str(&configuration(&spec.to_string()).unwrap()).unwrap();
        let drivers = parsed["driver"].as_array().unwrap();
        assert_eq!(
            drivers[0]["options"]["vars"]["lat"].as_str(),
            Some("45.764")
        );
        assert_eq!(drivers[1]["id"].as_str(), Some("android-tablet"));
        assert_eq!(review(tablet).unwrap()["mode"], "tablet");
        assert!(review(&tablet.replace("/tablet", "/switch")).is_err());
        for location in [
            json!({"latitude":91,"longitude":0}),
            json!({"latitude":0,"longitude":181}),
            json!({"latitude":"0&host=example.invalid","longitude":0}),
        ] {
            spec["weather"]["location"] = location;
            assert_eq!(configuration(&spec.to_string()), Err("location"));
        }
    }

    #[test]
    fn weather_fixture_uses_existing_point_conversion() {
        let weather = include_str!("../../../integrations/open-meteo/profile.toml");
        let compiled = Profile::parse(weather)
            .unwrap()
            .compile(Source::File)
            .unwrap();
        let answer = moli_profile::Answer::Json(
            json!({"current":{"temperature_2m":12.3,"relative_humidity_2m":67}}),
        );
        for (key, expected) in [("temperature", 12.3), ("humidity", 67.0)] {
            let point = compiled
                .points
                .iter()
                .find(|p| &*p.spec.key == key)
                .unwrap();
            assert_eq!(
                serde_json::to_value(point.read(&answer).unwrap()).unwrap(),
                json!(expected)
            );
        }
        assert!(review(&weather.replace("15m", "5s")).is_err());
    }

    #[test]
    fn weather_and_exact_approval_preserve_other_configuration() {
        let weather = include_str!("../../../integrations/open-meteo/profile.toml");
        assert_eq!(review(weather).unwrap()["mode"], "weather");
        let mut spec = json!({"config":"[server]\nworker_threads=3\n[[driver]]\nid='untouched'\nkind='helpers'\n", "data":"/synthetic", "port":1234,
            "demo":true,"import":{"text":SAMPLE,"sha256":moli_profile::sha256(SAMPLE)}});
        let output = configuration(&spec.to_string()).unwrap();
        let parsed: toml::Value = toml::from_str(&output).unwrap();
        assert_eq!(parsed["server"]["worker_threads"].as_integer(), Some(3));
        assert_eq!(parsed["driver"].as_array().unwrap().len(), 3);
        spec["import"]["text"] = json!(format!("{SAMPLE}\n# altered"));
        assert_eq!(configuration(&spec.to_string()), Err("changed"));
    }
}
