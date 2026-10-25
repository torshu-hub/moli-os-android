//! Opt-in personal experiment. No supplier compliance claim, embedded secret,
//! hosted broker or listener. The person pastes the complete redirected URL.
use super::{CallbackSession, Error, Grant, Policy, Result, SESSION_MS};
use crate::client::Https;
use crate::client::{self, Transport};
use serde::{Deserialize, Serialize};
use std::{
    fmt,
    time::{Duration, Instant},
};

/// Previously documented/tested by the Energy fork; acceptance for a new
/// registration still requires the owner's portal/browser test.
pub const REDIRECT: &str = "http://localhost/moli-netatmo";
pub const APPLICATION_SECRET: &str = "oauth_personal_application";
pub const ROTATION_SECRET: &str = "oauth_personal_rotation";

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Application {
    client_id: String,
    client_secret: String,
    experimental: bool,
}
impl fmt::Debug for Application {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("PersonalOAuthApplication(<redacted>)")
    }
}
impl Application {
    pub fn new(client_id: &str, client_secret: &str, experimental: bool) -> Result<Self> {
        Policy::personal(client_id)?;
        if !experimental
            || client_secret.is_empty()
            || client_secret.len() > 4096
            || !client_secret.bytes().all(|b| b.is_ascii_graphic())
        {
            return Err(Error::Input);
        }
        Ok(Self {
            client_id: client_id.into(),
            client_secret: client_secret.into(),
            experimental,
        })
    }
    pub fn parse(raw: &str) -> Result<Self> {
        if raw.len() > 8192 {
            return Err(Error::Input);
        }
        let app: Self = serde_json::from_str(raw).map_err(|_| Error::Input)?;
        Self::new(&app.client_id, &app.client_secret, app.experimental)
    }
    pub fn bundle(&self) -> Result<String> {
        serde_json::to_string(self).map_err(|_| Error::Input)
    }
    pub fn client_id(&self) -> &str {
        &self.client_id
    }
    pub fn policy(&self) -> Result<Policy> {
        Policy::personal(&self.client_id)
    }
    pub async fn renew(&self, previous: &Grant, now: u64) -> Result<Grant> {
        self.renew_with(&Https, previous, now).await
    }
    pub(crate) async fn renew_with(
        &self,
        transport: &dyn Transport,
        previous: &Grant,
        now: u64,
    ) -> Result<Grant> {
        let tokens = client::refresh(
            transport,
            &previous.tokens,
            &self.client_id,
            &self.client_secret,
            now / 1000,
        )
        .await
        .map_err(|e| match e {
            client::Failure::RateLimited(seconds) => Error::RateLimited(seconds),
            client::Failure::Network | client::Failure::InvalidResponse => Error::Ambiguous,
            _ => Error::Denied,
        })?;
        Ok(Grant { tokens })
    }
}
pub fn rotation_fingerprint(grant: &Grant) -> Result<String> {
    Ok(super::proof_challenge(&grant.vault_bundle()?))
}

#[derive(Default)]
pub struct Session {
    pending: Option<Attempt>,
    state: &'static str,
}
struct Attempt {
    callback: CallbackSession,
    started: u64,
    monotonic: Instant,
    grant: Option<Grant>,
}
impl fmt::Debug for Session {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PersonalOAuthSession")
            .field("state", &self.state)
            .finish_non_exhaustive()
    }
}
impl Session {
    pub fn state(&mut self, now: u64) -> &'static str {
        if self.pending.as_ref().is_some_and(|p| {
            now < p.started
                || now - p.started >= SESSION_MS
                || p.monotonic.elapsed() >= Duration::from_millis(SESSION_MS)
        }) {
            self.pending = None;
            self.state = "expired";
        }
        if self.state.is_empty() {
            "idle"
        } else {
            self.state
        }
    }
    pub fn begin(&mut self, app: &Application, now: u64) -> Result<String> {
        self.state(now);
        if self.pending.is_some() {
            return Err(Error::State);
        }
        let policy = app.policy()?;
        let state = super::random()?;
        let url = policy.authorization_url(&state)?;
        self.pending = Some(Attempt {
            callback: CallbackSession::new(policy, &state, now)?,
            started: now,
            monotonic: Instant::now(),
            grant: None,
        });
        self.state = "pending";
        Ok(url)
    }
    pub async fn complete(&mut self, app: &Application, callback: &str, now: u64) -> Result<()> {
        self.complete_with(&Https, app, callback, now).await
    }
    async fn complete_with(
        &mut self,
        transport: &dyn Transport,
        app: &Application,
        callback: &str,
        now: u64,
    ) -> Result<()> {
        if self.state(now) == "expired" {
            return Err(Error::Expired);
        }
        let pending = self.pending.as_mut().ok_or(Error::State)?;
        if pending.grant.is_some() {
            return Ok(());
        }
        let code = match pending.callback.accept(callback, now) {
            Ok(code) => code,
            Err(Error::Denied) => {
                self.pending = None;
                self.state = "denied";
                return Err(Error::Denied);
            }
            Err(e) => return Err(e),
        };
        let outcome = super::exchange::exchange_with(
            transport,
            &app.policy()?,
            code,
            &app.client_secret,
            now,
        )
        .await;
        let grant = match outcome {
            Ok(grant) => grant,
            Err(error) => {
                self.pending = None;
                self.state = "error";
                return Err(error);
            }
        };
        pending.grant = Some(grant);
        self.state = "saving";
        Ok(())
    }
    pub fn grant(&self) -> Option<&Grant> {
        self.pending.as_ref()?.grant.as_ref()
    }
    pub fn stored(&mut self) -> Result<()> {
        if self.state != "saving" {
            return Err(Error::State);
        }
        self.pending = None;
        self.state = "stored";
        Ok(())
    }
    pub fn cancel(&mut self) {
        self.pending = None;
        self.state = "idle";
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use http::{HeaderMap, Request, StatusCode};
    use http_body_util::{BodyExt as _, Full};
    use moli_runtime::BoxFuture;
    use serde_json::json;
    use std::sync::{
        Mutex,
        atomic::{AtomicUsize, Ordering},
    };
    struct Mock {
        calls: AtomicUsize,
        form: Mutex<String>,
        reply: serde_json::Value,
        status: StatusCode,
    }
    impl Transport for Mock {
        fn send(
            &self,
            request: Request<Full<moli_net::Body>>,
        ) -> BoxFuture<'_, std::result::Result<client::Response, client::Failure>> {
            Box::pin(async move {
                assert_eq!(request.method(), "POST");
                assert_eq!(request.uri(), "/oauth2/token");
                self.calls.fetch_add(1, Ordering::SeqCst);
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
                Ok(client::Response {
                    status: self.status,
                    headers: HeaderMap::new(),
                    body: self.reply.to_string().into(),
                })
            })
        }
    }
    fn app() -> Application {
        Application::new("personal-test", "synthetic-personal-secret", true).unwrap()
    }
    fn mock() -> Mock {
        Mock {
            calls: AtomicUsize::new(0),
            form: Mutex::new(String::new()),
            reply: json!({"access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_in":10800,"scope":["read_thermostat"]}),
            status: StatusCode::OK,
        }
    }
    #[tokio::test]
    async fn personal_flow_requires_full_exact_bound_callback_and_does_not_reexchange_on_save_retry()
     {
        let app = app();
        let mut session = Session::default();
        let now = moli_core::now_ms();
        let url = session.begin(&app, now).unwrap();
        assert!(!url.contains("synthetic-personal-secret"));
        let state = url.split("&state=").nth(1).unwrap();
        let m = mock();
        for bad in [
            "synthetic-code".into(),
            format!("http://localhost.evil/moli-netatmo?state={state}&code=x"),
            format!("{REDIRECT}?state=wrong&code=x"),
        ] {
            assert!(
                session
                    .complete_with(&m, &app, &bad, now + 1)
                    .await
                    .is_err()
            );
        }
        assert_eq!(m.calls.load(Ordering::SeqCst), 0);
        session
            .complete_with(
                &m,
                &app,
                &format!("{REDIRECT}?state={state}&code=synthetic-code"),
                now + 2,
            )
            .await
            .unwrap();
        let first = session.grant().unwrap().vault_bundle().unwrap();
        session
            .complete_with(&m, &app, "ignored", now + 3)
            .await
            .unwrap();
        assert_eq!(session.grant().unwrap().vault_bundle().unwrap(), first);
        assert_eq!(m.calls.load(Ordering::SeqCst), 1);
        assert!(
            m.form
                .lock()
                .unwrap()
                .contains("redirect_uri=http%3A%2F%2Flocalhost%2Fmoli-netatmo")
        );
        assert!(!m.form.lock().unwrap().contains("write_thermostat"));
        session.stored().unwrap();
        assert!(
            session
                .complete_with(&m, &app, "ignored", now + 4)
                .await
                .is_err()
        );
        assert_eq!(format!("{app:?}"), "PersonalOAuthApplication(<redacted>)");
        assert!(!format!("{session:?}").contains("synthetic"));
    }
    #[tokio::test]
    async fn personal_denial_expiry_reopen_and_provider_errors_never_return_credentials() {
        let app = app();
        let now = moli_core::now_ms();
        let mut session = Session::default();
        let url = session.begin(&app, now).unwrap();
        let state = url.split("&state=").nth(1).unwrap();
        let m = mock();
        assert_eq!(
            session
                .complete_with(
                    &m,
                    &app,
                    &format!("{REDIRECT}?state={state}&error=access_denied"),
                    now + 1
                )
                .await,
            Err(Error::Denied)
        );
        assert_eq!(session.state(now + 2), "denied");
        assert_eq!(m.calls.load(Ordering::SeqCst), 0);
        session.begin(&app, now + 2).unwrap();
        assert_eq!(session.state(now + 2 + SESSION_MS), "expired");
        assert_eq!(Session::default().state(now), "idle");
        for (provider, expected) in [
            ("invalid_client", Error::ClientRejected),
            ("invalid_grant", Error::CodeRejected),
            ("redirect_uri_mismatch", Error::RedirectRejected),
        ] {
            let mut session = Session::default();
            let url = session.begin(&app, now).unwrap();
            let state = url.split("&state=").nth(1).unwrap();
            let mut m = mock();
            m.status = StatusCode::BAD_REQUEST;
            m.reply =
                json!({"error":provider,"error_description":"synthetic-secret-must-not-escape"});
            assert_eq!(
                session
                    .complete_with(
                        &m,
                        &app,
                        &format!("{REDIRECT}?state={state}&code=x"),
                        now + 1
                    )
                    .await,
                Err(expected)
            );
            assert!(session.grant().is_none());
        }
        assert!(Application::new("personal-test", "synthetic-personal-secret", false).is_err());
        assert!(Application::parse("{}").is_err());
    }
}
