//! Qualification prototype only: no API, installed graph or background observer.
//! Explicit synthetic selections, snapshot admission and the original dry-run engine.
use moli_automation::{Actor, Author, Automation, Automations, Config, Graph, Mode, StepStatus};
use moli_core::{Access, Action, Device, DeviceId, InstanceId, Kind, PointSpec, Semantic, Value};
use moli_runtime::{BoxFuture, Driver, DriverCtx, Hub, HubOptions, spawn_driver};
use serde_json::json;
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};
use std::time::Duration;
use tokio_util::sync::CancellationToken;

const SOURCE: &str = "fixture:thermostat";
const TARGET: &str = "fixture:plug";
#[derive(Debug)]
struct Fixture {
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
        "fixture"
    }
    fn run<'a>(&'a self, ctx: &'a mut DriverCtx) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(async move {
            for (name, model, points) in [
                (
                    "thermostat",
                    "NATherm1",
                    vec![
                        point("declared_demand", Kind::Text, false),
                        point("freshness", Kind::Text, false),
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
                    "plug",
                    "MSS310",
                    vec![point("on", Kind::Binary, self.writable)],
                ),
            ] {
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
            let source = ctx.device_id("thermostat");
            ctx.set_state(&source, "declared_demand", self.demand.clone());
            ctx.set_state(&source, "freshness", Value::from("cloud_recent"));
            if let Some(collected) = self.collected {
                ctx.set_state(&source, "collected_at", Value::Int(collected));
            }
            ctx.set_availability(&source, self.online);
            ctx.set_state(&ctx.device_id("plug"), "on", Value::Bool(false));
            ctx.set_availability(&ctx.device_id("plug"), true);
            ctx.ready();
            while let Some(command) = ctx.next_command().await {
                self.commands.fetch_add(1, Ordering::SeqCst);
                command.reply(Ok(()));
            }
            Ok(())
        })
    }
}

// Admission is intentionally kept in this qualification fixture. It must move
// to a dedicated, coherent, server-side observation path before tablet exposure.
fn graph(hub: &Hub, source: &str, target: &str, now: u64) -> Option<Graph> {
    let snapshot = hub.snapshot();
    let source = snapshot
        .devices
        .iter()
        .find(|d| d.device.id == DeviceId::from(source))?;
    let target = snapshot
        .devices
        .iter()
        .find(|d| d.device.id == DeviceId::from(target))?;
    if source.online != Some(true)
        || target.online != Some(true)
        || source.device.model.as_deref() != Some("NATherm1")
        || target.device.model.as_deref() != Some("MSS310")
    {
        return None;
    }
    // Generic Moli comparisons intentionally equate OFF and false. This
    // integration's contract requires the declared text enum, never a boolean
    // accidentally published under that point key.
    if !source.state.get("declared_demand").is_some_and(|sample| {
        matches!(&sample.value, Value::Text(value) if matches!(value.as_ref(), "on" | "off" | "unknown"))
    }) {
        return None;
    }
    let demand = format!("{}/declared_demand", source.device.id);
    let quality = format!("{}/freshness", source.device.id);
    let collected = format!("{}/collected_at", source.device.id);
    let target = format!("{}/on", target.device.id);
    serde_json::from_value(json!({"nodes":[
        {"id":"start","type":"manual"},
        {"id":"fresh","type":"if","all":true,"rules":[
            {"kind":"state","point":quality,"value":"cloud_recent"},
            {"kind":"state","point":collected,"op":"ge","value":now.saturating_sub(300_000)},
            {"kind":"state","point":collected,"op":"le","value":now}
        ]},
        {"id":"demand_on","type":"if","all":true,"rules":[{"kind":"state","point":demand,"value":"on"}]},
        {"id":"demand_off","type":"if","all":true,"rules":[{"kind":"state","point":demand,"value":"off"}]},
        {"id":"would_on","type":"set","point":target,"value":true},
        {"id":"would_off","type":"set","point":target,"value":false}
    ],"edges":[
        {"from":"start","to":"fresh"},
        {"from":"fresh","port":"yes","to":"demand_on"},
        {"from":"demand_on","port":"yes","to":"would_on"},
        {"from":"demand_on","port":"no","to":"demand_off"},
        {"from":"demand_off","port":"yes","to":"would_off"}
    ]})).ok()
}

#[tokio::test]
async fn original_engine_simulates_known_fresh_demand_without_commands_or_manual_permission() {
    let now = moli_core::now_ms();
    let now_i64 = i64::try_from(now).unwrap();
    for writable in [false, true] {
        for (demand, collected, online, intention) in [
            (Value::from("on"), Some(now_i64), true, Some("would_on")),
            (Value::from("off"), Some(now_i64), true, Some("would_off")),
            (Value::from("unknown"), Some(now_i64), true, None),
            (Value::Null, Some(now_i64), true, None),
            (Value::Bool(false), Some(now_i64), true, None),
            (Value::from("on"), Some(now_i64 - 301_000), true, None),
            (Value::from("on"), Some(now_i64 + 60_000), true, None),
            (Value::from("on"), None, true, None),
            (Value::from("on"), Some(now_i64), false, None),
        ] {
            let hub = Hub::new(HubOptions::default()).unwrap();
            let cancel = CancellationToken::new();
            let commands = Arc::new(AtomicUsize::new(0));
            spawn_driver(
                &hub,
                InstanceId::from("fixture"),
                Arc::new(Fixture {
                    demand,
                    collected,
                    online,
                    writable,
                    commands: commands.clone(),
                }),
                cancel.clone(),
            );
            for _ in 0..100 {
                if hub
                    .device(&DeviceId::from(TARGET))
                    .is_some_and(|d| d.online == Some(true))
                {
                    break;
                }
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
            let (autos, _task) = Automations::start(&hub, Config::default(), None, cancel.clone())
                .await
                .unwrap();
            let actor = Actor {
                human: false,
                author: Author::Agent,
            };
            assert!(graph(&hub, SOURCE, "fixture:missing", now).is_none());
            if let Some(graph) = graph(&hub, SOURCE, TARGET, now) {
                let draft = autos
                    .save(
                        Automation {
                            id: String::new(),
                            name: "Synthetic observation".into(),
                            enabled: false,
                            mode: Mode::Restart,
                            graph,
                            author: Author::Agent,
                            approved: None,
                            approved_version: None,
                            note: None,
                            created: 0,
                            updated: 0,
                        },
                        actor,
                    )
                    .await
                    .unwrap();
                assert!(!draft.enabled);
                assert!(!draft.is_approved());
                for _ in 0..3 {
                    let run = autos.run_now(&draft.id, true, None, actor).await.unwrap();
                    assert!(run.dry);
                    let simulated: Vec<_> = run
                        .steps
                        .iter()
                        .filter(|s| s.status == StepStatus::Simulated)
                        .map(|s| s.node.as_str())
                        .collect();
                    assert_eq!(simulated, intention.into_iter().collect::<Vec<_>>());
                }
            } else {
                assert!(intention.is_none());
            }
            assert_eq!(commands.load(Ordering::SeqCst), 0);
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
