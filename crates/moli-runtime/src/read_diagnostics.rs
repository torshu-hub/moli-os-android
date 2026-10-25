//! Fixed-schema read diagnostics. No identifiers, values, URLs or provider text.
//! Kept in the hub even before a driver has published its first device.
use serde::Serialize;
use std::collections::BTreeMap;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReadStage {
    Authorization,
    Renewal,
    Homesdata,
    Homestatus,
    Parsing,
    Publication,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReadOutcome {
    #[default]
    Unobserved,
    Pending,
    Ok,
    Transport,
    Authorization,
    Credentials,
    Ambiguous,
    RateLimit,
    Storage,
    InvalidResponse,
    Provider,
}
#[derive(Clone, Debug, Default, Serialize)]
pub struct ReadStep {
    pub status: ReadOutcome,
    pub attempts: u64,
    pub successes: u64,
    pub failures: u64,
    pub last_attempt_at: u64,
    pub last_success_at: u64,
    pub last_error_at: u64,
}
#[derive(Clone, Copy, Debug, Default, Serialize)]
pub struct ReadSummary {
    pub devices: u64,
    pub fresh: u64,
    pub temperatures: u64,
    pub setpoints: u64,
    pub modes: u64,
    pub demand_present: u64,
    pub demand_unknown: u64,
}
#[derive(Clone, Debug, Serialize)]
// Independent observations (enabled, vault, target, process, expiry), not
// mutually exclusive states. Keep their explicit boolean API representation.
#[allow(clippy::struct_excessive_bools)]
pub struct ReadDiagnostics {
    pub kind: &'static str,
    pub configured: bool,
    pub enabled: bool,
    pub credential_present: bool,
    pub target_selected: bool,
    pub driver_started: bool,
    pub started_at: u64,
    pub token_expired: bool,
    pub last_attempt_at: u64,
    pub last_response_at: u64,
    pub last_publish_at: u64,
    pub stages: BTreeMap<ReadStage, ReadStep>,
    pub summary: ReadSummary,
}
#[derive(Clone, Copy, Debug)]
pub enum ReadEvent {
    Configuration {
        enabled: bool,
        credential_present: bool,
        target_selected: bool,
    },
    Started,
    TokenFresh,
    Attempt {
        credential_present: bool,
        token_expired: bool,
    },
    Stage(ReadStage, ReadOutcome),
    Published(ReadSummary),
}
impl ReadDiagnostics {
    pub(crate) fn new(kind: &'static str) -> Self {
        Self {
            kind,
            configured: true,
            enabled: false,
            credential_present: false,
            target_selected: false,
            driver_started: false,
            started_at: 0,
            token_expired: false,
            last_attempt_at: 0,
            last_response_at: 0,
            last_publish_at: 0,
            stages: BTreeMap::new(),
            summary: ReadSummary::default(),
        }
    }
    pub(crate) fn observe(&mut self, event: ReadEvent, now: u64) {
        match event {
            ReadEvent::Configuration {
                enabled,
                credential_present,
                target_selected,
            } => {
                self.enabled = enabled;
                self.credential_present = credential_present;
                self.target_selected = target_selected;
            }
            ReadEvent::Started => {
                self.driver_started = true;
                self.started_at = now;
            }
            ReadEvent::TokenFresh => self.token_expired = false,
            ReadEvent::Attempt {
                credential_present,
                token_expired,
            } => {
                self.credential_present = credential_present;
                self.token_expired = token_expired;
                self.last_attempt_at = now;
            }
            ReadEvent::Stage(stage, outcome) => {
                let step = self.stages.entry(stage).or_default();
                step.status = outcome;
                match outcome {
                    ReadOutcome::Pending => {
                        step.attempts = step.attempts.saturating_add(1);
                        step.last_attempt_at = now;
                    }
                    ReadOutcome::Ok => {
                        step.successes = step.successes.saturating_add(1);
                        step.last_success_at = now;
                    }
                    ReadOutcome::Unobserved => (),
                    _ => {
                        step.failures = step.failures.saturating_add(1);
                        step.last_error_at = now;
                    }
                }
                if outcome == ReadOutcome::Ok && stage == ReadStage::Homestatus {
                    self.last_response_at = now;
                }
            }
            ReadEvent::Published(summary) => {
                self.summary = summary;
                self.last_publish_at = now;
                self.observe(
                    ReadEvent::Stage(ReadStage::Publication, ReadOutcome::Ok),
                    now,
                );
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn configured_disabled_or_failed_reads_remain_visible_with_zero_devices() {
        let hub = crate::Hub::new(crate::HubOptions::default()).unwrap();
        let instance = moli_core::InstanceId::from("fixture-read");
        hub.observe_read(
            &instance,
            "netatmo",
            ReadEvent::Configuration {
                enabled: false,
                credential_present: true,
                target_selected: true,
            },
        );
        let snapshot = hub.snapshot();
        assert!(snapshot.devices.is_empty());
        assert!(!snapshot.diagnostics[0].read.enabled);
        assert!(snapshot.diagnostics[0].read.credential_present);
        hub.observe_read(
            &instance,
            "netatmo",
            ReadEvent::Stage(ReadStage::Homesdata, ReadOutcome::Pending),
        );
        hub.observe_read(
            &instance,
            "netatmo",
            ReadEvent::Stage(ReadStage::Homesdata, ReadOutcome::Transport),
        );
        let snapshot = hub.snapshot();
        let step = &snapshot.diagnostics[0].read.stages[&ReadStage::Homesdata];
        assert_eq!(step.failures, 1);
        assert_eq!(step.successes, 0);
        assert!(snapshot.devices.is_empty());
    }
}
