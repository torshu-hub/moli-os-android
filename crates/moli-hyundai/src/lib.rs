//! Native Hyundai Europe CCI/GSPA. Passive cloud reads; voluntary human controls.
pub mod account;
pub mod auth;
mod client;
mod commands;
mod crypto;
mod legacy;
mod model;
mod private;
#[cfg(test)]
mod tests;
use client::Client;
pub use client::{Error, Session};
use moli_core::{Access, Device, DeviceId, Kind, Origin, PointSpec, Semantic, Unit, Value, now_ms};
use moli_history::{
    journal::Journal,
    vehicle::{DrivingDay, VehicleObservation},
};
use moli_runtime::{BoxFuture, CommandRequest, Driver, DriverCtx};
use serde::{Deserialize, Serialize};
use serde_json::{Value as Json, json};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tokio::time::Instant;
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub vehicles: Vec<String>,
    pub poll_seconds: u64,
    pub max_age_seconds: u64,
    pub commands_enabled: bool,
    pub location_enabled: bool,
    pub weather_point: Option<String>,
    pub dedicated_ev_meter: Option<String>,
}
impl Default for Config {
    fn default() -> Self {
        Self {
            vehicles: Vec::new(),
            poll_seconds: 900,
            max_age_seconds: 3600,
            commands_enabled: false,
            location_enabled: false,
            weather_point: None,
            dedicated_ev_meter: None,
        }
    }
}
impl Config {
    pub fn validate(&self) -> anyhow::Result<()> {
        anyhow::ensure!(
            (900..=86_400).contains(&self.poll_seconds),
            "Hyundai polling 900..86400s"
        );
        anyhow::ensure!(
            (self.poll_seconds..=172_800).contains(&self.max_age_seconds),
            "Hyundai maximum age poll..172800s"
        );
        anyhow::ensure!(
            self.vehicles.len() <= 20
                && self.vehicles.iter().all(|s| s.starts_with("v_")
                    && s.len() == 26
                    && s[2..].bytes().all(|b| b.is_ascii_hexdigit())),
            "invalid local vehicle selection"
        );
        for p in [&self.weather_point, &self.dedicated_ev_meter]
            .into_iter()
            .flatten()
        {
            anyhow::ensure!(
                p.len() <= 128 && moli_core::PointId::from(p.as_str()).split().is_some(),
                "invalid source point"
            );
        }
        Ok(())
    }
}
pub struct Hyundai {
    config: Config,
    path: PathBuf,
}
impl std::fmt::Debug for Hyundai {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Hyundai EU")
    }
}
impl Hyundai {
    pub fn new(config: Config, data: &Path) -> anyhow::Result<Self> {
        config.validate()?;
        Ok(Self {
            config,
            path: data.join("history.db"),
        })
    }
}
struct Vault<'a>(&'a DriverCtx);
impl account::Vault for Vault<'_> {
    fn get(&self) -> Result<Option<String>, Error> {
        Ok(self.0.secret(account::SECRET))
    }
    fn put<'a>(&'a self, text: &'a str) -> BoxFuture<'a, Result<(), Error>> {
        Box::pin(async move {
            self.0
                .store_secret(account::SECRET, text)
                .await
                .map_err(|_| Error::Storage)
        })
    }
}
struct Record {
    api_mode: auth::ApiMode,
    vehicle: model::Vehicle,
    snapshot: model::Snapshot,
    private: Arc<private::Private>,
    unsupported: BTreeMap<String, &'static str>,
}
enum LoopEvent {
    Command(Option<CommandRequest>),
    Poll,
    Expire,
}
impl Driver for Hyundai {
    fn kind(&self) -> &'static str {
        "hyundai"
    }
    #[allow(clippy::too_many_lines)] // Single serialized account/command scheduler; no background control task.
    fn run<'a>(&'a self, ctx: &'a mut DriverCtx) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(async move {
            let mut journal = Journal::new(self.path.clone());
            let mut client = Client::new();
            let mut records = BTreeMap::new();
            let mut account = ctx
                .secret(account::SECRET)
                .map(|s| account::Account::parse(&s))
                .transpose()?;
            let mut next = Instant::now();
            let mut extras = Instant::now();
            let mut blocked_until = Instant::now();
            let mut manual_cache: Option<Instant> = None;
            for id in ctx.devices() {
                ctx.set_availability(&id, false);
                ctx.set_state(&id, "quality", Value::from("unconfirmed"));
            }
            loop {
                let expiry = records
                    .values()
                    .filter(|r: &&Record| r.snapshot.quality == "cached_recent")
                    .filter_map(|r| r.snapshot.vehicle_at)
                    .map(|at| {
                        at.saturating_add(self.config.max_age_seconds * 1000)
                            .saturating_add(1)
                    })
                    .min();
                let expire_at = Instant::now()
                    + Duration::from_millis(
                        expiry.map_or(86_400_000, |at| at.saturating_sub(now_ms())),
                    );
                let event = tokio::select! {cmd=ctx.next_command()=>LoopEvent::Command(cmd),()=tokio::time::sleep_until(next)=>LoopEvent::Poll,()=tokio::time::sleep_until(expire_at)=>LoopEvent::Expire};
                match event {
                    LoopEvent::Expire => {
                        for (id, record) in &mut records {
                            if record
                                .snapshot
                                .expire(now_ms(), self.config.max_age_seconds * 1000)
                            {
                                ctx.set_state(id, "quality", Value::from(record.snapshot.quality));
                                ctx.set_state(
                                    id,
                                    "snapshot",
                                    Value::from_json(&record.snapshot.summary()),
                                );
                            }
                        }
                    }
                    LoopEvent::Command(None) => return Ok(()),
                    LoopEvent::Command(Some(cmd)) => {
                        if cmd.key.as_ref() == "refresh_cached" {
                            if cmd.origin != Origin::Ui || cmd.abandoned() {
                                cmd.reply(Err("voluntary human session required".into()));
                            } else if Instant::now() < blocked_until
                                || manual_cache
                                    .is_some_and(|at| at.elapsed() < Duration::from_secs(60))
                            {
                                cmd.reply(Err("cached refresh cooldown".into()));
                            } else {
                                manual_cache = Some(Instant::now());
                                extras = Instant::now();
                                next = Instant::now();
                                cmd.reply(Ok(()));
                            }
                            continue;
                        }

                        let result = if Instant::now() < blocked_until {
                            Err(Error::RateLimit(900))
                        } else {
                            self.command(ctx, &mut client, account.as_mut(), &mut records, &cmd)
                                .await
                        };
                        if let Err(Error::RateLimit(seconds)) = result {
                            blocked_until = Instant::now() + Duration::from_secs(seconds.max(900));
                            next = blocked_until;
                        }
                        cmd.reply(result.map_err(|e| {
                            format!("Hyundai: {} (résultat physique non confirmé)", e.code())
                        }));
                    }
                    LoopEvent::Poll => {
                        for id in ctx.devices() {
                            if !self.config.vehicles.is_empty()
                                && !self
                                    .config
                                    .vehicles
                                    .iter()
                                    .any(|key| ctx.device_id(key) == id)
                            {
                                continue;
                            }
                            journal.weather(
                                ctx,
                                &id,
                                self.config.weather_point.as_deref(),
                                self.config.max_age_seconds * 1000,
                            );
                        }

                        let extra_due = Instant::now() >= extras;
                        let result = if let Some(account) = &mut account {
                            self.collect(
                                ctx,
                                &mut journal,
                                &mut client,
                                account,
                                &mut records,
                                extra_due,
                            )
                            .await
                        } else {
                            Err(Error::Authentication)
                        };
                        let delay = match result {
                            Ok(()) => {
                                ctx.ready();
                                if extra_due {
                                    extras = Instant::now() + Duration::from_secs(6 * 3600);
                                }
                                self.config.poll_seconds
                            }
                            Err(error) => {
                                for id in ctx.devices() {
                                    ctx.set_availability(&id, false);
                                    ctx.set_state(&id, "quality", Value::from("unavailable"));
                                    ctx.set_state(&id, "error", Value::from(error.code()));
                                }
                                ctx.wait_for(format!("Bluelink/myHyundai : {}", error.code()));
                                ctx.diagnostic(
                                    "hyundai_read_error",
                                    error.code(),
                                    self.config.poll_seconds,
                                );
                                if let Error::RateLimit(seconds) = error {
                                    let seconds = seconds.max(900);
                                    blocked_until = Instant::now() + Duration::from_secs(seconds);
                                    seconds
                                } else if error == Error::Authentication
                                    && account
                                        .as_ref()
                                        .is_some_and(|a| a.phase == account::Phase::Ready)
                                {
                                    if let Some(a) = &mut account {
                                        a.session.expires = 0;
                                    }
                                    self.config.poll_seconds
                                } else if matches!(
                                    error,
                                    Error::Authentication | Error::Captcha | Error::Pin
                                ) {
                                    86400
                                } else {
                                    self.config.poll_seconds
                                }
                            }
                        };
                        next = Instant::now() + Duration::from_secs(delay);
                    }
                }
            }
        })
    }
}
impl Hyundai {
    #[allow(clippy::too_many_lines)] // One passive cloud batch and its typed persistence boundary.
    async fn collect(
        &self,
        ctx: &DriverCtx,
        journal: &mut Journal,
        client: &mut Client,
        account: &mut account::Account,
        records: &mut BTreeMap<DeviceId, Record>,
        extra_due: bool,
    ) -> Result<(), Error> {
        account.ensure(client, &Vault(ctx)).await?;
        if extra_due {
            let discovered = model::vehicles(&client.vehicles(&account.session).await?);
            account.vehicles = discovered;
            ctx.store_secret(account::SECRET, &account.encode()?)
                .await
                .map_err(|_| Error::Storage)?;
        }
        for vehicle in account.vehicles.clone() {
            if !self.config.vehicles.is_empty() && !self.config.vehicles.contains(&vehicle.key) {
                continue;
            }
            let provider = model::validate_id(vehicle.id.as_deref().ok_or(Error::Protocol)?)?;
            let id = ctx.device_id(&vehicle.key);
            let payload = client.cached_status(&mut account.session, &vehicle).await?;
            let mut snapshot =
                model::Snapshot::parse(&payload, now_ms(), self.config.max_age_seconds * 1000);
            snapshot
                .values
                .insert("snapshot".into(), snapshot.summary());
            // Driving lifetime totals carry Wh in this protocol. Never infer from SOC.
            match client
                .auxiliary(
                    &mut account.session,
                    &vehicle,
                    "driving_info",
                    &format!("driving-info/vehicles/{provider}"),
                )
                .await
            {
                Ok(data) => {
                    snapshot
                        .values
                        .insert("driving_info".into(), safe_details("driving_info", &data));
                }
                Err(e @ (Error::Authentication | Error::RateLimit(_))) => return Err(e),
                Err(e) => {
                    snapshot.values.insert(
                        "driving_info".into(),
                        json!({"available":false,"reason":e.code(),"received_at":now_ms()}),
                    );
                }
            }
            if extra_due {
                for (key, path) in [
                    (
                        "driving_history",
                        format!("driving-history/vehicles/{provider}"),
                    ),
                    (
                        "diagnostics",
                        format!("diagnostics/vehicles/{provider}/breakdowns"),
                    ),
                    (
                        "software_version",
                        format!("device-info/vehicles/{provider}/software-version"),
                    ),
                    (
                        "ota_updates",
                        format!("mru/vehicles/{provider}/ota-updates"),
                    ),
                ] {
                    match client
                        .auxiliary(&mut account.session, &vehicle, key, &path)
                        .await
                    {
                        Ok(data) => {
                            if key == "driving_history" {
                                for row in driving_days(&data, now_ms()) {
                                    journal.day(ctx, &id, &row);
                                }
                            }
                            let safe = safe_details(key, &data);
                            snapshot.values.insert(key.into(), safe);
                        }
                        Err(e @ (Error::RateLimit(_) | Error::Authentication)) => return Err(e),
                        Err(e) => {
                            snapshot.values.insert(
                                key.into(),
                                json!({"available":false,"reason":e.code(),"received_at":now_ms()}),
                            );
                        }
                    }
                }
            } else if let Some(previous) = records.get(&id) {
                for key in [
                    "driving_history",
                    "diagnostics",
                    "software_version",
                    "ota_updates",
                ] {
                    if let Some(value) = previous.snapshot.values.get(key) {
                        snapshot.values.insert(key.into(), value.clone());
                    }
                }
            }
            let private = records.get(&id).map_or_else(
                || Arc::new(private::Private::default()),
                |r| Arc::clone(&r.private),
            );
            private.update(
                &payload,
                &account.session,
                &vehicle,
                self.config.location_enabled,
                self.config.commands_enabled,
            );
            let unsupported = records
                .get(&id)
                .map_or_else(BTreeMap::new, |r| r.unsupported.clone());
            snapshot
                .values
                .insert("api_mode".into(), json!(account.session.api_mode));
            // Auxiliary calls have their own receipt timestamps and can outlast freshness.
            snapshot.expire(now_ms(), self.config.max_age_seconds * 1000);
            let record = Record {
                api_mode: account.session.api_mode,
                vehicle,
                snapshot,
                private,
                unsupported,
            };
            self.publish(ctx, &id, &record, account.session.pin.is_some());
            if record.snapshot.quality == "cached_recent"
                && let Some(at) = record.snapshot.vehicle_at
            {
                let get = |k: &str| record.snapshot.values.get(k).and_then(model::number);
                let row = VehicleObservation {
                    at,
                    received_at: record.snapshot.received_at,
                    soc: get("soc"),
                    odometer_km: get("odometer"),
                    charging: record
                        .snapshot
                        .values
                        .get("charging")
                        .and_then(Json::as_bool),
                    consumed_wh: record
                        .snapshot
                        .values
                        .get("driving_info")
                        .and_then(|v| model::number(&v["totalPwrCsp"])),
                    weather: None,
                    grid: None,
                    max_gap_ms: self.config.max_age_seconds * 1000,
                };
                // Counter deltas are bounded and resets become unknown. Their
                // boundaries remain observed/incomplete, never exact metered trips.
                journal.observe(
                    ctx,
                    &id,
                    row,
                    self.config.weather_point.as_deref(),
                    self.config.dedicated_ev_meter.as_deref(),
                );
            }
            records.insert(id, record);
        }

        let seen = account
            .vehicles
            .iter()
            .filter(|v| self.config.vehicles.is_empty() || self.config.vehicles.contains(&v.key))
            .map(|v| ctx.device_id(&v.key))
            .collect::<Vec<_>>();
        for id in ctx.devices() {
            if !seen.contains(&id) {
                ctx.remove_device(&id);
                records.remove(&id);
            }
        }
        Ok(())
    }
    fn publish(&self, ctx: &DriverCtx, id: &DeviceId, r: &Record, pin: bool) {
        let caps = capabilities(
            r.api_mode,
            &r.vehicle,
            &r.snapshot.state,
            self.config.commands_enabled,
            pin,
        )
        .into_iter()
        .map(|mut c| {
            if let Some(reason) = r.unsupported.get(c.key) {
                c.available = false;
                c.reason = reason;
            }
            c
        })
        .collect::<Vec<_>>();
        let mut points = r
            .snapshot
            .values
            .keys()
            .map(|key| {
                let mut s = spec(key, r.snapshot.values.get(key), false);
                if let Some(unit) = r
                    .snapshot
                    .values
                    .get(&format!("{key}_unit"))
                    .and_then(Json::as_str)
                {
                    s.unit = Unit::parse(unit);
                }
                s
            })
            .collect::<Vec<_>>();
        for c in &caps {
            if c.available {
                points.push(spec(c.key, None, true));
            }
        }
        points.push(spec("capabilities", None, false));
        points.push(spec("command_status", None, false));
        points.push(spec("error", None, false));
        points.push(spec(
            "max_age_seconds",
            Some(&json!(self.config.max_age_seconds)),
            false,
        ));
        points.push(spec("location_enabled", Some(&json!(true)), false));
        ctx.upsert_device(Device {
            id: id.clone(),
            instance: ctx.instance().clone(),
            native_name: r.vehicle.name.as_str().into(),
            manufacturer: Some("Hyundai".into()),
            model: Some(r.vehicle.model.as_str().into()),
            description: Some("Bluelink / myHyundai Europe — cache cloud".into()),
            native_room: None,
            members: vec![],
            points,
        });
        for (key, value) in &r.snapshot.values {
            ctx.set_state(id, key, Value::from_json(value));
        }
        ctx.set_state(id, "capabilities", Value::from_json(&json!(caps)));
        ctx.set_state(
            id,
            "location_enabled",
            Value::Bool(self.config.location_enabled),
        );
        ctx.set_state(id, "error", Value::from(""));
        ctx.set_state(
            id,
            "max_age_seconds",
            Value::from_json(&json!(self.config.max_age_seconds)),
        );
        ctx.set_availability(id, true);
        ctx.provide_private(id, r.private.clone());
    }
    #[allow(clippy::too_many_lines)] // One guarded, deadline-checked order; no retry after POST.
    async fn command(
        &self,
        ctx: &DriverCtx,
        client: &mut Client,
        account: Option<&mut account::Account>,
        records: &mut BTreeMap<DeviceId, Record>,
        cmd: &CommandRequest,
    ) -> Result<(), Error> {
        if cmd.origin != Origin::Ui || cmd.abandoned() || !self.config.commands_enabled {
            return Err(Error::Unsupported);
        }
        let record = records.get_mut(&cmd.device.id).ok_or(Error::Unsupported)?;
        if record.snapshot.quality != "cached_recent"
            || record
                .snapshot
                .vehicle_at
                .is_none_or(|at| now_ms().saturating_sub(at) > self.config.max_age_seconds * 1000)
        {
            return Err(Error::Expired);
        }
        let account = account.ok_or(Error::Authentication)?;
        // No token rotation or retry hidden inside a vehicle order. Renew passively.
        if account.phase != account::Phase::Ready || account.session.expires <= now_ms() / 1000 + 30
        {
            return Err(Error::Expired);
        }
        let cap = capabilities(
            account.session.api_mode,
            &record.vehicle,
            &record.snapshot.state,
            true,
            account.session.pin.is_some(),
        )
        .into_iter()
        .find(|c| c.key == cmd.key.as_ref())
        .ok_or(Error::Unsupported)?;
        if record.unsupported.contains_key(cap.key) {
            return Err(Error::Unsupported);
        }
        let order = commands::build(&cap, &cmd.value.to_json(), &record.snapshot.state)?;
        // Durable cooldown consumed before a possibly ambiguous POST; restart cannot
        // turn a previous timeout into another wakeup/order.
        let marker = format!("last_command_{}", record.vehicle.key);
        let last = ctx
            .secret(&marker)
            .and_then(|s| s.parse::<u64>().ok())
            .unwrap_or(0);
        let cooldown = if cap.key == "force_refresh" {
            900_000
        } else {
            30_000
        };
        if now_ms().saturating_sub(last) < cooldown {
            return Err(Error::Cooldown(cooldown / 1000));
        }
        ctx.store_secret(&marker, &now_ms().to_string())
            .await
            .map_err(|_| Error::Storage)?;
        if cap.key == "force_refresh"
            && record
                .snapshot
                .values
                .get("battery_12v")
                .and_then(model::number)
                .is_none_or(|n| n < 40.0)
        {
            return Err(Error::Unsupported);
        }
        let provider = model::validate_id(record.vehicle.id.as_deref().ok_or(Error::Protocol)?)?;
        if cmd.abandoned() {
            return Err(Error::Expired);
        }
        // PIN verification can take time; enforce deadline again before POST below.
        let headers = client
            .prepare_control(&mut account.session, order.pin, record.vehicle.ccs2)
            .await?;
        if cmd.abandoned()
            || cmd
                .deadline
                .saturating_duration_since(std::time::Instant::now())
                < Duration::from_secs(3)
        {
            return Err(Error::Expired);
        }
        let result = if account.session.api_mode == auth::ApiMode::Europe {
            let mut order = legacy::order(
                &record.vehicle,
                &cap,
                &cmd.value.to_json(),
                &record.snapshot.state,
                &account.session.device,
            )?;
            if cap.key == "set_navigation" {
                order.body = record.private.take_navigation(
                    order.body["navigation_token"]
                        .as_str()
                        .ok_or(Error::Protocol)?,
                    &account.session.device,
                )?;
            }
            client
                .legacy_request(order.method, &order.path, &headers, &order.body)
                .await
        } else {
            client
                .control_post(
                    &format!("{}/{provider}/{}", order.prefix, order.endpoint),
                    &headers,
                    &order.body,
                )
                .await
        };
        match result {
            Ok(ref reply) => {
                record.private.acknowledge(reply);
                ctx.set_state(&cmd.device.id,"command_status",Value::from_json(&json!({"action":cap.key,"at":now_ms(),"state":"cloud_acknowledged","physical_result":"not_confirmed"})));
                ctx.diagnostic("hyundai_command_ack", cap.key, 0);
                Ok(())
            }
            Err(error) => {
                if error == Error::Authentication {
                    account.session.control = None;
                    account.session.control_expires = 0;
                }
                if error == Error::Unsupported {
                    record
                        .unsupported
                        .insert(cap.key.into(), "provider_rejected_capability");
                    self.publish(ctx, &cmd.device.id, record, account.session.pin.is_some());
                }
                ctx.set_state(&cmd.device.id,"command_status",Value::from_json(&json!({"action":cap.key,"at":now_ms(),"state":"failed_or_unknown","reason":error.code()})));
                Err(error)
            }
        }
    }
}
fn capabilities(
    mode: auth::ApiMode,
    vehicle: &model::Vehicle,
    state: &Json,
    write: bool,
    pin: bool,
) -> Vec<commands::Capability> {
    if mode == auth::ApiMode::Europe {
        legacy::capabilities(vehicle, state, write, pin)
    } else {
        commands::capabilities(vehicle, state, write, pin)
    }
}
fn spec(key: &str, _value: Option<&Json>, write: bool) -> PointSpec {
    let unit = match key {
        "soc" | "soh" | "battery_12v" => Some(Unit::Percent),
        "odometer" | "ev_range_km" => Unit::parse("km"),
        "charging_power" => Unit::parse("kW"),
        "air_temperature_c" | "outside_temperature_c" => Some(Unit::Celsius),
        _ if model::aliases().iter().any(|a| a.key == key) => model::aliases()
            .iter()
            .find(|a| a.key == key)
            .and_then(|a| Unit::parse(&a.unit)),
        _ => model::fields()
            .iter()
            .find(|f| f.key == key)
            .and_then(|f| Unit::parse(&f.unit)),
    };
    PointSpec {
        key: key.into(),
        label: if model::aliases().iter().any(|a| a.key == key) {
            moli_i18n::tr(&format!("vehicle.reading.{key}")).into()
        } else {
            key.into()
        },
        semantic: if write {
            Semantic::Control
        } else {
            Semantic::infer(key, unit.as_ref())
        },
        kind: if write {
            Kind::Text
        } else {
            match model::point_kind(key) {
                model::PointKind::Binary => Kind::Binary,
                model::PointKind::Numeric => Kind::Numeric {
                    min: None,
                    max: None,
                    step: None,
                },
                model::PointKind::Text => Kind::Text,
            }
        },
        unit,
        access: Access {
            read: !write,
            write,
        },
    }
}
fn driving_days(data: &Json, now: u64) -> Vec<DrivingDay> {
    data["drivingInfoDetail"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|d| {
            let s = d["drivingDate"].as_str()?;
            if s.len() != 8 || !s.bytes().all(|b| b.is_ascii_digit()) {
                return None;
            }
            let day_key = format!("{}-{}-{}", &s[..4], &s[4..6], &s[6..]);
            day_key.parse::<jiff::civil::Date>().ok()?;
            let n = |key: &str| model::number(&d[key]).filter(|v| *v >= 0.0);
            Some(DrivingDay {
                day: day_key,
                distance_km: n("calculativeOdo"),
                consumed_wh: n("totalPwrCsp"),
                motor_wh: n("motorPwrCsp"),
                climate_wh: n("climatePwrCsp"),
                electronics_wh: n("eDPwrCsp"),
                battery_management_wh: n("batteryMgPwrCsp"),
                regenerated_wh: n("regenPwr"),
                source: "Hyundai EU driving-history / Wh".into(),
                received_at: now,
            })
        })
        .collect()
}
fn safe_details(key: &str, data: &Json) -> Json {
    // These endpoints can contain VIN, locations or arbitrary strings. Select
    // typed numeric statistics / fixed status codes only, never retain raw JSON.
    if key == "driving_history" {
        let summary = data["drivingInfo"]
            .as_array()
            .and_then(|a| a.iter().find(|v| v["drivingPeriod"] == 0));
        let per_km = summary
            .and_then(|v| model::number(&v["totalPwrCsp"]).zip(model::number(&v["calculativeOdo"])))
            .filter(|(wh, km)| *wh >= 0.0 && *km > 0.0)
            .map(|(wh, km)| wh / km);
        return json!({"days":driving_days(data,now_ms()),"consumption_30d_wh_km":per_km,"received_at":now_ms(),"source":"Hyundai EU driving-history"});
    }
    let mut out = json!({"received_at":now_ms(),"source":key});
    if key == "driving_info" {
        let d = data.get("drivingInfo").unwrap_or(data);
        let d = d.as_array().and_then(|a| a.first()).unwrap_or(d);
        for k in [
            "totalDistance",
            "totalPwrCsp",
            "regenPwr",
            "motorPwrCsp",
            "climatePwrCsp",
            "eDPwrCsp",
            "batteryMgPwrCsp",
        ] {
            out[k] = model::number(&d[k])
                .filter(|v| *v >= 0.0)
                .map_or(Json::Null, |v| json!(v));
        }
    } else {
        out["available"] = json!(!data.is_null());
        if key == "diagnostics"
            && let Some(items) = data["breakdown"].as_array()
        {
            out["dtcCount"] = json!(items.len());
            out["breakdowns"] = json!(items.iter().take(100).map(technical).collect::<Vec<_>>());
        }
        if key == "ota_updates"
            && let Some(items) = data["otaUpdateList"].as_array()
        {
            out["updateAvailable"] = json!(!items.is_empty());
            out["updates"] = json!(items.iter().take(100).map(technical).collect::<Vec<_>>());
        }

        for k in [
            "breakdownCount",
            "dtcCount",
            "updateAvailable",
            "updateState",
            "softwareVersion",
            "version",
        ] {
            if let Some(v) = data.get(k).filter(|v| {
                v.is_boolean()
                    || v.is_number()
                    || v.as_str().is_some_and(|s| {
                        s.len() <= 80
                            && s.bytes()
                                .all(|b| b.is_ascii_alphanumeric() || b"._- ".contains(&b))
                    })
            }) {
                out[k] = v.clone();
            }
        }
    }
    out
}

fn technical(data: &Json) -> Json {
    match data {
        Json::Array(a) => json!(a.iter().take(100).map(technical).collect::<Vec<_>>()),
        Json::Object(map) => {
            let mut out = json!({});
            for key in [
                "ecuName",
                "ecuIdx",
                "dtcList",
                "code",
                "dtcCode",
                "dtcName",
                "description",
                "status",
                "softwareVersion",
                "currentVersion",
                "updateVersion",
                "updateType",
                "updateState",
                "installStatus",
                "needUpdate",
            ] {
                if let Some(value) = map.get(key) {
                    out[key] = technical(value);
                }
            }
            out
        }
        Json::String(s)
            if s.len() <= 512
                && !(s.len() == 17 && s.bytes().all(|b| b.is_ascii_alphanumeric())) =>
        {
            json!(s)
        }
        Json::Number(_) | Json::Bool(_) => data.clone(),
        _ => Json::Null,
    }
}
