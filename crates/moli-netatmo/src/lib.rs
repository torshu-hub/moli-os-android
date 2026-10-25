//! Netatmo Energy observations. Portable Rust; dormant unless configured.
//! OAuth is a vault operation, never an actuator operation. All points read-only.
mod client;
mod model;
#[cfg(test)]
mod tests;

use std::{collections::BTreeMap, sync::Arc, time::Duration};

use moli_core::{Access, Device, DeviceId, Kind, PointSpec, Semantic, Unit, Value, now_ms};
use moli_i18n::tr;
use moli_runtime::{BoxFuture, Driver, DriverCtx};
use serde::Deserialize;
use tokio::time::Instant;

use client::{Failure, Https, TOKEN_SECRET, Tokens, Transport};
use model::Observation;

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    pub home_id: String,
    /// Official app identifier; secret belongs in the instance's vault.
    #[serde(default)]
    pub client_id: Option<String>,
    #[serde(default = "poll_default")]
    pub poll_seconds: u64,
    #[serde(default = "age_default")]
    pub max_age_seconds: u64,
}
fn poll_default() -> u64 {
    120
}
fn age_default() -> u64 {
    300
}

pub struct Netatmo {
    config: Config,
    transport: Arc<dyn Transport>,
}
impl std::fmt::Debug for Netatmo {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Netatmo").finish_non_exhaustive()
    }
}
impl Netatmo {
    pub fn new(config: Config) -> anyhow::Result<Self> {
        anyhow::ensure!(model::valid_id(&config.home_id), "invalid Netatmo home id");
        anyhow::ensure!(
            (120..=3600).contains(&config.poll_seconds),
            "Netatmo poll must be 120..3600 seconds"
        );
        anyhow::ensure!(
            (config.poll_seconds..=3600).contains(&config.max_age_seconds),
            "Netatmo max age must be poll..3600 seconds"
        );
        anyhow::ensure!(
            config.client_id.as_deref().is_none_or(model::valid_id),
            "invalid Netatmo client id"
        );
        Ok(Self {
            config,
            transport: Arc::new(Https),
        })
    }
    async fn collect(
        &self,
        ctx: &DriverCtx,
        force_refresh: bool,
        pending: &mut Option<Tokens>,
    ) -> Result<Vec<Observation>, Failure> {
        persist_pending(ctx, pending).await?;
        let raw = ctx.secret(TOKEN_SECRET).ok_or(Failure::Credentials)?;
        let mut tokens = Tokens::parse(&raw)?;
        let now = now_ms() / 1000;
        if force_refresh || tokens.expires_at <= now.saturating_add(60) {
            let client_id = self
                .config
                .client_id
                .as_deref()
                .ok_or(Failure::Credentials)?;
            let secret = ctx.secret("client_secret").ok_or(Failure::Credentials)?;
            if !ctx.can_store_secrets() {
                return Err(Failure::Storage);
            }
            let rotated =
                client::refresh(self.transport.as_ref(), &tokens, client_id, &secret, now).await?;
            // Retain an unsaved rotation only to retry persistence. Never read
            // with it before the whole pair has reached the encrypted vault.
            *pending = Some(rotated);
            persist_pending(ctx, pending).await?;
            tokens = Tokens::parse(&ctx.secret(TOKEN_SECRET).ok_or(Failure::Storage)?)?;
        }
        let topology = client::read(
            self.transport.as_ref(),
            &tokens.access_token,
            &self.config.home_id,
            true,
        )
        .await?;
        let status = client::read(
            self.transport.as_ref(),
            &tokens.access_token,
            &self.config.home_id,
            false,
        )
        .await?;
        model::parse(
            &topology,
            &status,
            &self.config.home_id,
            now_ms(),
            self.config.max_age_seconds * 1000,
        )
    }
}
impl Driver for Netatmo {
    fn kind(&self) -> &'static str {
        "netatmo"
    }
    fn run<'a>(&'a self, ctx: &'a mut DriverCtx) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(async move {
            let mut records = BTreeMap::new();
            // Cache restoration must never briefly announce an old demand as current.
            for id in ctx.devices() {
                invalidate(ctx, &id, "unconfirmed", "");
            }
            let mut next = Instant::now();
            let mut force_refresh = false;
            let mut pending = None;
            let mut tick = tokio::time::interval(Duration::from_secs(5));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                tokio::select! {
                    command = ctx.next_command() => {
                        let Some(command) = command else { return Ok(()); };
                        command.reply(Err(tr!("pilotes.netatmo.read_only")));
                    }
                    _ = tick.tick() => {}
                }
                expire(ctx, &mut records, self.config.max_age_seconds * 1000);
                if Instant::now() < next {
                    continue;
                }
                let result = tokio::select! {
                    () = ctx.cancelled() => return Ok(()),
                    result = self.collect(ctx, force_refresh, &mut pending) => result,
                };
                let delay = match result {
                    Ok(observations) => {
                        let seen: std::collections::BTreeSet<_> = observations
                            .iter()
                            .map(|o| ctx.device_id(&o.native))
                            .collect();
                        for id in ctx.devices().into_iter().filter(|id| !seen.contains(id)) {
                            invalidate(ctx, &id, "missing", "");
                            records.remove(&id);
                        }
                        for observation in observations {
                            let id = publish(ctx, &observation, self.config.max_age_seconds);
                            records.insert(
                                id,
                                (observation.server_at, Instant::now(), observation.current()),
                            );
                        }
                        force_refresh = false;
                        ctx.ready();
                        self.config.poll_seconds
                    }
                    Err(failure) => {
                        for id in ctx.devices() {
                            invalidate(ctx, &id, "error", failure.code());
                        }
                        records.clear();
                        ctx.wait_for(tr!(
                            "pilotes.netatmo.error",
                            cause = error_text(failure.code())
                        ));
                        force_refresh = failure == Failure::Unauthorized;
                        if force_refresh {
                            self.config.poll_seconds
                        } else {
                            failure.retry_seconds(self.config.poll_seconds)
                        }
                    }
                };
                next = Instant::now() + Duration::from_secs(delay.min(7 * 86400));
            }
        })
    }
}

async fn persist_pending(ctx: &DriverCtx, pending: &mut Option<Tokens>) -> Result<(), Failure> {
    if let Some(tokens) = pending.as_ref() {
        let serialized = serde_json::to_string(tokens).map_err(|_| Failure::Storage)?;
        ctx.store_secret(TOKEN_SECRET, &serialized)
            .await
            .map_err(|_| Failure::Storage)?;
        *pending = None;
    }
    Ok(())
}

type Records = BTreeMap<DeviceId, (Option<u64>, Instant, bool)>;
fn expire(ctx: &DriverCtx, records: &mut Records, max_age: u64) {
    for (id, (at, received, current)) in records {
        let age = at.and_then(|t| now_ms().checked_sub(t));
        if *current
            && (age.is_none_or(|age| age > max_age)
                || received.elapsed() > Duration::from_millis(max_age))
        {
            invalidate(ctx, id, "stale", "");
            *current = false;
        }
    }
}
const META: &[&str] = &[
    "freshness",
    "collected_at",
    "server_at",
    "measurement_at",
    "error",
    "max_age_seconds",
];
fn invalidate(ctx: &DriverCtx, id: &DeviceId, quality: &str, error: &str) {
    // Fixed union: clears formerly present fields too (omission is never a hold).
    for key in [
        "temperature",
        "setpoint_temperature",
        "mode",
        "heating_power_request",
        "boiler_status",
        "battery_state",
        "home_mode",
        "schedule",
    ] {
        ctx.set_state(id, key, Value::Null);
    }
    ctx.set_state(id, "declared_demand", Value::from("unknown"));
    ctx.set_state(id, "freshness", Value::from(quality));
    ctx.set_state(id, "error", Value::from(error_text(error).as_str()));
    ctx.set_availability(id, false);
}
fn publish(ctx: &DriverCtx, obs: &Observation, max_age_seconds: u64) -> DeviceId {
    let id = ctx.device_id(&obs.native);
    let mut keys: Vec<_> = obs.readings.keys().copied().collect();
    keys.extend(META);
    ctx.upsert_device(Device {
        id: id.clone(),
        instance: ctx.instance().clone(),
        native_name: obs.name.as_str().into(),
        manufacturer: Some("Netatmo".into()),
        model: Some(obs.model.as_str().into()),
        description: Some(tr!("pilotes.netatmo.description").into()),
        native_room: None,
        members: Vec::new(),
        points: keys.into_iter().map(spec).collect(),
    });
    for (key, value) in &obs.readings {
        let value = if obs.current() {
            value.clone()
        } else if *key == "declared_demand" {
            Value::from("unknown")
        } else {
            Value::Null
        };
        ctx.set_state(&id, key, value);
    }
    ctx.set_state(&id, "freshness", Value::from(obs.quality));
    ctx.set_state(&id, "error", Value::from(""));
    ctx.set_state(
        &id,
        "max_age_seconds",
        Value::Int(i64::try_from(max_age_seconds).unwrap_or(i64::MAX)),
    );
    ctx.set_state(
        &id,
        "collected_at",
        Value::Int(i64::try_from(obs.collected_at).unwrap_or(i64::MAX)),
    );
    ctx.set_state(
        &id,
        "server_at",
        obs.server_at.map_or(Value::Null, |v| {
            Value::Int(i64::try_from(v).unwrap_or(i64::MAX))
        }),
    );
    ctx.set_state(&id, "measurement_at", Value::Null);
    ctx.set_availability(&id, obs.current());
    id
}
fn error_text(code: &str) -> String {
    match code {
        "network" => tr!("pilotes.netatmo.network"),
        "oauth" => tr!("pilotes.netatmo.oauth"),
        "rate_limit" => tr!("pilotes.netatmo.rate_limit"),
        "invalid_response" => tr!("pilotes.netatmo.invalid_response"),
        "api" => tr!("pilotes.netatmo.api"),
        "storage" => tr!("pilotes.netatmo.storage"),
        _ => String::new(),
    }
}
fn spec(key: &str) -> PointSpec {
    let (kind, unit) = match key {
        "temperature" | "setpoint_temperature" => (numeric(), Some(Unit::Celsius)),
        "heating_power_request" => (numeric(), Some(Unit::Percent)),
        "collected_at" | "server_at" | "measurement_at" | "max_age_seconds" => (numeric(), None),
        "boiler_status" => (Kind::Binary, None),
        _ => (Kind::Text, None),
    };
    PointSpec {
        key: key.into(),
        label: label(key).into(),
        semantic: Semantic::infer(key, unit.as_ref()),
        kind,
        unit,
        access: Access {
            read: true,
            write: false,
        },
    }
}
fn numeric() -> Kind {
    Kind::Numeric {
        min: None,
        max: None,
        step: None,
    }
}
fn label(key: &str) -> String {
    // Literal translation keys remain discoverable by the existing i18n checker.
    match key {
        "temperature" => tr!("pilotes.netatmo.temperature"),
        "setpoint_temperature" => tr!("pilotes.netatmo.setpoint_temperature"),
        "mode" => tr!("pilotes.netatmo.mode"),
        "heating_power_request" => tr!("pilotes.netatmo.heating_power_request"),
        "boiler_status" => tr!("pilotes.netatmo.boiler_status"),
        "declared_demand" => tr!("pilotes.netatmo.declared_demand"),
        "battery_state" => tr!("pilotes.netatmo.battery_state"),
        "home_mode" => tr!("pilotes.netatmo.home_mode"),
        "schedule" => tr!("pilotes.netatmo.schedule"),
        "freshness" => tr!("pilotes.netatmo.freshness"),
        "collected_at" => tr!("pilotes.netatmo.collected_at"),
        "server_at" => tr!("pilotes.netatmo.server_at"),
        "measurement_at" => tr!("pilotes.netatmo.measurement_at"),
        "max_age_seconds" => tr!("pilotes.netatmo.max_age_seconds"),
        _ => tr!("pilotes.netatmo.error_label"),
    }
}
