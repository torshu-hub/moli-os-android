//! Fixed official endpoints only. No device write, configurable URL or redirect.
use std::{fmt, time::Duration};

use http::{HeaderMap, Request, StatusCode};
use http_body_util::Full;
use moli_net::{Body, empty};
use moli_runtime::BoxFuture;
use serde::{Deserialize, Serialize};
use serde_json::Value as Json;

pub(crate) const MAX_BODY: usize = 1024 * 1024;
pub(crate) const MAX_TOKEN_BODY: usize = 16 * 1024;
pub(crate) const TOKEN_SECRET: &str = "oauth_tokens";

/// One encrypted bundle makes access/refresh token rotation an atomic vault write.
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Tokens {
    pub access_token: String,
    pub refresh_token: String,
    pub expires_at: u64,
}
impl fmt::Debug for Tokens {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Tokens(<redacted>)")
    }
}
impl Tokens {
    pub fn parse(text: &str) -> Result<Self, Failure> {
        let tokens: Self = serde_json::from_str(text).map_err(|_| Failure::Credentials)?;
        if !valid_token(&tokens.access_token) || !valid_token(&tokens.refresh_token) {
            return Err(Failure::Credentials);
        }
        Ok(tokens)
    }
}
fn valid_token(s: &str) -> bool {
    !s.is_empty() && s.len() <= 4096 && s.bytes().all(|b| b.is_ascii_graphic())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Failure {
    Network,
    Unauthorized,
    Credentials,
    RateLimited(u64),
    InvalidResponse,
    Api,
    Storage,
}
impl Failure {
    pub fn code(self) -> &'static str {
        match self {
            Self::Network => "network",
            Self::Unauthorized | Self::Credentials => "oauth",
            Self::RateLimited(_) => "rate_limit",
            Self::InvalidResponse => "invalid_response",
            Self::Api => "api",
            Self::Storage => "storage",
        }
    }
    pub fn retry_seconds(self, poll: u64) -> u64 {
        match self {
            Self::RateLimited(secs) => secs.max(poll),
            Self::Unauthorized | Self::Credentials | Self::Storage => 3600,
            _ => poll,
        }
    }
}

pub(crate) struct Response {
    pub status: StatusCode,
    pub headers: HeaderMap,
    pub body: Body,
}
/// Injection seam for synthetic HTTP tests; production always uses public TLS.
pub(crate) trait Transport: Send + Sync {
    fn send(&self, request: Request<Full<Body>>) -> BoxFuture<'_, Result<Response, Failure>>;
}
#[derive(Debug)]
pub(crate) struct Https;
impl Transport for Https {
    fn send(&self, request: Request<Full<Body>>) -> BoxFuture<'_, Result<Response, Failure>> {
        Box::pin(async move {
            let max_body = if request.uri().path() == "/oauth2/token" {
                MAX_TOKEN_BODY
            } else {
                MAX_BODY
            };
            let (status, headers, body) = moli_net::web_with_headers(
                "api.netatmo.com",
                443,
                true,
                request,
                Duration::from_secs(10),
                max_body,
            )
            .await
            .map_err(|_| Failure::Network)?;
            Ok(Response {
                status,
                headers,
                body,
            })
        })
    }
}

/// Percent-encode every byte other than unreserved URI characters.
pub(crate) fn encode(s: &str) -> String {
    use std::fmt::Write as _;
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            out.push(char::from(b));
        } else {
            let _ = write!(out, "%{b:02X}");
        }
    }
    out
}

pub(crate) async fn read(
    transport: &dyn Transport,
    token: &str,
    home: &str,
    topology: bool,
) -> Result<Json, Failure> {
    let endpoint = if topology { "homesdata" } else { "homestatus" };
    read_path(
        transport,
        token,
        format!("/api/{endpoint}?home_id={}", encode(home)),
    )
    .await
}
pub(crate) async fn homes(transport: &dyn Transport, token: &str) -> Result<Json, Failure> {
    read_path(transport, token, "/api/homesdata".into()).await
}
async fn read_path(transport: &dyn Transport, token: &str, path: String) -> Result<Json, Failure> {
    if !valid_token(token) {
        return Err(Failure::Credentials);
    }
    let mut authorization = http::HeaderValue::from_str(&format!("Bearer {token}"))
        .map_err(|_| Failure::Credentials)?;
    authorization.set_sensitive(true);
    let request = empty(
        Request::builder()
            .method("GET")
            .uri(path)
            .header("Authorization", authorization),
    )
    .map_err(|_| Failure::Credentials)?;
    decode(&transport.send(request).await?, false)
}

#[cfg(any(not(target_os = "android"), test, feature = "personal-oauth"))]
pub(crate) async fn refresh(
    transport: &dyn Transport,
    tokens: &Tokens,
    client_id: &str,
    client_secret: &str,
    now: u64,
) -> Result<Tokens, Failure> {
    if !valid_token(client_secret) {
        return Err(Failure::Credentials);
    }
    let form = format!(
        "grant_type=refresh_token&refresh_token={}&client_id={}&client_secret={}",
        encode(&tokens.refresh_token),
        encode(client_id),
        encode(client_secret)
    );
    let request = Request::builder()
        .method("POST")
        .uri("/oauth2/token")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .body(Full::new(Body::from(form)))
        .map_err(|_| Failure::Credentials)?;
    let answer = decode(&transport.send(request).await?, true).map_err(|e| {
        if e == Failure::Unauthorized {
            Failure::Credentials
        } else {
            e
        }
    })?;
    if let Some(scopes) = answer.get("scope") {
        let valid = scopes
            .as_array()
            .is_some_and(|items| items.len() == 1 && items[0].as_str() == Some("read_thermostat"))
            || scopes.as_str() == Some("read_thermostat");
        if !valid {
            return Err(Failure::Credentials);
        }
    }
    let expires = answer["expires_in"]
        .as_u64()
        .filter(|n| (60..=86400).contains(n))
        .ok_or(Failure::InvalidResponse)?;
    let next = Tokens {
        access_token: answer["access_token"]
            .as_str()
            .ok_or(Failure::InvalidResponse)?
            .to_owned(),
        refresh_token: answer["refresh_token"]
            .as_str()
            .ok_or(Failure::InvalidResponse)?
            .to_owned(),
        expires_at: now.saturating_add(expires),
    };
    Tokens::parse(&serde_json::to_string(&next).map_err(|_| Failure::InvalidResponse)?)
}

pub(crate) fn decode(response: &Response, oauth: bool) -> Result<Json, Failure> {
    if response.status == StatusCode::TOO_MANY_REQUESTS {
        // Honor both HTTP forms. Missing/invalid headers use a one-hour pause.
        let retry = response
            .headers
            .get("retry-after")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| {
                v.parse::<u64>().ok().or_else(|| {
                    let until = httpdate::parse_http_date(v).ok()?;
                    Some(
                        until
                            .duration_since(std::time::SystemTime::now())
                            .unwrap_or_default()
                            .as_secs(),
                    )
                })
            })
            .unwrap_or(3600);
        return Err(Failure::RateLimited(retry));
    }
    if matches!(response.status.as_u16(), 401 | 403) {
        return Err(Failure::Unauthorized);
    }
    if !response.status.is_success() {
        return Err(if oauth {
            Failure::Unauthorized
        } else {
            Failure::Api
        });
    }
    if response.body.len() > if oauth { MAX_TOKEN_BODY } else { MAX_BODY } {
        return Err(Failure::InvalidResponse);
    }
    let json: Json =
        serde_json::from_slice(&response.body).map_err(|_| Failure::InvalidResponse)?;
    if json.get("error").is_some() {
        return Err(match json["error"]["code"].as_u64() {
            Some(2 | 3) => Failure::Unauthorized,
            Some(26) => Failure::RateLimited(3600),
            _ => Failure::Api,
        });
    }
    if !oauth && json["status"] != "ok" {
        return Err(Failure::InvalidResponse);
    }
    Ok(json)
}
