use super::*;
use crate::client::{Failure, Response};
use http::{HeaderMap, Request, StatusCode};
use http_body_util::{BodyExt as _, Full};
use moli_runtime::{BoxFuture, HubOptions, MasterKey};
use serde_json::json;
use std::{
    collections::VecDeque,
    path::PathBuf,
    sync::{
        Mutex,
        atomic::{AtomicUsize, Ordering},
    },
};

const NOW: u64 = 1_800_000_000_000;
const KEY: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const STATE: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const PROOF: &str = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
#[derive(Default)]
struct Mock {
    replies: Mutex<VecDeque<std::result::Result<Response, Failure>>>,
    calls: AtomicUsize,
    corrupt: Mutex<Option<PathBuf>>,
}
impl Mock {
    fn pair(&self, access: &str, refresh: &str) {
        self.reply(200, &json!({"access_token":access,"refresh_token":refresh,"expires_in":10800,"scope":["read_thermostat"]}));
    }
    fn reply(&self, status: u16, body: &serde_json::Value) {
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
    ) -> BoxFuture<'_, std::result::Result<Response, Failure>> {
        Box::pin(async move {
            assert_eq!(request.method(), "POST");
            assert_eq!(request.uri(), "/oauth2/token");
            let form = String::from_utf8(
                request
                    .into_body()
                    .collect()
                    .await
                    .unwrap()
                    .to_bytes()
                    .to_vec(),
            )
            .unwrap();
            assert!(form.contains("client_secret=synthetic-server-secret"));
            assert!(!form.contains("write_thermostat"));
            self.calls.fetch_add(1, Ordering::SeqCst);
            if let Some(path) = self.corrupt.lock().unwrap().take() {
                std::fs::write(path, b"damaged synthetic vault").unwrap();
            }
            self.replies
                .lock()
                .unwrap()
                .pop_front()
                .expect("unexpected provider replay")
        })
    }
}
struct Harness {
    dir: PathBuf,
    hub: Hub,
    mock: Arc<Mock>,
}
impl Harness {
    async fn new() -> Self {
        static SERIAL: AtomicUsize = AtomicUsize::new(0);
        let dir = std::env::temp_dir().join(format!(
            "moli-oauth-service-{}-{}",
            std::process::id(),
            SERIAL.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let hub = Hub::new(HubOptions {
            secrets: Some((dir.join("secrets.enc"), MasterKey::new("33".repeat(32)))),
            ..HubOptions::default()
        })
        .unwrap();
        for (account, name, value) in [
            ("account-test", DEVICE_KEY, KEY),
            ("account-test", "client_secret", "synthetic-server-secret"),
            ("other", "key", "preserved-synthetic-key"),
        ] {
            hub.store_secret(&InstanceId::from(account), name, value)
                .await
                .unwrap();
        }
        Self {
            dir,
            hub,
            mock: Arc::new(Mock::default()),
        }
    }
    fn service(&self, now: u64) -> Service {
        let mut service = Service::open(
            Policy::new(
                "app-test",
                "https://oauth.example.com/oauth/netatmo/callback",
            )
            .unwrap(),
            self.hub.clone(),
            InstanceId::from("account-test"),
            "energy-test",
            now,
        )
        .unwrap();
        service.transport = self.mock.clone();
        service
    }
    async fn authorize(&self, service: &mut Service, now: u64) -> (String, Grant) {
        let ticket = service
            .begin(KEY, STATE, &fingerprint(PROOF), now)
            .await
            .unwrap();
        self.mock.pair("initial-access", "initial-refresh");
        service.callback(&format!("https://oauth.example.com/oauth/netatmo/callback?state={STATE}&code=synthetic-code"), now+1).await.unwrap();
        let Reply::Approved(grant) = service.poll(KEY, &ticket, PROOF, now + 2).await.unwrap()
        else {
            panic!("approval expected")
        };
        (ticket, grant)
    }
}
impl Drop for Harness {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

struct RouterTransport {
    app: axum::Router,
}
impl Transport for RouterTransport {
    fn send(
        &self,
        mut request: Request<Full<moli_net::Body>>,
    ) -> BoxFuture<'_, std::result::Result<Response, Failure>> {
        use tower::ServiceExt as _;
        Box::pin(async move {
            request
                .headers_mut()
                .insert("host", "oauth.example.com".parse().unwrap());
            let response = self
                .app
                .clone()
                .oneshot(request.map(axum::body::Body::new))
                .await
                .unwrap();
            let (parts, body) = response.into_parts();
            Ok(Response {
                status: parts.status,
                headers: parts.headers,
                body: body.collect().await.unwrap().to_bytes(),
            })
        })
    }
}

#[tokio::test]
async fn real_router_and_portable_client_complete_consent_and_idempotent_renewal() {
    use crate::oauth::{Client, State, remote::RemoteBroker};
    use tower::ServiceExt as _;
    let now = moli_core::now_ms();
    let h = Harness::new().await;
    let s = h.service(now);
    let policy = s.policy.clone();
    let app = endpoint::router(s, "https://oauth.example.com").unwrap();
    let mut broker = RemoteBroker::new(
        policy,
        "https://oauth.example.com",
        "energy-test",
        KEY.into(),
    )
    .unwrap();
    broker.use_test_transport(Arc::new(RouterTransport { app: app.clone() }));
    let mut client = Client::default();
    let url = client.begin(&broker, now).await.unwrap();
    let state = url.split("&state=").nth(1).unwrap();
    h.mock.pair("first-access", "first-refresh");
    let request = Request::builder()
        .uri(format!(
            "/oauth/netatmo/callback?state={state}&code=synthetic-code"
        ))
        .header("host", "oauth.example.com")
        .body(axum::body::Body::empty())
        .unwrap();
    let response = app.clone().oneshot(request).await.unwrap();
    assert_eq!(response.status(), StatusCode::SEE_OTHER);
    assert_eq!(response.headers()["location"], "/oauth/netatmo/result");
    assert_eq!(response.headers()["referrer-policy"], "no-referrer");
    assert_eq!(client.check(&broker, now + 1).await.unwrap(), State::Saving);
    let previous = Grant {
        tokens: client.grant().unwrap().tokens.clone(),
    };
    let original_bundle = previous.vault_bundle().unwrap();
    h.hub
        .store_secret(
            &InstanceId::from("energy-test"),
            "oauth_tokens",
            &original_bundle,
        )
        .await
        .unwrap();
    client.stored().unwrap();
    h.mock.pair("next-access", "next-refresh");
    let renewed = broker
        .renew("energy-test", &previous, now + 2)
        .await
        .unwrap();
    let recovered = broker
        .renew("energy-test", &previous, now + 3)
        .await
        .unwrap();
    assert_eq!(
        renewed.vault_bundle().unwrap(),
        recovered.vault_bundle().unwrap()
    );
    // Only the client commits its mobile vault; a server refresh never rewrites it.
    assert_eq!(
        h.hub
            .secret(&InstanceId::from("energy-test"), "oauth_tokens")
            .unwrap(),
        original_bundle
    );
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 2);
    let forbidden = Request::builder()
        .method("POST")
        .uri("/v1/netatmo/begin")
        .header("host", "forged.example.com")
        .body(axum::body::Body::empty())
        .unwrap();
    assert_eq!(
        app.oneshot(forbidden).await.unwrap().status(),
        StatusCode::FORBIDDEN
    );
}

#[tokio::test]
async fn router_rejects_cross_origin_unauthenticated_foreign_and_oversized_bodies() {
    use tower::ServiceExt as _;
    let h = Harness::new().await;
    let app =
        endpoint::router(h.service(moli_core::now_ms()), "https://oauth.example.com").unwrap();
    let base = json!({"version":1,"instance":"energy-test","client_id":"app-test","redirect_uri":"https://oauth.example.com/oauth/netatmo/callback","scope":"read_thermostat","data":{"state":STATE,"challenge":fingerprint(PROOF)}});
    for (origin, key, body) in [
        ("https://foreign.example.com", KEY, base.to_string()),
        ("https://oauth.example.com", STATE, base.to_string()),
        ("https://oauth.example.com", KEY, {
            let mut v = base.clone();
            v["instance"] = json!("foreign");
            v.to_string()
        }),
        ("https://oauth.example.com", KEY, {
            let mut v = base.clone();
            v["scope"] = json!("write_thermostat");
            v.to_string()
        }),
        (
            "https://oauth.example.com",
            KEY,
            " ".repeat(crate::client::MAX_TOKEN_BODY + 1),
        ),
    ] {
        let req = Request::builder()
            .method("POST")
            .uri("/v1/netatmo/begin")
            .header("host", "oauth.example.com")
            .header("origin", origin)
            .header("authorization", format!("Bearer {key}"))
            .header("content-type", "application/json")
            .body(axum::body::Body::from(body))
            .unwrap();
        let response = app.clone().oneshot(req).await.unwrap();
        assert!(!response.status().is_success());
        assert_eq!(response.headers()["cache-control"], "no-store");
    }
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn authorization_survives_reopen_and_handoff_loss_without_replaying_code() {
    let h = Harness::new().await;
    let mut service = h.service(NOW);
    let ticket = service
        .begin(KEY, STATE, &fingerprint(PROOF), NOW)
        .await
        .unwrap();
    drop(service);
    let mut reopened = h.service(NOW + 1);
    h.mock.pair("initial-access", "initial-refresh");
    reopened
        .callback(
            &format!(
                "https://oauth.example.com/oauth/netatmo/callback?state={STATE}&code=synthetic-code"
            ),
            NOW + 2,
        )
        .await
        .unwrap();
    drop(reopened);
    let mut reopened = h.service(NOW + 3);
    for _ in 0..2 {
        let Reply::Approved(grant) = reopened.poll(KEY, &ticket, PROOF, NOW + 4).await.unwrap()
        else {
            panic!("approval expected")
        };
        assert_eq!(grant.tokens.refresh_token, "initial-refresh");
    }
    assert_eq!(
        reopened.callback("ignored", NOW + 5).await,
        Err(Error::Replay)
    );
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 1);
    assert!(!format!("{reopened:?}").contains("initial"));
    let raw = std::fs::read(h.dir.join("secrets.enc")).unwrap();
    assert!(!raw.windows(15).any(|w| w == b"initial-refresh"));
    assert_eq!(
        h.hub.secret(&InstanceId::from("other"), "key").as_deref(),
        Some("preserved-synthetic-key")
    );
}
#[tokio::test]
async fn foreign_key_proof_instance_and_changed_registration_are_rejected() {
    let h = Harness::new().await;
    let mut s = h.service(NOW);
    assert_eq!(
        s.begin(STATE, STATE, &fingerprint(PROOF), NOW)
            .await
            .unwrap_err(),
        Error::Denied
    );
    let (ticket, old) = h.authorize(&mut s, NOW).await;
    assert_eq!(
        s.poll(KEY, &ticket, STATE, NOW + 3).await.unwrap_err(),
        Error::State
    );
    assert_eq!(
        s.renew(KEY, "other-instance", &old, NOW + 3)
            .await
            .unwrap_err(),
        Error::State
    );
    assert!(
        Service::open(
            Policy::new(
                "other-app",
                "https://oauth.example.com/oauth/netatmo/callback"
            )
            .unwrap(),
            h.hub.clone(),
            InstanceId::from("account-test"),
            "energy-test",
            NOW + 3
        )
        .is_err()
    );
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 1);
}
#[tokio::test]
async fn renewal_is_recovered_after_reopen_without_refreshing_the_old_pair_twice() {
    let h = Harness::new().await;
    let mut s = h.service(NOW);
    let (_, old) = h.authorize(&mut s, NOW).await;
    h.mock.pair("next-access", "next-refresh");
    let next = s.renew(KEY, "energy-test", &old, NOW + 3).await.unwrap();
    assert_eq!(next.tokens.refresh_token, "next-refresh");
    drop(s);
    let mut s = h.service(NOW + 4);
    let recovered = s.renew(KEY, "energy-test", &old, NOW + 5).await.unwrap();
    assert_eq!(
        recovered.vault_bundle().unwrap(),
        next.vault_bundle().unwrap()
    );
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 2);
    s.forget(KEY).await.unwrap();
    assert!(s.renew(KEY, "energy-test", &old, NOW + 6).await.is_err());
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 2);
}
#[tokio::test]
async fn timeout_after_send_stays_ambiguous_across_restart_without_another_provider_call() {
    let h = Harness::new().await;
    let mut s = h.service(NOW);
    let (_, old) = h.authorize(&mut s, NOW).await;
    h.mock
        .replies
        .lock()
        .unwrap()
        .push_back(Err(Failure::Network));
    assert_eq!(
        s.renew(KEY, "energy-test", &old, NOW + 3)
            .await
            .unwrap_err(),
        Error::Ambiguous
    );
    drop(s);
    let mut s = h.service(NOW + 4);
    assert_eq!(
        s.renew(KEY, "energy-test", &old, NOW + 5)
            .await
            .unwrap_err(),
        Error::Ambiguous
    );
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 2);
}
#[tokio::test]
async fn failed_save_retries_the_same_pair_and_never_releases_it_before_commit() {
    let h = Harness::new().await;
    let mut s = h.service(NOW);
    let (ticket, old) = h.authorize(&mut s, NOW).await;
    let saved = std::fs::read(h.dir.join("secrets.enc")).unwrap();
    h.mock.pair("next-access", "next-refresh");
    *h.mock.corrupt.lock().unwrap() = Some(h.dir.join("secrets.enc"));
    assert_eq!(
        s.renew(KEY, "energy-test", &old, NOW + 3)
            .await
            .unwrap_err(),
        Error::Storage
    );
    assert_eq!(
        s.poll(KEY, &ticket, PROOF, NOW + 4).await.unwrap_err(),
        Error::Storage
    );
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 2);
    std::fs::write(h.dir.join("secrets.enc"), saved).unwrap();
    let next = s.renew(KEY, "energy-test", &old, NOW + 5).await.unwrap();
    assert_eq!(next.tokens.refresh_token, "next-refresh");
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 2);
}
#[tokio::test]
async fn quota_deadline_persists_and_expired_flows_never_exchange() {
    let h = Harness::new().await;
    let mut s = h.service(NOW);
    let (_, old) = h.authorize(&mut s, NOW).await;
    h.mock.reply(429, &json!({}));
    assert_eq!(
        s.renew(KEY, "energy-test", &old, NOW + 3)
            .await
            .unwrap_err(),
        Error::RateLimited(3600)
    );
    drop(s);
    let mut s = h.service(NOW + 4);
    assert!(matches!(
        s.renew(KEY, "energy-test", &old, NOW + 5).await,
        Err(Error::RateLimited(_))
    ));
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 2);
    let h = Harness::new().await;
    let mut s = h.service(NOW);
    s.begin(KEY, STATE, &fingerprint(PROOF), NOW).await.unwrap();
    assert_eq!(
        s.callback("ignored", NOW + SESSION_MS).await,
        Err(Error::Expired)
    );
    assert_eq!(h.mock.calls.load(Ordering::SeqCst), 0);
}
