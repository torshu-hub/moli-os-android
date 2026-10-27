//! Candidate tests use only the existing synthetic fixture and native executor.
use super::*;
use crate::observation::daily;
use moli_core::{Origin, PointId};
fn actor() -> Actor {
    Actor {
        human: true,
        author: Author::Human,
    }
}
async fn assert_dry_branch(autos: &Automations, id: &str, demand: &str) {
    for _ in 0..3 {
        let run = autos.run_now(id, true, None, actor()).await.unwrap();
        let sets: Vec<_> = run
            .steps
            .iter()
            .filter(|s| s.status == StepStatus::Simulated)
            .collect();
        assert_eq!(sets.len(), 1);
        assert_eq!(sets[0].node, format!("set_{demand}"));
    }
}
#[tokio::test]
async fn daily_native_graph_prepares_once_and_never_obtains_real_authority() {
    let temp = Temp::new();
    for demand in ["on", "off"] {
        let now = i64::try_from(moli_core::now_ms()).unwrap();
        let (hub, autos, observer, cancel, commands) = setup(
            Value::from(demand),
            Some(now),
            true,
            true,
            temp.0.join(FILE),
        )
        .await;
        let input = select(&observer, &hub).await;
        if demand == "off" {
            // Set up a synthetic already-ON plug, through the original manual
            // path: no real equipment exists in this fixture.
            hub.command(
                &PointId::from(format!("{TARGET}/on")),
                Value::Bool(true),
                Origin::Ui,
                None,
            )
            .await
            .unwrap();
        }
        let baseline = commands.load(Ordering::SeqCst);
        let view = observer.prepare_daily(&hub, input).await.unwrap();
        assert_eq!(view["state"], "disabled");
        assert_eq!(view["readiness"], "ready");
        assert_eq!(view["activation_available"], false);
        let id = view["draft"]["id"].as_str().unwrap();
        let fp = view["draft"]["fingerprint"].as_str().unwrap();
        let again = observer
            .prepare_daily(
                &hub,
                Observe {
                    revision: 1,
                    epoch: observer.0.epoch,
                },
            )
            .await
            .unwrap();
        assert_eq!(again["draft"], view["draft"]);
        assert_eq!(autos.list().len(), 1);
        let a = autos.get(id).unwrap();
        assert!(autos.check(&a.graph).ok());
        assert!(
            a.graph
                .nodes
                .iter()
                .all(|n| !matches!(n.step, moli_automation::Step::OnStart))
        );
        assert_dry_branch(&autos, id, demand).await;
        assert!(autos.approve(id, fp, actor()).await.is_err());
        assert!(
            autos
                .set_enabled(id, true, Some(fp), actor())
                .await
                .is_err()
        );
        assert!(autos.run_now(id, false, Some(fp), actor()).await.is_err());
        assert_eq!(commands.load(Ordering::SeqCst), baseline);
        // Manual control remains usable, and matching readback removes the
        // candidate SET from subsequent clock/state evaluations.
        hub.command(
            &PointId::from(format!("{TARGET}/on")),
            Value::Bool(demand == "on"),
            Origin::Ui,
            None,
        )
        .await
        .unwrap();
        let run = autos.run_now(id, true, None, actor()).await.unwrap();
        assert!(run.steps.iter().all(|s| s.status != StepStatus::Simulated));
        let recovered = Observer::open(Some(temp.0.join(FILE)), Some(autos.clone()));
        assert_eq!(recovered.daily_view(&hub).await["readiness"], "refused");
        recovered
            .prepare_daily(
                &hub,
                Observe {
                    revision: 1,
                    epoch: recovered.0.epoch,
                },
            )
            .await
            .unwrap();
        assert_eq!(autos.list().len(), 1);
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
        assert_eq!(observer.daily_view(&hub).await["readiness"], "refused");
        assert!(autos.list().iter().all(|a| !a.enabled && !a.is_approved()));
        cancel.cancel();
        // Next fixture uses a fresh selection store.
        std::fs::remove_file(temp.0.join(FILE)).unwrap();
    }
}
#[tokio::test]
async fn daily_refuses_unknown_stale_future_offline_and_missing_dates_without_creating_scenarios() {
    let temp = Temp::new();
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    for (demand, collected, online) in [
        ("unknown", Some(now), true),
        ("on", Some(now - 300_001), true),
        ("on", Some(now + 60_000), true),
        ("on", Some(now), false),
        ("on", None, true),
    ] {
        let (hub, autos, observer, cancel, commands) = setup(
            Value::from(demand),
            collected,
            online,
            true,
            temp.0.join(FILE),
        )
        .await;
        let input = select(&observer, &hub).await;
        assert!(observer.prepare_daily(&hub, input).await.is_err());
        assert!(autos.list().is_empty());
        assert_eq!(commands.load(Ordering::SeqCst), 0);
        cancel.cancel();
        std::fs::remove_file(temp.0.join(FILE)).unwrap();
    }
}
#[tokio::test]
async fn daily_freeze_and_network_loss_refuse_while_original_data_remains_untouched() {
    let temp = Temp::new();
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    for file in OTHER_FILES {
        std::fs::write(temp.0.join(file), format!("synthetic-{file}")).unwrap();
    }
    let (hub, autos, observer, cancel, commands) =
        setup(Value::from("on"), Some(now), true, true, temp.0.join(FILE)).await;
    select(&observer, &hub).await;
    observer
        .prepare_daily(
            &hub,
            Observe {
                revision: 1,
                epoch: observer.0.epoch,
            },
        )
        .await
        .unwrap();
    let selection = observer
        .0
        .storage
        .lock()
        .await
        .value
        .selection
        .clone()
        .unwrap();
    // With the same samples, a graph evaluated against a shorter freshness
    // contract refuses instead of replaying a remembered intention.
    tokio::time::sleep(Duration::from_millis(5)).await;
    let run = autos
        .preview_graph(daily::graph(&selection, 1), actor())
        .await
        .unwrap();
    assert!(run.steps.iter().all(|s| s.status != StepStatus::Simulated));
    cancel.cancel();
    for _ in 0..100 {
        if hub.device(&DeviceId::from(SOURCE)).unwrap().online != Some(true) {
            break;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(observer.daily_view(&hub).await["readiness"], "refused");
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    for file in OTHER_FILES {
        assert_eq!(
            std::fs::read_to_string(temp.0.join(file)).unwrap(),
            format!("synthetic-{file}")
        );
    }
}

#[tokio::test]
async fn daily_edit_or_revocation_invalidates_exact_native_admission() {
    let temp = Temp::new();
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    let (hub, autos, observer, cancel, commands) =
        setup(Value::from("on"), Some(now), true, true, temp.0.join(FILE)).await;
    let input = select(&observer, &hub).await;
    let view = observer.prepare_daily(&hub, input).await.unwrap();
    let id = view["draft"]["id"].as_str().unwrap();
    let mut edited = autos.get(id).unwrap();
    edited.mode = moli_automation::Mode::Queued;
    autos.save(edited, actor()).await.unwrap();
    assert_eq!(observer.daily_view(&hub).await["readiness"], "refused");
    let run = autos.run_now(id, true, None, actor()).await.unwrap();
    assert!(run.steps.iter().any(|s| s.status == StepStatus::Failed));
    assert!(run.steps.iter().all(|s| s.status != StepStatus::Simulated));
    observer
        .prepare_daily(
            &hub,
            Observe {
                revision: 1,
                epoch: observer.0.epoch,
            },
        )
        .await
        .unwrap();
    assert_eq!(observer.daily_view(&hub).await["readiness"], "ready");
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
    let run = autos.run_now(id, true, None, actor()).await.unwrap();
    assert!(run.steps.iter().any(|s| s.status == StepStatus::Failed));
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    cancel.cancel();
}

#[tokio::test]
async fn daily_refuses_new_network_error_even_while_last_sample_is_fresh() {
    let temp = Temp::new();
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    let (hub, autos, observer, cancel, commands) =
        setup(Value::from("on"), Some(now), true, true, temp.0.join(FILE)).await;
    select(&observer, &hub).await;
    let view = observer
        .prepare_daily(
            &hub,
            Observe {
                revision: 1,
                epoch: observer.0.epoch,
            },
        )
        .await
        .unwrap();
    let id = view["draft"]["id"].as_str().unwrap();
    hub.observe_read(
        &InstanceId::from("source"),
        "netatmo",
        ReadEvent::Stage(
            moli_runtime::ReadStage::Homestatus,
            moli_runtime::ReadOutcome::Transport,
        ),
    );
    assert_eq!(observer.daily_view(&hub).await["readiness"], "refused");
    let run = autos.run_now(id, true, None, actor()).await.unwrap();
    assert!(run.steps.iter().any(|s| s.status == StepStatus::Failed));
    assert!(run.steps.iter().all(|s| s.status != StepStatus::Simulated));
    assert!(
        observer
            .prepare_daily(
                &hub,
                Observe {
                    revision: 1,
                    epoch: observer.0.epoch
                }
            )
            .await
            .is_err()
    );
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    cancel.cancel();
}

async fn wait_decision(
    autos: &Automations,
    id: &str,
    expected: &str,
) -> moli_automation::ObservationStatus {
    for _ in 0..200 {
        let status = autos.observation_status(id).unwrap();
        if status
            .history
            .last()
            .is_some_and(|row| row.reason == expected)
        {
            return status;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    panic!(
        "expected {expected}, got {:?}",
        autos.observation_status(id)
    );
}
async fn serve_daily(
    hub: &Hub,
    autos: &Automations,
    home_path: PathBuf,
    cancel: &CancellationToken,
) -> std::net::SocketAddr {
    let router = crate::router(
        hub.clone(),
        cancel.clone(),
        &Options {
            home_path: Some(home_path),
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
    address
}
async fn assert_watch_rejections(address: std::net::SocketAddr, endpoint: &str, start: &JsonValue) {
    for (key, value) in [
        ("revision", json!(2)),
        ("epoch", json!(0)),
        ("fingerprint", json!("other-version")),
        ("action", json!("activate")),
    ] {
        let mut bad = start.clone();
        bad[key] = value;
        assert!(http(address, "POST", endpoint, bad).await.0 >= 400);
    }
    for flag in ["enabled", "approved", "dry", "physical"] {
        let mut bad = start.clone();
        bad[flag] = json!(true);
        assert_eq!(http(address, "POST", endpoint, bad).await.0, 422);
    }
}
async fn next_publication(
    tx: &tokio::sync::mpsc::UnboundedSender<FixtureUpdate>,
    autos: &Automations,
    id: &str,
    demand: Option<bool>,
    fresh: bool,
    expected: &str,
) {
    tokio::time::sleep(Duration::from_millis(3)).await;
    tx.send(FixtureUpdate::Publish(demand, fresh)).unwrap();
    wait_decision(autos, id, expected).await;
}
fn assert_preserved_files(temp: &Temp) {
    for file in OTHER_FILES {
        assert_eq!(
            std::fs::read_to_string(temp.0.join(file)).unwrap(),
            format!("synthetic-{file}")
        );
    }
}
#[tokio::test]
async fn continuous_dry_observation_uses_real_native_publications_and_preserves_all_physical_locks()
{
    let temp = Temp::new();
    for file in OTHER_FILES {
        std::fs::write(temp.0.join(file), format!("synthetic-{file}")).unwrap();
    }
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    let (hub, autos, observer, cancel, commands) = setup_updates(
        Value::from("on"),
        Some(i64::try_from(moli_core::now_ms()).unwrap()),
        true,
        true,
        temp.0.join(FILE),
        Some(Arc::new(Mutex::new(rx))),
    )
    .await;
    select(&observer, &hub).await;
    let address = serve_daily(&hub, &autos, temp.0.join("home.json"), &cancel).await;
    let path = "/api/observations/netatmo-meross";
    let (_, settings) = http(address, "GET", path, JsonValue::Null).await;
    let input = json!({"revision":1,"epoch":settings["epoch"]});
    let (status, prepared) = http(address, "POST", &format!("{path}/daily"), input.clone()).await;
    assert_eq!(status, 200);
    assert!(prepared["observation"].is_null());
    let id = prepared["draft"]["id"].as_str().unwrap();
    let fp = prepared["draft"]["fingerprint"].as_str().unwrap();
    let endpoint = format!("{path}/daily/observation");
    let start = json!({"revision":1,"epoch":settings["epoch"],"fingerprint":fp,"action":"start"});
    assert_watch_rejections(address, &endpoint, &start).await;
    let (status, started) = http(address, "POST", &endpoint, start.clone()).await;
    assert_eq!(status, 200);
    assert_eq!(started["observation"]["active"], true);
    assert_eq!(started["observation"]["reason"], "warming");
    let publish = |demand, fresh| tx.send(FixtureUpdate::Publish(demand, fresh)).unwrap();
    next_publication(&tx, &autos, id, Some(true), true, "would_on").await;
    let events = autos.observation_status(id).unwrap().events;
    let (_, again) = http(address, "POST", &endpoint, start.clone()).await;
    assert_eq!(again["observation"]["events"], events);
    next_publication(&tx, &autos, id, Some(false), true, "no_change").await;
    tx.send(FixtureUpdate::Pending).unwrap();
    for _ in 0..100 {
        if autos.observation_status(id).unwrap().reason == "source_pending" {
            break;
        }
        tokio::task::yield_now().await;
    }
    assert_eq!(
        autos.observation_status(id).unwrap().reason,
        "source_pending"
    );
    assert!(autos.observation_status(id).unwrap().intention.is_none());
    next_publication(&tx, &autos, id, Some(true), true, "would_on").await;
    tx.send(FixtureUpdate::Offline).unwrap();
    wait_decision(&autos, id, "source_error").await;
    next_publication(&tx, &autos, id, Some(true), true, "warming").await;
    next_publication(&tx, &autos, id, Some(true), true, "would_on").await;
    next_publication(&tx, &autos, id, None, true, "unknown_demand").await;
    next_publication(&tx, &autos, id, Some(true), false, "source_stale").await;
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    // A manual order on the in-memory synthetic driver remains independent.
    hub.command(
        &PointId::from(format!("{TARGET}/on")),
        Value::Bool(true),
        Origin::Ui,
        None,
    )
    .await
    .unwrap();
    wait_decision(&autos, id, "manual").await;
    next_publication(&tx, &autos, id, Some(false), true, "warming").await;
    next_publication(&tx, &autos, id, Some(false), true, "would_off").await;
    assert_eq!(commands.load(Ordering::SeqCst), 1);
    assert!(autos.approve(id, fp, actor()).await.is_err());
    assert!(autos.run_now(id, false, Some(fp), actor()).await.is_err());
    assert!(autos.list().iter().all(|a| !a.enabled && !a.is_approved()));
    let stop = json!({"revision":0,"epoch":0,"fingerprint":"outdated","action":"stop"});
    let (status, stopped) = http(address, "POST", &endpoint, stop).await;
    assert_eq!(status, 200);
    assert_eq!(stopped["observation"]["active"], false);
    let before = autos.observation_status(id).unwrap().events;
    tokio::time::sleep(Duration::from_millis(3)).await;
    publish(Some(true), true);
    tokio::time::sleep(Duration::from_millis(15)).await;
    assert_eq!(autos.observation_status(id).unwrap().events, before);
    assert_eq!(http(address, "POST", &endpoint, start.clone()).await.0, 200);
    assert_eq!(
        http(
            address,
            "PUT",
            path,
            json!({"revision":1,"source":null,"target":null})
        )
        .await
        .0,
        200
    );
    assert!(!autos.observation_status(id).unwrap().active);
    assert_eq!(http(address, "POST", &endpoint, start).await.0, 409);
    assert_eq!(commands.load(Ordering::SeqCst), 1);
    assert!(autos.runs(None, 500).is_empty());
    assert_preserved_files(&temp);
    cancel.cancel();
}
#[tokio::test]
async fn continuous_admission_refuses_unknown_old_or_future_meross_readback_without_commands() {
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
    select(&observer, &hub).await;
    let selection = observer
        .0
        .storage
        .lock()
        .await
        .value
        .selection
        .clone()
        .unwrap();
    for value in [Value::Null, Value::from("on"), Value::Int(1)] {
        let mut snapshot = hub.snapshot();
        snapshot
            .devices
            .iter_mut()
            .find(|d| d.device.id.as_str() == TARGET)
            .unwrap()
            .state
            .get_mut("on")
            .unwrap()
            .value = value;
        assert_eq!(
            daily::review_snapshot(&snapshot, &selection, moli_core::now_ms()).unwrap_err(),
            "target_unknown"
        );
    }
    for date in [0, now - 65_001, now + 60_000] {
        let mut snapshot = hub.snapshot();
        snapshot
            .devices
            .iter_mut()
            .find(|d| d.device.id.as_str() == TARGET)
            .unwrap()
            .state
            .get_mut("on")
            .unwrap()
            .ts = date;
        assert_eq!(
            daily::review_snapshot(&snapshot, &selection, moli_core::now_ms()).unwrap_err(),
            "target_stale"
        );
    }
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    cancel.cancel();
}
