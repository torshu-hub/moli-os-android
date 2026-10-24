//! Sanitized inventory from this binary's registry and effective configuration.
//! No driver construction, profile-file reads, secrets or network operations.
use crate::{KINDS, config::Config};
use serde::Deserialize;
use serde_json::{Value, json};

const MANIFESTS: &[&str] = include!(concat!(env!("OUT_DIR"), "/integration_manifests.rs"));

#[derive(Deserialize)]
struct Manifest {
    integration: Package,
}
#[derive(Deserialize)]
struct Package {
    id: String,
    driver: String,
}

pub(crate) fn inventory(config: &Config) -> Vec<Value> {
    let mut packages = std::collections::BTreeMap::new();
    for text in MANIFESTS {
        let package = toml::from_str::<Manifest>(text)
            .expect("embedded integration manifest")
            .integration;
        let compiled = KINDS.contains(&package.driver.as_str())
            && (package.driver != "profile" || moli_profile::builtin(&package.id).is_some());
        packages.insert(
            (package.driver.clone(), package.id.clone()),
            json!({"id":package.id,"kind":package.driver,"compiled":compiled,"instances":[]}),
        );
    }
    for kind in KINDS {
        packages
            .entry(((*kind).to_owned(), (*kind).to_owned()))
            .or_insert_with(|| json!({"id":kind,"kind":kind,"compiled":true,"instances":[]}));
    }
    for driver in &config.drivers {
        let id = if driver.kind == "profile" {
            driver
                .options
                .get("profile")
                .and_then(toml::Value::as_str)
                .filter(|id| moli_profile::builtin(id).is_some())
                .unwrap_or("profile")
        } else {
            &driver.kind
        };
        let row = packages.entry((driver.kind.clone(), id.to_owned())).or_insert_with(|| json!({"id":id,"kind":driver.kind,"compiled":KINDS.contains(&driver.kind.as_str()),"instances":[]}));
        row["instances"]
            .as_array_mut()
            .unwrap()
            .push(json!({"instance":driver.id,"enabled":driver.enabled != Some(false)}));
    }
    packages.into_values().collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn inventory_separates_builtin_disabled_and_external_without_reading_or_leaking_options() {
        let config: Config = toml::from_str(
            r#"
[[driver]]
id = "disabled"
kind = "profile"
enabled = false
[driver.options]
profile = "meross-mss310"
host = "private-host"
[[driver]]
id = "external"
kind = "profile"
[driver.options]
profile = "/nonexistent/private-profile.toml"
key = "private-key"
"#,
        )
        .unwrap();
        let rows = inventory(&config);
        let meross = rows.iter().find(|r| r["id"] == "meross-mss310").unwrap();
        assert_eq!(meross["compiled"], true);
        assert_eq!(meross["instances"][0]["enabled"], false);
        assert!(
            rows.iter()
                .any(|r| r["id"] == "hue" && r["instances"].as_array().unwrap().is_empty())
        );
        assert!(
            rows.iter()
                .any(|r| r["id"] == "profile" && r["instances"][0]["instance"] == "external")
        );
        let moonraker: Vec<_> = rows.iter().filter(|r| r["id"] == "moonraker").collect();
        assert_eq!(
            moonraker.len(),
            2,
            "native and profile implementations stay distinct"
        );
        assert!(moonraker.iter().any(|r| r["kind"] == "moonraker"));
        assert!(moonraker.iter().any(|r| r["kind"] == "profile"));
        let serialized = serde_json::to_string(&rows).unwrap();
        for forbidden in ["private-host", "private-profile", "private-key"] {
            assert!(!serialized.contains(forbidden));
        }
        for row in rows
            .iter()
            .filter(|r| r["kind"] == "profile" && r["id"] != "profile")
        {
            assert_eq!(
                row["compiled"],
                moli_profile::builtin(row["id"].as_str().unwrap()).is_some()
            );
        }
    }
}
