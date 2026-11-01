//! Portable Netatmo Energy. Ordinary Hub points and guarded commands; no household automation.
mod client;
mod commands;
mod model;
pub mod oauth;
#[cfg(test)]
mod tests;
use client::{Failure, Https, Transport};
use model::Observation;
use moli_core::{Access, Device, DeviceId, Kind, PointSpec, Semantic, Unit, Value, now_ms};
use moli_i18n::tr;
use moli_runtime::{BoxFuture, Driver, DriverCtx};
use serde::Deserialize;
use std::{collections::BTreeMap, sync::Arc, time::Duration};
use tokio::time::Instant;

// Let the cloud apply a successful write before checking it. Commands arriving
// before this read share its deadline; only the subsequent read confirms state.
const POST_COMMAND_DELAY: Duration = Duration::from_secs(2);

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    pub home_id: String,
    #[serde(default)]
    pub client_id: Option<String>,
    #[serde(default = "poll_default")]
    pub poll_seconds: u64,
    #[serde(default = "topology_default")]
    pub topology_seconds: u64,
    #[serde(default = "age_default")]
    pub max_age_seconds: u64,
}
fn poll_default() -> u64 {
    30
}
fn topology_default() -> u64 {
    300
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
            (30..=3600).contains(&config.poll_seconds),
            "Netatmo poll must be 30..3600 seconds"
        );
        anyhow::ensure!(
            (300..=86400).contains(&config.topology_seconds),
            "Netatmo topology must be 300..86400 seconds"
        );
        anyhow::ensure!(
            (config.poll_seconds..=3600).contains(&config.max_age_seconds),
            "Netatmo max age must be poll..3600 seconds"
        );
        Ok(Self {
            config,
            transport: Arc::new(Https),
        })
    }
    async fn collect(
        &self,
        ctx: &DriverCtx,
        account: &mut oauth::account::Account,
        force: bool,
        cache: &mut Option<Topology>,
        started: Instant,
    ) -> Result<(Vec<Observation>, bool), Failure> {
        let grant = account
            .ensure_with(self.transport.as_ref(), &DriverVault(ctx), now_ms(), force)
            .await
            .map_err(auth_failure)?;
        let status = client::read(
            self.transport.as_ref(),
            &grant.tokens.access_token,
            &self.config.home_id,
            false,
        )
        .await?;
        let status_collected_at = now_ms();
        if cache.as_ref().is_none_or(|c| Instant::now() >= c.next) {
            match client::read(
                self.transport.as_ref(),
                &grant.tokens.access_token,
                &self.config.home_id,
                true,
            )
            .await
            {
                Ok(data) => {
                    *cache = Some(Topology {
                        data,
                        next: started + Duration::from_secs(self.config.topology_seconds),
                        collected_at: now_ms(),
                    });
                }
                Err(error @ (Failure::RateLimited(_) | Failure::Unauthorized)) => {
                    return Err(error);
                }
                Err(error) => {
                    if let Some(previous) = cache.as_mut() {
                        previous.next =
                            Instant::now() + Duration::from_secs(self.config.topology_seconds);
                    } else {
                        return Err(error);
                    }
                }
            }
        }
        let topology = cache.as_ref().ok_or(Failure::InvalidResponse)?;
        let mut rows = model::parse(
            &topology.data,
            &status,
            &self.config.home_id,
            now_ms(),
            self.config.max_age_seconds * 1000,
        )?;
        for row in &mut rows {
            row.collected_at = status_collected_at;
            row.readings.insert(
                "topology_collected_at",
                Value::Int(i64::try_from(topology.collected_at).unwrap_or(i64::MAX)),
            );
        }
        Ok((rows, grant.write_allowed()))
    }
}
struct Topology {
    data: serde_json::Value,
    next: Instant,
    collected_at: u64,
}
struct DriverVault<'a>(&'a DriverCtx);
impl oauth::account::Vault for DriverVault<'_> {
    fn get(&self, name: &str) -> oauth::Result<Option<String>> {
        Ok(self.0.secret(name))
    }
    fn put<'a>(&'a self, name: &'a str, value: &'a str) -> BoxFuture<'a, oauth::Result<()>> {
        Box::pin(async move {
            self.0
                .store_secret(name, value)
                .await
                .map_err(|_| oauth::Error::Storage)
        })
    }
    fn remove<'a>(&'a self, name: &'a str) -> BoxFuture<'a, oauth::Result<()>> {
        Box::pin(async move {
            self.0
                .forget_secret(name)
                .await
                .map(|_| ())
                .map_err(|_| oauth::Error::Storage)
        })
    }
}
fn auth_failure(e: oauth::Error) -> Failure {
    match e {
        oauth::Error::Storage => Failure::Storage,
        oauth::Error::Network => Failure::Network,
        oauth::Error::Provider => Failure::Api,
        oauth::Error::ClientRejected => Failure::Application,
        oauth::Error::RateLimited(s) => Failure::RateLimited(s),
        oauth::Error::Ambiguous => Failure::Ambiguous,
        _ => Failure::Credentials,
    }
}
impl Driver for Netatmo {
    fn kind(&self) -> &'static str {
        "netatmo"
    }
    fn run<'a>(&'a self, ctx: &'a mut DriverCtx) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(async move {
            let mut account = oauth::account::Account::default();
            let mut cache = None;
            let mut records = BTreeMap::new();
            let mut next = Instant::now();
            let mut force = false;
            let mut retry = 30;
            let mut quota_until = Instant::now();
            for id in ctx.devices() {
                invalidate(ctx, &id, "unconfirmed", "");
            }
            loop {
                tokio::select! {
                    command=ctx.next_command()=>{
                        let Some(command)=command else {return Ok(());};
                        if Instant::now()<quota_until {command.reply(Err(tr!("pilotes.netatmo.rate_limit")));continue;}
                        let result=commands::execute(self,ctx,&mut account,cache.as_ref(),&command).await;
                        if let Err(Failure::RateLimited(seconds))=result {quota_until=Instant::now()+Duration::from_secs(seconds.max(self.config.poll_seconds));next=quota_until;}
                        let success=result.is_ok();let topology_changed=command.key.as_ref()=="program";
                        command.reply(result.map_err(|_|tr!("pilotes.netatmo.command_error")));
                        if success {
                            if topology_changed && let Some(topology)=cache.as_mut() {
                                topology.next=Instant::now();
                            }
                            next=next.min(Instant::now()+POST_COMMAND_DELAY);
                        }
                    }
                    ()=tokio::time::sleep_until(next)=>{
                        let start=Instant::now();
                        match self.collect(ctx,&mut account,force,&mut cache,start).await {
                            Ok((observations,write))=>{
                                let seen:std::collections::BTreeSet<_>=observations.iter().map(|o|ctx.device_id(&o.native)).collect();
                                for id in ctx.devices().into_iter().filter(|id|!seen.contains(id)){invalidate(ctx,&id,"missing","");records.remove(&id);}
                                for observation in observations {
                                    let id=publish(ctx,&observation,self.config.max_age_seconds,cache.as_ref(),write);
                                    records.insert(id,(observation.server_at,Instant::now(),observation.current()));
                                }
                                force=false;retry=30;ctx.ready();
                                next=(start+Duration::from_secs(self.config.poll_seconds)).max(Instant::now()+Duration::from_millis(1));
                            }
                            Err(failure)=>{
                                for id in ctx.devices(){invalidate(ctx,&id,"error",failure.code());}
                                ctx.wait_for(tr!("pilotes.netatmo.error",cause=error_text(failure.code())));
                                force=failure==Failure::Unauthorized;
                                let delay=if failure.network(){let d=retry;retry=(retry*2).min(300);d}else{failure.retry_seconds(self.config.poll_seconds)};
                                next=Instant::now()+Duration::from_secs(delay.max(self.config.poll_seconds));
                                if matches!(failure,Failure::RateLimited(_)){quota_until=next;}
                            }
                        }
                        expire(ctx,&mut records,self.config.max_age_seconds*1000);
                    }
                }
            }
        })
    }
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
        "target_temperature",
        "setpoint_temperature",
        "mode",
        "heating_power_request",
        "boiler_status",
        "anticipating",
        "open_windows",
        "boiler_valve_comfort_boost",
        "setpoint_start_at",
        "setpoint_end_at",
        "battery_state",
        "home_mode",
        "program",
        "home_name",
        "schedule",
    ] {
        ctx.set_state(id, key, Value::Null);
    }
    ctx.set_state(id, "declared_demand", Value::from("unknown"));
    ctx.set_state(id, "freshness", Value::from(quality));
    ctx.set_state(id, "error", Value::from(error_text(error).as_str()));
    ctx.set_availability(id, false);
}
fn publish(
    ctx: &DriverCtx,
    obs: &Observation,
    max_age_seconds: u64,
    topology: Option<&Topology>,
    write: bool,
) -> DeviceId {
    let id = ctx.device_id(&obs.native);
    ctx.upsert_device(topology.map_or_else(
        || device_base(obs, ctx),
        |topology| commands::definition(ctx, obs, topology, write),
    ));
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
    if let Some(topology) = topology {
        commands::publish_metadata(ctx, &id, obs, topology);
    }
    ctx.set_availability(&id, obs.current());
    id
}
fn device_base(obs: &Observation, ctx: &DriverCtx) -> Device {
    let id = ctx.device_id(&obs.native);
    let mut keys: Vec<_> = obs.readings.keys().copied().collect();
    keys.extend(META);
    Device {
        id: id.clone(),
        instance: ctx.instance().clone(),
        native_name: obs.name.as_str().into(),
        manufacturer: Some("Netatmo".into()),
        model: Some(obs.model.as_str().into()),
        description: Some(tr!("pilotes.netatmo.description").into()),
        native_room: (obs.model == "room").then(|| obs.name.as_str().into()),
        members: Vec::new(),
        points: keys.into_iter().map(spec).collect(),
    }
}
fn error_text(code: &str) -> String {
    match code {
        "network" => tr!("pilotes.netatmo.network"),
        "oauth" => tr!("pilotes.netatmo.oauth"),
        "oauth_application" => tr!("pilotes.netatmo.application"),
        "oauth_ambiguous" => tr!("pilotes.netatmo.error_ambiguous"),
        "rate_limit" => tr!("pilotes.netatmo.rate_limit"),
        "invalid_response" => tr!("pilotes.netatmo.invalid_response"),
        "api" => tr!("pilotes.netatmo.api"),
        "storage" => tr!("pilotes.netatmo.storage"),
        _ => String::new(),
    }
}
fn spec(key: &str) -> PointSpec {
    let (kind, unit) = match key {
        "temperature" | "target_temperature" | "setpoint_temperature" => {
            (numeric(), Some(Unit::Celsius))
        }
        "heating_power_request" => (numeric(), Some(Unit::Percent)),
        "collected_at"
        | "topology_collected_at"
        | "server_at"
        | "measurement_at"
        | "max_age_seconds" => (numeric(), None),
        "boiler_status" | "anticipating" | "open_windows" | "boiler_valve_comfort_boost" => {
            (Kind::Binary, None)
        }
        "firmware_revision" | "rf_strength" | "setpoint_start_at" | "setpoint_end_at" => {
            (numeric(), None)
        }
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
        "anticipating" => tr!("pilotes.netatmo.anticipating"),
        "open_windows" => tr!("pilotes.netatmo.open_windows"),
        "boiler_valve_comfort_boost" => tr!("pilotes.netatmo.boiler_valve_comfort_boost"),
        "setpoint_start_at" => tr!("pilotes.netatmo.setpoint_start_at"),
        "setpoint_end_at" => tr!("pilotes.netatmo.setpoint_end_at"),
        "firmware_revision" => tr!("pilotes.netatmo.firmware_revision"),
        "rf_strength" => tr!("pilotes.netatmo.rf_strength"),
        "temperature" => tr!("pilotes.netatmo.temperature"),
        "setpoint" => tr!("pilotes.netatmo.setpoint"),
        "resume" => tr!("pilotes.netatmo.resume"),
        "program" => tr!("pilotes.netatmo.program"),
        "program_names" => tr!("pilotes.netatmo.program_names"),
        "topology_collected_at" => tr!("pilotes.netatmo.topology_collected_at"),
        "target_temperature" | "setpoint_temperature" => {
            tr!("pilotes.netatmo.setpoint_temperature")
        }
        "mode" => tr!("pilotes.netatmo.mode"),
        "heating_power_request" => tr!("pilotes.netatmo.heating_power_request"),
        "boiler_status" => tr!("pilotes.netatmo.boiler_status"),
        "declared_demand" => tr!("pilotes.netatmo.declared_demand"),
        "battery_state" => tr!("pilotes.netatmo.battery_state"),
        "home_mode" => tr!("pilotes.netatmo.home_mode"),
        "home_name" => tr!("pilotes.netatmo.home_name"),
        "schedule" => tr!("pilotes.netatmo.schedule"),
        "freshness" => tr!("pilotes.netatmo.freshness"),
        "collected_at" => tr!("pilotes.netatmo.collected_at"),
        "server_at" => tr!("pilotes.netatmo.server_at"),
        "measurement_at" => tr!("pilotes.netatmo.measurement_at"),
        "max_age_seconds" => tr!("pilotes.netatmo.max_age_seconds"),
        _ => tr!("pilotes.netatmo.error_label"),
    }
}
