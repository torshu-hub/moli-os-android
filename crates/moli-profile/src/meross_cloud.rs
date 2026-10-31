//! Voluntary Meross account provisioning, never a dependency of local polling.
//! Protocol reference: krahabb/meross_lan, merossclient/cloudapi.py (MIT, see NOTICE).
use base64::{Engine as _, engine::general_purpose::STANDARD};
use http::{Method, Request};
use http_body_util::Full;
use hyper::body::Bytes;
use md5::{Digest as _, Md5};
use ring::rand::{SecureRandom as _, SystemRandom};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::fmt;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub type Result<T> = std::result::Result<T, &'static str>;
const HOSTS: [&str; 4] = [
    "iotx-eu.meross.com",
    "iotx-us.meross.com",
    "iotx-ap.meross.com",
    "iot.meross.com",
];
// Public protocol application salt, NOT a user's credential or signing key.
const APPLICATION_SALT: &str = "23x17ahWarFH6w29";
#[derive(Clone, Serialize, Deserialize)]
pub struct Credentials {
    pub host: String,
    pub key: String,
    pub token: String,
}
impl fmt::Debug for Credentials {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("MerossCredentials(<redacted>)")
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Device {
    pub uuid: String,
    pub name: String,
    pub model: String,
    pub hardware: String,
}

pub fn domain(value: &str) -> Result<String> {
    let host = value
        .strip_prefix("https://")
        .ok_or("account_region")?
        .trim_end_matches('/');
    if !HOSTS.contains(&host) {
        return Err("account_region");
    }
    Ok(host.into())
}
pub fn uuid(value: &str) -> bool {
    value.len() == 32 && value.bytes().all(|b| b.is_ascii_hexdigit())
}
fn text(value: &Value, max: usize) -> Result<String> {
    value
        .as_str()
        .filter(|v| !v.is_empty() && v.len() <= max && !v.chars().any(char::is_control))
        .map(str::to_owned)
        .ok_or("account_protocol")
}
pub fn credentials(data: &Value, host: &str) -> Result<Credentials> {
    let host = match data["domain"].as_str() {
        Some(d) => domain(d)?,
        None => domain(&format!("https://{host}"))?,
    };
    let key = text(&data["key"], 32)?;
    if !uuid(&key) {
        return Err("account_protocol");
    }
    let token = text(&data["token"], 2048)?;
    if !token.bytes().all(|b| (33..=126).contains(&b)) {
        return Err("account_protocol");
    }
    Ok(Credentials { host, key, token })
}
pub fn inventory(data: &Value) -> Result<Vec<Device>> {
    let items = data
        .as_array()
        .filter(|v| v.len() <= 128)
        .ok_or("account_protocol")?;
    let mut result = Vec::new();
    let mut ids = std::collections::HashSet::new();
    for item in items {
        if !item["deviceType"]
            .as_str()
            .is_some_and(|t| t.eq_ignore_ascii_case("mss310"))
        {
            continue;
        }
        let id = text(&item["uuid"], 32)?.to_ascii_lowercase();
        if !uuid(&id) || !ids.insert(id.clone()) {
            return Err("account_protocol");
        }
        result.push(Device {
            uuid: id,
            name: text(&item["devName"], 160)?,
            model: "MSS310".into(),
            hardware: item["hdwareVersion"]
                .as_str()
                .filter(|s| s.len() <= 40 && !s.chars().any(char::is_control))
                .unwrap_or("")
                .into(),
        });
    }
    Ok(result)
}
fn status(value: &Value) -> Result<()> {
    match value["apiStatus"].as_i64() {
        Some(0) if value.get("data").is_some() => Ok(()),
        Some(1030) => Err("account_redirect"),
        Some(1032 | 1033) => Err("account_mfa"),
        Some(1001..=1008) => Err("account_auth"),
        Some(1019 | 1022 | 1200) => Err("account_expired"),
        Some(1028 | 1042 | 1301) => Err("account_rate"),
        Some(1035 | 1043) => Err("account_denied"),
        _ => Err("account_protocol"),
    }
}
#[derive(Debug)]
pub struct Client {
    host: String,
    #[cfg(test)]
    fixture: Option<std::net::SocketAddr>,
}
impl Client {
    pub fn new(region: &str) -> Result<Self> {
        let index = match region {
            "eu" => 0,
            "us" => 1,
            "ap" => 2,
            "global" => 3,
            _ => return Err("account_region"),
        };
        Ok(Self {
            host: HOSTS[index].into(),
            #[cfg(test)]
            fixture: None,
        })
    }
    pub fn saved(credentials: &Credentials) -> Result<Self> {
        let mut c = Self::new("global")?;
        c.host = domain(&format!("https://{}", credentials.host))?;
        Ok(c)
    }
    async fn post(
        &self,
        host: &str,
        path: &str,
        data: &Value,
        token: Option<&str>,
    ) -> Result<Value> {
        if !HOSTS.contains(&host)
            || !matches!(
                path,
                "/v1/Auth/signIn" | "/v1/Device/devList" | "/v1/Profile/Logout"
            )
        {
            return Err("account_protocol");
        }
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| "account_clock")?
            .as_millis();
        let mut raw = [0u8; 16];
        SystemRandom::new().fill(&mut raw).map_err(|_| "internal")?;
        let nonce = super::hex(&raw);
        let params = STANDARD.encode(data.to_string());
        let sign = super::hex(&Md5::digest(
            format!("{APPLICATION_SALT}{timestamp}{nonce}{params}").as_bytes(),
        ));
        let mut request = Request::builder()
            .method(Method::POST)
            .uri(path)
            .header("host", host)
            .header("content-type", "application/json");
        if let Some(token) = token {
            request = request.header("authorization", format!("Basic {token}"));
        }
        let request = request
            .body(Full::new(Bytes::from(
                json!({"timestamp":timestamp,"nonce":nonce,"params":params,"sign":sign})
                    .to_string(),
            )))
            .map_err(|_| "account_protocol")?;
        #[cfg(test)]
        let (destination, port, tls) = self.fixture.map_or((host.to_owned(), 443, true), |a| {
            (a.ip().to_string(), a.port(), false)
        });
        #[cfg(not(test))]
        let (destination, port, tls) = (host.to_owned(), 443, true);
        let (code, body) = moli_net::web(
            &destination,
            port,
            tls,
            request,
            Duration::from_secs(8),
            131_072,
        )
        .await
        .map_err(|_| "account_network")?;
        match code.as_u16() {
            200 => {}
            401 => return Err("account_auth"),
            403 => return Err("account_denied"),
            429 => return Err("account_rate"),
            _ => return Err("account_network"),
        }
        serde_json::from_slice(&body).map_err(|_| "account_protocol")
    }
    pub async fn signin(
        &self,
        email: &str,
        password: &str,
        region: &str,
        otp: &str,
    ) -> Result<Credentials> {
        if email.len() > 254
            || !email.contains('@')
            || email.chars().any(char::is_control)
            || password.is_empty()
            || password.len() > 1024
            || (!otp.is_empty() && (otp.len() > 12 || !otp.bytes().all(|b| b.is_ascii_digit())))
        {
            return Err("account_input");
        }
        let mut data = json!({"email":email,"password":super::hex(&Md5::digest(password.as_bytes())),"accountCountryCode":if region=="global" {Value::Null}else{json!(region)},"encryption":1,"agree":0});
        if !otp.is_empty() {
            data["mfaCode"] = json!(otp);
        }
        let mut host = self.host.clone();
        let mut response = self.post(&host, "/v1/Auth/signIn", &data, None).await?;
        if status(&response) == Err("account_redirect") {
            host = domain(
                response["data"]["domain"]
                    .as_str()
                    .ok_or("account_region")?,
            )?;
            response = self.post(&host, "/v1/Auth/signIn", &data, None).await?;
        }
        status(&response)?;
        credentials(&response["data"], &host)
    }
    pub async fn devices(&self, credentials: &Credentials) -> Result<Vec<Device>> {
        let response = self
            .post(
                &credentials.host,
                "/v1/Device/devList",
                &json!({}),
                Some(&credentials.token),
            )
            .await?;
        status(&response)?;
        inventory(&response["data"])
    }
    pub async fn logout(&self, credentials: &Credentials) -> Result<()> {
        let response = self
            .post(
                &credentials.host,
                "/v1/Profile/Logout",
                &json!({}),
                Some(&credentials.token),
            )
            .await?;
        status(&response)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
    fn login(host: &str) -> Value {
        json!({"apiStatus":0,"data":{"domain":format!("https://{host}"),"key":"a".repeat(32),"token":"invented-session-token","userid":"synthetic-user","email":"user@example.com"}})
    }
    fn devices() -> Value {
        json!({"apiStatus":0,"data":(1..=4).map(|i|json!({"uuid":format!("{i:032x}"),"devName":format!("Invented plug {i}"),"deviceType":"mss310","hdwareVersion":"6.0.0","ip":"8.8.8.8","token":"never-forward-this"})).collect::<Vec<_>>()})
    }
    async fn fixture(responses: Vec<Value>) -> (Client, tokio::task::JoinHandle<Vec<Value>>) {
        let socket = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = socket.local_addr().unwrap();
        let worker = tokio::spawn(async move {
            let mut seen = vec![];
            for response in responses {
                let (mut conn, _) = socket.accept().await.unwrap();
                let mut raw = vec![];
                let mut byte = [0];
                while !raw.ends_with(b"\r\n\r\n") {
                    conn.read_exact(&mut byte).await.unwrap();
                    raw.push(byte[0]);
                    assert!(raw.len() < 8192);
                }
                let headers = String::from_utf8(raw).unwrap();
                let first = headers.lines().next().unwrap();
                assert!(first.starts_with("POST /v1/"));
                let length = headers
                    .lines()
                    .find_map(|l| {
                        l.to_ascii_lowercase()
                            .strip_prefix("content-length:")
                            .map(|v| v.trim().parse::<usize>().unwrap())
                    })
                    .unwrap();
                assert!(length < 8192);
                let mut bytes = vec![0; length];
                conn.read_exact(&mut bytes).await.unwrap();
                let envelope: Value = serde_json::from_slice(&bytes).unwrap();
                let sign = super::super::hex(&Md5::digest(
                    format!(
                        "{}{}{}{}",
                        APPLICATION_SALT,
                        envelope["timestamp"],
                        envelope["nonce"].as_str().unwrap(),
                        envelope["params"].as_str().unwrap()
                    )
                    .as_bytes(),
                ));
                assert_eq!(envelope["sign"], sign);
                let data: Value = serde_json::from_slice(
                    &STANDARD
                        .decode(envelope["params"].as_str().unwrap())
                        .unwrap(),
                )
                .unwrap();
                seen.push(json!({"headers":headers,"data":data}));
                let body = response.to_string();
                let reply = format!(
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                conn.write_all(reply.as_bytes()).await.unwrap();
            }
            seen
        });
        let mut client = Client::new("eu").unwrap();
        client.fixture = Some(addr);
        (client, worker)
    }
    #[tokio::test]
    async fn region_signed_login_inventory_and_logout_use_only_fake_http() {
        let (client, worker) = fixture(vec![
            json!({"apiStatus":1030,"data":{"domain":"https://iotx-us.meross.com"}}),
            login(HOSTS[1]),
            devices(),
            json!({"apiStatus":0,"data":{}}),
        ])
        .await;
        let c = client
            .signin("user@example.com", "invented-password", "eu", "")
            .await
            .unwrap();
        assert_eq!(c.host, HOSTS[1]);
        assert!(!format!("{c:?}").contains(&c.token));
        let items = client.devices(&c).await.unwrap();
        assert_eq!(items.len(), 4);
        assert_eq!(items[0].uuid, format!("{:032x}", 1));
        let public = serde_json::to_string(&items).unwrap();
        assert!(!public.contains("token") && !public.contains("8.8.8.8"));
        client.logout(&c).await.unwrap();
        let seen = worker.await.unwrap();
        assert_eq!(seen.len(), 4);
        assert_eq!(
            seen[0]["data"]["password"],
            super::super::hex(&Md5::digest(b"invented-password"))
        );
        assert!(!seen[0]["data"].to_string().contains("invented-password"));
        assert!(
            seen[1]["headers"]
                .as_str()
                .unwrap()
                .contains("iotx-us.meross.com")
        );
        assert!(
            seen[2]["headers"]
                .as_str()
                .unwrap()
                .contains("Basic invented-session-token")
        );
    }
    #[tokio::test]
    async fn mfa_errors_expiry_denial_and_untrusted_redirects_are_bounded() {
        for (code, expected) in [
            (1004, "account_auth"),
            (1033, "account_mfa"),
            (1032, "account_mfa"),
            (1200, "account_expired"),
            (1028, "account_rate"),
            (1043, "account_denied"),
        ] {
            let (client, worker) = fixture(vec![
                json!({"apiStatus":code,"info":"private-password-token-do-not-echo","data":{}}),
            ])
            .await;
            assert_eq!(
                client
                    .signin("user@example.com", "invented-password", "eu", "123456")
                    .await
                    .unwrap_err(),
                expected
            );
            let seen = worker.await.unwrap();
            assert_eq!(seen[0]["data"]["mfaCode"], "123456");
        }
        let (client, worker) = fixture(vec![
            json!({"apiStatus":1030,"data":{"domain":"https://example.org"}}),
        ])
        .await;
        assert_eq!(
            client
                .signin("user@example.com", "invented-password", "eu", "")
                .await
                .unwrap_err(),
            "account_region"
        );
        assert_eq!(worker.await.unwrap().len(), 1);
        for bad in [
            "http://iotx-eu.meross.com",
            "https://iotx-eu.meross.com.evil.invalid",
            "https://iotx-eu.meross.com:443",
            "https://iotx-eu.meross.com/path",
        ] {
            assert!(domain(bad).is_err());
        }
    }
    #[test]
    fn malformed_inventory_keys_and_duplicate_ids_do_not_become_devices() {
        assert!(inventory(&json!(null)).is_err());
        let mut d = devices();
        let first = d["data"][0].clone();
        d["data"].as_array_mut().unwrap().push(first);
        assert!(inventory(&d["data"]).is_err());
        d["data"][0]["uuid"] = json!("not-an-identity");
        assert!(inventory(&d["data"]).is_err());
        assert!(
            credentials(
                &json!({"key":"not-a-local-key","token":"invented"}),
                HOSTS[0]
            )
            .is_err()
        );
        assert!(
            credentials(
                &json!({"key":"a".repeat(32),"token":"bad\r\nheader"}),
                HOSTS[0]
            )
            .is_err()
        );
        assert!(
            inventory(&json!([{"deviceType":"em06","uuid":"wrong"}]))
                .unwrap()
                .is_empty()
        );
    }
}
