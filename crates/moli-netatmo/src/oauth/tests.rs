use super::*;
use serde_json::json;
use std::sync::{
    Mutex,
    atomic::{AtomicUsize, Ordering},
};

const NOW: u64 = 1_800_000_000_000;
#[tokio::test]
async fn homes_are_private_choices_and_do_not_select_or_modify_an_instance() {
    use crate::client::{Failure, Response, Transport};
    use http::{HeaderMap, Request, StatusCode};
    use http_body_util::Full;
    struct Homes {
        body: Json,
    }
    impl Transport for Homes {
        fn send(
            &self,
            request: Request<Full<moli_net::Body>>,
        ) -> BoxFuture<'_, std::result::Result<Response, Failure>> {
            Box::pin(async move {
                assert_eq!(request.method(), "GET");
                assert_eq!(request.uri(), "/api/homesdata");
                assert!(request.headers()["authorization"].is_sensitive());
                Ok(Response {
                    status: StatusCode::OK,
                    headers: HeaderMap::new(),
                    body: self.body.to_string().into(),
                })
            })
        }
    }
    let g = grant();
    let homes=g.homes_with(&Homes {body:json!({"status":"ok","body":{"homes":[{"id":"home-a","name":"Synthetic A"},{"id":"home-b","name":"Synthetic B"}]}})},NOW).await.unwrap();
    assert_eq!(homes.len(), 2);
    assert_eq!(homes[0].id, "home-a");
    assert_eq!(format!("{:?}", homes[0]), "OAuthHome(<redacted>)");
    assert!(
        g.homes_with(
            &Homes {
                body: json!({"status":"ok","body":{"homes":[{"id":"home-a"},{"id":"home-a"}]}})
            },
            NOW
        )
        .await
        .is_err()
    );
    assert!(
        g.homes_with(&Homes { body: Json::Null }, NOW + 86_400_000)
            .await
            .is_err()
    );
}
fn policy() -> Policy {
    Policy::new(
        "synthetic-client",
        "https://oauth.example.com/netatmo/return",
    )
    .unwrap()
}
fn grant() -> Grant {
    Grant::from_broker(&json!({"access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_at":NOW/1000+10800,"scope":SCOPE}),NOW).unwrap()
}
struct Fake {
    policy: Policy,
    state: Mutex<String>,
    challenge: Mutex<String>,
    polls: AtomicUsize,
    approved: bool,
    delayed_expiry: Option<u64>,
}
impl Fake {
    fn new(approved: bool) -> Self {
        Self {
            policy: policy(),
            state: Mutex::new(String::new()),
            challenge: Mutex::new(String::new()),
            polls: AtomicUsize::new(0),
            approved,
            delayed_expiry: None,
        }
    }
}
impl Broker for Fake {
    fn policy(&self) -> Option<&Policy> {
        Some(&self.policy)
    }
    fn begin<'a>(&'a self, state: &'a str, challenge: &'a str) -> BoxFuture<'a, Result<String>> {
        Box::pin(async move {
            state.clone_into(&mut self.state.lock().unwrap());
            challenge.clone_into(&mut self.challenge.lock().unwrap());
            Ok("b".repeat(64))
        })
    }
    fn poll<'a>(&'a self, _: &'a str, verifier: &'a str) -> BoxFuture<'a, Result<Reply>> {
        Box::pin(async move {
            assert_eq!(*self.challenge.lock().unwrap(), proof_challenge(verifier));
            self.polls.fetch_add(1, Ordering::SeqCst);
            Ok(if self.approved {
                let mut pair = grant();
                if let Some(expires) = self.delayed_expiry {
                    pair.tokens.expires_at = expires;
                }
                Reply::Approved(pair)
            } else {
                Reply::Denied
            })
        })
    }
    fn cancel<'a>(&'a self, _: &'a str) -> BoxFuture<'a, Result<()>> {
        Box::pin(async { Ok(()) })
    }
}
#[tokio::test]
async fn browser_flow_binds_random_state_and_proof_and_preserves_an_unsaved_pair() {
    let broker = Fake::new(true);
    let mut client = Client::default();
    let url = client.begin(&broker, NOW).await.unwrap();
    assert!(url.starts_with("https://api.netatmo.com/oauth2/authorize?"));
    assert!(url.contains("scope=read_thermostat"));
    assert!(!url.contains("secret") && !url.contains("token") && !url.contains("verifier"));
    assert!(nonce(&broker.state.lock().unwrap()));
    assert_eq!(client.check(&broker, NOW + 1).await.unwrap(), State::Saving);
    let saved = client.grant().unwrap().vault_bundle().unwrap();
    // A failed write may retry the same pair without polling/redeeming twice.
    assert_eq!(client.check(&broker, NOW + 2).await.unwrap(), State::Saving);
    assert_eq!(saved, client.grant().unwrap().vault_bundle().unwrap());
    assert_eq!(broker.polls.load(Ordering::SeqCst), 1);
    client.stored().unwrap();
    assert_eq!(client.state(NOW + 3), State::Stored);
    assert!(client.grant().is_none());
    assert_eq!(
        client.check(&broker, NOW + 4).await.unwrap_err(),
        Error::State
    );
    assert!(!format!("{client:?}").contains("synthetic-access"));
}
#[tokio::test]
async fn denial_cancel_process_restart_and_expiry_do_not_restore_a_pending_authorization() {
    let broker = Fake::new(false);
    let mut client = Client::default();
    client.begin(&broker, NOW).await.unwrap();
    assert_eq!(client.check(&broker, NOW + 1).await.unwrap(), State::Denied);
    client.begin(&broker, NOW + 2).await.unwrap();
    assert_eq!(
        client
            .check(&broker, NOW + SESSION_MS + 2)
            .await
            .unwrap_err(),
        Error::Expired
    );
    client.begin(&broker, NOW + SESSION_MS + 3).await.unwrap();
    client.cancel(&broker).await.unwrap();
    assert_eq!(client.state(NOW), State::Idle);
    let mut reopened = Client::default();
    assert_eq!(
        reopened.check(&broker, NOW).await.unwrap_err(),
        Error::State
    );
    assert_eq!(
        reopened.begin(&Unconfigured, NOW).await.unwrap_err(),
        Error::SetupRequired
    );
}
#[test]
fn invalid_service_policy_and_partial_or_expired_tokens_fail_closed() {
    for redirect in [
        "http://oauth.example.com/return",
        "https://user@example.com/return",
        "https://oauth.example.com/return?code=x",
        "https://oauth.example.com/return#fragment",
    ] {
        assert!(Policy::new("client", redirect).is_err());
    }
    for json in [
        json!({"access_token":"x","scope":SCOPE,"expires_at":NOW/1000+60}),
        json!({"access_token":"x","refresh_token":"y","scope":"write_thermostat","expires_at":NOW/1000+60}),
        json!({"access_token":"x","refresh_token":"y","scope":SCOPE,"expires_at":NOW/1000-1}),
    ] {
        assert!(Grant::from_broker(&json, NOW).is_err());
    }
}
#[test]
fn callback_requires_exact_registered_redirect_state_and_single_use() {
    let state = "a".repeat(64);
    let mut session = CallbackSession::new(policy(), &state, NOW).unwrap();
    for forged in [
        format!("https://oauth.example.com.evil/return?state={state}&code=x"),
        format!(
            "https://oauth.example.com/netatmo/return?state={}&code=x",
            "b".repeat(64)
        ),
        format!("https://oauth.example.com/netatmo/return?state={state}&state={state}&code=x"),
        format!("https://oauth.example.com/netatmo/return?state={state}&code=x#fragment"),
    ] {
        assert!(session.accept(&forged, NOW + 1).is_err());
    }
    let code = session
        .accept(
            &format!("https://oauth.example.com/netatmo/return?state={state}&code=synthetic-code"),
            NOW + 2,
        )
        .unwrap();
    assert_eq!(format!("{code:?}"), "AuthorizationCode(<redacted>)");
    assert_eq!(
        session.accept("ignored", NOW + 3).unwrap_err(),
        Error::Replay
    );
}
#[test]
fn denied_or_expired_callback_never_exposes_a_code() {
    let state = "a".repeat(64);
    let mut denied = CallbackSession::new(policy(), &state, NOW).unwrap();
    assert_eq!(
        denied
            .accept(
                &format!(
                    "https://oauth.example.com/netatmo/return?state={state}&error=access_denied"
                ),
                NOW + 1
            )
            .unwrap_err(),
        Error::Denied
    );
    assert_eq!(
        denied.accept("ignored", NOW + 2).unwrap_err(),
        Error::Replay
    );
    let mut expired = CallbackSession::new(policy(), &state, NOW).unwrap();
    assert_eq!(
        expired.accept("ignored", NOW + SESSION_MS).unwrap_err(),
        Error::Expired
    );
}

#[tokio::test]
async fn a_pair_that_expired_while_waiting_at_the_service_never_reaches_storage() {
    let mut broker = Fake::new(true);
    broker.delayed_expiry = Some(NOW / 1000 + 1);
    let mut client = Client::default();
    client.begin(&broker, NOW).await.unwrap();
    assert_eq!(
        client.check(&broker, NOW + 2000).await.unwrap_err(),
        Error::Expired
    );
    assert!(client.grant().is_none());
    assert_eq!(client.state(NOW + 2001), State::Expired);
}
