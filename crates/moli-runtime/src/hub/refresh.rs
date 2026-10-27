//! Read-only wakeup for an existing driver loop. One pending request, no task,
//! no command point or authority. The driver retains all cadence/backoff policy.
use super::{Hub, InstanceId};
fn lock<T>(value: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    value
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}
use serde::Serialize;
use std::sync::{Arc, Mutex};
use tokio::{sync::Notify, time::Instant};

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum ReadRefreshReply {
    Queued,
    Busy,
    Limited { retry_at: u64 },
    Unavailable,
}
#[derive(Debug)]
pub(super) struct Control {
    notify: Notify,
    state: Mutex<State>,
}
#[derive(Debug)]
struct State {
    enabled: bool,
    busy: bool,
    pending: bool,
    not_before: Instant,
}
/// Scoped registration: dropping it disables requests even across a restart.
#[derive(Debug)]
pub struct ReadRefresh {
    hub: Hub,
    instance: InstanceId,
    control: Arc<Control>,
}
impl Hub {
    pub(crate) fn register_read_refresh(&self, instance: &InstanceId) -> ReadRefresh {
        let control = Arc::new(Control {
            notify: Notify::new(),
            state: Mutex::new(State {
                enabled: true,
                busy: true,
                pending: false,
                not_before: Instant::now(),
            }),
        });
        lock(&self.0.read_refresh).insert(instance.clone(), control.clone());
        ReadRefresh {
            hub: self.clone(),
            instance: instance.clone(),
            control,
        }
    }
    /// Request a fresh observation, never an actuator command.
    pub fn request_read_refresh(&self, instance: &InstanceId) -> ReadRefreshReply {
        let controls = lock(&self.0.read_refresh);
        let Some(control) = controls.get(instance) else {
            return ReadRefreshReply::Unavailable;
        };
        let mut state = lock(&control.state);
        if !state.enabled {
            return ReadRefreshReply::Unavailable;
        }
        if state.busy || state.pending {
            return ReadRefreshReply::Busy;
        }
        let now = Instant::now();
        if now < state.not_before {
            return ReadRefreshReply::Limited {
                retry_at: moli_core::now_ms().saturating_add(
                    u64::try_from((state.not_before - now).as_millis()).unwrap_or(u64::MAX),
                ),
            };
        }
        state.pending = true;
        control.notify.notify_one();
        ReadRefreshReply::Queued
    }
}
impl ReadRefresh {
    pub async fn notified(&self) {
        self.control.notify.notified().await;
    }
    /// Consume the one request only. An automatic cycle may already have served it.
    pub fn take(&self) -> bool {
        let mut state = lock(&self.control.state);
        let take =
            state.enabled && !state.busy && state.pending && Instant::now() >= state.not_before;
        state.pending = false;
        take
    }
    pub fn begin(&self) {
        let mut state = lock(&self.control.state);
        state.busy = true;
        state.pending = false;
    }
    pub fn complete(&self, not_before: Instant) {
        let mut state = lock(&self.control.state);
        state.busy = false;
        state.not_before = not_before;
    }
}
impl Drop for ReadRefresh {
    fn drop(&mut self) {
        lock(&self.control.state).enabled = false;
        let mut controls = lock(&self.hub.0.read_refresh);
        if controls
            .get(&self.instance)
            .is_some_and(|c| Arc::ptr_eq(c, &self.control))
        {
            controls.remove(&self.instance);
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test(start_paused = true)]
    async fn coalesces_cadence_quota_stop_and_instance_replacement_without_commands() {
        let hub = Hub::new(super::super::HubOptions::default()).unwrap();
        let id = InstanceId::from("synthetic");
        assert_eq!(hub.request_read_refresh(&id), ReadRefreshReply::Unavailable);
        let read = hub.register_read_refresh(&id);
        assert_eq!(hub.request_read_refresh(&id), ReadRefreshReply::Busy);
        read.complete(Instant::now() + std::time::Duration::from_secs(60));
        assert!(matches!(
            hub.request_read_refresh(&id),
            ReadRefreshReply::Limited { .. }
        ));
        tokio::time::advance(std::time::Duration::from_secs(60)).await;
        assert_eq!(hub.request_read_refresh(&id), ReadRefreshReply::Queued);
        assert_eq!(hub.request_read_refresh(&id), ReadRefreshReply::Busy);
        read.notified().await;
        assert!(read.take());
        assert!(!read.take());
        read.begin();
        read.complete(Instant::now() + std::time::Duration::from_secs(3600));
        tokio::time::advance(std::time::Duration::from_secs(120)).await;
        assert!(matches!(
            hub.request_read_refresh(&id),
            ReadRefreshReply::Limited { .. }
        ));
        let replacement = hub.register_read_refresh(&id);
        drop(read);
        assert_eq!(hub.request_read_refresh(&id), ReadRefreshReply::Busy);
        drop(replacement);
        assert_eq!(hub.request_read_refresh(&id), ReadRefreshReply::Unavailable);
        assert!(hub.journal(10).is_empty());
    }
}
