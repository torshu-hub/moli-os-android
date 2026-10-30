//! Minimal catalogue form adapter. `moli.toml` is the only effective state.
use serde_json::{Value, json};
use toml_edit::{ArrayOfTables, DocumentMut, Item, Table, value};

include!(concat!(env!("OUT_DIR"), "/catalogue.rs"));

pub(crate) fn form(text: &str) -> Result<Value, &'static str> {
    form_for(text, "open-meteo")
}

pub(crate) fn catalogue(text: &str) -> Result<Value, &'static str> {
    let config: toml::Value = toml::from_str(text).map_err(|_| "configuration")?;
    let packages: Result<Vec<_>, _> = PACKAGES
        .iter()
        .map(|(id, _, _)| package_for(&config, id))
        .collect();
    Ok(json!({"packages":packages?}))
}

fn form_for(text: &str, profile: &str) -> Result<Value, &'static str> {
    let config: toml::Value = toml::from_str(text).map_err(|_| "configuration")?;
    package_for(&config, profile)
}

fn package_for(config: &toml::Value, profile: &str) -> Result<Value, &'static str> {
    let (_, manifest, onboarding) = PACKAGES
        .iter()
        .find(|(id, _, _)| *id == profile)
        .ok_or("catalogue")?;
    let manifest: toml::Value = toml::from_str(manifest).map_err(|_| "catalogue")?;
    let id = manifest["integration"]["id"].as_str().ok_or("catalogue")?;
    let kind = manifest["integration"]["driver"]
        .as_str()
        .ok_or("catalogue")?;
    let instances: Result<Vec<Value>, &'static str> = config
        .get("driver")
        .and_then(toml::Value::as_array)
        .into_iter()
        .flatten()
        .filter(|d| {
            d.get("kind").and_then(toml::Value::as_str) == Some(kind)
                && (kind != "profile" || d.get("options")
                    .and_then(|o| o.get("profile"))
                    .and_then(toml::Value::as_str)
                    == Some(id))
        })
        .map(|d| {
            let enabled = d.get("enabled").map_or(Ok(true), |v| v.as_bool().ok_or("configuration"))?;
            Ok(json!({"id":d.get("id"), "enabled":enabled, "vars":d.get("options").and_then(|o|o.get("vars"))}))
        })
        .collect();
    // Only the existing profile-variable contract has an Android editor. No
    // implicit secret, host, discovery or pairing implementation is claimed.
    let needs = &manifest["needs"];
    let configurable = kind == "profile"
        && needs["secrets"].as_array().is_some_and(Vec::is_empty)
        && needs["options"].as_array().is_some_and(Vec::is_empty)
        && needs["vars"].as_array().is_some_and(|vars| {
            vars.iter()
                .all(|v| matches!(v.as_str(), Some("lat" | "lon")))
        });
    Ok(
        json!({"manifest":manifest,"onboarding":onboarding,"instances":instances?,"configurable":configurable}),
    )
}

pub(crate) fn prepare(input: &Value) -> Result<String, &'static str> {
    let text = input["text"].as_str().ok_or("input")?;
    let id = input["id"].as_str().ok_or("input")?;
    let vars = input["vars"].as_object().ok_or("input")?;
    let profile = input
        .get("profile")
        .map_or(Some("open-meteo"), Value::as_str)
        .ok_or("input")?;
    let package = form_for(text, profile)?;
    if package["configurable"] != true {
        return Err("unsupported");
    }
    let manifest = &package["manifest"];
    let profile = manifest["integration"]["id"].as_str().ok_or("catalogue")?;
    let needs = manifest["needs"]["vars"].as_array().ok_or("catalogue")?;
    if vars.len() != needs.len()
        || needs.iter().any(|n| {
            n.as_str()
                .and_then(|n| vars.get(n))
                .and_then(Value::as_str)
                .is_none()
        })
    {
        return Err("input");
    }
    let mut document: DocumentMut = text.parse().map_err(|_| "configuration")?;
    if !document.contains_key("driver") {
        document["driver"] = Item::ArrayOfTables(ArrayOfTables::new());
    }
    let drivers = document["driver"]
        .as_array_of_tables_mut()
        .ok_or("configuration")?;
    let index = drivers
        .iter()
        .position(|d| d.get("id").and_then(Item::as_str) == Some(id));
    let index = if let Some(index) = index {
        index
    } else {
        let mut driver = Table::new();
        driver["id"] = value(id);
        driver["kind"] = value("profile");
        let mut options = Table::new();
        options["profile"] = value(profile);
        driver["options"] = Item::Table(options);
        drivers.push(driver);
        drivers.len() - 1
    };
    let driver = drivers.get_mut(index).ok_or("configuration")?;
    if driver.get("kind").and_then(Item::as_str) != Some("profile")
        || driver
            .get("options")
            .and_then(|o| o.get("profile"))
            .and_then(Item::as_str)
            != Some(profile)
    {
        return Err("conflict");
    }
    // Mutate only the selected driver's required variables. toml_edit retains
    // the surrounding document, other drivers and the existing decorations.
    for name in needs {
        let name = name.as_str().ok_or("catalogue")?;
        let content = vars[name].as_str().ok_or("input")?;
        let field = &mut driver["options"]["vars"][name];
        let decoration = field.as_value().map(|v| v.decor().clone());
        *field = value(content);
        if let Some(decoration) = decoration {
            *field.as_value_mut().ok_or("configuration")?.decor_mut() = decoration;
        }
    }
    Ok(document.to_string())
}

/// Activation changes only the selected canonical driver's `enabled` field.
pub(crate) fn toggle(input: &Value) -> Result<String, &'static str> {
    let text = input["text"].as_str().ok_or("input")?;
    let id = input["id"].as_str().ok_or("input")?;
    let enabled = input["enabled"].as_bool().ok_or("input")?;
    let mut document: DocumentMut = text.parse().map_err(|_| "configuration")?;
    let driver = document
        .get_mut("driver")
        .and_then(Item::as_array_of_tables_mut)
        .and_then(|drivers| {
            drivers
                .iter_mut()
                .find(|d| d.get("id").and_then(Item::as_str) == Some(id))
        })
        .ok_or("conflict")?;
    let decoration = driver
        .get("enabled")
        .and_then(Item::as_value)
        .map(|v| v.decor().clone());
    driver["enabled"] = value(enabled);
    if let Some(decoration) = decoration {
        *driver["enabled"]
            .as_value_mut()
            .ok_or("configuration")?
            .decor_mut() = decoration;
    }
    Ok(document.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_index_lists_every_official_package_with_honest_editor_capabilities() {
        let state = catalogue("").unwrap();
        let packages = state["packages"].as_array().unwrap();
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../integrations");
        let expected = std::fs::read_dir(root)
            .unwrap()
            .filter_map(Result::ok)
            .filter(|entry| entry.path().join("integration.toml").is_file())
            .count();
        assert_eq!(packages.len(), expected);
        for (id, _, _) in PACKAGES {
            let package = packages
                .iter()
                .find(|p| p["manifest"]["integration"]["id"] == *id)
                .unwrap();
            assert_eq!(package["instances"], json!([]));
            assert_ne!(package["onboarding"].as_str().unwrap(), "");
        }
        let editable: Vec<_> = packages
            .iter()
            .filter(|p| p["configurable"] == true)
            .map(|p| p["manifest"]["integration"]["id"].as_str().unwrap())
            .collect();
        assert_eq!(editable, ["open-meteo", "open-meteo-air", "tempo"]);
        assert!(prepare(&json!({"text":"", "profile":"iopool", "id":"pool", "vars":{}})).is_err());
        let tempo =
            prepare(&json!({"text":"", "profile":"tempo", "id":"tempo", "vars":{}})).unwrap();
        assert_eq!(
            form_for(&tempo, "tempo").unwrap()["instances"][0]["enabled"],
            true
        );
    }

    #[test]
    fn activation_is_per_id_and_preserves_comments_options_and_other_instances() {
        let before = "# keep\n[[driver]]\nid = \"first\"\nkind = \"profile\"\nenabled = true # first flag\n[driver.options]\nprofile = \"open-meteo\"\nvars = { lat = \"0\", lon = \"0\" } # coordinates\n[[driver]]\nid = \"second\"\nkind = \"profile\"\nenabled = false # second flag\n[driver.options]\nprofile = \"open-meteo\"\nvars = { lat = \"1\", lon = \"2\" }\n[[driver]]\nid = \"host\"\nkind = \"host\"\n[guard]\nprotect_unassigned = true # guard\n";
        let toggled = toggle(&json!({"text":before,"id":"first","enabled":false})).unwrap();
        assert_eq!(
            toggled,
            before.replace("enabled = true # first", "enabled = false # first")
        );
        assert_eq!(
            toggle(&json!({"text":toggled,"id":"first","enabled":true})).unwrap(),
            before
        );
        let instances = form(&toggled).unwrap()["instances"]
            .as_array()
            .unwrap()
            .clone();
        assert_eq!(instances.len(), 2);
        assert!(instances.iter().all(|i| i["enabled"] == false));
        assert_eq!(
            form_for(before, "host").unwrap()["instances"][0]["enabled"],
            true
        );
        let explicit = toggle(&json!({"text":before,"id":"host","enabled":false})).unwrap();
        assert_eq!(
            form_for(&explicit, "host").unwrap()["instances"][0]["enabled"],
            false
        );
        assert!(toggle(&json!({"text":before,"id":"missing","enabled":false})).is_err());
        assert!(toggle(&json!({"text":before,"id":"first","enabled":"false"})).is_err());
    }

    #[test]
    fn updates_one_native_driver_and_keeps_comments_and_other_sections() {
        let before = "# keep\n[server]\nlanguage = \"fr\" # language\n[[driver]]\nid = \"outside\"\nkind = \"profile\"\n[driver.options]\nprofile = \"open-meteo\"\n[driver.options.vars]\nlat = \"0\" # coordinate\nlon = \"0\"\n[[driver]]\nid = \"other\"\nkind = \"helpers\"\n[guard]\nprotect_unassigned = true # guard\n";
        let next =
            prepare(&json!({"text":before,"id":"outside","vars":{"lat":"1","lon":"2"}})).unwrap();
        assert_eq!(
            next,
            before
                .replace("lat = \"0\"", "lat = \"1\"")
                .replace("lon = \"0\"", "lon = \"2\"")
        );
        assert!(
            prepare(&json!({"text":before,"id":"other","vars":{"lat":"1","lon":"2"}})).is_err()
        );
    }

    #[test]
    fn a_new_instance_is_catalogue_driven_and_read_back_from_toml() {
        let next = prepare(&json!({"text":"[server]\nlanguage = \"en\"\n","id":"weather","vars":{"lat":"0","lon":"0"}})).unwrap();
        let state = form(&next).unwrap();
        assert_eq!(state["instances"][0]["id"], "weather");
        assert_eq!(state["instances"][0]["vars"]["lat"], "0");
        assert_eq!(state["manifest"]["needs"]["vars"], json!(["lat", "lon"]));
    }

    #[test]
    fn official_profiles_coexist_and_edit_independently_without_overwriting_conflicts() {
        let weather = prepare(&json!({"text":"# retained\n[server]\nlanguage = \"fr\"\n", "id":"existing-weather", "vars":{"lat":"0","lon":"0"}})).unwrap();
        let air = prepare(&json!({"text":weather,"profile":"open-meteo-air","id":"air", "vars":{"lat":"1","lon":"2"}})).unwrap();
        assert!(air.starts_with(&weather));
        let next = prepare(&json!({"text":air,"profile":"open-meteo-air","id":"air", "vars":{"lat":"3","lon":"4"}})).unwrap();
        let state = catalogue(&next).unwrap();
        let packages = state["packages"].as_array().unwrap();
        let weather = packages
            .iter()
            .find(|p| p["manifest"]["integration"]["id"] == "open-meteo")
            .unwrap();
        let air = packages
            .iter()
            .find(|p| p["manifest"]["integration"]["id"] == "open-meteo-air")
            .unwrap();
        assert_eq!(weather["instances"][0]["vars"]["lat"], "0");
        assert_eq!(air["instances"][0]["vars"]["lat"], "3");
        assert_eq!(air["manifest"]["integration"]["id"], "open-meteo-air");
        assert!(prepare(&json!({"text":next,"profile":"open-meteo-air","id":"existing-weather", "vars":{"lat":"3","lon":"4"}})).is_err());
        assert!(
            prepare(
                &json!({"text":next,"profile":"unknown","id":"new", "vars":{"lat":"3","lon":"4"}})
            )
            .is_err()
        );
    }
}
