//! Volatile observation of a disabled daily graph on the original engine's
//! clock and event subscription. Nothing here creates a command permit.
use super::{
    Automation, AutomationError, Automations, RunStatus, Step, StepStatus, heating_locked, lock,
};
use moli_core::{Action, Event, PointId};
use serde::Serialize;
use std::{
    collections::VecDeque,
    sync::Arc,
    time::{Duration, Instant},
};
use tokio_util::sync::CancellationToken;

const KEEP: usize = 32;
const MAX_WATCHES: usize = 8;

/// Trusted, read-only adapter. The witness remains in memory, never in the
/// journal/export; it binds the decision to coherent live source/target data.
#[derive(Clone, Debug, PartialEq)]
pub struct ObservationSample {
    pub publication: u64,
    pub desired: bool,
    pub actual: bool,
    pub valid_until: u64,
    pub witness: serde_json::Value,
}
pub trait ObservationSource: std::fmt::Debug + Send + Sync + 'static {
    fn review(&self) -> Result<ObservationSample, &'static str>;
}
#[derive(Clone, Debug, Serialize)]
pub struct ObservationDecision {
    pub at: u64,
    pub reason: &'static str,
    pub intention: Option<bool>,
    pub actual: Option<bool>,
    pub repeats: u64,
}
#[derive(Clone, Debug, Serialize)]
pub struct ObservationStatus {
    pub active: bool,
    pub fingerprint: String,
    pub events: u64,
    pub publications: u64,
    pub clocks: u64,
    pub omitted: u64,
    pub last_at: Option<u64>,
    pub reason: &'static str,
    pub intention: Option<bool>,
    pub actual: Option<bool>,
    pub valid_until: Option<u64>,
    pub history: Vec<ObservationDecision>,
}
pub(crate) struct Watch {
    graph: Automation,
    fingerprint: String,
    source: Arc<dyn ObservationSource>,
    cancel: CancellationToken,
    status: ObservationStatus,
    skip_publication: Option<u64>,
    last_tick: Instant,
    last_wall: u64,
    since: u64,
    busy: Option<u64>,
    last_sample: Option<ObservationSample>,
    rate: VecDeque<Instant>,
}
impl std::fmt::Debug for Watch {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("DryObservation(..)")
    }
}
impl Watch {
    fn record(&mut self, reason: &'static str, intention: Option<bool>, actual: Option<bool>) {
        let now = moli_core::now_ms();
        self.status.last_at = Some(now);
        self.status.reason = reason;
        self.status.intention = intention;
        self.status.actual = actual;
        self.status.valid_until = None;
        if !matches!(reason, "would_on" | "would_off" | "no_change") {
            self.last_sample = None;
        }
        if let Some(last) = self.status.history.last_mut()
            && (last.reason, last.intention, last.actual) == (reason, intention, actual)
        {
            last.at = now;
            last.repeats = last.repeats.saturating_add(1);
            return;
        }
        if self.status.history.len() == KEEP {
            self.status.history.remove(0);
            self.status.omitted = self.status.omitted.saturating_add(1);
        }
        self.status.history.push(ObservationDecision {
            at: now,
            reason,
            intention,
            actual,
            repeats: 1,
        });
    }
    fn boundary(&mut self, reason: &'static str) {
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.skip_publication = self.source.review().ok().map(|s| s.publication);
        self.since = moli_core::now_ms();
        self.busy = None;
        self.last_sample = None;
        self.record(reason, None, None);
    }
    fn eligible(&mut self, review: &Result<ObservationSample, &'static str>) -> bool {
        match review {
            Ok(sample) => {
                let skipped = self.skip_publication.get_or_insert(sample.publication);
                if *skipped == sample.publication {
                    self.record("warming", None, Some(sample.actual));
                    false
                } else {
                    true
                }
            }
            Err(reason) => {
                if *reason != "source_pending" {
                    self.skip_publication = None;
                }
                self.record(reason, None, None);
                false
            }
        }
    }
    fn complete(
        &mut self,
        before: ObservationSample,
        run: Result<super::Run, AutomationError>,
        after: Result<ObservationSample, &'static str>,
    ) {
        match (run, after) {
            (Ok(run), Ok(after)) if after == before && run.status == RunStatus::Done => {
                let simulated: Vec<_> = run
                    .steps
                    .iter()
                    .filter(|s| s.status == StepStatus::Simulated)
                    .collect();
                let matching_set = simulated.len()==1 && self.graph.graph.node(&simulated[0].node).is_some_and(|n| {
                    matches!(&n.step,Step::Set{value,..}if value.as_bool()==Some(before.desired))
                });
                if before.desired == before.actual && simulated.is_empty() {
                    self.record("no_change", None, Some(before.actual));
                } else if before.desired != before.actual && matching_set {
                    self.record(
                        if before.desired {
                            "would_on"
                        } else {
                            "would_off"
                        },
                        Some(before.desired),
                        Some(before.actual),
                    );
                } else {
                    self.record("engine", None, None);
                    return;
                }
                self.status.valid_until = Some(before.valid_until);
                self.last_sample = Some(before);
            }
            (_, Err(reason)) => {
                self.skip_publication = None;
                self.record(reason, None, None);
            }
            _ => self.record("coherence", None, None),
        }
    }
    fn targets(&self, point: &PointId) -> bool {
        self.graph
            .graph
            .nodes
            .iter()
            .any(|node| matches!(&node.step, Step::Set { point: p, .. } if p == point.as_str()))
    }
    fn touches(&self, event: &Event) -> bool {
        self.graph.graph.nodes.iter().any(|node| match &node.step {
            Step::WhenState { point, .. } | Step::Set { point, .. } => match event {
                Event::State { point: p, .. } => point
                    .split_once('/')
                    .zip(p.as_str().split_once('/'))
                    .is_some_and(|(a, b)| a.0 == b.0),
                Event::Availability {
                    device,
                    online: false,
                }
                | Event::DeviceRemoved { device } => point
                    .strip_prefix(device.as_str())
                    .is_some_and(|s| s.starts_with('/')),
                Event::DriverStatus { instance, status } => {
                    *status != moli_core::DriverStatus::Running
                        && point.starts_with(&format!("{instance}:"))
                }
                _ => false,
            },
            _ => false,
        })
    }
}
impl Automations {
    /// Explicit observation opt-in. Never serialized, approved or made live.
    pub fn start_observation(
        &self,
        id: &str,
        fingerprint: &str,
        source: Arc<dyn ObservationSource>,
    ) -> Result<ObservationStatus, AutomationError> {
        let a = self.get(id).ok_or(AutomationError::Unknown)?;
        if self.0.cancel.is_cancelled()
            || !heating_locked(&a.graph)
            || a.enabled
            || a.is_approved()
            || a.mode != super::Mode::Single
            || a.fingerprint() != fingerprint
            || a.graph.triggers().any(|n| {
                !matches!(
                    n.step,
                    Step::Every { .. } | Step::WhenState { for_s: 0, .. }
                )
            })
        {
            return Err(AutomationError::Conflict);
        }
        let mut watches = lock(&self.0.observations);
        if let Some(watch) = watches.get(id)
            && watch.status.active
            && watch.fingerprint == fingerprint
        {
            return Ok(watch.status.clone());
        }
        if watches.len() >= MAX_WATCHES && !watches.contains_key(id) {
            return Err(AutomationError::Conflict);
        }
        let now = moli_core::now_ms();
        let skip_publication = source.review().ok().map(|s| s.publication);
        let mut watch = Watch {
            graph: a.clone(),
            fingerprint: fingerprint.into(),
            source,
            cancel: CancellationToken::new(),
            skip_publication,
            since: now,
            busy: None,
            last_sample: None,
            last_tick: Instant::now(),
            last_wall: now,
            rate: VecDeque::new(),
            status: ObservationStatus {
                active: true,
                fingerprint: fingerprint.into(),
                events: 0,
                publications: 0,
                clocks: 0,
                omitted: 0,
                last_at: None,
                reason: "warming",
                intention: None,
                actual: None,
                valid_until: None,
                history: Vec::new(),
            },
        };
        watch.record("warming", None, None);
        let status = watch.status.clone();
        watches.insert(id.into(), watch);
        drop(watches);
        // A concurrent save/delete may have happened between get() and insertion.
        // Once registered, subsequent changes cancel this watch through stop().
        if !self.get(id).is_some_and(|current| {
            current.fingerprint() == fingerprint && !current.enabled && !current.is_approved()
        }) {
            self.stop_observation(id);
            return Err(AutomationError::Conflict);
        }
        // Seed only this watch's current clock minute: no immediate/catch-up run.
        super::engine::mark_observation_minute(self, &a);
        Ok(status)
    }
    pub fn stop_observation(&self, id: &str) {
        if let Some(watch) = lock(&self.0.observations).get_mut(id) {
            watch.cancel.cancel();
            watch.status.active = false;
            watch.record("stopped", None, None);
        }
    }
    pub fn observation_status(&self, id: &str) -> Option<ObservationStatus> {
        let watches = lock(&self.0.observations);
        let watch = watches.get(id)?;
        let mut status = watch.status.clone();
        if status.active {
            match watch.source.review() {
                Ok(sample)
                    if watch.last_sample.is_some()
                        && watch.last_sample.as_ref() != Some(&sample) =>
                {
                    status.reason = "coherence";
                    status.intention = None;
                    status.actual = Some(sample.actual);
                    status.valid_until = None;
                }
                Err(reason) => {
                    status.reason = reason;
                    status.intention = None;
                    status.actual = None;
                    status.valid_until = None;
                }
                _ => {}
            }
        }
        Some(status)
    }
    pub(crate) fn observed(&self) -> Vec<Automation> {
        lock(&self.0.observations)
            .values()
            .filter(|w| w.status.active)
            .map(|w| w.graph.clone())
            .collect()
    }
    pub(crate) fn observation_event_is_current(&self, id: &str, ts: u64) -> bool {
        lock(&self.0.observations)
            .get(id)
            .is_none_or(|w| w.status.active && ts >= w.since)
    }
    pub fn stop_observations(&self) {
        for watch in lock(&self.0.observations).values_mut() {
            watch.cancel.cancel();
            watch.status.active = false;
            watch.record("stopped", None, None);
        }
    }
    pub(crate) fn observation_tick(&self) {
        let mut paused = Vec::new();
        for watch in lock(&self.0.observations)
            .values_mut()
            .filter(|w| w.status.active)
        {
            let now = Instant::now();
            let wall = moli_core::now_ms();
            if now.duration_since(watch.last_tick) > Duration::from_secs(5)
                || wall.saturating_sub(watch.last_wall) > 5_000
                || wall < watch.last_wall
            {
                watch.boundary("paused");
                paused.push(watch.graph.clone());
            }
            watch.last_tick = now;
            watch.last_wall = wall;
        }
        for a in paused {
            super::engine::mark_observation_minute(self, &a);
        }
    }
    pub(crate) fn observation_event(&self, event: &Event) {
        self.observation_tick();
        for watch in lock(&self.0.observations)
            .values_mut()
            .filter(|w| w.status.active)
        {
            if let Event::Journal { entry } = event
                && let Action::Command { point, .. } = &entry.action
                && watch.targets(point)
            {
                watch.boundary("manual");
            } else if watch.touches(event)
                && let Err(reason) = watch.source.review()
                && reason != "source_pending"
                && (watch.status.reason != reason || watch.status.intention.is_some())
            {
                watch.boundary(reason);
            }
        }
    }
    pub(crate) fn observation_fire(&self, a: &Automation, trigger: &str) -> bool {
        let (source, token, fp, ticket) = {
            let mut watches = lock(&self.0.observations);
            let Some(watch) = watches.get_mut(&a.id) else {
                return false;
            };
            if !watch.status.active || watch.busy.is_some() {
                return true;
            }
            let now = Instant::now();
            while watch
                .rate
                .front()
                .is_some_and(|t| now.duration_since(*t) >= Duration::from_secs(60))
            {
                watch.rate.pop_front();
            }
            if watch.rate.len() >= 20 {
                watch.status.active = false;
                watch.record("throttled", None, None);
                return true;
            }
            watch.rate.push_back(now);
            let ticket = self.next_id();
            watch.busy = Some(ticket);
            watch.status.events = watch.status.events.saturating_add(1);
            if trigger == super::DAILY_HEATING_LOCK {
                watch.status.clocks = watch.status.clocks.saturating_add(1);
            } else {
                watch.status.publications = watch.status.publications.saturating_add(1);
            }
            (
                watch.source.clone(),
                watch.cancel.child_token(),
                watch.fingerprint.clone(),
                ticket,
            )
        };
        let (this, id, trigger) = (self.clone(), a.id.clone(), trigger.to_string());
        tokio::spawn(async move {
            let before = source.review();
            let should_run = {
                let mut watches = lock(&this.0.observations);
                let Some(watch) = watches
                    .get_mut(&id)
                    .filter(|w| w.busy == Some(ticket) && w.status.active && !token.is_cancelled())
                else {
                    return;
                };
                watch.eligible(&before)
            };
            if should_run && let Ok(before) = before {
                let run = this
                    .observe_trigger(&id, &fp, &trigger, token.clone())
                    .await;
                let after = source.review();
                let mut watches = lock(&this.0.observations);
                if let Some(watch) = watches
                    .get_mut(&id)
                    .filter(|w| w.busy == Some(ticket) && w.status.active && !token.is_cancelled())
                {
                    watch.complete(before, run, after);
                }
            }
            if let Some(watch) = lock(&this.0.observations)
                .get_mut(&id)
                .filter(|w| w.busy == Some(ticket))
            {
                watch.busy = None;
            }
        });
        true
    }
}
#[cfg(test)]
mod tests;
