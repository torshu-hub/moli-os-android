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
        api_mode: crate::auth::ApiMode::default(),
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
                api_mode: crate::auth::ApiMode::Gspa,
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

#[allow(clippy::unnecessary_wraps)] // Same fallible queue interface as synthetic responses.
fn redirect(target: &str) -> Result<Response, Error> {
    let mut r = response(302, Json::Null).unwrap();
    r.headers.insert("location", target.parse().unwrap());
    Ok(r)
}
fn synthetic_cert() -> Json {
    use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
    json!({"retValue":{"n":URL_SAFE_NO_PAD.encode([255_u8;256]),"e":"AQAB","kid":"synthetic"}})
}
#[tokio::test]
async fn legitimate_oneapp_authorize_redirect_and_signin_chain_are_not_false_consent() {
    use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
    let mut initial = redirect("https://oneapp.hyundai.com/login").unwrap();
    initial.headers.insert(
        "set-cookie",
        "idp_session=synthetic; Secure; HttpOnly".parse().unwrap(),
    );
    let wire = Fake::new(vec![
        Ok(initial),
        response(200, json!({})),
        response(200, synthetic_cert()),
        redirect("/auth/complete"),
        redirect("https://oneapp.hyundai.com/redirect?code=synthetic-code"),
        response(200, session().cci),
        response(
            200,
            json!({"accessToken":format!("x.{}.x",URL_SAFE_NO_PAD.encode(br#"{"uid":"synthetic-user"}"#)),"expiresTime":3600}),
        ),
    ]);
    let mut client = Client::with_wire(wire.clone());
    client
        .login("user@example.com", "synthetic-password", None)
        .await
        .unwrap();
    let req = wire.requests.lock().unwrap();
    assert!(req[1].1.get("cookie").is_none());
    assert!(
        req[2].1["cookie"]
            .to_str()
            .unwrap()
            .contains("idp_session=synthetic")
    );
    assert!(
        req.iter()
            .all(|(url, _, _)| !url.starts_with("https://oneapp.hyundai.com/redirect"))
    );
    assert_eq!(req.len(), 7);
}
#[tokio::test]
async fn safe_diagnostics_distinguish_authorize_redirect_provider_terms_and_discovery() {
    let wire = Fake::new(vec![redirect(
        "https://other.example.org/?code=synthetic-secret",
    )]);
    let mut c = Client::with_wire(wire.clone());
    let e = c
        .login("user@example.com", "synthetic", None)
        .await
        .unwrap_err();
    let d = c.failure(e);
    assert_eq!(d.diagnostic.phase, "authorize");
    assert_eq!(d.diagnostic.cause, "redirect_not_allowed");
    assert_eq!(d.diagnostic.http, Some(302));
    assert!(!format!("{d:?}").contains("synthetic-secret"));
    assert_eq!(wire.requests.lock().unwrap().len(), 1);
    let mut c = Client::with_wire(Fake::new(vec![
        response(200, json!({})),
        response(200, synthetic_cert()),
        redirect("/web/v1/user/authorization?session=synthetic-private"),
    ]));
    let e = c
        .login("user@example.com", "synthetic", None)
        .await
        .unwrap_err();
    let d = c.failure(e);
    assert_eq!(d.diagnostic.phase, "signin_redirect");
    assert_eq!(d.diagnostic.cause, "provider_terms_required");
    assert!(
        !serde_json::to_string(&d.diagnostic)
            .unwrap()
            .contains("synthetic-private")
    );
    let mut c = Client::with_wire(Fake::new(vec![response(503, Json::Null)]));
    let e = c.vehicles(&session()).await.unwrap_err();
    assert_eq!(c.failure(e).diagnostic.phase, "fleet_discovery");
}
#[tokio::test]
async fn legacy_mode_uses_ccapi_registration_fleet_cache_and_passive_history_query() {
    let wire = Fake::new(vec![
        response(
            200,
            json!({"retCode":"S","resMsg":{"deviceId":"synthetic-device"}}),
        ),
        response(
            200,
            json!({"retCode":"S","resMsg":{"vehicles":[{"vehicleId":"synthetic-car","nickname":"Ioniq test","vehicleName":"Ioniq 5","ccuCCS2ProtocolSupport":0}]}}),
        ),
        response(
            200,
            json!({"retCode":"S","resMsg":{"vehicleStatusInfo":{"vehicleStatus":{"time":"20261102120000","battery":{"batSoc":80},"doorLock":true,"evStatus":{"batteryStatus":50,"batteryCharge":false,"batteryPlugin":2,"chargePortDoorOpenStatus":2}},"odometer":{"value":1000,"unit":1}}}}),
        ),
        response(
            200,
            json!({"retCode":"S","resMsg":{"drivingInfoDetail":[{"drivingDate":"20261101","totalPwrCsp":16000,"calculativeOdo":100}]}}),
        ),
    ]);
    let mut c = Client::with_wire(wire.clone());
    let mode = crate::auth::ApiMode::Europe;
    let device = c.login_identity(mode).await.unwrap();
    let mut s = session();
    s.api_mode = mode;
    s.device = device;
    let fleet = c.vehicles(&s).await.unwrap();
    let v = model::vehicles(&fleet);
    assert_eq!(v.len(), 1);
    assert_eq!(v[0].ccs2, 0);
    let cached = c.cached_status(&mut s, &v[0]).await.unwrap();
    let snapshot = model::Snapshot::parse(
        &cached,
        model::timestamp(&json!("20261102110000")).unwrap(),
        3_600_000,
    );
    assert_eq!(snapshot.values["soc"], 50.0);
    assert_eq!(snapshot.values["is_locked"], true);
    assert_eq!(snapshot.values["charging"], false);
    assert_eq!(snapshot.values["ev_battery_is_plugged_in"], true);
    assert_eq!(snapshot.quality, "cached_recent");
    let days = c
        .auxiliary(&mut s, &v[0], "driving_history", "ignored-gspa-route")
        .await
        .unwrap();
    assert_eq!(days["drivingInfoDetail"][0]["totalPwrCsp"], 16000);
    let req = wire.requests.lock().unwrap();
    assert!(
        req.iter()
            .all(|(u, _, _)| u.starts_with(crate::legacy::BASE))
    );
    assert!(req[2].0.ends_with("/status/latest"));
    assert_eq!(
        serde_json::from_str::<Json>(&req[3].2).unwrap(),
        json!({"periodTarget":0})
    );
    assert!(
        req.iter()
            .all(|(u, _, _)| !u.contains("/control/") && !u.ends_with("/status"))
    );
    assert_eq!(
        req[1].1["ccsp-application-id"],
        "014d2225-8495-4735-812d-2616334fd15d"
    );
}
#[test]
fn legacy_commands_translate_real_endpoints_and_do_not_enable_unimplemented_gspa_features() {
    let v = model::Vehicle {
        key: "v_synthetic".into(),
        id: Some("synthetic-car".into()),
        name: "synthetic".into(),
        model: "Ioniq".into(),
        ccs2: 0,
    };
    let state = json!({"Cabin":{"Door":{},"HVAC":{},"Window":{}},"Green":{"ChargingInformation":{},"ChargingDoor":{"State":2}},"Electronics":{"Battery":{"Level":80}}});
    let caps = crate::legacy::capabilities(&v, &state, true, true);
    let lock = caps.iter().find(|c| c.key == "lock").unwrap();
    assert!(lock.available);
    assert!(!lock.pin);
    let order = crate::legacy::order(&v, lock, &json!({}), &state, "synthetic-device").unwrap();
    assert_eq!(
        order.path,
        "/api/v1/spa/vehicles/synthetic-car/control/door"
    );
    assert_eq!(
        order.body,
        json!({"action":"lock","deviceId":"synthetic-device"})
    );
    assert!(
        !caps
            .iter()
            .find(|c| c.key == "capture_svm_image")
            .unwrap()
            .available
    );
    assert!(
        !caps
            .iter()
            .find(|c| c.key == "set_charging_current")
            .unwrap()
            .available
    );
    let force = caps.iter().find(|c| c.key == "force_refresh").unwrap();
    let order = crate::legacy::order(&v, force, &json!({}), &state, "synthetic").unwrap();
    assert_eq!(order.method, "GET");
    assert!(order.path.ends_with("/status"));
    let climate = caps.iter().find(|c| c.key == "start_climate").unwrap();
    let order = crate::legacy::order(
        &v,
        climate,
        &json!({"temperature":21,"duration":5,"defrost":true,"heating":3}),
        &state,
        "synthetic",
    )
    .unwrap();
    assert_eq!(order.body["tempCode"], "0EH");
    assert_eq!(order.body["options"]["heating1"], 3);
}
#[tokio::test]
async fn private_navigation_staging_returns_only_a_one_use_token_and_never_hub_coordinates() {
    use moli_runtime::media::PrivateSource;
    let private = crate::private::Private::default();
    let mut s = session();
    s.api_mode = crate::auth::ApiMode::Europe;
    let v = model::Vehicle {
        key: "v_synthetic".into(),
        id: Some("synthetic-car".into()),
        name: "synthetic".into(),
        model: "Ioniq".into(),
        ccs2: 0,
    };
    private.update(&json!({}), &s, &v, false, true);
    assert!(
        private
            .prepare(
                "navigation",
                json!({"latitude":1.0,"longitude":2.0,"name":"synthetic","consent":false})
            )
            .await
            .is_err()
    );
    let staged = private
        .prepare(
            "navigation",
            json!({"latitude":1.0,"longitude":2.0,"name":"synthetic","consent":true}),
        )
        .await
        .unwrap();
    assert!(staged.get("latitude").is_none() && staged.get("poiInfoList").is_none());
    let token = staged["navigation_token"].as_str().unwrap();
    let body = private.take_navigation(token, "synthetic-device").unwrap();
    assert_eq!(body["poiInfoList"][0]["coord"]["lat"], 1.0);
    assert_eq!(body["deviceID"], "synthetic-device");
    assert!(private.take_navigation(token, "synthetic-device").is_err());
    assert!(!format!("{private:?}").contains("synthetic-car"));
}

#[tokio::test]
async fn browser_authorization_to_legacy_fleet_is_end_to_end_without_credentials_or_wakeup() {
    let wire = Fake::new(vec![
        response(
            200,
            json!({"retCode":"S","resMsg":{"deviceId":"synthetic-device"}}),
        ),
        response(200, session().cci),
        response(
            200,
            json!({"accessToken":"synthetic-opaque-ccs","expiresTime":3600}),
        ),
        response(
            200,
            json!({"retCode":"S","resMsg":{"vehicles":[{"vehicleId":"synthetic-car","nickname":"Ioniq test","vehicleName":"Ioniq 5","ccuCCS2ProtocolSupport":0}]}}),
        ),
    ]);
    let mut client = Client::with_wire(wire.clone());
    let ticket = crate::auth::PendingLogin::begin_with(
        &mut client,
        crate::auth::ApiMode::Europe,
        Some("0000".into()),
    )
    .await
    .unwrap();
    let url = url::Url::parse(ticket.public()["authorization_url"].as_str().unwrap()).unwrap();
    let state = url
        .query_pairs()
        .find(|(k, _)| k == "state")
        .unwrap()
        .1
        .into_owned();
    let session = ticket
        .finish_with(
            &mut client,
            &format!("https://oneapp.hyundai.com/redirect?code=synthetic-code&state={state}"),
        )
        .await
        .unwrap();
    assert_eq!(session.api_mode, crate::auth::ApiMode::Europe);
    let account = Account::discover_with(&mut client, session).await.unwrap();
    assert_eq!(account.vehicles.len(), 1);
    assert_eq!(account.public()["api_mode"], "europe");
    assert!(
        !account
            .public()
            .to_string()
            .contains("synthetic-opaque-ccs")
    );
    let requests = wire.requests.lock().unwrap();
    assert_eq!(requests.len(), 4);
    assert!(
        requests
            .iter()
            .all(|(url, _, body)| !url.contains("/control/")
                && !url.ends_with("/status")
                && !body.contains("password"))
    );
}
