//! Fixed EU endpoints, bounded responses and redacted errors. No autonomous login retry.
use crate::crypto;
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use http::{HeaderMap, Request};
use http_body_util::Full;
use moli_runtime::BoxFuture;
use serde::{Deserialize, Serialize};
use serde_json::{Value as Json, json};
use std::{collections::BTreeMap, fmt, time::Duration};

pub const IDP: &str = "https://idpconnect-eu.hyundai.com";
pub const CCI: &str = "https://cci-api-eu.hyundai.com";
pub const GSPA: &str = "https://gspa-ccs-eu.hyundai.com";
pub(crate) const CLIENT: &str = "4f4953b5-02e1-4dbc-8599-87e983ee1be5";
pub(crate) const REDIRECT: &str = "https://oneapp.hyundai.com/redirect";
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    Network,
    Authentication,
    Consent,
    AuthorizeRedirect,
    SigninRedirect,
    RedirectUntrusted,
    RedirectLoop,
    InvalidCredentials,
    Callback,
    NetworkFailure(moli_net::web_diagnostics::Phase, bool),
    Captcha,
    Certificate,
    Protocol,
    Unsupported,
    Pin,
    Storage,
    Expired,
    RateLimit(u64),
    Cooldown(u64),
}
impl Error {
    pub fn code(self) -> &'static str {
        match self {
            Self::Authentication => "authentication",
            Self::Consent => "provider_terms_required",
            Self::AuthorizeRedirect => "authorize_redirect_incomplete",
            Self::SigninRedirect => "signin_redirect_incomplete",
            Self::RedirectUntrusted => "redirect_not_allowed",
            Self::RedirectLoop => "redirect_limit",
            Self::InvalidCredentials => "credentials_rejected",
            Self::Callback => "callback_invalid_or_expired",
            Self::NetworkFailure(_, true) => "network_timeout",
            Self::NetworkFailure(moli_net::web_diagnostics::Phase::Tls, false) => "tls_failure",
            Self::Network | Self::NetworkFailure(_, false) => "network",
            Self::Captcha => "captcha_or_block",
            Self::Certificate => "certificate",
            Self::Protocol => "protocol",
            Self::Unsupported => "unsupported",
            Self::Pin => "pin_rejected",
            Self::Storage => "storage",
            Self::Expired => "expired",
            Self::RateLimit(_) => "rate_limit",
            Self::Cooldown(_) => "cooldown",
        }
    }
}
impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.code())
    }
}
impl std::error::Error for Error {}
pub(crate) struct Response {
    pub status: u16,
    pub headers: HeaderMap,
    pub body: Vec<u8>,
}
pub(crate) trait Transport: Send + Sync {
    fn send(&self, req: Request<Full<moli_net::Body>>) -> BoxFuture<'_, Result<Response, Error>>;
}
#[derive(Debug)]
pub(crate) struct Https;
impl Transport for Https {
    fn send(&self, req: Request<Full<moli_net::Body>>) -> BoxFuture<'_, Result<Response, Error>> {
        Box::pin(async move {
            let host = req.uri().host().ok_or(Error::Protocol)?.to_owned();
            let uri = url::Url::parse(&req.uri().to_string()).map_err(|_| Error::Protocol)?;
            if !allowed_url(&uri) {
                return Err(Error::RedirectUntrusted);
            }
            let path = uri.path();
            let budget = if host == "idpconnect-eu.hyundai.com"
                || host == "cci-api-eu.hyundai.com"
                || legacy_authorize_page(&uri)
            {
                35
            } else if host == "prd.eu-ccapi.hyundai.com"
                && (path == "/api/v1/spa/vehicles"
                    || path.ends_with("/status/latest")
                    || path.ends_with("/carstatus/latest")
                    || path.ends_with("/drvhistory"))
            {
                15
            } else {
                3
            };
            let (status, headers, body) = moli_net::web_diagnostics::https_port(
                &host,
                uri.port_or_known_default().ok_or(Error::Protocol)?,
                req,
                Duration::from_secs(budget),
                2 * 1024 * 1024,
            )
            .await
            .map_err(|e| Error::NetworkFailure(e.phase, e.timeout))?;
            Ok(Response {
                status: status.as_u16(),
                headers,
                body: body.to_vec(),
            })
        })
    }
}
#[derive(Clone, Serialize, Deserialize)]
pub struct Session {
    #[serde(default)]
    pub api_mode: crate::auth::ApiMode,
    pub device: String,
    pub cci: Json,
    pub access: String,
    pub expires: u64,
    pub user: String,
    #[serde(default)]
    pub pin: Option<String>,
    #[serde(default)]
    pub control: Option<String>,
    #[serde(default)]
    pub control_expires: u64,
}
impl fmt::Debug for Session {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Session(<redacted>)")
    }
}
pub(crate) struct Client<T = Https> {
    pub wire: T,
    cookies: BTreeMap<String, String>,
    pub(crate) phase: &'static str,
    pub(crate) last_http: Option<u16>,
}
impl Client<Https> {
    pub fn new() -> Self {
        Self {
            wire: Https,
            cookies: BTreeMap::new(),
            phase: "setup",
            last_http: None,
        }
    }
}
impl<T: Transport> Client<T> {
    pub(crate) async fn raw(
        &mut self,
        method: &str,
        url: &str,
        headers: &HeaderMap,
        body: &str,
        form: bool,
    ) -> Result<Response, Error> {
        let uri: url::Url = url.parse().map_err(|_| Error::Protocol)?;
        let host = uri.host_str().ok_or(Error::Protocol)?.to_owned();
        if !allowed_url(&uri)
            || (legacy_authorize_page(&uri) && (method != "GET" || !body.is_empty()))
        {
            return Err(Error::RedirectUntrusted);
        }
        let mut req = Request::builder()
            .method(method)
            .uri(url)
            .body(Full::new(moli_net::Body::copy_from_slice(body.as_bytes())))
            .map_err(|_| Error::Protocol)?;
        *req.headers_mut() = headers.clone();
        if !body.is_empty() {
            req.headers_mut().insert(
                "content-type",
                if form {
                    "application/x-www-form-urlencoded"
                } else {
                    "application/json"
                }
                .parse()
                .map_err(|_| Error::Protocol)?,
            );
        }
        if let Some(cookie) = self.cookies.get(&host) {
            req.headers_mut()
                .insert("cookie", cookie.parse().map_err(|_| Error::Protocol)?);
        }
        self.last_http = None;
        let response = self.wire.send(req).await?;
        self.last_http = Some(response.status);
        let cookies: Vec<_> = response
            .headers
            .get_all("set-cookie")
            .iter()
            .filter_map(|v| v.to_str().ok())
            .filter_map(|v| v.split(';').next())
            .filter(|v| !v.is_empty())
            .map(str::to_owned)
            .collect();
        if !cookies.is_empty() {
            let mut jar: BTreeMap<String, String> = self
                .cookies
                .get(&host)
                .into_iter()
                .flat_map(|v| v.split("; "))
                .filter_map(|s| s.split_once('=').map(|(k, v)| (k.to_owned(), v.to_owned())))
                .collect();
            for cookie in cookies {
                if let Some((k, v)) = cookie.split_once('=') {
                    if v.is_empty() {
                        jar.remove(k);
                    } else {
                        jar.insert(k.to_owned(), v.to_owned());
                    }
                }
            }
            self.cookies.insert(
                host,
                jar.into_iter()
                    .map(|(k, v)| format!("{k}={v}"))
                    .collect::<Vec<_>>()
                    .join("; "),
            );
        }
        if response.status == 429 {
            let retry = response
                .headers
                .get("retry-after")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| {
                    v.parse().ok().or_else(|| {
                        httpdate::parse_http_date(v)
                            .ok()
                            .and_then(|date| date.duration_since(std::time::SystemTime::now()).ok())
                            .map(|d| d.as_secs())
                    })
                })
                .unwrap_or(900);
            return Err(Error::RateLimit(retry));
        }
        Ok(response)
    }
    #[allow(clippy::needless_pass_by_value)] // Consume the sensitive response at its decoding boundary.
    pub(crate) fn json(response: Response) -> Result<Json, Error> {
        match response.status {
            401 => return Err(Error::Authentication),
            403 => return Err(Error::Captcha),
            404 => return Err(Error::Unsupported),
            200..=299 => {}
            _ => return Err(Error::Protocol),
        }
        serde_json::from_slice(&response.body).map_err(|_| Error::Protocol)
    }
    fn headers(device: &str) -> Result<HeaderMap, Error> {
        let mut out = HeaderMap::new();
        for (key, value) in [
            ("client-id", "com.hyundai.oneapp.eu"),
            ("client-name", "HyundaiEU"),
            ("client-version", "1.4.0"),
            ("client-os-code", "android"),
            ("client-os-version", "14"),
            ("client-device-model", "SM-S918B"),
            ("client-notification-provider-type", "FCM"),
            ("locale", "FR"),
            ("timezone", "+00:00"),
            ("user-agent", "okhttp/4.12.0"),
            ("x-brand", "Hyundai"),
            ("x-region", "eu"),
            ("accept", "*/*"),
        ] {
            out.insert(
                http::header::HeaderName::from_bytes(key.as_bytes())
                    .map_err(|_| Error::Protocol)?,
                value.parse().map_err(|_| Error::Protocol)?,
            );
        }
        out.insert(
            "client-device-id",
            device.parse().map_err(|_| Error::Protocol)?,
        );
        out.insert(
            "app-request-id",
            format!(
                "{}_{}_AOS",
                crypto::random_hex(12)
                    .map_err(|_| Error::Protocol)?
                    .to_uppercase(),
                device.replace('-', "")
            )
            .parse()
            .map_err(|_| Error::Protocol)?,
        );
        Ok(out)
    }
    fn bearer(headers: &mut HeaderMap, session: &Session) -> Result<(), Error> {
        for (key, value) in [
            (
                "authorization",
                format!("Bearer {}", text(&session.cci, "accessToken")?),
            ),
            (
                "authentication",
                text(&session.cci, "nonCcsToken").unwrap_or("").to_owned(),
            ),
            (
                "non-ccs-token",
                text(&session.cci, "nonCcsToken").unwrap_or("").to_owned(),
            ),
            (
                "exchangeable-token",
                text(&session.cci, "exchangeableAccessToken")
                    .unwrap_or("")
                    .to_owned(),
            ),
        ] {
            headers.insert(
                http::header::HeaderName::from_bytes(key.as_bytes())
                    .map_err(|_| Error::Protocol)?,
                value.parse().map_err(|_| Error::Protocol)?,
            );
        }
        Ok(())
    }
    pub(crate) fn legacy_headers(device: &str) -> Result<HeaderMap, Error> {
        let mut h = Self::headers(device)?;
        for (k, v) in [
            ("client-name", "hyundai"),
            ("client-version", "1.3.3"),
            ("client-os-code", "ios"),
            ("client-os-version", "18.7"),
            ("client-device-model", "iPhone"),
            ("client-notification-provider-type", "APNS"),
        ] {
            h.insert(
                http::header::HeaderName::from_bytes(k.as_bytes()).map_err(|_| Error::Protocol)?,
                v.parse().map_err(|_| Error::Protocol)?,
            );
        }
        Ok(h)
    }
    #[cfg(test)]
    pub(crate) fn with_wire(wire: T) -> Self {
        Self {
            wire,
            cookies: BTreeMap::new(),
            phase: "setup",
            last_http: None,
        }
    }
    async fn auth(
        &mut self,
        device: &str,
        path: &str,
        body: &Json,
        session: Option<&Session>,
    ) -> Result<Json, Error> {
        let body = if body.is_null() {
            String::new()
        } else {
            body.to_string()
        };
        let timestamp = moli_core::now_ms();
        self.phase = if path.contains("token-exchange") {
            "ccs_exchange"
        } else if path.contains("token-refresh") {
            "refresh"
        } else if path.ends_with("pin") {
            "pin"
        } else {
            "cci_exchange"
        };
        let mut h = Self::headers(device)?;
        h.insert(
            "x-timestamp",
            timestamp.to_string().parse().map_err(|_| Error::Protocol)?,
        );
        h.insert(
            "x-fingerprint",
            crypto::fingerprint(timestamp, &body)
                .parse()
                .map_err(|_| Error::Protocol)?,
        );
        if let Some(session) = session.filter(|_| !path.starts_with("v2/token-refresh")) {
            Self::bearer(&mut h, session)?;
        }
        let response = self
            .raw("POST", &format!("{CCI}/auth/{path}"), &h, &body, false)
            .await?;
        // Only an explicit fingerprint gate rejection permits the documented
        // legacy route. Network failures, 401, CAPTCHA and ambiguous refreshes
        // never trigger a fallback/replay.
        let gate = if (400..500).contains(&response.status) {
            serde_json::from_slice::<Json>(&response.body)
                .ok()
                .and_then(|v| {
                    v.get("code")
                        .or_else(|| v.get("resCode"))
                        .or_else(|| v.get("errorCode"))
                        .cloned()
                })
                .is_some_and(|v| {
                    matches!(v.as_str(), Some("9035" | "9036" | "9037"))
                        || matches!(v.as_u64(), Some(9035..=9037))
                })
        } else {
            false
        };
        if gate {
            let (version, endpoint) = path.split_once('/').ok_or(Error::Protocol)?;
            let mut headers = Self::legacy_headers(device)?;
            if let Some(session) = session {
                Self::bearer(&mut headers, session)?;
            }
            let url = format!("{CCI}/domain/api/{version}/auth/{endpoint}");
            return Self::json(self.raw("POST", &url, &headers, &body, false).await?);
        }
        Self::json(response)
    }
    #[allow(clippy::too_many_lines)] // Bounded five-step identity protocol, one ordered transaction.
    #[cfg(test)]
    pub async fn login(
        &mut self,
        username: &str,
        password: &str,
        pin: Option<String>,
    ) -> Result<Session, Error> {
        let device = crypto::device_id().map_err(|_| Error::Protocol)?;
        self.login_device(username, password, pin, device, crate::auth::ApiMode::Gspa)
            .await
    }
    #[allow(clippy::too_many_lines)] // Bounded identity protocol with explicit phases and safe redirects.
    pub(crate) async fn login_device(
        &mut self,
        username: &str,
        password: &str,
        pin: Option<String>,
        device: String,
        mode: crate::auth::ApiMode,
    ) -> Result<Session, Error> {
        self.phase = "authorize";
        if username.is_empty()
            || password.is_empty()
            || username.len() > 256
            || password.len() > 1024
        {
            return Err(Error::Authentication);
        }
        let mut h = Self::headers(&device)?;
        h.insert("user-agent","Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36_CCS_APP_AOS".parse().map_err(|_|Error::Protocol)?);
        let mut url = url::Url::parse(&format!("{IDP}/auth/api/v2/user/oauth2/authorize"))
            .map_err(|_| Error::Protocol)?;
        url.query_pairs_mut().extend_pairs([
            ("response_type", "code"),
            ("client_id", CLIENT),
            ("redirect_uri", REDIRECT),
            ("lang", "fr"),
            ("state", "ccsp"),
            ("country", "fr"),
        ]);
        let mut reached = false;
        for _ in 0..8 {
            let response = self.raw("GET", url.as_str(), &h, "", false).await?;
            let lower = String::from_utf8_lossy(&response.body).to_lowercase();
            if lower.contains("abusing") || lower.contains("captcha") {
                return Err(Error::Captcha);
            }
            if (300..400).contains(&response.status) {
                url = redirect_url(&url, &response.headers)?;
                // A callback is a terminal navigation target, never fetch its code.
                if crate::auth::is_callback(&url) {
                    reached = true;
                    break;
                }
                continue;
            }
            if response.status != 200 {
                return Err(Error::AuthorizeRedirect);
            }
            reached = true;
            break;
        }
        if !reached {
            return Err(Error::RedirectLoop);
        }
        self.phase = "rsa_certificate";
        let cert = Self::json(
            self.raw(
                "GET",
                &format!("{IDP}/auth/api/v1/accounts/certs"),
                &h,
                "",
                false,
            )
            .await?,
        )?;
        let key = &cert["retValue"];
        let encrypted = crypto::encrypt_password(text(key, "n")?, text(key, "e")?, password)
            .map_err(|_| Error::Certificate)?;
        let mut form = url::form_urlencoded::Serializer::new(String::new());
        form.extend_pairs([
            ("client_id", CLIENT),
            ("encryptedPassword", "true"),
            ("password", encrypted.as_str()),
            ("redirect_uri", REDIRECT),
            ("scope", ""),
            ("nonce", ""),
            ("state", "ccsp"),
            ("username", username),
            ("connector_session_key", ""),
            ("kid", text(key, "kid")?),
            ("_csrf", ""),
        ]);
        self.phase = "signin";
        let response = self
            .raw(
                "POST",
                &format!("{IDP}/auth/account/signin"),
                &h,
                &form.finish(),
                true,
            )
            .await?;
        if response.status != 302 {
            return Err(if response.status == 403 {
                Error::Captcha
            } else {
                Error::Authentication
            });
        }
        self.phase = "signin_redirect";
        let base =
            url::Url::parse(&format!("{IDP}/auth/account/signin")).map_err(|_| Error::Protocol)?;
        let mut redirect = redirect_url(&base, &response.headers)?;
        for _ in 0..8 {
            if redirect.query_pairs().any(|(k, _)| k == "error") {
                return Err(
                    if redirect.query_pairs().any(|(k, v)| {
                        k == "error_description"
                            && v.trim().eq_ignore_ascii_case("invalid credentials")
                    }) {
                        Error::InvalidCredentials
                    } else {
                        Error::SigninRedirect
                    },
                );
            }
            if let Some(code) = crate::auth::code_at_callback(&redirect) {
                return self.finish_code(device, &code, pin, mode).await;
            }
            if redirect.path().contains("/web/v1/user/authorization") {
                return Err(Error::Consent);
            }
            if redirect.path().ends_with("/account/signin") {
                return Err(Error::SigninRedirect);
            }
            let response = self.raw("GET", redirect.as_str(), &h, "", false).await?;
            if !(300..400).contains(&response.status) {
                return Err(Error::SigninRedirect);
            }
            redirect = redirect_url(&redirect, &response.headers)?;
        }
        Err(Error::RedirectLoop)
    }
    pub(crate) async fn finish_code(
        &mut self,
        device: String,
        code: &str,
        pin: Option<String>,
        mode: crate::auth::ApiMode,
    ) -> Result<Session, Error> {
        let cci = self
            .auth(
                &device,
                &format!(
                    "v1/token?code={}",
                    url::form_urlencoded::byte_serialize(code.as_bytes()).collect::<String>()
                ),
                &Json::Null,
                None,
            )
            .await?;
        let mut session = Session {
            api_mode: mode,
            device,
            cci,
            access: String::new(),
            expires: 0,
            user: String::new(),
            pin,
            control: None,
            control_expires: 0,
        };
        self.exchange(&mut session).await?;
        Ok(session)
    }
    pub async fn exchange(&mut self, session: &mut Session) -> Result<(), Error> {
        let ccs = self
            .auth(
                &session.device,
                "v1/token-exchange?serviceType=CCS",
                &Json::Null,
                Some(session),
            )
            .await?;
        ccs.get("accessToken")
            .or_else(|| ccs.get("ccsAccessToken"))
            .and_then(Json::as_str)
            .filter(|v| !v.is_empty())
            .ok_or(Error::Protocol)?
            .clone_into(&mut session.access);
        session.expires =
            moli_core::now_ms() / 1000 + integer(&ccs["expiresTime"]).unwrap_or(3600).min(86_400);
        session.user = claim(&session.access, "uid")
            .or_else(|| {
                session.cci["idToken"]
                    .as_str()
                    .and_then(|t| claim(t, "sub"))
            })
            .or_else(|| (session.api_mode == crate::auth::ApiMode::Europe).then(String::new))
            .ok_or(Error::Protocol)?;
        Ok(())
    }
    pub async fn refresh(&mut self, session: &mut Session) -> Result<(), Error> {
        let old = session.clone();
        let mut body = json!({});
        for key in [
            "accessToken",
            "refreshToken",
            "nonCcsToken",
            "nonCcsRefreshToken",
            "exchangeableAccessToken",
            "exchangeableRefreshToken",
            "idToken",
        ] {
            body[key] = json!(old.cci[key].as_str().unwrap_or(""));
        }
        let result = self
            .auth(&old.device, "v2/token-refresh", &body, Some(&old))
            .await?;
        for key in [
            "accessToken",
            "refreshToken",
            "nonCcsToken",
            "nonCcsRefreshToken",
            "exchangeableAccessToken",
            "exchangeableRefreshToken",
            "idToken",
        ] {
            if let Some(value) = result.get(key) {
                session.cci[key] = value.clone();
            }
        }
        if let Some(cookie) = self
            .cookies
            .get("cci-api-eu.hyundai.com")
            .and_then(|c| c.split("; ").find_map(|p| p.strip_prefix("t=")))
        {
            session.cci["exchangeableAccessToken"] = json!(cookie);
        }
        session.control = None;
        session.control_expires = 0;
        session.access.clear();
        session.expires = 0;
        Ok(())
    }
    pub async fn register_device(&mut self, session: &Session) -> Result<(), Error> {
        self.phase = "device_registration";
        let mut headers = Self::legacy_headers(&session.device)?;
        Self::bearer(&mut headers, session)?;
        let app_token = crate::crypto::hex(
            ring::digest::digest(&ring::digest::SHA256, session.device.as_bytes()).as_ref(),
        );
        let body = json!({"appToken":app_token,"deviceModel":"iPhone","deviceAppVer":"1.3.3","deviceOsVer":"18.7","deviceToken":session.device});
        let response = self
            .raw(
                "POST",
                &format!("{CCI}/domain/api/v3/notifications/bases/devices"),
                &headers,
                &body.to_string(),
                false,
            )
            .await?;
        if response.status == 200 {
            Ok(())
        } else {
            Err(Error::Protocol)
        }
    }
    pub async fn vehicles(&mut self, session: &Session) -> Result<Json, Error> {
        self.phase = "fleet_discovery";
        if session.api_mode == crate::auth::ApiMode::Europe {
            return self.legacy_vehicles(session).await;
        }
        let mut h = Self::legacy_headers(&session.device)?;
        Self::bearer(&mut h, session)?;
        Self::json(
            self.raw(
                "GET",
                &format!("{CCI}/domain/api/v1/vehicle/available-vehicles?detail=true"),
                &h,
                "",
                false,
            )
            .await?,
        )
    }
    async fn gspa_headers(
        &mut self,
        session: &mut Session,
        pin: bool,
        ccs2: u64,
    ) -> Result<HeaderMap, Error> {
        if session.expires <= moli_core::now_ms() / 1000 + 10 {
            return Err(Error::Expired);
        }
        if pin
            && (session.control.is_none()
                || session.control_expires <= moli_core::now_ms() / 1000 + 10)
        {
            let code = session.pin.as_ref().ok_or(Error::Pin)?.clone();
            let result = self
                .auth(
                    &session.device,
                    "v1/pin",
                    &json!({"pin":code}),
                    Some(session),
                )
                .await?;
            if result["isMatched"] != true {
                return Err(Error::Pin);
            }
            session.control = Some(text(&result["controlTokenInfo"], "controlToken")?.to_owned());
            let expires = integer(&result["controlTokenInfo"]["expiresTime"]).unwrap_or(0);
            let now = moli_core::now_ms() / 1000;
            session.control_expires = if expires > 1_000_000_000_000 {
                expires / 1000
            } else if expires > 1_000_000_000 {
                expires
            } else {
                now + expires
            }
            .min(now + 600);
        }
        let mut h = Self::headers(&session.device)?;
        let tsid =
            crypto::tsid(moli_core::now_ms(), &session.device).map_err(|_| Error::Protocol)?;
        h.insert(
            "authorization",
            format!(
                "Bearer {}",
                if pin {
                    session.control.as_deref().ok_or(Error::Pin)?
                } else {
                    &session.access
                }
            )
            .parse()
            .map_err(|_| Error::Protocol)?,
        );
        if pin {
            h.insert("authorizationccsp", h["authorization"].clone());
        }
        for key in ["ccsp-service-id", "ccsp-application-id"] {
            h.insert(
                key,
                "6d477c38-3ca4-4cf3-9557-2a1929a94654"
                    .parse()
                    .map_err(|_| Error::Protocol)?,
            );
        }
        h.insert("language", "fr".parse().map_err(|_| Error::Protocol)?);
        h.insert("client-id", CLIENT.parse().map_err(|_| Error::Protocol)?);
        h.insert(
            "client-os-code",
            "AOS".parse().map_err(|_| Error::Protocol)?,
        );
        h.insert(
            "client-name",
            "hyundai".parse().map_err(|_| Error::Protocol)?,
        );
        h.insert(
            "client-version",
            "1.3.3".parse().map_err(|_| Error::Protocol)?,
        );
        h.insert(
            "ccsp-device-id",
            session.device.parse().map_err(|_| Error::Protocol)?,
        );
        h.insert(
            "x-device-id",
            session.device.parse().map_err(|_| Error::Protocol)?,
        );
        h.insert("x-request-id", tsid.parse().map_err(|_| Error::Protocol)?);
        h.insert(
            "x-stamp",
            crypto::stamp(&tsid, moli_core::now_ms() / 1000, &session.user)
                .parse()
                .map_err(|_| Error::Protocol)?,
        );
        h.insert(
            "ccuccs2protocolsupport",
            ccs2.to_string().parse().map_err(|_| Error::Protocol)?,
        );
        Ok(h)
    }
    pub async fn prepare_control(
        &mut self,
        session: &mut Session,
        pin: bool,
        ccs2: u64,
    ) -> Result<HeaderMap, Error> {
        if session.api_mode == crate::auth::ApiMode::Europe {
            return self.legacy_control_headers(session, pin, ccs2).await;
        }
        self.gspa_headers(session, pin, ccs2).await
    }
    pub async fn control_post(
        &mut self,
        path: &str,
        headers: &HeaderMap,
        body: &Json,
    ) -> Result<Json, Error> {
        let value = Self::json(
            self.raw(
                "POST",
                &format!("{GSPA}/gspa/v1/{path}"),
                headers,
                &body.to_string(),
                false,
            )
            .await?,
        )?;
        parse_gspa(path, value)
    }
    pub async fn gspa(
        &mut self,
        session: &mut Session,
        method: &str,
        path: &str,
        body: &Json,
        pin: bool,
        ccs2: u64,
    ) -> Result<Json, Error> {
        let h = self.gspa_headers(session, pin, ccs2).await?;
        let payload = if body.is_null() {
            String::new()
        } else {
            body.to_string()
        };
        let value = Self::json(
            self.raw(
                method,
                &format!("{GSPA}/gspa/v1/{path}"),
                &h,
                &payload,
                false,
            )
            .await?,
        )?;
        parse_gspa(path, value)
    }
}
fn text<'a>(value: &'a Json, key: &str) -> Result<&'a str, Error> {
    value[key]
        .as_str()
        .filter(|v| !v.is_empty() && v.len() < 16384)
        .ok_or(Error::Protocol)
}
fn claim(token: &str, key: &str) -> Option<String> {
    let payload = token.split('.').nth(1)?;
    let bytes = URL_SAFE_NO_PAD.decode(payload).ok()?;
    serde_json::from_slice::<Json>(&bytes)
        .ok()?
        .get(key)?
        .as_str()
        .map(str::to_owned)
}

fn integer(value: &Json) -> Option<u64> {
    value.as_u64().or_else(|| value.as_str()?.parse().ok())
}

#[allow(clippy::needless_pass_by_value)] // Response consumed; never retain the raw envelope.
fn parse_gspa(path: &str, value: Json) -> Result<Json, Error> {
    if path.ends_with("/prewakeup") && value["rc"] == "0000" {
        return Ok(value["rs"].clone());
    }
    if value["metaInfo"]["retCode"] != "S" {
        let code = value["metaInfo"]["resCode"].as_str().unwrap_or("");
        return Err(if code.starts_with("404") {
            Error::Unsupported
        } else if code.starts_with("401") {
            Error::Authentication
        } else if code.starts_with("429") {
            Error::RateLimit(900)
        } else {
            Error::Protocol
        });
    }
    Ok(value.get("data").cloned().unwrap_or(Json::Null))
}

// The public OneApp authorize endpoint redirects here before RSA signin.
// This is an identity page, not permission to navigate the CCAPI host/API.
pub(crate) fn legacy_authorize_page(url: &url::Url) -> bool {
    url.scheme() == "https"
        && url.host_str() == Some("prd.eu-ccapi.hyundai.com")
        && url.port() == Some(8080)
        && url.path() == "/web/v1/user/authorize"
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none()
}
pub(crate) fn allowed_url(url: &url::Url) -> bool {
    url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none()
        && match (url.host_str(), url.port_or_known_default()) {
            (
                Some(
                    "idpconnect-eu.hyundai.com"
                    | "oneapp.hyundai.com"
                    | "cci-api-eu.hyundai.com"
                    | "gspa-ccs-eu.hyundai.com",
                ),
                Some(443),
            ) => true,
            (Some("prd.eu-ccapi.hyundai.com"), Some(8080)) => {
                url.path().starts_with("/api/") || legacy_authorize_page(url)
            }
            _ => false,
        }
}
fn redirect_url(base: &url::Url, headers: &HeaderMap) -> Result<url::Url, Error> {
    let location = headers
        .get("location")
        .and_then(|v| v.to_str().ok())
        .ok_or(Error::Protocol)?;
    if location.len() > 8192 {
        return Err(Error::RedirectUntrusted);
    }
    let url = base.join(location).map_err(|_| Error::RedirectUntrusted)?;
    if !allowed_url(&url)
        || !(legacy_authorize_page(&url)
            || matches!(
                url.host_str(),
                Some("idpconnect-eu.hyundai.com" | "oneapp.hyundai.com")
            ))
    {
        return Err(Error::RedirectUntrusted);
    }
    Ok(url)
}
