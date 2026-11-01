//! Portable authenticated HTTPS adapter. Construction requires an approved
//! deployment and independent device credential; no default host is supplied.
use std::{fmt, sync::Arc, time::Duration};

use http::{HeaderValue, Request};
use http_body_util::Full;
use moli_runtime::BoxFuture;
use serde_json::{Value as Json, json};

use super::{Broker, Error, Grant, Policy, Reply, Result, SCOPE, nonce};
use crate::client::{Failure, MAX_TOKEN_BODY, Response, Transport};

struct Https {
    host: String,
}
impl Transport for Https {
    fn send(
        &self,
        request: Request<Full<moli_net::Body>>,
    ) -> BoxFuture<'_, std::result::Result<Response, Failure>> {
        Box::pin(async move {
            let (status, headers, body) = moli_net::web_with_headers(
                &self.host,
                443,
                true,
                request,
                Duration::from_secs(10),
                MAX_TOKEN_BODY,
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
/// One approved application, server and device/instance binding. Reusable on
/// Android and Linux; it never contains a provider client secret.
pub struct RemoteBroker {
    policy: Policy,
    instance: String,
    key: String,
    transport: Arc<dyn Transport>,
}
impl fmt::Debug for RemoteBroker {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("RemoteOAuthBroker(<redacted>)")
    }
}
impl RemoteBroker {
    /// Values must come from explicitly approved provisioning, never an Intent,
    /// WebView, callback query or arbitrary equipment form.
    ///
    /// # Errors
    /// Rejects non-HTTPS/non-origin URLs, invalid instance IDs and device keys.
    pub fn new(policy: Policy, origin: &str, instance: &str, device_key: String) -> Result<Self> {
        let uri: http::Uri = origin.parse().map_err(|_| Error::Input)?;
        let host = uri.host().ok_or(Error::Input)?;
        if uri.scheme_str() != Some("https")
            || uri.query().is_some()
            || uri.path() != "/"
            || origin.contains(['#', '@'])
            || uri.port_u16().is_some_and(|p| p != 443)
            || host.len() > 253
            || !host
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-'))
            || !crate::model::valid_id(instance)
            || !nonce(&device_key)
        {
            return Err(Error::Input);
        }
        Ok(Self {
            policy,
            instance: instance.to_owned(),
            key: device_key,
            transport: Arc::new(Https {
                host: host.to_owned(),
            }),
        })
    }
    async fn request(&self, operation: &str, data: Json) -> Result<Json> {
        let body = json!({"version":1,"instance":self.instance,"client_id":self.policy.client_id(),"redirect_uri":self.policy.redirect_uri(),"scope":SCOPE,"data":data}).to_string();
        if body.len() > MAX_TOKEN_BODY {
            return Err(Error::Input);
        }
        let mut authorization =
            HeaderValue::from_str(&format!("Bearer {}", self.key)).map_err(|_| Error::Input)?;
        authorization.set_sensitive(true);
        let request = Request::builder()
            .method("POST")
            .uri(format!("/v1/netatmo/{operation}"))
            .header("content-type", "application/json")
            .header("authorization", authorization)
            .body(Full::new(moli_net::Body::from(body)))
            .map_err(|_| Error::Input)?;
        let response = self
            .transport
            .send(request)
            .await
            .map_err(|_| Error::Network)?;
        if response.status.as_u16() == 429 {
            let seconds = response
                .headers
                .get("retry-after")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(3600)
                .clamp(60, 7 * 86400);
            return Err(Error::RateLimited(seconds));
        }
        if !response.status.is_success() {
            if response.body.len() <= MAX_TOKEN_BODY
                && let Ok(reply) = serde_json::from_slice::<Json>(&response.body)
            {
                let mapped = match (response.status.as_u16(), reply["error"].as_str()) {
                    (409, Some("oauth_ambiguous")) => Some(Error::Ambiguous),
                    (409, Some("oauth_replay")) => Some(Error::Replay),
                    (410, Some("oauth_expired")) => Some(Error::Expired),
                    (503, Some("storage")) => Some(Error::Storage),
                    (503, Some("oauth_setup")) => Some(Error::SetupRequired),
                    _ => None,
                };
                if let Some(error) = mapped {
                    return Err(error);
                }
            }
            return Err(if response.status.is_server_error() {
                Error::Network
            } else {
                Error::Denied
            });
        }
        if response.body.len() > MAX_TOKEN_BODY {
            return Err(Error::Input);
        }
        serde_json::from_slice(&response.body).map_err(|_| Error::Input)
    }
    #[cfg(test)]
    pub(crate) fn use_test_transport(&mut self, transport: Arc<dyn Transport>) {
        self.transport = transport;
    }
}
impl Broker for RemoteBroker {
    fn policy(&self) -> Option<&Policy> {
        Some(&self.policy)
    }
    fn begin<'a>(&'a self, state: &'a str, challenge: &'a str) -> BoxFuture<'a, Result<String>> {
        Box::pin(async move {
            if !nonce(state) || !nonce(challenge) {
                return Err(Error::Input);
            }
            let reply = self
                .request("begin", json!({"state":state,"challenge":challenge}))
                .await?;
            if reply.as_object().is_none_or(|v| v.len() != 1) {
                return Err(Error::Input);
            }
            let ticket = reply["ticket"]
                .as_str()
                .filter(|v| nonce(v))
                .ok_or(Error::Input)?;
            Ok(ticket.to_owned())
        })
    }
    fn poll<'a>(&'a self, ticket: &'a str, verifier: &'a str) -> BoxFuture<'a, Result<Reply>> {
        Box::pin(async move {
            if !nonce(ticket) || !nonce(verifier) {
                return Err(Error::Input);
            }
            let reply = self
                .request("poll", json!({"ticket":ticket,"verifier":verifier}))
                .await?;
            let object = reply.as_object().ok_or(Error::Input)?;
            match reply["status"].as_str() {
                Some("pending") if object.len() == 1 => Ok(Reply::Pending),
                Some("denied") if object.len() == 1 => Ok(Reply::Denied),
                Some("approved") if object.len() == 2 => {
                    Grant::from_broker(&reply["grant"], moli_core::now_ms()).map(Reply::Approved)
                }
                _ => Err(Error::Input),
            }
        })
    }
    fn cancel<'a>(&'a self, ticket: &'a str) -> BoxFuture<'a, Result<()>> {
        Box::pin(async move {
            if !nonce(ticket) {
                return Err(Error::Input);
            }
            if self.request("cancel", json!({"ticket":ticket})).await? != json!({"cancelled":true})
            {
                return Err(Error::Input);
            }
            Ok(())
        })
    }
    fn renew<'a>(
        &'a self,
        instance: &'a str,
        previous: &'a Grant,
        now: u64,
    ) -> BoxFuture<'a, Result<Grant>> {
        Box::pin(async move {
            if instance != self.instance {
                return Err(Error::State);
            }
            let previous: Json =
                serde_json::from_str(&previous.vault_bundle()?).map_err(|_| Error::Tokens)?;
            let reply = self.request("renew", json!({"previous":previous})).await?;
            Grant::from_broker(&reply, now)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use http::{HeaderMap, StatusCode};
    use http_body_util::BodyExt as _;
    use std::{collections::VecDeque, sync::Mutex};
    struct Mock {
        replies: Mutex<VecDeque<Response>>,
        requests: Mutex<Vec<(String, Json)>>,
    }
    impl Transport for Mock {
        fn send(
            &self,
            request: Request<Full<moli_net::Body>>,
        ) -> BoxFuture<'_, std::result::Result<Response, Failure>> {
            Box::pin(async move {
                assert_eq!(request.method(), "POST");
                assert!(request.headers()["authorization"].is_sensitive());
                assert_eq!(
                    request.headers()["authorization"],
                    format!("Bearer {}", "a".repeat(64)).as_str()
                );
                let path = request.uri().to_string();
                assert!(!path.contains('?'));
                let body = request.into_body().collect().await.unwrap().to_bytes();
                let json: Json = serde_json::from_slice(&body).unwrap();
                assert_eq!(json["scope"], SCOPE);
                assert_eq!(json["instance"], "energy-test");
                assert_eq!(json["client_id"], "app-test");
                assert!(json.get("client_secret").is_none());
                self.requests.lock().unwrap().push((path, json));
                Ok(self
                    .replies
                    .lock()
                    .unwrap()
                    .pop_front()
                    .expect("unexpected request"))
            })
        }
    }
    fn policy() -> Policy {
        Policy::new(
            "app-test",
            "https://oauth.example.com/oauth/netatmo/callback",
        )
        .unwrap()
    }
    fn broker(replies: Vec<Json>) -> (RemoteBroker, Arc<Mock>) {
        let mock = Arc::new(Mock {
            replies: Mutex::new(
                replies
                    .into_iter()
                    .map(|v| Response {
                        status: StatusCode::OK,
                        headers: HeaderMap::new(),
                        body: v.to_string().into(),
                    })
                    .collect(),
            ),
            requests: Mutex::new(Vec::new()),
        });
        let mut broker = RemoteBroker::new(
            policy(),
            "https://oauth.example.com",
            "energy-test",
            "a".repeat(64),
        )
        .unwrap();
        broker.use_test_transport(mock.clone());
        (broker, mock)
    }
    #[test]
    fn provisioning_is_https_origin_only_and_debug_is_redacted() {
        for origin in [
            "http://oauth.example.com",
            "https://user@example.com",
            "https://oauth.example.com/path",
            "https://oauth.example.com?x=1",
            "https://oauth.example.com#fragment",
            "https://oauth.example.com:8443",
        ] {
            assert!(RemoteBroker::new(policy(), origin, "energy-test", "a".repeat(64)).is_err());
        }
        let (b, _) = broker(vec![]);
        assert_eq!(format!("{b:?}"), "RemoteOAuthBroker(<redacted>)");
    }
    #[tokio::test]
    async fn flow_and_renewal_use_bound_authenticated_posts_and_strict_replies() {
        let now = moli_core::now_ms();
        let grant = json!({"scope":SCOPE,"access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_at":now/1000+3600});
        let (b, m) = broker(vec![
            json!({"ticket":"b".repeat(64)}),
            json!({"status":"approved","grant":grant}),
            grant.clone(),
            json!({"cancelled":true}),
        ]);
        let ticket = b.begin(&"c".repeat(64), &"d".repeat(64)).await.unwrap();
        let Reply::Approved(previous) = b.poll(&ticket, &"e".repeat(64)).await.unwrap() else {
            panic!("approved expected")
        };
        assert!(b.renew("foreign-instance", &previous, now).await.is_err());
        b.renew("energy-test", &previous, now).await.unwrap();
        b.cancel(&ticket).await.unwrap();
        let paths: Vec<_> = m
            .requests
            .lock()
            .unwrap()
            .iter()
            .map(|v| v.0.clone())
            .collect();
        assert_eq!(
            paths,
            [
                "/v1/netatmo/begin",
                "/v1/netatmo/poll",
                "/v1/netatmo/renew",
                "/v1/netatmo/cancel"
            ]
        );
    }
    #[tokio::test]
    async fn malformed_partial_wider_scope_and_oversized_replies_are_rejected() {
        let (b, _) = broker(vec![
            json!({"ticket":"b".repeat(64),"extra":true}),
            json!({"status":"approved","grant":{"access_token":"partial"}}),
            json!({"status":"pending","token":"unexpected"}),
        ]);
        assert!(b.begin(&"c".repeat(64), &"d".repeat(64)).await.is_err());
        assert!(b.poll(&"b".repeat(64), &"e".repeat(64)).await.is_err());
        assert!(b.poll(&"b".repeat(64), &"e".repeat(64)).await.is_err());
        let (b, m) = broker(vec![]);
        m.replies.lock().unwrap().push_back(Response {
            status: StatusCode::OK,
            headers: HeaderMap::new(),
            body: vec![b' '; MAX_TOKEN_BODY + 1].into(),
        });
        assert!(b.begin(&"c".repeat(64), &"d".repeat(64)).await.is_err());
    }
}
