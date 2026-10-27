//! Voluntary one-shot execution. No timer, automatic enable or device IO here:
//! all writes use the original automation executor, hub and profile.
use super::{
    Actor, Admission, Author, Caller, Demand, Extension, Hub, Json, JsonValue, Observe, Observer,
    Response, Router, Selection, State, StatusCode, StepStatus, Value, admit, allowed, denied,
    find, json, post, sample_expiry,
};
use axum::http::HeaderMap;
use axum::response::IntoResponse;
use moli_automation::{Automation, Mode, RunStatus, Step};
use moli_core::PointId;
use moli_runtime::{CommandPermit, ReadStage};
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
#[serde(deny_unknown_fields)]
pub(super) struct Execute {
    revision: u64,
    epoch: u64,
    fingerprint: String,
    /// One explicit consent for this exact order, never stored or inherited.
    confirmed: bool,
}
#[derive(Debug, PartialEq, Eq)]
pub(super) struct Refusal(StatusCode, &'static str);
impl From<StatusCode> for Refusal {
    fn from(status: StatusCode) -> Self {
        Self(status, "version_changed")
    }
}
impl PartialEq<StatusCode> for Refusal {
    fn eq(&self, other: &StatusCode) -> bool {
        self.0 == *other
    }
}
fn assessment(
    hub: &Hub,
    selection: &Selection,
    now: u64,
) -> Result<(Admission, moli_automation::ObservationSample), Refusal> {
    let snapshot = hub.snapshot();
    if find(&snapshot, &selection.target)
        .is_some_and(|d| !d.device.point("on").is_some_and(|p| p.access.write))
    {
        return Err(Refusal(StatusCode::FORBIDDEN, "target_permission"));
    }
    super::daily::review_snapshot(&snapshot, selection, now)
        .map_err(|reason| Refusal(StatusCode::CONFLICT, reason))
}
fn unchanged(before: &Admission, after: &Admission) -> bool {
    before.source == after.source
        && before.target == after.target
        && before.demand == after.demand
        && before.max_age == after.max_age
        && after.collected >= before.collected
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
fn execution_result(
    trial: &Trial,
    progress_before: [u64; 4],
    progress_after: [u64; 4],
) -> JsonValue {
    let mut result = view(trial);
    result["dispatch"] = json!(if progress_after[1] > progress_before[1]
        || trial.state == "confirmed"
    {
        "acknowledged"
    } else if progress_after[0] > progress_before[0] {
        "attempted"
    } else {
        "not_sent"
    });
    result["reason"] = json!(if trial.state == "refused" {
        "preflight"
    } else if trial.state == "sent" {
        "readback"
    } else {
        "unconfirmed"
    });
    result
}
fn rejected(Refusal(status, reason): Refusal) -> Response {
    (
        status,
        Json(json!({"state":"refused","reason":reason,"autonomous":false,"prepared":null})),
    )
        .into_response()
}
pub(super) fn real_snapshot(
    snapshot: &moli_runtime::Snapshot,
    selection: &Selection,
    now: u64,
) -> Result<Admission, StatusCode> {
    let mut admitted = admit(snapshot, selection, now).map_err(|_| StatusCode::CONFLICT)?;
    let source = find(snapshot, &selection.source).ok_or(StatusCode::CONFLICT)?;
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
    let target = find(snapshot, &selection.target).ok_or(StatusCode::CONFLICT)?;
    if !target.device.point("on").is_some_and(|p| p.access.write) {
        return Err(StatusCode::FORBIDDEN);
    }
    Ok(admitted)
}
fn command_progress(hub: &Hub, target: &str) -> [u64; 4] {
    let snapshot = hub.snapshot();
    let Some(device) = find(&snapshot, target) else {
        return [0; 4];
    };
    let Some(diagnostic) = snapshot
        .diagnostics
        .iter()
        .find(|d| d.instance == device.device.instance)
    else {
        return [0; 4];
    };
    let counts = [
        ReadStage::CommandSend,
        ReadStage::CommandAck,
        ReadStage::CommandReadback,
    ]
    .map(|stage| {
        diagnostic.read.stages.get(&stage).map_or(0, |s| {
            if stage == ReadStage::CommandSend {
                s.attempts
            } else {
                s.successes
            }
        })
    });
    [
        counts[0],
        counts[1],
        counts[2],
        u64::from(diagnostic.read.kind == "meross"),
    ]
}
fn command_error(hub: &Hub, target: &str, since: u64) -> JsonValue {
    let snapshot = hub.snapshot();
    find(&snapshot, target)
        .and_then(|d| {
            snapshot
                .diagnostics
                .iter()
                .find(|r| r.instance == d.device.instance)
        })
        .and_then(|d| {
            d.read
                .stages
                .values()
                .filter(|s| s.last_error_at >= since)
                .max_by_key(|s| s.last_error_at)
        })
        .map_or(JsonValue::Null, |s| json!(s.last_error))
}
fn one_shot_graph(before: &Admission, selection: &Selection) -> moli_automation::Graph {
    // Reuse the qualified freshness/identity/demand/readback graph, manual only.
    let mut graph = super::daily::graph(selection, before.max_age);
    let unused = if before.demand == Demand::On {
        "off"
    } else {
        "on"
    };
    graph.nodes.retain(|n| {
        n.id != "publication"
            && n.id != format!("set_{unused}")
            && n.id != format!("demand_{unused}")
    });
    for node in &mut graph.nodes {
        if node.id == moli_automation::DAILY_HEATING_LOCK {
            node.id = "start".into();
            node.step = Step::Manual;
        }
        if node.id.starts_with("set_") {
            node.id = node.id.replace("set_", "would_");
        }
    }
    graph.edges.retain(|e| {
        e.from != "publication"
            && e.from != format!("demand_{unused}")
            && e.to != format!("demand_{unused}")
            && e.to != format!("set_{unused}")
    });
    for edge in &mut graph.edges {
        if edge.from == moli_automation::DAILY_HEATING_LOCK {
            edge.from = "start".into();
        }
        if edge.to.starts_with("set_") {
            edge.to = edge.to.replace("set_", "would_");
        }
    }
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
    ) -> Result<JsonValue, Refusal> {
        let mut trial = self.0.trial.try_lock().map_err(|_| StatusCode::CONFLICT)?;
        revoke(self);
        let generation = self.0.generation.load(Ordering::Acquire);
        trial.pending = None;
        trial.state = "observation";
        let storage = self.0.storage.lock().await;
        if !storage.valid || input.epoch != self.0.epoch || input.revision != storage.value.revision
        {
            return Err(StatusCode::CONFLICT.into());
        }
        let selection = storage
            .value
            .selection
            .clone()
            .ok_or(StatusCode::CONFLICT)?;
        drop(storage);
        let now = moli_core::now_ms();
        let (before, sample) = assessment(hub, &selection, now)?;
        if sample.actual == sample.desired {
            trial.state = "already_applied";
            return Ok(view(&trial));
        }
        // New target readings are revalidated on confirmation; do not expire a
        // ticket solely because the previous Meross poll is nearing its deadline.
        let expires = (now + 120_000).min(
            before
                .collected
                .saturating_add(before.max_age)
                .saturating_sub(5_000),
        );
        if expires <= now {
            return Err(StatusCode::CONFLICT.into());
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
            graph: one_shot_graph(&before, &selection),
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
            return Err(StatusCode::CONFLICT.into());
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
                }) && approved
                    .get(&approved_id)
                    .is_some_and(|a| !a.enabled && a.fingerprint() == approved_version)
                    && super::daily::review_source(
                        &live.snapshot(),
                        &selection,
                        moli_core::now_ms(),
                    )
                    .is_ok_and(|a| unchanged(&before, &a))
            },
        )
    }
    pub(super) async fn execute_trial(
        &self,
        hub: &Hub,
        input: Execute,
        by: Actor,
    ) -> Result<JsonValue, Refusal> {
        if !by.human || !input.confirmed {
            return Err(Refusal(StatusCode::FORBIDDEN, "confirmation"));
        }
        let mut trial = self.0.trial.try_lock().map_err(|_| StatusCode::CONFLICT)?;
        let prepared = trial
            .pending
            .take()
            .ok_or(Refusal(StatusCode::CONFLICT, "cancelled"))?;
        trial.state = "unconfirmed";
        let storage = self.0.storage.lock().await;
        if !storage.valid
            || input.epoch != self.0.epoch
            || input.revision != prepared.revision
            || storage.value.revision != prepared.revision
            || input.fingerprint != prepared.fingerprint
            || self.0.generation.load(Ordering::Acquire) != prepared.generation
        {
            return Err(StatusCode::CONFLICT.into());
        }
        let selection = storage
            .value
            .selection
            .clone()
            .ok_or(StatusCode::CONFLICT)?;
        drop(storage);
        let now = moli_core::now_ms();
        if now >= prepared.expires {
            return Err(Refusal(StatusCode::CONFLICT, "expired"));
        }
        let (after, sample) = assessment(hub, &selection, now)?;
        if !unchanged(&prepared.before, &after) {
            return Err(Refusal(StatusCode::CONFLICT, "demand_changed"));
        }
        if sample.actual == sample.desired {
            trial.state = "already_applied";
            return Ok(view(&trial));
        }
        let autos = self
            .0
            .autos
            .as_ref()
            .ok_or(StatusCode::SERVICE_UNAVAILABLE)?;
        let generation = prepared.generation;
        let progress_before = command_progress(hub, &selection.target);
        let target = selection.target.clone();
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
        let progress_after = command_progress(hub, &target);
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
            _ if progress_after[1] > progress_before[1] => "sent",
            _ if progress_after[0] > progress_before[0] => "unconfirmed",
            _ => "refused",
        };
        if trial.state == "confirmed"
            && progress_after[3] == 1
            && progress_after[0] == progress_before[0]
        {
            trial.state = "already_applied";
        }
        if self.0.generation.load(Ordering::Acquire) != generation {
            trial.state = "cancelled";
        }
        let mut result = execution_result(&trial, progress_before, progress_after);
        result["technical"] = command_error(hub, &target, now);
        Ok(result)
    }
}
async fn status(
    State(hub): State<Hub>,
    Extension(observer): Extension<Observer>,
    caller: Caller,
) -> Response {
    if !allowed(&caller) {
        return denied();
    }
    let storage = observer.0.storage.lock().await;
    let Some(selection) = &storage.value.selection else {
        return rejected(Refusal(StatusCode::CONFLICT, "unconfigured"));
    };
    let now = moli_core::now_ms();
    let mut result = json!({"selection":selection,"revision":storage.value.revision,"epoch":observer.0.epoch,"autonomous":false});
    match assessment(&hub, selection, now) {
        Ok((admission, sample)) => {
            result["state"] = json!(if sample.actual == sample.desired {
                "already_applied"
            } else {
                "ready"
            });
            result["demand"] = json!(admission.demand);
            result["actual"] = json!(sample.actual);
            result["collected_at"] = json!(admission.collected);
            result["valid_until"] = json!(admission.valid_until);
        }
        Err(Refusal(_, reason)) => {
            result["state"] = json!("refused");
            result["reason"] = json!(reason);
        }
    }
    Json(result).into_response()
}
async fn refresh(
    State(hub): State<Hub>,
    Extension(observer): Extension<Observer>,
    caller: Caller,
    Json(input): Json<Observe>,
) -> Response {
    if !allowed(&caller) {
        return denied();
    }
    let storage = observer.0.storage.lock().await;
    if !storage.valid || input.epoch != observer.0.epoch || input.revision != storage.value.revision
    {
        return rejected(Refusal(StatusCode::CONFLICT, "version_changed"));
    }
    let Some(selection) = &storage.value.selection else {
        return rejected(Refusal(StatusCode::CONFLICT, "unconfigured"));
    };
    let snapshot = hub.snapshot();
    let Some(source) = find(&snapshot, &selection.source) else {
        return rejected(Refusal(StatusCode::CONFLICT, "identity"));
    };
    // Registration only exists for a running Netatmo driver; never dispatch SET.
    Json(hub.request_read_refresh(&source.device.instance)).into_response()
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
            "/api/observations/netatmo-meross/trial/status",
            super::get(status),
        )
        .route("/api/observations/netatmo-meross/refresh", post(refresh))
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
