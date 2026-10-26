//! Declarative ToggleX simulation over the existing profile transport.
//! No LAN target is permitted by this increment. Protocol ideas only from
//! krahabb/meross_lan; no upstream implementation copied.
use super::{
    Answer, Compiled, CompiledWrite, Session, Target, meross_message_method, meross_signature,
};
use moli_core::{DeviceId, Value};
use moli_runtime::{CommandRequest, DriverCtx};
use serde_json::{Value as Json, json};

const ALL: &str = "Appliance.System.All";
const ABILITY: &str = "Appliance.System.Ability";
const TOGGLE: &str = "Appliance.Control.ToggleX";

pub(super) fn loopback(host: &str) -> bool {
    host.parse::<std::net::IpAddr>()
        .is_ok_and(|ip| ip.is_loopback())
}
fn expected<'a>(target: &'a Target, secret: Option<&'a str>) -> Result<(&'a str, &'a str), String> {
    let uuid = target
        .vars
        .get("expected_uuid")
        .filter(|s| s.len() == 32 && s.bytes().all(|b| b.is_ascii_hexdigit()));
    let key = secret
        .filter(|s| !s.is_empty() && s.len() <= 4096 && s.bytes().all(|b| b.is_ascii_graphic()));
    if !loopback(&target.host) || target.tls {
        return Err("Meross SET is restricted to loopback simulation".into());
    }
    uuid.zip(key)
        .map(|(u, k)| (u.as_str(), k))
        .ok_or_else(|| "Meross simulation identity/key required".into())
}

async fn rpc(
    target: &Target,
    key: &str,
    uuid: &str,
    method: &str,
    namespace: &str,
    payload: &Json,
) -> Result<Json, String> {
    let request = meross_message_method(method, namespace, payload, key);
    let body = super::exchange(
        target,
        "/config",
        &[],
        "Meross simulation",
        Some(request.to_string().into_bytes()),
    )
    .await
    .map_err(|_| "Meross simulated exchange unavailable".to_owned())?;
    let response: Json =
        serde_json::from_slice(&body).map_err(|_| "Meross simulated response invalid")?;
    validate_reply(&request, &response, key, uuid)?;
    Ok(response)
}
fn validate_reply(request: &Json, response: &Json, key: &str, uuid: &str) -> Result<(), String> {
    let h = &response["header"];
    let timestamp = h["timestamp"]
        .as_u64()
        .ok_or("Meross response timestamp invalid")?;
    let now = moli_core::now_ms() / 1000;
    let valid = h["messageId"] == request["header"]["messageId"]
        && h["namespace"] == request["header"]["namespace"]
        && h["method"]
            == format!(
                "{}ACK",
                request["header"]["method"].as_str().unwrap_or("invalid")
            )
        && h["payloadVersion"] == 1
        && h["from"] == format!("/appliance/{uuid}/publish")
        && now.abs_diff(timestamp) <= 30
        && h["sign"]
            == meross_signature(h["messageId"].as_str().unwrap_or_default(), key, timestamp)
        && response["payload"].is_object()
        && response["payload"].get("error").is_none();
    if !valid {
        return Err("Meross response binding/signature/ACK rejected".into());
    }
    if request["header"]["method"] == "SET"
        && response["payload"]
            .as_object()
            .is_none_or(|p| !p.is_empty())
    {
        return Err("Meross SETACK schema rejected".into());
    }
    Ok(())
}
fn onoff(all: &Json, uuid: &str) -> Result<bool, String> {
    if all["payload"]["all"]["system"]["hardware"]["uuid"] != uuid {
        return Err("Meross target identity rejected".into());
    }
    let toggles = all["payload"]["all"]["digest"]["togglex"]
        .as_array()
        .ok_or("Meross ToggleX capability absent")?;
    let mut channels = std::collections::BTreeSet::new();
    for toggle in toggles {
        let channel = toggle["channel"].as_u64().ok_or("Meross channel invalid")?;
        if !channels.insert(channel) {
            return Err("Meross duplicate channel".into());
        }
    }
    match toggles
        .iter()
        .find(|t| t["channel"] == 0)
        .and_then(|t| t["onoff"].as_u64())
    {
        Some(0) => Ok(false),
        Some(1) => Ok(true),
        _ => Err("Meross channel-0 state invalid".into()),
    }
}
pub(super) async fn read(
    target: &Target,
    secret: Option<&str>,
    _profile: &Compiled,
) -> Result<Json, String> {
    let (uuid, key) = expected(target, secret)?;
    let all = rpc(target, key, uuid, "GET", ALL, &json!({})).await?;
    onoff(&all, uuid)?;
    Ok(all)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Progress {
    Preflight,
    Sending,
    Acknowledged,
    Confirmed,
    Refused,
    Unknown,
}
fn progress(state: &mut Progress, next: Progress) {
    *state = next;
    // Closed vocabulary, at most a few transitions per explicit command.
    tracing::debug!(stage=?next,"Meross simulated write");
}

impl Session<'_> {
    pub(super) async fn meross_command(
        &mut self,
        ctx: &DriverCtx,
        device: &DeviceId,
        write: &CompiledWrite,
        command: CommandRequest,
    ) {
        let mut state = Progress::Preflight;
        let deadline = tokio::time::Instant::from_std(command.deadline);
        let result = tokio::time::timeout_at(
            deadline,
            self.meross_write(ctx, device, write, &command, &mut state),
        )
        .await;
        let outcome = if let Ok(Ok(answer)) = result {
            let index = write.refresh.expect("validated refresh");
            let request = &self.profile.requests[index];
            super::publish(
                ctx,
                self.profile,
                device,
                &request.id,
                &Answer::Json(answer.clone()),
            );
            self.answers
                .insert(request.id.clone(), Answer::Json(answer));
            self.failures[index] = 0;
            self.due[index] = tokio::time::Instant::now() + request.every;
            ctx.set_availability(device, true);
            self.offline = false;
            progress(&mut state, Progress::Confirmed);
            Ok(())
        } else {
            let reason = match result {
                Ok(Err(reason)) => reason,
                _ => "Meross command deadline elapsed".into(),
            };
            tracing::debug!(stage=?state,reason=%reason,"Meross simulated write failed");
            ctx.set_state(device, &write.key, Value::Null);
            ctx.set_availability(device, false);
            self.offline = true;
            let ambiguous = matches!(state, Progress::Sending | Progress::Acknowledged);
            let message = if state == Progress::Acknowledged {
                moli_i18n::tr!("pilotes.profile.meross_unconfirmed")
            } else if ambiguous {
                moli_i18n::tr!("pilotes.profile.meross_unknown")
            } else {
                moli_i18n::tr!("pilotes.profile.meross_refused")
            };
            progress(
                &mut state,
                if ambiguous {
                    Progress::Unknown
                } else {
                    Progress::Refused
                },
            );
            Err(message)
        };
        command.reply(outcome);
    }
    async fn meross_write(
        &self,
        ctx: &DriverCtx,
        device: &DeviceId,
        _write: &CompiledWrite,
        command: &CommandRequest,
        state: &mut Progress,
    ) -> Result<Json, String> {
        let (uuid, key) = expected(self.target, self.secret)?;
        if device != &ctx.device_id(uuid) || command.abandoned() {
            return Err("Meross target/deadline rejected".into());
        }
        let Value::Bool(wanted) = command.value else {
            return Err("Meross boolean required".into());
        };
        let all = rpc(self.target, key, uuid, "GET", ALL, &json!({})).await?;
        let actual = onoff(&all, uuid)?;
        if command.abandoned() {
            return Err("Meross expired".into());
        }
        let ability = rpc(self.target, key, uuid, "GET", ABILITY, &json!({})).await?;
        if !ability["payload"]["ability"][TOGGLE].is_object() {
            return Err("Meross ToggleX not advertised".into());
        }
        if command.abandoned() {
            return Err("Meross expired".into());
        }
        // A repeated request, including after restart, is resolved by a fresh
        // read. No queued or ambiguous command is ever retried by the driver.
        if actual == wanted {
            return Ok(all);
        }
        ctx.set_state(device, &command.key, Value::Null);
        progress(state, Progress::Sending);
        rpc(
            self.target,
            key,
            uuid,
            "SET",
            TOGGLE,
            &json!({"togglex":[{"channel":0,"onoff":u8::from(wanted)}]}),
        )
        .await?;
        progress(state, Progress::Acknowledged);
        if command.abandoned() {
            return Err("Meross expired".into());
        }
        let confirmed = rpc(self.target, key, uuid, "GET", ALL, &json!({})).await?;
        if onoff(&confirmed, uuid)? != wanted {
            return Err("Meross readback differs".into());
        }
        Ok(confirmed)
    }
}

#[cfg(test)]
mod tests;
