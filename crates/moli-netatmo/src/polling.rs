//! Deadline-driven reads. A suspended Android monotonic clock must not hide an
//! overdue normal cycle after resume. Never replay missed cycles or quota waits.
use moli_runtime::{ReadOutcome, ReadTrigger};
use std::time::Duration;
use tokio::time::Instant;

pub(super) struct Polling {
    pub next: Instant,
    pub next_wall: u64,
    initial: bool,
    regular: bool,
}
impl Polling {
    pub fn new(now: Instant, wall: u64) -> Self {
        Self {
            next: now,
            next_wall: wall,
            initial: true,
            regular: true,
        }
    }
    pub fn due(&self, now: Instant, wall: u64) -> Option<(ReadTrigger, u64)> {
        if self.initial {
            return Some((ReadTrigger::Initial, 0));
        }
        if now >= self.next {
            return Some((
                ReadTrigger::Deadline,
                u64::try_from(now.duration_since(self.next).as_millis()).unwrap_or(u64::MAX),
            ));
        }
        if self.regular && wall >= self.next_wall {
            return Some((ReadTrigger::WallDeadline, wall - self.next_wall));
        }
        None
    }
    pub fn schedule(
        &mut self,
        start: Instant,
        start_wall: u64,
        end: Instant,
        end_wall: u64,
        delay: u64,
        outcome: ReadOutcome,
    ) {
        self.initial = false;
        self.regular = outcome == ReadOutcome::Ok;
        let period = Duration::from_secs(delay);
        // Normal frequency is measured between starts, not response completion.
        // If execution itself overran, skip missed periods instead of bursting.
        if self.regular && start + period > end {
            self.next = start + period;
            self.next_wall = start_wall.saturating_add(delay.saturating_mul(1000));
        } else {
            self.next = end + period;
            self.next_wall = end_wall.saturating_add(delay.saturating_mul(1000));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test(start_paused = true)]
    async fn frequency_is_between_starts_and_resume_skips_backlog_without_bypassing_quota() {
        let start = Instant::now();
        let mut p = Polling::new(start, 1_000_000);
        assert!(matches!(
            p.due(start, 1_000_000),
            Some((ReadTrigger::Initial, _))
        ));
        p.schedule(
            start,
            1_000_000,
            start + Duration::from_secs(20),
            1_020_000,
            120,
            ReadOutcome::Ok,
        );
        assert!(p.due(start + Duration::from_secs(119), 1_119_000).is_none());
        assert!(matches!(
            p.due(start + Duration::from_secs(120), 1_120_000),
            Some((ReadTrigger::Deadline, 0))
        ));
        // Wall time advanced while the monotonic clock was suspended.
        assert!(matches!(
            p.due(start + Duration::from_secs(30), 1_600_000),
            Some((ReadTrigger::WallDeadline, 480_000))
        ));
        let resumed = start + Duration::from_secs(30);
        p.schedule(
            resumed,
            1_600_000,
            resumed + Duration::from_secs(2),
            1_602_000,
            120,
            ReadOutcome::Ok,
        );
        assert!(p.due(resumed + Duration::from_secs(3), 1_603_000).is_none());
        p.schedule(
            resumed,
            1_600_000,
            resumed,
            1_600_000,
            3600,
            ReadOutcome::RateLimit,
        );
        assert!(
            p.due(resumed + Duration::from_secs(120), 9_000_000)
                .is_none()
        );
        assert!(matches!(
            p.due(resumed + Duration::from_secs(3600), 9_000_000),
            Some((ReadTrigger::Deadline, _))
        ));
    }
}
