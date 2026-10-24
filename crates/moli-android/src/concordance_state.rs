//! Android-owned path relocation, explicit inactive records and form projection.
//! No file access; checked profile bytes and the existing merge supply provenance.
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};
type Rows = BTreeMap<String, Value>;
type Result<T> = std::result::Result<T, &'static str>;

pub fn inactive(request: &Value, current: &Rows, previous: &Rows, next: &mut Rows) -> Result<()> {
    let Some(ids) = request.get("selected").and_then(|s| s.get("inactive")) else {
        return Ok(());
    };
    let ids = ids
        .as_array()
        .filter(|a| a.len() <= 32)
        .ok_or("configuration_conflict")?;
    let mut seen = BTreeSet::new();
    for id in ids {
        let id = id
            .as_str()
            .filter(|id| super::concordance::managed(id) && *id != "android-tablet")
            .ok_or("configuration_conflict")?;
        if !seen.insert(id) {
            return Err("configuration_conflict");
        }
        if !next.contains_key(id) {
            if let Some(old) = previous.get(id) {
                next.insert(id.to_owned(), old.clone());
            } else if current.contains_key(id) {
                return Err("configuration_conflict");
            } else {
                continue;
            }
        }
        next.get_mut(id).ok_or("configuration_conflict")?["enabled"] = json!(false);
    }
    Ok(())
}

fn absolute_root(root: &str) -> bool {
    root.starts_with('/')
        && root.len() > 1
        && !root.ends_with('/')
        && !root.contains("//")
        && !root.split('/').any(|p| p == "." || p == "..")
}

pub fn relocate(current: &mut Rows, previous: &mut Rows, next: &mut Rows) -> Result<()> {
    let mut changes = Vec::new();
    let mut roots = BTreeSet::new();
    let mut destinations = BTreeMap::new();
    for (id, key) in [
        ("android-tablet", "profile"),
        ("android-import", "profile"),
        ("android-weather", "profile"),
        ("android-demo", "fixture"),
    ] {
        let Some(new) = next.get(id) else {
            continue;
        };
        let Some(old) = previous.get(id).or_else(|| current.get(id)) else {
            continue;
        };
        let Some(target) = new["options"][key].as_str() else {
            continue;
        };
        let Some(source) = old["options"][key].as_str() else {
            continue;
        };
        let owned = |path: &str| -> Option<(String, String)> {
            if key == "profile" {
                let (root, name) = path.rsplit_once("/android-profiles/")?;
                let hash = name.strip_suffix(".toml")?;
                if !absolute_root(root)
                    || hash.len() != 64
                    || !hash
                        .bytes()
                        .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
                {
                    return None;
                }
                Some((root.to_owned(), name.to_owned()))
            } else {
                let root = path.strip_suffix("/android-demo.json")?;
                absolute_root(root).then(|| (root.to_owned(), "android-demo.json".to_owned()))
            }
        };
        let (source_root, source_name) = owned(source).ok_or("configuration_conflict")?;
        let (target_root, target_name) = owned(target).ok_or("configuration_conflict")?;
        roots.insert(source_root.clone());
        if source == target {
            continue;
        }
        destinations.insert(source_root, target_root);
        if source_name != target_name {
            // A freshly approved digest is a proposal change, not a relocation.
            // Only the old proposal proves which installed path it may replace.
            if !previous.contains_key(id) {
                return Err("configuration_conflict");
            }
            continue;
        }
        changes.push((id, key, source.to_owned(), target.to_owned()));
    }
    if roots.len() > 1 {
        return Err("configuration_conflict");
    }
    // Inactive approved profiles are not loaded or re-approved. Their known
    // Android-owned paths follow the same proven root, with no byte/path read.
    if let Some((source_root, target_root)) = destinations.iter().next() {
        for id in ["android-import", "android-weather"] {
            if next.get(id).is_some_and(|r| r["enabled"] == false) {
                let prefix = format!("{source_root}/android-profiles/");
                let source = next[id]["options"]["profile"].as_str().unwrap_or("");
                if let Some(name) = source.strip_prefix(&prefix)
                    && name.strip_suffix(".toml").is_some_and(|sha| {
                        sha.len() == 64
                            && sha
                                .bytes()
                                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
                    })
                {
                    changes.push((
                        id,
                        "profile",
                        source.to_owned(),
                        format!("{target_root}/android-profiles/{name}"),
                    ));
                }
            }
        }
    }
    for (id, key, source, target) in changes {
        for rows in [&mut *current, &mut *previous, &mut *next] {
            if let Some(row) = rows.get_mut(id)
                && row["options"][key] == source
            {
                row["options"][key] = json!(target);
            }
        }
    }
    Ok(())
}

fn enabled(row: &Value) -> bool {
    row["enabled"] != false
}
fn selected_item(selected: &Value, key: &str) -> Option<Value> {
    selected
        .get(key)
        .or_else(|| selected.get("retained").and_then(|r| r.get(key)))
        .cloned()
}
fn place(selected: &mut Value, key: &str, value: Value, active: bool) {
    selected
        .as_object_mut()
        .expect("validated selection")
        .remove(key);
    if selected.get("retained").is_none() {
        selected["retained"] = json!({});
    }
    selected["retained"]
        .as_object_mut()
        .expect("validated retained selection")
        .remove(key);
    if active {
        selected[key] = value;
    } else {
        selected["retained"][key] = value;
    }
}

fn profile_info(
    request: &Value,
    selected: &Value,
    key: &str,
    options: &Value,
    active: bool,
) -> Result<Value> {
    let mut info = selected_item(selected, key)
        .filter(Value::is_object)
        .ok_or("configuration_conflict")?;
    if options["host"] == "api.open-meteo.com" {
        let coordinate = |key: &str, limit: f64| {
            options["vars"][key]
                .as_str()
                .and_then(|v| v.parse::<f64>().ok())
                .filter(|v| v.is_finite() && v.abs() <= limit)
                .ok_or("configuration_conflict")
        };
        let old = request
            .get("baseline")
            .and_then(|b| b["home"]["android"].get("location"));
        let current = request["home"]["android"].get("location");
        let next = request["home_proposal"]["android"]
            .get("location")
            .or_else(|| info["location"].get("label"));
        let label = if active && key == "weather" {
            super::concordance::merge(old, current, next)?
        } else {
            info["location"].get("label").cloned()
        }
        .unwrap_or_else(|| json!("Paris (démo)"));
        if !label
            .as_str()
            .is_some_and(|s| !s.is_empty() && s.len() <= 160)
            || options["port"] != 443
        {
            return Err("configuration_conflict");
        }
        info["location"] = json!({"label":label,"latitude":coordinate("lat",90.0)?,"longitude":coordinate("lon",180.0)?});
    } else if options["host"] != "127.0.0.1" {
        return Err("configuration_conflict");
    }
    Ok(info)
}

/// Project only fields the existing Android forms understand. Failure leaves
/// the effective Moli configuration authoritative; edit mode becomes read-only.
pub fn preferences(request: &Value, rows: &Rows, proposed: &Rows) -> Result<(Value, Value, Value)> {
    let mut selected = request["selected"].clone();
    if !selected.is_object() || selected.get("retained").is_some_and(|r| !r.is_object()) {
        return Err("configuration_conflict");
    }
    let mut equipment = json!({"meross":[]});
    let mut inactive = Vec::new();
    for (id, row) in rows
        .iter()
        .filter(|(id, _)| super::concordance::managed(id))
    {
        if !enabled(row)
            && matches!(
                id.as_str(),
                "android-import" | "android-weather" | "android-demo"
            )
        {
            inactive.push(id.clone());
        }
        let options = &row["options"];
        match id.as_str() {
            "android-printer" => {
                if row["kind"] != "ipp" || options["read_only"] != true {
                    return Err("configuration_conflict");
                }
                equipment["printer"] = json!({"host":options["host"],"port":options["port"],"path":options["path"],"enabled":enabled(row)});
            }
            "android-gateway" => {
                if row["kind"] != "igd" || options["discovery"] != false {
                    return Err("configuration_conflict");
                }
                equipment["gateway"] =
                    json!({"location":options["location"],"enabled":enabled(row)});
            }
            "android-demo" => {
                if row["kind"] != "demo" {
                    return Err("configuration_conflict");
                }
                place(&mut selected, "demo", json!(true), enabled(row));
            }
            "android-import" | "android-weather" => {
                let key = if id == "android-import" {
                    "import"
                } else {
                    "weather"
                };
                if row["kind"] != "profile"
                    || Some(&options["profile"])
                        != proposed.get(id).map(|n| &n["options"]["profile"])
                {
                    return Err("configuration_conflict");
                }
                let info = profile_info(request, &selected, key, options, enabled(row))?;
                place(&mut selected, key, info, enabled(row));
            }
            "android-tablet" => (),
            _ if id.starts_with("android-meross-") => {
                if row["kind"] != "profile"
                    || options["profile"] != "meross-mss310"
                    || options["discovery"] != false
                {
                    return Err("configuration_conflict");
                }
                let mut entry = json!({"id":id.trim_start_matches("android-meross-"),"name":options["name"],"host":options["host"],"port":options["port"],"enabled":enabled(row)});
                if let Some(uuid) = options.get("vars").and_then(|v| v.get("expected_uuid")) {
                    entry["uuid"] = uuid.clone();
                }
                equipment["meross"]
                    .as_array_mut()
                    .ok_or("configuration_conflict")?
                    .push(entry);
            }
            _ => return Err("configuration_conflict"),
        }
    }
    // Keep Android row ordering; UUID/internal ID comparisons, never names/positions.
    if let Some(old) = request["equipment"]["meross"].as_array() {
        equipment["meross"]
            .as_array_mut()
            .ok_or("configuration_conflict")?
            .sort_by_key(|e| {
                old.iter()
                    .position(|v| v["id"] == e["id"])
                    .unwrap_or(usize::MAX)
            });
    }
    equipment = crate::equipment::validate(&equipment)?;
    selected["inactive"] = json!(inactive);
    let mut spec = request.clone();
    spec["selected"] = selected.clone();
    spec["equipment"] = equipment.clone();
    spec["demo"] = json!(selected_item(&selected, "demo").is_some_and(|v| v == true));
    for key in ["weather", "import"] {
        if let Some(info) = selected_item(&selected, key) {
            // Checked text/SHA stay in memory; only location is projected back.
            if let Some(location) = info.get("location")
                && spec[key].get("text").is_some()
            {
                spec[key]["location"] = location.clone();
            }
        }
    }
    Ok((selected, equipment, spec))
}
