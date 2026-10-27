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
