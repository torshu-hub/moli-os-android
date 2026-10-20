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
    let mode = if compiled.meta.host.as_deref() == Some("api.open-meteo.com")
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
        !matches!(
            d.get("id").and_then(toml::Value::as_str),
            Some("android-import" | "android-demo" | "android-weather")
        )
    });
    if input["demo"].as_bool() == Some(true) {
        drivers.push(
            toml::Value::try_from(json!({"id":"android-demo","kind":"demo",
            "options":{"fixture":format!("{data}/android-demo.json"),"tick_s":5}}))
            .map_err(|_| "configuration")?,
        );
    }
    for (key, id) in [("import", "android-import"), ("weather", "android-weather")] {
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
            options["vars"] = json!({"lat":"48.8566","lon":"2.3522"});
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
