//! Netatmo Energy observations. Portable Rust; dormant unless configured.
//! OAuth is a vault operation, never an actuator operation. All points read-only.
mod client;
mod model;
pub mod oauth;
mod polling;
#[cfg(test)]
mod tests;

use std::{collections::BTreeMap, sync::Arc, time::Duration};

use moli_core::{Access, Device, DeviceId, Kind, PointSpec, Semantic, Unit, Value, now_ms};
use moli_i18n::tr;
use moli_runtime::{BoxFuture, Driver, DriverCtx, ReadEvent, ReadOutcome, ReadStage, ReadSummary};
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
    broker: Option<Arc<dyn oauth::Broker>>,
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
            broker: None,
        })
    }
    /// Bind an approved renewal adapter to this application's existing driver.
    /// No endpoint or secret is accepted from device data or a callback.
    ///
    /// # Errors
    /// Rejects an unconfigured broker or a different application registration.
    pub fn with_broker(mut self, broker: Arc<dyn oauth::Broker>) -> anyhow::Result<Self> {
        let policy = broker
            .policy()
            .ok_or_else(|| anyhow::anyhow!("OAuth setup required"))?;
        anyhow::ensure!(
            self.config.client_id.as_deref() == Some(policy.client_id()),
            "OAuth application mismatch"
        );
        self.broker = Some(broker);
        Ok(self)
    }
    async fn broker_refresh(
        &self,
        ctx: &DriverCtx,
        tokens: &Tokens,
        now: u64,
    ) -> Result<Option<Tokens>, Failure> {
        let Some(broker) = &self.broker else {
            return Ok(None);
        };
        if !ctx.can_store_secrets() {
            return Err(Failure::Storage);
        }
        let previous = oauth::Grant {
            tokens: tokens.clone(),
        };
        let next = broker
            .renew(ctx.instance().as_str(), &previous, now.saturating_mul(1000))
            .await
            .map_err(|error| match error {
                oauth::Error::Storage => Failure::Storage,
                oauth::Error::Network => Failure::Network,
                oauth::Error::RateLimited(seconds) => Failure::RateLimited(seconds),
                _ => Failure::Credentials,
            })?;
        if next.tokens.expires_at <= now || next.tokens.expires_at > now.saturating_add(86400) {
            return Err(Failure::Credentials);
        }
        Ok(Some(next.tokens))
    }
    // Netatmo explicitly forbids the client secret on mobile. Keep the
    // server path by default; the separate personal feature is experimental.
    #[cfg(feature = "personal-oauth")]
    async fn personal_refresh(
        &self,
        ctx: &DriverCtx,
        tokens: &Tokens,
        now: u64,
    ) -> Result<Option<Tokens>, Failure> {
        let Some(raw) = ctx.secret(oauth::personal::APPLICATION_SECRET) else {
            return Ok(None);
        };
        if !ctx.can_store_secrets() {
            return Err(Failure::Storage);
        }
        let app = oauth::personal::Application::parse(&raw).map_err(|_| Failure::Credentials)?;
        if self
            .config
            .client_id
            .as_deref()
            .is_some_and(|id| id != app.client_id())
        {
            return Err(Failure::Credentials);
        }
        let previous = oauth::Grant {
            tokens: tokens.clone(),
        };
        let marker =
            oauth::personal::rotation_fingerprint(&previous).map_err(|_| Failure::Credentials)?;
        ctx.store_secret(oauth::personal::ROTATION_SECRET, &marker)
            .await
            .map_err(|_| Failure::Storage)?;
        match app
            .renew_with(self.transport.as_ref(), &previous, now.saturating_mul(1000))
            .await
        {
            Ok(grant) => Ok(Some(grant.tokens)),
            Err(oauth::Error::RateLimited(seconds)) => {
                ctx.forget_secret(oauth::personal::ROTATION_SECRET)
                    .await
                    .map_err(|_| Failure::Storage)?;
                Err(Failure::RateLimited(seconds))
            }
            Err(oauth::Error::Ambiguous) => Err(Failure::Ambiguous),
            Err(_) => Err(Failure::Credentials),
        }
    }
    #[cfg(not(target_os = "android"))]
    async fn refresh_tokens(
        &self,
        ctx: &DriverCtx,
        tokens: &Tokens,
        now: u64,
    ) -> Result<Tokens, Failure> {
        if let Some(tokens) = self.broker_refresh(ctx, tokens, now).await? {
            return Ok(tokens);
        }
        #[cfg(feature = "personal-oauth")]
        if let Some(tokens) = self.personal_refresh(ctx, tokens, now).await? {
            return Ok(tokens);
        }
        let client_id = self
            .config
            .client_id
            .as_deref()
            .ok_or(Failure::Credentials)?;
        let secret = ctx.secret("client_secret").ok_or(Failure::Credentials)?;
        if !ctx.can_store_secrets() {
            return Err(Failure::Storage);
        }
        client::refresh(self.transport.as_ref(), tokens, client_id, &secret, now).await
    }
    #[cfg(target_os = "android")]
    async fn refresh_tokens(
        &self,
        ctx: &DriverCtx,
        tokens: &Tokens,
        now: u64,
    ) -> Result<Tokens, Failure> {
        #[cfg(feature = "personal-oauth")]
        if let Some(tokens) = self.personal_refresh(ctx, tokens, now).await? {
            return Ok(tokens);
        }
        self.broker_refresh(ctx, tokens, now)
            .await?
            .ok_or(Failure::Credentials)
    }
    async fn collect(
        &self,
        ctx: &DriverCtx,
        force_refresh: bool,
        pending: &mut Option<Tokens>,
    ) -> Result<Vec<Observation>, Failure> {
        ctx.observe_read(
            "netatmo",
            ReadEvent::Attempt {
                credential_present: ctx.secret(TOKEN_SECRET).is_some(),
                token_expired: false,
            },
        );
        let mut tokens = observed(ctx, ReadStage::Authorization, async {
            persist_pending(ctx, pending).await?;
            let raw = ctx.secret(TOKEN_SECRET).ok_or(Failure::Credentials)?;
            let tokens = Tokens::parse(&raw)?;
            #[cfg(feature = "personal-oauth")]
            if ctx.secret(oauth::personal::APPLICATION_SECRET).is_some() {
                let fingerprint = oauth::personal::rotation_fingerprint(&oauth::Grant {
                    tokens: tokens.clone(),
                })
                .map_err(|_| Failure::Credentials)?;
                if let Some(previous) = ctx.secret(oauth::personal::ROTATION_SECRET) {
                    if previous == fingerprint {
                        return Err(Failure::Ambiguous);
                    }
                    ctx.forget_secret(oauth::personal::ROTATION_SECRET)
                        .await
                        .map_err(|_| Failure::Storage)?;
                }
            }
            Ok(tokens)
        })
        .await?;
        let now = now_ms() / 1000;
        ctx.observe_read(
            "netatmo",
            ReadEvent::Attempt {
                credential_present: true,
                token_expired: tokens.expires_at <= now,
            },
        );
        if force_refresh || tokens.expires_at <= now.saturating_add(60) {
            tokens = observed(ctx, ReadStage::Renewal, async {
                let rotated = self.refresh_tokens(ctx, &tokens, now).await?;
                // Retain an unsaved rotation only to retry persistence. Never read
                // with it before the whole pair has reached the encrypted vault.
                *pending = Some(rotated);
                persist_pending(ctx, pending).await?;
                Tokens::parse(&ctx.secret(TOKEN_SECRET).ok_or(Failure::Storage)?)
            })
            .await?;
            ctx.observe_read("netatmo", ReadEvent::TokenFresh);
        }
        let topology = observed(
            ctx,
            ReadStage::Homesdata,
            client::read(
                self.transport.as_ref(),
                &tokens.access_token,
                &self.config.home_id,
                true,
            ),
        )
        .await?;
        let status = observed(
            ctx,
            ReadStage::Homestatus,
            client::read(
                self.transport.as_ref(),
                &tokens.access_token,
                &self.config.home_id,
                false,
            ),
        )
        .await?;
        observed(
            ctx,
            ReadStage::Parsing,
            std::future::ready(model::parse(
                &topology,
                &status,
                &self.config.home_id,
                now_ms(),
                self.config.max_age_seconds * 1000,
            )),
        )
        .await
    }
}
impl Driver for Netatmo {
    fn kind(&self) -> &'static str {
        "netatmo"
    }
    // Keep the single read/select/backoff loop together; no parallel refresher.
    #[allow(clippy::too_many_lines)]
    fn run<'a>(&'a self, ctx: &'a mut DriverCtx) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(async move {
            ctx.observe_read(
                "netatmo",
                ReadEvent::Configuration {
                    enabled: true,
                    credential_present: ctx.secret(TOKEN_SECRET).is_some(),
                    target_selected: !self.config.home_id.is_empty(),
                },
            );
            ctx.observe_read("netatmo", ReadEvent::Started);
            let mut records = BTreeMap::new();
            // Cache restoration must never briefly announce an old demand as current.
            for id in ctx.devices() {
                invalidate(ctx, &id, "unconfirmed", "");
            }
            let refresh = ctx.read_refresh();
            let mut polling = polling::Polling::new(Instant::now(), now_ms());
            let mut force_refresh = false;
            let mut pending = None;
            let mut network_retry = 30;
            let mut tick = tokio::time::interval(Duration::from_secs(5));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                let manual = tokio::select! {
                    ()=refresh.notified()=>refresh.take(),
                    command = ctx.next_command() => {
                        let Some(command) = command else { return Ok(()); };
                        command.reply(Err(tr!("pilotes.netatmo.read_only")));
                        continue;
                    }
                    () = tokio::time::sleep_until(polling.next) => false,
                    _ = tick.tick() => false
                };
                expire(ctx, &mut records, self.config.max_age_seconds * 1000);
                let start = Instant::now();
                let start_wall = now_ms();
                let due = if manual {
                    Some((moli_runtime::ReadTrigger::Manual, 0))
                } else {
                    polling.due(start, start_wall)
                };
                let Some((trigger, late_ms)) = due else {
                    continue;
                };
                refresh.begin();
                ctx.observe_read(
                    "netatmo",
                    ReadEvent::Cycle {
                        period_seconds: self.config.poll_seconds,
                        trigger,
                        late_ms,
                    },
                );
                let result = tokio::select! {
                    () = ctx.cancelled() => return Ok(()),
                    result = tokio::time::timeout(Duration::from_secs(60), self.collect(ctx, force_refresh, &mut pending)) => result.unwrap_or(Err(Failure::CycleTimeout)),
                };
                let outcome = result
                    .as_ref()
                    .map_or_else(|failure| failure_outcome(*failure), |_| ReadOutcome::Ok);
                let delay = match result {
                    Ok(observations) => {
                        publish_observations(
                            ctx,
                            &mut records,
                            observations,
                            self.config.max_age_seconds,
                        );
                        force_refresh = false;
                        network_retry = 30;
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
                        } else if failure.network() {
                            // Read-only transient recovery, independently of the
                            // normal configured period. Back off, never burst.
                            let delay = network_retry.min(self.config.poll_seconds);
                            network_retry = (network_retry * 2).min(self.config.poll_seconds);
                            delay
                        } else {
                            failure.retry_seconds(self.config.poll_seconds)
                        }
                    }
                };
                let delay = delay.min(7 * 86400);
                polling.schedule(start, start_wall, Instant::now(), now_ms(), delay, outcome);
                let minimum = start + Duration::from_secs(60);
                refresh.complete(if outcome == ReadOutcome::Ok {
                    minimum
                } else {
                    minimum.max(polling.next)
                });
                ctx.observe_read(
                    "netatmo",
                    ReadEvent::Scheduled {
                        delay_seconds: delay,
                        next_due_at: polling.next_wall,
                        reason: outcome,
                        duration_ms: u64::try_from(start.elapsed().as_millis()).unwrap_or(u64::MAX),
                    },
                );
            }
        })
    }
}

fn publish_observations(
    ctx: &DriverCtx,
    records: &mut Records,
    observations: Vec<Observation>,
    max_age_seconds: u64,
) {
    ctx.observe_read(
        "netatmo",
        ReadEvent::Stage(ReadStage::Publication, ReadOutcome::Pending),
    );
    let summary = ReadSummary {
        devices: observations.len() as u64,
        fresh: observations.iter().filter(|o| o.current()).count() as u64,
        temperatures: reading_count(&observations, "temperature"),
        setpoints: reading_count(&observations, "setpoint_temperature"),
        modes: reading_count(&observations, "mode"),
        demand_present: reading_count(&observations, "boiler_status"),
        demand_unknown: observations
            .iter()
            .filter(|o| {
                matches!(o.model.as_str(), "NATherm1" | "OTM")
                    && o.readings
                        .get("boiler_status")
                        .is_none_or(|v| *v == Value::Null)
            })
            .count() as u64,
    };
    let seen: std::collections::BTreeSet<_> = observations
        .iter()
        .map(|o| ctx.device_id(&o.native))
        .collect();
    for id in ctx.devices().into_iter().filter(|id| !seen.contains(id)) {
        invalidate(ctx, &id, "missing", "");
        records.remove(&id);
    }
    for observation in observations {
        let id = publish(ctx, &observation, max_age_seconds);
        records.insert(
            id,
            (observation.server_at, Instant::now(), observation.current()),
        );
    }
    ctx.observe_read("netatmo", ReadEvent::Published(summary));
}

fn reading_count(observations: &[Observation], key: &str) -> u64 {
    observations
        .iter()
        .filter(|o| o.readings.get(key).is_some_and(|v| *v != Value::Null))
        .count() as u64
}
async fn observed<T>(
    ctx: &DriverCtx,
    stage: ReadStage,
    future: impl std::future::Future<Output = Result<T, Failure>>,
) -> Result<T, Failure> {
    ctx.observe_read("netatmo", ReadEvent::Stage(stage, ReadOutcome::Pending));
    let start = Instant::now();
    // A transport/broker/storage adapter must not leave the pipeline pending.
    // Ambiguous OAuth rotation remains protected by its durable marker; no
    // timed-out renewal is silently replayed with an old pair.
    let result = tokio::time::timeout(Duration::from_secs(15), future)
        .await
        .unwrap_or(Err(Failure::Timeout));
    ctx.observe_read(
        "netatmo",
        ReadEvent::Latency(
            stage,
            u64::try_from(start.elapsed().as_millis()).unwrap_or(u64::MAX),
        ),
    );
    let outcome = result
        .as_ref()
        .map_or_else(|failure| failure_outcome(*failure), |_| ReadOutcome::Ok);
    ctx.observe_read("netatmo", ReadEvent::Stage(stage, outcome));
    result
}
fn failure_outcome(failure: Failure) -> ReadOutcome {
    match failure {
        Failure::Network => ReadOutcome::Transport,
        Failure::Timeout => ReadOutcome::Timeout,
        Failure::Dns => ReadOutcome::Dns,
        Failure::Connect => ReadOutcome::Connect,
        Failure::Tls => ReadOutcome::Tls,
        Failure::Headers => ReadOutcome::Headers,
        Failure::Body => ReadOutcome::Body,
        Failure::CycleTimeout => ReadOutcome::CycleTimeout,
        Failure::Unauthorized => ReadOutcome::Authorization,
        Failure::Credentials => ReadOutcome::Credentials,
        Failure::Ambiguous => ReadOutcome::Ambiguous,
        Failure::RateLimited(_) => ReadOutcome::RateLimit,
        Failure::Storage => ReadOutcome::Storage,
        Failure::InvalidResponse => ReadOutcome::InvalidResponse,
        Failure::Api => ReadOutcome::Provider,
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
