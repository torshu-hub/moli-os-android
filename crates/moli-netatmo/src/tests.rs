use std::{collections::VecDeque, sync::Mutex};

use crate::client::Tokens;
use http::{HeaderMap, Request, StatusCode};
use http_body_util::{BodyExt as _, Full};
use moli_core::{InstanceId, PointId};
use moli_runtime::{Hub, HubOptions, MasterKey, spawn_driver};
use serde_json::{Value as Json, json};
use tokio_util::sync::CancellationToken;

use super::*;
use crate::client::{Response, Transport};
use oauth::account::Vault;

const AT: u64 = 1_800_000_000_000;
fn topology() -> Json {
    serde_json::from_str(include_str!(
        "../../../integrations/netatmo/fixtures/homesdata.json"
    ))
    .unwrap()
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
        poll_seconds: 30,
        topology_seconds: 300,
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
    hang_at: Mutex<Option<usize>>,
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
            let hang = *self.hang_at.lock().unwrap() == Some(self.requests.lock().unwrap().len());
            if hang {
                return std::future::pending().await;
            }
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
        write: false,
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

struct DiskVault {
    path: std::path::PathBuf,
    key: MasterKey,
    fail: std::sync::atomic::AtomicBool,
}
impl DiskVault {
    fn new() -> Self {
        static SERIAL: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let path = std::env::temp_dir().join(format!(
            "moli-energy-{}-{}",
            std::process::id(),
            SERIAL.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&path).unwrap();
        Self {
            path: path.join("secrets.enc"),
            key: MasterKey::new("22".repeat(32)),
            fail: std::sync::atomic::AtomicBool::default(),
        }
    }
    async fn seed(&self, expired: bool, write: bool) {
        self.put(
            "oauth_personal_application",
            &oauth::personal::Application::new("app-test", "synthetic-secret", true)
                .unwrap()
                .with_write(write)
                .bundle()
                .unwrap(),
        )
        .await
        .unwrap();
        self.put("oauth_tokens",&json!({"access_token":"old-access","refresh_token":"old-refresh","expires_at":if expired {0}else{now_ms()/1000+3600},"write":write}).to_string()).await.unwrap();
        self.put("unrelated", "keep").await.unwrap();
    }
}
impl oauth::account::Vault for DiskVault {
    fn get(&self, name: &str) -> oauth::Result<Option<String>> {
        moli_runtime::read_secret(&self.path, &self.key, name).map_err(|_| oauth::Error::Storage)
    }
    fn put<'a>(&'a self, name: &'a str, value: &'a str) -> BoxFuture<'a, oauth::Result<()>> {
        Box::pin(async move {
            if name == "oauth_tokens" && self.fail.load(std::sync::atomic::Ordering::Relaxed) {
                return Err(oauth::Error::Storage);
            }
            moli_runtime::set_secret(&self.path, &self.key, name, value)
                .await
                .map_err(|_| oauth::Error::Storage)
        })
    }
    fn remove<'a>(&'a self, name: &'a str) -> BoxFuture<'a, oauth::Result<()>> {
        Box::pin(async move {
            moli_runtime::forget_secret(&self.path, &self.key, name)
                .await
                .map(|_| ())
                .map_err(|_| oauth::Error::Storage)
        })
    }
}
impl Drop for DiskVault {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(self.path.parent().unwrap());
    }
}
#[tokio::test]
async fn successive_rotations_and_process_reloads_use_only_the_persisted_new_pair() {
    use oauth::account::Account;
    let vault = DiskVault::new();
    vault.seed(true, true).await;
    let mock = Mock::default();
    for n in 1..=3 {
        mock.add(200,&json!({"access_token":format!("access-{n}"),"refresh_token":format!("refresh-{n}"),"expires_in":3600,"scope":["read_thermostat","write_thermostat"]}));
        let mut account = Account::default();
        let grant = account
            .ensure_with(&mock, &vault, AT + n * 3_600_000, false)
            .await
            .unwrap();
        assert!(grant.write_allowed());
        assert_eq!(vault.get("oauth_personal_rotation").unwrap(), None);
        let tokens = Tokens::parse(&vault.get("oauth_tokens").unwrap().unwrap()).unwrap();
        assert_eq!(tokens.access_token, format!("access-{n}"));
        assert_eq!(tokens.refresh_token, format!("refresh-{n}"));
    }
    let calls = mock.requests.lock().unwrap();
    assert!(calls[0].3.contains("refresh_token=old-refresh"));
    assert!(calls[1].3.contains("refresh_token=refresh-1"));
    assert!(calls[2].3.contains("refresh_token=refresh-2"));
    assert_eq!(vault.get("unrelated").unwrap().as_deref(), Some("keep"));
}
#[tokio::test]
async fn storage_failure_retries_the_same_pair_and_a_saved_pair_recovers_a_leftover_marker() {
    use oauth::account::Account;
    let vault = DiskVault::new();
    vault.seed(true, false).await;
    let mock = Mock::default();
    mock.add(
        200,
        &json!({"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}),
    );
    vault.fail.store(true, std::sync::atomic::Ordering::Relaxed);
    let mut account = Account::default();
    assert_eq!(
        account
            .ensure_with(&mock, &vault, AT, false)
            .await
            .unwrap_err(),
        oauth::Error::Storage
    );
    // An actual process interruption before the new pair reaches disk is ambiguous.
    assert_eq!(
        Account::default()
            .ensure_with(&mock, &vault, AT, false)
            .await
            .unwrap_err(),
        oauth::Error::Ambiguous
    );
    vault
        .fail
        .store(false, std::sync::atomic::Ordering::Relaxed);
    let grant = account.ensure_with(&mock, &vault, AT, false).await.unwrap();
    assert_eq!(mock.requests.lock().unwrap().len(), 1);
    vault
        .put("oauth_personal_rotation", "old-fingerprint")
        .await
        .unwrap();
    let restored = Account::default()
        .ensure_with(&mock, &vault, AT, false)
        .await
        .unwrap();
    assert_eq!(
        restored.vault_bundle().unwrap(),
        grant.vault_bundle().unwrap()
    );
    assert_eq!(mock.requests.lock().unwrap().len(), 1);
    assert!(vault.get("oauth_personal_rotation").unwrap().is_none());
}
#[tokio::test]
async fn network_and_quota_preserve_authorization_but_invalid_grant_and_sent_ambiguity_are_distinct()
 {
    use oauth::account::Account;
    for (failure, expected) in [
        (Failure::Dns, oauth::Error::Network),
        (Failure::Connect, oauth::Error::Network),
        (Failure::Tls, oauth::Error::Network),
        (Failure::Headers, oauth::Error::Ambiguous),
    ] {
        let vault = DiskVault::new();
        vault.seed(true, false).await;
        let mock = Mock::default();
        mock.replies.lock().unwrap().push_back(Err(failure));
        assert_eq!(
            Account::default()
                .ensure_with(&mock, &vault, AT, false)
                .await
                .unwrap_err(),
            expected
        );
        assert_eq!(
            vault.get("oauth_personal_rotation").unwrap().is_some(),
            expected == oauth::Error::Ambiguous
        );
    }
    let vault = DiskVault::new();
    vault.seed(true, false).await;
    let mock = Mock::default();
    mock.add(503, &json!({"error":"temporarily_unavailable"}));
    assert_eq!(
        Account::default()
            .ensure_with(&mock, &vault, AT, false)
            .await
            .unwrap_err(),
        oauth::Error::Provider
    );
    assert!(vault.get("oauth_personal_rotation").unwrap().is_none());
    mock.add(429, &json!({}));
    assert!(matches!(
        Account::default()
            .ensure_with(&mock, &vault, AT, false)
            .await,
        Err(oauth::Error::RateLimited(_))
    ));
    assert!(vault.get("oauth_personal_rotation").unwrap().is_none());
    mock.add(400, &json!({"error":"invalid_grant"}));
    assert_eq!(
        Account::default()
            .ensure_with(&mock, &vault, AT, false)
            .await
            .unwrap_err(),
        oauth::Error::Denied
    );
    assert_eq!(
        Account::default()
            .ensure_with(&mock, &vault, AT, false)
            .await
            .unwrap_err(),
        oauth::Error::Denied
    );
    assert_eq!(mock.requests.lock().unwrap().len(), 3);
}
#[test]
fn topology_cache_does_not_age_a_new_status_and_commands_require_real_room_capabilities_and_duration()
 {
    let mut old = topology();
    old["time_server"] = json!(AT / 1000 - 600);
    let rows = model::parse(&old, &status(), "home-test", AT, 300_000).unwrap();
    assert!(rows.iter().all(Observation::current));
    let cache = Topology {
        data: topology(),
        next: Instant::now(),
        collected_at: AT,
    };
    let id = "home-test.room.101";
    let (path, args) = commands::prepare(
        "home-test",
        id,
        "setpoint",
        &Value::from("{\"temperature\":7,\"duration_minutes\":90}"),
        &cache,
        AT,
    )
    .unwrap();
    assert_eq!(path, "setroomthermpoint");
    assert!(args.contains(&("endtime".into(), (AT / 1000 + 5400).to_string())));
    assert!(
        commands::prepare(
            "home-test",
            id,
            "setpoint",
            &Value::from("{\"temperature\":21}"),
            &cache,
            AT
        )
        .is_err()
    );
    assert!(
        commands::prepare(
            "home-test",
            id,
            "home_mode",
            &Value::from("froid"),
            &cache,
            AT
        )
        .is_err()
    );
    assert!(
        commands::prepare(
            "home-test",
            id,
            "program",
            &Value::from("unrelated"),
            &cache,
            AT
        )
        .is_err()
    );
    assert_eq!(
        commands::prepare("home-test", id, "resume", &Value::Bool(true), &cache, AT)
            .unwrap()
            .0,
        "setroomthermpoint"
    );
    assert!(
        commands::prepare(
            "home-test",
            "home-test.module.aa:bb:cc:dd:ee:01",
            "resume",
            &Value::Bool(true),
            &cache,
            AT
        )
        .is_err()
    );
}
async fn settle(test: impl Fn() -> bool) {
    for _ in 0..5000 {
        if test() {
            return;
        }
        tokio::task::yield_now().await;
    }
    assert!(test());
}
#[tokio::test(start_paused = true)]
async fn driver_polls_status_at_30_seconds_and_topology_at_300_and_read_scope_cannot_write() {
    let vault = DiskVault::new();
    vault.seed(false, false).await;
    // Seed the real per-instance vault namespace used by the Hub.
    let instance = InstanceId::from("energy-test");
    let hub = Hub::new(HubOptions {
        secrets: Some((vault.path.clone(), vault.key.clone())),
        ..HubOptions::default()
    })
    .unwrap();
    hub.store_secret(
        &instance,
        "oauth_tokens",
        &vault.get("oauth_tokens").unwrap().unwrap(),
    )
    .await
    .unwrap();
    let mock = Arc::new(Mock::default());
    let mut t = topology();
    t["time_server"] = json!(now_ms() / 1000);
    let mut s = status();
    s["time_server"] = json!(now_ms() / 1000);
    mock.add(200, &s);
    mock.add(200, &t);
    let mut driver = Netatmo::new(config()).unwrap();
    driver.transport = mock.clone();
    let cancel = CancellationToken::new();
    let task = spawn_driver(&hub, instance, Arc::new(driver), cancel.clone());
    settle(|| hub.stats().drivers_running == 1).await;
    let point = PointId::from("energy-test:home-test.room.101/setpoint");
    assert!(matches!(
        hub.command(
            &point,
            Value::from("{\"temperature\":21,\"duration_minutes\":60}"),
            moli_core::Origin::Ui,
            None
        )
        .await,
        Err(moli_runtime::CommandError::ReadOnly)
    ));
    for n in 1..=10 {
        mock.add(200, &s);
        if n == 10 {
            mock.add(200, &t);
        }
        let before = mock.requests.lock().unwrap().len();
        tokio::time::advance(Duration::from_secs(30)).await;
        settle(|| mock.requests.lock().unwrap().len() > before && hub.stats().drivers_running == 1)
            .await;
    }
    {
        let calls = mock.requests.lock().unwrap();
        assert_eq!(
            calls.iter().filter(|r| r.1.contains("homesdata")).count(),
            2
        );
        assert_eq!(
            calls.iter().filter(|r| r.1.contains("homestatus")).count(),
            11
        );
    }
    cancel.cancel();
    task.await.unwrap();
}
#[tokio::test]
async fn guarded_hub_commands_send_official_payloads_without_startup_writes_or_optimistic_measurements()
 {
    let vault = DiskVault::new();
    vault.seed(false, true).await;
    let instance = InstanceId::from("energy-test");
    let hub = Hub::new(HubOptions {
        secrets: Some((vault.path.clone(), vault.key.clone())),
        ..HubOptions::default()
    })
    .unwrap();
    hub.store_secret(
        &instance,
        "oauth_tokens",
        &vault.get("oauth_tokens").unwrap().unwrap(),
    )
    .await
    .unwrap();
    let mock = Arc::new(Mock::default());
    let mut t = topology();
    t["time_server"] = json!(now_ms() / 1000);
    let mut s = status();
    s["time_server"] = json!(now_ms() / 1000);
    mock.add(200, &s);
    mock.add(200, &t);
    let mut driver = Netatmo::new(config()).unwrap();
    driver.transport = mock.clone();
    let cancel = CancellationToken::new();
    let task = spawn_driver(&hub, instance, Arc::new(driver), cancel.clone());
    settle(|| hub.stats().drivers_running == 1).await;
    assert_eq!(mock.requests.lock().unwrap().len(), 2);
    for (key, value, endpoint, field) in [
        (
            "setpoint",
            Value::from("{\"temperature\":7,\"duration_minutes\":90}"),
            "setroomthermpoint",
            "mode=manual",
        ),
        (
            "resume",
            Value::Bool(true),
            "setroomthermpoint",
            "mode=home",
        ),
        ("home_mode", Value::from("hg"), "setthermmode", "mode=hg"),
        (
            "program",
            Value::from("program-test"),
            "switchhomeschedule",
            "schedule_id=program-test",
        ),
    ] {
        mock.add(200, &json!({"status":"ok"}));
        hub.command(
            &PointId::from(format!("energy-test:home-test.room.101/{key}")),
            value,
            moli_core::Origin::Ui,
            None,
        )
        .await
        .unwrap();
        let calls = mock.requests.lock().unwrap();
        let request = calls.last().unwrap();
        assert_eq!(request.0, "POST");
        assert_eq!(request.1, format!("/api/{endpoint}"));
        assert!(request.3.contains(field));
        assert!(request.3.contains("home_id=home-test"));
    }
    assert_eq!(
        hub.state(&PointId::from(
            "energy-test:home-test.room.101/target_temperature"
        ))
        .unwrap()
        .value,
        Value::Float(20.5)
    );
    cancel.cancel();
    task.await.unwrap();
}
