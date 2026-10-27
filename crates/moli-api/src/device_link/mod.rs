//! A simple source → output rule stored and executed by Moli's original engine.
use crate::{
    Options,
    caller::{Caller, Via},
};
use axum::{
    Extension, Json, Router,
    extract::State,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::get,
};
use moli_automation::{
    Actor, Author, Automation, Automations, CommandAdmission, DEVICE_LINK, Graph, Mode,
};
use moli_core::{DeviceId, DriverStatus, PointId, Value};
use moli_runtime::{DeviceView, Hub, Snapshot};
use serde::Deserialize;
use serde_json::{Value as JsonValue, json};
use std::sync::Arc;
use tokio::sync::Mutex;
#[cfg(test)]
mod tests;
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
struct Selection {
    source: String,
    target: String,
}
#[derive(Clone)]
struct Links {
    autos: Option<Automations>,
    write: Arc<Mutex<()>>,
    defaults: Option<Selection>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Save {
    source: String,
    target: String,
    enabled: bool,
    fingerprint: Option<String>,
}
fn selected(a: &Automation) -> Option<Selection> {
    let trigger = a.graph.node(DEVICE_LINK)?;
    let moli_automation::Step::WhenState { point, .. } = &trigger.step else {
        return None;
    };
    let source = point.strip_suffix("/collected_at")?.to_owned();
    let target = a.graph.nodes.iter().find_map(|n| {
        if let moli_automation::Step::Set { point, .. } = &n.step {
            point.strip_suffix("/on").map(str::to_owned)
        } else {
            None
        }
    })?;
    Some(Selection { source, target })
}
fn graph(s: &Selection) -> Graph {
    let source = &s.source;
    let target = format!("{}/on", s.target);
    serde_json::from_value(json!({"nodes":[
        {"id":DEVICE_LINK,"type":"when_state","point":format!("{source}/collected_at")},
        {"id":"clock","type":"every","minutes":1},
        {"id":"fresh","type":"if","rules":[{"kind":"stale","point":format!("{source}/boiler_status"),"for_s":300}]},
        {"id":"on","type":"if","all":true,"rules":[{"kind":"state","point":format!("{source}/boiler_status"),"value":true},{"kind":"state","point":format!("{source}/freshness"),"value":"cloud_recent"}]},
        {"id":"off","type":"if","all":true,"rules":[{"kind":"state","point":format!("{source}/boiler_status"),"value":false},{"kind":"state","point":format!("{source}/freshness"),"value":"cloud_recent"}]},
        {"id":"set_on","type":"set","point":target,"value":true},
        {"id":"set_off","type":"set","point":target,"value":false}],"edges":[
        {"from":DEVICE_LINK,"to":"fresh"},{"from":"clock","to":"fresh"},
        {"from":"fresh","port":"no","to":"on"},{"from":"on","port":"yes","to":"set_on"},
        {"from":"on","port":"no","to":"off"},{"from":"off","port":"yes","to":"set_off"}
    ]})).expect("built-in device link")
}
#[derive(Debug)]
struct LinkPolicy {
    hub: Hub,
    selection: Selection,
}
impl LinkPolicy {
    fn demand(&self) -> Option<bool> {
        let snap = self.hub.snapshot();
        let s = find(&snap, &self.selection.source)?;
        let t = find(&snap, &self.selection.target)?;
        if !candidate(&snap, s, true)
            || !candidate(&snap, t, false)
            || s.online != Some(true)
            || t.online != Some(true)
            || !t.device.point("on").is_some_and(|p| p.access.write)
            || [s, t].iter().any(|d| {
                !snap
                    .drivers
                    .iter()
                    .any(|r| r.instance == d.device.instance && r.status == DriverStatus::Running)
            })
        {
            return None;
        }
        let Value::Text(quality) = &s.state.get("freshness")?.value else {
            return None;
        };
        if quality.as_ref() != "cloud_recent" {
            return None;
        }
        let Value::Int(age) = s.state.get("max_age_seconds")?.value else {
            return None;
        };
        let age = u64::try_from(age).ok()?.clamp(1, 300) * 1000;
        let now = moli_core::now_ms();
        let Value::Int(collected) = s.state.get("collected_at")?.value else {
            return None;
        };
        let collected = u64::try_from(collected).ok()?;
        if collected == 0 || collected > now || now - collected >= age {
            return None;
        }
        let sample = s.state.get("boiler_status")?;
        if sample.ts == 0 || sample.ts > now || now - sample.ts >= age {
            return None;
        }
        let Value::Bool(demand) = sample.value else {
            return None;
        };
        if !matches!(s.state.get("declared_demand").map(|v|&v.value),Some(Value::Text(v)) if v.as_ref()==if demand {"on"} else {"off"})
        {
            return None;
        }
        Some(demand)
    }
}
impl CommandAdmission for LinkPolicy {
    fn allows(&self, point: &PointId, value: &Value) -> bool {
        point.as_str() == format!("{}/on", self.selection.target)
            && self.demand().is_some_and(|d| *value == Value::Bool(d))
    }
}
impl Links {
    fn current(&self) -> Option<Automation> {
        self.autos
            .as_ref()?
            .list()
            .into_iter()
            .find(|a| selected(a).is_some())
    }
    fn register(&self, hub: &Hub, a: &Automation) {
        if let (Some(autos), Some(selection)) = (&self.autos, selected(a)) {
            let _ = autos.admit_link(
                &a.id,
                &a.fingerprint(),
                Arc::new(LinkPolicy {
                    hub: hub.clone(),
                    selection,
                }),
            );
        }
    }
    fn view(&self, hub: &Hub) -> JsonValue {
        let snap = hub.snapshot();
        let a = self.current();
        let selection = a
            .as_ref()
            .and_then(selected)
            .or_else(|| self.defaults.clone());
        let demand = selection.as_ref().and_then(|s| {
            LinkPolicy {
                hub: hub.clone(),
                selection: s.clone(),
            }
            .demand()
        });
        let actual = selection
            .as_ref()
            .and_then(|s| find(&snap, &s.target))
            .filter(|d| {
                d.online == Some(true)
                    && snap.drivers.iter().any(|r| {
                        r.instance == d.device.instance && r.status == DriverStatus::Running
                    })
            })
            .and_then(|d| d.state.get("on"))
            .filter(|s| {
                let now = moli_core::now_ms();
                s.ts > 0 && s.ts <= now && now - s.ts < 65_000
            })
            .and_then(|s| {
                if let Value::Bool(value) = s.value {
                    Some(value)
                } else {
                    None
                }
            });
        let enabled = a.as_ref().is_some_and(Automation::is_live);
        let items = |source| {
            snap.devices
                .iter()
                .filter(|d| candidate(&snap, d, source))
                .take(64)
                .map(|d| json!({"id":d.device.id,"name":name(d)}))
                .collect::<Vec<_>>()
        };
        json!({"sources":items(true),"targets":items(false),"source":selection.as_ref().map(|s|&s.source),"target":selection.as_ref().map(|s|&s.target),"fingerprint":a.as_ref().map(Automation::fingerprint),"enabled":enabled,"demand":demand,"actual":actual,"state":if !enabled {"disabled"}else if demand.is_none(){"waiting"}else{"active"},"available":self.autos.is_some()})
    }
    async fn save(&self, hub: &Hub, input: Save) -> Result<(), StatusCode> {
        let _one = self.write.lock().await;
        let autos = self.autos.as_ref().ok_or(StatusCode::SERVICE_UNAVAILABLE)?;
        let previous = self.current();
        if previous.as_ref().map(Automation::fingerprint) != input.fingerprint {
            return Err(StatusCode::CONFLICT);
        }
        let selection = Selection {
            source: input.source,
            target: input.target,
        };
        let human = Actor {
            human: true,
            author: Author::Human,
        };
        if !input.enabled
            && let Some(a) = &previous
        {
            autos
                .set_enabled(&a.id, false, None, human)
                .await
                .map_err(|_| StatusCode::CONFLICT)?;
            if selected(a).as_ref() == Some(&selection) {
                return Ok(());
            }
        }
        let snapshot = hub.snapshot();
        if !find(&snapshot, &selection.source).is_some_and(|d| candidate(&snapshot, d, true))
            || !find(&snapshot, &selection.target).is_some_and(|d| candidate(&snapshot, d, false))
        {
            return Err(StatusCode::UNPROCESSABLE_ENTITY);
        }
        if input.enabled
            && !find(&snapshot, &selection.target)
                .is_some_and(|d| d.device.point("on").is_some_and(|p| p.access.write))
        {
            return Err(StatusCode::FORBIDDEN);
        }
        let human = Actor {
            human: true,
            author: Author::Human,
        };
        if let Some(a) = &previous {
            autos
                .set_enabled(&a.id, false, None, human)
                .await
                .map_err(|_| StatusCode::CONFLICT)?;
        }
        let a = Automation {
            id: previous.as_ref().map_or(String::new(), |a| a.id.clone()),
            name: moli_i18n::tr!("maison.link.title"),
            enabled: false,
            mode: Mode::Restart,
            graph: graph(&selection),
            author: Author::Human,
            approved: None,
            approved_version: None,
            note: None,
            created: 0,
            updated: 0,
        };
        let saved = autos
            .save(a, human)
            .await
            .map_err(|_| StatusCode::CONFLICT)?;
        self.register(hub, &saved);
        if input.enabled {
            let enabled = autos
                .approve(&saved.id, &saved.fingerprint(), human)
                .await
                .map_err(|_| StatusCode::CONFLICT)?;
            autos
                .run_now(&enabled.id, false, Some(&enabled.fingerprint()), human)
                .await
                .map_err(|_| StatusCode::CONFLICT)?;
        }
        Ok(())
    }
}
fn allowed(c: &Caller) -> bool {
    c.is_household() && !matches!(c.via, Via::Visitor)
}
fn refused(reason: &str, status: StatusCode) -> Response {
    (status, Json(json!({"reason":reason}))).into_response()
}
async fn settings(
    State(hub): State<Hub>,
    Extension(links): Extension<Links>,
    caller: Caller,
) -> Response {
    if !allowed(&caller) {
        return refused("access", StatusCode::FORBIDDEN);
    }
    Json(links.view(&hub)).into_response()
}
async fn save(
    State(hub): State<Hub>,
    Extension(links): Extension<Links>,
    Extension(humans): Extension<Arc<crate::session::Sessions>>,
    caller: Caller,
    headers: HeaderMap,
    Json(input): Json<Save>,
) -> Response {
    if !allowed(&caller) || !humans.is_human(&headers, &caller.key) {
        return refused("human_session", StatusCode::FORBIDDEN);
    }
    match links.save(&hub, input).await {
        Ok(()) => Json(links.view(&hub)).into_response(),
        Err(status) => refused(
            if status == StatusCode::FORBIDDEN {
                "permission"
            } else if status == StatusCode::CONFLICT {
                "changed"
            } else {
                "unavailable"
            },
            status,
        ),
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Refresh {
    source: String,
}
async fn refresh(State(hub): State<Hub>, caller: Caller, Json(input): Json<Refresh>) -> Response {
    if !allowed(&caller) {
        return refused("access", StatusCode::FORBIDDEN);
    }
    let snap = hub.snapshot();
    let Some(source) = find(&snap, &input.source).filter(|d| candidate(&snap, d, true)) else {
        return refused("unavailable", StatusCode::CONFLICT);
    };
    Json(hub.request_read_refresh(&source.device.instance)).into_response()
}
pub(crate) fn routes(options: &Options, hub: &Hub) -> Router<Hub> {
    let links = Links {
        autos: options.automations.clone(),
        write: Arc::new(Mutex::new(())),
        defaults: options
            .home_path
            .as_deref()
            .and_then(std::path::Path::parent)
            .and_then(|dir| {
                let path = dir.join("netatmo-meross-observation.json");
                if std::fs::metadata(&path).ok()?.len() > 4096 {
                    return None;
                }
                let value: JsonValue = serde_json::from_slice(&std::fs::read(path).ok()?).ok()?;
                serde_json::from_value(value.get("selection")?.clone()).ok()
            }),
    };
    if let Some(a) = links.current() {
        links.register(hub, &a);
    }
    Router::new()
        .route("/api/device-link", get(settings).put(save))
        .route("/api/device-link/refresh", axum::routing::post(refresh))
        .layer(axum::extract::DefaultBodyLimit::max(4096))
        .layer(Extension(links))
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
