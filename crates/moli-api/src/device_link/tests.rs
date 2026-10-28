//! Reused synthetic driver: native rule → original engine → scoped command.
use super::*;
use moli_automation::Config;
use moli_core::{Access, Device, InstanceId, Kind, PointSpec, Semantic, Value};
use moli_runtime::{
    BoxFuture, Driver, DriverCtx, HubOptions, ReadEvent, ReadSummary, spawn_driver,
};
use std::path::PathBuf;
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};
use std::time::Duration;
use tokio_util::sync::CancellationToken;

const SOURCE: &str = "source:thermostat";
const NATIVE: &str = "00000000000000000000000000000001";
const TARGET: &str = "target:00000000000000000000000000000001";
#[derive(Debug)]
enum FixtureUpdate {
    Publish(Option<bool>, bool),
    Offline,
}
type Updates = Arc<tokio::sync::Mutex<tokio::sync::mpsc::UnboundedReceiver<FixtureUpdate>>>;
#[derive(Debug)]
struct Fixture {
    target: bool,
    demand: Value,
    collected: Option<i64>,
    online: bool,
    writable: bool,
    commands: Arc<AtomicUsize>,
    updates: Option<Updates>,
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
    let mut updates = match &fixture.updates {
        Some(updates) => Some(updates.lock().await),
        None => None,
    };
    loop {
        tokio::select! {
            command=ctx.next_command()=>{
                let Some(command)=command else {break;};
                fixture.commands.fetch_add(1, Ordering::SeqCst);
                if command.origin == moli_core::Origin::Automation {
                    // Match the existing Meross transport's in-flight invalidation.
                    // Its own write must not revoke a still-valid source permit.
                    ctx.set_state(&command.device.id,&command.key,Value::Null);
                    if !command.automation_authorized() { command.reply(Err("scope required".into())); continue; }
                }
                ctx.set_state(&command.device.id,&command.key,command.value.clone()); command.reply(Ok(()));
            }
            update=async{match &mut updates {Some(rx)=>rx.recv().await,None=>std::future::pending().await}}=>{
                let Some(update)=update else {break;};
                let source=ctx.device_id("thermostat");
                match update {
                    FixtureUpdate::Publish(demand,fresh)=>{
                        ctx.observe_read("netatmo",ReadEvent::Cycle {period_seconds:120,trigger:moli_runtime::ReadTrigger::Deadline,late_ms:0});
                        ctx.set_availability(&source,true);
                        ctx.set_state(&source,"boiler_status",demand.map_or(Value::Null,Value::Bool));
                        ctx.set_state(&source,"declared_demand",Value::from(match demand {Some(true)=>"on",Some(false)=>"off",None=>"unknown"}));
                        let now=moli_core::now_ms();
                        ctx.set_state(&source,"collected_at",Value::Int(i64::try_from(if fresh {now}else{now-301_000}).unwrap()));
                        ctx.set_state(&source,"freshness",Value::from("cloud_recent"));
                        ctx.set_state(&source,"max_age_seconds",Value::Int(300));
                        ctx.observe_read("netatmo",ReadEvent::Published(ReadSummary{devices:1,fresh:1,..ReadSummary::default()}));
                        ctx.observe_read("netatmo",ReadEvent::Scheduled {delay_seconds:120,next_due_at:now+120_000,reason:moli_runtime::ReadOutcome::Ok,duration_ms:1});
                    }
                    FixtureUpdate::Offline=>{
                        ctx.observe_read("netatmo",ReadEvent::Stage(moli_runtime::ReadStage::Homestatus,moli_runtime::ReadOutcome::Transport));
                        ctx.set_availability(&source,false);
                    }

                }
            }
        }
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

async fn wait(hub: &Hub, key: &str, value: Value) {
    for _ in 0..200 {
        if hub
            .state(&PointId::from(key))
            .is_some_and(|s| s.value == value)
        {
            return;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    panic!("synthetic state not published: {key}, wanted {value:?}");
}
// One fixture/sequence covers the changed rule, rather than duplicate setups.
#[allow(clippy::too_many_lines)]
#[tokio::test]
async fn native_rule_applies_on_off_refuses_unavailable_and_stops_without_replay() {
    let temp = Temp::new();
    let hub = Hub::new(HubOptions::default()).unwrap();
    let cancel = CancellationToken::new();
    let commands = Arc::new(AtomicUsize::new(0));
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    let updates = Arc::new(Mutex::new(rx));
    for target in [false, true] {
        spawn_driver(
            &hub,
            InstanceId::from(if target { "target" } else { "source" }),
            Arc::new(Fixture {
                target,
                demand: Value::from("on"),
                collected: Some(i64::try_from(moli_core::now_ms()).unwrap()),
                online: true,
                writable: true,
                commands: commands.clone(),
                updates: if target { None } else { Some(updates.clone()) },
            }),
            cancel.clone(),
        );
    }
    wait(
        &hub,
        &format!("{SOURCE}/declared_demand"),
        Value::from("on"),
    )
    .await;
    wait(&hub, &format!("{TARGET}/on"), Value::Bool(false)).await;
    let (autos, task) = Automations::start(&hub, Config::default(), Some(&temp.0), cancel.clone())
        .await
        .unwrap();
    let links = Links {
        autos: Some(autos.clone()),
        write: Arc::new(Mutex::new(())),
        defaults: Some(Selection {
            source: SOURCE.into(),
            target: TARGET.into(),
        }),
    };
    assert!(!links.view(&hub)["enabled"].as_bool().unwrap());
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    links
        .save(
            &hub,
            Save {
                source: SOURCE.into(),
                target: TARGET.into(),
                enabled: true,
                fingerprint: None,
            },
        )
        .await
        .unwrap();
    wait(&hub, &format!("{TARGET}/on"), Value::Bool(true)).await;
    assert!(autos.list()[0].is_live());
    tx.send(FixtureUpdate::Publish(Some(false), true)).unwrap();
    wait(&hub, &format!("{TARGET}/on"), Value::Bool(false)).await;
    let before = commands.load(Ordering::SeqCst);
    for change in [
        FixtureUpdate::Publish(None, true),
        FixtureUpdate::Publish(Some(true), false),
        FixtureUpdate::Offline,
    ] {
        tx.send(change).unwrap();
        tokio::time::sleep(Duration::from_millis(30)).await;
        let a = autos.list()[0].clone();
        autos
            .run_now(
                &a.id,
                false,
                Some(&a.fingerprint()),
                Actor {
                    human: true,
                    author: Author::Human,
                },
            )
            .await
            .unwrap();
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert_eq!(commands.load(Ordering::SeqCst), before);
    }
    let a = autos.list()[0].clone();
    links
        .save(
            &hub,
            Save {
                source: SOURCE.into(),
                target: TARGET.into(),
                enabled: false,
                fingerprint: Some(a.fingerprint()),
            },
        )
        .await
        .unwrap();
    tx.send(FixtureUpdate::Publish(Some(true), true)).unwrap();
    tokio::time::sleep(Duration::from_millis(30)).await;
    assert_eq!(commands.load(Ordering::SeqCst), before);
    assert!(!autos.list()[0].is_live());
    assert!(
        std::fs::read_to_string(temp.0.join("automations.json"))
            .unwrap()
            .contains(DEVICE_LINK)
    );
    cancel.cancel();
    task.await.unwrap();
}
