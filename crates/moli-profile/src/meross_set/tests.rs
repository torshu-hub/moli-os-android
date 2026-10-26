//! Only loopback HTTP and invented data; no device or cloud service.
use super::*;
use crate::{Config, Profile, ProfileDriver, Source};
use moli_core::{InstanceId, Origin, PointId};
use moli_runtime::{Hub, HubOptions, MasterKey, spawn_driver};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, AtomicUsize, Ordering},
};
use std::time::Duration;
use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
use tokio_util::sync::CancellationToken;

const UUID: &str = "00000000000000000000000000000001";
const OTHER: &str = "00000000000000000000000000000002";
const KEY: &str = "synthetic-simulation-key";
const PROFILE: &str =
    include_str!("../../../../integrations/meross-mss310/simulation/profile.toml");
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Fault {
    None,
    OtherUuid,
    WrongModel,
    BadKey,
    NoAbility,
    BadAck,
    WrongMessage,
    WrongNamespace,
    WrongFrom,
    BadAckSignature,
    AckPayload,
    OldTimestamp,
    Fake200,
    MissingRead,
    FailedRead,
    DifferentRead,
    SetTimeout,
    ReadTimeout,
    PreflightTimeout,
    SlowAbility,
}
struct State {
    on: AtomicBool,
    sets: AtomicUsize,
    calls: AtomicUsize,
    fault: Mutex<Fault>,
    post_set: AtomicBool,
}
struct Fake {
    state: Arc<State>,
    port: u16,
    task: tokio::task::JoinHandle<()>,
}
impl Fake {
    async fn start() -> Self {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let state = Arc::new(State {
            on: AtomicBool::new(false),
            sets: AtomicUsize::new(0),
            calls: AtomicUsize::new(0),
            fault: Mutex::new(Fault::None),
            post_set: AtomicBool::new(false),
        });
        let shared = state.clone();
        let task = tokio::spawn(async move {
            loop {
                let (socket, _) = listener.accept().await.unwrap();
                tokio::spawn(serve(socket, shared.clone()));
            }
        });
        Self { state, port, task }
    }
    fn fault(&self, fault: Fault) {
        *self.state.fault.lock().unwrap() = fault;
    }
    fn sets(&self) -> usize {
        self.state.sets.load(Ordering::SeqCst)
    }
}
impl Drop for Fake {
    fn drop(&mut self) {
        self.task.abort();
    }
}

async fn receive(socket: &mut tokio::net::TcpStream) -> Option<Json> {
    let mut bytes = Vec::new();
    let mut buf = [0_u8; 2048];
    loop {
        let n = socket.read(&mut buf).await.unwrap();
        if n == 0 {
            return None;
        }
        bytes.extend_from_slice(&buf[..n]);
        assert!(bytes.len() < 16384);
        if let Some(i) = bytes.windows(4).position(|s| s == b"\r\n\r\n") {
            let headers = String::from_utf8_lossy(&bytes[..i]);
            assert!(headers.starts_with("POST /config HTTP/1.1"));
            let len: usize = headers
                .lines()
                .find_map(|l| {
                    l.to_ascii_lowercase()
                        .strip_prefix("content-length:")
                        .map(|s| s.trim().parse().unwrap())
                })
                .unwrap();
            if bytes.len() >= i + 4 + len {
                return Some(serde_json::from_slice(&bytes[i + 4..i + 4 + len]).unwrap());
            }
        }
    }
}
async fn serve(mut socket: tokio::net::TcpStream, state: Arc<State>) {
    let Some(request) = receive(&mut socket).await else {
        return;
    };
    state.calls.fetch_add(1, Ordering::SeqCst);
    let h = &request["header"];
    let method = h["method"].as_str().unwrap();
    let namespace = h["namespace"].as_str().unwrap();
    assert_eq!(
        h["sign"],
        meross_signature(
            h["messageId"].as_str().unwrap(),
            KEY,
            h["timestamp"].as_u64().unwrap()
        )
    );
    let fault = *state.fault.lock().unwrap();
    let after_set = state.post_set.load(Ordering::SeqCst);
    if method == "GET" && namespace == ABILITY && fault == Fault::SlowAbility {
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    if method == "GET" && namespace == ALL && fault == Fault::PreflightTimeout {
        tokio::time::sleep(Duration::from_secs(10)).await;
        return;
    }
    if method == "SET" {
        assert_eq!(namespace, TOGGLE);
        // Protocol oracle: MLToggleX uses a dict SET payload, whereas the
        // System.All digest is a list (meross_lan bafedb4, MEDictChannelMixin).
        assert_eq!(request["payload"].as_object().unwrap().len(), 1);
        let toggle = request["payload"]["togglex"].as_object().unwrap();
        assert_eq!(toggle.len(), 2);
        assert_eq!(toggle["channel"], 0);
        let wanted = toggle["onoff"].as_u64().unwrap();
        assert!(wanted <= 1);
        state.sets.fetch_add(1, Ordering::SeqCst);
        state.on.store(wanted == 1, Ordering::SeqCst);
        state.post_set.store(true, Ordering::SeqCst);
    }
    if method == "SET" && fault == Fault::SetTimeout
        || method == "GET" && namespace == ALL && after_set && fault == Fault::ReadTimeout
    {
        tokio::time::sleep(Duration::from_secs(10)).await;
        return;
    }
    let body = reply(&request, &state, fault, after_set).to_string();
    let status = if method == "GET" && namespace == ALL && after_set && fault == Fault::FailedRead {
        503
    } else {
        200
    };
    let _=socket.write_all(format!("HTTP/1.1 {status} Simulated\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await;
}
fn reply(request: &Json, state: &State, fault: Fault, after_set: bool) -> Json {
    let h = &request["header"];
    let method = h["method"].as_str().unwrap();
    let namespace = h["namespace"].as_str().unwrap();
    let uuid = if fault == Fault::OtherUuid {
        OTHER
    } else {
        UUID
    };
    let payload = match (method, namespace) {
        ("GET", ALL) => {
            json!({"all":{"system":{"hardware":{"uuid":uuid,"type":if fault==Fault::WrongModel {"other"}else{"mss310"},"version":"synthetic"}},"digest":{"togglex":[{"channel":0,"onoff":u8::from(state.on.load(Ordering::SeqCst))}]}}})
        }
        ("GET", ABILITY) => {
            if fault == Fault::NoAbility {
                json!({"ability":{}})
            } else {
                json!({"ability":{TOGGLE:{}}})
            }
        }
        ("GET", "Appliance.Control.Electricity") => {
            json!({"electricity":{"power":1000,"current":1,"voltage":2300}})
        }
        ("GET", "Appliance.Control.ConsumptionX") => json!({"consumptionx":[]}),
        ("GET", "Appliance.System.Runtime") => json!({"runtime":{"signal":80}}),
        ("SET", TOGGLE) => json!({}),
        _ => panic!("unexpected simulated RPC"),
    };
    let mut response = request.clone();
    response["payload"] = payload;
    response["header"]["method"] = json!(format!("{method}ACK"));
    response["header"]["from"] = json!(format!("/appliance/{uuid}/publish"));
    if fault == Fault::BadKey {
        response["header"]["sign"] = json!(meross_signature(
            h["messageId"].as_str().unwrap(),
            "another-invented-key",
            h["timestamp"].as_u64().unwrap()
        ));
    }
    if method == "SET" {
        match fault {
            Fault::BadAck => response["header"]["method"] = json!("GETACK"),
            Fault::WrongMessage => {
                response["header"]["messageId"] = json!("foreign");
                response["header"]["sign"] = json!(meross_signature(
                    "foreign",
                    KEY,
                    h["timestamp"].as_u64().unwrap()
                ));
            }
            Fault::WrongNamespace => response["header"]["namespace"] = json!(ALL),
            Fault::WrongFrom => {
                response["header"]["from"] = json!(format!("/appliance/{OTHER}/publish"));
            }
            Fault::BadAckSignature => response["header"]["sign"] = json!("invalid"),
            Fault::AckPayload => response["payload"] = json!({"unexpected":true}),
            Fault::OldTimestamp => {
                response["header"]["timestamp"] = json!(1);
                response["header"]["sign"] =
                    json!(meross_signature(h["messageId"].as_str().unwrap(), KEY, 1));
            }
            Fault::Fake200 => response = json!({"ok":true,"private":KEY,"uuid":UUID}),
            _ => {}
        }
    }
    if method == "GET" && namespace == ALL && after_set {
        match fault {
            Fault::MissingRead => response["payload"] = json!({}),
            Fault::DifferentRead => {
                response["payload"]["all"]["digest"]["togglex"][0]["onoff"] = json!(0);
            }
            _ => {}
        }
    }
    response
}

struct Harness {
    hub: Hub,
    cancel: CancellationToken,
    task: tokio::task::JoinHandle<()>,
    dir: std::path::PathBuf,
    config: Config,
    trust: std::path::PathBuf,
}
impl Harness {
    async fn start(fake: &Fake, text: &str, approve: bool) -> Self {
        Self::options(fake, text, approve, true, false).await
    }
    async fn options(fake: &Fake, text: &str, approve: bool, key: bool, guarded: bool) -> Self {
        static SERIAL: AtomicUsize = AtomicUsize::new(0);
        let dir = std::env::temp_dir().join(format!(
            "moli-meross-set-{}-{}",
            std::process::id(),
            SERIAL.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("profile.toml");
        std::fs::write(&path, text).unwrap();
        let trust = dir.join("trusted-profiles.toml");
        let config = Config {
            profile: path.to_string_lossy().into_owned(),
            host: None,
            port: Some(fake.port),
            vars: std::collections::BTreeMap::from([("expected_uuid".into(), UUID.into())]),
            discovery: false,
            name: None,
        };
        let driver = ProfileDriver::new(config.clone(), trust.clone()).unwrap();
        if approve {
            crate::trust(&trust, driver.file.as_ref().unwrap()).unwrap();
        }
        let hub = Hub::new(HubOptions {
            secrets: Some((dir.join("vault.enc"), MasterKey::new("44".repeat(32)))),
            guard: moli_runtime::guard::GuardPolicy {
                protect_unassigned: guarded,
                ..Default::default()
            },
            ..HubOptions::default()
        })
        .unwrap();
        if key {
            hub.store_secret(&InstanceId::from("simulation"), "key", KEY)
                .await
                .unwrap();
        }
        let cancel = CancellationToken::new();
        let task = spawn_driver(
            &hub,
            InstanceId::from("simulation"),
            Arc::new(driver),
            cancel.clone(),
        );
        let h = Self {
            hub,
            cancel,
            task,
            dir,
            config,
            trust,
        };
        if approve && key {
            h.ready().await;
        }
        h
    }
    async fn ready(&self) {
        tokio::time::timeout(Duration::from_secs(3), async {
            while self.hub.stats().drivers_running != 1 {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .unwrap();
    }
    fn point() -> PointId {
        PointId::from(format!("simulation:{UUID}/on"))
    }
    fn value(&self) -> Value {
        self.hub.state(&Self::point()).unwrap().value
    }
    async fn command(&self, on: bool) -> Result<(), moli_runtime::CommandError> {
        self.hub
            .command(&Self::point(), Value::Bool(on), Origin::Api, None)
            .await
    }
    async fn restart(&mut self) {
        self.cancel.cancel();
        let old = std::mem::replace(&mut self.task, tokio::spawn(async {}));
        old.await.unwrap();
        self.cancel = CancellationToken::new();
        self.task = spawn_driver(
            &self.hub,
            InstanceId::from("simulation"),
            Arc::new(ProfileDriver::new(self.config.clone(), self.trust.clone()).unwrap()),
            self.cancel.clone(),
        );
        self.ready().await;
    }
    async fn stop(self) {
        self.cancel.cancel();
        self.task.await.unwrap();
        drop(self.hub);
        std::fs::remove_dir_all(self.dir).unwrap();
    }
}
#[test]
fn validation_is_narrow_and_the_shipped_package_stays_read_only() {
    let valid = Profile::parse(PROFILE)
        .unwrap()
        .compile(Source::File)
        .unwrap();
    assert!(valid.writes[0].meross);
    for (old, new) in [
        ("127.0.0.1", "192.0.2.1"),
        ("127.0.0.1", "localhost"),
        ("channel = 0", "channel = 1"),
        ("Appliance.Control.ToggleX", "Appliance.System.All"),
        ("kind = \"binary\"", "kind = \"text\""),
        ("refresh = \"all\"", "refresh = \"missing\""),
        (
            "all.payload.all.system.hardware.uuid",
            "all.payload.all.system.hardware.mac",
        ),
        (
            "transport = \"meross\"\nnamespace = \"Appliance.Control.ToggleX\"",
            "transport = \"get\"\nnamespace = \"Appliance.Control.ToggleX\"",
        ),
    ] {
        assert!(
            Profile::parse(&PROFILE.replace(old, new))
                .unwrap()
                .compile(Source::File)
                .is_err(),
            "{old} -> {new}"
        );
    }
    let shipped = Profile::parse(crate::builtin("meross-mss310").unwrap())
        .unwrap()
        .compile(Source::Builtin)
        .unwrap();
    assert!(shipped.writes.is_empty());
    assert!(shipped.points.iter().all(|p| !p.spec.access.write));
}
#[tokio::test]
async fn on_off_repeated_commands_and_restart_use_hub_and_verified_readback() {
    let fake = Fake::start().await;
    let mut h = Harness::start(&fake, PROFILE, true).await;
    assert_eq!(h.value(), Value::Bool(false));
    let (a, b) = tokio::join!(h.command(true), h.command(true));
    assert!(a.is_ok() && b.is_ok());
    assert_eq!(h.value(), Value::Bool(true));
    assert_eq!(fake.sets(), 1);
    assert!(h.command(true).await.is_ok());
    assert_eq!(fake.sets(), 1);
    h.restart().await;
    assert_eq!(fake.sets(), 1);
    assert_eq!(h.value(), Value::Bool(true));
    assert!(h.command(false).await.is_ok());
    assert_eq!(h.value(), Value::Bool(false));
    assert_eq!(fake.sets(), 2);
    assert!(h.hub.journal(10).iter().any(|e| e.origin == Origin::Api));
    h.stop().await;
}
#[tokio::test]
async fn substituted_identity_wrong_key_and_missing_capability_refuse_before_set() {
    for fault in [Fault::OtherUuid, Fault::BadKey, Fault::NoAbility] {
        let fake = Fake::start().await;
        let h = Harness::start(&fake, PROFILE, true).await;
        fake.fault(fault);
        assert!(h.command(true).await.is_err());
        assert_eq!(fake.sets(), 0);
        assert_eq!(h.value(), Value::Null);
        h.stop().await;
    }
}
#[tokio::test]
async fn invalid_acks_and_unconfirmed_readbacks_never_publish_the_commanded_value() {
    for fault in [
        Fault::BadAck,
        Fault::WrongMessage,
        Fault::WrongNamespace,
        Fault::WrongFrom,
        Fault::BadAckSignature,
        Fault::AckPayload,
        Fault::OldTimestamp,
        Fault::Fake200,
        Fault::MissingRead,
        Fault::FailedRead,
        Fault::DifferentRead,
    ] {
        let fake = Fake::start().await;
        let h = Harness::start(&fake, PROFILE, true).await;
        fake.fault(fault);
        let error = h.command(true).await.unwrap_err().to_string();
        assert!(!error.contains(KEY) && !error.contains(UUID), "{fault:?}");
        assert_eq!(fake.sets(), 1);
        assert_eq!(h.value(), Value::Null);
        h.stop().await;
    }
}
#[tokio::test]
async fn timeout_after_send_is_not_retried_and_next_explicit_command_resolves_by_reading() {
    for fault in [Fault::SetTimeout, Fault::ReadTimeout] {
        let fake = Fake::start().await;
        let mut h = Harness::start(&fake, PROFILE, true).await;
        fake.fault(fault);
        assert!(h.command(true).await.is_err());
        assert_eq!(h.value(), Value::Null);
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(fake.sets(), 1);
        fake.fault(Fault::None);
        h.restart().await;
        assert_eq!(fake.sets(), 1);
        assert!(h.command(true).await.is_ok());
        assert_eq!(fake.sets(), 1);
        assert_eq!(h.value(), Value::Bool(true));
        h.stop().await;
    }
}
#[tokio::test]
async fn profile_without_writes_invalid_value_and_untrusted_file_cannot_send_a_set() {
    let fake = Fake::start().await;
    let text = PROFILE.split("[[write]]").next().unwrap();
    let h = Harness::start(&fake, text, true).await;
    let before = fake.state.calls.load(Ordering::SeqCst);
    assert!(h.command(true).await.is_err());
    assert_eq!(fake.state.calls.load(Ordering::SeqCst), before);
    h.stop().await;
    let h = Harness::start(&fake, PROFILE, true).await;
    assert!(
        h.hub
            .command(&Harness::point(), Value::Int(1), Origin::Api, None)
            .await
            .is_err()
    );
    assert_eq!(fake.sets(), 0);
    h.stop().await;
    let fake = Fake::start().await;
    let h = Harness::start(&fake, PROFILE, false).await;
    tokio::time::sleep(Duration::from_millis(50)).await;
    assert_eq!(fake.state.calls.load(Ordering::SeqCst), 0);
    h.stop().await;
}

#[tokio::test]
async fn guard_missing_key_and_abandoned_preflight_prevent_any_set() {
    let fake = Fake::start().await;
    let h = Harness::options(&fake, PROFILE, true, true, true).await;
    let calls = fake.state.calls.load(Ordering::SeqCst);
    assert!(matches!(
        h.command(true).await,
        Err(moli_runtime::CommandError::NeedsApproval { .. })
    ));
    assert_eq!(fake.state.calls.load(Ordering::SeqCst), calls);
    assert_eq!(fake.sets(), 0);
    h.stop().await;
    let fake = Fake::start().await;
    let h = Harness::options(&fake, PROFILE, true, false, false).await;
    tokio::time::sleep(Duration::from_millis(50)).await;
    assert_eq!(fake.state.calls.load(Ordering::SeqCst), 0);
    h.stop().await;
    let fake = Fake::start().await;
    let h = Harness::start(&fake, PROFILE, true).await;
    fake.fault(Fault::SlowAbility);
    let hub = h.hub.clone();
    let caller = tokio::spawn(async move {
        hub.command(&Harness::point(), Value::Bool(true), Origin::Api, None)
            .await
    });
    tokio::time::timeout(Duration::from_secs(2), async {
        while fake.state.calls.load(Ordering::SeqCst) < 3 {
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    })
    .await
    .unwrap();
    caller.abort();
    tokio::time::sleep(Duration::from_millis(250)).await;
    assert_eq!(fake.sets(), 0);
    h.stop().await;
}
#[tokio::test]
async fn expired_preflight_and_a_moved_endpoint_do_not_write_to_a_substituted_target() {
    let fake = Fake::start().await;
    let mut h = Harness::start(&fake, PROFILE, true).await;
    fake.fault(Fault::PreflightTimeout);
    assert!(h.command(true).await.is_err());
    assert_eq!(fake.sets(), 0);
    let mut forbidden = h.config.clone();
    forbidden.host = Some("127.0.0.2".into());
    assert!(ProfileDriver::new(forbidden, h.trust.clone()).is_err());
    let replacement = Fake::start().await;
    h.config.port = Some(replacement.port);
    h.restart().await;
    assert!(h.command(true).await.is_ok());
    assert_eq!(replacement.sets(), 1);
    assert_eq!(fake.sets(), 0);
    assert_eq!(h.value(), Value::Bool(true));
    h.stop().await;
}

#[tokio::test]
async fn an_ack_does_not_confirm_the_state_before_the_readback_finishes() {
    let fake = Fake::start().await;
    let h = Harness::start(&fake, PROFILE, true).await;
    fake.fault(Fault::ReadTimeout);
    let hub = h.hub.clone();
    let caller = tokio::spawn(async move {
        hub.command(&Harness::point(), Value::Bool(true), Origin::Api, None)
            .await
    });
    tokio::time::timeout(Duration::from_secs(2), async {
        while fake.state.calls.load(Ordering::SeqCst) < 5 {
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(fake.sets(), 1);
    assert!(!caller.is_finished());
    assert_eq!(h.value(), Value::Null);
    caller.abort();
    h.stop().await;
}

#[tokio::test]
async fn only_a_valid_identity_read_restores_availability_after_a_refused_command() {
    let fake = Fake::start().await;
    let text = PROFILE.replace("every = \"1h\"", "every = \"5s\"");
    let h = Harness::start(&fake, &text, true).await;
    fake.fault(Fault::OtherUuid);
    assert!(h.command(true).await.is_err());
    assert_eq!(h.value(), Value::Null);
    let calls = fake.state.calls.load(Ordering::SeqCst);
    tokio::time::timeout(Duration::from_secs(6), async {
        while fake.state.calls.load(Ordering::SeqCst) <= calls {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    tokio::time::sleep(Duration::from_millis(50)).await;
    assert_eq!(h.hub.snapshot().devices[0].online, Some(false));
    fake.fault(Fault::None);
    tokio::time::timeout(Duration::from_secs(6), async {
        while h.hub.snapshot().devices[0].online != Some(true) {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(h.value(), Value::Bool(false));
    assert_eq!(fake.sets(), 0);
    h.stop().await;
}

impl Harness {
    async fn manual(fake: &Fake) -> Self {
        let mut h = Self::start(fake, PROFILE, true).await;
        h.config.profile = "meross-mss310-manual".into();
        h.config.host = Some("127.0.0.1".into());
        h.config.vars.insert("manual_uuid".into(), UUID.into());
        h.restart().await;
        h
    }
    async fn human(&self, on: bool, origin: Origin) -> Result<(), moli_runtime::CommandError> {
        self.hub
            .command(&Self::point(), Value::Bool(on), origin, None)
            .await
    }
    fn step(&self, stage: &str) -> Json {
        serde_json::to_value(self.hub.snapshot()).unwrap()["diagnostics"][0]["stages"][stage]
            .clone()
    }
}

#[tokio::test]
async fn manual_opt_in_uses_original_reader_human_origin_guard_and_read_only_revocation() {
    let fake = Fake::start().await;
    let mut h = Harness::manual(&fake).await;
    assert_eq!(fake.sets(), 0, "startup cannot replay a command");
    assert_eq!(h.value(), Value::Bool(false));
    assert_eq!(h.step("parsing")["status"], "ok");
    for origin in [
        Origin::Api,
        Origin::Mcp,
        Origin::Cli,
        Origin::System,
        Origin::Assistant,
        Origin::Automation,
    ] {
        let calls = fake.state.calls.load(Ordering::SeqCst);
        assert!(h.human(true, origin).await.is_err());
        assert_eq!(
            fake.state.calls.load(Ordering::SeqCst),
            calls,
            "refuse before any request"
        );
        assert_eq!(h.value(), Value::Bool(false));
    }
    assert_eq!(h.step("command_preflight")["status"], "authorization");
    assert!(h.human(true, Origin::Household).await.is_ok());
    assert_eq!(h.value(), Value::Bool(true));
    for stage in [
        "command_preflight",
        "command_send",
        "command_ack",
        "command_readback",
    ] {
        assert_eq!(h.step(stage)["status"], "ok", "{stage}");
    }
    assert_eq!(h.step("command_preflight")["errors"]["authorization"], 6);
    assert!(h.human(true, Origin::Ui).await.is_ok());
    assert_eq!(fake.sets(), 1);
    assert_eq!(h.step("command_send")["status"], "unobserved");
    assert_eq!(h.step("command_readback")["status"], "ok");
    assert!(h.human(false, Origin::Ui).await.is_ok());
    assert_eq!(fake.sets(), 2);
    h.restart().await;
    assert_eq!(fake.sets(), 2);
    h.config.profile = "meross-mss310".into();
    h.restart().await;
    assert!(h.human(true, Origin::Ui).await.is_err());
    assert_eq!(fake.sets(), 2);
    assert_eq!(h.config.vars["expected_uuid"], UUID);
    assert_eq!(h.config.vars["manual_uuid"], UUID);
    h.stop().await;
}

#[tokio::test]
async fn manual_command_diagnostics_close_failed_or_ambiguous_phases_without_retry() {
    for (fault, phase, outcome, sets) in [
        (
            Fault::WrongModel,
            "command_preflight",
            "invalid_response",
            0,
        ),
        (Fault::BadAckSignature, "command_ack", "invalid_response", 1),
        (Fault::ReadTimeout, "command_readback", "timeout", 1),
        (Fault::SetTimeout, "command_ack", "timeout", 1),
    ] {
        let fake = Fake::start().await;
        let h = Harness::manual(&fake).await;
        fake.fault(fault);
        assert!(h.human(true, Origin::Ui).await.is_err());
        assert_eq!(h.value(), Value::Null);
        assert_eq!(fake.sets(), sets);
        assert_eq!(h.step(phase)["status"], outcome, "{fault:?}");
        if phase == "command_readback" {
            assert_eq!(h.step("command_ack")["status"], "ok");
        }
        let summary = serde_json::to_string(&h.hub.snapshot().diagnostics).unwrap();
        assert!(!summary.contains("pending"));
        for sensitive in [KEY, UUID, "127.0.0.1"] {
            assert!(!summary.contains(sensitive));
        }
        h.stop().await;
    }
}

#[test]
fn manual_builtin_is_a_scoped_variant_not_a_way_to_trust_external_lan_files() {
    let text = crate::builtin("meross-mss310-manual").unwrap();
    let p = Profile::parse(text).unwrap();
    assert!(p.compile(Source::File).is_err());
    let profile = Profile::parse(text)
        .unwrap()
        .compile(Source::Builtin)
        .unwrap();
    assert_eq!(profile.meta.id, "meross-mss310");
    assert_eq!(profile.writes.len(), 1);
    let mut target = Target {
        host: "192.168.1.20".into(),
        port: 80,
        tls: false,
        vars: std::collections::BTreeMap::from([("manual_uuid".into(), UUID.into())]),
    };
    assert!(expected(&target, Some(KEY), &profile).is_ok());
    target.vars.insert("expected_uuid".into(), OTHER.into());
    assert!(expected(&target, Some(KEY), &profile).is_err());
    target.vars.remove("expected_uuid");
    for host in ["8.8.8.8", "169.254.169.254", "example.invalid"] {
        target.host = host.into();
        assert!(expected(&target, Some(KEY), &profile).is_err());
    }
}
