//! MSS310 protocol adapter, reused from code39; schedules, state and commands belong to Moli.
use super::{Answer, CompiledWrite, Session, Target};
use serde_json::{Value, json};

const ALL: &str = "Appliance.System.All";
const TOGGLE: &str = "Appliance.Control.ToggleX";

#[derive(Debug, thiserror::Error)]
#[error("Meross key rejected")]
pub(super) struct KeyRejected;

async fn rpc(
    target: &Target,
    key: &str,
    method: &str,
    namespace: &str,
    payload: &Value,
) -> anyhow::Result<Value> {
    let request = super::meross_message_method(method, namespace, payload, key);
    let bytes = super::exchange(
        target,
        "/config",
        &[],
        "Meross local exchange",
        Some(request.to_string().into_bytes()),
        super::MAX_BODY,
    )
    .await?;
    let response: Value =
        serde_json::from_slice(&bytes).map_err(|_| anyhow::anyhow!("Meross response invalid"))?;
    validate_reply(
        &request,
        &response,
        key,
        target.vars.get("expected_uuid").map(String::as_str),
    )?;
    Ok(response)
}

fn validate_reply(
    request: &Value,
    response: &Value,
    key: &str,
    uuid: Option<&str>,
) -> anyhow::Result<()> {
    let h = &response["header"];
    if h["method"] == "ERROR"
        && h["namespace"] == request["header"]["namespace"]
        && response["payload"]["error"]["code"] == 5001
    {
        return Err(KeyRejected.into());
    }
    let timestamp = h["timestamp"]
        .as_u64()
        .ok_or_else(|| anyhow::anyhow!("Meross timestamp missing"))?;
    let from = h["from"]
        .as_str()
        .and_then(|f| f.strip_prefix("/appliance/"))
        .and_then(|f| f.strip_suffix("/publish"));
    anyhow::ensure!(
        h["messageId"] == request["header"]["messageId"]
            && h["namespace"] == request["header"]["namespace"]
            && h["method"]
                == format!(
                    "{}ACK",
                    request["header"]["method"].as_str().unwrap_or_default()
                )
            && h["sign"]
                .as_str()
                .is_some_and(|sign| sign.eq_ignore_ascii_case(&super::meross_signature(
                    h["messageId"].as_str().unwrap_or_default(),
                    key,
                    timestamp
                )))
            && from.is_some_and(|id| super::meross_cloud::uuid(id)
                && uuid.is_none_or(|expected| id.eq_ignore_ascii_case(expected)))
            && response["payload"].is_object()
            && response["payload"].get("error").is_none(),
        "Meross response identity/signature/ACK rejected"
    );
    if request["header"]["namespace"] == ALL {
        anyhow::ensure!(
            response["payload"]["all"]["system"]["hardware"]["uuid"]
                .as_str()
                .is_some_and(|id| from.is_some_and(|sender| sender.eq_ignore_ascii_case(id))),
            "MSS310 identity differs from sender"
        );
    }
    Ok(())
}

pub(super) async fn fetch(
    target: &Target,
    request: &super::profile::CompiledRequest,
    secret: Option<&str>,
) -> anyhow::Result<Answer> {
    let super::Call::Meross { namespace, payload } = &request.call else {
        anyhow::bail!("MSS310 signed transport required")
    };
    let mut response = rpc(
        target,
        secret.ok_or_else(|| anyhow::anyhow!("Meross key missing"))?,
        "GET",
        namespace,
        payload,
    )
    .await?;
    normalise(&request.id, &mut response)?;
    Ok(Answer::Json(response))
}

impl Session<'_> {
    pub(super) async fn meross_command(
        &mut self,
        ctx: &moli_runtime::DriverCtx,
        device: &moli_core::DeviceId,
        command: moli_runtime::CommandRequest,
        write: &CompiledWrite,
    ) {
        let mut sent = false;
        let mut acknowledged = false;
        let result =
            tokio::time::timeout_at(tokio::time::Instant::from_std(command.deadline), async {
                let moli_core::Value::Bool(wanted) = command.value else {
                    anyhow::bail!("Meross boolean required")
                };
                let key = self
                    .secret
                    .ok_or_else(|| anyhow::anyhow!("Meross key missing"))?;
                let all = rpc(self.target, key, "GET", ALL, &json!({})).await?;
                let mut normalised = all;
                normalise("all", &mut normalised)?;
                let uuid = normalised["payload"]["all"]["system"]["hardware"]["uuid"]
                    .as_str()
                    .ok_or_else(|| anyhow::anyhow!("MSS310 identity missing"))?;
                anyhow::ensure!(
                    device == &ctx.device_id(uuid) && !command.abandoned(),
                    "Meross target/deadline rejected"
                );
                let actual = normalised["payload"]["all"]["digest"]["togglex"][0]["onoff"]
                    .as_u64()
                    .filter(|n| *n <= 1)
                    .ok_or_else(|| anyhow::anyhow!("MSS310 channel-0 state missing"))?;
                if (actual == 1) == wanted {
                    return Ok(normalised);
                }
                sent = true;
                rpc(
                    self.target,
                    key,
                    "SET",
                    TOGGLE,
                    &json!({"togglex":{"channel":0,"onoff":u8::from(wanted)}}),
                )
                .await?;
                acknowledged = true;
                anyhow::ensure!(!command.abandoned(), "Meross deadline elapsed");
                let mut confirmed = rpc(self.target, key, "GET", ALL, &json!({})).await?;
                normalise("all", &mut confirmed)?;
                anyhow::ensure!(
                    confirmed["payload"]["all"]["digest"]["togglex"][0]["onoff"]
                        == u8::from(wanted),
                    "Meross readback differs"
                );
                Ok::<_, anyhow::Error>(confirmed)
            })
            .await;
        let outcome = if let Ok(Ok(all)) = result {
            let index = write.refresh.expect("validated Meross refresh");
            let request = &self.profile.requests[index];
            let answer = Answer::Json(all);
            super::publish(ctx, self.profile, device, &request.id, &answer);
            self.answers.insert(request.id.clone(), answer);
            self.failures[index] = 0;
            self.due[index] = tokio::time::Instant::now() + request.every;
            ctx.set_availability(device, true);
            self.offline = false;
            Ok(())
        } else {
            // A timeout after SET is uncertain. Never enqueue or retry it.
            ctx.set_state(device, &write.key, moli_core::Value::Null);
            self.due[write.refresh.expect("validated Meross refresh")] =
                tokio::time::Instant::now();
            Err(if acknowledged {
                moli_i18n::tr!("pilotes.profile.meross_unconfirmed")
            } else if sent {
                moli_i18n::tr!("pilotes.profile.meross_unknown")
            } else {
                moli_i18n::tr!("pilotes.profile.meross_refused")
            })
        };
        command.reply(outcome);
    }
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
            hardware["uuid"] = json!(hardware["uuid"].as_str().unwrap().to_ascii_lowercase());
            null_missing(hardware, &["version"]);
            null_missing(&mut p["all"]["system"]["firmware"], &["version"]);
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
    use crate::Compiled;
    fn compiled() -> Compiled {
        super::super::Profile::parse(super::super::builtin("meross-mss310").unwrap())
            .unwrap()
            .compile(super::super::Source::Builtin)
            .unwrap()
    }
    #[test]
    fn fixtures_use_mss_units_and_unsorted_daily_counters_without_writes() {
        let profile = compiled();
        assert_eq!(profile.writes.len(), 1);
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
#[path = "mss310_tests.rs"]
mod command_tests;
