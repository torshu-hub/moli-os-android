//! Code32 synthetic fixture reused against the production observation adapter.
use super::*;
use moli_automation::Config;
use moli_core::{Access, Action, Device, DeviceId, InstanceId, Kind, PointSpec, Semantic, Value};
use moli_runtime::{
    BoxFuture, Driver, DriverCtx, HubOptions, ReadEvent, ReadSummary, spawn_driver,
};
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};
use std::time::Duration;
use tokio_util::sync::CancellationToken;

const OTHER_FILES: [&str; 4] = [
    "moli.toml",
    "home.json",
    "android-equipment.json",
    "secrets.enc",
];
const SOURCE: &str = "source:thermostat";
const NATIVE: &str = "00000000000000000000000000000001";
const TARGET: &str = "target:00000000000000000000000000000001";
#[derive(Debug)]
struct Fixture {
    target: bool,
    demand: Value,
    collected: Option<i64>,
    online: bool,
    writable: bool,
    commands: Arc<AtomicUsize>,
}
fn point(key: &str, kind: Kind, write: bool) -> PointSpec {
    PointSpec {
        key: key.into(),
        label: key.into(),
        kind,
        access: Access { read: true, write },
        unit: None,
        semantic: Semantic::Other,
    }
}
impl Driver for Fixture {
    fn kind(&self) -> &'static str {
        if self.target { "profile" } else { "netatmo" }
    }
    fn integration(&self) -> Arc<str> {
        Arc::from(if self.target {
            "meross-mss310"
        } else {
            "netatmo"
        })
    }
    fn run<'a>(&'a self, ctx: &'a mut DriverCtx) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(async move {
            for (name, model, points) in [
                (
                    "thermostat",
                    "NATherm1",
                    vec![
                        point("declared_demand", Kind::Text, false),
                        point("boiler_status", Kind::Binary, false),
                        point("freshness", Kind::Text, false),
                        point(
                            "max_age_seconds",
                            Kind::Numeric {
                                min: None,
                                max: None,
                                step: None,
                            },
                            false,
                        ),
                        point(
                            "collected_at",
                            Kind::Numeric {
                                min: None,
                                max: None,
                                step: None,
                            },
                            false,
                        ),
                    ],
                ),
                (
                    NATIVE,
                    "MSS310",
                    vec![
                        point("on", Kind::Binary, self.writable),
                        point("reported_model", Kind::Text, false),
                    ],
                ),
            ] {
                if (name == NATIVE) != self.target {
                    continue;
                }
                ctx.upsert_device(Device {
                    id: ctx.device_id(name),
                    instance: ctx.instance().clone(),
                    native_name: name.into(),
                    manufacturer: None,
                    model: Some(model.into()),
                    description: None,
                    native_room: None,
                    members: Vec::new(),
                    points,
                });
            }
            if self.target {
                let target = ctx.device_id(NATIVE);
                ctx.set_state(&target, "on", Value::Bool(false));
                ctx.set_state(&target, "reported_model", Value::from("mss310"));
                ctx.set_availability(&target, self.online);
            } else {
                ctx.observe_read(
                    "netatmo",
                    ReadEvent::Configuration {
                        enabled: true,
                        credential_present: true,
                        target_selected: true,
                    },
                );
                ctx.observe_read("netatmo", ReadEvent::Started);
                let source = ctx.device_id("thermostat");
                let boiler = match &self.demand {
                    Value::Text(s) if s.as_ref() == "on" => Value::Bool(true),
                    Value::Text(s) if s.as_ref() == "off" => Value::Bool(false),
                    _ => Value::Null,
                };
                ctx.set_state(&source, "boiler_status", boiler);
                ctx.set_state(&source, "declared_demand", self.demand.clone());
                ctx.set_state(&source, "freshness", Value::from("cloud_recent"));
                ctx.set_state(&source, "max_age_seconds", Value::Int(300));
                if let Some(collected) = self.collected {
                    ctx.set_state(&source, "collected_at", Value::Int(collected));
                }
                ctx.set_availability(&source, self.online);
                ctx.observe_read(
                    "netatmo",
                    ReadEvent::Published(ReadSummary {
                        devices: 1,
                        fresh: 1,
                        ..ReadSummary::default()
                    }),
                );
            }
            ctx.ready();
            receive_commands(self, ctx).await;
            Ok(())
        })
    }
}

async fn receive_commands(fixture: &Fixture, ctx: &mut DriverCtx) {
    while let Some(command) = ctx.next_command().await {
        fixture.commands.fetch_add(1, Ordering::SeqCst);
        if command.origin == moli_core::Origin::Automation && !command.automation_authorized() {
            command.reply(Err("scope required".into()));
            continue;
        }
        ctx.set_state(&command.device.id, &command.key, command.value.clone());
        command.reply(Ok(()));
    }
}

static NEXT: AtomicUsize = AtomicUsize::new(0);
struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "moli-observation-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&path).unwrap();
        Self(path)
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
async fn setup(
    demand: Value,
    collected: Option<i64>,
    online: bool,
    writable: bool,
    path: PathBuf,
) -> (
    Hub,
    Automations,
    Observer,
    CancellationToken,
    Arc<AtomicUsize>,
) {
    let hub = Hub::new(HubOptions::default()).unwrap();
    let cancel = CancellationToken::new();
    let commands = Arc::new(AtomicUsize::new(0));
    for target in [false, true] {
        spawn_driver(
            &hub,
            InstanceId::from(if target { "target" } else { "source" }),
            Arc::new(Fixture {
                target,
                demand: demand.clone(),
                collected,
                online: target || online,
                writable,
                commands: commands.clone(),
            }),
            cancel.clone(),
        );
    }
    for _ in 0..100 {
        if hub.device(&DeviceId::from(TARGET)).is_some()
            && hub
                .snapshot()
                .diagnostics
                .iter()
                .any(|d| d.read.last_publish_at > 0)
        {
            break;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    let (autos, _task) = Automations::start(&hub, Config::default(), None, cancel.clone())
        .await
        .unwrap();
    let observer = Observer::open(Some(path), Some(autos.clone()));
    (hub, autos, observer, cancel, commands)
}
async fn select(observer: &Observer, hub: &Hub) -> Observe {
    observer
        .save(
            hub,
            Save {
                revision: 0,
                source: Some(SOURCE.into()),
                target: Some(TARGET.into()),
            },
        )
        .await
        .unwrap();
    Observe {
        revision: 1,
        epoch: observer.0.epoch,
    }
}
#[tokio::test]
async fn original_engine_simulates_known_fresh_demand_without_commands_or_manual_permission() {
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    for writable in [false, true] {
        for (demand, collected, online, intention, reason) in [
            (Value::from("on"), Some(now), true, Some(true), Reason::None),
            (
                Value::from("off"),
                Some(now),
                true,
                Some(false),
                Reason::None,
            ),
            (
                Value::from("unknown"),
                Some(now),
                true,
                None,
                Reason::UnknownDemand,
            ),
            (Value::Null, Some(now), true, None, Reason::InvalidDemand),
            (
                Value::Bool(false),
                Some(now),
                true,
                None,
                Reason::InvalidDemand,
            ),
            (
                Value::from("on"),
                Some(now - 301_000),
                true,
                None,
                Reason::SourceStale,
            ),
            (
                Value::from("on"),
                Some(now + 60000),
                true,
                None,
                Reason::FutureDate,
            ),
            (Value::from("on"), None, true, None, Reason::MissingDate),
            (
                Value::from("on"),
                Some(now),
                false,
                None,
                Reason::SourceOffline,
            ),
        ] {
            let temp = Temp::new();
            let (hub, autos, observer, cancel, commands) =
                setup(demand, collected, online, writable, temp.0.join(FILE)).await;
            let request = select(&observer, &hub).await;
            let first = observer.observe(&hub, Observe { ..request }).await;
            assert_eq!(first.reason, Reason::Restarted);
            assert_eq!(first.intention, None);
            let result = observer.observe(&hub, Observe { ..request }).await;
            assert_eq!(result.reason, reason);
            assert_eq!(result.intention, intention);
            for _ in 0..3 {
                let again = observer.observe(&hub, Observe { ..request }).await;
                assert_eq!(again.intention, intention);
                assert_eq!(again.transitions, result.transitions);
            }
            assert_eq!(commands.load(Ordering::SeqCst), 0);
            assert!(autos.list().is_empty());
            assert!(autos.runs(None, 100).is_empty());
            assert!(
                !hub.journal(100)
                    .iter()
                    .any(|entry| matches!(entry.action, Action::Command { .. }))
            );
            assert!(hub.snapshot().approvals.is_empty());
            cancel.cancel();
        }
    }
}
#[tokio::test]
async fn selection_is_atomic_independent_persistent_and_revision_checked() {
    let temp = Temp::new();
    let path = temp.0.join(FILE);
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    let (hub, autos, observer, cancel, commands) =
        setup(Value::from("on"), Some(now), true, false, path.clone()).await;
    let request = select(&observer, &hub).await;
    let stored = std::fs::read(&path).unwrap();
    assert_eq!(
        observer
            .save(
                &hub,
                Save {
                    revision: 0,
                    source: None,
                    target: None
                }
            )
            .await,
        Err(StatusCode::CONFLICT)
    );
    assert_eq!(
        observer
            .save(
                &hub,
                Save {
                    revision: 1,
                    source: Some(SOURCE.into()),
                    target: Some("target:missing".into())
                }
            )
            .await,
        Err(StatusCode::UNPROCESSABLE_ENTITY)
    );
    assert_eq!(std::fs::read(&path).unwrap(), stored);
    let restarted = Observer::open(Some(path), Some(autos));
    let settings = restarted.settings(&hub).await;
    assert_eq!(settings["selection"]["source"], SOURCE);
    assert_eq!(settings["selection"]["target"], TARGET);
    let result = restarted
        .observe(
            &hub,
            Observe {
                revision: 1,
                epoch: restarted.0.epoch,
            },
        )
        .await;
    assert_eq!(result.reason, Reason::Restarted);
    assert!(result.intention.is_none());
    observer
        .save(
            &hub,
            Save {
                revision: 1,
                source: None,
                target: None,
            },
        )
        .await
        .unwrap();
    let changed = observer.observe(&hub, request).await;
    assert!(changed.intention.is_none());
    assert_eq!(changed.reason, Reason::SelectionChanged);
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    cancel.cancel();
}
#[tokio::test]
async fn corrupt_or_unavailable_storage_never_replaces_selections_or_returns_intentions() {
    let temp = Temp::new();
    let path = temp.0.join(FILE);
    std::fs::write(&path, b"not json").unwrap();
    let hub = Hub::new(HubOptions::default()).unwrap();
    for path in [Some(path.clone()), None] {
        let observer = Observer::open(path, None);
        assert_eq!(observer.settings(&hub).await["storage_available"], false);
        let result = observer
            .observe(
                &hub,
                Observe {
                    revision: 0,
                    epoch: observer.0.epoch,
                },
            )
            .await;
        assert_eq!(result.reason, Reason::Storage);
        assert!(result.intention.is_none());
        assert_eq!(
            observer
                .save(
                    &hub,
                    Save {
                        revision: 0,
                        source: None,
                        target: None
                    }
                )
                .await,
            Err(StatusCode::SERVICE_UNAVAILABLE)
        );
    }
    assert_eq!(std::fs::read(path).unwrap(), b"not json");
}

fn damage(snapshot: &mut Snapshot, mode: usize, now: u64) {
    let si = snapshot
        .devices
        .iter()
        .position(|d| d.device.id == DeviceId::from(SOURCE))
        .unwrap();
    let ti = snapshot
        .devices
        .iter()
        .position(|d| d.device.id == DeviceId::from(TARGET))
        .unwrap();
    match mode {
        0 => {
            snapshot.devices[ti].online = Some(false);
        }
        1 => {
            snapshot
                .drivers
                .iter_mut()
                .find(|d| d.instance == InstanceId::from("source"))
                .unwrap()
                .status = DriverStatus::Stopped;
        }
        2 => {
            snapshot
                .diagnostics
                .iter_mut()
                .find(|d| d.instance == InstanceId::from("source"))
                .unwrap()
                .read
                .last_publish_at = 0;
        }
        3 => {
            snapshot.devices[si].state.get_mut("freshness").unwrap().ts = now + 1000;
        }
        4 => {
            snapshot.devices[ti]
                .state
                .get_mut("reported_model")
                .unwrap()
                .ts = now - 66000;
        }
        5 => {
            snapshot.devices[ti]
                .state
                .get_mut("reported_model")
                .unwrap()
                .value = Value::from("different");
        }
        _ => {
            snapshot.devices[si]
                .state
                .get_mut("max_age_seconds")
                .unwrap()
                .value = Value::Int(120);
            snapshot.devices[si]
                .state
                .get_mut("collected_at")
                .unwrap()
                .value = Value::Int(i64::try_from(now - 121_000).unwrap());
        }
    }
}
#[tokio::test]
async fn admission_checks_collection_contract_identity_driver_and_publication() {
    let temp = Temp::new();
    let now = moli_core::now_ms();
    let (hub, _autos, observer, cancel, commands) = setup(
        Value::from("on"),
        Some(i64::try_from(now).unwrap()),
        true,
        true,
        temp.0.join(FILE),
    )
    .await;
    let request = select(&observer, &hub).await;
    let selection = observer
        .0
        .storage
        .lock()
        .await
        .value
        .selection
        .clone()
        .unwrap();
    let now = moli_core::now_ms();
    let admitted = admit(&hub.snapshot(), &selection, now).unwrap();
    for (mode, reason) in [
        (0, Reason::TargetOffline),
        (1, Reason::DriverStopped),
        (2, Reason::SourceUnpublished),
        (3, Reason::FutureDate),
        (4, Reason::TargetStale),
        (5, Reason::Identity),
        (6, Reason::SourceStale),
    ] {
        let mut snapshot = hub.snapshot();
        damage(&mut snapshot, mode, now);
        assert_eq!(admit(&snapshot, &selection, now), Err(reason));
    }
    let mut snapshot = hub.snapshot();
    snapshot
        .devices
        .iter_mut()
        .find(|d| d.device.id == DeviceId::from(SOURCE))
        .unwrap()
        .state
        .get_mut("declared_demand")
        .unwrap()
        .value = Value::from("off");
    assert_ne!(admit(&snapshot, &selection, now).unwrap(), admitted);
    let mut partial = hub.snapshot();
    let published = partial
        .diagnostics
        .iter()
        .find(|d| d.instance == InstanceId::from("source"))
        .unwrap()
        .read
        .last_publish_at;
    partial
        .devices
        .iter_mut()
        .find(|d| d.device.id == DeviceId::from(SOURCE))
        .unwrap()
        .state
        .get_mut("declared_demand")
        .unwrap()
        .ts = published + 1;
    assert_eq!(
        admit(&partial, &selection, now.max(published + 1)),
        Err(Reason::Coherence)
    );
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    assert_eq!(request.revision, 1);
    cancel.cancel();
}
#[tokio::test]
async fn selection_and_driver_changes_during_dry_run_discard_the_result() {
    for change_selection in [true, false] {
        let temp = Temp::new();
        let now = i64::try_from(moli_core::now_ms()).unwrap();
        let (hub, autos, observer, cancel, commands) =
            setup(Value::from("on"), Some(now), true, true, temp.0.join(FILE)).await;
        let request = select(&observer, &hub).await;
        observer.observe(&hub, Observe { ..request }).await;
        let (reached, ready) = tokio::sync::oneshot::channel();
        let (resume, wait) = tokio::sync::oneshot::channel();
        *observer.0.gate.lock().await = Some((reached, wait));
        let cloned = observer.clone();
        let model = hub.clone();
        let pending = tokio::spawn(async move { cloned.observe(&model, request).await });
        ready.await.unwrap();
        if change_selection {
            observer
                .save(
                    &hub,
                    Save {
                        revision: 1,
                        source: None,
                        target: None,
                    },
                )
                .await
                .unwrap();
        } else {
            cancel.cancel();
            for _ in 0..100 {
                if hub
                    .snapshot()
                    .drivers
                    .iter()
                    .any(|d| d.status == DriverStatus::Stopped)
                {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        }
        resume.send(()).unwrap();
        let result = pending.await.unwrap();
        assert!(result.intention.is_none());
        if change_selection {
            assert_eq!(result.reason, Reason::SelectionChanged);
        } else {
            assert_ne!(result.reason, Reason::None);
        }
        assert_eq!(commands.load(Ordering::SeqCst), 0);
        assert!(autos.list().is_empty());
        assert!(autos.runs(None, 100).is_empty());
        cancel.cancel();
    }
}
#[tokio::test]
async fn engine_restart_keeps_selection_but_never_replays_a_previous_intention() {
    let temp = Temp::new();
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    let (hub, _autos, observer, cancel, commands) =
        setup(Value::from("on"), Some(now), true, false, temp.0.join(FILE)).await;
    let request = select(&observer, &hub).await;
    observer.observe(&hub, Observe { ..request }).await;
    assert_eq!(observer.observe(&hub, request).await.intention, Some(true));
    cancel.cancel();
    let (hub, _autos, observer, cancel, after) = setup(
        Value::from("off"),
        Some(now),
        true,
        false,
        temp.0.join(FILE),
    )
    .await;
    let request = Observe {
        revision: 1,
        epoch: observer.0.epoch,
    };
    let first = observer.observe(&hub, Observe { ..request }).await;
    assert!(first.intention.is_none());
    assert_eq!(first.reason, Reason::Restarted);
    assert_eq!(observer.observe(&hub, request).await.intention, Some(false));
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    assert_eq!(after.load(Ordering::SeqCst), 0);
    cancel.cancel();
}

async fn http(
    addr: std::net::SocketAddr,
    method: &str,
    path: &str,
    body: JsonValue,
) -> (u16, JsonValue) {
    let (status, body, _) = http_cookie(addr, method, path, body, "").await;
    (status, body)
}
async fn http_cookie(
    addr: std::net::SocketAddr,
    method: &str,
    path: &str,
    body: JsonValue,
    cookie: &str,
) -> (u16, JsonValue, Option<String>) {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let body = if method == "GET" {
        String::new()
    } else {
        body.to_string()
    };
    let request = format!(
        "{method} {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: application/json\r\nx-moli-origin: ui\r\nCookie: {cookie}\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    );
    let mut stream = tokio::net::TcpStream::connect(addr).await.unwrap();
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut bytes = Vec::new();
    stream.read_to_end(&mut bytes).await.unwrap();
    let response = String::from_utf8(bytes).unwrap();
    let (head, body) = response.split_once("\r\n\r\n").unwrap();
    (
        head.split_whitespace().nth(1).unwrap().parse().unwrap(),
        serde_json::from_str(body).unwrap_or(JsonValue::Null),
        head.lines().find_map(|line| {
            line.strip_prefix("set-cookie: ")
                .map(|v| v.split(';').next().unwrap().to_owned())
        }),
    )
}
async fn assert_daily_locked_http(address: std::net::SocketAddr, path: &str, saved: &JsonValue) {
    let daily_path = format!("{path}/daily");
    let (status, daily) = http(address, "GET", &daily_path, JsonValue::Null).await;
    assert_eq!(status, 200);
    assert_eq!(daily["state"], "disabled");
    assert_eq!(daily["activation_available"], false);
    // No wire flag can turn preparation into approval, execution or arming.
    for flag in ["enabled", "activate", "dry", "approved"] {
        let mut body = json!({"revision":1,"epoch":saved["epoch"]});
        body[flag] = json!(true);
        assert_eq!(http(address, "POST", &daily_path, body).await.0, 422);
    }
}
#[tokio::test]
async fn http_surface_has_no_real_execution_flag_and_keeps_other_configuration_untouched() {
    let temp = Temp::new();
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    let (hub, autos, _unused, cancel, commands) =
        setup(Value::from("on"), Some(now), true, false, temp.0.join(FILE)).await;
    for name in OTHER_FILES {
        std::fs::write(temp.0.join(name), b"unchanged synthetic configuration").unwrap();
    }
    let router = crate::router(
        hub.clone(),
        cancel.clone(),
        &Options {
            home_path: Some(temp.0.join("home.json")),
            automations: Some(autos.clone()),
            ..Options::default()
        },
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let shutdown = cancel.clone();
    tokio::spawn(async move {
        axum::serve(
            listener,
            router.into_make_service_with_connect_info::<std::net::SocketAddr>(),
        )
        .with_graceful_shutdown(shutdown.cancelled_owned())
        .await
        .unwrap();
    });
    let path = "/api/observations/netatmo-meross";
    let (status, settings) = http(address, "GET", path, JsonValue::Null).await;
    assert_eq!(status, 200);
    assert_eq!(settings["sources"].as_array().unwrap().len(), 1);
    assert_eq!(settings["targets"].as_array().unwrap().len(), 1);
    let (status, saved) = http(
        address,
        "PUT",
        path,
        json!({"revision":0,"source":SOURCE,"target":TARGET}),
    )
    .await;
    assert_eq!(status, 200);
    assert_eq!(saved["revision"], 1);
    let observe = format!("{path}/observe");
    let input = json!({"revision":1,"epoch":saved["epoch"]});
    let (status, first) = http(address, "POST", &observe, input.clone()).await;
    assert_eq!(status, 200);
    assert!(first["intention"].is_null());
    let (status, result) = http(address, "POST", &observe, input).await;
    assert_eq!(status, 200);
    assert_eq!(result["intention"], true);
    assert_eq!(result["witness"].as_array().unwrap().len(), 5);
    assert_eq!(
        http(
            address,
            "POST",
            &observe,
            json!({"revision":1,"epoch":saved["epoch"],"dry":false})
        )
        .await
        .0,
        422
    );
    assert_eq!(
        http(
            address,
            "PUT",
            path,
            json!({"revision":1,"source":SOURCE,"target":TARGET,"manual_control":true})
        )
        .await
        .0,
        422
    );
    assert_daily_locked_http(address, path, &saved).await;
    for name in OTHER_FILES {
        assert_eq!(
            std::fs::read(temp.0.join(name)).unwrap(),
            b"unchanged synthetic configuration"
        );
    }
    assert_eq!(
        http(
            address,
            "PUT",
            path,
            json!({"revision":1,"source":"x".repeat(MAX_FILE),"target":TARGET})
        )
        .await
        .0,
        413
    );
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    assert!(autos.list().is_empty());
    assert!(autos.runs(None, 100).is_empty());
    assert!(!allowed(&Caller {
        via: Via::Visitor,
        key: "synthetic".into(),
        person: None
    }));
    cancel.cancel();
}

mod supervised;

mod daily;
