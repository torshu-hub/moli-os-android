//! Minimal catalogue form adapter. `moli.toml` is the only effective state.
use serde_json::{Value, json};
use toml_edit::{ArrayOfTables, DocumentMut, Item, Table, value};

// Explicitly supported official packages. Each entry uses the same form adapter.
const PACKAGES: &[(&str, &str, &str)] = &[
    (
        "open-meteo",
        include_str!("../../../integrations/open-meteo/integration.toml"),
        include_str!("../../../integrations/open-meteo/onboarding.md"),
    ),
    (
        "open-meteo-air",
        include_str!("../../../integrations/open-meteo-air/integration.toml"),
        include_str!("../../../integrations/open-meteo-air/onboarding.md"),
    ),
];

pub(crate) fn form(text: &str) -> Result<Value, &'static str> {
    form_for(text, "open-meteo")
}

pub(crate) fn catalogue(text: &str) -> Result<Value, &'static str> {
    let packages: Result<Vec<_>, _> = PACKAGES
        .iter()
        .map(|(id, _, _)| form_for(text, id))
        .collect();
    Ok(json!({"packages":packages?}))
}

fn form_for(text: &str, profile: &str) -> Result<Value, &'static str> {
    let (_, manifest, onboarding) = PACKAGES
        .iter()
        .find(|(id, _, _)| *id == profile)
        .ok_or("catalogue")?;
    let manifest: toml::Value = toml::from_str(manifest).map_err(|_| "catalogue")?;
    let config: toml::Value = toml::from_str(text).map_err(|_| "configuration")?;
    let id = manifest["integration"]["id"].as_str().ok_or("catalogue")?;
    let instances: Vec<Value> = config
        .get("driver")
        .and_then(toml::Value::as_array)
        .into_iter()
        .flatten()
        .filter(|d| {
            d.get("kind").and_then(toml::Value::as_str) == Some("profile")
                && d.get("options")
                    .and_then(|o| o.get("profile"))
                    .and_then(toml::Value::as_str)
                    == Some(id)
        })
        .map(|d| json!({"id":d.get("id"), "vars":d.get("options").and_then(|o|o.get("vars"))}))
        .collect();
    Ok(json!({"manifest":manifest,"onboarding":onboarding,"instances":instances}))
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

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(state["packages"][0]["instances"][0]["vars"]["lat"], "0");
        assert_eq!(state["packages"][1]["instances"][0]["vars"]["lat"], "3");
        assert_eq!(
            state["packages"][1]["manifest"]["integration"]["id"],
            "open-meteo-air"
        );
        assert!(prepare(&json!({"text":next,"profile":"open-meteo-air","id":"existing-weather", "vars":{"lat":"3","lon":"4"}})).is_err());
        assert!(
            prepare(
                &json!({"text":next,"profile":"unknown","id":"new", "vars":{"lat":"3","lon":"4"}})
            )
            .is_err()
        );
    }
}
