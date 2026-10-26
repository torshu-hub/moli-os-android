//! Small MSS310 adapter over the shared signed GET transport and point engine.
//! Protocol knowledge: krahabb/meross_lan devices/mss.py; no upstream code copied.
use super::{Answer, Compiled, Target, profile};
use serde_json::{Value, json};

pub(super) async fn fetch(
    target: &Target,
    request: &profile::CompiledRequest,
    secret: Option<&str>,
    compiled: &Compiled,
) -> anyhow::Result<Answer> {
    if compiled.writes.iter().any(|w| {
        w.meross
            && w.refresh
                .is_some_and(|i| compiled.requests[i].id == request.id)
    }) {
        return super::meross_set::read(target, secret, compiled)
            .await
            .map(Answer::Json)
            .map_err(anyhow::Error::msg);
    }
    if compiled.meta.id == "meross-mss310"
        && compiled.source == super::Source::Builtin
        && request.id != "all"
        && let Some(expected) = target.vars.get("expected_uuid")
    {
        let identity = compiled
            .requests
            .iter()
            .find(|r| r.id == "all")
            .ok_or_else(|| anyhow::anyhow!("MSS310 identity request missing"))?;
        let Answer::Json(mut body) = super::fetch(target, identity, secret).await? else {
            anyhow::bail!("MSS310 identity rejected");
        };
        anyhow::ensure!(
            body["header"]["namespace"] == "Appliance.System.All",
            "MSS310 identity namespace rejected"
        );
        normalise("all", &mut body)?;
        anyhow::ensure!(
            body["payload"]["all"]["system"]["hardware"]["uuid"]
                .as_str()
                .is_some_and(|id| id.eq_ignore_ascii_case(expected)),
            "MSS310 expected identity rejected"
        );
    }
    let mut answer = super::fetch(target, request, secret).await?;
    if compiled.meta.id == "meross-mss310" && compiled.source == super::Source::Builtin {
        let Answer::Json(body) = &mut answer else {
            anyhow::bail!("MSS310 protocol rejected")
        };
        if let super::Call::Meross { namespace, .. } = &request.call {
            anyhow::ensure!(
                body["header"]["namespace"] == *namespace,
                "MSS310 namespace rejected"
            );
        }
        normalise(&request.id, body)?;
        if request.id == "all"
            && let Some(expected) = target.vars.get("expected_uuid")
        {
            anyhow::ensure!(
                body["payload"]["all"]["system"]["hardware"]["uuid"]
                    .as_str()
                    .is_some_and(|id| id.eq_ignore_ascii_case(expected)),
                "MSS310 expected identity rejected"
            );
        }
    }
    Ok(answer)
}

fn null_missing(object: &mut Value, keys: &[&str]) {
    if !object.is_object() {
        *object = json!({});
    }
    for key in keys {
        if object.get(*key).is_none() {
            object[*key] = Value::Null;
        }
    }
}

pub(super) fn normalise(request: &str, body: &mut Value) -> anyhow::Result<()> {
    let p = body
        .get_mut("payload")
        .filter(|p| p.is_object())
        .ok_or_else(|| anyhow::anyhow!("MSS310 payload rejected"))?;
    match request {
        "all" => {
            let hardware = p
                .get_mut("all")
                .and_then(|a| a.get_mut("system"))
                .and_then(|s| s.get_mut("hardware"))
                .filter(|h| h.is_object())
                .ok_or_else(|| anyhow::anyhow!("MSS310 identity rejected"))?;
            anyhow::ensure!(
                hardware["type"]
                    .as_str()
                    .is_some_and(|s| s.eq_ignore_ascii_case("mss310")),
                "MSS310 model rejected"
            );
            anyhow::ensure!(
                hardware["uuid"]
                    .as_str()
                    .is_some_and(|s| s.len() == 32 && s.bytes().all(|b| b.is_ascii_hexdigit())),
                "MSS310 identity rejected"
            );
            null_missing(hardware, &["version"]);
            let toggle = p["all"]["digest"]["togglex"]
                .as_array()
                .and_then(|a| a.iter().find(|v| v["channel"] == 0 || v["channel"] == "0"))
                .cloned()
                .unwrap_or(json!({}));
            p["all"]["digest"] = json!({"togglex":[{"channel":0,"onoff":toggle.get("onoff").cloned().unwrap_or(Value::Null)}]});
        }
        "electricity" => {
            null_missing(&mut p["electricity"], &["power", "current", "voltage"]);
            for key in ["power", "current", "voltage"] {
                if !p["electricity"][key].as_f64().is_some_and(|n| n >= 0.0) {
                    p["electricity"][key] = Value::Null;
                }
            }
        }
        "consumption" => {
            // Devices return an unsorted month of daily Wh counters. Never sum
            // it or call the last entry "today" (timezone/reset quirks differ).
            let latest = p["consumptionx"].as_array().and_then(|days| {
                days.iter()
                    .filter(|d| {
                        d["time"].as_i64().is_some_and(|t| t > 0)
                            && d["value"].as_f64().is_some_and(|n| n >= 0.0)
                            && d["date"].as_str().is_some_and(valid_date)
                    })
                    .max_by_key(|d| d["time"].as_i64())
            });
            p["moli_latest"] = latest.cloned().unwrap_or(json!({"date":null,"value":null}));
        }
        "runtime" => {
            null_missing(&mut p["runtime"], &["signal"]);
            if !p["runtime"]["signal"]
                .as_f64()
                .is_some_and(|n| (0.0..=100.0).contains(&n))
            {
                p["runtime"]["signal"] = Value::Null;
            }
        }
        _ => anyhow::bail!("MSS310 request rejected"),
    }
    Ok(())
}

fn valid_date(s: &str) -> bool {
    let b = s.as_bytes();
    b.len() == 10
        && b[4] == b'-'
        && b[7] == b'-'
        && b.iter()
            .enumerate()
            .all(|(i, c)| i == 4 || i == 7 || c.is_ascii_digit())
        && s[5..7].parse::<u8>().is_ok_and(|v| (1..=12).contains(&v))
        && s[8..10].parse::<u8>().is_ok_and(|v| (1..=31).contains(&v))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn compiled() -> Compiled {
        super::super::Profile::parse(super::super::builtin("meross-mss310").unwrap())
            .unwrap()
            .compile(super::super::Source::Builtin)
            .unwrap()
    }
    #[test]
    fn fixtures_use_mss_units_and_unsorted_daily_counters_without_writes() {
        let profile = compiled();
        assert!(profile.writes.is_empty());
        for (id, text) in [
            (
                "all",
                include_str!("../../../integrations/meross-mss310/fixtures/all.json"),
            ),
            (
                "electricity",
                include_str!("../../../integrations/meross-mss310/fixtures/electricity.json"),
            ),
            (
                "consumption",
                include_str!("../../../integrations/meross-mss310/fixtures/consumption.json"),
            ),
            (
                "runtime",
                include_str!("../../../integrations/meross-mss310/fixtures/runtime.json"),
            ),
        ] {
            let mut body: Value = serde_json::from_str(text).unwrap();
            normalise(id, &mut body).unwrap();
            let answer = Answer::Json(body);
            for (key, expected) in [
                ("power", 123.456),
                ("current", 0.789),
                ("voltage", 231.4),
                ("energy_recent", 987.0),
                ("signal", 76.0),
            ] {
                if let Some(point) = profile
                    .points
                    .iter()
                    .find(|p| p.request == id && &*p.spec.key == key)
                {
                    assert_eq!(point.read(&answer).unwrap().as_f64(), Some(expected));
                }
            }
        }
    }
    #[test]
    fn malformed_payloads_and_empty_identity_are_rejected_without_echoing_input() {
        for mut body in [
            json!(null),
            json!(true),
            json!({"payload":"private-data"}),
            json!({"payload":{"all":"private-data"}}),
        ] {
            let error = normalise("all", &mut body).unwrap_err().to_string();
            assert!(!error.contains("private-data"));
        }
        let mut body = json!({"payload":{"electricity":{"power":-1,"current":null}}});
        normalise("electricity", &mut body).unwrap();
        assert!(body["payload"]["electricity"]["power"].is_null());
    }
    #[test]
    fn absent_measurements_clear_instead_of_inventing_zero_or_retaining_old_values() {
        let profile = compiled();
        for id in ["electricity", "consumption", "runtime"] {
            let mut body = json!({"payload":{}});
            normalise(id, &mut body).unwrap();
            for p in profile.points.iter().filter(|p| p.request == id) {
                assert_eq!(
                    p.read(&Answer::Json(body.clone())),
                    Some(moli_core::Value::Null)
                );
            }
        }
        let mut wrong = json!({"payload":{"all":{"system":{"hardware":{"type":"em06","uuid":"00000000000000000000000000000001"}}}}});
        assert!(normalise("all", &mut wrong).is_err());
    }
}

#[cfg(test)]
mod binding_tests {
    use super::*;
    use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
    #[tokio::test]
    async fn explicit_invalid_key_is_distinct_from_protocol_and_unreachable() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let fixture = tokio::spawn(async move {
            for code in [5001, 5002] {
                let (mut stream, _) = listener.accept().await.unwrap();
                let mut header = vec![];
                let mut byte = [0];
                while !header.ends_with(b"\r\n\r\n") {
                    stream.read_exact(&mut byte).await.unwrap();
                    header.push(byte[0]);
                    assert!(header.len() < 8192);
                }
                let head = String::from_utf8(header).unwrap();
                let len = head
                    .lines()
                    .find_map(|line| {
                        line.to_ascii_lowercase()
                            .strip_prefix("content-length:")
                            .map(|n| n.trim().parse::<usize>().unwrap())
                    })
                    .unwrap();
                let mut body = vec![0; len];
                stream.read_exact(&mut body).await.unwrap();
                let request: serde_json::Value = serde_json::from_slice(&body).unwrap();
                assert_eq!(request["header"]["method"], "GET");
                assert_eq!(request["header"]["namespace"], "Appliance.System.All");
                let response=json!({"header":{"method":"ERROR","namespace":"Appliance.System.All"},"payload":{"error":{"code":code,"detail":"never-copy-private-data"}}}).to_string();
                stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{response}",response.len()).as_bytes()).await.unwrap();
            }
        });
        for expected in ["account_key", "account_protocol"] {
            let error = crate::mss310_identity("127.0.0.1", port, &"a".repeat(32))
                .await
                .unwrap_err();
            assert_eq!(crate::mss310_identity_error(&error), expected);
            assert!(!error.to_string().contains("never-copy-private-data"));
        }
        fixture.await.unwrap();
        let error = crate::mss310_identity("127.0.0.1", port, &"a".repeat(32))
            .await
            .unwrap_err();
        assert_eq!(crate::mss310_identity_error(&error), "account_local");
    }
    #[tokio::test]
    async fn meross_bound_measurement_refuses_another_uuid_before_reading_electricity() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let fixture = tokio::spawn(async move {
            for value in [
                "00000000000000000000000000000001",
                "00000000000000000000000000000002",
            ] {
                let (mut stream, _) = listener.accept().await.unwrap();
                let mut headers = vec![];
                let mut byte = [0];
                while !headers.ends_with(b"\r\n\r\n") {
                    stream.read_exact(&mut byte).await.unwrap();
                    headers.push(byte[0]);
                    assert!(headers.len() < 8192);
                }
                let head = String::from_utf8(headers).unwrap();
                let len = head
                    .lines()
                    .find_map(|s| {
                        s.to_ascii_lowercase()
                            .strip_prefix("content-length:")
                            .map(|s| s.trim().parse::<usize>().unwrap())
                    })
                    .unwrap();
                let mut body = vec![0; len];
                stream.read_exact(&mut body).await.unwrap();
                let request: serde_json::Value = serde_json::from_slice(&body).unwrap();
                assert_eq!(request["header"]["namespace"], "Appliance.System.All");
                assert_eq!(request["header"]["method"], "GET");
                let mut response: serde_json::Value = serde_json::from_str(include_str!(
                    "../../../integrations/meross-mss310/fixtures/all.json"
                ))
                .unwrap();
                response["payload"]["all"]["system"]["hardware"]["uuid"] = serde_json::json!(value);
                let body = response.to_string();
                stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).as_bytes()).await.unwrap();
            }
        });
        assert_eq!(
            crate::mss310_identity("127.0.0.1", port, &"a".repeat(32))
                .await
                .unwrap(),
            "00000000000000000000000000000001"
        );
        let (compiled, _) = crate::open("meross-mss310").unwrap();
        let config:crate::Config=serde_json::from_value(serde_json::json!({"profile":"meross-mss310","host":"127.0.0.1","port":port,"discovery":false,"vars":{"expected_uuid":"00000000000000000000000000000001"}})).unwrap();
        let target = crate::Target::of(&config, &compiled).unwrap();
        let request = compiled
            .requests
            .iter()
            .find(|r| r.id == "electricity")
            .unwrap();
        let error = fetch(&target, request, Some(&"a".repeat(32)), &compiled)
            .await
            .unwrap_err()
            .to_string();
        assert!(error.contains("expected identity rejected"));
        fixture.await.unwrap(); // Only two identity GETs, no electricity request to the other plug.
    }
}
