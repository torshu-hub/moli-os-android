use super::*;
use crate::{Actor, Author, Config, DraftAdmission, Graph};
use moli_runtime::{Hub, HubOptions};
use std::sync::Mutex;
#[derive(Debug)]
struct Source(Mutex<(u64, bool, bool, Option<&'static str>)>);
impl Source {
    fn advance(&self) {
        lock(&self.0).0 += 1;
    }
}
impl ObservationSource for Source {
    fn review(&self) -> Result<ObservationSample, &'static str> {
        let s = lock(&self.0);
        if let Some(reason) = s.3 {
            return Err(reason);
        }
        Ok(ObservationSample {
            publication: s.0,
            desired: s.1,
            actual: s.2,
            valid_until: u64::MAX,
            witness: serde_json::json!([s.0, s.1, s.2]),
        })
    }
}
impl DraftAdmission for Source {
    fn allows(&self, _: &PointId, value: &moli_core::Value) -> bool {
        self.review()
            .is_ok_and(|s| *value == moli_core::Value::Bool(s.desired) && s.desired != s.actual)
    }
}
async fn setup() -> (
    Hub,
    Automations,
    Automation,
    Arc<Source>,
    CancellationToken,
    tokio::task::JoinHandle<()>,
) {
    let hub = Hub::new(HubOptions::default()).unwrap();
    let cancel = CancellationToken::new();
    let (autos, task) = Automations::start(&hub, Config::default(), None, cancel.clone())
        .await
        .unwrap();
    tokio::task::yield_now().await;
    let mut a = crate::daily_tests::candidate();
    a.graph=serde_json::from_value::<Graph>(serde_json::json!({"nodes":[
            {"id":crate::DAILY_HEATING_LOCK,"type":"every","minutes":1},
            {"id":"publication","type":"when_state","point":"synthetic:source/collected_at"},
            {"id":"set","type":"set","point":"synthetic:plug/on","value":true}],
            "edges":[{"from":crate::DAILY_HEATING_LOCK,"to":"set"},{"from":"publication","to":"set"}]})).unwrap();
    let a = autos
        .save(
            a,
            Actor {
                human: false,
                author: Author::Agent,
            },
        )
        .await
        .unwrap();
    let source = Arc::new(Source(Mutex::new((1, true, false, None))));
    autos
        .admit_draft(&a.id, &a.fingerprint(), source.clone())
        .unwrap();
    autos
        .start_observation(&a.id, &a.fingerprint(), source.clone())
        .unwrap();
    (hub, autos, a, source, cancel, task)
}
async fn fire(autos: &Automations, a: &Automation) {
    crate::engine::fire(autos, a, "publication", "synthetic".into());
    for _ in 0..1000 {
        if lock(&autos.0.observations)
            .get(&a.id)
            .is_some_and(|w| w.busy.is_none())
        {
            return;
        }
        tokio::task::yield_now().await;
    }
    panic!("observation did not finish");
}
fn reason(autos: &Automations, a: &Automation) -> &'static str {
    autos.observation_status(&a.id).unwrap().reason
}
#[tokio::test]
async fn native_observation_never_arms_or_replays_after_loss_pause_manual_stop_and_restart() {
    let (hub, autos, a, source, cancel, task) = setup().await;
    fire(&autos, &a).await;
    assert_eq!(reason(&autos, &a), "warming");
    source.advance();
    fire(&autos, &a).await;
    assert_eq!(reason(&autos, &a), "would_on");
    let events = autos.observation_status(&a.id).unwrap().events;
    let status = autos
        .start_observation(&a.id, &a.fingerprint(), source.clone())
        .unwrap();
    assert_eq!(status.events, events);
    lock(&source.0).3 = Some("source_error");
    fire(&autos, &a).await;
    assert_eq!(reason(&autos, &a), "source_error");
    lock(&source.0).3 = None;
    source.advance();
    fire(&autos, &a).await;
    assert_eq!(reason(&autos, &a), "warming");
    source.advance();
    fire(&autos, &a).await;
    assert_eq!(reason(&autos, &a), "would_on");
    lock(&autos.0.observations)
        .get_mut(&a.id)
        .unwrap()
        .last_tick = Instant::now().checked_sub(Duration::from_secs(6)).unwrap();
    autos.observation_tick();
    assert_eq!(reason(&autos, &a), "paused");
    // Android suspend can pause CLOCK_MONOTONIC while wall time advances.
    {
        let mut watches = lock(&autos.0.observations);
        let watch = watches.get_mut(&a.id).unwrap();
        watch.last_tick = Instant::now();
        watch.last_wall = moli_core::now_ms().saturating_sub(20_000);
    }
    autos.observation_tick();
    assert_eq!(reason(&autos, &a), "paused");
    fire(&autos, &a).await;
    assert_eq!(reason(&autos, &a), "warming");
    source.advance();
    fire(&autos, &a).await;
    autos.observation_event(&Event::Journal {
        entry: Arc::new(moli_core::JournalEntry {
            id: 1,
            ts: moli_core::now_ms(),
            origin: moli_core::Origin::Ui,
            actor: None,
            action: Action::Command {
                point: PointId::from("synthetic:plug/on"),
                value: moli_core::Value::Bool(false),
            },
            outcome: moli_core::Outcome::Ok,
            duration_ms: 0,
        }),
    });
    assert_eq!(reason(&autos, &a), "manual");
    fire(&autos, &a).await;
    assert_eq!(reason(&autos, &a), "warming");
    autos.stop_observation(&a.id);
    source.advance();
    fire(&autos, &a).await;
    assert_eq!(reason(&autos, &a), "stopped");
    assert!(!autos.observation_status(&a.id).unwrap().active);
    assert!(autos.list().iter().all(|a| !a.enabled && !a.is_approved()));
    assert!(autos.runs(None, 500).is_empty());
    assert!(
        hub.journal(100)
            .iter()
            .all(|e| !matches!(e.action, Action::Command { .. }))
    );
    cancel.cancel();
    task.await.unwrap();
    assert!(!autos.observation_status(&a.id).unwrap().active);
    let cancel2 = CancellationToken::new();
    let (restored, task) = Automations::start(&hub, Config::default(), None, cancel2.clone())
        .await
        .unwrap();
    assert!(restored.observation_status(&a.id).is_none());
    cancel2.cancel();
    task.await.unwrap();
}
#[tokio::test]
async fn native_observation_bounds_coalesces_journal_and_stops_on_changed_graph() {
    let (_hub, autos, a, source, cancel, task) = setup().await;
    source.advance();
    fire(&autos, &a).await;
    for _ in 0..5 {
        fire(&autos, &a).await;
    }
    let status = autos.observation_status(&a.id).unwrap();
    assert_eq!(status.history.last().unwrap().repeats, 6);
    for i in 0..70 {
        lock(&source.0).3 = Some(if i % 2 == 0 {
            "source_error"
        } else {
            "source_stale"
        });
        lock(&autos.0.observations)
            .get_mut(&a.id)
            .unwrap()
            .rate
            .clear();
        fire(&autos, &a).await;
    }
    let status = autos.observation_status(&a.id).unwrap();
    assert_eq!(status.history.len(), KEEP);
    assert!(status.omitted > 0);
    let mut edited = a.clone();
    edited.graph.nodes[2].step = Step::Set {
        point: "synthetic:other/on".into(),
        value: serde_json::json!(true),
    };
    autos
        .save(
            edited,
            Actor {
                human: false,
                author: Author::Agent,
            },
        )
        .await
        .unwrap();
    assert!(!autos.observation_status(&a.id).unwrap().active);
    assert!(
        autos
            .observe_trigger(&a.id, &a.fingerprint(), "publication", cancel.child_token())
            .await
            .is_err()
    );
    cancel.cancel();
    task.await.unwrap();
}
#[tokio::test]
async fn native_clock_and_publication_share_scheduler_without_duplicate_or_catchup() {
    let (_hub, autos, a, source, cancel, task) = setup().await;
    source.advance();
    // Observation opt-in marks the minute, so it cannot fire immediately.
    crate::engine::on_clock(&autos);
    assert_eq!(autos.observation_status(&a.id).unwrap().clocks, 0);
    lock(&autos.0.fired).remove(&(a.id.clone(), crate::DAILY_HEATING_LOCK.into()));
    for _ in 0..6 {
        crate::engine::on_clock(&autos);
    }
    for _ in 0..1000 {
        if lock(&autos.0.observations)
            .get(&a.id)
            .unwrap()
            .busy
            .is_none()
        {
            break;
        }
        tokio::task::yield_now().await;
    }
    assert_eq!(autos.observation_status(&a.id).unwrap().clocks, 1);
    let p = PointId::from("synthetic:source/collected_at");
    crate::engine::on_state(&autos, &p, &moli_core::Value::Int(1), moli_core::now_ms());
    source.advance();
    crate::engine::on_state(&autos, &p, &moli_core::Value::Int(2), moli_core::now_ms());
    crate::engine::on_state(&autos, &p, &moli_core::Value::Int(2), moli_core::now_ms());
    for _ in 0..1000 {
        if lock(&autos.0.observations)
            .get(&a.id)
            .unwrap()
            .busy
            .is_none()
        {
            break;
        }
        tokio::task::yield_now().await;
    }
    let status = autos.observation_status(&a.id).unwrap();
    assert_eq!(status.publications, 1);
    assert_eq!(status.events, 2);
    assert_eq!(status.reason, "would_on");
    cancel.cancel();
    task.await.unwrap();
}

#[tokio::test]
async fn observation_rate_limit_is_volatile_and_does_not_enable_the_original_graph() {
    let (hub, autos, a, source, cancel, task) = setup().await;
    source.advance();
    for _ in 0..22 {
        fire(&autos, &a).await;
    }
    let status = autos.observation_status(&a.id).unwrap();
    assert!(!status.active);
    assert_eq!(status.reason, "throttled");
    assert_eq!(status.events, 20);
    assert!(autos.list().iter().all(|a| !a.enabled && !a.is_approved()));
    assert!(
        hub.journal(100)
            .iter()
            .all(|e| !matches!(e.action, Action::Command { .. }))
    );
    cancel.cancel();
    task.await.unwrap();
}
