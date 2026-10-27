//! Daily candidate: original clock/state triggers and executor, no extra task.
//! This delivery deliberately supplies no Meross authority or activation route.
use super::{
    Actor, Arc, Author, Automations, Caller, Demand, Extension, Hub, Inner, IntoResponse, Json,
    JsonValue, Observe, Observer, Response, Router, Selection, State, StatusCode, Value, allowed,
    denied, find, get, json, trial,
};
use moli_automation::{Automation, Mode};

pub(super) fn graph(selection: &Selection, max_age: u64) -> moli_automation::Graph {
    let source = &selection.source;
    let target = format!("{}/on", selection.target);
    let collected = format!("{source}/collected_at");
    let demand = format!("{source}/declared_demand");
    let boiler = format!("{source}/boiler_status");
    let mut nodes = vec![
        json!({"id":moli_automation::DAILY_HEATING_LOCK,"type":"every","minutes":1}),
        json!({"id":"publication","type":"when_state","point":collected}),
        json!({"id":"fresh","type":"if","all":true,"rules":[{"kind":"state","point":format!("{source}/freshness"),"value":"cloud_recent"}]}),
    ];
    let mut edges = vec![
        json!({"from":moli_automation::DAILY_HEATING_LOCK,"to":"fresh"}),
        json!({"from":"publication","to":"fresh"}),
    ];
    let mut previous = "fresh".to_string();
    let mut port = "yes";
    // A frozen publication or target cannot keep granting intentions. A missing
    // state is stale too. Original `if` no-ports provide the negation.
    for (i, (point, seconds)) in [
        (collected, max_age / 1000),
        (demand.clone(), max_age / 1000),
        (boiler.clone(), max_age / 1000),
        (format!("{}/reported_model", selection.target), 65),
        (target.clone(), 65),
    ]
    .into_iter()
    .enumerate()
    {
        let id = format!("age_{i}");
        nodes.push(
            json!({"id":id,"type":"if","rules":[{"kind":"stale","point":point,"for_s":seconds}]}),
        );
        edges.push(json!({"from":previous,"port":port,"to":id}));
        previous = id;
        port = "no";
    }
    for (suffix, text, value) in [("on", "on", true), ("off", "off", false)] {
        let branch = format!("demand_{suffix}");
        let set = format!("set_{suffix}");
        nodes.push(json!({"id":branch,"type":"if","all":true,"rules":[
            {"kind":"state","point":demand,"value":text},
            {"kind":"state","point":boiler,"value":value},
            {"kind":"state","point":target,"value":!value}
        ]}));
        nodes.push(json!({"id":set,"type":"set","point":target,"value":value}));
        edges.push(json!({"from":previous,"port":port,"to":branch}));
        edges.push(json!({"from":branch,"port":"yes","to":set}));
    }
    serde_json::from_value(json!({"nodes":nodes,"edges":edges})).expect("built-in daily graph")
}
// A previously successful sample is not an admission while a newer poll is
// failing or incomplete. This is stricter than one-shot observation freshness.
fn daily_admission(
    hub: &Hub,
    selection: &Selection,
    now: u64,
) -> Result<super::Admission, StatusCode> {
    review_daily(hub, selection, now)
        .map(|(admitted, _)| admitted)
        .map_err(|_| StatusCode::CONFLICT)
}
fn reason_code(reason: super::Reason) -> &'static str {
    use super::Reason;
    match reason {
        Reason::SourceOffline | Reason::DriverStopped => "source_offline",
        Reason::TargetOffline => "target_offline",
        Reason::SourceStale | Reason::MissingDate | Reason::FutureDate => "source_stale",
        Reason::TargetStale => "target_stale",
        Reason::Identity | Reason::SourceMissing | Reason::TargetMissing => "identity",
        Reason::InvalidDemand | Reason::UnknownDemand => "unknown_demand",
        Reason::SourceUnpublished => "source_unpublished",
        _ => "coherence",
    }
}
fn review_daily(
    hub: &Hub,
    selection: &Selection,
    now: u64,
) -> Result<(super::Admission, moli_automation::ObservationSample), &'static str> {
    review_snapshot(&hub.snapshot(), selection, now)
}
pub(super) fn review_snapshot(
    snapshot: &moli_runtime::Snapshot,
    selection: &Selection,
    now: u64,
) -> Result<(super::Admission, moli_automation::ObservationSample), &'static str> {
    let source = find(snapshot, &selection.source).ok_or("identity")?;
    let read = &snapshot
        .diagnostics
        .iter()
        .find(|d| d.instance == source.device.instance && d.read.kind == "netatmo")
        .ok_or("source_unpublished")?
        .read;
    if read.token_expired
        || (read.polling.last_error_at > 0 && read.polling.last_error_at >= read.last_publish_at)
        || read
            .stages
            .values()
            .any(|s| s.last_error_at > 0 && s.last_error_at >= read.last_publish_at)
    {
        return Err("source_error");
    }
    if read.polling.in_flight
        || read
            .stages
            .values()
            .any(|s| s.status == moli_runtime::ReadOutcome::Pending)
    {
        return Err("source_pending");
    }
    if super::admit(snapshot, selection, now)
        .map_err(reason_code)?
        .demand
        == Demand::Unknown
    {
        return Err("unknown_demand");
    }
    let mut admitted = trial::real_snapshot(snapshot, selection, now).map_err(|_| "coherence")?;
    let target = find(snapshot, &selection.target)
        .and_then(|d| d.state.get("on"))
        .ok_or("target_unknown")?;
    let Value::Bool(actual) = target.value else {
        return Err("target_unknown");
    };
    if target.ts == 0 || target.ts > now || now - target.ts >= 65_000 {
        return Err("target_stale");
    }
    admitted.valid_until = admitted.valid_until.min(target.ts.saturating_add(65_000));
    admitted.stamp = json!([admitted.stamp, target]);
    let sample = moli_automation::ObservationSample {
        publication: admitted.collected,
        desired: admitted.demand == Demand::On,
        actual,
        valid_until: admitted.valid_until,
        witness: admitted.stamp.clone(),
    };
    Ok((admitted, sample))
}
impl moli_automation::ObservationSource for AdmissionGate {
    fn review(&self) -> Result<moli_automation::ObservationSample, &'static str> {
        if !self.observer.upgrade().is_some_and(|inner| {
            inner.revision.load(std::sync::atomic::Ordering::Acquire) == self.revision
        }) {
            return Err("selection_changed");
        }
        review_daily(&self.hub, &self.selection, moli_core::now_ms()).map(|(_, sample)| sample)
    }
}
// Trusted read-only admission, rechecked at the native SET boundary. No
// permit can be derived from this object or from the four manual permissions.
struct AdmissionGate {
    observer: std::sync::Weak<Inner>,
    hub: Hub,
    selection: Selection,
    revision: u64,
}
impl std::fmt::Debug for AdmissionGate {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("DailyDraftAdmission(..)")
    }
}
impl moli_automation::DraftAdmission for AdmissionGate {
    fn allows(&self, point: &moli_core::PointId, value: &Value) -> bool {
        let now = moli_core::now_ms();
        self.observer.upgrade().is_some_and(|inner| {
            inner.revision.load(std::sync::atomic::Ordering::Acquire) == self.revision
        }) && point.as_str() == format!("{}/on", self.selection.target)
            && daily_admission(&self.hub, &self.selection, now).is_ok_and(|a| {
                value == &Value::Bool(a.demand == Demand::On)
                    && find(&self.hub.snapshot(), &self.selection.target)
                        .and_then(|d| d.state.get("on"))
                        .is_some_and(|s| {
                            s.ts > 0
                                && s.ts <= now
                                && now - s.ts < 65_000
                                && s.value == Value::Bool(a.demand != Demand::On)
                        })
            })
    }
}
#[derive(Default)]
pub(super) struct Daily {
    id: Option<String>,
    revision: Option<u64>,
}
impl Daily {
    pub(super) fn recover(autos: Option<&Automations>) -> Self {
        Self {
            id: autos.and_then(|autos| {
                autos
                    .list()
                    .into_iter()
                    .find(|a| {
                        a.graph
                            .nodes
                            .iter()
                            .any(|n| n.id == moli_automation::DAILY_HEATING_LOCK)
                    })
                    .map(|a| a.id)
            }),
            revision: None,
        }
    }
}
impl Observer {
    pub(super) async fn daily_view(&self, hub: &Hub) -> JsonValue {
        let daily = self.0.daily.lock().await;
        let stored = self.0.storage.lock().await;
        let admission = stored
            .value
            .selection
            .as_ref()
            .and_then(|s| daily_admission(hub, s, moli_core::now_ms()).ok());
        let draft = self
            .0
            .autos
            .as_ref()
            .and_then(|a| daily.id.as_ref().and_then(|id| a.get(id)));
        let ready = daily.revision == Some(stored.value.revision)
            && stored.valid
            && stored.path.is_some()
            && admission.is_some()
            && draft.as_ref().is_some_and(|a| {
                !a.enabled
                    && !a.is_approved()
                    && a.mode == Mode::Single
                    && stored
                        .value
                        .selection
                        .as_ref()
                        .zip(admission.as_ref())
                        .is_some_and(|(s, admitted)| a.graph == graph(s, admitted.max_age))
            });
        json!({"revision":stored.value.revision,"epoch":self.0.epoch,"state":"disabled","readiness":if ready {"ready"} else if daily.id.is_some(){"refused"}else{"disabled"},
            "activation_available":false,"reason":"release_locked","already_on_may_remain_on":true,
            "observation":self.0.autos.as_ref().and_then(|autos|daily.id.as_ref().and_then(|id|autos.observation_status(id))).filter(|o|draft.as_ref().is_some_and(|a|a.fingerprint()==o.fingerprint)),
            "draft":draft.map(|a|json!({"id":a.id,"fingerprint":a.fingerprint()}))})
    }
    pub(super) async fn prepare_daily(
        &self,
        hub: &Hub,
        input: Observe,
    ) -> Result<JsonValue, StatusCode> {
        let mut daily = self.0.daily.lock().await;
        let stored = self.0.storage.lock().await;
        if !stored.valid
            || stored.path.is_none()
            || input.epoch != self.0.epoch
            || input.revision != stored.value.revision
        {
            return Err(StatusCode::CONFLICT);
        }
        let selection = stored
            .value
            .selection
            .as_ref()
            .ok_or(StatusCode::CONFLICT)?;
        let admission = daily_admission(hub, selection, moli_core::now_ms())?;
        let autos = self
            .0
            .autos
            .as_ref()
            .ok_or(StatusCode::SERVICE_UNAVAILABLE)?;
        let previous = daily.id.as_ref().and_then(|id| autos.get(id));
        let a = Automation {
            id: previous.as_ref().map_or_else(String::new, |a| a.id.clone()),
            name: moli_i18n::tr!("maison.observation.daily_name"),
            enabled: false,
            mode: Mode::Single,
            graph: graph(selection, admission.max_age),
            author: Author::Agent,
            approved: None,
            approved_version: None,
            note: None,
            created: 0,
            updated: 0,
        };
        if !autos.check(&a.graph).ok() {
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
        autos
            .admit_draft(
                &saved.id,
                &saved.fingerprint(),
                Arc::new(AdmissionGate {
                    observer: Arc::downgrade(&self.0),
                    hub: hub.clone(),
                    selection: selection.clone(),
                    revision: input.revision,
                }),
            )
            .map_err(|_| StatusCode::CONFLICT)?;
        daily.id = Some(saved.id);
        daily.revision = Some(input.revision);
        drop(stored);
        drop(daily);
        Ok(self.daily_view(hub).await)
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
    match observer.prepare_daily(&hub, input).await {
        Ok(value) => Json(value).into_response(),
        Err(status) => (status, Json(json!({"error":"daily_heating_refused"}))).into_response(),
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
    Json(observer.daily_view(&hub).await).into_response()
}
#[derive(serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct WatchRequest {
    revision: u64,
    epoch: u64,
    fingerprint: String,
    action: WatchAction,
}
#[derive(serde::Deserialize)]
#[serde(rename_all = "snake_case")]
enum WatchAction {
    Start,
    Stop,
}
impl Observer {
    async fn watch_daily(&self, hub: &Hub, input: WatchRequest) -> Result<JsonValue, StatusCode> {
        let daily = self.0.daily.lock().await;
        let autos = self
            .0
            .autos
            .as_ref()
            .ok_or(StatusCode::SERVICE_UNAVAILABLE)?;
        let id = daily.id.as_ref().ok_or(StatusCode::CONFLICT)?;
        match input.action {
            WatchAction::Stop => autos.stop_observation(id),
            WatchAction::Start => {
                let stored = self.0.storage.lock().await;
                if !stored.valid
                    || stored.path.is_none()
                    || input.epoch != self.0.epoch
                    || input.revision != stored.value.revision
                    || daily.revision != Some(input.revision)
                {
                    return Err(StatusCode::CONFLICT);
                }
                let selection = stored
                    .value
                    .selection
                    .as_ref()
                    .ok_or(StatusCode::CONFLICT)?;
                let admission = daily_admission(hub, selection, moli_core::now_ms())?;
                let draft = autos.get(id).ok_or(StatusCode::CONFLICT)?;
                if draft.graph != graph(selection, admission.max_age)
                    || draft.fingerprint() != input.fingerprint
                {
                    return Err(StatusCode::CONFLICT);
                }
                autos
                    .start_observation(
                        id,
                        &input.fingerprint,
                        Arc::new(AdmissionGate {
                            observer: Arc::downgrade(&self.0),
                            hub: hub.clone(),
                            selection: selection.clone(),
                            revision: input.revision,
                        }),
                    )
                    .map_err(|_| StatusCode::CONFLICT)?;
            }
        }
        drop(daily);
        Ok(self.daily_view(hub).await)
    }
}
async fn watch(
    State(hub): State<Hub>,
    Extension(observer): Extension<Observer>,
    caller: Caller,
    Json(input): Json<WatchRequest>,
) -> Response {
    if !allowed(&caller) {
        return denied();
    }
    match observer.watch_daily(&hub, input).await {
        Ok(value) => Json(value).into_response(),
        Err(status) => (status, Json(json!({"error":"daily_observation_refused"}))).into_response(),
    }
}

pub(super) fn routes() -> Router<Hub> {
    Router::new()
        .route(
            "/api/observations/netatmo-meross/daily/observation",
            axum::routing::post(watch),
        )
        .route(
            "/api/observations/netatmo-meross/daily",
            get(status).post(prepare),
        )
}
