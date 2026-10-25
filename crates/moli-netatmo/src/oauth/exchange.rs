//! Shared token protocol; personal mobile use is explicit and experimental.
use http::Request;
use http_body_util::Full;
use serde_json::{Value as Json, json};

use super::{Code, Error, Grant, Policy, Result, SCOPE};
#[cfg(all(feature = "confidential-oauth", not(target_os = "android")))]
use crate::client::Https;
use crate::client::{self, Transport, encode};

#[cfg(all(feature = "confidential-oauth", not(target_os = "android")))]
pub async fn exchange(
    policy: &Policy,
    code: Code,
    client_secret: &str,
    now_ms: u64,
) -> Result<Grant> {
    exchange_with(&Https, policy, code, client_secret, now_ms).await
}
pub(super) async fn exchange_with(
    transport: &dyn Transport,
    policy: &Policy,
    code: Code,
    secret: &str,
    now: u64,
) -> Result<Grant> {
    if secret.is_empty() || secret.len() > 4096 || secret.chars().any(char::is_control) {
        return Err(Error::Input);
    }
    let form = format!(
        "grant_type=authorization_code&client_id={}&client_secret={}&code={}&redirect_uri={}&scope={SCOPE}",
        encode(&policy.client_id),
        encode(secret),
        encode(code.expose()),
        encode(&policy.redirect)
    );
    let request = Request::builder()
        .method("POST")
        .uri("/oauth2/token")
        .header("content-type", "application/x-www-form-urlencoded")
        .body(Full::new(moli_net::Body::from(form)))
        .map_err(|_| Error::Input)?;
    let response = transport
        .send(request)
        .await
        .map_err(|_| Error::Ambiguous)?;
    if response.body.len() <= crate::client::MAX_TOKEN_BODY
        && let Ok(reply) = serde_json::from_slice::<Json>(&response.body)
    {
        match reply["error"].as_str() {
            Some("invalid_client" | "unauthorized_client") => return Err(Error::ClientRejected),
            Some("invalid_grant") => return Err(Error::CodeRejected),
            Some("redirect_uri_mismatch" | "invalid_redirect_uri") => {
                return Err(Error::RedirectRejected);
            }
            _ => (),
        }
    }
    let answer = client::decode(&response, true).map_err(provider_error)?;
    provider_grant(&answer, now)
}
fn provider_error(error: client::Failure) -> Error {
    match error {
        client::Failure::RateLimited(seconds) => Error::RateLimited(seconds),
        client::Failure::Storage => Error::Storage,
        _ => Error::Denied,
    }
}
pub(super) fn provider_grant(answer: &Json, now: u64) -> Result<Grant> {
    if let Some(scope) = answer.get("scope")
        && scope != SCOPE
        && scope != &json!([SCOPE])
    {
        return Err(Error::Tokens);
    }
    let life = answer["expires_in"]
        .as_u64()
        .filter(|n| (60..=86400).contains(n))
        .ok_or(Error::Tokens)?;
    Grant::from_broker(
        &json!({"scope":SCOPE,"access_token":answer["access_token"],
        "refresh_token":answer["refresh_token"],"expires_at":now/1000+life}),
        now,
    )
}
/// Rotation is returned as one complete pair. A service must persist it before
/// releasing a handoff, just as the existing Moli driver does in its vault.
#[cfg(all(feature = "confidential-oauth", not(target_os = "android")))]
pub async fn refresh(
    policy: &Policy,
    previous: &Grant,
    secret: &str,
    now_ms: u64,
) -> Result<Grant> {
    let tokens = client::refresh(
        &Https,
        &previous.tokens,
        &policy.client_id,
        secret,
        now_ms / 1000,
    )
    .await
    .map_err(|_| Error::Denied)?;
    Ok(Grant { tokens })
}
