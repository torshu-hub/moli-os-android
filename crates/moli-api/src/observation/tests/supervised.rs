//! End-to-end adapter -> approved core graph -> original hub -> scoped fake driver.
use super::*;
use crate::observation::trial::{self, Execute};
fn actor(human: bool) -> Actor {
    Actor {
        human,
        author: if human { Author::Human } else { Author::Agent },
    }
}
fn execution(epoch: u64, fingerprint: &str) -> Execute {
    serde_json::from_value(
        json!({"epoch":epoch,"revision":1,"fingerprint":fingerprint,"confirmed":true}),
    )
    .unwrap()
}
async fn prepared(observer: &Observer, hub: &Hub) -> JsonValue {
    observer
        .prepare_trial(
            hub,
            Observe {
                revision: 1,
                epoch: observer.0.epoch,
            },
        )
        .await
        .unwrap()["prepared"]
        .clone()
}
#[tokio::test]
async fn supervised_order_requires_single_confirmation_human_and_exact_version_and_never_enables_scheduler()
 {
    let temp = Temp::new();
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    let (hub, autos, observer, cancel, commands) = setup_updates(
        Value::from("on"),
        Some(now),
        true,
        true,
        temp.0.join(FILE),
        Some(Arc::new(Mutex::new(rx))),
    )
    .await;
    select(&observer, &hub).await;
    let ticket = prepared(&observer, &hub).await;
    let fp = ticket["fingerprint"].as_str().unwrap();
    let epoch = observer.0.epoch;
    let input = json!({"epoch":epoch,"revision":1,"fingerprint":fp,"confirmed":false});
    assert_eq!(
        observer
            .execute_trial(&hub, serde_json::from_value(input).unwrap(), actor(true))
            .await
            .unwrap_err(),
        StatusCode::FORBIDDEN
    );
    assert!(serde_json::from_value::<Execute>(json!({"epoch":epoch,"revision":1,"fingerprint":fp,"confirmed":true,"vessel_pressure":true})).is_err());
    assert_eq!(
        observer
            .execute_trial(&hub, execution(epoch, fp), actor(false))
            .await
            .unwrap_err(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    assert!(autos.list().iter().all(|a| !a.enabled && !a.is_approved()));
    assert_eq!(
        observer
            .execute_trial(&hub, execution(epoch, "wrong"), actor(true))
            .await
            .unwrap_err(),
        StatusCode::CONFLICT
    );
    assert!(
        observer
            .execute_trial(&hub, execution(epoch, fp), actor(true))
            .await
            .is_err(),
        "consumed invalid version cannot be replayed"
    );
    let ticket = prepared(&observer, &hub).await;
    let fp = ticket["fingerprint"].as_str().unwrap();
    let previous = hub
        .state(&format!("{SOURCE}/collected_at").as_str().into())
        .unwrap()
        .value;
    tokio::time::sleep(Duration::from_millis(2)).await;
    tx.send(FixtureUpdate::Publish(Some(true), true)).unwrap();
    for _ in 0..100 {
        if hub
            .state(&format!("{SOURCE}/collected_at").as_str().into())
            .unwrap()
            .value
            != previous
        {
            break;
        }
        tokio::time::sleep(Duration::from_millis(1)).await;
    }
    let result = observer
        .execute_trial(&hub, execution(epoch, fp), actor(true))
        .await
        .unwrap();
    assert_eq!(result["state"], "confirmed");
    assert_eq!(result["autonomous"], false);
    assert_eq!(commands.load(Ordering::SeqCst), 1);
    assert!(autos.list().iter().all(|a| !a.enabled && !a.is_approved()));
    let journal = hub.journal(100);
    assert_eq!(
        journal
            .iter()
            .filter(|e| matches!(e.action, Action::Command { .. })
                && e.origin == moli_core::Origin::Automation)
            .count(),
        1
    );
    assert!(
        observer
            .execute_trial(&hub, execution(epoch, fp), actor(true))
            .await
            .is_err()
    );
    assert!(observer.0.authority.lock().unwrap().is_none());
    tokio::time::sleep(Duration::from_millis(20)).await;
    assert_eq!(commands.load(Ordering::SeqCst), 1);
    cancel.cancel();
}
#[tokio::test]
async fn revocation_restart_and_selection_change_invalidate_prepared_order_without_replay() {
    let temp = Temp::new();
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    let (hub, autos, observer, cancel, commands) =
        setup(Value::from("on"), Some(now), true, true, temp.0.join(FILE)).await;
    select(&observer, &hub).await;
    let epoch = observer.0.epoch;
    for mode in ["revoke", "restart", "expired", "selection"] {
        let ticket = prepared(&observer, &hub).await;
        let fp = ticket["fingerprint"].as_str().unwrap();
        match mode {
            "revoke" => trial::revoke(&observer),
            "selection" => {
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
            }
            "expired" => {
                observer.expire_trial().await;
            }
            _ => {
                let restarted = Observer::open(Some(temp.0.join(FILE)), Some(autos.clone()));
                assert!(
                    restarted
                        .execute_trial(&hub, execution(restarted.0.epoch, fp), actor(true))
                        .await
                        .is_err()
                );
                trial::revoke(&observer);
            }
        }
        assert!(
            observer
                .execute_trial(&hub, execution(epoch, fp), actor(true))
                .await
                .is_err()
        );
        if mode == "selection" {
            observer
                .save(
                    &hub,
                    Save {
                        revision: 2,
                        source: Some(SOURCE.into()),
                        target: Some(TARGET.into()),
                    },
                )
                .await
                .unwrap();
            break;
        }
    }
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    assert!(autos.list().iter().all(|a| !a.enabled && !a.is_approved()));
    cancel.cancel();
}
#[tokio::test]
async fn no_trial_from_read_only_unknown_or_old_source_and_no_grant_in_saved_selection() {
    for (demand, age, writable) in [
        (Value::from("unknown"), 0, true),
        (Value::Bool(false), 0, true),
        (Value::Null, 0, true),
        (Value::from("on"), 400_000, true),
        (Value::from("on"), 0, false),
    ] {
        let temp = Temp::new();
        let now = i64::try_from(moli_core::now_ms()).unwrap();
        let (hub, autos, observer, cancel, commands) =
            setup(demand, Some(now - age), true, writable, temp.0.join(FILE)).await;
        select(&observer, &hub).await;
        assert!(
            observer
                .prepare_trial(
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
        assert!(autos.list().is_empty());
        let saved = std::fs::read_to_string(temp.0.join(FILE)).unwrap();
        assert!(
            !saved.contains("approved") && !saved.contains("permit") && !saved.contains("enabled")
        );
        cancel.cancel();
    }
}

#[tokio::test]
async fn http_trial_cannot_use_an_origin_header_as_human_approval_and_logout_revokes_session() {
    let temp = Temp::new();
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    let (hub, autos, _observer, cancel, commands) =
        setup(Value::from("on"), Some(now), true, true, temp.0.join(FILE)).await;
    let router = crate::router(
        hub.clone(),
        cancel.clone(),
        &Options {
            home_path: Some(temp.0.join("home.json")),
            automations: Some(autos.clone()),
            ui_pin: Some(crate::UiPin::new("654321")),
            ..Options::default()
        },
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
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
    let base = "/api/observations/netatmo-meross";
    let (status, saved) = http(
        addr,
        "PUT",
        base,
        json!({"revision":0,"source":SOURCE,"target":TARGET}),
    )
    .await;
    assert_eq!(status, 200);
    let request = json!({"revision":1,"epoch":saved["epoch"]});
    let (status, prepared) = http(
        addr,
        "POST",
        &format!("{base}/trial/prepare"),
        request.clone(),
    )
    .await;
    assert_eq!(status, 200);
    let ticket = &prepared["prepared"];
    let execute = format!("{base}/trial/execute");
    let mut input = json!({"revision":1,"epoch":saved["epoch"],"fingerprint":ticket["fingerprint"],"confirmed":true});
    assert_eq!(http(addr, "POST", &execute, input.clone()).await.0, 403);
    let (status, _, cookie) =
        http_cookie(addr, "POST", "/api/session", json!({"pin":"654321"}), "").await;
    assert_eq!(status, 200);
    let cookie = cookie.unwrap();
    input["confirmed"] = json!(false);
    assert_eq!(
        http_cookie(addr, "POST", &execute, input.clone(), &cookie)
            .await
            .0,
        403
    );
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    input["confirmed"] = json!(true);
    let (status, result, _) = http_cookie(addr, "POST", &execute, input.clone(), &cookie).await;
    assert_eq!(status, 200);
    assert_eq!(result["state"], "confirmed");
    assert_eq!(commands.load(Ordering::SeqCst), 1);
    assert!(autos.list().iter().all(|a| !a.is_approved() && !a.enabled));
    assert_eq!(
        http_cookie(addr, "POST", &execute, input.clone(), &cookie)
            .await
            .0,
        409
    );
    assert_eq!(commands.load(Ordering::SeqCst), 1);
    assert_eq!(
        http_cookie(addr, "DELETE", "/api/session", JsonValue::Null, &cookie)
            .await
            .0,
        200
    );
    let (_, prepared) = http(addr, "POST", &format!("{base}/trial/prepare"), request).await;
    assert_eq!(prepared["state"], "already_applied");
    assert_eq!(
        http_cookie(addr, "POST", &execute, input, &cookie).await.0,
        403
    );
    assert_eq!(commands.load(Ordering::SeqCst), 1);
    cancel.cancel();
}

#[tokio::test]
async fn supervised_off_uses_the_off_branch_without_an_on_or_scheduled_run() {
    let temp = Temp::new();
    let now = i64::try_from(moli_core::now_ms()).unwrap();
    let (hub, autos, observer, cancel, commands) =
        setup(Value::from("off"), Some(now), true, true, temp.0.join(FILE)).await;
    select(&observer, &hub).await;
    let result = observer
        .prepare_trial(
            &hub,
            Observe {
                revision: 1,
                epoch: observer.0.epoch,
            },
        )
        .await
        .unwrap();
    assert_eq!(result["state"], "already_applied");
    assert_eq!(commands.load(Ordering::SeqCst), 0);
    assert!(autos.list().is_empty());
    hub.command(
        &format!("{TARGET}/on").as_str().into(),
        Value::Bool(true),
        moli_core::Origin::Ui,
        None,
    )
    .await
    .unwrap();
    commands.store(0, Ordering::SeqCst);
    let ticket = prepared(&observer, &hub).await;
    assert_eq!(ticket["demand"], "off");
    let result = observer
        .execute_trial(
            &hub,
            execution(observer.0.epoch, ticket["fingerprint"].as_str().unwrap()),
            actor(true),
        )
        .await
        .unwrap();
    assert_eq!(result["state"], "confirmed");
    assert_eq!(commands.load(Ordering::SeqCst), 1);
    assert_eq!(
        hub.state(&format!("{TARGET}/on").as_str().into())
            .unwrap()
            .value,
        Value::Bool(false)
    );
    assert!(autos.list().iter().all(|a| !a.enabled));
    assert!(
        hub.journal(100)
            .iter()
            .filter(|entry| entry.origin == moli_core::Origin::Automation)
            .all(|entry| !matches!(
                &entry.action,
                Action::Command {
                    value: Value::Bool(true),
                    ..
                }
            ))
    );
    cancel.cancel();
}
