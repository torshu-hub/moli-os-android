//! Opt-in observation adapter and separately confirmed supervised trial.
//! Observation is dry-only; trial uses the official executor and scoped hub
//! authority. No provider transport or parallel scheduler.
mod graph;
#[cfg(test)]
mod tests;
mod trial;
use crate::{
    Options,
    caller::{Caller, Via},
};
use axum::{
    Extension, Json, Router,
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post},
};
use moli_automation::{Actor, Author, Automations, StepStatus};
use moli_core::{DeviceId, DriverStatus, Value};
use moli_runtime::{DeviceView, Hub, Snapshot};
use serde::{Deserialize, Serialize};
use serde_json::{Value as JsonValue, json};
use std::{path::PathBuf, sync::Arc};
use tokio::{io::AsyncWriteExt, sync::Mutex};
const FILE: &str = "netatmo-meross-observation.json";
const MAX_FILE: usize = 4096;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Selection {
    source: String,
    target: String,
    source_name: String,
    target_name: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Stored {
    version: u8,
    revision: u64,
    selection: Option<Selection>,
}
impl Default for Stored {
    fn default() -> Self {
        Self {
            version: 1,
            revision: 0,
            selection: None,
        }
    }
}
struct Storage {
    path: Option<PathBuf>,
    value: Stored,
    valid: bool,
}
#[derive(Clone)]
pub(crate) struct Observer(Arc<Inner>);
struct Inner {
    storage: Mutex<Storage>,
    memory: Mutex<Memory>,
    autos: Option<Automations>,
    epoch: u64,
    revision: std::sync::atomic::AtomicU64,
    trial: Mutex<trial::Trial>,
    generation: std::sync::atomic::AtomicU64,
    authority: std::sync::Mutex<Option<moli_runtime::CommandPermit>>,
    #[cfg(test)]
    gate: Mutex<
        Option<(
            tokio::sync::oneshot::Sender<()>,
            tokio::sync::oneshot::Receiver<()>,
        )>,
    >,
}
#[derive(Default)]
struct Memory {
    armed: Option<u64>,
    cache: Option<(JsonValue, Option<bool>)>,
    last: Option<(u64, Reason, Demand, Option<bool>)>,
    transitions: u64,
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
enum Demand {
    On,
    Off,
    #[default]
    Unknown,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
enum Reason {
    None,
    Unconfigured,
    Storage,
    SelectionChanged,
    Restarted,
    SourceMissing,
    TargetMissing,
    Identity,
    SourceOffline,
    TargetOffline,
    DriverStopped,
    SourceUnpublished,
    InvalidDemand,
    UnknownDemand,
    MissingDate,
    FutureDate,
    SourceStale,
    TargetStale,
    Coherence,
    Engine,
}
#[derive(Serialize)]
struct Observation {
    revision: u64,
    epoch: u64,
    state: &'static str,
    reason: Reason,
    demand: Demand,
    intention: Option<bool>,
    observed_at: u64,
    collected_at: Option<u64>,
    valid_until: Option<u64>,
    transitions: u64,
    witness: Option<JsonValue>,
}
#[derive(Clone, Debug, PartialEq)]
struct Admission {
    source: String,
    target: String,
    demand: Demand,
    collected: u64,
    max_age: u64,
    valid_until: u64,
    stamp: JsonValue,
    witness: JsonValue,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Save {
    revision: u64,
    source: Option<String>,
    target: Option<String>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Observe {
    revision: u64,
    epoch: u64,
}

impl Observer {
    fn open(path: Option<PathBuf>, autos: Option<Automations>) -> Self {
        let mut storage = Storage {
            path,
            value: Stored::default(),
            valid: true,
        };
        if let Some(path) = &storage.path {
            match std::fs::metadata(path) {
                Ok(meta) if meta.is_file() && meta.len() <= MAX_FILE as u64 => {
                    match std::fs::read(path)
                        .ok()
                        .and_then(|bytes| serde_json::from_slice::<Stored>(&bytes).ok())
                    {
                        Some(value)
                            if value.version == 1
                                && value.selection.as_ref().is_none_or(valid_selection) =>
                        {
                            storage.value = value;
                        }
                        _ => storage.valid = false,
                    }
                }
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                _ => storage.valid = false,
            }
        }
        let revision = storage.value.revision;
        Self(Arc::new(Inner {
            storage: Mutex::new(storage),
            memory: Mutex::new(Memory::default()),
            autos,
            epoch: moli_core::now_ms(),
            revision: std::sync::atomic::AtomicU64::new(revision),
            trial: Mutex::default(),
            generation: std::sync::atomic::AtomicU64::new(0),
            authority: std::sync::Mutex::default(),
            #[cfg(test)]
            gate: Mutex::new(None),
        }))
    }
    async fn settings(&self, hub: &Hub) -> JsonValue {
        let stored = self.0.storage.lock().await;
        let snapshot = hub.snapshot();
        let candidates = |source| {
            snapshot.devices.iter().filter(|d|candidate(&snapshot,d,source)).take(64).map(|d|json!({
            "id":d.device.id,"name":name(d),"instance":d.device.instance,"model":d.device.model,"online":d.online==Some(true)
        })).collect::<Vec<_>>()
        };
        json!({"revision":stored.value.revision,"epoch":self.0.epoch,"selection":stored.value.selection,
            "storage_available":stored.valid&&stored.path.is_some(),"sources":candidates(true),"targets":candidates(false)})
    }
    async fn save(&self, hub: &Hub, input: Save) -> Result<(), StatusCode> {
        let mut storage = self.0.storage.lock().await;
        if !storage.valid || storage.path.is_none() {
            return Err(StatusCode::SERVICE_UNAVAILABLE);
        }
        if input.revision != storage.value.revision {
            return Err(StatusCode::CONFLICT);
        }
        let selection = match (input.source, input.target) {
            (None, None) => None,
            (Some(source), Some(target)) => {
                if !valid_id(&source) || !valid_id(&target) {
                    return Err(StatusCode::UNPROCESSABLE_ENTITY);
                }
                let snapshot = hub.snapshot();
                let s = find(&snapshot, &source)
                    .filter(|d| candidate(&snapshot, d, true))
                    .ok_or(StatusCode::UNPROCESSABLE_ENTITY)?;
                let t = find(&snapshot, &target)
                    .filter(|d| candidate(&snapshot, d, false))
                    .ok_or(StatusCode::UNPROCESSABLE_ENTITY)?;
                Some(Selection {
                    source,
                    target,
                    source_name: name(s),
                    target_name: name(t),
                })
            }
            _ => return Err(StatusCode::UNPROCESSABLE_ENTITY),
        };
        if selection == storage.value.selection {
            return Ok(());
        }
        let value = Stored {
            version: 1,
            revision: storage
                .value
                .revision
                .checked_add(1)
                .ok_or(StatusCode::SERVICE_UNAVAILABLE)?,
            selection,
        };
        trial::revoke(self);
        let path = storage
            .path
            .as_ref()
            .ok_or(StatusCode::SERVICE_UNAVAILABLE)?;
        let bytes = serde_json::to_vec(&value).map_err(|_| StatusCode::SERVICE_UNAVAILABLE)?;
        let tmp = path.with_extension(format!("{}.tmp", moli_core::now_ms()));
        let written = async {
            let mut options = tokio::fs::OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            options.mode(0o600);
            let mut file = options.open(&tmp).await?;
            file.write_all(&bytes).await?;
            file.sync_all().await?;
            drop(file);
            tokio::fs::rename(&tmp, path).await
        }
        .await;
        if written.is_err() {
            let _ = tokio::fs::remove_file(&tmp).await;
            return Err(StatusCode::SERVICE_UNAVAILABLE);
        }
        self.0
            .revision
            .store(value.revision, std::sync::atomic::Ordering::Release);
        storage.value = value;
        Ok(())
    }
    async fn observe(&self, hub: &Hub, input: Observe) -> Observation {
        let mut memory = self.0.memory.lock().await;
        let storage = self.0.storage.lock().await;
        let value = storage.value.clone();
        let valid = storage.valid && storage.path.is_some();
        drop(storage);
        let now = moli_core::now_ms();
        let blocked = if !valid {
            Some(Reason::Storage)
        } else if input.epoch != self.0.epoch || input.revision != value.revision {
            Some(Reason::SelectionChanged)
        } else if value.selection.is_none() {
            Some(Reason::Unconfigured)
        } else if memory.armed != Some(value.revision) {
            let first = memory.armed.is_none();
            memory.armed = Some(value.revision);
            memory.cache = None;
            Some(if first {
                Reason::Restarted
            } else {
                Reason::SelectionChanged
            })
        } else {
            None
        };
        if let Some(reason) = blocked {
            return no_intention(&mut memory, &value, self.0.epoch, reason, now);
        }
        let Some(selection) = value.selection.as_ref() else {
            unreachable!()
        };
        let snapshot = hub.snapshot();
        let now = moli_core::now_ms();
        let collected = find(&snapshot, &selection.source)
            .and_then(|d| integer(d, "collected_at"))
            .filter(|n| *n > 0);
        let before = match admit(&snapshot, selection, now) {
            Ok(value) => value,
            Err(reason) => {
                memory.cache = None;
                return finish(
                    &mut memory,
                    &value,
                    self.0.epoch,
                    reason,
                    Reading {
                        collected_at: collected,
                        ..Reading::default()
                    },
                    now,
                );
            }
        };
        self.evaluate(hub, &value, before, &mut memory).await
    }
    async fn evaluate(
        &self,
        hub: &Hub,
        value: &Stored,
        before: Admission,
        memory: &mut Memory,
    ) -> Observation {
        let selection = value.selection.as_ref().expect("admitted selection");
        let now = moli_core::now_ms();
        let intention = if let Some((stamp, intention)) = &memory.cache
            && stamp == &before.stamp
        {
            *intention
        } else {
            let Some(autos) = &self.0.autos else {
                return no_intention(memory, value, self.0.epoch, Reason::Engine, now);
            };
            let graph = graph::graph(&before, now);
            let run = match autos
                .preview_graph(
                    graph,
                    Actor {
                        human: false,
                        author: Author::Agent,
                    },
                )
                .await
            {
                Ok(run) if run.dry => run,
                _ => {
                    return no_intention(memory, value, self.0.epoch, Reason::Engine, now);
                }
            };
            let steps: Vec<_> = run
                .steps
                .iter()
                .filter(|step| step.status == StepStatus::Simulated)
                .collect();
            match steps.as_slice() {
                [] if before.demand == Demand::Unknown => None,
                [step] if step.node == "would_on" && before.demand == Demand::On => Some(true),
                [step] if step.node == "would_off" && before.demand == Demand::Off => Some(false),
                _ => {
                    return no_intention(memory, value, self.0.epoch, Reason::Engine, now);
                }
            }
        };
        #[cfg(test)]
        if let Some((reached, resume)) = self.0.gate.lock().await.take() {
            let _ = reached.send(());
            let _ = resume.await;
        }
        let now = moli_core::now_ms();
        let current = self.0.storage.lock().await.value.revision;
        if current != value.revision {
            memory.cache = None;
            return no_intention(memory, value, self.0.epoch, Reason::SelectionChanged, now);
        }
        let after = hub.snapshot();
        let now = moli_core::now_ms();
        match admit(&after, selection, now) {
            Ok(after) if after == before => {
                memory.cache = Some((before.stamp, intention));
                finish(
                    memory,
                    value,
                    self.0.epoch,
                    if intention.is_some() {
                        Reason::None
                    } else {
                        Reason::UnknownDemand
                    },
                    Reading {
                        demand: before.demand,
                        intention,
                        collected_at: Some(before.collected),
                        valid_until: Some(before.valid_until),
                        witness: Some(before.witness),
                    },
                    now,
                )
            }
            result => {
                memory.cache = None;
                no_intention(
                    memory,
                    value,
                    self.0.epoch,
                    result.err().unwrap_or(Reason::Coherence),
                    now,
                )
            }
        }
    }
}
#[derive(Default)]
struct Reading {
    demand: Demand,
    intention: Option<bool>,
    collected_at: Option<u64>,
    valid_until: Option<u64>,
    witness: Option<JsonValue>,
}

fn no_intention(
    memory: &mut Memory,
    stored: &Stored,
    epoch: u64,
    reason: Reason,
    now: u64,
) -> Observation {
    finish(memory, stored, epoch, reason, Reading::default(), now)
}
fn finish(
    memory: &mut Memory,
    stored: &Stored,
    epoch: u64,
    reason: Reason,
    reading: Reading,
    now: u64,
) -> Observation {
    let Reading {
        demand,
        intention,
        collected_at,
        valid_until,
        witness,
    } = reading;
    let key = (stored.revision, reason, demand, intention);
    if memory.last.as_ref() != Some(&key) {
        memory.transitions = memory.transitions.saturating_add(1);
        memory.last = Some(key);
    }
    let state = match reason {
        Reason::None => "ready",
        Reason::Unconfigured => "unconfigured",
        Reason::SourceOffline
        | Reason::TargetOffline
        | Reason::DriverStopped
        | Reason::SourceUnpublished
        | Reason::MissingDate
        | Reason::FutureDate
        | Reason::SourceStale
        | Reason::TargetStale => "stale",
        _ => "unknown",
    };
    Observation {
        revision: stored.revision,
        epoch,
        state,
        reason,
        demand,
        intention,
        observed_at: now,
        collected_at,
        valid_until: if intention.is_some() {
            valid_until
        } else {
            None
        },
        transitions: memory.transitions,
        witness: if intention.is_some() { witness } else { None },
    }
}
fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 256 && id.contains(':') && !id.chars().any(char::is_control)
}
fn valid_selection(s: &Selection) -> bool {
    valid_id(&s.source)
        && valid_id(&s.target)
        && s.source_name.len() <= 640
        && s.target_name.len() <= 640
}
fn find<'a>(snapshot: &'a Snapshot, id: &str) -> Option<&'a DeviceView> {
    snapshot
        .devices
        .iter()
        .find(|d| d.device.id == DeviceId::from(id))
}
fn name(d: &DeviceView) -> String {
    d.label
        .name
        .as_deref()
        .unwrap_or(&d.device.native_name)
        .chars()
        .filter(|c| !c.is_control())
        .take(160)
        .collect()
}
fn candidate(snapshot: &Snapshot, d: &DeviceView, source: bool) -> bool {
    let Some(driver) = snapshot
        .drivers
        .iter()
        .find(|driver| driver.instance == d.device.instance)
    else {
        return false;
    };
    if source {
        driver.kind == "netatmo"
            && matches!(d.device.model.as_deref(), Some("NATherm1" | "OTM"))
            && d.device
                .points
                .iter()
                .any(|p| p.key.as_ref() == "declared_demand" && p.access.read)
    } else {
        driver.kind == "profile"
            && driver.integration.as_ref() == "meross-mss310"
            && d.device
                .model
                .as_deref()
                .is_some_and(|m| m.eq_ignore_ascii_case("mss310"))
            && d.device
                .id
                .as_str()
                .strip_prefix(&format!("{}:", d.device.instance))
                .is_some_and(|id| id.len() == 32 && id.bytes().all(|b| b.is_ascii_hexdigit()))
    }
}
fn integer(d: &DeviceView, key: &str) -> Option<u64> {
    match d.state.get(key)?.value {
        Value::Int(n) => u64::try_from(n).ok(),
        _ => None,
    }
}
fn sample_expiry(
    device: &DeviceView,
    key: &str,
    now: u64,
    age: u64,
    published: u64,
) -> Result<u64, Reason> {
    let sample = device.state.get(key).ok_or(Reason::MissingDate)?;
    if sample.ts == 0 {
        return Err(Reason::MissingDate);
    }
    if sample.ts > now {
        return Err(Reason::FutureDate);
    }
    if now - sample.ts >= age {
        return Err(Reason::SourceStale);
    }
    if sample.ts > published {
        return Err(Reason::Coherence);
    }
    Ok(sample.ts.saturating_add(age))
}
fn selected<'a>(
    snapshot: &'a Snapshot,
    selection: &Selection,
) -> Result<
    (
        &'a DeviceView,
        &'a DeviceView,
        &'a moli_runtime::IntegrationReadView,
    ),
    Reason,
> {
    let source = find(snapshot, &selection.source).ok_or(Reason::SourceMissing)?;
    let target = find(snapshot, &selection.target).ok_or(Reason::TargetMissing)?;
    if !candidate(snapshot, source, true) || !candidate(snapshot, target, false) {
        return Err(Reason::Identity);
    }
    if source.online != Some(true) {
        return Err(Reason::SourceOffline);
    }
    if target.online != Some(true) {
        return Err(Reason::TargetOffline);
    }
    if [source, target].iter().any(|d| {
        !snapshot.drivers.iter().any(|driver| {
            driver.instance == d.device.instance && driver.status == DriverStatus::Running
        })
    }) {
        return Err(Reason::DriverStopped);
    }
    let diagnostic = snapshot
        .diagnostics
        .iter()
        .find(|row| row.instance == source.device.instance && row.read.kind == "netatmo")
        .ok_or(Reason::SourceUnpublished)?;
    if !diagnostic.read.driver_started
        || diagnostic.read.last_publish_at == 0
        || diagnostic.read.last_publish_at < diagnostic.read.started_at
    {
        return Err(Reason::SourceUnpublished);
    }
    Ok((source, target, diagnostic))
}
fn admit(snapshot: &Snapshot, selection: &Selection, now: u64) -> Result<Admission, Reason> {
    let (source, target, diagnostic) = selected(snapshot, selection)?;
    let demand = match &source
        .state
        .get("declared_demand")
        .ok_or(Reason::InvalidDemand)?
        .value
    {
        Value::Text(s) if s.as_ref() == "on" => Demand::On,
        Value::Text(s) if s.as_ref() == "off" => Demand::Off,
        Value::Text(s) if s.as_ref() == "unknown" => Demand::Unknown,
        _ => return Err(Reason::InvalidDemand),
    };
    let max_age = integer(source, "max_age_seconds")
        .filter(|n| (1..=3600).contains(n))
        .ok_or(Reason::MissingDate)?
        .saturating_mul(1000);
    let collected = integer(source, "collected_at")
        .filter(|n| *n > 0)
        .ok_or(Reason::MissingDate)?;
    if collected > now {
        return Err(Reason::FutureDate);
    }
    if now - collected >= max_age {
        return Err(Reason::SourceStale);
    }
    if !matches!(source.state.get("freshness").map(|s|&s.value),Some(Value::Text(v)) if v.as_ref()=="cloud_recent")
    {
        return Err(Reason::SourceStale);
    }
    let mut valid_until = collected.saturating_add(max_age);
    for key in [
        "declared_demand",
        "freshness",
        "collected_at",
        "max_age_seconds",
    ] {
        valid_until = valid_until.min(sample_expiry(
            source,
            key,
            now,
            max_age,
            diagnostic.read.last_publish_at,
        )?);
    }
    let model = target.state.get("reported_model").ok_or(Reason::Identity)?;
    if !matches!(&model.value,Value::Text(s) if s.eq_ignore_ascii_case("mss310")) {
        return Err(Reason::Identity);
    }
    if model.ts == 0 || model.ts > now || now - model.ts >= 65000 {
        return Err(Reason::TargetStale);
    }
    valid_until = valid_until.min(model.ts.saturating_add(65000));
    let stamp = json!([
        source.device.id,
        source.device.model,
        source.state.get("declared_demand"),
        source.state.get("freshness"),
        source.state.get("collected_at"),
        source.state.get("max_age_seconds"),
        target.device.id,
        target.device.model,
        model,
        diagnostic.read.counter_epoch,
        diagnostic.read.started_at,
        diagnostic.read.last_publish_at
    ]);
    let witness = json!([
        source
            .state
            .get("declared_demand")
            .map(|s| (&s.value, s.ts)),
        source.state.get("freshness").map(|s| (&s.value, s.ts)),
        source.state.get("collected_at").map(|s| (&s.value, s.ts)),
        source
            .state
            .get("max_age_seconds")
            .map(|s| (&s.value, s.ts)),
        (&model.value, model.ts)
    ]);
    Ok(Admission {
        source: selection.source.clone(),
        target: selection.target.clone(),
        demand,
        collected,
        max_age,
        valid_until,
        stamp,
        witness,
    })
}
fn allowed(caller: &Caller) -> bool {
    caller.is_household()
        && caller.via != Via::Visitor
        && !caller
            .person
            .as_ref()
            .is_some_and(crate::people::PersonRef::is_guest)
}
fn denied() -> Response {
    (
        StatusCode::FORBIDDEN,
        Json(json!({"error":"observation_forbidden"})),
    )
        .into_response()
}
async fn settings(
    State(hub): State<Hub>,
    Extension(observer): Extension<Observer>,
    caller: Caller,
) -> Response {
    if !allowed(&caller) {
        return denied();
    }
    Json(observer.settings(&hub).await).into_response()
}
async fn save(
    State(hub): State<Hub>,
    Extension(observer): Extension<Observer>,
    caller: Caller,
    Json(input): Json<Save>,
) -> Response {
    if !allowed(&caller) {
        return denied();
    }
    match observer.save(&hub, input).await {
        Ok(()) => Json(observer.settings(&hub).await).into_response(),
        Err(status) => (status, Json(json!({"error":"observation_selection"}))).into_response(),
    }
}
async fn observe(
    State(hub): State<Hub>,
    Extension(observer): Extension<Observer>,
    caller: Caller,
    Json(input): Json<Observe>,
) -> Response {
    if !allowed(&caller) {
        return denied();
    }
    Json(observer.observe(&hub, input).await).into_response()
}
pub(crate) fn routes(options: &Options) -> Router<Hub> {
    let path = options
        .home_path
        .as_deref()
        .and_then(std::path::Path::parent)
        .map(|dir| dir.join(FILE));
    Router::new()
        .route("/api/observations/netatmo-meross", get(settings).put(save))
        .route("/api/observations/netatmo-meross/observe", post(observe))
        .merge(trial::routes())
        .layer(axum::extract::DefaultBodyLimit::max(MAX_FILE))
        .layer(Extension(Observer::open(path, options.automations.clone())))
}
