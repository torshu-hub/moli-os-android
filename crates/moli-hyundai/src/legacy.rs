//! HA region 1 Hyundai EU CCAPI: cached reads and explicit typed remote orders.
//! Public protocol from ApiImplType1/KiaUvoApiEU v4.45.0 (MIT notice in data/).
use crate::{
    auth::ApiMode,
    client::{Client, Error, Session, Transport},
    model::{self, Vehicle, at, number},
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use http::HeaderMap;
use serde::Deserialize;
use serde_json::{Value as Json, json};
pub(crate) const BASE: &str = "https://prd.eu-ccapi.hyundai.com:8080";
const APP: &str = "014d2225-8495-4735-812d-2616334fd15d";
const SERVICE: &str = "6d477c38-3ca4-4cf3-9557-2a1929a94654";
impl<T: Transport> Client<T> {
    pub(crate) fn legacy_vehicle_headers(
        session: Option<&Session>,
        ccs2: u64,
    ) -> Result<HeaderMap, Error> {
        let raw = format!("{APP}:{}", moli_core::now_ms() / 1000);
        let cfb = STANDARD
            .decode("RFtoRq/vDXJmRndoZaZQyfOot7OrIqGVFj96iY2WL3yyH5Z/pUvlUhqmCxD2t+D65SQ=")
            .map_err(|_| Error::Protocol)?;
        let stamp = STANDARD.encode(raw.bytes().zip(cfb).map(|(a, b)| a ^ b).collect::<Vec<_>>());
        let mut h = HeaderMap::new();
        for (k, v) in [
            ("ccsp-service-id", SERVICE),
            ("ccsp-application-id", APP),
            ("user-agent", "okhttp/3.12.0"),
        ] {
            h.insert(k, v.parse().map_err(|_| Error::Protocol)?);
        }
        h.insert("stamp", stamp.parse().map_err(|_| Error::Protocol)?);
        h.insert(
            "ccuccs2protocolsupport",
            ccs2.to_string().parse().map_err(|_| Error::Protocol)?,
        );
        if let Some(s) = session {
            h.insert(
                "authorization",
                format!("Bearer {}", s.access)
                    .parse()
                    .map_err(|_| Error::Protocol)?,
            );
            h.insert(
                "ccsp-device-id",
                s.device.parse().map_err(|_| Error::Protocol)?,
            );
        }
        Ok(h)
    }
    pub(crate) async fn login_identity(&mut self, mode: ApiMode) -> Result<String, Error> {
        let random = crate::crypto::device_id().map_err(|_| Error::Protocol)?;
        if mode == ApiMode::Gspa {
            return Ok(random);
        }
        self.phase = "device_registration";
        let h = Self::legacy_vehicle_headers(None, 0)?;
        let body = json!({"pushRegId":crate::crypto::hex(ring::digest::digest(&ring::digest::SHA256,random.as_bytes()).as_ref()),"pushType":"GCM","uuid":random});
        let result = self
            .legacy_request("POST", "/api/v1/spa/notifications/register", &h, &body)
            .await;
        match result {
            Ok(data) => Ok(data["resMsg"]["deviceId"]
                .as_str()
                .filter(|id| model::validate_id(id).is_ok())
                .unwrap_or(&random)
                .to_owned()),
            Err(error @ Error::RateLimit(_)) => Err(error),
            // Reference EU tolerates registration failure, no automatic replay.
            Err(_) => Ok(random),
        }
    }
    pub(crate) async fn legacy_request(
        &mut self,
        method: &str,
        path: &str,
        headers: &HeaderMap,
        body: &Json,
    ) -> Result<Json, Error> {
        if !path.starts_with("/api/v1/") && !path.starts_with("/api/v2/") {
            return Err(Error::Unsupported);
        }
        let payload = if body.is_null() {
            String::new()
        } else {
            body.to_string()
        };
        let response = self
            .raw(method, &format!("{BASE}{path}"), headers, &payload, false)
            .await?;
        let value = Self::json(response)?;
        if value["retCode"] == "F" || value.get("error").is_some() {
            return Err(match value["resCode"].as_str().unwrap_or("") {
                "7501" => Error::Authentication,
                "4005" | "5921" => Error::Unsupported,
                "5091" => Error::RateLimit(900),
                _ => Error::Protocol,
            });
        }
        if value["retCode"] != "S"
            && value.get("resMsg").is_none()
            && value.get("controlToken").is_none()
        {
            return Err(Error::Protocol);
        }
        Ok(value)
    }
    pub(crate) async fn legacy_vehicles(&mut self, session: &Session) -> Result<Json, Error> {
        let h = Self::legacy_vehicle_headers(Some(session), 0)?;
        let data = self
            .legacy_request("GET", "/api/v1/spa/vehicles", &h, &Json::Null)
            .await?;
        Ok(json!({"vehicles":data["resMsg"]["vehicles"]}))
    }
    pub(crate) async fn cached_status(
        &mut self,
        session: &mut Session,
        vehicle: &Vehicle,
    ) -> Result<Json, Error> {
        let id = model::validate_id(vehicle.id.as_deref().ok_or(Error::Protocol)?)?;
        if session.api_mode == ApiMode::Gspa {
            return self
                .gspa(
                    session,
                    "GET",
                    &format!("status/vehicles/{id}/stored-status"),
                    &Json::Null,
                    false,
                    vehicle.ccs2,
                )
                .await;
        }
        self.phase = "cached_status";
        let h = Self::legacy_vehicle_headers(Some(session), vehicle.ccs2)?;
        let endpoint = if vehicle.ccs2 == 0 {
            "status/latest"
        } else {
            "ccs2/carstatus/latest"
        };
        let data = self
            .legacy_request(
                "GET",
                &format!("/api/v1/spa/vehicles/{id}/{endpoint}"),
                &h,
                &Json::Null,
            )
            .await?;
        if vehicle.ccs2 != 0 {
            return Ok(
                json!({"state":data["resMsg"]["state"],"lastUpdateTime":data["lastUpdateTime"]}),
            );
        }
        Ok(normalize(&data["resMsg"]["vehicleStatusInfo"]))
    }
    pub(crate) async fn auxiliary(
        &mut self,
        session: &mut Session,
        vehicle: &Vehicle,
        key: &str,
        path: &str,
    ) -> Result<Json, Error> {
        if session.api_mode == ApiMode::Gspa {
            return self
                .gspa(session, "GET", path, &Json::Null, false, vehicle.ccs2)
                .await;
        }
        if !matches!(key, "driving_info" | "driving_history") {
            return Err(Error::Unsupported);
        }
        self.phase = "driving_history";
        let id = model::validate_id(vehicle.id.as_deref().ok_or(Error::Protocol)?)?;
        let h = Self::legacy_vehicle_headers(Some(session), vehicle.ccs2)?;
        // This POST queries stored statistics; it is not a wake/control endpoint.
        let data = self
            .legacy_request(
                "POST",
                &format!("/api/v1/spa/vehicles/{id}/drvhistory"),
                &h,
                &json!({"periodTarget":u8::from(key=="driving_info")}),
            )
            .await?;
        Ok(data["resMsg"].clone())
    }
}
#[derive(Deserialize)]
struct Field {
    key: String,
    source: String,
    target: String,
    conversion: String,
}
fn set(root: &mut Json, path: &str, value: Json) {
    if value.is_null() {
        return;
    }
    let mut node = root;
    let p = path.split('.').collect::<Vec<_>>();
    for k in &p[..p.len() - 1] {
        if !node[*k].is_object() {
            node[*k] = json!({});
        }
        node = &mut node[*k];
    }
    node[p[p.len() - 1]] = value;
}
fn field<'a>(state: &'a Json, path: &str) -> &'a Json {
    let mut value = state;
    for key in path.split('.') {
        value = if let Ok(i) = key.parse::<usize>() {
            value.get(i)
        } else {
            value.get(key)
        }
        .unwrap_or(&Json::Null);
    }
    value
}
fn km(value: &Json, unit: &Json) -> Json {
    number(value)
        .zip(unit.as_u64())
        .and_then(|(n, u)| match u {
            1 => Some(n),
            2 | 3 => Some(n * 1.609_344),
            _ => None,
        })
        .filter(|n| *n >= 0.0)
        .map_or(Json::Null, |v| json!(v))
}
fn clock(state: &Json, path: &str) -> Option<(u64, u64)> {
    let t = field(state, path).as_str()?;
    if t.len() != 4 {
        return None;
    }
    let h = t[..2].parse::<u64>().ok()?;
    let m = t[2..].parse::<u64>().ok()?;
    let section = field(
        state,
        &format!("{}.timeSection", path.strip_suffix(".time")?),
    )
    .as_u64()?;
    if h == 0 || h > 12 || m > 59 || section > 1 {
        return None;
    }
    Some((h % 12 + section * 12, m))
}
fn local_event(value: &Json) -> Option<u64> {
    let raw = model::timestamp(value)?;
    if value.as_str().is_some_and(|s| s.len() == 14) {
        let dt = jiff::Timestamp::from_millisecond(i64::try_from(raw).ok()?)
            .ok()?
            .to_zoned(jiff::tz::TimeZone::UTC)
            .datetime();
        u64::try_from(dt.in_tz("Europe/Paris").ok()?.timestamp().as_millisecond()).ok()
    } else {
        Some(raw)
    }
}
/// Normalize known legacy fields to the existing typed snapshot; raw GPS is not retained.
#[allow(clippy::too_many_lines)]
pub(crate) fn normalize(source: &Json) -> Json {
    let mut state = json!({});
    let fields: Vec<Field> = serde_json::from_str(include_str!("../data/legacy-fields.json"))
        .expect("public legacy field map");
    for f in fields {
        let mut raw = field(source, &f.source).clone();
        if raw.is_null() {
            continue;
        }
        if f.key.starts_with("ev_charge_limits_") {
            continue;
        }
        if f.conversion == "temperature" {
            let value = raw
                .as_str()
                .and_then(|s| u8::from_str_radix(s.trim_end_matches('H'), 16).ok())
                .filter(|i| *i < 36)
                .map(|i| 14.0 + f64::from(i) * 0.5);
            let unit = field(source, &f.source.replace(".value", ".unit"));
            let c = value.zip(unit.as_u64()).and_then(|(v, u)| match u {
                0 => Some(v),
                1 => Some((v - 32.0) * 5.0 / 9.0),
                _ => None,
            });
            raw = json!(c);
            set(&mut state, &f.target.replace(".Value", ".Unit"), json!(0));
        } else if f.conversion == "distance" {
            raw = km(&raw, field(source, &f.source.replace(".value", ".unit")));
        } else if f.conversion == "clock" || f.key.ends_with("departure_time") {
            if let Some((h, m)) = clock(source, &f.source) {
                if f.conversion == "clock" {
                    set(&mut state, &f.target, json!(h));
                    set(&mut state, &f.target.replace("Hour", "Min"), json!(m));
                } else {
                    set(&mut state, &f.target, json!({"Hour":h,"Min":m}));
                }
            }
            continue;
        }
        if f.key == "air_control_is_on"
            && let Some(v) = raw.as_bool()
        {
            raw = json!(u8::from(v));
        }
        if raw.is_object() {
            continue;
        }
        set(&mut state, &f.target, raw);
    }
    for (target, source_key) in [
        (
            "Cabin.SteeringWheel.Heat.State",
            "vehicleStatus.steerWheelHeat",
        ),
        (
            "Green.ChargingDoor.State",
            "vehicleStatus.evStatus.chargePortDoorOpenStatus",
        ),
        (
            "Green.BatteryManagement.SoH.Ratio",
            "vehicleStatus.evStatus.batterySoh",
        ),
    ] {
        set(&mut state, target, field(source, source_key).clone());
    }
    set(
        &mut state,
        "Date",
        json!(local_event(field(source, "vehicleStatus.time"))),
    );
    set(
        &mut state,
        "Drivetrain.Odometer",
        km(
            field(source, "odometer.value"),
            field(source, "odometer.unit"),
        ),
    );
    set(
        &mut state,
        "Drivetrain.OdometerUnit",
        field(source, "odometer.unit").clone(),
    );
    set(
        &mut state,
        "Drivetrain.FuelSystem.DTE.Total",
        km(
            field(
                source,
                "vehicleStatus.evStatus.drvDistance.0.rangeByFuel.totalAvailableRange.value",
            ),
            field(
                source,
                "vehicleStatus.evStatus.drvDistance.0.rangeByFuel.totalAvailableRange.unit",
            ),
        ),
    );
    set(&mut state, "Drivetrain.FuelSystem.DTE.Unit", json!(1));
    if let Some(locked) = flag(field(source, "vehicleStatus.doorLock")) {
        for door in ["Row1.Driver", "Row1.Passenger", "Row2.Left", "Row2.Right"] {
            set(
                &mut state,
                &format!("Cabin.Door.{door}.Lock"),
                json!(u8::from(!locked)),
            );
        }
    }
    for (key, wire) in [("Standard", 1), ("Quick", 0)] {
        let v = field(
            source,
            "vehicleStatus.evStatus.reservChargeInfos.targetSOClist",
        )
        .as_array()
        .and_then(|a| a.iter().find(|v| v["plugType"] == wire))
        .and_then(|v| number(&v["targetSOClevel"]));
        set(
            &mut state,
            &format!("Green.ChargingInformation.TargetSoC.{key}"),
            json!(v),
        );
    }
    let location = field(source, "vehicleLocation");
    if let (Some(lat), Some(lon)) = (
        number(&location["coord"]["lat"]),
        number(&location["coord"]["lon"]),
    ) {
        set(&mut state, "Location.GeoCoord.Latitude", json!(lat));
        set(&mut state, "Location.GeoCoord.Longitude", json!(lon));
        set(
            &mut state,
            "Location.TimeStamp",
            json!(local_event(&location["time"])),
        );
    }
    json!({"state":{"Vehicle":state},"lastUpdateTime":null,"legacy_charging":flag(field(source,"vehicleStatus.evStatus.batteryCharge")),"legacy_plugged":number(field(source,"vehicleStatus.evStatus.batteryPlugin")).filter(|n|(0.0..=2.0).contains(n)).map(|n|n>0.0)})
}
#[allow(clippy::float_cmp)] // Exact 0/1 protocol flags, never measured quantities.
fn flag(value: &Json) -> Option<bool> {
    value.as_bool().or_else(|| {
        number(value)
            .filter(|n| *n == 0.0 || *n == 1.0)
            .map(|n| n == 1.0)
    })
}

#[allow(clippy::too_many_lines)] // Keep the selected-API capability profile together.
pub(crate) fn capabilities(
    vehicle: &Vehicle,
    state: &Json,
    write: bool,
    pin: bool,
) -> Vec<crate::commands::Capability> {
    let mut caps = crate::commands::capabilities(vehicle, state, write, pin);
    for cap in &mut caps {
        if cap.key == "refresh_cached" {
            continue;
        }
        let requires_ccs2 = matches!(
            cap.key,
            "set_charging_current"
                | "set_v2l_limit"
                | "start_hazard_lights"
                | "start_hazard_lights_and_horn"
        );
        let not_exposed = matches!(
            cap.key,
            "capture_svm_image"
                | "set_charge_alarm"
                | "start_battery_conditioning"
                | "stop_battery_conditioning"
        );
        cap.pin = match cap.key {
            "lock" | "unlock" | "start_climate" | "stop_climate" | "start_charge"
            | "stop_charge" => vehicle.ccs2 != 0,
            "set_charge_limits" | "set_charging_current" | "set_v2l_limit" | "force_refresh" => {
                false
            }
            _ => true,
        };
        let missing = match cap.key {
            "lock" | "unlock" => at(state, "Cabin.Door").is_null(),
            "start_climate" | "stop_climate" => at(state, "Cabin.HVAC").is_null(),
            "start_charge" | "stop_charge" => {
                at(state, "Green.BatteryManagement.BatteryRemain.Ratio").is_null()
            }
            "set_charge_limits" => {
                at(state, "Green.ChargingInformation.TargetSoC.Standard").is_null()
                    && at(state, "Green.ChargingInformation.TargetSoC.Quick").is_null()
            }
            "set_charging_current" => {
                at(state, "Green.ChargingInformation.ElectricCurrentLevel").is_null()
            }
            "set_v2l_limit" => at(state, "Green.Electric.SmartGrid.VehicleToLoad").is_null(),
            "start_hazard_lights" | "start_hazard_lights_and_horn" => {
                at(state, "Body.Lights.Hazard").is_null()
            }
            "set_windows" => at(state, "Cabin.Window").is_null(),
            "open_charge_port" | "close_charge_port" => {
                at(state, "Green.ChargingDoor.State").is_null()
            }
            "schedule_charging_and_climate" | "set_off_peak_charging" => {
                at(state, "Green.Reservation").is_null()
            }
            _ => false,
        };
        cap.reason = if !write {
            "commands_not_authorized"
        } else if not_exposed {
            "legacy_reference_not_implemented"
        } else if requires_ccs2 && vehicle.ccs2 == 0 {
            "pre_ccs2_not_supported"
        } else if cap.key == "schedule_charging_and_climate" && vehicle.ccs2 != 0 {
            "legacy_split_schedule_required"
        } else if cap.key == "set_off_peak_charging" && vehicle.ccs2 == 0 {
            "legacy_use_combined_schedule"
        } else if cap.pin && !pin {
            "pin_required"
        } else if missing {
            "capability_not_reported"
        } else if cap.key == "force_refresh"
            && number(at(state, "Electronics.Battery.Level")).is_none_or(|v| v < 40.0 || v > 100.0)
        {
            "battery_12v_unconfirmed_or_low"
        } else {
            "protocol_supported_to_qualify"
        };
        cap.available = cap.reason == "protocol_supported_to_qualify";
        if cap.key == "set_navigation" {
            cap.parameters = json!({"navigation_token":{"type":"private_navigation"}});
        }
        if cap.key == "set_windows" {
            cap.parameters
                .as_object_mut()
                .expect("schema")
                .retain(|k, _| !k.ends_with("curtain"));
        }
        if cap.key == "start_climate" && vehicle.ccs2 == 0 {
            cap.parameters
                .as_object_mut()
                .expect("schema")
                .retain(|k, _| ["temperature", "duration", "defrost"].contains(&k.as_str()));
            cap.parameters["heating"] = json!({"min":0,"max":4});
        }
    }
    if vehicle.ccs2 != 0 {
        let mut climate = caps
            .iter()
            .find(|c| c.key == "schedule_charging_and_climate")
            .expect("schedule schema")
            .clone();
        climate.key = "schedule_climate";
        climate.pin = true;
        climate
            .parameters
            .as_object_mut()
            .expect("schema")
            .retain(|k, _| !k.starts_with("off_peak") && k != "charging");
        climate.reason = if !write {
            "commands_not_authorized"
        } else if !pin {
            "pin_required"
        } else if at(state, "Green.Reservation").is_null() {
            "capability_not_reported"
        } else {
            "protocol_supported_to_qualify"
        };
        climate.available = climate.reason == "protocol_supported_to_qualify";
        caps.push(climate);
    }
    caps
}
pub(crate) struct Order {
    pub method: &'static str,
    pub path: String,
    pub body: Json,
}
/// Typed GSPA-neutral values are translated to HA's region-1 payloads here.
#[allow(clippy::too_many_lines)]
pub(crate) fn order(
    vehicle: &Vehicle,
    cap: &crate::commands::Capability,
    value: &Json,
    state: &Json,
    device: &str,
) -> Result<Order, Error> {
    let id = model::validate_id(vehicle.id.as_deref().ok_or(Error::Protocol)?)?;
    let base = crate::commands::build(cap, value, state)?;
    let mut b = base.body;
    let old = vehicle.ccs2 == 0;
    let mut version = 2;
    let mut method = "POST";
    let endpoint = match cap.key {
        "lock" | "unlock" => {
            if old {
                version = 1;
                b = json!({"action":cap.key,"deviceId":device});
                "control/door"
            } else {
                b = json!({"command":cap.key});
                "ccs2/control/door"
            }
        }
        "start_charge" | "stop_charge" => {
            if old {
                version = 1;
                b = json!({"action":if cap.key=="start_charge"{"start"}else{"stop"},"deviceId":device});
                "control/charge"
            } else {
                "ccs2/control/charge"
            }
        }
        "start_climate" => {
            if old {
                version = 1;
                let v = if let Some(v) = value.as_str() {
                    serde_json::from_str::<Json>(v).map_err(|_| Error::Protocol)?
                } else {
                    value.clone()
                };
                let heating = v["heating"].as_u64().unwrap_or(0);
                if heating > 4 {
                    return Err(Error::Protocol);
                }
                let temp = b["hvacTemp"]
                    .as_str()
                    .and_then(|v| v.parse::<f64>().ok())
                    .ok_or(Error::Protocol)?;
                b = json!({"action":"start","hvacType":0,"options":{"defrost":b["windshieldFrontDefogState"].as_bool().unwrap_or(false),"heating1":heating,"igniOnDuration":b["ignitionDuration"]},"tempCode":format!("{:02X}H",format!("{:.0}",(temp-14.0)*2.0).parse::<u8>().map_err(|_|Error::Protocol)?),"unit":"C"});
                "control/temperature"
            } else {
                b["hvacTemp"] = json!(
                    b["hvacTemp"]
                        .as_str()
                        .and_then(|v| v.parse::<f64>().ok())
                        .ok_or(Error::Protocol)?
                );
                b["drvSeatLoc"] = json!(if matches!(
                    at(state, "Drivetrain.OdometerUnit").as_u64(),
                    Some(2 | 3)
                ) {
                    "R"
                } else {
                    "L"
                });
                "ccs2/control/temperature"
            }
        }
        "stop_climate" => {
            if old {
                version = 1;
                b = json!({"action":"stop","hvacType":0,"options":{"defrost":true,"heating1":1},"tempCode":"10H","unit":"C"});
                "control/temperature"
            } else {
                "ccs2/control/temperature"
            }
        }
        "set_charge_limits" => {
            version = 1;
            b.as_object_mut().ok_or(Error::Protocol)?.remove("command");
            "charge/target"
        }
        "set_charging_current" => {
            version = 1;
            b.as_object_mut().ok_or(Error::Protocol)?.remove("command");
            "ccs2/charge/chargingcurrent"
        }
        "set_v2l_limit" => {
            version = 1;
            b.as_object_mut().ok_or(Error::Protocol)?.remove("command");
            "ccs2/charge/dischargelimit"
        }
        "open_charge_port" | "close_charge_port" => {
            b = json!({"action":if cap.key=="open_charge_port"{"open"}else{"close"}});
            "control/portdoor"
        }
        "start_valet_mode" | "stop_valet_mode" => {
            b = json!({"action":if cap.key=="start_valet_mode"{"activate"}else{"deactivate"}});
            "control/valet"
        }
        "start_hazard_lights" => "ccs2/control/light",
        "start_hazard_lights_and_horn" => "ccs2/control/hornlight",
        "set_windows" => {
            let right = matches!(at(state, "Drivetrain.OdometerUnit").as_u64(), Some(2 | 3));
            b = json!({"backLeft":b["rlSeatWindow"],"backRight":b["rrSeatWindow"],"frontLeft":b[if right{"psgSeatWindow"}else{"drvSeatWindow"}],"frontRight":b[if right{"drvSeatWindow"}else{"psgSeatWindow"}]});
            b.as_object_mut()
                .ok_or(Error::Protocol)?
                .retain(|_, v| !v.is_null());
            "control/windowcurtain"
        }
        "schedule_charging_and_climate" => {
            let mut body = b["reservChargeInfo"].clone();
            body["offPeakPowerInfo"] = b["offPeakPowerInfo"].clone();
            body["reservFlag"] = b["reservFlag"].clone();
            b = body;
            "ccs2/reservation/chargehvac"
        }
        "set_off_peak_charging" => {
            b = json!({"reservFlag":b["reservFlag"],"offpeakPowerFlag":if b["offPeakPowerInfo"]["offPeakPowerFlag"]==2{1}else{2},"reservStartTime":b["offPeakPowerInfo"]["offPeakPowerTime1"]["starttime"],"reservEndTime":b["offPeakPowerInfo"]["offPeakPowerTime1"]["endtime"]});
            "ccs2/reservation/charge"
        }
        "schedule_climate" => {
            let mut body = json!({});
            for i in [1, 2] {
                let dep = &b["reservChargeInfo"][format!("reservChargeInfo{i}")];
                let mut climate = dep["reservFatcSet"].clone();
                climate
                    .as_object_mut()
                    .ok_or(Error::Protocol)?
                    .remove("heating1");
                body[format!("reservedHVACInfo{i}")] = json!({"reservHVACflag":u8::from(dep["reservChargeSet"]==true),"reservInfo":dep["reservInfo"],"reservHVACSet":climate});
            }
            b = body;
            "ccs2/reservation/hvac"
        }
        "force_refresh" => {
            method = "GET";
            version = 1;
            b = Json::Null;
            if old { "status" } else { "ccs2/carstatus" }
        }
        "set_navigation" => "location/routes",
        _ => return Err(Error::Unsupported),
    };
    Ok(Order {
        method,
        path: format!("/api/v{version}/spa/vehicles/{id}/{endpoint}"),
        body: b,
    })
}
impl<T: Transport> Client<T> {
    pub(crate) async fn legacy_control_headers(
        &mut self,
        session: &mut Session,
        pin: bool,
        ccs2: u64,
    ) -> Result<HeaderMap, Error> {
        if session.expires <= moli_core::now_ms() / 1000 + 10 {
            return Err(Error::Expired);
        }
        let mut h = Self::legacy_vehicle_headers(Some(session), ccs2)?;
        if pin {
            if session.control.is_none()
                || session.control_expires <= moli_core::now_ms() / 1000 + 10
            {
                self.phase = "pin";
                let data=self.legacy_request("PUT","/api/v1/user/pin?token=",&h,&json!({"deviceId":session.device,"pin":session.pin.as_ref().ok_or(Error::Pin)?})).await?;
                session.control = Some(
                    data["controlToken"]
                        .as_str()
                        .filter(|s| !s.is_empty() && s.len() < 16384)
                        .ok_or(Error::Pin)?
                        .to_owned(),
                );
                session.control_expires =
                    moli_core::now_ms() / 1000 + data["expiresTime"].as_u64().unwrap_or(0).min(600);
            }
            let token = format!("Bearer {}", session.control.as_deref().ok_or(Error::Pin)?)
                .parse()
                .map_err(|_| Error::Protocol)?;
            h.insert("authorization", token);
            h.insert("authorizationccsp", h["authorization"].clone());
        }
        Ok(h)
    }
}
