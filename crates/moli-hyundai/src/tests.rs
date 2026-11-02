//! Synthetic wire contracts, no account/vehicle access.
use crate::{
    account::{Account, Phase, Vault},
    client::{Client, Error, Response, Session, Transport},
    crypto, model,
};
use http::{HeaderMap, Request};
use http_body_util::{BodyExt, Full};
use moli_runtime::BoxFuture;
use serde_json::{Value as Json, json};
use std::{
    collections::VecDeque,
    sync::{Arc, Mutex},
};
#[derive(Clone)]
struct Fake {
    responses: Arc<Mutex<VecDeque<Result<Response, Error>>>>,
    requests: Arc<Mutex<Vec<(String, HeaderMap, String)>>>,
}
impl Fake {
    fn new(responses: Vec<Result<Response, Error>>) -> Self {
        Self {
            responses: Arc::new(Mutex::new(responses.into())),
            requests: Arc::default(),
        }
    }
}
impl Transport for Fake {
    fn send(&self, r: Request<Full<moli_net::Body>>) -> BoxFuture<'_, Result<Response, Error>> {
        Box::pin(async move {
            let (parts, body) = r.into_parts();
            let body =
                String::from_utf8(body.collect().await.unwrap().to_bytes().to_vec()).unwrap();
            self.requests
                .lock()
                .unwrap()
                .push((parts.uri.to_string(), parts.headers, body));
            self.responses
                .lock()
                .unwrap()
                .pop_front()
                .expect("no unplanned HTTP")
        })
    }
}
#[allow(clippy::needless_pass_by_value, clippy::unnecessary_wraps)] // Homogeneous synthetic success/error response queue.
fn response(status: u16, body: Json) -> Result<Response, Error> {
    Ok(Response {
        status,
        headers: HeaderMap::new(),
        body: body.to_string().into_bytes(),
    })
}
fn session() -> Session {
    Session {
        device: "00000000-0000-0000-0000-000000000000".into(),
        cci: json!({"accessToken":"synthetic-access","refreshToken":"synthetic-refresh","nonCcsToken":"synthetic-non-ccs","nonCcsRefreshToken":"synthetic-non-refresh","exchangeableAccessToken":"synthetic-exchange","exchangeableRefreshToken":"synthetic-exchange-refresh","idToken":"synthetic-id"}),
        access: "synthetic-ccs".into(),
        expires: moli_core::now_ms() / 1000 + 3600,
        user: "synthetic-user".into(),
        pin: Some("0000".into()),
        control: None,
        control_expires: 0,
    }
}
#[tokio::test]
async fn full_login_encrypts_password_and_signs_real_body_without_remote_orders() {
    use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
    let cert =
        json!({"retValue":{"n":URL_SAFE_NO_PAD.encode([255_u8;256]),"e":"AQAB","kid":"synthetic"}});
    let jwt = format!(
        "x.{}.x",
        URL_SAFE_NO_PAD.encode(br#"{"uid":"synthetic-user"}"#)
    );
    let mut redirect = response(302, Json::Null).unwrap();
    redirect.headers.insert(
        "location",
        "https://oneapp.hyundai.com/redirect?code=synthetic-code"
            .parse()
            .unwrap(),
    );
    let wire = Fake::new(vec![
        response(200, json!({})),
        response(200, cert),
        Ok(redirect),
        response(200, session().cci),
        response(200, json!({"accessToken":jwt,"expiresTime":"3600"})),
    ]);
    let mut client = Client::with_wire(wire.clone());
    let result = client
        .login("synthetic-user", "synthetic-password", None)
        .await
        .unwrap();
    assert_eq!(result.user, "synthetic-user");
    let calls = wire.requests.lock().unwrap();
    assert_eq!(calls.len(), 5);
    assert!(
        calls
            .iter()
            .all(|(url, _, _)| !url.contains("remote/vehicles"))
    );
    assert!(!calls[2].2.contains("synthetic-password"));
    let form: std::collections::BTreeMap<_, _> = url::form_urlencoded::parse(calls[2].2.as_bytes())
        .into_owned()
        .collect();
    assert_eq!(form["password"].len(), 512);
    assert!(form["password"].bytes().all(|v| v.is_ascii_hexdigit()));
    let (_, headers, body) = &calls[3];
    let timestamp = headers["x-timestamp"].to_str().unwrap().parse().unwrap();
    assert_eq!(
        headers["x-fingerprint"],
        crypto::fingerprint(timestamp, body)
    );
    assert!(!format!("{result:?}").contains("synthetic-user"));
}
#[tokio::test]
async fn refresh_sends_all_seven_fields_without_bearer_and_retains_rotation_bundle() {
    let mut s = session();
    let wire = Fake::new(vec![response(
        200,
        json!({"accessToken":"rotated","refreshToken":"rotated-refresh"}),
    )]);
    let mut client = Client::with_wire(wire.clone());
    client.refresh(&mut s).await.unwrap();
    assert!(s.access.is_empty());
    assert_eq!(s.cci["accessToken"], "rotated");
    assert_eq!(s.cci["nonCcsRefreshToken"], "synthetic-non-refresh");
    let calls = wire.requests.lock().unwrap();
    let (_, h, b) = &calls[0];
    assert!(!h.contains_key("authorization"));
    let body: Json = serde_json::from_str(b).unwrap();
    assert_eq!(body.as_object().unwrap().len(), 7);
    assert_eq!(
        h["x-fingerprint"],
        crypto::fingerprint(h["x-timestamp"].to_str().unwrap().parse().unwrap(), b)
    );
}
#[tokio::test]
async fn pin_token_and_gspa_body_follow_reference_without_hidden_retry() {
    let wire = Fake::new(vec![
        response(
            200,
            json!({"isMatched":true,"controlTokenInfo":{"controlToken":"synthetic-control","expiresTime":"600"}}),
        ),
        response(
            202,
            json!({"metaInfo":{"retCode":"S","resCode":"202-000"},"data":{"sid":"synthetic"}}),
        ),
    ]);
    let mut client = Client::with_wire(wire.clone());
    let mut s = session();
    let h = client.prepare_control(&mut s, true, 2).await.unwrap();
    assert_eq!(h["authorization"], "Bearer synthetic-control");
    assert_eq!(h["authorizationccsp"], "Bearer synthetic-control");
    client
        .control_post(
            "remote/vehicles/synthetic/door-unlock",
            &h,
            &json!({"command":"set"}),
        )
        .await
        .unwrap();
    assert_eq!(wire.requests.lock().unwrap().len(), 2);
    assert!((599..=600).contains(&s.control_expires.saturating_sub(moli_core::now_ms() / 1000)));
}
#[tokio::test]
async fn quota_and_2xx_failure_are_not_success_or_retried() {
    for r in [
        response(429, json!({})),
        response(200, json!({"metaInfo":{"retCode":"F","resCode":"400-001"}})),
        Err(Error::Network),
    ] {
        let wire = Fake::new(vec![r]);
        let mut client = Client::with_wire(wire.clone());
        assert!(
            client
                .control_post(
                    "remote/vehicles/synthetic/charge-target",
                    &HeaderMap::new(),
                    &json!({"command":"set"})
                )
                .await
                .is_err()
        );
        assert_eq!(wire.requests.lock().unwrap().len(), 1);
    }
}
#[derive(Default)]
struct Memory {
    rows: Mutex<Vec<String>>,
}
impl Vault for Memory {
    fn get(&self) -> Result<Option<String>, Error> {
        Ok(self.rows.lock().unwrap().last().cloned())
    }
    fn put<'a>(&'a self, text: &'a str) -> BoxFuture<'a, Result<(), Error>> {
        Box::pin(async move {
            self.rows.lock().unwrap().push(text.into());
            Ok(())
        })
    }
}
#[tokio::test]
async fn ambiguous_rotation_is_marked_durably_before_io_and_not_replayed() {
    let wire = Fake::new(vec![Err(Error::Network)]);
    let mut client = Client::with_wire(wire.clone());
    let mut s = session();
    s.expires = 0;
    let mut account = Account {
        session: s,
        vehicles: vec![],
        phase: Phase::Ready,
    };
    let vault = Memory::default();
    assert_eq!(
        account.ensure(&mut client, &vault).await.unwrap_err(),
        Error::Network
    );
    assert_eq!(
        Account::parse(&vault.get().unwrap().unwrap())
            .unwrap()
            .phase,
        Phase::Rotating
    );
    assert_eq!(
        account.ensure(&mut client, &vault).await.unwrap_err(),
        Error::Authentication
    );
    assert_eq!(wire.requests.lock().unwrap().len(), 1);
}
#[test]
fn schema_ignores_unknown_location_containers_and_preserves_valid_zero() {
    let value = json!({"state":{"Vehicle":{"Date":"20261101000000","VIN":"synthetic-private","Location":{"GeoCoord":{"Latitude":10}},"Cabin":{"Door":{"Row1":{"Driver":{"Lock":0},"Passenger":{"Lock":0}},"Row2":{"Left":{"Lock":0},"Right":{"Lock":0}}}},"Green":{"BatteryManagement":{"BatteryRemain":{"Ratio":0}}}}}});
    let s = model::Snapshot::parse(&value, 1_793_491_200_000, 3_600_000);
    assert_eq!(s.values["soc"], 0.0);
    assert_eq!(s.values["is_locked"], true);
    let safe = format!("{s:?}");
    assert!(!safe.contains("synthetic-private"));
    assert!(!safe.contains("Latitude"));
}

struct CommandGate;
impl moli_runtime::Driver for CommandGate {
    fn kind(&self) -> &'static str {
        "hyundai-test"
    }
    fn run<'a>(
        &'a self,
        ctx: &'a mut moli_runtime::DriverCtx,
    ) -> BoxFuture<'a, anyhow::Result<()>> {
        Box::pin(async move {
            let config = crate::Config {
                commands_enabled: true,
                ..crate::Config::default()
            };
            let driver =
                crate::Hyundai::new(config, std::path::Path::new("unused-synthetic-path"))?;
            let vehicle = model::Vehicle {
                key: "v_synthetic".into(),
                id: Some("synthetic-car".into()),
                name: "Synthetic Ioniq".into(),
                model: "Ioniq 5".into(),
                ccs2: 2,
            };
            let id = ctx.device_id(&vehicle.key);
            let snapshot = model::Snapshot::parse(
                &json!({"state":{"Vehicle":{"Date":moli_core::now_ms(),"Cabin":{"Door":{"Row1":{"Driver":{"Lock":0}}}}}}}),
                moli_core::now_ms(),
                3_600_000,
            );
            let record = crate::Record {
                vehicle,
                snapshot,
                private: Arc::default(),
                unsupported: std::collections::BTreeMap::new(),
            };
            driver.publish(ctx, &id, &record, true);
            ctx.ready();
            let mut records = std::collections::BTreeMap::from([(id, record)]);
            let mut client = Client::new();
            while let Some(cmd) = ctx.next_command().await {
                let result = driver
                    .command(ctx, &mut client, None, &mut records, &cmd)
                    .await;
                cmd.reply(result.map_err(|e| e.code().into()));
            }
            Ok(())
        })
    }
}
#[tokio::test]
async fn actual_hub_origin_reaches_driver_and_every_non_human_origin_is_refused() {
    use moli_core::{InstanceId, Origin, PointId, Value};
    use moli_runtime::{Hub, HubOptions, spawn_driver};
    let hub = Hub::new(HubOptions::default()).unwrap();
    let cancel = tokio_util::sync::CancellationToken::new();
    spawn_driver(
        &hub,
        InstanceId::from("synthetic"),
        Arc::new(CommandGate),
        cancel.clone(),
    );
    let point = PointId::from("synthetic:v_synthetic/lock");
    for _ in 0..100 {
        if hub
            .state(&PointId::from("synthetic:v_synthetic/capabilities"))
            .is_some()
        {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    }
    for origin in [
        Origin::Automation,
        Origin::System,
        Origin::Api,
        Origin::Mcp,
        Origin::Cli,
        Origin::Assistant,
        Origin::Household,
    ] {
        let result = hub.command(&point, Value::from("{}"), origin, None).await;
        match result.unwrap_err() {
            moli_runtime::CommandError::Driver(reason) => assert_eq!(reason, "unsupported"),
            moli_runtime::CommandError::NeedsApproval { .. } => {}
            other => panic!("unexpected gate result: {origin:?} {other}"),
        }
    }
    let result = hub
        .command(&point, Value::from("{}"), Origin::Ui, None)
        .await;
    assert!(result.unwrap_err().to_string().contains("authentication"));
    cancel.cancel();
}

#[test]
fn synthetic_vehicle_exposes_eu_commands_with_reference_dtos() {
    let payload: Json = serde_json::from_str(include_str!(
        "../../../integrations/hyundai/fixtures/stored-status.json"
    ))
    .unwrap();
    let s = model::Snapshot::parse(&payload, 1_793_534_400_000, 3_600_000);
    let car = model::Vehicle {
        key: "synthetic".into(),
        id: None,
        name: "Synthetic".into(),
        model: "Ioniq 5".into(),
        ccs2: 2,
    };
    let caps = crate::commands::capabilities(&car, &s.state, true, true);
    let get = |key| caps.iter().find(|c| c.key == key).unwrap();
    let window = crate::commands::build(
        get("set_windows"),
        &json!({"front_left":2,"front_right":0}),
        &s.state,
    )
    .unwrap();
    assert_eq!(window.endpoint, "window-curtain");
    assert_eq!(window.body["drvSeatLoc"], "L");
    assert_eq!(window.body["drvSeatWindow"], 2);
    let climate = crate::commands::build(
        get("start_climate"),
        &json!({"temperature":21.5,"duration":10,"defrost":true,"driver_seat":6}),
        &s.state,
    )
    .unwrap();
    assert_eq!(climate.body["tempUnit"], "C");
    assert_eq!(climate.body["hvacTemp"], "21.5");
    let force = crate::commands::build(get("force_refresh"), &json!({}), &s.state).unwrap();
    assert_eq!(force.prefix, "remote/vehicles");
    assert_eq!(force.body, json!({"action":"prewakeup"}));
    let schedule=crate::commands::build(get("schedule_charging_and_climate"),&json!({"temperature":21.5,"charging":true,"climate":true,"first_enabled":true,"first_days":[1,2,3,4,5],"first_time":"07:30","second_enabled":false,"second_days":[0],"second_time":"12:00","off_peak_only":true,"off_peak_start":"22:00","off_peak_end":"06:00"}),&s.state).unwrap();
    assert_eq!(
        schedule.body["reservChargeInfo"]["reservChargeInfo2"]["reservInfo"]["time"]["timeSection"],
        1
    );
    assert_eq!(schedule.body["offPeakPowerInfo"]["offPeakPowerFlag"], 2);
    assert_eq!(s.values["ev_range_km"], 300.0);
    assert_eq!(s.values["ev_battery_capacity"], 277_200);
    assert_eq!(s.values["front_left_door_is_locked"], true);
}
