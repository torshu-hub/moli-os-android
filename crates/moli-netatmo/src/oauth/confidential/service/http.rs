//! Small Axum adapter for a separate confidential HTTPS host. Use behind a TLS
//! terminator that preserves Host; do not mount it in the public home dashboard.
//! No trace middleware, credentials in URLs, CORS or cookie authentication.
use std::sync::Arc;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, HeaderValue, StatusCode, Uri},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::Deserialize;
use serde_json::{Value as JsonValue, json};
use tokio::sync::Mutex;

use super::super::super::{Error, Grant, Reply, Result, SCOPE};
use super::{Service, Tokens};

#[derive(Clone)]
struct Host {
    origin: String,
    authority: String,
    service: Arc<Mutex<Service>>,
}
impl Host {
    fn allowed(&self, headers: &HeaderMap) -> bool {
        headers.get("host").and_then(|v| v.to_str().ok()) == Some(self.authority.as_str())
            && headers
                .get("origin")
                .is_none_or(|v| v.to_str().ok() == Some(self.origin.as_str()))
    }
}

/// Construct a dormant, single-account router. `origin` must match the approved
/// redirect exactly. A single Service owner serializes account operations.
///
/// # Errors
/// Rejects a redirect that does not belong to this HTTPS origin/callback path.
pub fn router(service: Service, origin: &str) -> Result<Router> {
    let uri: http::Uri = origin.parse().map_err(|_| Error::Input)?;
    if uri.scheme_str() != Some("https")
        || uri.path() != "/"
        || uri.query().is_some()
        || origin.contains(['#', '@'])
        || uri.port_u16().is_some_and(|p| p != 443)
        || service.policy.redirect_uri() != format!("{origin}/oauth/netatmo/callback")
    {
        return Err(Error::Input);
    }
    let authority = uri.authority().ok_or(Error::Input)?.as_str().to_owned();
    Ok(Router::new()
        .route("/v1/netatmo/{operation}", post(operation))
        .route("/oauth/netatmo/callback", get(callback))
        .route("/oauth/netatmo/result", get(result))
        .layer(DefaultBodyLimit::max(crate::client::MAX_TOKEN_BODY))
        .with_state(Host {
            origin: origin.to_owned(),
            authority,
            service: Arc::new(Mutex::new(service)),
        }))
}
fn private(response: impl IntoResponse) -> Response {
    let mut response = response.into_response();
    let headers = response.headers_mut();
    headers.insert("cache-control", HeaderValue::from_static("no-store"));
    headers.insert("referrer-policy", HeaderValue::from_static("no-referrer"));
    headers.insert(
        "content-security-policy",
        HeaderValue::from_static("default-src 'none'; frame-ancestors 'none'; base-uri 'none'"),
    );
    response
}
fn error(error: Error) -> Response {
    let status = match error {
        Error::Input => StatusCode::BAD_REQUEST,
        Error::State
        | Error::Denied
        | Error::ClientRejected
        | Error::CodeRejected
        | Error::RedirectRejected => StatusCode::FORBIDDEN,
        Error::Expired => StatusCode::GONE,
        Error::Replay | Error::Ambiguous => StatusCode::CONFLICT,
        Error::RateLimited(_) => StatusCode::TOO_MANY_REQUESTS,
        Error::Storage | Error::SetupRequired => StatusCode::SERVICE_UNAVAILABLE,
        Error::Network | Error::Tokens => StatusCode::BAD_GATEWAY,
    };
    let mut response = private((status, Json(json!({"error":error.code()}))));
    if let Error::RateLimited(seconds) = error
        && let Ok(value) = HeaderValue::from_str(&seconds.clamp(60, 7 * 86400).to_string())
    {
        response.headers_mut().insert("retry-after", value);
    }
    response
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    version: u8,
    instance: String,
    client_id: String,
    redirect_uri: String,
    scope: String,
    data: JsonValue,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Begin {
    state: String,
    challenge: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Poll {
    ticket: String,
    verifier: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Cancel {
    ticket: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Renew {
    previous: Tokens,
}

async fn operation(
    State(host): State<Host>,
    Path(operation): Path<String>,
    headers: HeaderMap,
    body: std::result::Result<Json<Envelope>, axum::extract::rejection::JsonRejection>,
) -> Response {
    if !host.allowed(&headers) {
        return error(Error::Denied);
    }
    let Some(key) = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
    else {
        return error(Error::Denied);
    };
    let Ok(mut service) = host.service.try_lock() else {
        return error(Error::RateLimited(60));
    };
    if let Err(e) = service.authenticate(key) {
        return error(e);
    }
    let Ok(Json(envelope)) = body else {
        return error(Error::Input);
    };
    if envelope.version != 1
        || envelope.instance != service.device_instance
        || envelope.client_id != service.policy.client_id()
        || envelope.redirect_uri != service.policy.redirect_uri()
        || envelope.scope != SCOPE
    {
        return error(Error::State);
    }
    let now = moli_core::now_ms();
    let result: Result<JsonValue> =
        dispatch(&mut service, key, &operation, envelope.data, now).await;
    match result {
        Ok(value) => private(Json(value)),
        Err(e) => error(e),
    }
}
async fn dispatch(
    service: &mut Service,
    key: &str,
    operation: &str,
    data: JsonValue,
    now: u64,
) -> Result<JsonValue> {
    match operation {
        "begin" => {
            let body: Begin = serde_json::from_value(data).map_err(|_| Error::Input)?;
            service
                .begin(key, &body.state, &body.challenge, now)
                .await
                .map(|ticket| json!({"ticket":ticket}))
        }
        "poll" => {
            let body: Poll = serde_json::from_value(data).map_err(|_| Error::Input)?;
            match service.poll(key, &body.ticket, &body.verifier, now).await? {
                Reply::Pending => Ok(json!({"status":"pending"})),
                Reply::Denied => Ok(json!({"status":"denied"})),
                Reply::Approved(grant) => {
                    Ok(json!({"status":"approved","grant":wire_grant(&grant)?}))
                }
            }
        }
        "cancel" => {
            let body: Cancel = serde_json::from_value(data).map_err(|_| Error::Input)?;
            service.cancel(key, &body.ticket).await?;
            Ok(json!({"cancelled":true}))
        }
        "renew" => {
            let body: Renew = serde_json::from_value(data).map_err(|_| Error::Input)?;
            let raw = serde_json::to_string(&body.previous).map_err(|_| Error::Input)?;
            let tokens = Tokens::parse(&raw).map_err(|_| Error::Tokens)?;
            let instance = service.device_instance.clone();
            let grant = service
                .renew(key, &instance, &Grant { tokens }, now)
                .await?;
            wire_grant(&grant)
        }
        _ => Err(Error::Input),
    }
}
fn wire_grant(grant: &Grant) -> Result<JsonValue> {
    let mut value: JsonValue =
        serde_json::from_str(&grant.vault_bundle()?).map_err(|_| Error::Tokens)?;
    value["scope"] = json!(SCOPE);
    Ok(value)
}
async fn callback(State(host): State<Host>, headers: HeaderMap, uri: Uri) -> Response {
    if !host.allowed(&headers) {
        return error(Error::Denied);
    }
    if uri.to_string().len() > 4096 {
        return error(Error::Input);
    }
    let Ok(mut service) = host.service.try_lock() else {
        return error(Error::RateLimited(60));
    };
    let callback = format!("{}{uri}", host.origin);
    let outcome = service.callback(&callback, moli_core::now_ms()).await;
    // Always clear the query on a correlated completion/refusal. Errors are
    // sanitized and no provider data is echoed into HTML or Location.
    match outcome {
        Ok(()) | Err(Error::Denied) => private((
            StatusCode::SEE_OTHER,
            [("location", "/oauth/netatmo/result")],
        )),
        Err(e) => error(e),
    }
}
async fn result(State(host): State<Host>, headers: HeaderMap) -> Response {
    if !host.allowed(&headers) {
        return error(Error::Denied);
    }
    private(axum::response::Html(format!(
        "<!doctype html><html><meta charset=\"utf-8\"><title>{}</title><p>{}</p></html>",
        moli_i18n::tr!("pilotes.netatmo.oauth_return_title"),
        moli_i18n::tr!("pilotes.netatmo.oauth_return_message")
    )))
}
