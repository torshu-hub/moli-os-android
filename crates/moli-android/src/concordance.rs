//! Pure, bounded preparation. No filesystem, credentials or device operations.
use std::collections::{BTreeMap, BTreeSet};

use serde_json::{Map, Value, json};

type Result<T> = std::result::Result<T, &'static str>;

fn managed(id: &str) -> bool {
    id.starts_with("android-meross-")
        || matches!(
            id,
            "android-import"
                | "android-demo"
                | "android-weather"
                | "android-tablet"
                | "android-printer"
                | "android-gateway"
        )
}

fn drivers(config: &Value) -> Result<BTreeMap<String, Value>> {
    let mut result = BTreeMap::new();
    if let Some(rows) = config.get("driver") {
        for row in rows.as_array().ok_or("configuration")? {
            let id = row["id"].as_str().ok_or("configuration")?;
            if result.insert(id.to_owned(), row.clone()).is_some() {
                return Err("configuration");
            }
        }
    }
    Ok(result)
}

fn parse(text: &str) -> Result<Value> {
    let config: toml::Value = toml::from_str(text).map_err(|_| "configuration")?;
    serde_json::to_value(config).map_err(|_| "configuration")
}

// Missing is distinct from null. Arrays are a unit: never merge by position/name.
fn merge(
    old: Option<&Value>,
    current: Option<&Value>,
    next: Option<&Value>,
) -> Result<Option<Value>> {
    if current == next || next == old {
        return Ok(current.cloned());
    }
    if current == old {
        return Ok(next.cloned());
    }
    if let (Some(Value::Object(c)), Some(Value::Object(n))) = (current, next) {
        let empty = Map::new();
        let o = match old {
            Some(Value::Object(o)) => o,
            None => &empty,
            _ => return Err("configuration_conflict"),
        };
        let keys: BTreeSet<_> = o.keys().chain(c.keys()).chain(n.keys()).collect();
        let mut merged = Map::new();
        for k in keys {
            if let Some(v) = merge(o.get(k), c.get(k), n.get(k))? {
                merged.insert(k.clone(), v);
            }
        }
        return Ok(Some(Value::Object(merged)));
    }
    Err("configuration_conflict")
}

// Only these private synthetic endpoints have an Android-owned ephemeral port.
fn endpoint(row: &mut Value, next: &Value) -> Result<()> {
    if !matches!(
        row["id"].as_str(),
        Some("android-tablet" | "android-import")
    ) || next["options"]["host"] != "127.0.0.1"
    {
        return Ok(());
    }
    if row["kind"] != "profile"
        || row["options"]["host"] != "127.0.0.1"
        || row["options"]["profile"] != next["options"]["profile"]
    {
        return Err("configuration_conflict");
    }
    row["options"]["port"] = next["options"]["port"].clone();
    Ok(())
}

// Engine semantics: an absent TOML enabled flag means true. Normalize only
// Meross comparisons so a legacy omitted flag and an explicit true agree.
fn activation(row: &mut Value) -> Result<()> {
    if row["id"]
        .as_str()
        .is_some_and(|id| id.starts_with("android-meross-"))
    {
        let enabled = match row.get("enabled") {
            None => true,
            Some(Value::Bool(v)) => *v,
            _ => return Err("configuration_conflict"),
        };
        row["enabled"] = json!(enabled);
    }
    Ok(())
}

fn identities(rows: &BTreeMap<String, Value>) -> Result<BTreeMap<String, String>> {
    let mut uuids = BTreeMap::new();
    for (id, row) in rows
        .iter()
        .filter(|(id, _)| id.starts_with("android-meross-"))
    {
        if let Some(uuid) = row["options"]["vars"]["expected_uuid"].as_str()
            && uuids.insert(uuid.to_owned(), id.clone()).is_some()
        {
            return Err("configuration_conflict");
        }
    }
    Ok(uuids)
}

fn projection(home: &Value) -> Value {
    let mut result = json!({});
    for key in [
        "tablet",
        "printer",
        "gateway",
        "printer_id",
        "meross",
        "weather",
        "location",
    ] {
        if let Some(v) = home.get("android").and_then(|a| a.get(key)) {
            result["android"][key] = v.clone();
        }
    }
    // outdoor.weather belongs to Moli: Android only seeds it when absent.
    result
}

// Dashboard adapter metadata follows the retained driver values, including Moli
// edits. The original inventory already reads the effective engine configuration.
fn effective_home(home: &mut Value, rows: &BTreeMap<String, Value>) -> Result<()> {
    let Some(android) = home.get_mut("android") else {
        return Ok(());
    };
    let android = android.as_object_mut().ok_or("configuration")?;
    let enabled = |id: &str| rows.get(id).filter(|r| r["enabled"] != false);
    for (key, id) in [
        ("printer", "android-printer"),
        ("gateway", "android-gateway"),
    ] {
        android.insert(key.to_owned(), json!(enabled(id).is_some()));
    }
    android.insert(
        "printer_id".to_owned(),
        enabled("android-printer")
            .and_then(|r| r["options"]["host"].as_str())
            .map_or(Value::Null, |host| json!(format!("android-printer:{host}"))),
    );
    if let Some(entries) = android.get_mut("meross").and_then(Value::as_array_mut) {
        for entry in entries {
            let id = format!(
                "android-meross-{}",
                entry["id"].as_str().ok_or("configuration")?
            );
            entry["enabled"] = json!(enabled(&id).is_some());
            if let Some(row) = rows.get(&id) {
                for key in ["name", "host", "port"] {
                    if let Some(v) = row["options"].get(key) {
                        entry[key] = v.clone();
                    }
                }
            }
        }
    }
    if enabled("android-weather").is_none() {
        android.insert("weather".to_owned(), Value::Null);
    }
    Ok(())
}

fn reconcile_home(
    request: &Value,
    baseline: Option<&Value>,
    c: &BTreeMap<String, Value>,
) -> Result<(Value, Value)> {
    let home = request.get("home").cloned().unwrap_or_else(|| json!({}));
    let mut proposal = request
        .get("home_proposal")
        .cloned()
        .unwrap_or_else(|| home.clone());
    if !home.is_object() || !proposal.is_object() {
        return Err("configuration");
    }
    effective_home(&mut proposal, c)?;
    let selected_home = projection(&proposal);
    let current_home = projection(&home);
    // Legacy adapter fields must agree before adoption; custom fields outside
    // the projection stay untouched. An ambiguous managed value blocks startup.
    let empty_home = json!({});
    let previous_home = baseline.map_or(&empty_home, |b| &b["home"]);
    let merged_home = merge(
        Some(previous_home),
        Some(&current_home),
        Some(&selected_home),
    )?
    .ok_or("configuration")?;
    let mut home = home;
    if let Some(android) = merged_home.get("android") {
        if home.get("android").is_some_and(|a| !a.is_object()) {
            return Err("configuration_conflict");
        }
        if home.get("android").is_none() {
            home["android"] = json!({});
        }
        for (key, v) in android.as_object().ok_or("configuration")? {
            home["android"][key] = v.clone();
        }
    }
    if home.get("outdoor").is_some_and(|v| !v.is_object()) {
        return Err("configuration_conflict");
    }
    if home.get("outdoor").is_none() {
        home["outdoor"] = json!({});
    }
    if home["outdoor"].get("weather").is_none()
        && let Some(weather) = proposal.get("outdoor").and_then(|v| v.get("weather"))
    {
        home["outdoor"]["weather"] = weather.clone();
    }
    Ok((home, selected_home))
}

// Retain original TOML types (including datetimes) for every unchanged leaf.
// JSON is only the comparison/baseline format, never the serialization authority
// for unmanaged settings or manually added options.
fn apply_json(original: &toml::Value, before: &Value, after: &Value) -> Result<toml::Value> {
    if before == after {
        return Ok(original.clone());
    }
    if let (Some(table), Some(next)) = (original.as_table(), after.as_object()) {
        let mut result = toml::Table::new();
        for (key, value) in next {
            let v = if let Some(old) = table.get(key) {
                apply_json(old, &before[key], value)?
            } else {
                toml::Value::try_from(value).map_err(|_| "configuration")?
            };
            result.insert(key.clone(), v);
        }
        return Ok(toml::Value::Table(result));
    }
    toml::Value::try_from(after).map_err(|_| "configuration")
}

fn serialize_config(original: &mut toml::Value, c: &BTreeMap<String, Value>) -> Result<String> {
    let original_rows = original
        .get("driver")
        .and_then(toml::Value::as_array)
        .cloned()
        .unwrap_or_default();
    let mut retained = Vec::new();
    let mut seen = BTreeSet::new();
    for old in original_rows {
        let id = old["id"].as_str().ok_or("configuration")?;
        if let Some(row) = c.get(id) {
            retained.push(apply_json(
                &old,
                &serde_json::to_value(&old).map_err(|_| "configuration")?,
                row,
            )?);
            seen.insert(id.to_owned());
        }
    }
    for (_, row) in c.iter().filter(|(id, _)| !seen.contains(*id)) {
        retained.push(toml::Value::try_from(row).map_err(|_| "configuration")?);
    }
    original
        .as_table_mut()
        .ok_or("configuration")?
        .insert("driver".to_owned(), toml::Value::Array(retained));
    toml::to_string(original).map_err(|_| "configuration")
}

pub fn prepare(input: &str) -> Result<Value> {
    let request: Value = serde_json::from_str(input).map_err(|_| "configuration")?;
    let current_text = request["config"].as_str().ok_or("configuration")?;
    if current_text.trim().is_empty()
        && (request["config_present"] == true
            || request.get("baseline").is_some_and(|b| !b.is_null()))
    {
        return Err("configuration");
    }
    if !current_text.trim().is_empty() {
        moli_os::validate_configuration(current_text).map_err(|_| "configuration")?;
    }
    let mut original: toml::Value = toml::from_str(current_text).map_err(|_| "configuration")?;
    let current = parse(current_text)?;
    let proposed = parse(&crate::profiles::configuration(input)?)?;
    let mut c = drivers(&current)?;
    let n = drivers(&proposed)?;
    let baseline = request.get("baseline").filter(|v| !v.is_null());
    if baseline
        .is_some_and(|b| b["version"] != 1 || !b["drivers"].is_object() || !b["home"].is_object())
    {
        return Err("configuration_conflict");
    }
    let mut o: BTreeMap<String, Value> = match baseline {
        Some(b) => {
            serde_json::from_value(b["drivers"].clone()).map_err(|_| "configuration_conflict")?
        }
        None => BTreeMap::new(),
    };
    // UUID is hardware identity; an existing binding cannot silently move to another
    // internal instance, nor can an instance acquire/lose/swap its known UUID.
    let known = identities(&c)?;
    let next = identities(&n)?;
    for (uuid, id) in &known {
        if next.get(uuid).is_some_and(|other| other != id) {
            return Err("configuration_conflict");
        }
    }
    for (id, row) in c.iter().filter(|(id, _)| managed(id)) {
        if let Some(new) = n.get(id)
            && row["options"]["vars"]["expected_uuid"] != new["options"]["vars"]["expected_uuid"]
        {
            return Err("configuration_conflict");
        }
    }
    let ids: BTreeSet<_> = c
        .keys()
        .chain(o.keys())
        .chain(n.keys())
        .filter(|id| managed(id))
        .cloned()
        .collect();
    for id in ids {
        // A missing Android selection is not permission to remove an existing driver.
        if c.contains_key(&id) && !n.contains_key(&id) {
            return Err("configuration_conflict");
        }
        if let Some(new) = n.get(&id) {
            if let Some(row) = c.get_mut(&id) {
                activation(row)?;
                endpoint(row, new)?;
            }
            if let Some(row) = o.get_mut(&id) {
                activation(row)?;
                endpoint(row, new)?;
            }
        }
        // No provenance: adopt only exact generated entries (apart from lease port).
        if baseline.is_none() && c.contains_key(&id) && c.get(&id) != n.get(&id) {
            return Err("configuration_conflict");
        }
        if let Some(row) = merge(o.get(&id), c.get(&id), n.get(&id))? {
            c.insert(id, row);
        } else {
            c.remove(&id);
        }
    }
    let config = serialize_config(&mut original, &c)?;
    moli_os::validate_configuration(&config).map_err(|_| "configuration")?;
    let (home, selected_home) = reconcile_home(&request, baseline, &c)?;
    let generated: BTreeMap<_, _> = n.into_iter().filter(|(id, _)| managed(id)).collect();
    Ok(
        json!({"config":config,"home":home,"baseline":{"version":1,"drivers":generated,"home":selected_home}}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> Value {
        let tablet = include_str!("../../../android/app/src/main/assets/tablet.toml");
        let weather = include_str!("../../../android/app/src/main/assets/open-meteo.toml");
        let import =
            include_str!("../../../android/app/src/main/assets/synthetic-temperature.toml");
        let plugs: Vec<_> = (1..=4).map(|i| json!({"id":format!("{i:032x}"),"uuid":format!("{:032x}",i+100),
            "name":format!("Fixture {i}"),"host":format!("192.168.1.{}",40+i),"port":80,"enabled":true})).collect();
        let mut s = json!({"data":"/synthetic","port":1234,"demo":true,
            "config":"[server]\nworker_threads=3\n[guard]\nprotected_rooms=['Fixture room']\n[[driver]]\nid='external'\nkind='helpers'\n",
            "equipment":{"meross":plugs,"printer":{"host":"192.168.1.20","port":631,"path":"/ipp/print"},
            "gateway":{"location":"http://192.168.1.1:5000/rootDesc.xml"}},
            "tablet":{"text":tablet,"sha256":moli_profile::sha256(tablet)},
            "weather":{"text":weather,"sha256":moli_profile::sha256(weather)},
            "import":{"text":import,"sha256":moli_profile::sha256(import)},
            "home":{"rooms":[{"name":"Fixture room","protected":true}],"favorites":["external:lamp"],"outdoor":{"weather":"external:weather","custom":42}}});
        s["home_proposal"] = s["home"].clone();
        s["home_proposal"]["android"] = json!({"tablet":"android-tablet:tablet","printer":true,"gateway":true,
            "printer_id":"android-printer:192.168.1.20","meross":s["equipment"]["meross"],
            "weather":"android-weather:api.open-meteo.com:443","location":"Fixture"});
        s
    }
    fn run(s: &Value) -> Result<Value> {
        prepare(&s.to_string())
    }
    fn again(s: &mut Value, result: &Value) {
        s["config"] = result["config"].clone();
        s["home"] = result["home"].clone();
        s["baseline"] = result["baseline"].clone();
    }
    fn edit(s: &mut Value, id: &str, key: &str, v: &Value) {
        let mut c = parse(s["config"].as_str().unwrap()).unwrap();
        for row in c["driver"].as_array_mut().unwrap() {
            if row["id"] == id {
                row["options"][key] = v.clone();
            }
        }
        s["config"] = json!(toml::to_string(&c).unwrap());
    }
    #[test]
    fn legacy_upgrade_restarts_idempotently_and_keeps_six_devices_and_moli_metadata() {
        let mut s = fixture();
        // Exactly the previous generator's output: no baseline on upgrade.
        s["config"] = json!(crate::profiles::configuration(&s.to_string()).unwrap());
        s["home"] = s["home_proposal"].clone();
        let first = run(&s).unwrap();
        again(&mut s, &first);
        assert_eq!(run(&s).unwrap(), first);
        s["port"] = json!(4321); // New stopped/started synthetic endpoint lease.
        let restart = run(&s).unwrap();
        let parsed = parse(restart["config"].as_str().unwrap()).unwrap();
        assert_eq!(drivers(&parsed).unwrap().len(), 11); // six devices + demo/import/weather/tablet + external
        assert_eq!(parsed["server"]["worker_threads"], 3);
        assert_eq!(parsed["guard"]["protected_rooms"], json!(["Fixture room"]));
        assert_eq!(restart["home"]["rooms"], first["home"]["rooms"]);
        assert_eq!(restart["home"]["outdoor"]["weather"], "external:weather");
        assert_eq!(restart["home"]["favorites"], first["home"]["favorites"]);
        assert_eq!(
            restart["baseline"]["drivers"]["android-tablet"]["options"]["port"],
            4321
        );
    }
    #[test]
    fn moli_only_edits_survive_and_android_conflicts_are_explicit() {
        let mut s = fixture();
        let first = run(&s).unwrap();
        again(&mut s, &first);
        let id = format!("android-meross-{:032x}", 1);
        edit(&mut s, &id, "name", &json!("Moli label"));
        edit(
            &mut s,
            "android-weather",
            "vars",
            &json!({"lat":"42","lon":"3"}),
        );
        s["home"]["android"]["location"] = json!("Moli location");
        let retained = run(&s).unwrap();
        assert_eq!(
            drivers(&parse(retained["config"].as_str().unwrap()).unwrap()).unwrap()[&id]["options"]
                ["name"],
            "Moli label"
        );
        assert_eq!(
            retained["home"]["android"]["meross"][0]["name"],
            "Moli label"
        );
        assert_eq!(retained["home"]["android"]["location"], "Moli location");
        again(&mut s, &retained);
        assert_eq!(run(&s).unwrap(), retained);
        s["equipment"]["meross"][0]["name"] = json!("Android label");
        assert_eq!(run(&s), Err("configuration_conflict"));
    }
    #[test]
    fn meross_rename_and_reorder_keep_uuid_internal_id_and_manual_entries_without_uuid() {
        let mut s = fixture();
        s["equipment"]["meross"][3]
            .as_object_mut()
            .unwrap()
            .remove("uuid");
        let first = run(&s).unwrap();
        again(&mut s, &first);
        let before = drivers(&parse(first["config"].as_str().unwrap()).unwrap()).unwrap();
        s["equipment"]["meross"][0]["name"] = json!("Renamed in Meross");
        s["equipment"]["meross"].as_array_mut().unwrap().reverse();
        s["home_proposal"]["android"]["meross"] = s["equipment"]["meross"].clone();
        let renamed = run(&s).unwrap();
        let after = drivers(&parse(renamed["config"].as_str().unwrap()).unwrap()).unwrap();
        assert_eq!(
            before.keys().collect::<Vec<_>>(),
            after.keys().collect::<Vec<_>>()
        );
        for (id, row) in before {
            assert_eq!(row["options"]["vars"], after[&id]["options"]["vars"]);
        }
        let id = format!("android-meross-{:032x}", 1);
        assert_eq!(after[&id]["options"]["name"], "Renamed in Meross");
        assert!(
            after[&format!("android-meross-{:032x}", 4)]["options"]
                .get("vars")
                .is_none()
        );
        s["equipment"]["meross"][3]["id"] = json!(format!("{:032x}", 99));
        assert_eq!(run(&s), Err("configuration_conflict"));
    }
    #[test]
    fn meross_explicit_disable_and_reenable_retain_identity_and_moli_options_from_v1() {
        let mut s = fixture();
        let first = run(&s).unwrap();
        again(&mut s, &first);
        // Provenance/configuration written by 4c1dd6e used implicit enabled=true.
        let mut legacy = parse(s["config"].as_str().unwrap()).unwrap();
        for row in legacy["driver"].as_array_mut().unwrap() {
            if row["id"].as_str().unwrap().starts_with("android-meross-") {
                row.as_object_mut().unwrap().remove("enabled");
            }
        }
        s["config"] = json!(toml::to_string(&legacy).unwrap());
        for row in s["baseline"]["drivers"]
            .as_object_mut()
            .unwrap()
            .values_mut()
        {
            row.as_object_mut().unwrap().remove("enabled");
        }
        let id = format!("android-meross-{:032x}", 1);
        edit(&mut s, &id, "name", &json!("Retained Moli label"));
        s["equipment"]["meross"][0]["enabled"] = json!(false);
        let disabled = run(&s).unwrap();
        let rows = drivers(&parse(disabled["config"].as_str().unwrap()).unwrap()).unwrap();
        assert_eq!(rows.len(), 11);
        assert_eq!(rows[&id]["enabled"], false);
        assert_eq!(rows[&id]["options"]["name"], "Retained Moli label");
        assert_eq!(
            rows[&id]["options"]["vars"]["expected_uuid"],
            s["equipment"]["meross"][0]["uuid"]
        );
        assert_eq!(disabled["home"]["android"]["meross"][0]["enabled"], false);
        assert_eq!(disabled["home"]["rooms"], first["home"]["rooms"]);
        again(&mut s, &disabled);
        assert_eq!(run(&s).unwrap(), disabled);
        s["equipment"]["meross"][0]["enabled"] = json!(true);
        let enabled = run(&s).unwrap();
        let enabled_rows = drivers(&parse(enabled["config"].as_str().unwrap()).unwrap()).unwrap();
        assert_eq!(
            rows.keys().collect::<Vec<_>>(),
            enabled_rows.keys().collect::<Vec<_>>()
        );
        assert_eq!(enabled_rows[&id]["enabled"], true);
        assert_eq!(enabled_rows[&id]["options"], rows[&id]["options"]);
        assert_eq!(enabled["home"]["android"]["meross"][0]["enabled"], true);
        again(&mut s, &enabled);
        assert_eq!(run(&s).unwrap(), enabled);
        s["equipment"]["meross"].as_array_mut().unwrap().remove(0);
        assert_eq!(run(&s), Err("configuration_conflict"));
    }

    #[test]
    fn disabling_does_not_resolve_ambiguous_legacy_state_or_overwrite_conflicting_moli_edits() {
        let mut s = fixture();
        s["config"] = json!(crate::profiles::configuration(&s.to_string()).unwrap());
        s["equipment"]["meross"][0]["enabled"] = json!(false);
        assert_eq!(run(&s), Err("configuration_conflict")); // No baseline: still refuse ambiguity.
        let mut s = fixture();
        let first = run(&s).unwrap();
        again(&mut s, &first);
        let id = format!("android-meross-{:032x}", 1);
        let mut config = parse(s["config"].as_str().unwrap()).unwrap();
        config["driver"]
            .as_array_mut()
            .unwrap()
            .iter_mut()
            .find(|r| r["id"] == id)
            .unwrap()["enabled"] = json!(false);
        s["config"] = json!(toml::to_string(&config).unwrap());
        let retained = run(&s).unwrap();
        assert_eq!(
            drivers(&parse(retained["config"].as_str().unwrap()).unwrap()).unwrap()[&id]["enabled"],
            false
        );
        assert_eq!(retained["home"]["android"]["meross"][0]["enabled"], false);
        edit(&mut s, &id, "name", &json!("Moli edit"));
        s["equipment"]["meross"][0]["enabled"] = json!(false);
        s["equipment"]["meross"][0]["name"] = json!("Android edit");
        assert_eq!(run(&s), Err("configuration_conflict"));
    }

    #[test]
    fn unrelated_toml_types_and_manual_options_are_preserved() {
        let mut s = fixture();
        s["config"] = json!(format!(
            "{}\n[driver.options]\ndate = 2026-01-02\n",
            s["config"].as_str().unwrap()
        ));
        let first = run(&s).unwrap();
        again(&mut s, &first);
        let mut config: toml::Value = toml::from_str(s["config"].as_str().unwrap()).unwrap();
        let date: toml::Value = toml::from_str("date = 2026-01-02").unwrap();
        let rows = config["driver"].as_array_mut().unwrap();
        rows.iter_mut()
            .find(|r| r["id"].as_str() == Some("android-printer"))
            .unwrap()["options"]
            .as_table_mut()
            .unwrap()
            .insert("manual_date".to_owned(), date["date"].clone());
        s["config"] = json!(toml::to_string(&config).unwrap());
        let retained = run(&s).unwrap();
        let parsed: toml::Value = toml::from_str(retained["config"].as_str().unwrap()).unwrap();
        for row in parsed["driver"].as_array().unwrap() {
            if row["id"].as_str() == Some("external") {
                assert!(row["options"]["date"].is_datetime());
            }
            if row["id"].as_str() == Some("android-printer") {
                assert!(row["options"]["manual_date"].is_datetime());
            }
        }
    }

    #[test]
    fn ambiguity_missing_selection_invalid_input_and_duplicate_uuid_never_generate_replacements() {
        let mut s = fixture();
        s["config"] = json!(crate::profiles::configuration(&s.to_string()).unwrap());
        edit(&mut s, "android-printer", "port", &json!(8631));
        assert_eq!(run(&s), Err("configuration_conflict")); // Unproven legacy edit.
        let mut s = fixture();
        let first = run(&s).unwrap();
        again(&mut s, &first);
        s["equipment"] = json!({});
        assert_eq!(run(&s), Err("configuration_conflict"));
        s["config"] = json!("");
        assert_eq!(run(&s), Err("configuration"));
        s["config"] = json!("this is invalid TOML");
        assert_eq!(run(&s), Err("configuration"));
        let mut s = fixture();
        s["equipment"]["meross"][1]["uuid"] = s["equipment"]["meross"][0]["uuid"].clone();
        assert_eq!(run(&s), Err("meross_duplicate"));
        let mut s = fixture();
        s["config"] = json!("[server]\nworker_threads=0");
        assert_eq!(run(&s), Err("configuration"));
        let mut s = fixture();
        s["baseline"] = json!({"version":99});
        assert_eq!(run(&s), Err("configuration_conflict"));
    }
}
