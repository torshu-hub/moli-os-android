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

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReadOutcome {
    #[default]
    Unobserved,
    Pending,
    Ok,
    Transport,
    Timeout,
    Dns,
    Connect,
    Tls,
    Headers,
    Body,
    CycleTimeout,
    Cancelled,
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
    pub last_error: ReadOutcome,
    pub interruptions: u64,
    pub last_duration_ms: u64,
    pub errors: BTreeMap<ReadOutcome, u64>,
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
#[derive(Clone, Copy, Debug, Default, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReadTrigger {
    #[default]
    Initial,
    Deadline,
    WallDeadline,
}
#[derive(Clone, Debug, Default, Serialize)]
pub struct ReadPolling {
    pub period_seconds: u64,
    pub delay_seconds: u64,
    pub next_due_at: u64,
    pub cycles: u64,
    pub automatic_cycles: u64,
    pub successes: u64,
    pub failures: u64,
    pub interruptions: u64,
    pub last_cycle_at: u64,
    pub last_automatic_at: u64,
    pub last_gap_ms: u64,
    pub late_ms: u64,
    pub trigger: ReadTrigger,
    pub reason: ReadOutcome,
    pub in_flight: bool,
    pub last_finished_at: u64,
    pub last_duration_ms: u64,
    pub last_error: ReadOutcome,
    pub last_error_at: u64,
    pub cycle_due_at: u64,
}
#[derive(Clone, Debug, Serialize)]
// Independent observations (enabled, vault, target, process, expiry), not
// mutually exclusive states. Keep their explicit boolean API representation.
#[allow(clippy::struct_excessive_bools)]
pub struct ReadDiagnostics {
    pub kind: &'static str,
    /// Stable for this hub's counters, including supervised driver restarts.
    pub counter_epoch: u64,
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
    pub polling: ReadPolling,
}
#[derive(Clone, Copy, Debug)]
pub enum ReadEvent {
    Cycle {
        period_seconds: u64,
        trigger: ReadTrigger,
        late_ms: u64,
    },
    Scheduled {
        delay_seconds: u64,
        next_due_at: u64,
        reason: ReadOutcome,
        duration_ms: u64,
    },
    Configuration {
        enabled: bool,
        credential_present: bool,
        target_selected: bool,
    },
    Started,
    Stopped,
    TokenFresh,
    Attempt {
        credential_present: bool,
        token_expired: bool,
    },
    Stage(ReadStage, ReadOutcome),
    Latency(ReadStage, u64),
    Published(ReadSummary),
}
impl ReadDiagnostics {
    pub(crate) fn new(kind: &'static str) -> Self {
        Self {
            kind,
            counter_epoch: moli_core::now_ms(),
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
            polling: ReadPolling::default(),
        }
    }
    pub(crate) fn observe(&mut self, event: ReadEvent, now: u64) {
        match event {
            ReadEvent::Cycle {
                period_seconds,
                trigger,
                late_ms,
            } => {
                let p = &mut self.polling;
                p.period_seconds = period_seconds;
                p.trigger = trigger;
                p.late_ms = late_ms;
                p.in_flight = true;
                p.last_duration_ms = 0;
                p.cycle_due_at = p.next_due_at;
                p.next_due_at = 0;
                p.last_gap_ms = if p.last_cycle_at == 0 {
                    0
                } else {
                    now.saturating_sub(p.last_cycle_at)
                };
                p.last_cycle_at = now;
                p.cycles = p.cycles.saturating_add(1);
                if !matches!(trigger, ReadTrigger::Initial) {
                    p.last_automatic_at = now;
                    p.automatic_cycles = p.automatic_cycles.saturating_add(1);
                }
            }
            ReadEvent::Scheduled {
                delay_seconds,
                next_due_at,
                reason,
                duration_ms,
            } => {
                let p = &mut self.polling;
                p.delay_seconds = delay_seconds;
                p.next_due_at = next_due_at;
                p.reason = reason;
                p.in_flight = false;
                p.last_finished_at = now;
                p.last_duration_ms = duration_ms;
                if reason == ReadOutcome::Ok {
                    p.successes = p.successes.saturating_add(1);
                } else {
                    p.failures = p.failures.saturating_add(1);
                    p.last_error = reason;
                    p.last_error_at = now;
                }
                self.finish_pending(reason, now);
            }
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
            ReadEvent::Stopped => {
                self.driver_started = false;
                self.finish_pending(ReadOutcome::Cancelled, now);
                let p = &mut self.polling;
                if p.in_flight {
                    p.interruptions = p.interruptions.saturating_add(1);
                    p.last_finished_at = now;
                    p.reason = ReadOutcome::Cancelled;
                }
                p.in_flight = false;
                p.next_due_at = 0;
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
            ReadEvent::Stage(stage, outcome) => self.stage(stage, outcome, now),
            ReadEvent::Latency(stage, duration) => {
                self.stages.entry(stage).or_default().last_duration_ms = duration;
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
    fn stage(&mut self, stage: ReadStage, outcome: ReadOutcome, now: u64) {
        let step = self.stages.entry(stage).or_default();
        step.status = outcome;
        match outcome {
            ReadOutcome::Pending => {
                step.last_duration_ms = 0;
                step.attempts = step.attempts.saturating_add(1);
                step.last_attempt_at = now;
            }
            ReadOutcome::Ok => {
                step.successes = step.successes.saturating_add(1);
                step.last_success_at = now;
            }
            ReadOutcome::Unobserved => (),
            ReadOutcome::Cancelled => {
                step.interruptions = step.interruptions.saturating_add(1);
            }
            _ => {
                step.failures = step.failures.saturating_add(1);
                step.last_error_at = now;
                step.last_error = outcome;
                let count = step.errors.entry(outcome).or_default();
                *count = count.saturating_add(1);
            }
        }
        if outcome == ReadOutcome::Ok && stage == ReadStage::Homestatus {
            self.last_response_at = now;
        }
    }
    fn finish_pending(&mut self, outcome: ReadOutcome, now: u64) {
        let pending: Vec<_> = self
            .stages
            .iter()
            .filter_map(|(stage, step)| (step.status == ReadOutcome::Pending).then_some(*stage))
            .collect();
        for stage in pending {
            self.observe(ReadEvent::Stage(stage, outcome), now);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cycle_timeout_and_stop_close_pending_steps_without_erasing_error_history_or_counter_epoch() {
        let mut d = ReadDiagnostics::new("netatmo");
        let epoch = d.counter_epoch;
        d.observe(ReadEvent::Started, 1000);
        d.observe(
            ReadEvent::Cycle {
                period_seconds: 120,
                trigger: ReadTrigger::Initial,
                late_ms: 0,
            },
            1000,
        );
        d.observe(
            ReadEvent::Stage(ReadStage::Homesdata, ReadOutcome::Pending),
            1000,
        );
        d.observe(
            ReadEvent::Scheduled {
                delay_seconds: 30,
                next_due_at: 91000,
                reason: ReadOutcome::CycleTimeout,
                duration_ms: 60000,
            },
            61000,
        );
        assert_eq!(
            d.stages[&ReadStage::Homesdata].status,
            ReadOutcome::CycleTimeout
        );
        assert_eq!(
            d.stages[&ReadStage::Homesdata].last_error,
            ReadOutcome::CycleTimeout
        );
        d.observe(
            ReadEvent::Cycle {
                period_seconds: 120,
                trigger: ReadTrigger::Deadline,
                late_ms: 0,
            },
            91000,
        );
        d.observe(
            ReadEvent::Stage(ReadStage::Homesdata, ReadOutcome::Pending),
            91000,
        );
        d.observe(ReadEvent::Stopped, 92000);
        d.observe(ReadEvent::Started, 93000);
        assert_eq!(d.counter_epoch, epoch);
        assert_eq!(d.polling.cycles, 2);
        assert_eq!(d.polling.automatic_cycles, 1);
        assert_eq!(d.polling.failures, 1);
        assert_eq!(d.polling.interruptions, 1);
        assert_eq!(d.polling.next_due_at, 0);
        assert_eq!(
            d.stages[&ReadStage::Homesdata].last_error,
            ReadOutcome::CycleTimeout
        );
        assert_eq!(
            d.stages[&ReadStage::Homesdata].status,
            ReadOutcome::Cancelled
        );
    }
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
