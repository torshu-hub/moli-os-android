use super::*;
use moli_core::Action;
use moli_runtime::{Hub, HubOptions};
use serde_json::json;
#[tokio::test]
async fn ephemeral_preview_is_uninstalled_and_cannot_be_enabled_approved_or_run_for_real() {
    let hub = Hub::new(HubOptions::default()).unwrap();
    let cancel = CancellationToken::new();
    let (autos, _task) = Automations::start(&hub, Config::default(), None, cancel.clone())
        .await
        .unwrap();
    let graph:Graph=serde_json::from_value(json!({"nodes":[{"id":"start","type":"manual"},{"id":"would_on","type":"set","point":"synthetic:plug/on","value":true}],"edges":[{"from":"start","to":"would_on"}]})).unwrap();
    let actor = Actor {
        human: true,
        author: Author::Human,
    };
    let run = autos.preview_graph(graph.clone(), actor).await.unwrap();
    assert!(run.dry);
    assert_eq!(run.steps[0].status, StepStatus::Simulated);
    assert!(autos.list().is_empty());
    assert!(autos.runs(None, 10).is_empty());
    assert!(lock(&autos.0.previews).is_empty());
    let id = format!("{PREVIEW_PREFIX}test");
    let draft = Automation {
        id: id.clone(),
        name: "Synthetic preview".into(),
        enabled: false,
        mode: Mode::Single,
        graph,
        author: Author::Agent,
        approved: None,
        approved_version: None,
        note: None,
        created: 0,
        updated: 0,
    };
    lock(&autos.0.previews).insert(id.clone(), draft);
    assert!(autos.get(&id).is_none());
    assert!(autos.list().is_empty());
    assert!(matches!(
        autos.run_now(&id, false, None, actor).await,
        Err(AutomationError::Invalid(_))
    ));
    assert!(matches!(
        autos.approve(&id, "unused", actor).await,
        Err(AutomationError::Unknown)
    ));
    assert!(matches!(
        autos.set_enabled(&id, true, Some("unused"), actor).await,
        Err(AutomationError::Unknown)
    ));
    drop(PreviewDraft(autos.clone(), id));
    assert!(lock(&autos.0.previews).is_empty());
    assert!(
        !hub.journal(100)
            .iter()
            .any(|entry| matches!(entry.action, Action::Command { .. }))
    );
    cancel.cancel();
}
