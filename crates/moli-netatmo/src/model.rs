//! Join by immutable native ids, never names or response order. Cloud time is
//! explicitly NOT a timestamp proving when a thermostat physically measured.
use std::collections::{BTreeMap, BTreeSet};

use moli_core::Value;
use serde_json::Value as Json;

use crate::client::Failure;

type Result<T> = std::result::Result<T, Failure>;
const MAX_ITEMS: usize = 128;

#[derive(Debug)]
pub(crate) struct Observation {
    pub native: String,
    pub name: String,
    pub model: String,
    pub collected_at: u64,
    pub server_at: Option<u64>,
    pub quality: &'static str,
    pub readings: BTreeMap<&'static str, Value>,
}
impl Observation {
    pub fn current(&self) -> bool {
        self.quality == "cloud_recent"
    }
}

pub(crate) fn valid_id(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 128
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_:-".contains(&b))
}
fn id(json: &Json) -> Result<String> {
    let value = json
        .as_str()
        .map(str::to_owned)
        .or_else(|| json.as_u64().map(|v| v.to_string()))
        .ok_or(Failure::InvalidResponse)?;
    if !valid_id(&value) {
        return Err(Failure::InvalidResponse);
    }
    Ok(value)
}
fn items(json: &Json) -> Result<Vec<&Json>> {
    if json.is_null() {
        return Ok(Vec::new());
    }
    let array = json
        .as_array()
        .filter(|v| v.len() <= MAX_ITEMS)
        .ok_or(Failure::InvalidResponse)?;
    let mut seen = BTreeSet::new();
    for item in array {
        if !seen.insert(id(&item["id"])?) {
            return Err(Failure::InvalidResponse);
        }
    }
    Ok(array.iter().collect())
}
fn text(json: &Json) -> Result<Option<String>> {
    if json.is_null() {
        return Ok(None);
    }
    let s = json
        .as_str()
        .filter(|s| s.len() <= 256 && !s.chars().any(char::is_control))
        .ok_or(Failure::InvalidResponse)?;
    Ok(Some(s.to_owned()))
}
fn number(json: &Json, min: f64, max: f64) -> Result<Value> {
    if json.is_null() {
        return Ok(Value::Null);
    }
    json.as_f64()
        .filter(|n| n.is_finite() && (min..=max).contains(n))
        .map(Value::Float)
        .ok_or(Failure::InvalidResponse)
}
fn optional_text(json: &Json) -> Result<Value> {
    Ok(text(json)?.map_or(Value::Null, |s| Value::Text(s.into())))
}
fn boolean(json: &Json) -> Result<Option<bool>> {
    if json.is_null() {
        return Ok(None);
    }
    json.as_bool().map(Some).ok_or(Failure::InvalidResponse)
}
fn timestamp(json: &Json) -> Option<u64> {
    json.as_u64()
        .or_else(|| json.as_str()?.parse().ok())
        .filter(|n| *n > 0)
        .and_then(|n| n.checked_mul(1000))
}

pub(crate) fn parse(
    topology: &Json,
    status: &Json,
    home_id: &str,
    now: u64,
    max_age_ms: u64,
) -> Result<Vec<Observation>> {
    let homes = items(&topology["body"]["homes"])?;
    let home = homes
        .into_iter()
        .find(|h| id(&h["id"]).ok().as_deref() == Some(home_id))
        .ok_or(Failure::InvalidResponse)?;
    let live = &status["body"]["home"];
    if id(&live["id"])? != home_id {
        return Err(Failure::InvalidResponse);
    }
    // Even a partial cloud sync error invalidates this snapshot. Preserve devices,
    // clear their current values; don't mislabel a partial response as complete.
    if let Some(errors) = status["body"].get("errors")
        && !errors.as_array().is_some_and(Vec::is_empty)
    {
        return Err(Failure::Api);
    }
    let server_at = timestamp(&status["time_server"]);
    let cloud_quality = match server_at {
        Some(at) if at <= now && now - at <= max_age_ms => "cloud_recent",
        Some(at) if at <= now => "stale",
        _ => "unconfirmed",
    };
    let room_status = index(items(&live["rooms"])?)?;
    let module_status = index(items(&live["modules"])?)?;
    let modules = items(&home["modules"])?;
    let mut readings = BTreeMap::new();
    readings.insert("home_mode", optional_text(&home["therm_mode"])?);
    readings.insert("schedule", selected_schedule(home)?);
    let mut out = Vec::new();
    for room in items(&home["rooms"])? {
        let native = id(&room["id"])?;
        let live = room_status.get(&native).copied().unwrap_or(&Json::Null);
        let values = room_readings(live, &readings)?;
        out.push(observation(
            home_id,
            "room",
            &native,
            room,
            live,
            "room",
            now,
            server_at,
            cloud_quality,
            values,
        )?);
    }
    for module in modules {
        let kind = text(&module["type"])?.ok_or(Failure::InvalidResponse)?;
        if !matches!(kind.as_str(), "NATherm1" | "OTM" | "NRV") {
            continue;
        }
        let native = id(&module["id"])?;
        let live = module_status.get(&native).copied().unwrap_or(&Json::Null);
        if !live.is_null() && live["type"].as_str() != Some(&kind) {
            return Err(Failure::InvalidResponse);
        }
        let mut values = readings.clone();
        // Only documented boolean on these models; never infer from room power,
        // target/measured temperature, or an unsupported product's field.
        let boiler = if matches!(kind.as_str(), "NATherm1" | "OTM") {
            boolean(&live["boiler_status"])?
        } else {
            None
        };
        values.insert("boiler_status", boiler.map_or(Value::Null, Value::Bool));
        values.insert(
            "declared_demand",
            Value::from(match boiler {
                Some(true) => "on",
                Some(false) => "off",
                None => "missing",
            }),
        );
        values.insert("battery_state", optional_text(&live["battery_state"])?);
        let mut obs = observation(
            home_id,
            "module",
            &native,
            module,
            live,
            &kind,
            now,
            server_at,
            cloud_quality,
            values,
        )?;
        if let Some(bridge) = live.get("bridge").filter(|b| b.is_string())
            && module_status
                .get(bridge.as_str().unwrap())
                .is_some_and(|m| m["reachable"] == false)
        {
            obs.quality = "offline";
        }
        out.push(obs);
    }
    if out.is_empty() {
        return Err(Failure::InvalidResponse);
    }
    Ok(out)
}
fn index(items: Vec<&Json>) -> Result<BTreeMap<String, &Json>> {
    items
        .into_iter()
        .map(|item| Ok((id(&item["id"])?, item)))
        .collect()
}
#[allow(clippy::too_many_arguments)]
fn observation(
    home: &str,
    category: &str,
    native: &str,
    metadata: &Json,
    live: &Json,
    model: &str,
    now: u64,
    server_at: Option<u64>,
    cloud_quality: &'static str,
    readings: BTreeMap<&'static str, Value>,
) -> Result<Observation> {
    let quality = match boolean(&live["reachable"])? {
        Some(false) => "offline",
        None => "unconfirmed",
        Some(true) => cloud_quality,
    };
    Ok(Observation {
        native: format!("{home}.{category}.{native}"),
        name: text(&metadata["name"])?.unwrap_or_else(|| native.to_owned()),
        model: model.to_owned(),
        collected_at: now,
        server_at,
        quality,
        readings,
    })
}

fn room_readings(
    live: &Json,
    common: &BTreeMap<&'static str, Value>,
) -> Result<BTreeMap<&'static str, Value>> {
    let mut values = common.clone();
    values.insert(
        "temperature",
        number(&live["therm_measured_temperature"], -50.0, 100.0)?,
    );
    values.insert(
        "setpoint_temperature",
        number(&live["therm_setpoint_temperature"], -50.0, 100.0)?,
    );
    values.insert("mode", optional_text(&live["therm_setpoint_mode"])?);
    values.insert(
        "heating_power_request",
        number(&live["heating_power_request"], 0.0, 100.0)?,
    );
    Ok(values)
}

fn selected_schedule(home: &Json) -> Result<Value> {
    let schedules = items(&home["schedules"])?;
    let selected: Vec<_> = schedules.iter().filter(|s| s["selected"] == true).collect();
    if selected.len() > 1 {
        return Err(Failure::InvalidResponse);
    }
    selected
        .first()
        .map_or(Ok(Value::Null), |s| optional_text(&s["name"]))
}
