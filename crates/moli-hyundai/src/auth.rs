//! Private interactive OneApp authorization. No token or redirect URL enters a diagnostic.
use crate::{
    client::{CLIENT, Client, Error, IDP, REDIRECT, Session, Transport},
    crypto,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value as Json, json};

/// Both HA Europe transports share OneApp authentication; their vehicle APIs differ.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ApiMode {
    Europe,
    #[default]
    Gspa,
}

#[derive(Debug, Clone, Serialize)]
pub struct Diagnostic {
    pub phase: &'static str,
    pub cause: &'static str,
    pub http: Option<u16>,
    pub network_phase: Option<&'static str>,
    pub timeout: bool,
    pub retry_after_seconds: Option<u64>,
}
#[derive(Debug)]
pub struct LoginFailure {
    pub error: Error,
    pub diagnostic: Diagnostic,
}
impl LoginFailure {
    pub fn browser(cause: &str) -> Self {
        use moli_net::web_diagnostics::Phase;
        let error = match cause {
            "redirect_not_allowed" => Error::RedirectUntrusted,
            "tls_failure" => Error::NetworkFailure(Phase::Tls, false),
            "network_timeout" => Error::NetworkFailure(Phase::Headers, true),
            "network" => Error::Network,
            _ => Error::SigninRedirect,
        };
        Self::local("browser_authorize", error)
    }
    pub fn local(phase: &'static str, error: Error) -> Self {
        Self {
            error,
            diagnostic: Diagnostic {
                phase,
                cause: error.code(),
                http: None,
                network_phase: None,
                timeout: false,
                retry_after_seconds: None,
            },
        }
    }
}
impl<T: Transport> Client<T> {
    pub(crate) fn failure(&self, error: Error) -> LoginFailure {
        use moli_net::web_diagnostics::Phase;
        let (network_phase, timeout) = match error {
            Error::NetworkFailure(p, t) => (
                Some(match p {
                    Phase::Dns => "dns",
                    Phase::Connect => "connect",
                    Phase::Tls => "tls",
                    Phase::Headers => "headers",
                    Phase::Body => "body",
                }),
                t,
            ),
            _ => (None, false),
        };
        LoginFailure {
            error,
            diagnostic: Diagnostic {
                phase: self.phase,
                cause: error.code(),
                http: self.last_http,
                network_phase,
                timeout,
                retry_after_seconds: if let Error::RateLimit(s) = error {
                    Some(s)
                } else {
                    None
                },
            },
        }
    }
}
/// Saved only in the encrypted vault, ten-minute lifetime, consumed before code exchange.
#[derive(Clone, Serialize, Deserialize)]
pub struct PendingLogin {
    device: String,
    state: String,
    expires_ms: u64,
    pin: Option<String>,
    pub api_mode: ApiMode,
}
impl std::fmt::Debug for PendingLogin {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("PendingLogin(<redacted>)")
    }
}
impl PendingLogin {
    pub async fn begin(mode: ApiMode, pin: Option<String>) -> Result<Self, LoginFailure> {
        let mut client = Client::new();
        Self::begin_with(&mut client, mode, pin).await
    }
    pub(crate) async fn begin_with<T: Transport>(
        client: &mut Client<T>,
        mode: ApiMode,
        pin: Option<String>,
    ) -> Result<Self, LoginFailure> {
        check_pin(pin.as_deref()).map_err(|e| LoginFailure::local("pin", e))?;
        let device = client
            .login_identity(mode)
            .await
            .map_err(|e| client.failure(e))?;
        Ok(Self {
            device,
            state: crypto::device_id()
                .map_err(|_| LoginFailure::local("setup", Error::Protocol))?,
            expires_ms: moli_core::now_ms() + 600_000,
            pin,
            api_mode: mode,
        })
    }
    pub fn active(&self) -> bool {
        self.expires_ms > moli_core::now_ms()
    }
    pub fn public(&self) -> Json {
        json!({"authorization_url":authorize_url(&self.state),"expires_at":self.expires_ms,"api_mode":self.api_mode})
    }
    pub fn validate_callback(&self, text: &str) -> Result<String, Error> {
        if self.expires_ms < moli_core::now_ms() || text.len() > 8192 {
            return Err(Error::Callback);
        }
        let url = url::Url::parse(text).map_err(|_| Error::Callback)?;
        if !is_callback(&url) {
            return Err(Error::Callback);
        }
        let states = url
            .query_pairs()
            .filter(|(k, _)| k == "state")
            .map(|(_, v)| v.to_string())
            .collect::<Vec<_>>();
        if states != [self.state.clone()] {
            return Err(Error::Callback);
        }
        if url.query_pairs().any(|(k, _)| k == "error") {
            return Err(Error::SigninRedirect);
        }
        code_at_callback(&url).ok_or(Error::Callback)
    }
    pub async fn finish(self, callback: &str) -> Result<Session, LoginFailure> {
        let mut client = Client::new();
        self.finish_with(&mut client, callback).await
    }
    pub(crate) async fn finish_with<T: Transport>(
        self,
        client: &mut Client<T>,
        callback: &str,
    ) -> Result<Session, LoginFailure> {
        let code = self
            .validate_callback(callback)
            .map_err(|e| LoginFailure::local("browser_callback", e))?;
        let session = client
            .finish_code(self.device, &code, self.pin, self.api_mode)
            .await
            .map_err(|e| client.failure(e))?;
        Ok(session)
    }
}
pub(crate) fn check_pin(pin: Option<&str>) -> Result<(), Error> {
    if pin.is_some_and(|p| p.len() != 4 || !p.bytes().all(|b| b.is_ascii_digit())) {
        return Err(Error::Pin);
    }
    Ok(())
}
pub(crate) fn authorize_url(state: &str) -> String {
    let mut url = url::Url::parse(&format!("{IDP}/auth/api/v2/user/oauth2/authorize"))
        .expect("fixed IDP URL");
    url.query_pairs_mut().extend_pairs([
        ("response_type", "code"),
        ("client_id", CLIENT),
        ("redirect_uri", REDIRECT),
        ("lang", "fr"),
        ("state", state),
        ("country", "fr"),
    ]);
    url.into()
}
pub(crate) fn is_callback(url: &url::Url) -> bool {
    url.scheme() == "https"
        && url.host_str() == Some("oneapp.hyundai.com")
        && url.port_or_known_default() == Some(443)
        && url.path() == "/redirect"
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none()
}
pub(crate) fn code_at_callback(url: &url::Url) -> Option<String> {
    if !is_callback(url) {
        return None;
    }
    let codes = url
        .query_pairs()
        .filter(|(k, _)| k == "code")
        .map(|(_, v)| v.to_string())
        .collect::<Vec<_>>();
    if codes.len() != 1 || codes[0].is_empty() || codes[0].len() > 4096 {
        return None;
    }
    Some(codes[0].clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn browser_ticket_rejects_foreign_callbacks_state_confusion_and_expiry() {
        let ticket = PendingLogin::begin(ApiMode::Gspa, Some("0000".into()))
            .await
            .unwrap();
        let good = format!("{REDIRECT}?code=synthetic-code&state={}", ticket.state);
        assert_eq!(ticket.validate_callback(&good).unwrap(), "synthetic-code");
        for bad in [
            good.replace("oneapp.hyundai.com", "other.example.org"),
            good.replace("https://", "http://"),
            good.replace("/redirect?", "/other?"),
            good.replace(&ticket.state, "synthetic-wrong-state"),
            format!("{good}&code=second"),
            format!("{good}&state=second"),
        ] {
            assert_eq!(ticket.validate_callback(&bad), Err(Error::Callback));
        }
        assert!(!format!("{ticket:?}").contains(&ticket.state));
        let mut expired = ticket;
        expired.expires_ms = 0;
        assert_eq!(expired.validate_callback(&good), Err(Error::Callback));
    }
    #[test]
    fn code21_saved_sessions_keep_their_gspa_transport() {
        assert_eq!(
            serde_json::from_str::<ApiMode>("\"europe\"").unwrap(),
            ApiMode::Europe
        );
        let s = serde_json::json!({"device":"synthetic","cci":{},"access":"synthetic","expires":1,"user":"synthetic"});
        assert_eq!(
            serde_json::from_value::<Session>(s).unwrap().api_mode,
            ApiMode::Gspa
        );
        assert_eq!(
            LoginFailure::browser("secret https://example.org/?code=synthetic")
                .diagnostic
                .cause,
            "signin_redirect_incomplete"
        );
    }
}
