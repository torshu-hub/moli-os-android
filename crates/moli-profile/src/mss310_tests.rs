//! Focused code39 HTTP fixture reuse: a finite exchange sequence, no device simulator.
use super::*;
use crate::{Config, ProfileDriver};
use moli_core::{InstanceId, Origin, PointId};
use moli_runtime::{Hub, HubOptions, MasterKey, spawn_driver};
use serde_json::Value as Json;
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};
use std::time::Duration;
use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
use tokio_util::sync::CancellationToken;
const UUID: &str = "00000000000000000000000000000001";
const KEY: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
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

fn reply(request: &Json, on: bool) -> Json {
    let namespace = request["header"]["namespace"].as_str().unwrap();
    let name = match namespace {
        ALL => "all",
        "Appliance.Control.Electricity" => "electricity",
        "Appliance.Control.ConsumptionX" => "consumption",
        "Appliance.System.Runtime" => "runtime",
        TOGGLE => "",
        _ => panic!("unexpected namespace"),
    };
    let mut response = request.clone();
    response["header"]["method"] = json!(format!(
        "{}ACK",
        request["header"]["method"].as_str().unwrap()
    ));
    response["header"]["from"] = json!(format!("/appliance/{UUID}/publish"));
    response["payload"] = if name.is_empty() {
        json!({})
    } else {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(format!(
            "../../integrations/meross-mss310/fixtures/{name}.json"
        ));
        let body: Json = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
        body["payload"].clone()
    };
    if namespace == ALL {
        response["payload"]["all"]["digest"]["togglex"][0]["onoff"] = json!(u8::from(on));
    }
    response
}

#[test]
fn code39_reply_binding_rejects_wrong_key_identity_and_ack() {
    let request = super::super::meross_message_method(
        "SET",
        TOGGLE,
        &json!({"togglex":{"channel":0,"onoff":1}}),
        KEY,
    );
    let valid = reply(&request, true);
    validate_reply(&request, &valid, KEY, Some(UUID)).unwrap();
    for (field, value) in [
        ("method", json!("GETACK")),
        ("messageId", json!("foreign")),
        ("namespace", json!(ALL)),
        (
            "from",
            json!("/appliance/00000000000000000000000000000002/publish"),
        ),
        ("sign", json!("invalid")),
    ] {
        let mut changed = valid.clone();
        changed["header"][field] = value;
        assert!(validate_reply(&request, &changed, KEY, Some(UUID)).is_err());
    }
    assert!(validate_reply(&request, &valid, "wrong-key", Some(UUID)).is_err());
}

async fn fixture() -> (u16, Arc<AtomicUsize>, tokio::task::JoinHandle<()>) {
    // Reuse code39's signed request/reply fixtures, in a finite script.
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let sets = Arc::new(AtomicUsize::new(0));
    let seen = sets.clone();
    let worker = tokio::spawn(async move {
        let mut on = false;
        for (method, namespace) in [
            ("GET", ALL),
            ("GET", "Appliance.Control.Electricity"),
            ("GET", "Appliance.Control.ConsumptionX"),
            ("GET", "Appliance.System.Runtime"),
            ("GET", ALL),
            ("SET", TOGGLE),
            ("GET", ALL),
            ("GET", ALL), // repeated explicit ON reads state, without a second SET
            ("GET", ALL),
            ("SET", TOGGLE),
            ("GET", ALL),
        ] {
            let (mut socket, _) = listener.accept().await.unwrap();
            let request = receive(&mut socket).await.unwrap();
            assert_eq!(request["header"]["method"], method);
            assert_eq!(request["header"]["namespace"], namespace);
            assert_eq!(
                request["header"]["sign"],
                super::super::meross_signature(
                    request["header"]["messageId"].as_str().unwrap(),
                    KEY,
                    request["header"]["timestamp"].as_u64().unwrap()
                )
            );
            if method == "SET" {
                assert_eq!(request["payload"]["togglex"]["channel"], 0);
                on = request["payload"]["togglex"]["onoff"] == 1;
                seen.fetch_add(1, Ordering::SeqCst);
            }
            let body = reply(&request, on).to_string();
            socket
                .write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    )
                    .as_bytes(),
                )
                .await
                .unwrap();
        }
    });
    (port, sets, worker)
}

#[tokio::test]
async fn official_hub_exposes_measurements_and_on_off_to_api_and_automations_without_extra_permission()
 {
    let (port, sets, worker) = fixture().await;
    let dir = std::env::temp_dir().join(format!("moli-mss310-hub-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let hub = Hub::new(HubOptions {
        secrets: Some((dir.join("secrets.enc"), MasterKey::new("44".repeat(32)))),
        guard: moli_runtime::guard::GuardPolicy {
            protect_unassigned: false,
            ..Default::default()
        },
        ..HubOptions::default()
    })
    .unwrap();
    let instance = InstanceId::from("plug");
    hub.store_secret(&instance, "key", KEY).await.unwrap();
    let config:Config=serde_json::from_value(json!({"profile":"meross-mss310","host":"127.0.0.1","port":port,"name":"Test plug","vars":{"expected_uuid":UUID}})).unwrap();
    let cancel = CancellationToken::new();
    let task = spawn_driver(
        &hub,
        instance,
        Arc::new(ProfileDriver::new(config, dir.join("trusted-profiles.toml")).unwrap()),
        cancel.clone(),
    );
    tokio::time::timeout(Duration::from_secs(3), async {
        while hub.stats().drivers_running != 1 {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(sets.load(Ordering::SeqCst), 0); // startup never writes
    let point = PointId::from(format!("plug:{UUID}/on"));
    assert_eq!(
        hub.state(&point).unwrap().value,
        moli_core::Value::Bool(false)
    );
    assert_eq!(
        hub.state(&PointId::from(format!("plug:{UUID}/power")))
            .unwrap()
            .value
            .as_f64(),
        Some(123.456)
    );
    for (wanted, origin) in [
        (true, Origin::Api),
        (true, Origin::Ui),
        (false, Origin::Automation),
    ] {
        hub.command(&point, moli_core::Value::Bool(wanted), origin, None)
            .await
            .unwrap();
        assert_eq!(
            hub.state(&point).unwrap().value,
            moli_core::Value::Bool(wanted)
        );
    }
    assert_eq!(sets.load(Ordering::SeqCst), 2);
    cancel.cancel();
    task.await.unwrap();
    worker.await.unwrap();
    drop(hub);
    std::fs::remove_dir_all(dir).unwrap();
}
