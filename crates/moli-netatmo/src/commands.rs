//! Official Energy writes through the Hub. No retries of a possibly applied command.
use super::{DriverVault, Failure, Netatmo, Observation, Topology, oauth};
use crate::client::{self, Transport};
use http::Request;
use http_body_util::Full;
use moli_core::{Access, DeviceId, Kind, Value, now_ms};
use moli_runtime::{CommandRequest, DriverCtx};
use serde_json::{Value as Json, json};

fn room<'a>(topology: &'a Topology, home: &str, native: &str) -> Option<&'a Json> {
    let id = native.strip_prefix(&format!("{home}.room."))?;
    topology.data["body"]["homes"]
        .as_array()?
        .iter()
        .find(|h| h["id"] == home)?["rooms"]
        .as_array()?
        .iter()
        .find(|r| {
            r["id"].as_str() == Some(id) || r["id"].as_u64().is_some_and(|n| n.to_string() == id)
        })
}
fn supports(topology: &Topology, home: &str, native: &str) -> bool {
    let Some(room) = room(topology, home, native) else {
        return false;
    };
    let Some(ids) = room["module_ids"].as_array() else {
        return false;
    };
    topology.data["body"]["homes"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|h| h["id"] == home)
        .flat_map(|h| h["modules"].as_array().into_iter().flatten())
        .any(|m| {
            ids.contains(&m["id"]) && matches!(m["type"].as_str(), Some("NATherm1" | "OTM" | "NRV"))
        })
}
fn programs(topology: &Topology, home: &str) -> Vec<(String, String)> {
    topology.data["body"]["homes"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|h| h["id"] == home)
        .flat_map(|h| h["schedules"].as_array().into_iter().flatten())
        .filter_map(|p| Some((p["id"].as_str()?.into(), p["name"].as_str()?.into())))
        .collect()
}
pub(super) fn definition(
    ctx: &DriverCtx,
    obs: &Observation,
    topology: &Topology,
    write: bool,
) -> moli_core::Device {
    let mut device = super::device_base(obs, ctx);
    let Some((home, _)) = obs.native.split_once(".room.") else {
        return device;
    };
    if !supports(topology, home, &obs.native) {
        return device;
    }
    let choices = programs(topology, home);
    for (key, kind) in [
        ("setpoint", Kind::Text),
        (
            "home_mode",
            Kind::Enum {
                values: ["schedule", "away", "hg"]
                    .into_iter()
                    .map(Into::into)
                    .collect(),
            },
        ),
        (
            "program",
            Kind::Enum {
                values: choices.iter().map(|p| p.0.as_str().into()).collect(),
            },
        ),
    ] {
        device.points.retain(|p| p.key.as_ref() != key);
        let mut point = super::spec(key);
        point.kind = kind;
        point.access = Access { read: true, write };
        device.points.push(point);
    }
    let mut target = super::spec("target_temperature");
    target.kind = Kind::Numeric {
        min: Some(7.0),
        max: Some(30.0),
        step: Some(0.5),
    };
    device.points.push(target);
    let mut resume = super::spec("resume");
    resume.kind = Kind::Binary;
    resume.access = Access { read: false, write };
    device.points.push(resume);
    device.points.push(super::spec("program_names"));
    device
}
pub(super) fn publish_metadata(
    ctx: &DriverCtx,
    id: &DeviceId,
    obs: &Observation,
    topology: &Topology,
) {
    let Some((home, _)) = obs.native.split_once(".room.") else {
        return;
    };
    if !supports(topology, home, &obs.native) {
        return;
    }
    let names: std::collections::BTreeMap<_, _> = programs(topology, home).into_iter().collect();

    ctx.set_state(
        id,
        "target_temperature",
        if obs.current() {
            obs.readings
                .get("setpoint_temperature")
                .cloned()
                .unwrap_or(Value::Null)
        } else {
            Value::Null
        },
    );
    ctx.set_state(
        id,
        "program_names",
        Value::Text(json!(names).to_string().into()),
    );
    let selected = topology.data["body"]["homes"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|h| h["id"] == home)
        .and_then(|h| h["schedules"].as_array())
        .and_then(|s| s.iter().find(|p| p["selected"] == true))
        .and_then(|p| p["id"].as_str());
    ctx.set_state(id, "program", selected.map_or(Value::Null, Value::from));
}
type Prepared = (&'static str, Vec<(String, String)>);
pub(super) fn prepare(
    home: &str,
    native: &str,
    key: &str,
    value: &Value,
    topology: &Topology,
    now: u64,
) -> Result<Prepared, Failure> {
    if !supports(topology, home, native) {
        return Err(Failure::InvalidResponse);
    }
    let mut fields = vec![("home_id".into(), home.into())];
    let endpoint = match key {
        "setpoint" => {
            let Value::Text(raw) = value else {
                return Err(Failure::InvalidResponse);
            };
            let args: Json = serde_json::from_str(raw).map_err(|_| Failure::InvalidResponse)?;
            if args.as_object().is_none_or(|o| o.len() != 2) {
                return Err(Failure::InvalidResponse);
            }
            let temperature = args["temperature"]
                .as_f64()
                .filter(|n| n.is_finite() && (7.0..=30.0).contains(n))
                .ok_or(Failure::InvalidResponse)?;
            let minutes = args["duration_minutes"]
                .as_u64()
                .filter(|n| *n > 0 && *n <= 1440)
                .ok_or(Failure::InvalidResponse)?;
            fields.extend([
                (
                    "room_id".into(),
                    native
                        .split_once(".room.")
                        .ok_or(Failure::InvalidResponse)?
                        .1
                        .into(),
                ),
                ("mode".into(), "manual".into()),
                ("temp".into(), temperature.to_string()),
                ("endtime".into(), (now / 1000 + minutes * 60).to_string()),
            ]);
            "setroomthermpoint"
        }
        "home_mode" => {
            let Value::Text(mode) = value else {
                return Err(Failure::InvalidResponse);
            };
            if !matches!(mode.as_ref(), "schedule" | "away" | "hg") {
                return Err(Failure::InvalidResponse);
            }
            fields.push(("mode".into(), mode.to_string()));
            "setthermmode"
        }
        "program" => {
            let Value::Text(program) = value else {
                return Err(Failure::InvalidResponse);
            };
            if !programs(topology, home)
                .iter()
                .any(|p| p.0 == program.as_ref())
            {
                return Err(Failure::InvalidResponse);
            }
            fields.push(("schedule_id".into(), program.to_string()));
            "switchhomeschedule"
        }
        "resume" => {
            if value != &Value::Bool(true) {
                return Err(Failure::InvalidResponse);
            }
            fields.extend([
                (
                    "room_id".into(),
                    native
                        .split_once(".room.")
                        .ok_or(Failure::InvalidResponse)?
                        .1
                        .into(),
                ),
                ("mode".into(), "home".into()),
            ]);
            "setroomthermpoint"
        }
        _ => return Err(Failure::InvalidResponse),
    };
    Ok((endpoint, fields))
}
pub(super) async fn execute(
    driver: &Netatmo,
    ctx: &DriverCtx,
    account: &mut oauth::account::Account,
    topology: Option<&Topology>,
    command: &CommandRequest,
) -> Result<(), Failure> {
    let topology = topology.ok_or(Failure::InvalidResponse)?;
    let native = command
        .device
        .id
        .as_str()
        .split_once(':')
        .ok_or(Failure::InvalidResponse)?
        .1;
    let (endpoint, fields) = prepare(
        &driver.config.home_id,
        native,
        command.key.as_ref(),
        &command.value,
        topology,
        now_ms(),
    )?;
    let grant = account
        .ensure_with(
            driver.transport.as_ref(),
            &DriverVault(ctx),
            now_ms(),
            false,
        )
        .await
        .map_err(super::auth_failure)?;
    if !grant.write_allowed() || command.abandoned() {
        return Err(Failure::Credentials);
    }
    send(
        driver.transport.as_ref(),
        &grant.tokens.access_token,
        endpoint,
        &fields,
        command
            .deadline
            .saturating_duration_since(std::time::Instant::now()),
    )
    .await
}
async fn send(
    transport: &dyn Transport,
    token: &str,
    endpoint: &str,
    fields: &[(String, String)],
    remaining: std::time::Duration,
) -> Result<(), Failure> {
    let mut bearer = http::HeaderValue::from_str(&format!("Bearer {token}"))
        .map_err(|_| Failure::Credentials)?;
    bearer.set_sensitive(true);
    let form = fields
        .iter()
        .map(|(k, v)| format!("{}={}", client::encode(k), client::encode(v)))
        .collect::<Vec<_>>()
        .join("&");
    let request = Request::builder()
        .method("POST")
        .uri(format!("/api/{endpoint}"))
        .header("Authorization", bearer)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body(Full::new(moli_net::Body::from(form)))
        .map_err(|_| Failure::InvalidResponse)?;
    let response = tokio::time::timeout(remaining, transport.send(request))
        .await
        .map_err(|_| Failure::Timeout)??;
    client::decode(&response, false).map(|_| ())
}
