use crate::client::Error;
use serde::{Deserialize, Serialize};
use serde_json::{Value as Json, json};
use std::{collections::BTreeMap, sync::OnceLock};

#[derive(Clone, Serialize, Deserialize)]
pub struct Vehicle {
    pub key: String,
    pub name: String,
    pub model: String,
    pub ccs2: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
}
/// Private provider identity is only serialized into the encrypted account bundle.
impl std::fmt::Debug for Vehicle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Vehicle(<private identity>)")
    }
}
pub fn vehicles(value: &Json) -> Vec<Vehicle> {
    let source = value
        .get("contents")
        .or_else(|| value.get("vehicles"))
        .unwrap_or(value);
    let entries: Vec<_> = if let Some(list) = source.as_array() {
        list.iter().collect()
    } else if source.is_object() {
        vec![source]
    } else {
        Vec::new()
    };
    entries
        .into_iter()
        .filter_map(|v| {
            let id = v
                .get("ccspCarId")
                .and_then(Json::as_str)
                .filter(|s| !s.is_empty())
                .or_else(|| v.pointer("/ccspVehicle/carId").and_then(Json::as_str))
                .or_else(|| v.get("vehicleId").and_then(Json::as_str))?;
            if id.is_empty()
                || id.len() > 128
                || !id
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
            {
                return None;
            }
            let key = format!(
                "v_{}",
                &crate::crypto::hex(
                    ring::digest::digest(&ring::digest::SHA256, id.as_bytes()).as_ref()
                )[..24]
            );
            Some(Vehicle {
                key,
                name: v
                    .get("vehicleNameView")
                    .or_else(|| v.get("nickname"))
                    .or_else(|| v.get("vehicleName"))
                    .and_then(Json::as_str)
                    .filter(|s| !s.is_empty())
                    .unwrap_or("Hyundai")
                    .chars()
                    .take(80)
                    .collect(),
                model: v
                    .get("vehicleModelName")
                    .or_else(|| v.get("modelName"))
                    .and_then(Json::as_str)
                    .unwrap_or("Hyundai EU")
                    .chars()
                    .take(80)
                    .collect(),
                ccs2: {
                    let reported = v
                        .get("ccs2ProtocolSupport")
                        .or_else(|| v.get("ccu_ccs2_protocol_support"))
                        .and_then(Json::as_u64)
                        .unwrap_or(0);
                    if reported == 0 && v["isCcs"] == true && v["isCcsOpen"] == true {
                        2
                    } else {
                        reported
                    }
                },
                id: Some(id.to_owned()),
            })
        })
        .collect()
}
#[derive(Debug, Deserialize)]
pub struct Field {
    pub key: String,
    pub path: String,
    pub unit: String,
}
pub fn fields() -> &'static Vec<Field> {
    static F: OnceLock<Vec<Field>> = OnceLock::new();
    F.get_or_init(|| {
        serde_json::from_str(include_str!("../data/status-fields.json"))
            .expect("embedded public status schema")
    })
}
pub fn at<'a>(value: &'a Json, path: &str) -> &'a Json {
    path.split('.')
        .fold(value, |v, k| v.get(k).unwrap_or(&Json::Null))
}
pub fn number(value: &Json) -> Option<f64> {
    value
        .as_f64()
        .or_else(|| value.as_str().and_then(|s| s.parse().ok()))
        .filter(|n| n.is_finite())
}
fn percent(value: &Json, positive: bool) -> Json {
    number(value)
        .filter(|n| (0.0..=100.0).contains(n) && (!positive || *n > 0.0))
        .map_or(Json::Null, |n| json!(n))
}
pub fn timestamp(v: &Json) -> Option<u64> {
    if let Some(n) = v.as_u64().or_else(|| {
        v.as_str()
            .filter(|s| s.len() != 14)
            .and_then(|s| s.parse().ok())
    }) {
        return Some(if n < 10_000_000_000 {
            n.checked_mul(1000)?
        } else {
            n
        });
    }
    let text = v.as_str()?;
    if text.len() >= 14 && text.as_bytes()[..14].iter().all(u8::is_ascii_digit) {
        let parse = |a, b| text.get(a..b)?.parse::<i32>().ok();
        let dt = jiff::civil::DateTime::new(
            i16::try_from(parse(0, 4)?).ok()?,
            i8::try_from(parse(4, 6)?).ok()?,
            i8::try_from(parse(6, 8)?).ok()?,
            i8::try_from(parse(8, 10)?).ok()?,
            i8::try_from(parse(10, 12)?).ok()?,
            i8::try_from(parse(12, 14)?).ok()?,
            0,
        )
        .ok()?;
        return u64::try_from(
            dt.to_zoned(jiff::tz::TimeZone::UTC)
                .ok()?
                .timestamp()
                .as_millisecond(),
        )
        .ok();
    }
    text.parse::<jiff::Timestamp>()
        .ok()
        .and_then(|v| u64::try_from(v.as_millisecond()).ok())
}
#[derive(Debug, Clone)]
pub struct Snapshot {
    pub values: BTreeMap<String, Json>,
    pub vehicle_at: Option<u64>,
    pub cloud_at: Option<u64>,
    pub received_at: u64,
    pub quality: &'static str,
    pub state: Json,
}
impl Snapshot {
    #[allow(clippy::too_many_lines, clippy::float_cmp)] // One schema boundary; 0/1 are exact wire enums.
    pub fn parse(payload: &Json, now: u64, max_age: u64) -> Self {
        let raw = payload.get("state").unwrap_or(payload);
        let state = raw.get("Vehicle").unwrap_or(raw);
        let vehicle_at = timestamp(&state["Date"]);
        let cloud_at = timestamp(&payload["lastUpdateTime"]);
        let quality = match vehicle_at {
            None => "unknown",
            Some(at) if at > now.saturating_add(1000) => "future",
            Some(at) if now.saturating_sub(at) > max_age => "stale",
            Some(_) => "cached_recent",
        };
        let mut values = BTreeMap::new();
        for f in fields() {
            let raw = at(state, &f.path);
            let value = if f.unit == "%" {
                percent(raw, f.path.contains("SoH"))
            } else if raw.is_null() {
                Json::Null
            } else if raw.is_array()
                && f.path.ends_with("DaysOfWeek")
                && raw.as_array().is_some_and(|a| {
                    a.len() <= 7 && a.iter().all(|n| n.as_u64().is_some_and(|n| n <= 6))
                })
            {
                json!(raw.to_string())
            } else if raw.is_object() || raw.is_array() {
                Json::Null
            } else {
                raw.clone()
            };
            if !value.is_null() || !values.contains_key(&f.key) {
                values.insert(f.key.clone(), value);
            }
        }
        for (key, path) in [
            ("soc", "Green.BatteryManagement.BatteryRemain.Ratio"),
            ("soh", "Green.BatteryManagement.SoH.Ratio"),
            ("battery_12v", "Electronics.Battery.Level"),
        ] {
            values.insert(key.into(), percent(at(state, path), key == "soh"));
        }
        for (key, path) in [
            ("odometer", "Drivetrain.Odometer"),
            ("charging_power", "Green.Electric.SmartGrid.RealTimePower"),
            (
                "total_consumed_wh",
                "Green.Electric.SmartGrid.TotalPowerConsumption",
            ),
            (
                "total_regenerated_wh",
                "Green.Electric.SmartGrid.TotalPowerRegeneration",
            ),
        ] {
            values.insert(
                key.into(),
                number(at(state, path))
                    .filter(|n| *n >= 0.0)
                    .map_or(Json::Null, |n| json!(n)),
            );
        }
        let remain = number(at(state, "Green.ChargingInformation.Charging.RemainTime"));
        values.insert(
            "charging".into(),
            remain.map_or(Json::Null, |n| json!(n > 0.0)),
        );
        values.insert(
            "driving_ready".into(),
            match at(state, "DrivingReady") {
                Json::Bool(b) => json!(b),
                v => number(v)
                    .filter(|n| *n == 0.0 || *n == 1.0)
                    .map_or(Json::Null, |n| json!(n == 1.0)),
            },
        );
        for (key, path) in [
            ("air_temperature_c", "Cabin.HVAC.Row1.Driver.Temperature"),
            ("outside_temperature_c", "Cabin.HVAC.OutsideTemperature"),
        ] {
            let raw = number(&at(state, path)["Value"]);
            let unit = at(state, path)["Unit"].as_u64();
            values.insert(
                key.into(),
                raw.zip(unit)
                    .and_then(|(n, u)| match u {
                        0 => Some(n),
                        1 => Some((n - 32.0) * 5.0 / 9.0),
                        _ => None,
                    })
                    .filter(|n| (-100.0..=100.0).contains(n))
                    .map_or(Json::Null, |n| json!(n)),
            );
        }
        let doors = [
            "Cabin.Door.Row1.Driver.Lock",
            "Cabin.Door.Row1.Passenger.Lock",
            "Cabin.Door.Row2.Left.Lock",
            "Cabin.Door.Row2.Right.Lock",
        ]
        .map(|p| {
            number(at(state, p))
                .filter(|n| *n == 0.0 || *n == 1.0)
                .map(|n| n == 0.0)
        });
        values.insert(
            "is_locked".into(),
            if doors.contains(&Some(false)) {
                json!(false)
            } else if doors.iter().all(|v| *v == Some(true)) {
                json!(true)
            } else {
                Json::Null
            },
        );
        values.insert(
            "ev_range_km".into(),
            number(at(state, "Drivetrain.FuelSystem.DTE.Total"))
                .zip(at(state, "Drivetrain.FuelSystem.DTE.Unit").as_u64())
                .and_then(|(n, u)| match u {
                    1 => Some(n),
                    2 | 3 => Some(n * 1.609_344),
                    _ => None,
                })
                .map_or(Json::Null, |n| json!(n)),
        );
        for (key, path) in [
            ("tire_front_left", "Chassis.Axle.Row1.Left.Tire.Pressure"),
            ("tire_front_right", "Chassis.Axle.Row1.Right.Tire.Pressure"),
            ("tire_rear_left", "Chassis.Axle.Row2.Left.Tire.Pressure"),
            ("tire_rear_right", "Chassis.Axle.Row2.Right.Tire.Pressure"),
        ] {
            let unit = at(state, "Chassis.Axle.Tire.PressureUnit").as_u64();
            let scale = match unit {
                Some(0) => Some((1.0, "psi")),
                Some(1) => Some((5.0, "kPa")),
                Some(2) => Some((0.1, "bar")),
                _ => None,
            };
            values.insert(
                key.into(),
                number(at(state, path))
                    .filter(|n| *n > 0.0 && *n < 255.0)
                    .zip(scale)
                    .map_or(Json::Null, |(n, (scale, _))| json!(n * scale)),
            );
            values.insert(
                format!("{key}_unit"),
                scale.map_or(Json::Null, |(_, u)| json!(u)),
            );
        }
        aliases_into(state, &mut values);
        for key in ["vehicle_at", "cloud_at", "received_at", "quality"] {
            let v = match key {
                "vehicle_at" => json!(vehicle_at),
                "cloud_at" => json!(cloud_at),
                "received_at" => json!(now),
                _ => json!(quality),
            };
            values.insert(key.into(), v);
        }
        // Only the known non-location status schema survives this boundary.
        let mut safe = json!({});
        for f in fields() {
            let value = at(state, &f.path);
            if !value.is_null() && !value.is_object() && !value.is_array() {
                insert_path(&mut safe, &f.path, value.clone());
            }
        }
        Self {
            values,
            vehicle_at,
            cloud_at,
            received_at: now,
            quality,
            state: safe,
        }
    }
    pub fn summary(&self) -> Json {
        json!({"vehicle_at":self.vehicle_at,"cloud_at":self.cloud_at,"received_at":self.received_at,"quality":self.quality,"odometer":self.values.get("odometer"),"soc":self.values.get("soc"),"charging":self.values.get("charging"),"consumed_wh":self.values.get("total_consumed_wh"),"regenerated_wh":self.values.get("total_regenerated_wh")})
    }
}
fn insert_path(root: &mut Json, path: &str, value: Json) {
    let mut node = root;
    let parts: Vec<_> = path.split('.').collect();
    for key in &parts[..parts.len() - 1] {
        if node.get(*key).is_none() {
            node[*key] = json!({});
        }
        node = &mut node[*key];
    }
    node[parts[parts.len() - 1]] = value;
}
pub fn validate_id(id: &str) -> Result<&str, Error> {
    if id.is_empty()
        || id.len() > 128
        || !id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
    {
        Err(Error::Protocol)
    } else {
        Ok(id)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn eu_derived_states_and_units_remain_explicit() {
        let s = Snapshot::parse(
            &json!({"state":{"Vehicle":{
                "Body":{"Windshield":{"Front":{"Defog":{"State":2}}}},
                "Cabin":{"SteeringWheel":{"Heat":{"State":1}}},
                "Green":{"ChargingDoor":{"State":3},"Reservation":{"Departure":{"Schedule1":{"HVAC":{"Temperature":{"Value":68,"Unit":1}}}}},"ChargingInformation":{"DTE":{"TargetSoC":{"Standard":100}}}},
                "Drivetrain":{"FuelSystem":{"DTE":{"Unit":3}}}
            }}}),
            1_800_000_000_000,
            1000,
        );
        assert_eq!(s.values["defrost_is_on"], false);
        assert_eq!(s.values["steering_wheel_heater_is_on"], true);
        assert!(s.values["ev_charge_port_door_is_open"].is_null());
        assert_eq!(s.values["ev_first_departure_climate_temperature"], 20.0);
        assert_eq!(s.values["ev_target_range_charge_AC"], 160.9344);
        let coverage = coverage(&s.values);
        let defrost = coverage
            .as_array()
            .unwrap()
            .iter()
            .find(|v| v["ha"] == "defrost_is_on")
            .unwrap();
        assert_eq!(defrost["available"], true);
    }
    #[test]
    fn missing_and_sentinel_values_never_become_zero() {
        let s = Snapshot::parse(
            &json!({"state":{"Vehicle":{"Electronics":{"Battery":{"Level":255}},"Green":{"BatteryManagement":{"SoH":{"Ratio":0}}},"Location":{"GeoCoord":{"Latitude":1,"Longitude":2}}}}}),
            1_800_000_000_000,
            3_600_000,
        );
        assert!(s.values["soc"].is_null());
        assert!(s.values["soh"].is_null());
        assert!(s.values["battery_12v"].is_null());
        assert_eq!(s.quality, "unknown");
        assert!(!s.summary().to_string().contains("Latitude"));
        assert!(!s.state.to_string().contains("Location"));
    }
    #[test]
    fn data_times_are_distinct_and_utc() {
        assert_eq!(timestamp(&json!("20270101120000")), Some(1_798_804_800_000));
        let s = Snapshot::parse(
            &json!({"lastUpdateTime":"1800000000000","state":{"Vehicle":{"Date":"20270101120000","Drivetrain":{"Odometer":0}}}}),
            1_800_000_000_000,
            1000,
        );
        assert_eq!(s.values["odometer"], 0.0);
        assert_eq!(s.quality, "stale");
        assert_ne!(s.vehicle_at, s.cloud_at);
    }
}

#[derive(Debug, Deserialize)]
pub struct Alias {
    pub key: String,
    pub path: String,
    pub conversion: String,
    pub unit: String,
}
pub fn aliases() -> &'static Vec<Alias> {
    static ALIASES: OnceLock<Vec<Alias>> = OnceLock::new();
    ALIASES.get_or_init(|| {
        serde_json::from_str(include_str!("../data/aliases.json")).expect("embedded EU aliases")
    })
}
fn aliases_into(state: &Json, values: &mut BTreeMap<String, Json>) {
    for a in aliases() {
        let raw = at(state, &a.path);
        let value = match a.conversion.as_str() {
            "percent" => percent(raw, a.key.contains("soh")),
            "temperature" => number(raw)
                .zip(at(state, &a.path.replace(".Value", ".Unit")).as_u64())
                .and_then(|(n, u)| match u {
                    0 => Some(n),
                    1 => Some((n - 32.0) * 5.0 / 9.0),
                    _ => None,
                })
                .map_or(Json::Null, |v| json!(v)),
            "distance" => number(raw)
                .zip(at(state, "Drivetrain.FuelSystem.DTE.Unit").as_u64())
                .and_then(|(n, u)| match u {
                    1 => Some(n),
                    2 | 3 => Some(n * 1.609_344),
                    _ => None,
                })
                .filter(|n| *n >= 0.0)
                .map_or(Json::Null, |n| json!(n)),
            "pressure" => {
                let (scale, unit) = match at(state, "Chassis.Axle.Tire.PressureUnit").as_u64() {
                    Some(0) => (Some(1.0), Some("psi")),
                    Some(1) => (Some(5.0), Some("kPa")),
                    Some(2) => (Some(0.1), Some("bar")),
                    _ => (None, None),
                };
                values.insert(format!("{}_unit", a.key), json!(unit));
                number(raw)
                    .filter(|n| *n > 0.0 && *n < 255.0)
                    .zip(scale)
                    .map_or(Json::Null, |(n, s)| json!(n * s))
            }
            "window" => {
                let flag = wire_flag(raw, false);
                if number(at(state, &a.path.replace(".Open", ".OpenLevel")))
                    .is_some_and(|n| n > 0.0 && n <= 100.0)
                {
                    json!(true)
                } else {
                    json!(flag)
                }
            }
            "conditioning" => match raw.as_u64() {
                Some(3 | 4) => json!(true),
                Some(0 | 2 | 6) => json!(false),
                _ => Json::Null,
            },
            "schedule" => match raw.as_u64() {
                Some(2 | 3) => json!(true),
                Some(0) => json!(false),
                _ => json!(wire_flag(
                    at(state, "Green.Reservation.ChargeSchedule.Enable"),
                    false
                )),
            },
            "offpeak" => match raw.as_u64() {
                Some(3) => json!(true),
                Some(2) => json!(false),
                _ => Json::Null,
            },
            "clock" => {
                let minute_path = a.path.replace("Hour", "Min");
                clock(raw, at(state, &minute_path))
            }

            "flag" | "lock" => {
                let flag = if a.key == "air_control_is_on" {
                    number(raw)
                        .filter(|n| (0.0..=10.0).contains(n))
                        .map(|n| n > 0.0)
                } else {
                    wire_flag(raw, a.conversion == "lock")
                };
                flag.map_or(Json::Null, |b| {
                    json!(if a.conversion == "lock" { !b } else { b })
                })
            }
            "seat" => seat(raw),
            _ => {
                if a.path.ends_with("DaysOfWeek")
                    && raw.as_array().is_some_and(|v| {
                        v.len() <= 7 && v.iter().all(|n| n.as_u64().is_some_and(|n| n <= 6))
                    })
                {
                    json!(raw.to_string())
                } else if raw.is_object() && a.path.ends_with(".Time") {
                    clock(&raw["Hour"], &raw["Min"])
                } else if raw.is_boolean() || raw.is_number() || raw.is_string() {
                    raw.clone()
                } else {
                    Json::Null
                }
            }
        };
        values.insert(a.key.clone(), value);
    }
}

fn wire_flag(raw: &Json, lock: bool) -> Option<bool> {
    raw.as_bool().or_else(|| {
        raw.as_i64().and_then(|n| match n {
            0 => Some(false),
            1 => Some(true),
            2 if !lock => Some(false),
            _ => None,
        })
    })
}
fn clock(hour: &Json, minute: &Json) -> Json {
    hour.as_u64()
        .zip(minute.as_u64())
        .filter(|(h, m)| *h < 24 && *m < 60)
        .map_or(Json::Null, |(h, m)| json!(format!("{h:02}:{m:02}")))
}

pub fn coverage(values: &BTreeMap<String, Json>) -> Json {
    let inventory: Vec<Json> =
        serde_json::from_str(include_str!("../data/ha-entity-reference.json"))
            .expect("public HA inventory");
    let reference: Json = serde_json::from_str(include_str!("../data/eu-field-reference.json"))
        .expect("public EU field map");
    json!(
        inventory
            .iter()
            .filter(|e| matches!(
                e["platform"].as_str(),
                Some("sensor" | "binary_sensor" | "number")
            ))
            .map(|e| {
                let key = e["key"].as_str().unwrap_or("");
                let bare = key.trim_start_matches('_');
                let special = match bare {
                    "total_driving_range" | "ev_driving_range" => "ev_range_km",
                    "last_updated_at" => "vehicle_at",
                    "last_scanned_at" => "received_at",
                    "ev_battery_soh_percentage" => "soh",
                    "ev_battery_is_charging" => "charging",
                    "front_left_seat_heater_on" => "front_left_seat_status",
                    "front_right_seat_heater_on" => "front_right_seat_status",
                    "rear_left_seat_heater_on" => "rear_left_seat_status",
                    "rear_right_seat_heater_on" => "rear_right_seat_status",
                    "ev_charge_complete_alarm" => "ev_charge_complete_alarm_off",
                    "dtc_count" | "dtc_descriptions" => "diagnostics",
                    "power_consumption_30d" => "driving_history",
                    _ => bare,
                };
                let points = if values.contains_key(special) {
                    vec![special.to_owned()]
                } else {
                    reference[bare]["paths"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(Json::as_str)
                        .filter(|p| !p.starts_with("Location."))
                        .map(|p| format!("vehicle_{}", p.to_lowercase().replace('.', "_")))
                        .filter(|p| values.contains_key(p))
                        .collect::<Vec<_>>()
                };
                let available = points.iter().any(|p| {
                    values.get(p).is_some_and(|v| {
                        !v.is_null() && v.get("available") != Some(&Json::Bool(false))
                    })
                });
                let reason = if bare == "ev_battery_is_plugged_in" {
                    "connector_lock_is_not_plug_sensor"
                } else if points.is_empty() {
                    "not_exposed_by_eu_reference"
                } else if !available {
                    "not_reported_by_vehicle"
                } else {
                    "cached_protocol_data"
                };
                json!({"ha":key,"points":points,"available":available,"reason":reason})
            })
            .collect::<Vec<_>>()
    )
}

fn seat(raw: &Json) -> Json {
    raw.as_u64()
        .and_then(|n| {
            [
                "Off",
                "On",
                "Off",
                "Low Cool",
                "Medium Cool",
                "High Cool",
                "Low Heat",
                "Medium Heat",
                "High Heat",
            ]
            .get(usize::try_from(n).ok()?)
        })
        .map_or(Json::Null, |v| json!(v))
}
