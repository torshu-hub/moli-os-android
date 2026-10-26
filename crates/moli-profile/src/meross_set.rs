//! Declarative ToggleX over the existing profile transport. External files stay
//! loopback-only; the built-in manual variant requires explicit target consent.
//! Protocol ideas only from
//! krahabb/meross_lan; no upstream implementation copied.
use super::{
    Answer, Compiled, CompiledWrite, Session, Target, meross_message_method, meross_signature,
};
use moli_core::{DeviceId, Value};
use moli_runtime::{
    CommandRequest, DriverCtx, ReadEvent, ReadOutcome as Outcome, ReadStage as Stage,
};
use serde_json::{Value as Json, json};

const ALL: &str = "Appliance.System.All";
const ABILITY: &str = "Appliance.System.Ability";
const TOGGLE: &str = "Appliance.Control.ToggleX";

pub(super) fn loopback(host: &str) -> bool {
    host.parse::<std::net::IpAddr>()
        .is_ok_and(|ip| ip.is_loopback())
}
fn expected<'a>(
    target: &'a Target,
    secret: Option<&'a str>,
    profile: &Compiled,
) -> Result<(&'a str, &'a str), String> {
    let uuid = target
        .vars
        .get(if manual(profile) {
            "manual_uuid"
        } else {
            "expected_uuid"
        })
        .filter(|s| s.len() == 32 && s.bytes().all(|b| b.is_ascii_hexdigit()));
    let key = secret
        .filter(|s| !s.is_empty() && s.len() <= 4096 && s.bytes().all(|b| b.is_ascii_graphic()));
    let private = manual(profile)
        && target
            .host
            .parse::<std::net::Ipv4Addr>()
            .is_ok_and(|ip| ip.is_private());
    if (!loopback(&target.host) && !private) || target.tls {
        return Err("Meross SET target is outside its permitted scope".into());
    }
    if manual(profile)
        && target
            .vars
            .get("expected_uuid")
            .is_some_and(|old| uuid.is_none_or(|chosen| !old.eq_ignore_ascii_case(chosen)))
    {
        return Err("Meross selected identity differs from its existing binding".into());
    }
    uuid.zip(key)
        .map(|(u, k)| (u.as_str(), k))
        .ok_or_else(|| "Meross selected identity/key required".into())
}

pub(super) fn manual(profile: &Compiled) -> bool {
    profile.source == super::Source::Builtin
        && profile.meta.id == "meross-mss310"
        && profile.writes.iter().any(|w| w.meross)
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
        "Meross local exchange",
        Some(request.to_string().into_bytes()),
    )
    .await
    .map_err(|_| "Meross exchange unavailable".to_owned())?;
    let response: Json = serde_json::from_slice(&body).map_err(|_| "Meross response invalid")?;
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
        && h["from"]
            .as_str()
            .and_then(|f| {
                f.strip_prefix("/appliance/")
                    .and_then(|f| f.strip_suffix("/publish"))
            })
            .is_some_and(|id| id.eq_ignore_ascii_case(uuid))
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
    if !all["payload"]["all"]["system"]["hardware"]["uuid"]
        .as_str()
        .is_some_and(|id| id.eq_ignore_ascii_case(uuid))
    {
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
fn verified_state(profile: &Compiled, all: &Json, uuid: &str) -> Result<bool, String> {
    if manual(profile)
        && !all["payload"]["all"]["system"]["hardware"]["type"]
            .as_str()
            .is_some_and(|m| m.eq_ignore_ascii_case("mss310"))
    {
        return Err("Meross manual target model rejected".into());
    }
    onoff(all, uuid)
}
pub(super) async fn read(
    target: &Target,
    secret: Option<&str>,
    profile: &Compiled,
) -> Result<Json, String> {
    let (uuid, key) = expected(target, secret, profile)?;
    let all = rpc(target, key, uuid, "GET", ALL, &json!({})).await?;
    verified_state(profile, &all, uuid)?;
    Ok(all)
}

// Fixed vocabulary only: no target identifiers, key or provider text reaches diagnostics.
fn stage(ctx: &DriverCtx, profile: &Compiled, step: Stage, outcome: Outcome) {
    if manual(profile) {
        ctx.observe_read("meross", ReadEvent::Stage(step, outcome));
    }
}
fn failure(reason: &str) -> Outcome {
    match reason {
        "Meross manual mode requires a human surface" => Outcome::Authorization,
        "Meross selected identity/key required" => Outcome::Credentials,
        "Meross exchange unavailable" => Outcome::Transport,
        "Meross command deadline elapsed" => Outcome::Timeout,
        "Meross expired" | "Meross target/deadline rejected" => Outcome::Cancelled,
        _ => Outcome::InvalidResponse,
    }
}
fn finish(
    ctx: &DriverCtx,
    profile: &Compiled,
    step: Stage,
    outcome: Outcome,
    since: std::time::Instant,
) {
    if manual(profile) {
        ctx.observe_read(
            "meross",
            ReadEvent::Latency(
                step,
                u64::try_from(since.elapsed().as_millis()).unwrap_or(u64::MAX),
            ),
        );
        stage(ctx, profile, step, outcome);
    }
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
    tracing::debug!(stage=?next,"Meross declared write");
}

fn finish_failure(
    ctx: &DriverCtx,
    profile: &Compiled,
    state: Progress,
    outcome: Outcome,
    since: std::time::Instant,
) {
    match state {
        Progress::Preflight => {
            finish(ctx, profile, Stage::CommandPreflight, outcome, since);
        }
        Progress::Sending => {
            finish(ctx, profile, Stage::CommandSend, outcome, since);
            finish(ctx, profile, Stage::CommandAck, outcome, since);
        }
        Progress::Acknowledged => {
            finish(ctx, profile, Stage::CommandReadback, outcome, since);
        }
        _ => (),
    }
}

impl Session<'_> {
    pub(super) async fn meross_first_round(&mut self, ctx: &DriverCtx) -> anyhow::Result<()> {
        let since = std::time::Instant::now();
        stage(ctx, self.profile, Stage::Parsing, Outcome::Pending);
        let result = self.first_round().await;
        let outcome = result
            .as_ref()
            .map_or_else(|e| failure(&e.to_string()), |()| Outcome::Ok);
        finish(ctx, self.profile, Stage::Parsing, outcome, since);
        result
    }
    pub(super) async fn meross_command(
        &mut self,
        ctx: &DriverCtx,
        device: &DeviceId,
        write: &CompiledWrite,
        command: CommandRequest,
    ) {
        let mut state = Progress::Preflight;
        let mut since = std::time::Instant::now();
        if manual(self.profile) {
            ctx.observe_read(
                "meross",
                ReadEvent::Attempt {
                    credential_present: self.secret.is_some(),
                    token_expired: false,
                },
            );
            for step in [
                Stage::CommandSend,
                Stage::CommandAck,
                Stage::CommandReadback,
            ] {
                stage(ctx, self.profile, step, Outcome::Unobserved);
            }
        }
        stage(ctx, self.profile, Stage::CommandPreflight, Outcome::Pending);
        let deadline = tokio::time::Instant::from_std(command.deadline);
        // Leave a small cleanup window for terminal diagnostics before the hub
        // gives up waiting. This never extends its deadline or retries a SET.
        let deadline = if manual(self.profile) {
            deadline
                .checked_sub(std::time::Duration::from_millis(25))
                .unwrap_or(deadline)
        } else {
            deadline
        };
        let result = tokio::time::timeout_at(
            deadline,
            self.meross_write(ctx, device, &command, &mut state, &mut since),
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
            finish(
                ctx,
                self.profile,
                Stage::CommandReadback,
                Outcome::Ok,
                since,
            );
            progress(&mut state, Progress::Confirmed);
            Ok(())
        } else {
            let reason = match result {
                Ok(Err(reason)) => reason,
                _ => "Meross command deadline elapsed".into(),
            };
            let outcome = failure(&reason);
            finish_failure(ctx, self.profile, state, outcome, since);
            tracing::debug!(stage=?state,reason=%reason,"Meross declared write failed");
            if outcome != Outcome::Authorization {
                ctx.set_state(device, &write.key, Value::Null);
                ctx.set_availability(device, false);
                self.offline = true;
            }
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
        command: &CommandRequest,
        state: &mut Progress,
        since: &mut std::time::Instant,
    ) -> Result<Json, String> {
        if manual(self.profile)
            && !matches!(
                command.origin,
                moli_core::Origin::Ui | moli_core::Origin::Household
            )
        {
            return Err("Meross manual mode requires a human surface".into());
        }
        let (uuid, key) = expected(self.target, self.secret, self.profile)?;
        if !device
            .as_str()
            .eq_ignore_ascii_case(ctx.device_id(uuid).as_str())
            || command.abandoned()
        {
            return Err("Meross target/deadline rejected".into());
        }
        let Value::Bool(wanted) = command.value else {
            return Err("Meross boolean required".into());
        };
        let all = rpc(self.target, key, uuid, "GET", ALL, &json!({})).await?;
        let actual = verified_state(self.profile, &all, uuid)?;
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
        finish(
            ctx,
            self.profile,
            Stage::CommandPreflight,
            Outcome::Ok,
            *since,
        );
        *since = std::time::Instant::now();
        if actual == wanted {
            stage(ctx, self.profile, Stage::CommandReadback, Outcome::Pending);
            return Ok(all);
        }
        ctx.set_state(device, &command.key, Value::Null);
        progress(state, Progress::Sending);
        stage(ctx, self.profile, Stage::CommandSend, Outcome::Pending);
        stage(ctx, self.profile, Stage::CommandAck, Outcome::Pending);
        rpc(
            self.target,
            key,
            uuid,
            "SET",
            TOGGLE,
            &json!({"togglex":{"channel":0,"onoff":u8::from(wanted)}}),
        )
        .await?;
        finish(ctx, self.profile, Stage::CommandSend, Outcome::Ok, *since);
        finish(ctx, self.profile, Stage::CommandAck, Outcome::Ok, *since);
        *since = std::time::Instant::now();
        progress(state, Progress::Acknowledged);
        stage(ctx, self.profile, Stage::CommandReadback, Outcome::Pending);
        if command.abandoned() {
            return Err("Meross expired".into());
        }
        let confirmed = rpc(self.target, key, uuid, "GET", ALL, &json!({})).await?;
        if verified_state(self.profile, &confirmed, uuid)? != wanted {
            return Err("Meross readback differs".into());
        }
        Ok(confirmed)
    }
}

#[cfg(test)]
mod tests;
