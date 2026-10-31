//! Native form presentation adapter; official Rust types validate every supported option.
//! `moli.toml` remains the only effective state. No network or device commands here.
use serde_json::{Value, json};
use toml_edit::{ArrayOfTables, DocumentMut, Item, Table, value};

include!(concat!(env!("OUT_DIR"), "/catalogue.rs"));

pub(crate) fn form(text: &str) -> Result<Value, &'static str> {
    form_for(text, "open-meteo")
}

pub(crate) fn catalogue(text: &str) -> Result<Value, &'static str> {
    let config: toml::Value = toml::from_str(text).map_err(|_| "configuration")?;
    let document = toml_edit::Document::parse(text).map_err(|_| "configuration")?;
    let packages: Result<Vec<_>, &'static str> = PACKAGES
        .iter()
        .map(|(id, _, _)| {
            let mut package = package_for(&config, id)?;
            package["configuration_source"] =
                json!(configuration_source(text, &document, &package));
            Ok(package)
        })
        .collect();
    Ok(json!({"packages":packages?}))
}

// Slice parsed TOML table spans from the original bytes; never regenerate the config.
fn configuration_source(
    text: &str,
    document: &toml_edit::Document<&str>,
    package: &Value,
) -> String {
    fn implicit_value_end(table: &Table, mut end: usize) -> usize {
        for (_, item) in table {
            if item.is_value()
                && let Some(span) = item.span()
            {
                end = end.max(span.end);
            }
            if let Some(child) = item.as_table().filter(|table| table.span().is_none()) {
                end = implicit_value_end(child, end);
            }
        }
        end
    }
    fn tables(text: &str, table: &Table, ranges: &mut Vec<std::ops::Range<usize>>) {
        if let Some(mut span) = table.span() {
            for (_, item) in table {
                if item.is_value()
                    && let Some(value) = item.span()
                {
                    span.end = span.end.max(value.end);
                }
                if let Some(implicit) = item.as_table().filter(|table| table.span().is_none()) {
                    span.end = implicit_value_end(implicit, span.end);
                }
            }
            span.end += text[span.end..]
                .find('\n')
                .map_or(text.len() - span.end, |offset| offset + 1);
            ranges.push(span);
        }
        for (_, item) in table {
            if let Some(child) = item.as_table() {
                tables(text, child, ranges);
            }
            if let Some(children) = item.as_array_of_tables() {
                for child in children {
                    tables(text, child, ranges);
                }
            }
        }
    }
    let ids: Vec<_> = package["instances"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|instance| instance["id"].as_str())
        .collect();
    let mut ranges = Vec::new();
    if let Some(drivers) = document.get("driver").and_then(Item::as_array_of_tables) {
        for driver in drivers {
            if driver
                .get("id")
                .and_then(Item::as_str)
                .is_some_and(|id| ids.contains(&id))
            {
                tables(text, driver, &mut ranges);
            }
        }
    }
    ranges.sort_by_key(|span| span.start);
    let mut merged: Vec<std::ops::Range<usize>> = Vec::new();
    for span in ranges {
        if let Some(previous) = merged
            .last_mut()
            .filter(|previous| previous.end >= span.start)
        {
            previous.end = previous.end.max(span.end);
        } else {
            merged.push(span);
        }
    }
    merged
        .into_iter()
        .map(|span| &text[span])
        .collect::<Vec<_>>()
        .join("\n")
}

fn form_for(text: &str, package: &str) -> Result<Value, &'static str> {
    let config: toml::Value = toml::from_str(text).map_err(|_| "configuration")?;
    package_for(&config, package)
}

// These are UI prompts for the small reviewed subset, not a configuration
// schema/validator. Types, ranges, unknown fields and profile requirements are
// checked by the unchanged official Config types and ProfileDriver constructor.
fn fields_for(manifest: &toml::Value) -> Vec<Value> {
    let kind = manifest["integration"]["driver"].as_str().unwrap_or("");
    let mut fields = Vec::new();
    let mut field = |name: &str, location: &str, numeric: bool, required: bool| {
        fields.push(json!({"name":name,"location":location,"numeric":numeric,"required":required}));
    };
    if manifest["integration"]["id"].as_str() == Some("meross-mss310") {
        field("host", "options", false, true);
        field("port", "options", true, false);
        field("name", "options", false, true);
        field("expected_uuid", "vars", false, true);
        return fields;
    }
    if kind == "profile" {
        for name in manifest["needs"]["vars"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(toml::Value::as_str)
        {
            field(name, "vars", false, true);
        }
        for name in manifest["needs"]["options"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(toml::Value::as_str)
        {
            field(name, "options", name == "port", true);
        }
    } else {
        field("host", "options", false, true);
        match kind {
            "solarman" => {
                for name in ["port", "serial", "slave"] {
                    field(name, "options", true, false);
                }
                for name in ["mac", "timezone"] {
                    field(name, "options", false, false);
                }
            }
            "ipp" => {
                field("port", "options", true, false);
                field("path", "options", false, false);
            }
            "moonraker" => {
                field("port", "options", true, false);
                field("name", "options", false, false);
            }
            _ => {}
        }
    }
    fields
}

fn supported(manifest: &toml::Value) -> bool {
    if manifest["integration"]["id"].as_str() == Some("meross-mss310") {
        return true;
    }
    // Pairing/authentication and discovery-dependent packages stay unavailable.
    if !manifest["needs"]["secrets"]
        .as_array()
        .is_some_and(Vec::is_empty)
    {
        return false;
    }
    matches!(
        manifest["integration"]["id"].as_str(),
        Some(
            "open-meteo"
                | "open-meteo-air"
                | "tempo"
                | "daikin-brp069"
                | "moonraker"
                | "klipper"
                | "solarman"
                | "ipp"
                | "sonos"
        )
    )
}

fn matches_package(driver: &toml::Value, kind: &str, id: &str) -> bool {
    driver.get("kind").and_then(toml::Value::as_str) == Some(kind)
        && (kind != "profile"
            || driver
                .get("options")
                .and_then(|o| o.get("profile"))
                .and_then(toml::Value::as_str)
                == Some(id))
}

/// Exact official option deserialization; profile construction checks its built-in
/// contract without starting a task, accessing secrets or sending a request.
fn validate_options(kind: &str, options: toml::Value) -> bool {
    match kind {
        "profile" => options
            .try_into::<moli_profile::Config>()
            .is_ok_and(|config| {
                moli_profile::ProfileDriver::new(config, std::path::PathBuf::new()).is_ok()
            }),
        "solarman" => options.try_into::<moli_solarman::Config>().is_ok(),
        "ipp" => options.try_into::<moli_ipp::Config>().is_ok(),
        "sonos" => options.try_into::<moli_sonos::Config>().is_ok(),
        "moonraker" => options.try_into::<moli_moonraker::Config>().is_ok(),
        _ => false,
    }
}

fn package_for(config: &toml::Value, package: &str) -> Result<Value, &'static str> {
    let (_, manifest_source, onboarding) = PACKAGES
        .iter()
        .find(|(id, _, _)| *id == package)
        .ok_or("catalogue")?;
    let manifest: toml::Value = toml::from_str(manifest_source).map_err(|_| "catalogue")?;
    let id = manifest["integration"]["id"].as_str().ok_or("catalogue")?;
    let kind = manifest["integration"]["driver"]
        .as_str()
        .ok_or("catalogue")?;
    // Native packages identify by kind; profiles by kind + exact official id.
    // If upstream ever shares a native kind, never assign its instances twice.
    let unique = kind == "profile"
        || PACKAGES
            .iter()
            .filter(|(_, text, _)| {
                toml::from_str::<toml::Value>(text)
                    .ok()
                    .and_then(|m| m["integration"]["driver"].as_str().map(str::to_owned))
                    .as_deref()
                    == Some(kind)
            })
            .count()
            == 1;
    let configurable = unique && supported(&manifest);
    let drivers = config.get("driver").and_then(toml::Value::as_array);
    let instances: Vec<_> = drivers.into_iter().flatten()
        .filter(|d| unique && matches_package(d,kind,id))
        .map(|d| {
            let identity = d.get("id").and_then(toml::Value::as_str).unwrap_or("");
            let options = d.get("options").cloned().unwrap_or_else(|| toml::Value::Table(toml::map::Map::new()));
            let valid = configurable && !identity.is_empty() && !identity.contains([':', '/'])
                && drivers.into_iter().flatten().filter(|other|other.get("id").and_then(toml::Value::as_str) == Some(identity)).count() == 1
                && d.get("enabled").is_none_or(|v|v.as_bool().is_some())
                && validate_options(kind, options.clone());
            json!({"id":identity,"enabled":d.get("enabled").and_then(toml::Value::as_bool).unwrap_or(true),"vars":options.get("vars"),"options":options,"valid":valid})
        }).collect();
    Ok(
        json!({"manifest":manifest,"manifest_source":manifest_source,"profile_source":moli_profile::builtin(id),"onboarding":onboarding,"instances":instances,"configurable":configurable,"fields":if configurable {fields_for(&manifest)} else {Vec::new()}}),
    )
}

pub(crate) fn prepare(input: &Value) -> Result<String, &'static str> {
    let text = input["text"].as_str().ok_or("input")?;
    let id = input["id"].as_str().ok_or("input")?;
    let package_id = input
        .get("profile")
        .map_or(Some("open-meteo"), Value::as_str)
        .ok_or("input")?;
    let package = form_for(text, package_id)?;
    if package["configurable"] != true {
        return Err("unsupported");
    }
    let kind = package["manifest"]["integration"]["driver"]
        .as_str()
        .ok_or("catalogue")?;
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
        driver["kind"] = value(kind);
        let mut options = Table::new();
        if kind == "profile" {
            options["profile"] = value(package_id);
        }
        driver["options"] = Item::Table(options);
        drivers.push(driver);
        drivers.len() - 1
    };
    let driver = drivers.get_mut(index).ok_or("configuration")?;
    if driver.get("kind").and_then(Item::as_str) != Some(kind)
        || (kind == "profile"
            && driver
                .get("options")
                .and_then(|o| o.get("profile"))
                .and_then(Item::as_str)
                != Some(package_id))
    {
        return Err("conflict");
    }
    update_fields(
        driver,
        input,
        package["fields"].as_array().ok_or("catalogue")?,
    )?;
    let result = document.to_string();
    let parsed: toml::Value = toml::from_str(&result).map_err(|_| "configuration")?;
    let options = parsed["driver"][index]["options"].clone();
    if !validate_options(kind, options) {
        return Err("configuration");
    }
    Ok(result)
}

fn update_fields(driver: &mut Table, input: &Value, fields: &[Value]) -> Result<(), &'static str> {
    // The wire format separates vars from driver options, just like official TOML.
    for location in ["vars", "options"] {
        if let Some(values) = input.get(location) {
            let values = values.as_object().ok_or("input")?;
            if values.keys().any(|name| {
                !fields
                    .iter()
                    .any(|f| f["location"] == location && f["name"] == name.as_str())
            }) {
                return Err("input");
            }
        }
    }
    for field in fields {
        let name = field["name"].as_str().ok_or("catalogue")?;
        let location = field["location"].as_str().ok_or("catalogue")?;
        let content = input
            .get(location)
            .and_then(|o| o.get(name))
            .and_then(Value::as_str);
        // An omitted optional field is untouched; an explicitly cleared one is
        // removed so the official driver's default is used, never copied here.
        if content.is_none() && field["required"] == false {
            continue;
        }
        let content = content.ok_or("configuration")?;
        let target = if location == "vars" {
            &mut driver["options"]["vars"]
        } else {
            &mut driver["options"]
        };
        if content.is_empty() && field["required"] == false {
            if let Some(table) = target.as_table_like_mut() {
                table.remove(name);
            }
            continue;
        }
        if content.trim().is_empty() {
            return Err("configuration");
        }
        let updated = if field["numeric"] == true {
            value(content.parse::<i64>().map_err(|_| "configuration")?)
        } else {
            value(content)
        };
        let current = &mut target[name];
        let decoration = current.as_value().map(|v| v.decor().clone());
        *current = updated;
        if let Some(decoration) = decoration {
            *current.as_value_mut().ok_or("configuration")?.decor_mut() = decoration;
        }
    }
    Ok(())
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
    set_enabled(driver, enabled)?;
    Ok(document.to_string())
}

/// Resolve membership through the same canonical catalogue as the UI, then
/// return one candidate for one validation and `ConfigurationTransaction` commit.
pub(crate) fn toggle_package(input: &Value) -> Result<String, &'static str> {
    let text = input["text"].as_str().ok_or("input")?;
    let profile = input["profile"].as_str().ok_or("input")?;
    let enabled = input["enabled"].as_bool().ok_or("input")?;
    let package = form_for(text, profile)?;
    let instances = package["instances"].as_array().ok_or("catalogue")?;
    if instances.is_empty() {
        return Err("conflict");
    }
    let ids: Vec<_> = instances.iter().filter_map(|i| i["id"].as_str()).collect();
    if ids.len() != instances.len() || ids.iter().any(|id| id.is_empty()) {
        return Err("configuration");
    }
    let mut document: DocumentMut = text.parse().map_err(|_| "configuration")?;
    let drivers = document
        .get_mut("driver")
        .and_then(Item::as_array_of_tables_mut)
        .ok_or("conflict")?;
    if ids.iter().any(|id| {
        drivers
            .iter()
            .filter(|d| d.get("id").and_then(Item::as_str) == Some(*id))
            .count()
            != 1
    }) {
        return Err("configuration");
    }
    for driver in drivers.iter_mut() {
        if driver
            .get("id")
            .and_then(Item::as_str)
            .is_some_and(|id| ids.contains(&id))
        {
            set_enabled(driver, enabled)?;
        }
    }
    Ok(document.to_string())
}

fn set_enabled(driver: &mut Table, enabled: bool) -> Result<(), &'static str> {
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
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn package_activation_preserves_other_packages_and_handles_implicit_and_mixed_states() {
        let before = "# canonical\n[[driver]] # implicit ON\nid = 'one'\nkind = 'profile'\n[driver.options]\nprofile = 'open-meteo'\nvars = { lat = '0', lon = '0' } # keep options\n[[driver]]\nid = 'two'\nkind = 'profile'\nenabled = false # keep flag\n[driver.options]\nprofile = 'open-meteo'\nvars = { lat = '1', lon = '2' }\n[[driver]] # another profile\nid = 'air'\nkind = 'profile'\nenabled = false\n[driver.options]\nprofile = 'open-meteo-air'\nvars = { lat = '0', lon = '0' }\n[[driver]]\nid = 'host'\nkind = 'host'\n[guard]\nprotect_unassigned = true # keep guard\n";
        let initial = form_for(before, "open-meteo").unwrap();
        assert_eq!(initial["instances"][0]["enabled"], true);
        assert_eq!(initial["instances"][1]["enabled"], false);
        let all =
            toggle_package(&json!({"text":before,"profile":"open-meteo","enabled":true})).unwrap();
        let expected = before
            .replacen(
                "kind = 'profile'\n",
                "kind = 'profile'\nenabled = true\n",
                1,
            )
            .replace("enabled = false # keep flag", "enabled = true # keep flag");
        assert_eq!(all, expected);
        let none =
            toggle_package(&json!({"text":all,"profile":"open-meteo","enabled":false})).unwrap();
        assert_eq!(
            none,
            expected.replacen("enabled = true", "enabled = false", 2)
        );
        assert!(
            form_for(&none, "open-meteo").unwrap()["instances"]
                .as_array()
                .unwrap()
                .iter()
                .all(|i| i["enabled"] == false)
        );
        let native =
            toggle_package(&json!({"text":before,"profile":"host","enabled":false})).unwrap();
        assert_eq!(
            native,
            before.replace("kind = 'host'", "kind = 'host'\nenabled = false")
        );
        assert!(toggle_package(&json!({"text":before,"profile":"sonos","enabled":true})).is_err());
        assert!(
            toggle_package(&json!({"text":before,"profile":"missing","enabled":true})).is_err()
        );
        let duplicate = before.replace("id = 'air'", "id = 'one'");
        assert!(
            toggle_package(&json!({"text":duplicate,"profile":"open-meteo","enabled":false}))
                .is_err()
        );
    }

    #[test]
    fn configuration_files_keep_original_sources_and_selected_instances() {
        let text = "# saved file\n[[driver]] # first\nid = 'plug-one'\nkind = 'profile'\nenabled = false\n[driver.options]\nprofile = 'meross-mss310'\nhost = '192.168.50.21'\n[driver.options.vars]\nexpected_uuid = '00000000000000000000000000000001'\n\n[[driver]]\nid = 'other'\nkind = 'sonos'\n[driver.options]\nhost = '192.168.50.22'\n\n[[driver]] # second\nid = 'plug-two'\nkind = 'profile'\n[driver.options]\nprofile = 'meross-mss310'\nhost = '192.168.50.23'\n";
        let state = catalogue(text).unwrap();
        let packages = state["packages"].as_array().unwrap();
        let meross = packages
            .iter()
            .find(|p| p["manifest"]["integration"]["id"] == "meross-mss310")
            .unwrap();
        let source = meross["configuration_source"].as_str().unwrap();
        assert!(source.contains("[[driver]] # first"), "{source}");
        assert!(source.contains("[[driver]] # second"));
        assert!(source.contains("expected_uuid = '00000000000000000000000000000001'"));
        assert!(!source.contains("id = 'other'"));
        assert_eq!(
            meross["manifest_source"],
            include_str!("../../../integrations/meross-mss310/integration.toml")
        );
        assert_eq!(
            meross["profile_source"].as_str(),
            moli_profile::builtin("meross-mss310")
        );
        let native = packages
            .iter()
            .find(|p| p["manifest"]["integration"]["id"] == "host")
            .unwrap();
        assert!(native["profile_source"].is_null());
        assert_eq!(native["configuration_source"], "");
    }

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
        assert_eq!(
            editable,
            [
                "daikin-brp069",
                "ipp",
                "klipper",
                "meross-mss310",
                "moonraker",
                "open-meteo",
                "open-meteo-air",
                "solarman",
                "sonos",
                "tempo"
            ]
        );
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
    #[test]
    fn simple_options_use_official_types_and_unambiguous_kinds() {
        let cases = [
            ("daikin-brp069", "profile"),
            ("moonraker", "profile"),
            ("klipper", "moonraker"),
            ("solarman", "solarman"),
            ("ipp", "ipp"),
            ("sonos", "sonos"),
        ];
        for (package, kind) in cases {
            let options = if package == "moonraker" {
                json!({"host":"192.0.2.1","port":"7125"})
            } else {
                json!({"host":"192.0.2.1"})
            };
            let text = prepare(
                &json!({"text":"# retained\n","profile":package,"id":"fixture","options":options}),
            )
            .unwrap();
            let parsed: toml::Value = toml::from_str(&text).unwrap();
            assert_eq!(parsed["driver"][0]["kind"].as_str(), Some(kind));
            assert!(
                form_for(&text, package).unwrap()["instances"][0]["valid"]
                    .as_bool()
                    .unwrap()
            );
            if package == "klipper" {
                assert_eq!(
                    form_for(&text, "moonraker").unwrap()["instances"],
                    json!([])
                );
            }
            if package == "moonraker" {
                assert_eq!(form_for(&text, "klipper").unwrap()["instances"], json!([]));
            }
            assert!(
                prepare(
                    &json!({"text":text,"profile":package,"id":"fixture","options":{"host":""}})
                )
                .is_err()
            );
        }
        for (package, name, bad) in [
            ("solarman", "serial", "4294967296"),
            ("solarman", "slave", "256"),
            ("ipp", "port", "65536"),
            ("klipper", "port", "text"),
            ("moonraker", "port", "-1"),
        ] {
            let mut options = json!({"host":"192.0.2.1"});
            options[name] = json!(bad);
            assert!(
                prepare(&json!({"text":"","profile":package,"id":"fixture","options":options}))
                    .is_err()
            );
        }
        assert!(prepare(&json!({"text":"","profile":"sonos","id":"fixture","options":{"host":"192.0.2.1","password":"never-written"}})).is_err());
    }

    #[test]
    fn optional_native_fields_roundtrip_comments_and_defaults_without_touching_other_sections() {
        let before = "# retained\n[[driver]]\nid = \"solar\"\nkind = \"solarman\"\nenabled = false # flag\n[driver.options]\nhost = \"192.0.2.1\" # address\nport = 8899 # port\nserial = 123\nslave = 1\ntimezone = \"Europe/Paris\"\n[guard]\nprotect_unassigned = true # guard\n";
        let text=prepare(&json!({"text":before,"profile":"solarman","id":"solar","options":{"host":"192.0.2.2","serial":"4294967295"}})).unwrap();
        assert_eq!(
            text,
            before
                .replace("192.0.2.1", "192.0.2.2")
                .replace("serial = 123", "serial = 4294967295")
        );
        let defaulted=prepare(&json!({"text":text,"profile":"solarman","id":"solar","options":{"host":"192.0.2.2","serial":""}})).unwrap();
        assert!(!defaulted.contains("serial ="));
        assert!(defaulted.contains("port = 8899 # port"));
        let mut parsed: toml::Value = toml::from_str(&defaulted).unwrap();
        parsed["driver"][0]["options"]["port"] = toml::Value::String("invalid".into());
        assert_eq!(
            form_for(&toml::to_string(&parsed).unwrap(), "solarman").unwrap()["instances"][0]["valid"],
            false
        );
        let sonos = "[[driver]]\nid='speaker'\nkind='sonos'\n[driver.options]\nhost='192.0.2.1'\n[driver.options.announce]\nmedia_base='http://192.0.2.2:8790'\ntts='192.0.2.2:10200'\nvoice='test'\nvolume=10\n";
        // Optional nested announcement settings are outside this base editor;
        // never replaced when editing just the host.
        let updated = prepare(
            &json!({"text":sonos,"profile":"sonos","id":"speaker","options":{"host":"192.0.2.3"}}),
        );
        let updated = updated.unwrap();
        assert!(updated.contains("[driver.options.announce]"));
        assert!(updated.contains("volume=10"));
    }
}
