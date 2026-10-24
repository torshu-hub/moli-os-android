//! In-process host with an explicit lifetime, injected key and local client.

use std::fmt;
use std::net::{SocketAddr, TcpListener};
use std::path::Path;
use std::sync::{Arc, mpsc};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use anyhow::{Context as _, bail};
use axum::extract::{Request, State};
use axum::http::{StatusCode, header};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use moli_runtime::MasterKey;
use ring::hmac;
use tokio_util::sync::CancellationToken;

use crate::config::Config;

pub(crate) struct Control {
    pub(crate) key: MasterKey,
    pub(crate) pin: String,
    pub(crate) gate: Arc<ClientGate>,
    pub(crate) cancel: CancellationToken,
    pub(crate) listener: Option<TcpListener>,
    pub(crate) ready: mpsc::SyncSender<()>,
}

/// A single embedded server. Dropping it cancels and joins its worker.
pub struct EmbeddedEngine {
    address: SocketAddr,
    cancel: CancellationToken,
    worker: Option<JoinHandle<anyhow::Result<()>>>,
}

impl fmt::Debug for EmbeddedEngine {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("EmbeddedEngine")
            .field("address", &self.address)
            .field(
                "running",
                &self.worker.as_ref().is_some_and(|w| !w.is_finished()),
            )
            .finish_non_exhaustive()
    }
}

impl EmbeddedEngine {
    /// Starts the real server on a reserved loopback port. The host owns the
    /// stable master key and the initial PIN; neither is read from process
    /// environment or written to a plaintext key file in this mode.
    pub fn start(data: &Path, key: MasterKey, token: &str, pin: &str) -> anyhow::Result<Self> {
        if !data.is_absolute() {
            bail!("embedded data directory must be absolute");
        }
        if !bootstrap_valid(pin) {
            bail!("invalid embedded bootstrap credential");
        }
        let gate = Arc::new(ClientGate::new(token)?);
        std::fs::create_dir_all(data)?;
        let path = data.join("moli.toml");
        let mut config = if path.exists() {
            Config::load(&path)?
        } else {
            Config::starter(&path)?
        };
        // The host determines storage/listening, never an imported machine path.
        config.server.data_dir = data.to_path_buf();
        if config.server.demo_seed.is_some() {
            bail!("demo seed resets are not allowed in the embedded host");
        }
        let listener = TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0))?;
        let address = listener.local_addr()?;
        listener.set_nonblocking(true)?;
        config.server.listen = address;
        let cancel = CancellationToken::new();
        let (ready, receiver) = mpsc::sync_channel(1);
        let control = Control {
            key,
            pin: pin.to_owned(),
            gate,
            cancel: cancel.clone(),
            listener: Some(listener),
            ready,
        };
        let stopping = cancel.clone();
        let worker = std::thread::Builder::new()
            .name("moli-engine".into())
            .spawn(move || {
                let runtime = tokio::runtime::Builder::new_multi_thread()
                    .worker_threads(config.server.worker_threads)
                    .enable_all()
                    .build()?;
                let result = runtime.block_on(async {
                    let serving = crate::serve(config, Instant::now(), Some(control));
                    tokio::pin!(serving);
                    tokio::select! {
                        result = &mut serving => result,
                        () = stopping.cancelled() => {
                            tokio::time::timeout(Duration::from_secs(10), serving)
                                .await.context("embedded shutdown exceeded 10 seconds")?
                        }
                    }
                });
                runtime.shutdown_timeout(Duration::from_secs(2));
                result
            })?;
        let mut engine = Self {
            address,
            cancel,
            worker: Some(worker),
        };
        if receiver.recv_timeout(Duration::from_secs(15)).is_err() {
            engine.stop().context("embedded startup failed")?;
            bail!("embedded startup did not become ready");
        }
        Ok(engine)
    }

    /// The bound loopback address. It is not an authentication credential.
    pub fn address(&self) -> SocketAddr {
        self.address
    }

    /// Cancels, flushes the engine, then joins its worker. Call off the UI thread.
    pub fn stop(&mut self) -> anyhow::Result<()> {
        self.cancel.cancel();
        if let Some(worker) = self.worker.take() {
            worker
                .join()
                .map_err(|_| anyhow::anyhow!("embedded worker panicked"))??;
        }
        Ok(())
    }
}

impl Drop for EmbeddedEngine {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}

pub(crate) fn master(control: Option<&Control>, data: &Path) -> anyhow::Result<Option<MasterKey>> {
    match control {
        Some(control) => Ok(Some(control.key.clone())),
        None => crate::master_key(data, true),
    }
}

pub(crate) fn pin(control: Option<&Control>) -> anyhow::Result<Option<moli_api::UiPin>> {
    match control {
        Some(control) => Ok(Some(moli_api::UiPin::new(&control.pin))),
        None => Ok(crate::from_env_or_file("MOLI_UI_PIN")?
            .as_deref()
            .map(moli_api::UiPin::new)),
    }
}

pub(crate) async fn listener(
    config: &Config,
    control: &mut Option<Control>,
) -> anyhow::Result<tokio::net::TcpListener> {
    // Bind before spawning workers. Embedded mode already reserved its port.
    match control.as_mut().and_then(|control| control.listener.take()) {
        Some(listener) => Ok(tokio::net::TcpListener::from_std(listener)?),
        None => tokio::net::TcpListener::bind(config.server.listen)
            .await
            .with_context(|| format!("cannot listen on {}", config.server.listen)),
    }
}

pub(crate) fn protect(router: axum::Router, control: Option<Control>) -> axum::Router {
    let Some(control) = control else {
        return router;
    };
    let router = router.layer(axum::middleware::from_fn_with_state(
        control.gate,
        authenticate,
    ));
    let _ = control.ready.send(());
    router
}

pub(crate) struct ClientGate {
    key: hmac::Key,
    proof: hmac::Tag,
}

impl ClientGate {
    fn new(token: &str) -> anyhow::Result<Self> {
        if token.len() != 64 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
            bail!("embedded session needs a 256-bit hex token");
        }
        let key = hmac::Key::new(hmac::HMAC_SHA256, token.as_bytes());
        let proof = hmac::sign(&key, token.as_bytes());
        Ok(Self { key, proof })
    }

    fn accepts(&self, request: &Request) -> bool {
        let mut cookies = request
            .headers()
            .get_all(header::COOKIE)
            .iter()
            .filter_map(|h| h.to_str().ok())
            .flat_map(|h| h.split(';'))
            .filter_map(|kv| kv.trim().strip_prefix("moli_android="));
        let Some(value) = cookies.next() else {
            return false;
        };
        cookies.next().is_none()
            && value.len() == 64
            && hmac::verify(&self.key, value.as_bytes(), self.proof.as_ref()).is_ok()
    }
}

pub(crate) async fn authenticate(
    State(gate): State<Arc<ClientGate>>,
    mut request: Request,
    next: Next,
) -> Response {
    if !gate.accepts(&request) {
        return (
            StatusCode::UNAUTHORIZED,
            "local client authentication required",
        )
            .into_response();
    }
    request.extensions_mut().insert(moli_api::EmbeddedClient);
    next.run(request).await
}

// Legacy human PINs remain accepted; new private previews use a 256-bit random bootstrap.
fn bootstrap_valid(value: &str) -> bool {
    ((4..=32).contains(&value.len()) && value.bytes().all(|b| b.is_ascii_digit()))
        || (value.len() == 64
            && value
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read as _, Write as _};

    const TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    fn request(address: SocketAddr, path: &str, cookie: &str) -> String {
        let mut socket = std::net::TcpStream::connect(address).unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        write!(
            socket,
            "GET {path} HTTP/1.1\r\nHost: {address}\r\nConnection: close\r\n{cookie}\r\n"
        )
        .unwrap();
        let mut response = String::new();
        socket.read_to_string(&mut response).unwrap();
        response
    }

    #[test]
    fn random_bootstrap_and_legacy_pin_formats_are_distinct() {
        assert!(bootstrap_valid("4826"));
        assert!(bootstrap_valid(TOKEN));
        for bad in [
            "",
            "123",
            "abcd",
            "0123456789abcdef",
            &"a".repeat(63),
            &"g".repeat(64),
        ] {
            assert!(!bootstrap_valid(bad));
        }
    }

    #[test]
    fn local_auth_stop_and_restart_preserve_data_without_a_plaintext_key() {
        let data = std::env::temp_dir().join(format!("moli-embedded-{}", std::process::id()));
        std::fs::create_dir_all(&data).unwrap();
        let key = MasterKey::generate().unwrap();
        let mut engine = EmbeddedEngine::start(&data, key.clone(), TOKEN, "4826").unwrap();
        let address = engine.address();
        assert!(request(address, "/api/health", "").starts_with("HTTP/1.1 401"));
        assert!(
            request(address, "/", "Cookie: moli_android=wrong\r\n").starts_with("HTTP/1.1 401")
        );
        let cookie = format!("Cookie: moli_android={TOKEN}\r\n");
        let health = request(address, "/api/health", &cookie);
        assert!(health.starts_with("HTTP/1.1 200"), "{health}");
        assert!(health.contains("moli-os"));
        let session = request(address, "/api/session", &cookie);
        assert!(session.contains("\"via\":\"lan\""), "{session}");
        let body = r#"{"pin":"4826"}"#;
        let mut login = std::net::TcpStream::connect(address).unwrap();
        login
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        write!(login, "POST /api/session HTTP/1.1\r\nHost: {address}\r\nConnection: close\r\n{cookie}Content-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}", body.len()).unwrap();
        let mut logged_in = String::new();
        login.read_to_string(&mut logged_in).unwrap();
        assert!(logged_in.starts_with("HTTP/1.1 200"), "{logged_in}");
        assert!(data.join("secrets.enc").exists());
        engine.stop().unwrap();
        assert!(std::net::TcpStream::connect(address).is_err());
        assert!(data.join("state.json").exists());
        assert!(!data.join("master.key").exists());
        assert!(
            EmbeddedEngine::start(&data, MasterKey::generate().unwrap(), TOKEN, "4826").is_err()
        );
        let mut again = EmbeddedEngine::start(&data, key, TOKEN, "4826").unwrap();
        assert!(request(again.address(), "/api/health", &cookie).starts_with("HTTP/1.1 200"));
        again.stop().unwrap();
        std::fs::remove_dir_all(&data).unwrap();
    }
}
