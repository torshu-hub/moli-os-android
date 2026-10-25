//! Preparatory native-client protocol. Netatmo requires a confidential server;
//! no server URL, client secret, registration or live broker is supplied here.
//! The proof below binds a Moli handoff, NOT undocumented Netatmo PKCE.
#[cfg(not(target_os = "android"))]
mod callback;
#[cfg(all(feature = "confidential-oauth", not(target_os = "android")))]
pub mod confidential;
pub mod remote;
#[cfg(not(target_os = "android"))]
pub use callback::{CallbackSession, Code};
#[cfg(all(test, not(target_os = "android")))]
mod tests;

use std::fmt;

use moli_runtime::BoxFuture;
use ring::{
    digest,
    rand::{SecureRandom as _, SystemRandom},
};
use serde_json::Value as Json;

use crate::client::{Tokens, encode};
pub const SCOPE: &str = "read_thermostat";
pub const SESSION_MS: u64 = 300_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error {
    SetupRequired,
    Input,
    State,
    Expired,
    Replay,
    Denied,
    Network,
    Tokens,
    Storage,
    Ambiguous,
    RateLimited(u64),
}
impl Error {
    pub fn code(self) -> &'static str {
        match self {
            Self::SetupRequired => "oauth_setup",
            Self::Input => "oauth_input",
            Self::State => "oauth_state",
            Self::Expired => "oauth_expired",
            Self::Replay => "oauth_replay",
            Self::Denied => "oauth_denied",
            Self::Network => "oauth_network",
            Self::Tokens => "oauth_tokens",
            Self::Storage => "storage",
            Self::Ambiguous => "oauth_ambiguous",
            Self::RateLimited(_) => "rate_limit",
        }
    }
}
pub type Result<T> = std::result::Result<T, Error>;

/// Supplied by an approved deployment, never by an arbitrary callback or JNI URL.
#[derive(Clone)]
pub struct Policy {
    client_id: String,
    redirect: String,
}
impl fmt::Debug for Policy {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("OAuthPolicy(<redacted>)")
    }
}
impl Policy {
    pub fn new(client_id: &str, redirect: &str) -> Result<Self> {
        if !crate::model::valid_id(client_id) || redirect.len() > 1024 {
            return Err(Error::Input);
        }
        let uri: http::Uri = redirect.parse().map_err(|_| Error::Input)?;
        if uri.scheme_str() != Some("https")
            || uri.host().is_none()
            || uri.authority().is_none_or(|a| a.as_str().contains('@'))
            || uri.port_u16().is_some_and(|p| p != 443)
            || uri.query().is_some()
            || redirect.contains('#')
            || !uri.path().starts_with('/')
        {
            return Err(Error::Input);
        }
        Ok(Self {
            client_id: client_id.to_owned(),
            redirect: redirect.to_owned(),
        })
    }
    pub fn client_id(&self) -> &str {
        &self.client_id
    }
    pub fn redirect_uri(&self) -> &str {
        &self.redirect
    }
    pub fn authorization_url(&self, state: &str) -> Result<String> {
        if !nonce(state) {
            return Err(Error::State);
        }
        Ok(format!(
            "https://api.netatmo.com/oauth2/authorize?response_type=code&client_id={}&redirect_uri={}&scope={SCOPE}&state={state}",
            encode(&self.client_id),
            encode(&self.redirect)
        ))
    }
}
fn nonce(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}
fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    bytes.iter().fold(String::new(), |mut out, b| {
        let _ = write!(out, "{b:02x}");
        out
    })
}
fn random() -> Result<String> {
    let mut bytes = [0; 32];
    SystemRandom::new()
        .fill(&mut bytes)
        .map_err(|_| Error::State)?;
    Ok(hex(&bytes))
}
pub fn proof_challenge(verifier: &str) -> String {
    hex(digest::digest(&digest::SHA256, verifier.as_bytes()).as_ref())
}

/// Returned only inside Rust over an approved, authenticated broker transport.
/// Its JSON never crosses to Java, a callback URL, public status or a log.
pub struct Grant {
    pub(crate) tokens: Tokens,
}
impl fmt::Debug for Grant {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Grant(<redacted>)")
    }
}
impl Grant {
    /// List the homes returned by this fresh consent, without selecting one,
    /// enabling a driver or rewriting any existing associations.
    pub async fn homes(&self) -> Result<Vec<Home>> {
        self.homes_with(&crate::client::Https, moli_core::now_ms())
            .await
    }
    async fn homes_with(
        &self,
        transport: &dyn crate::client::Transport,
        now: u64,
    ) -> Result<Vec<Home>> {
        if self.tokens.expires_at <= now / 1000 {
            return Err(Error::Expired);
        }
        let reply = crate::client::homes(transport, &self.tokens.access_token)
            .await
            .map_err(|e| match e {
                crate::client::Failure::RateLimited(seconds) => Error::RateLimited(seconds),
                crate::client::Failure::Network => Error::Network,
                _ => Error::Tokens,
            })?;
        let homes = reply["body"]["homes"]
            .as_array()
            .filter(|v| v.len() <= 32)
            .ok_or(Error::Input)?;
        let mut ids = std::collections::BTreeSet::new();
        homes
            .iter()
            .map(|home| {
                let id = home["id"]
                    .as_str()
                    .filter(|id| crate::model::valid_id(id))
                    .ok_or(Error::Input)?;
                let name = home["name"].as_str().unwrap_or(id);
                if name.len() > 256 || name.chars().any(char::is_control) || !ids.insert(id) {
                    return Err(Error::Input);
                }
                Ok(Home {
                    id: id.to_owned(),
                    name: name.to_owned(),
                })
            })
            .collect()
    }
    pub fn from_broker(json: &Json, now_ms: u64) -> Result<Self> {
        let object = json.as_object().ok_or(Error::Tokens)?;
        if object.len() != 4 || json["scope"] != SCOPE {
            return Err(Error::Tokens);
        }
        let tokens = Tokens::parse(
            &serde_json::json!({"access_token":json["access_token"],
            "refresh_token":json["refresh_token"],"expires_at":json["expires_at"]})
            .to_string(),
        )
        .map_err(|_| Error::Tokens)?;
        if tokens.expires_at <= now_ms / 1000 || tokens.expires_at > now_ms / 1000 + 86400 {
            return Err(Error::Tokens);
        }
        Ok(Self { tokens })
    }
    /// Sole persistence boundary: the unchanged portable token-pair vault format.
    pub fn vault_bundle(&self) -> Result<String> {
        serde_json::to_string(&self.tokens).map_err(|_| Error::Tokens)
    }
}
/// Account-scoped choice for an authorized private UI, never public diagnostics.
#[derive(Clone, serde::Serialize)]
pub struct Home {
    pub id: String,
    pub name: String,
}
impl fmt::Debug for Home {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("OAuthHome(<redacted>)")
    }
}
#[derive(Debug)]
pub enum Reply {
    Pending,
    Denied,
    Approved(Grant),
}

/// HTTPS authentication, pairing, issuer binding and renewal are requirements
/// for the future deployment adapter. There is deliberately no default endpoint.
pub trait Broker: Send + Sync {
    fn policy(&self) -> Option<&Policy>;
    fn begin<'a>(&'a self, state: &'a str, challenge: &'a str) -> BoxFuture<'a, Result<String>>;
    fn poll<'a>(&'a self, ticket: &'a str, verifier: &'a str) -> BoxFuture<'a, Result<Reply>>;
    fn cancel<'a>(&'a self, ticket: &'a str) -> BoxFuture<'a, Result<()>>;
    /// Renew through the same approved account/application and authenticated
    /// device binding. Retrying the previous pair must recover the same durable
    /// result, never blindly repeat a consumed provider refresh.
    fn renew<'a>(
        &'a self,
        _instance: &'a str,
        _previous: &'a Grant,
        _now_ms: u64,
    ) -> BoxFuture<'a, Result<Grant>> {
        Box::pin(async { Err(Error::SetupRequired) })
    }
}
#[derive(Debug)]
pub struct Unconfigured;
impl Broker for Unconfigured {
    fn policy(&self) -> Option<&Policy> {
        None
    }
    fn begin<'a>(&'a self, _: &'a str, _: &'a str) -> BoxFuture<'a, Result<String>> {
        Box::pin(async { Err(Error::SetupRequired) })
    }
    fn poll<'a>(&'a self, _: &'a str, _: &'a str) -> BoxFuture<'a, Result<Reply>> {
        Box::pin(async { Err(Error::SetupRequired) })
    }
    fn cancel<'a>(&'a self, _: &'a str) -> BoxFuture<'a, Result<()>> {
        Box::pin(async { Ok(()) })
    }
}
struct Pending {
    ticket: String,
    verifier: String,
    started: u64,
    monotonic: std::time::Instant,
    grant: Option<Grant>,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum State {
    Idle,
    Pending,
    Saving,
    Stored,
    Denied,
    Expired,
}
impl State {
    pub fn code(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::Pending => "pending",
            Self::Saving => "saving",
            Self::Stored => "stored",
            Self::Denied => "denied",
            Self::Expired => "expired",
        }
    }
}
/// Process-local only. Closing the process abandons the flow, keeping the vault.
/// No callback/token/nonce is serialized into preferences or saved activity state.
pub struct Client {
    pending: Option<Pending>,
    state: State,
}
impl Default for Client {
    fn default() -> Self {
        Self {
            pending: None,
            state: State::Idle,
        }
    }
}
impl fmt::Debug for Client {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("OAuthClient")
            .field("state", &self.state)
            .finish_non_exhaustive()
    }
}
impl Client {
    pub fn state(&mut self, now: u64) -> State {
        if self.pending.as_ref().is_some_and(|p| {
            now < p.started
                || now - p.started >= SESSION_MS
                || p.monotonic.elapsed() >= std::time::Duration::from_millis(SESSION_MS)
                || p.grant
                    .as_ref()
                    .is_some_and(|g| g.tokens.expires_at <= now / 1000)
        }) {
            self.pending = None;
            self.state = State::Expired;
        }
        self.state
    }
    pub async fn begin(&mut self, broker: &dyn Broker, now: u64) -> Result<String> {
        self.state(now);
        if self.pending.is_some() {
            return Err(Error::State);
        }
        let policy = broker.policy().ok_or(Error::SetupRequired)?;
        let state = random()?;
        let verifier = random()?;
        let ticket = broker.begin(&state, &proof_challenge(&verifier)).await?;
        if !nonce(&ticket) {
            return Err(Error::State);
        }
        self.pending = Some(Pending {
            ticket,
            verifier,
            started: now,
            monotonic: std::time::Instant::now(),
            grant: None,
        });
        self.state = State::Pending;
        policy.authorization_url(&state)
    }
    pub async fn check(&mut self, broker: &dyn Broker, now: u64) -> Result<State> {
        match self.state(now) {
            State::Expired => return Err(Error::Expired),
            State::Saving => return Ok(State::Saving),
            State::Pending => (),
            _ => return Err(Error::State),
        }
        let pending = self.pending.as_mut().ok_or(Error::State)?;
        match broker.poll(&pending.ticket, &pending.verifier).await? {
            Reply::Pending => (),
            Reply::Denied => {
                self.pending = None;
                self.state = State::Denied;
            }
            Reply::Approved(grant) => {
                pending.grant = Some(grant);
                self.state = State::Saving;
            }
        }
        let state = self.state(now);
        if state == State::Expired {
            Err(Error::Expired)
        } else {
            Ok(state)
        }
    }
    pub fn grant(&self) -> Option<&Grant> {
        self.pending.as_ref()?.grant.as_ref()
    }
    pub fn stored(&mut self) -> Result<()> {
        if self.state != State::Saving {
            return Err(Error::State);
        }
        self.pending = None;
        self.state = State::Stored;
        Ok(())
    }
    pub async fn cancel(&mut self, broker: &dyn Broker) -> Result<()> {
        let old = self.pending.take();
        self.state = State::Idle;
        if let Some(old) = old {
            broker.cancel(&old.ticket).await?;
        }
        Ok(())
    }
}
