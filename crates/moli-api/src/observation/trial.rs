//! Voluntary one-shot execution. No timer, automatic enable or device IO here:
//! all writes use the original automation executor, hub and profile.
use super::{
    Actor, Admission, Author, Caller, Demand, Extension, Hub, Json, JsonValue, Observe, Observer,
    Response, Router, Selection, State, StatusCode, StepStatus, Value, admit, allowed, denied,
    find, graph, json, post, sample_expiry,
};
use axum::http::HeaderMap;
use axum::response::IntoResponse;
use moli_automation::{Automation, Mode, RunStatus, Step};
use moli_core::PointId;
use moli_runtime::CommandPermit;
use serde::Deserialize;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;
#[derive(Default)]
pub(super) struct Trial {
    pending: Option<Prepared>,
    draft: Option<String>,
    state: &'static str,
}
struct Prepared {
    revision: u64,
    generation: u64,
    before: Admission,
    automation: String,
    fingerprint: String,
    expires: u64,
}
#[derive(Deserialize)]
// Independent physical assertions in a wire DTO, not an execution state machine.
#[allow(clippy::struct_excessive_bools)]
#[serde(deny_unknown_fields)]
pub(super) struct Execute {
    revision: u64,
    epoch: u64,
    fingerprint: String,
    /// Each assertion must be made for this attempt, never inherited from
    /// manual permissions, an update, a saved file or a previous session.
    vessel_pressure: bool,
    relay_enclosure: bool,
    thermostat_input: bool,
    loss_behavior: bool,
    supervised: bool,
}
pub(super) fn revoke(observer: &Observer) {
    observer.0.generation.fetch_add(1, Ordering::AcqRel);
    if let Some(scope) = observer
        .0
        .authority
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .take()
    {
        scope.revoke();
    }
}
fn view(trial: &Trial) -> JsonValue {
    json!({"state":if trial.state.is_empty(){"observation"}else{trial.state},"autonomous":false,
        "prepared":trial.pending.as_ref().map(|p|json!({"revision":p.revision,"automation":p.automation,"fingerprint":p.fingerprint,"demand":p.before.demand,"expires_at":p.expires}))})
}
fn rejected(status: StatusCode) -> Response {
    (status, Json(json!({"error":"supervised_trial_refused"}))).into_response()
}
fn real_admission(hub: &Hub, selection: &Selection, now: u64) -> Result<Admission, StatusCode> {
    let snapshot = hub.snapshot();
    let mut admitted = admit(&snapshot, selection, now).map_err(|_| StatusCode::CONFLICT)?;
    let source = find(&snapshot, &selection.source).ok_or(StatusCode::CONFLICT)?;
    let expected = match admitted.demand {
        Demand::On => true,
        Demand::Off => false,
        Demand::Unknown => return Err(StatusCode::CONFLICT),
    };
    let boiler = source
        .state
        .get("boiler_status")
        .ok_or(StatusCode::CONFLICT)?;
    if boiler.value != Value::Bool(expected)
        || boiler.ts > now
        || boiler.ts == 0
        || now.saturating_sub(boiler.ts) >= admitted.max_age
    {
        return Err(StatusCode::CONFLICT);
    }
    let published = snapshot
        .diagnostics
        .iter()
        .find(|d| d.instance == source.device.instance && d.read.kind == "netatmo")
        .ok_or(StatusCode::CONFLICT)?
        .read
        .last_publish_at;
    let boiler_until = sample_expiry(source, "boiler_status", now, admitted.max_age, published)
        .map_err(|_| StatusCode::CONFLICT)?;
    admitted.valid_until = admitted.valid_until.min(boiler_until);
    admitted.stamp = json!([admitted.stamp, boiler]);
    let target = find(&snapshot, &selection.target).ok_or(StatusCode::CONFLICT)?;
    if !target.device.point("on").is_some_and(|p| p.access.write) {
        return Err(StatusCode::FORBIDDEN);
    }
    Ok(admitted)
}
fn one_shot_graph(before: &Admission, now: u64) -> moli_automation::Graph {
    let mut graph = graph::graph(before, now);
    let unused = if before.demand == Demand::On {
        "would_off"
    } else {
        "would_on"
    };
    graph.nodes.retain(|n| n.id != unused);
    graph.edges.retain(|e| e.to != unused && e.from != unused);
    graph
}
impl Observer {
    #[cfg(test)]
    pub(super) async fn expire_trial(&self) {
        self.0.trial.lock().await.pending.as_mut().unwrap().expires = 0;
    }

    pub(super) async fn prepare_trial(
        &self,
        hub: &Hub,
        input: Observe,
    ) -> Result<JsonValue, StatusCode> {
        let mut trial = self.0.trial.try_lock().map_err(|_| StatusCode::CONFLICT)?;
        revoke(self);
        let generation = self.0.generation.load(Ordering::Acquire);
        trial.pending = None;
        trial.state = "observation";
        let storage = self.0.storage.lock().await;
        if !storage.valid || input.epoch != self.0.epoch || input.revision != storage.value.revision
        {
            return Err(StatusCode::CONFLICT);
        }
        let selection = storage
            .value
            .selection
            .clone()
            .ok_or(StatusCode::CONFLICT)?;
        drop(storage);
        let now = moli_core::now_ms();
        let before = real_admission(hub, &selection, now)?;
        let expires = (now + 60_000).min(before.valid_until.saturating_sub(5_000));
        if expires <= now {
            return Err(StatusCode::CONFLICT);
        }
        let autos = self
            .0
            .autos
            .as_ref()
            .ok_or(StatusCode::SERVICE_UNAVAILABLE)?;
        let previous = trial.draft.as_ref().and_then(|id| autos.get(id));
        let a = Automation {
            id: previous.as_ref().map(|a| a.id.clone()).unwrap_or_default(),
            name: previous.map_or_else(
                || moli_i18n::tr!("maison.observation.trial_name"),
                |a| a.name,
            ),
            enabled: false,
            mode: Mode::Single,
            graph: one_shot_graph(&before, now),
            author: Author::Agent,
            approved: None,
            approved_version: None,
            note: None,
            created: 0,
            updated: 0,
        };
        // No effectful/automatic trigger may be smuggled into this draft.
        if a.graph
            .nodes
            .iter()
            .filter(|n| matches!(n.step, Step::Set { .. }))
            .count()
            != 1
            || !autos.check(&a.graph).ok()
        {
            return Err(StatusCode::CONFLICT);
        }
        let saved = autos
            .save(
                a,
                Actor {
                    human: false,
                    author: Author::Agent,
                },
            )
            .await
            .map_err(|_| StatusCode::CONFLICT)?;
        trial.draft = Some(saved.id.clone());
        trial.pending = Some(Prepared {
            revision: input.revision,
            generation,
            before,
            automation: saved.id.clone(),
            fingerprint: saved.fingerprint(),
            expires,
        });
        trial.state = "prepared";
        Ok(view(&trial))
    }
    fn trial_scope(
        &self,
        hub: &Hub,
        autos: &moli_automation::Automations,
        prepared: &Prepared,
        selection: Selection,
    ) -> CommandPermit {
        let generation = prepared.generation;
        let revision = prepared.revision;
        let before = prepared.before.clone();
        let observer = Arc::downgrade(&self.0);
        let live = hub.clone();
        let approved = autos.clone();
        let approved_id = prepared.automation.clone();
        let approved_version = prepared.fingerprint.clone();
        let value = Value::Bool(before.demand == Demand::On);
        CommandPermit::new(
            PointId::from(format!("{}/on", selection.target)),
            value,
            Duration::from_secs(6),
            move || {
                observer.upgrade().is_some_and(|inner| {
                    inner.generation.load(Ordering::Acquire) == generation
                        && inner.revision.load(Ordering::Acquire) == revision
                }) && approved.get(&approved_id).is_some_and(|a| {
                    !a.enabled && a.is_approved() && a.fingerprint() == approved_version
                }) && real_admission(&live, &selection, moli_core::now_ms())
                    .is_ok_and(|a| a == before)
            },
        )
    }
    pub(super) async fn execute_trial(
        &self,
        hub: &Hub,
        input: Execute,
        by: Actor,
    ) -> Result<JsonValue, StatusCode> {
        if !by.human
            || ![
                input.vessel_pressure,
                input.relay_enclosure,
                input.thermostat_input,
                input.loss_behavior,
                input.supervised,
            ]
            .into_iter()
            .all(|v| v)
        {
            return Err(StatusCode::FORBIDDEN);
        }
        let mut trial = self.0.trial.try_lock().map_err(|_| StatusCode::CONFLICT)?;
        let prepared = trial.pending.take().ok_or(StatusCode::CONFLICT)?;
        trial.state = "unconfirmed";
        let storage = self.0.storage.lock().await;
        if !storage.valid
            || input.epoch != self.0.epoch
            || input.revision != prepared.revision
            || storage.value.revision != prepared.revision
            || input.fingerprint != prepared.fingerprint
            || self.0.generation.load(Ordering::Acquire) != prepared.generation
            || moli_core::now_ms() >= prepared.expires
        {
            return Err(StatusCode::CONFLICT);
        }
        let selection = storage
            .value
            .selection
            .clone()
            .ok_or(StatusCode::CONFLICT)?;
        drop(storage);
        let now = moli_core::now_ms();
        if real_admission(hub, &selection, now)? != prepared.before {
            return Err(StatusCode::CONFLICT);
        }
        let autos = self
            .0
            .autos
            .as_ref()
            .ok_or(StatusCode::SERVICE_UNAVAILABLE)?;
        autos
            .approve_supervised(&prepared.automation, &prepared.fingerprint, by)
            .await
            .map_err(|_| StatusCode::CONFLICT)?;
        let generation = prepared.generation;
        let scope = self.trial_scope(hub, autos, &prepared, selection);
        *self
            .0
            .authority
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(scope.clone());
        trial.state = "running";
        let result = autos
            .run_supervised(&prepared.automation, &prepared.fingerprint, by, scope)
            .await;
        self.0
            .authority
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take();
        trial.state = match result {
            Ok(run)
                if run.status == RunStatus::Done
                    && run
                        .steps
                        .iter()
                        .filter(|s| s.node.starts_with("would_") && s.status == StepStatus::Done)
                        .count()
                        == 1 =>
            {
                "confirmed"
            }
            _ => "unconfirmed",
        };
        if self.0.generation.load(Ordering::Acquire) != generation {
            trial.state = "cancelled";
        }
        Ok(view(&trial))
    }
}
async fn prepare(
    State(hub): State<Hub>,
    Extension(observer): Extension<Observer>,
    caller: Caller,
    Json(input): Json<Observe>,
) -> Response {
    if !allowed(&caller) {
        return denied();
    }
    match observer.prepare_trial(&hub, input).await {
        Ok(result) => Json(result).into_response(),
        Err(status) => rejected(status),
    }
}
async fn execute(
    State(hub): State<Hub>,
    Extension(observer): Extension<Observer>,
    Extension(humans): Extension<Arc<crate::session::Sessions>>,
    caller: Caller,
    headers: HeaderMap,
    Json(input): Json<Execute>,
) -> Response {
    if !allowed(&caller) || !humans.is_human(&headers, &caller.key) {
        return denied();
    }
    match observer
        .execute_trial(
            &hub,
            input,
            Actor {
                human: true,
                author: Author::Human,
            },
        )
        .await
    {
        Ok(result) => Json(result).into_response(),
        Err(status) => rejected(status),
    }
}
async fn cancel(Extension(observer): Extension<Observer>, caller: Caller) -> Response {
    if !allowed(&caller) {
        return denied();
    }
    revoke(&observer);
    // Revocation must not wait behind an in-flight request. An order already
    // transmitted cannot be recalled and cancellation never claims to send OFF.
    Json(json!({"state":"cancelled","autonomous":false,"prepared":null})).into_response()
}
pub(super) fn routes() -> Router<Hub> {
    Router::new()
        .route(
            "/api/observations/netatmo-meross/trial/prepare",
            post(prepare),
        )
        .route(
            "/api/observations/netatmo-meross/trial/execute",
            post(execute),
        )
        .route(
            "/api/observations/netatmo-meross/trial/cancel",
            post(cancel),
        )
}
