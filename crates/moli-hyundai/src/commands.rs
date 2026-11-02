//! Explicit EU GSPA command DTOs, never arbitrary URLs or provider payloads.
use crate::{
    client::Error,
    model::{Vehicle, at},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value as Json, json};

#[derive(Debug, Clone, Serialize)]
pub struct Capability {
    pub key: &'static str,
    pub endpoint: &'static str,
    pub pin: bool,
    pub available: bool,
    pub reason: &'static str,
    pub parameters: Json,
}
#[allow(clippy::too_many_lines)] // Declarative capability/schema table kept together.
pub fn capabilities(vehicle: &Vehicle, state: &Json, write: bool, pin: bool) -> Vec<Capability> {
    let specs = [
        (
            "lock",
            "door-lock",
            true,
            "Cabin.Door.Row1.Driver.Lock",
            json!({}),
        ),
        (
            "unlock",
            "door-unlock",
            true,
            "Cabin.Door.Row1.Driver.Lock",
            json!({}),
        ),
        (
            "start_climate",
            "temperature",
            true,
            "Cabin.HVAC",
            json!({"temperature":{"min":17,"max":27,"step":0.5},"duration":{"min":1,"max":30},"defrost":{"type":"boolean"},"steering":{"min":0,"max":2},"rear_heat":{"type":"boolean"},"driver_seat":{"min":0,"max":8},"passenger_seat":{"min":0,"max":8},"rear_left_seat":{"min":0,"max":8},"rear_right_seat":{"min":0,"max":8}}),
        ),
        ("stop_climate", "temperature", true, "Cabin.HVAC", json!({})),
        (
            "start_charge",
            "charge",
            true,
            "Green.ChargingInformation",
            json!({}),
        ),
        (
            "stop_charge",
            "charge",
            true,
            "Green.ChargingInformation",
            json!({}),
        ),
        (
            "set_charge_limits",
            "charge-target",
            false,
            "Green.ChargingInformation.TargetSoC",
            json!({"ac":{"min":50,"max":100,"step":10},"dc":{"min":50,"max":100,"step":10}}),
        ),
        (
            "set_charging_current",
            "charging-current",
            false,
            "Green.ChargingInformation.ElectricCurrentLevel",
            json!({"level":{"options":["0","1","2"]}}),
        ),
        (
            "open_charge_port",
            "portdoor",
            true,
            "Green.ChargingDoor",
            json!({}),
        ),
        (
            "close_charge_port",
            "portdoor",
            true,
            "Green.ChargingDoor",
            json!({}),
        ),
        (
            "set_windows",
            "window-curtain",
            true,
            "Cabin.Window",
            json!({"front_left":{"min":0,"max":2},"front_right":{"min":0,"max":2},"rear_left":{"min":0,"max":2},"rear_right":{"min":0,"max":2},"rear_left_curtain":{"min":0,"max":2},"rear_right_curtain":{"min":0,"max":2}}),
        ),
        (
            "start_hazard_lights",
            "light",
            true,
            "Body.Lights.Hazard",
            json!({}),
        ),
        (
            "start_hazard_lights_and_horn",
            "horn-light",
            true,
            "Body.Lights.Hazard",
            json!({}),
        ),
        ("start_valet_mode", "control", true, "Cabin", json!({})),
        ("stop_valet_mode", "control", true, "Cabin", json!({})),
        (
            "set_v2l_limit",
            "discharge-limit",
            false,
            "Green.Electric.SmartGrid.VehicleToLoad",
            json!({"limit":{"min":20,"max":80,"step":10}}),
        ),
        (
            "schedule_charging_and_climate",
            "reservation-charge-hvac",
            false,
            "Green.Reservation",
            json!({"charging":{"type":"boolean"},"climate":{"type":"boolean"},"defrost":{"type":"boolean"},"temperature":{"min":17,"max":27,"step":0.5},"first_enabled":{"type":"boolean"},"first_days":{"type":"days"},"first_time":{"type":"time"},"second_enabled":{"type":"boolean"},"second_days":{"type":"days"},"second_time":{"type":"time"},"off_peak_only":{"type":"boolean"},"off_peak_start":{"type":"time"},"off_peak_end":{"type":"time"}}),
        ),
        (
            "set_off_peak_charging",
            "reservation-charge",
            false,
            "Green.Reservation",
            json!({"charging":{"type":"boolean"},"off_peak_only":{"type":"boolean"},"off_peak_start":{"type":"time"},"off_peak_end":{"type":"time"}}),
        ),
        (
            "set_charge_alarm",
            "charge-alarm",
            false,
            "Green.ChargingInformation.Setting.CompleteAlarm",
            json!({"preset":{"options":["off","all","10","20","30"]}}),
        ),
        (
            "start_battery_conditioning",
            "battery-conditioning",
            true,
            "Green.BatteryManagement.BatteryPreCondition",
            json!({}),
        ),
        (
            "stop_battery_conditioning",
            "battery-conditioning",
            true,
            "Green.BatteryManagement.BatteryPreCondition",
            json!({}),
        ),
        ("force_refresh", "prewakeup", false, "", json!({})),
    ];
    specs
        .into_iter()
        .map(|(key, endpoint, needs_pin, path, parameters)| {
            let reason = if !write {
                "commands_not_authorized"
            } else if vehicle.ccs2 == 0 {
                "pre_ccs2_not_supported"
            } else if needs_pin && !pin {
                "pin_required"
            } else if key == "force_refresh"
                && crate::model::number(at(state, "Electronics.Battery.Level"))
                    .is_none_or(|n| !(40.0..=100.0).contains(&n))
            {
                "battery_12v_unconfirmed_or_low"
            } else if !path.is_empty() && at(state, path).is_null() {
                "capability_not_reported"
            } else {
                "protocol_supported_to_qualify"
            };
            Capability {
                key,
                endpoint,
                pin: needs_pin,
                available: reason == "protocol_supported_to_qualify",
                reason,
                parameters,
            }
        })
        .chain([
            Capability {
                key: "refresh_cached",
                endpoint: "stored-status",
                pin: false,
                available: true,
                reason: "passive_cache_read",
                parameters: json!({}),
            },
            Capability {
                key: "set_navigation",
                endpoint: "",
                pin: true,
                available: false,
                reason: "reference_gspa_not_implemented",
                parameters: json!({}),
            },
            Capability {
                key: "capture_svm_image",
                endpoint: "",
                pin: true,
                available: false,
                reason: "reference_gspa_capture_not_implemented",
                parameters: json!({}),
            },
        ])
        .collect()
}
#[derive(Debug)]
pub struct Command {
    pub endpoint: &'static str,
    pub prefix: &'static str,
    pub body: Json,
    pub pin: bool,
}
#[derive(Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
struct Options {
    heating: Option<u64>,
    navigation_token: Option<String>,
    temperature: Option<f64>,
    duration: Option<u64>,
    defrost: Option<bool>,
    steering: Option<u64>,
    rear_heat: Option<bool>,
    driver_seat: Option<u64>,
    passenger_seat: Option<u64>,
    rear_left_seat: Option<u64>,
    rear_right_seat: Option<u64>,
    ac: Option<u64>,
    dc: Option<u64>,
    level: Option<u64>,
    front_left: Option<u64>,
    front_right: Option<u64>,
    rear_left: Option<u64>,
    rear_right: Option<u64>,
    rear_left_curtain: Option<u64>,
    rear_right_curtain: Option<u64>,
    limit: Option<u64>,
    charging: Option<bool>,
    climate: Option<bool>,
    first_enabled: Option<bool>,
    first_days: Option<Vec<u64>>,
    first_time: Option<String>,
    second_enabled: Option<bool>,
    second_days: Option<Vec<u64>>,
    second_time: Option<String>,
    off_peak_only: Option<bool>,
    off_peak_start: Option<String>,
    off_peak_end: Option<String>,
    preset: Option<String>,
}
fn bounded(v: Option<u64>, min: u64, max: u64) -> Result<u64, Error> {
    v.filter(|v| (min..=max).contains(v)).ok_or(Error::Protocol)
}
fn time(v: Option<&str>) -> Result<Json, Error> {
    let v = v.ok_or(Error::Protocol)?;
    let (h, m) = v.split_once(':').ok_or(Error::Protocol)?;
    let h: u64 = h.parse().map_err(|_| Error::Protocol)?;
    let m: u64 = m.parse().map_err(|_| Error::Protocol)?;
    if h > 23 || m > 59 {
        return Err(Error::Protocol);
    }
    Ok(
        json!({"time":format!("{:02}{m:02}",if h.is_multiple_of(12){12}else{h%12}),"timeSection":u8::from(h>=12)}),
    )
}
fn days(v: Option<&Vec<u64>>) -> Result<Json, Error> {
    let v = v.ok_or(Error::Protocol)?;
    if v.is_empty() || v.len() > 7 || v.iter().any(|v| *v > 6) {
        return Err(Error::Protocol);
    }
    Ok(json!(v))
}
fn temp(v: Option<f64>) -> Result<f64, Error> {
    v.filter(|v| v.is_finite() && (17.0..=27.0).contains(v) && (v * 2.0).fract() == 0.0)
        .ok_or(Error::Protocol)
}
#[allow(clippy::too_many_lines)] // Exhaustive endpoint-to-DTO mapping; no arbitrary payloads.
pub fn build(cap: &Capability, value: &Json, state: &Json) -> Result<Command, Error> {
    if !cap.available {
        return Err(Error::Unsupported);
    }
    let params = if value == true {
        json!({})
    } else if let Some(s) = value.as_str() {
        serde_json::from_str(s).map_err(|_| Error::Protocol)?
    } else {
        value.clone()
    };
    if !params.is_object() {
        return Err(Error::Protocol);
    }
    let o: Options = serde_json::from_value(params.clone()).map_err(|_| Error::Protocol)?;
    let schema = cap.parameters.as_object().ok_or(Error::Protocol)?;
    if params
        .as_object()
        .ok_or(Error::Protocol)?
        .keys()
        .any(|k| !schema.contains_key(k))
    {
        return Err(Error::Protocol);
    }
    if o.heating.is_some_and(|v| v > 4) {
        return Err(Error::Protocol);
    }
    let mut prefix = "remote/vehicles";
    let body = match cap.key {
        "lock" | "unlock" => json!({"command":"set"}),
        "start_charge" | "start_battery_conditioning" => json!({"command":"start"}),
        "stop_charge" | "stop_battery_conditioning" | "stop_climate" => json!({"command":"stop"}),
        "open_charge_port" => json!({"command":"open"}),
        "close_charge_port" => json!({"command":"close"}),
        "start_hazard_lights" | "start_hazard_lights_and_horn" => json!({"command":"on"}),
        "start_valet_mode" | "stop_valet_mode" => {
            prefix = "valet/vehicles";
            json!({"command":if cap.key=="start_valet_mode"{"activate"}else{"deactivate"}})
        }
        "set_charge_limits" => {
            let ac = bounded(o.ac, 50, 100)?;
            let dc = bounded(o.dc, 50, 100)?;
            if ac % 10 != 0 || dc % 10 != 0 {
                return Err(Error::Protocol);
            }
            json!({"targetSOClist":[{"plugType":0,"targetSOClevel":dc},{"plugType":1,"targetSOClevel":ac}],"command":"set"})
        }
        "set_charging_current" => json!({"command":"set","chargingCurrent":bounded(o.level,0,2)?}),
        "set_v2l_limit" => {
            let n = bounded(o.limit, 20, 80)?;
            if n % 10 != 0 {
                return Err(Error::Protocol);
            }
            json!({"command":"set","dischargingLimit":n})
        }
        "set_windows" => {
            let right = matches!(at(state, "Drivetrain.OdometerUnit").as_u64(), Some(2 | 3));
            let mut b = json!({"command":"open","drvSeatLoc":if right{"R"}else{"L"}});
            for (k, v) in [
                (
                    if right {
                        "psgSeatWindow"
                    } else {
                        "drvSeatWindow"
                    },
                    o.front_left,
                ),
                (
                    if right {
                        "drvSeatWindow"
                    } else {
                        "psgSeatWindow"
                    },
                    o.front_right,
                ),
                ("rlSeatWindow", o.rear_left),
                ("rrSeatWindow", o.rear_right),
                ("rlSeatWindowCurtain", o.rear_left_curtain),
                ("rrSeatWindowCurtain", o.rear_right_curtain),
            ] {
                if let Some(v) = v {
                    b[k] = json!(bounded(Some(v), 0, 2)?);
                }
            }
            if b.as_object().ok_or(Error::Protocol)?.len() == 2 {
                return Err(Error::Protocol);
            }
            b
        }
        "start_climate" => {
            let mut b = json!({"command":"start","hvacTemp":format!("{:.1}",temp(o.temperature)?),"tempUnit":"C","hvacTempType":at(state,"Server.Option.HvacTempType").as_u64().unwrap_or(1),"ignitionDuration":bounded(o.duration,1,30)?});
            if let Some(v) = o.defrost {
                b["windshieldFrontDefogState"] = json!(v);
            }
            if let Some(v) = o.rear_heat {
                b["sideRearMirrorHeating"] = json!(u8::from(v));
            }
            if let Some(v) = o.steering {
                b["strgWhlHeating"] = json!(bounded(Some(v), 0, 2)?);
            }
            let mut seats = json!({});
            for (k, v) in [
                ("drvSeatClimateState", o.driver_seat),
                ("psgSeatClimateState", o.passenger_seat),
                ("rlSeatClimateState", o.rear_left_seat),
                ("rrSeatClimateState", o.rear_right_seat),
            ] {
                if let Some(v) = v {
                    seats[k] = json!(bounded(Some(v), 0, 8)?);
                }
            }
            if seats.as_object().is_some_and(|v| !v.is_empty()) {
                b["seatClimateInfo"] = seats;
            }
            b
        }
        "set_off_peak_charging" => {
            json!({"command":"set","reservFlag":u8::from(o.charging.ok_or(Error::Protocol)?),"offpeakPowerFlag":if o.off_peak_only.ok_or(Error::Protocol)?{1}else{2},"reservStartTime":time(o.off_peak_start.as_deref())?,"reservEndTime":time(o.off_peak_end.as_deref())?})
        }
        "set_navigation" => {
            json!({"navigation_token":o.navigation_token.filter(|t|t.len()==36&&t.bytes().all(|b|b.is_ascii_hexdigit()||b==b'-')).ok_or(Error::Protocol)?})
        }
        "schedule_charging_and_climate" | "schedule_climate" => {
            let temperature = temp(o.temperature)?;
            let climate_only = cap.key == "schedule_climate";
            let mut departures = json!({});
            for (i, enabled, day, t) in [
                (
                    1,
                    o.first_enabled,
                    o.first_days.as_ref(),
                    o.first_time.as_deref(),
                ),
                (
                    2,
                    o.second_enabled,
                    o.second_days.as_ref(),
                    o.second_time.as_deref(),
                ),
            ] {
                departures[format!("reservChargeInfo{i}")] = json!({"reservChargeSet":enabled.ok_or(Error::Protocol)?,"reservInfo":{"day":days(day)?,"time":time(t)?},"reservFatcSet":{"airCtrl":u8::from(o.climate.ok_or(Error::Protocol)?),"airTemp":{"value":format!("{temperature:.1}"),"hvacTempType":1,"unit":0},"heating1":0,"defrost":o.defrost.unwrap_or(false)}});
            }
            json!({"command":"set","reservChargeInfo":departures,"reservFlag":u8::from(if climate_only{false}else{o.charging.ok_or(Error::Protocol)?}),"offPeakPowerInfo":{"offPeakPowerFlag":if climate_only{1}else if o.off_peak_only.ok_or(Error::Protocol)?{2}else{1},"offPeakPowerTime1":{"starttime":time(o.off_peak_start.as_deref().or(if climate_only{Some("00:00")}else{None}))?,"endtime":time(o.off_peak_end.as_deref().or(if climate_only{Some("00:00")}else{None}))?}}})
        }
        "set_charge_alarm" => {
            let preset = o.preset.as_deref().ok_or(Error::Protocol)?;
            if !["off", "all", "10", "20", "30"].contains(&preset) {
                return Err(Error::Protocol);
            }
            json!({"command":"set","alarmOff":u8::from(preset=="off"),"alarmBefore10":u8::from(matches!(preset,"all"|"10")),"alarmBefore20":u8::from(matches!(preset,"all"|"20")),"alarmBefore30":u8::from(matches!(preset,"all"|"30"))})
        }
        "force_refresh" => json!({"action":"prewakeup"}),
        _ => return Err(Error::Unsupported),
    };
    Ok(Command {
        endpoint: cap.endpoint,
        prefix,
        body,
        pin: cap.pin,
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    fn car() -> Vehicle {
        Vehicle {
            key: "test".into(),
            id: Some("synthetic-car".into()),
            name: "Vehicle".into(),
            model: "Ioniq 5".into(),
            ccs2: 2,
        }
    }
    #[test]
    fn no_write_access_or_capability_means_no_command() {
        assert!(
            capabilities(&car(), &json!({}), false, false)
                .iter()
                .all(|c| !c.available || c.key == "refresh_cached")
        );
        assert!(
            capabilities(&car(), &json!({}), true, true)
                .iter()
                .find(|c| c.key == "lock")
                .is_some_and(|c| !c.available)
        );
    }
    #[test]
    fn command_shapes_are_explicit_and_invalid_values_are_refused() {
        let state = json!({"Green":{"ChargingInformation":{"TargetSoC":{}}}});
        let cap = capabilities(&car(), &state, true, true)
            .into_iter()
            .find(|c| c.key == "set_charge_limits")
            .unwrap();
        let cmd = build(&cap, &json!({"ac":80,"dc":90}), &state).unwrap();
        assert_eq!(cmd.body["targetSOClist"][0]["targetSOClevel"], 90);
        assert!(build(&cap, &json!({"ac":81,"dc":90}), &state).is_err());
        assert!(build(&cap, &json!({"ac":80,"dc":90,"url":"bad"}), &state).is_err());
    }
}
