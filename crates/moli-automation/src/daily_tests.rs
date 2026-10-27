//! Release interlock tested at generic approval, restore and executor boundaries.
use super::*;
use moli_runtime::{Hub, HubOptions};
use serde_json::json;
pub(crate) fn candidate() -> Automation {
    serde_json::from_value(json!({"id":"daily-test","name":"Synthetic daily heating","enabled":false,"mode":"single","author":"agent","created":0,"updated":0,"graph":{"nodes":[{"id":DAILY_HEATING_LOCK,"type":"every","minutes":1},{"id":"set","type":"set","point":"synthetic:plug/on","value":true}],"edges":[{"from":DAILY_HEATING_LOCK,"to":"set"}]}})).unwrap()
}
#[tokio::test]
async fn daily_lock_covers_human_approval_native_executor_and_restored_enable() {
    let hub = Hub::new(HubOptions::default()).unwrap();
    let cancel = CancellationToken::new();
    let (autos, task) = Automations::start(&hub, Config::default(), None, cancel.clone())
        .await
        .unwrap();
    let human = Actor {
        human: true,
        author: Author::Human,
    };
    let mut a = autos.save(candidate(), human).await.unwrap();
    assert!(autos.approve(&a.id, &a.fingerprint(), human).await.is_err());
    assert!(
        autos
            .set_enabled(&a.id, true, Some(&a.fingerprint()), human)
            .await
            .is_err()
    );
    // Even a caller inside the process cannot supply a supervised scope to
    // bypass the release interlock at the original act() boundary.
    a.approved = Some(a.fingerprint());
    a.enabled = true;
    let run = run::execute(
        &autos,
        &a,
        DAILY_HEATING_LOCK,
        "synthetic".into(),
        false,
        CancellationToken::new(),
        1,
    )
    .await;
    assert_eq!(run.status, RunStatus::Failed);
    assert_eq!(run.steps[0].status, StepStatus::Failed);
    assert!(
        hub.journal(20)
            .iter()
            .all(|e| !matches!(e.action, moli_core::Action::Command { .. }))
    );
    let path = std::env::temp_dir().join(format!("moli-daily-restore-{}", std::process::id()));
    tokio::fs::create_dir_all(&path).await.unwrap();
    store::save(Some(&path.join("automations.json")), &[a])
        .await
        .unwrap();
    let (restored, restored_task) =
        Automations::start(&hub, Config::default(), Some(&path), cancel.clone())
            .await
            .unwrap();
    assert!(
        restored
            .list()
            .iter()
            .all(|a| !a.enabled && !a.is_approved() && a.approved_version.is_none())
    );
    assert!(restored.live().is_empty());
    cancel.cancel();
    task.await.unwrap();
    restored_task.await.unwrap();
    tokio::fs::remove_dir_all(path).await.unwrap();
}

#[tokio::test]
async fn observation_uses_the_exact_native_trigger_and_cancellation_without_authority() {
    #[derive(Debug)]
    struct Admit;
    impl DraftAdmission for Admit {
        fn allows(&self, _: &moli_core::PointId, _: &moli_core::Value) -> bool {
            true
        }
    }
    let hub = Hub::new(HubOptions::default()).unwrap();
    let cancel = CancellationToken::new();
    let (autos, task) = Automations::start(&hub, Config::default(), None, cancel.clone())
        .await
        .unwrap();
    let a = autos
        .save(
            candidate(),
            Actor {
                human: false,
                author: Author::Agent,
            },
        )
        .await
        .unwrap();
    let fp = a.fingerprint();
    autos.admit_draft(&a.id, &fp, Arc::new(Admit)).unwrap();
    let run = autos
        .observe_trigger(&a.id, &fp, DAILY_HEATING_LOCK, cancel.child_token())
        .await
        .unwrap();
    assert!(run.dry);
    assert_eq!(run.trigger, DAILY_HEATING_LOCK);
    assert_eq!(run.steps[0].status, StepStatus::Simulated);
    assert!(
        autos
            .observe_trigger(
                &a.id,
                "other-version",
                DAILY_HEATING_LOCK,
                cancel.child_token()
            )
            .await
            .is_err()
    );
    assert!(
        autos
            .observe_trigger(&a.id, &fp, "set", cancel.child_token())
            .await
            .is_err()
    );
    let stopped = CancellationToken::new();
    stopped.cancel();
    let run = autos
        .observe_trigger(&a.id, &fp, DAILY_HEATING_LOCK, stopped)
        .await
        .unwrap();
    assert_eq!(run.status, RunStatus::Cancelled);
    assert!(run.steps.is_empty());
    assert!(autos.list().iter().all(|a| !a.enabled && !a.is_approved()));
    assert!(
        hub.journal(50)
            .iter()
            .all(|e| !matches!(e.action, moli_core::Action::Command { .. }))
    );
    cancel.cancel();
    task.await.unwrap();
}
