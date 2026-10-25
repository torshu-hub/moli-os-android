use std::{collections::VecDeque, sync::Mutex};

use http::{HeaderMap, Request, StatusCode};
use http_body_util::{BodyExt as _, Full};
use moli_core::{InstanceId, PointId};
use moli_runtime::{Hub, HubOptions, MasterKey, spawn_driver};
use serde_json::{Value as Json, json};
use tokio_util::sync::CancellationToken;

use super::*;
use crate::client::{Response, Transport};

const AT: u64 = 1_800_000_000_000;
fn topology() -> Json {
    serde_json::from_str(include_str!(
        "../../../integrations/netatmo/fixtures/homesdata.json"
    ))
    .unwrap()
}
fn live_topology() -> Json {
    let mut t = topology();
    t["time_server"] = json!(now_ms() / 1000);
    t
}
fn status() -> Json {
    serde_json::from_str(include_str!(
        "../../../integrations/netatmo/fixtures/homestatus.json"
    ))
    .unwrap()
}
fn parse(status: &Json, now: u64) -> Vec<Observation> {
    model::parse(&topology(), status, "home-test", now, 300_000).unwrap()
}
fn thermostat(rows: &[Observation]) -> &Observation {
    rows.iter().find(|o| o.model == "NATherm1").unwrap()
}
fn config() -> Config {
    Config {
        home_id: "home-test".into(),
        client_id: Some("app-test".into()),
        poll_seconds: 120,
        max_age_seconds: 300,
    }
}
#[test]
fn demand_on_off_missing_and_percentage_are_distinct() {
    let mut s = status();
    for (field, expected) in [
        (json!(true), "on"),
        (json!(false), "off"),
        (Json::Null, "missing"),
    ] {
        s["body"]["home"]["modules"][1]["boiler_status"] = field;
        let rows = parse(&s, AT);
        let t = thermostat(&rows);
        assert!(t.current());
        assert_eq!(t.readings["declared_demand"], Value::from(expected));
        let valve = rows.iter().find(|r| r.model == "NRV").unwrap();
        assert_eq!(valve.readings["declared_demand"], Value::from("missing"));
        assert_eq!(
            rows[0].readings["heating_power_request"],
            Value::Float(42.0)
        );
    }
    // A truthy string must not silently turn into a binary state.
    s["body"]["home"]["modules"][1]["boiler_status"] = json!("true");
    assert!(model::parse(&topology(), &s, "home-test", AT, 300_000).is_err());
}
#[test]
fn timestamps_reachability_and_partial_errors_fail_closed() {
    let mut s = status();
    assert_eq!(thermostat(&parse(&s, AT + 300_001)).quality, "stale");
    s["time_server"] = Json::Null;
    assert_eq!(thermostat(&parse(&s, AT)).quality, "unconfirmed");
    s["time_server"] = json!("1800000000"); // official schema also allows string
    assert!(thermostat(&parse(&s, AT)).current());
    assert_eq!(thermostat(&parse(&s, AT - 1)).quality, "unconfirmed");
    s["body"]["home"]["modules"][1]["reachable"] = json!(false);
    assert_eq!(thermostat(&parse(&s, AT)).quality, "offline");
    s["body"]["errors"] = json!([{"id":"aa:bb:cc:dd:ee:01","code":6}]);
    assert!(model::parse(&topology(), &s, "home-test", AT, 300_000).is_err());
}
#[test]
fn identity_survives_reorder_rename_and_numeric_room_ids() {
    let initial = parse(&status(), AT);
    let mut t = topology();
    let mut s = status();
    t["body"]["homes"][0]["modules"]
        .as_array_mut()
        .unwrap()
        .reverse();
    s["body"]["home"]["modules"]
        .as_array_mut()
        .unwrap()
        .reverse();
    t["body"]["homes"][0]["modules"][1]["name"] = json!("Renamed thermostat");
    let rows = model::parse(&t, &s, "home-test", AT, 300_000).unwrap();
    assert_eq!(thermostat(&initial).native, thermostat(&rows).native);
    assert_eq!(thermostat(&rows).name, "Renamed thermostat");
    assert_eq!(initial[0].native, "home-test.room.101");
    let duplicate = s["body"]["home"]["modules"][0].clone();
    s["body"]["home"]["modules"]
        .as_array_mut()
        .unwrap()
        .push(duplicate);
    assert!(model::parse(&t, &s, "home-test", AT, 300_000).is_err());
}
#[test]
fn bad_response_and_unknown_models_cannot_supply_demand() {
    let mut s = status();
    s["body"]["home"]["id"] = json!("different-home");
    assert!(model::parse(&topology(), &s, "home-test", AT, 300_000).is_err());
    let mut t = topology();
    s = status();
    t["body"]["homes"][0]["modules"][0]["type"] = json!("unknown-variant");
    let rows = model::parse(&t, &s, "home-test", AT, 300_000).unwrap();
    assert!(!rows.iter().any(|r| r.model == "unknown-variant"));
    s["body"]["home"]["rooms"][0]["therm_measured_temperature"] = json!([20]);
    assert!(model::parse(&t, &s, "home-test", AT, 300_000).is_err());
}
#[test]
fn options_are_inert_bounded_and_have_no_secret_or_command_url() {
    assert!(Netatmo::new(config()).is_ok());
    let mut c = config();
    c.poll_seconds = 10;
    assert!(Netatmo::new(c).is_err());
    let mut c = config();
    c.home_id = "home?write=true".into();
    assert!(Netatmo::new(c).is_err());
    assert!(
        serde_json::from_value::<Config>(json!({"home_id":"test", "access_token":"secret"}))
            .is_err()
    );
    assert!(
        serde_json::from_value::<Config>(json!({"home_id":"test", "url":"http://private"}))
            .is_err()
    );
}

#[derive(Default)]
struct Mock {
    replies: Mutex<VecDeque<Result<Response, Failure>>>,
    requests: Mutex<Vec<(String, String, String, String)>>,
}
impl Mock {
    fn add(&self, status: u16, body: &Json) {
        self.replies.lock().unwrap().push_back(Ok(Response {
            status: StatusCode::from_u16(status).unwrap(),
            headers: HeaderMap::new(),
            body: body.to_string().into(),
        }));
    }
}
impl Transport for Mock {
    fn send(
        &self,
        request: Request<Full<moli_net::Body>>,
    ) -> BoxFuture<'_, Result<Response, Failure>> {
        Box::pin(async move {
            let (parts, body) = request.into_parts();
            let body = body.collect().await.unwrap().to_bytes();
            self.requests.lock().unwrap().push((
                parts.method.to_string(),
                parts.uri.to_string(),
                parts
                    .headers
                    .get("authorization")
                    .map_or("", |v| v.to_str().unwrap())
                    .to_owned(),
                String::from_utf8(body.to_vec()).unwrap(),
            ));
            self.replies
                .lock()
                .unwrap()
                .pop_front()
                .expect("unexpected extra HTTP call")
        })
    }
}
#[tokio::test]
async fn client_sends_only_allowlisted_reads_and_sanitizes_failures() {
    let m = Mock::default();
    m.add(200, &topology());
    m.add(200, &status());
    client::read(&m, "synthetic-access", "home-test", true)
        .await
        .unwrap();
    client::read(&m, "synthetic-access", "home-test", false)
        .await
        .unwrap();
    for (method, path, auth, body) in m.requests.lock().unwrap().iter() {
        assert_eq!(method, "GET");
        assert!(
            path == "/api/homesdata?home_id=home-test"
                || path == "/api/homestatus?home_id=home-test"
        );
        assert_eq!(auth, "Bearer synthetic-access");
        assert!(body.is_empty());
        assert!(!path.contains("synthetic-access"));
    }
    m.add(401, &json!({"error":"synthetic-secret-must-not-leak"}));
    assert_eq!(
        client::read(&m, "synthetic-access", "test", false)
            .await
            .unwrap_err(),
        Failure::Unauthorized
    );
    m.replies.lock().unwrap().push_back(Err(Failure::Network));
    assert_eq!(
        client::read(&m, "synthetic-access", "test", false)
            .await
            .unwrap_err(),
        Failure::Network
    );
    m.add(200, &json!("malformed"));
    assert_eq!(
        client::read(&m, "synthetic-access", "test", false)
            .await
            .unwrap_err(),
        Failure::InvalidResponse
    );
    m.add(200, &json!({"status":"ok", "error":{"code":26}}));
    assert_eq!(
        client::read(&m, "synthetic-access", "test", false)
            .await
            .unwrap_err(),
        Failure::RateLimited(3600)
    );
}
#[tokio::test]
async fn oauth_rotation_is_read_scope_only_encoded_and_redacted() {
    let m = Mock::default();
    let tokens = Tokens {
        access_token: "old-access".into(),
        refresh_token: "old-refresh+&".into(),
        expires_at: 0,
    };
    m.add(200, &json!({"access_token":"next-access","refresh_token":"next-refresh","expires_in":10800,"scope":["read_thermostat"]}));
    let next = client::refresh(&m, &tokens, "app-test", "synthetic-secret+&", 1000)
        .await
        .unwrap();
    assert_eq!(next.expires_at, 11800);
    assert_eq!(next.refresh_token, "next-refresh");
    {
        let reqs = m.requests.lock().unwrap();
        assert_eq!((&*reqs[0].0, &*reqs[0].1), ("POST", "/oauth2/token"));
        assert!(reqs[0].3.contains("old-refresh%2B%26"));
        assert!(!reqs[0].3.contains("write_thermostat"));
    }
    assert_eq!(format!("{next:?}"), "Tokens(<redacted>)");
    m.add(200, &json!({"access_token":"next-access","refresh_token":"next-refresh","expires_in":10800,"scope":["read_thermostat","write_thermostat"]}));
    assert_eq!(
        client::refresh(&m, &tokens, "app", "secret", 1000)
            .await
            .unwrap_err(),
        Failure::Credentials
    );
}
#[tokio::test]
async fn rate_limit_honors_retry_after_and_response_bounds() {
    let m = Mock::default();
    let mut headers = HeaderMap::new();
    headers.insert("retry-after", "7200".parse().unwrap());
    m.replies.lock().unwrap().push_back(Ok(Response {
        status: StatusCode::TOO_MANY_REQUESTS,
        headers,
        body: "not JSON".into(),
    }));
    let f = client::read(&m, "test", "test", false).await.unwrap_err();
    assert_eq!(f.retry_seconds(120), 7200);
    let mut headers = HeaderMap::new();
    let until = std::time::SystemTime::now() + Duration::from_secs(7200);
    headers.insert(
        "retry-after",
        httpdate::fmt_http_date(until).parse().unwrap(),
    );
    m.replies.lock().unwrap().push_back(Ok(Response {
        status: StatusCode::TOO_MANY_REQUESTS,
        headers,
        body: "".into(),
    }));
    let Failure::RateLimited(secs) = client::read(&m, "test", "test", false).await.unwrap_err()
    else {
        panic!("quota expected");
    };
    assert!((7198..=7200).contains(&secs));
    m.replies.lock().unwrap().push_back(Ok(Response {
        status: StatusCode::OK,
        headers: HeaderMap::new(),
        body: vec![b' '; client::MAX_BODY + 1].into(),
    }));
    assert_eq!(
        client::read(&m, "test", "test", false).await.unwrap_err(),
        Failure::InvalidResponse
    );
}

struct Harness {
    hub: Hub,
    cancel: CancellationToken,
    task: tokio::task::JoinHandle<()>,
    mock: Arc<Mock>,
    data: std::path::PathBuf,
}
impl Harness {
    async fn start(expired: bool) -> Self {
        static SERIAL: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let data = std::env::temp_dir().join(format!(
            "moli-netatmo-{}-{}",
            std::process::id(),
            SERIAL.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&data).unwrap();
        let hub = Hub::new(HubOptions {
            secrets: Some((data.join("secrets.enc"), MasterKey::new("22".repeat(32)))),
            ..HubOptions::default()
        })
        .unwrap();
        let instance = InstanceId::from("energy-test");
        let tokens = Tokens {
            access_token: "test-access".into(),
            refresh_token: "test-refresh".into(),
            expires_at: if expired { 0 } else { now_ms() / 1000 + 3600 },
        };
        hub.store_secret(
            &instance,
            TOKEN_SECRET,
            &serde_json::to_string(&tokens).unwrap(),
        )
        .await
        .unwrap();
        hub.store_secret(&instance, "client_secret", "test-client-secret")
            .await
            .unwrap();
        let mock = Arc::new(Mock::default());
        if expired {
            mock.add(200, &json!({"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":10800}));
        }
        let mut s = status();
        s["time_server"] = json!(now_ms() / 1000);
        mock.add(200, &live_topology());
        mock.add(200, &s);
        let mut d = Netatmo::new(config()).unwrap();
        d.transport = mock.clone();
        let cancel = CancellationToken::new();
        let task = spawn_driver(&hub, instance, Arc::new(d), cancel.clone());
        for _ in 0..10000 {
            if hub.stats().drivers_running == 1 {
                break;
            }
            tokio::task::yield_now().await;
        }
        assert_eq!(hub.stats().drivers_running, 1);
        Self {
            hub,
            cancel,
            task,
            mock,
            data,
        }
    }
    fn point(&self, key: &str) -> Option<Value> {
        self.hub
            .state(&PointId::from(format!(
                "energy-test:home-test.module.aa:bb:cc:dd:ee:01/{key}"
            )))
            .map(|s| s.value)
    }
    async fn stop(self) {
        self.cancel.cancel();
        self.task.await.unwrap();
        drop(self.hub);
        std::fs::remove_dir_all(self.data).unwrap();
    }
}
#[tokio::test(start_paused = true)]
async fn driver_clears_old_demand_on_network_loss_retains_identity_then_recovers() {
    let h = Harness::start(false).await;
    assert_eq!(h.point("boiler_status"), Some(Value::Bool(true)));
    for d in &h.hub.snapshot().devices {
        assert!(d.device.points.iter().all(|p| !p.access.write));
    }
    let ids: Vec<_> = h
        .hub
        .snapshot()
        .devices
        .iter()
        .map(|d| d.device.id.clone())
        .collect();
    h.mock
        .replies
        .lock()
        .unwrap()
        .push_back(Err(Failure::Network));
    tokio::time::advance(Duration::from_secs(125)).await;
    for _ in 0..20 {
        tokio::task::yield_now().await;
    }
    assert_eq!(h.point("boiler_status"), Some(Value::Null));
    assert!(
        h.hub
            .snapshot()
            .devices
            .iter()
            .all(|d| d.online == Some(false))
    );
    h.mock.add(200, &live_topology());
    let mut s = status();
    s["time_server"] = json!(now_ms() / 1000);
    s["body"]["home"]["modules"][1]["boiler_status"] = json!(false);
    h.mock.add(200, &s);
    tokio::time::advance(Duration::from_secs(125)).await;
    for _ in 0..20 {
        tokio::task::yield_now().await;
    }
    assert_eq!(h.point("boiler_status"), Some(Value::Bool(false)));
    assert_eq!(
        ids,
        h.hub
            .snapshot()
            .devices
            .iter()
            .map(|d| d.device.id.clone())
            .collect::<Vec<_>>()
    );
    h.stop().await;
}
#[tokio::test(start_paused = true)]
async fn rotated_credentials_are_persisted_together_before_reads() {
    let h = Harness::start(true).await;
    let stored = h
        .hub
        .secret(&InstanceId::from("energy-test"), TOKEN_SECRET)
        .unwrap();
    let tokens = Tokens::parse(&stored).unwrap();
    assert_eq!(tokens.access_token, "rotated-access");
    assert_eq!(tokens.refresh_token, "rotated-refresh");
    {
        let reqs = h.mock.requests.lock().unwrap();
        assert_eq!(reqs[0].1, "/oauth2/token");
        assert_eq!(reqs[1].2, "Bearer rotated-access");
    }
    h.stop().await;
}

async fn advance_poll() {
    tokio::time::advance(Duration::from_secs(125)).await;
    for _ in 0..30 {
        tokio::task::yield_now().await;
    }
}
#[tokio::test(start_paused = true)]
async fn omitted_fields_clear_previous_values_without_erasing_devices() {
    let h = Harness::start(false).await;
    h.mock.add(200, &live_topology());
    let mut s = status();
    s["time_server"] = json!(now_ms() / 1000);
    s["body"]["home"]["modules"][1]
        .as_object_mut()
        .unwrap()
        .remove("boiler_status");
    h.mock.add(200, &s);
    advance_poll().await;
    assert_eq!(h.point("boiler_status"), Some(Value::Null));
    assert_eq!(h.point("declared_demand"), Some(Value::from("missing")));
    assert_eq!(h.hub.snapshot().devices.len(), 3);
    h.stop().await;
}
#[tokio::test(start_paused = true)]
async fn failed_vault_rotation_stops_before_any_read_and_preserves_unrelated_secrets() {
    let h = Harness::start(false).await;
    let instance = InstanceId::from("energy-test");
    h.hub
        .store_secret(
            &InstanceId::from("other-driver"),
            "key",
            "unrelated-test-key",
        )
        .await
        .unwrap();
    h.hub
        .store_secret(
            &instance,
            TOKEN_SECRET,
            &serde_json::to_string(&Tokens {
                access_token: "old-access".into(),
                refresh_token: "old-refresh".into(),
                expires_at: 0,
            })
            .unwrap(),
        )
        .await
        .unwrap();
    // Simulate storage corruption after opening the synthetic vault. Do not
    // overwrite this file with a partial/new bundle when rotation cannot commit.
    let saved = std::fs::read(h.data.join("secrets.enc")).unwrap();
    std::fs::write(h.data.join("secrets.enc"), b"synthetic damaged vault").unwrap();
    h.mock.add(
        200,
        &json!({"access_token":"new-access","refresh_token":"new-refresh","expires_in":10800}),
    );
    advance_poll().await;
    assert_eq!(h.mock.requests.lock().unwrap().len(), 3); // 2 initial reads + OAuth only
    assert_eq!(h.point("boiler_status"), Some(Value::Null));
    assert_eq!(
        std::fs::read(h.data.join("secrets.enc")).unwrap(),
        b"synthetic damaged vault"
    );
    assert_eq!(
        h.hub
            .secret(&InstanceId::from("other-driver"), "key")
            .as_deref(),
        Some("unrelated-test-key")
    );
    // Repair the synthetic vault and persist the pending pair without rotating again.
    std::fs::write(h.data.join("secrets.enc"), saved).unwrap();
    h.mock.add(200, &live_topology());
    let mut s = status();
    s["time_server"] = json!(now_ms() / 1000);
    h.mock.add(200, &s);
    tokio::time::advance(Duration::from_secs(3605)).await;
    for _ in 0..10000 {
        if h.point("boiler_status") == Some(Value::Bool(true)) {
            break;
        }
        tokio::task::yield_now().await;
    }
    assert_eq!(h.point("boiler_status"), Some(Value::Bool(true)));
    {
        let reqs = h.mock.requests.lock().unwrap();
        assert_eq!(reqs.len(), 5);
        assert_eq!(reqs[3].2, "Bearer new-access");
    }
    let stored = h.hub.secret(&instance, TOKEN_SECRET).unwrap();
    assert_eq!(Tokens::parse(&stored).unwrap().refresh_token, "new-refresh");
    assert_eq!(
        h.hub
            .secret(&InstanceId::from("other-driver"), "key")
            .as_deref(),
        Some("unrelated-test-key")
    );
    h.stop().await;
}
#[tokio::test(start_paused = true)]
async fn premature_access_token_revocation_refreshes_once_on_the_next_poll() {
    let h = Harness::start(false).await;
    h.mock.add(401, &json!({"error":{"code":3}}));
    advance_poll().await;
    assert_eq!(h.point("boiler_status"), Some(Value::Null));
    h.mock.add(
        200,
        &json!({"access_token":"next-access","refresh_token":"next-refresh","expires_in":10800}),
    );
    h.mock.add(200, &live_topology());
    let mut s = status();
    s["time_server"] = json!(now_ms() / 1000);
    h.mock.add(200, &s);
    advance_poll().await;
    for _ in 0..10000 {
        if h.point("boiler_status") == Some(Value::Bool(true)) {
            break;
        }
        tokio::task::yield_now().await;
    }
    assert_eq!(h.point("boiler_status"), Some(Value::Bool(true)));
    assert_eq!(
        h.mock
            .requests
            .lock()
            .unwrap()
            .iter()
            .filter(|r| r.0 == "POST")
            .count(),
        1
    );
    h.stop().await;
}
#[tokio::test(start_paused = true)]
async fn rate_limit_suspends_requests_and_demand_for_the_full_backoff() {
    let h = Harness::start(false).await;
    h.mock.add(429, &json!({}));
    advance_poll().await;
    assert_eq!(h.point("boiler_status"), Some(Value::Null));
    tokio::time::advance(Duration::from_secs(3500)).await;
    for _ in 0..30 {
        tokio::task::yield_now().await;
    }
    assert_eq!(h.mock.requests.lock().unwrap().len(), 3);
    h.stop().await;
}

#[tokio::test(start_paused = true)]
async fn stale_cloud_replies_never_publish_current_values_and_commands_are_refused() {
    let h = Harness::start(false).await;
    let command = h
        .hub
        .command(
            &PointId::from("energy-test:home-test.module.aa:bb:cc:dd:ee:01/boiler_status"),
            Value::Bool(false),
            moli_core::Origin::Ui,
            None,
        )
        .await;
    assert!(matches!(command, Err(moli_runtime::CommandError::ReadOnly)));
    assert_eq!(h.mock.requests.lock().unwrap().len(), 2);
    h.mock.add(200, &live_topology());
    let mut s = status();
    s["time_server"] = json!(now_ms() / 1000 - 400);
    h.mock.add(200, &s);
    advance_poll().await;
    assert_eq!(h.point("boiler_status"), Some(Value::Null));
    assert_eq!(h.point("freshness"), Some(Value::from("stale")));
    assert!(
        h.hub
            .snapshot()
            .devices
            .iter()
            .all(|d| d.online == Some(false))
    );
    h.stop().await;
}

#[test]
fn a_recent_status_cannot_refresh_an_old_or_undated_topology() {
    let mut top = topology();
    top["time_server"] = json!(AT / 1000 - 400);
    let rows = model::parse(&top, &status(), "home-test", AT, 300_000).unwrap();
    assert!(rows.iter().all(|o| !o.current()));
    assert_eq!(thermostat(&rows).quality, "stale");
    top["time_server"] = Json::Null;
    let rows = model::parse(&top, &status(), "home-test", AT, 300_000).unwrap();
    assert_eq!(thermostat(&rows).quality, "unconfirmed");
    top["time_server"] = json!(AT / 1000 + 1);
    let rows = model::parse(&top, &status(), "home-test", AT, 300_000).unwrap();
    assert_eq!(thermostat(&rows).quality, "unconfirmed");
}
