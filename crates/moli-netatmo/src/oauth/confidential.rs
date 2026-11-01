//! Optional confidential-server primitives. Excluded from Android even when the
//! feature is selected. Hosting, device authentication and durable handoffs are
//! deliberately pending the owner's deployment decision.
pub mod service;
use super::exchange::exchange_with;
#[cfg(test)]
use super::exchange::provider_grant;
pub use super::exchange::{exchange, refresh};
use super::{Error, Grant, Result};
#[cfg(test)]
use super::{Policy, SCOPE};
#[cfg(test)]
use serde_json::json;
#[cfg(test)]
mod tests {
    use super::*;
    use crate::client::{Failure, Response, Transport};
    use http::{HeaderMap, Request, StatusCode};
    use http_body_util::{BodyExt as _, Full};
    use moli_runtime::BoxFuture;
    use std::sync::Mutex;
    struct Mock {
        form: Mutex<String>,
    }
    impl Transport for Mock {
        fn send(
            &self,
            request: Request<Full<moli_net::Body>>,
        ) -> BoxFuture<'_, std::result::Result<Response, Failure>> {
            Box::pin(async move {
                assert_eq!(request.method(), "POST");
                assert_eq!(request.uri(), "/oauth2/token");
                *self.form.lock().unwrap() = String::from_utf8(
                    request
                        .into_body()
                        .collect()
                        .await
                        .unwrap()
                        .to_bytes()
                        .to_vec(),
                )
                .unwrap();
                Ok(Response {status:StatusCode::OK,headers:HeaderMap::new(),body:json!({"access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_in":10800}).to_string().into()})
            })
        }
    }
    #[tokio::test]
    async fn authorization_code_exchange_is_server_only_and_explicitly_read_scope() {
        let policy = Policy::new("test-client", "https://oauth.example.com/return").unwrap();
        let state = "a".repeat(64);
        let code = super::super::CallbackSession::new(policy.clone(), &state, 1000)
            .unwrap()
            .accept(
                &format!("https://oauth.example.com/return?state={state}&code=synthetic%2Bcode"),
                1001,
            )
            .unwrap();
        let mock = Mock {
            form: Mutex::new(String::new()),
        };
        let grant = exchange_with(&mock, &policy, code, "synthetic+secret", 1001)
            .await
            .unwrap();
        let form = mock.form.lock().unwrap();
        assert!(form.contains("scope=read_thermostat"));
        assert!(!form.contains("write_thermostat"));
        assert!(form.contains("client_secret=synthetic%2Bsecret"));
        assert!(form.contains("code=synthetic%2Bcode"));
        assert_eq!(format!("{grant:?}"), "Grant(<redacted>)");
    }
    #[test]
    fn partial_rotation_or_a_wider_scope_never_produces_a_grant() {
        for reply in [
            json!({"access_token":"next","expires_in":10800}),
            json!({"refresh_token":"next","expires_in":10800}),
            json!({"access_token":"next","refresh_token":"next","expires_in":10800,"scope":["read_thermostat","write_thermostat"]}),
        ] {
            assert!(provider_grant(&reply, 1000).is_err());
        }
    }
}

/// In-memory one-use handoff primitive for the future service. Production must
/// persist it with its account/issuer binding and authenticate the device.
pub struct Handoff {
    challenge: String,
    created: u64,
    reply: Option<super::Reply>,
    consumed: bool,
}
impl std::fmt::Debug for Handoff {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Handoff")
            .field("consumed", &self.consumed)
            .finish_non_exhaustive()
    }
}
impl Handoff {
    pub fn new(challenge: &str, now: u64) -> Result<Self> {
        if !super::nonce(challenge) {
            return Err(Error::Input);
        }
        Ok(Self {
            challenge: challenge.to_owned(),
            created: now,
            reply: None,
            consumed: false,
        })
    }
    /// Call only after a validated Netatmo callback and successful durable save.
    pub fn approve(&mut self, grant: Grant) -> Result<()> {
        if self.consumed || self.reply.is_some() {
            return Err(Error::Replay);
        }
        self.reply = Some(super::Reply::Approved(grant));
        Ok(())
    }
    pub fn redeem(&mut self, verifier: &str, now: u64) -> Result<super::Reply> {
        if now < self.created || now - self.created >= super::SESSION_MS {
            self.consumed = true;
            self.reply = None;
            return Err(Error::Expired);
        }
        if self.consumed {
            return Err(Error::Replay);
        }
        if !super::nonce(verifier) || super::proof_challenge(verifier) != self.challenge {
            return Err(Error::State);
        }
        if let Some(reply) = self.reply.take() {
            self.consumed = true;
            Ok(reply)
        } else {
            Ok(super::Reply::Pending)
        }
    }
}
#[cfg(test)]
mod handoff_tests {
    use super::*;
    #[test]
    fn a_stolen_ticket_without_its_proof_cannot_claim_or_replay_a_handoff() {
        let proof = "a".repeat(64);
        let now = 1_800_000_000_000;
        let mut handoff = Handoff::new(&super::super::proof_challenge(&proof), now).unwrap();
        let grant=Grant::from_broker(&json!({"scope":SCOPE,"access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_at":now/1000+10800}),now).unwrap();
        handoff.approve(grant).unwrap();
        assert_eq!(
            handoff.redeem(&"b".repeat(64), now + 1).unwrap_err(),
            Error::State
        );
        assert!(matches!(
            handoff.redeem(&proof, now + 2).unwrap(),
            super::super::Reply::Approved(_)
        ));
        assert_eq!(handoff.redeem(&proof, now + 3).unwrap_err(), Error::Replay);
    }
}
