//! Volatile, revocable command authority. Never serialized or restored.
use moli_core::{PointId, Value};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::time::{Duration, Instant};
#[derive(Clone)]
pub struct CommandPermit(Arc<Inner>);
struct Inner {
    point: PointId,
    value: Value,
    until: Instant,
    issued_ms: u64,
    until_ms: u64,
    revoked: AtomicBool,
    stop: std::sync::Mutex<
        Option<(
            tokio_util::sync::CancellationToken,
            tokio_util::sync::CancellationToken,
        )>,
    >,
    admission: Box<dyn Fn() -> bool + Send + Sync>,
}
impl std::fmt::Debug for CommandPermit {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("CommandPermit(..)")
    }
}
impl CommandPermit {
    /// A trusted human-approval adapter issues a short-lived grant for exactly
    /// one point/value. Its live admission predicate is checked again by the
    /// driver before transport; it cannot grant any other automation rights.
    #[must_use]
    pub fn new(
        point: PointId,
        value: Value,
        ttl: Duration,
        admission: impl Fn() -> bool + Send + Sync + 'static,
    ) -> Self {
        let ttl = ttl.min(Duration::from_secs(10));
        let issued_ms = moli_core::now_ms();
        Self(Arc::new(Inner {
            point,
            value,
            until: Instant::now() + ttl,
            issued_ms,
            until_ms: issued_ms.saturating_add(u64::try_from(ttl.as_millis()).unwrap_or(u64::MAX)),
            revoked: AtomicBool::new(false),
            admission: Box::new(admission),
            stop: std::sync::Mutex::default(),
        }))
    }
    #[must_use]
    pub fn allows(&self, point: &PointId, value: &Value) -> bool {
        // Android/Linux monotonic clocks can pause during device suspension.
        // Neither suspension nor a backwards clock change extends this grant.
        let now = moli_core::now_ms();
        now >= self.0.issued_ms
            && now < self.0.until_ms
            && !self.0.revoked.load(Ordering::Acquire)
            && !self
                .0
                .stop
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .as_ref()
                .is_some_and(|(run, engine)| run.is_cancelled() || engine.is_cancelled())
            && Instant::now() < self.0.until
            && point == &self.0.point
            && value == &self.0.value
            && (self.0.admission)()
    }
    /// Bind directly to both original stop tokens, including driver awaits.
    /// The global token closes shutdown races before the engine drains runs.
    pub fn bind(
        &self,
        token: tokio_util::sync::CancellationToken,
        engine: tokio_util::sync::CancellationToken,
    ) {
        *self
            .0
            .stop
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some((token, engine));
    }
    pub fn revoke(&self) {
        self.0.revoked.store(true, Ordering::Release);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn authority_is_exact_volatile_revocable_and_rechecks_admission() {
        let current = Arc::new(AtomicBool::new(true));
        let reading = current.clone();
        let p = PointId::from("synthetic:plug/on");
        let v = Value::Bool(true);
        let grant = CommandPermit::new(p.clone(), v.clone(), Duration::from_secs(2), move || {
            reading.load(Ordering::Relaxed)
        });
        assert!(grant.allows(&p, &v));
        assert!(!grant.allows(&PointId::from("other:plug/on"), &v));
        assert!(!grant.allows(&p, &Value::Bool(false)));
        current.store(false, Ordering::Relaxed);
        assert!(!grant.allows(&p, &v));
        current.store(true, Ordering::Relaxed);
        let stop = tokio_util::sync::CancellationToken::new();
        grant.bind(stop.clone(), tokio_util::sync::CancellationToken::new());
        stop.cancel();
        assert!(!grant.allows(&p, &v));
        let engine = tokio_util::sync::CancellationToken::new();
        let global = CommandPermit::new(p.clone(), v.clone(), Duration::from_secs(2), || true);
        global.bind(tokio_util::sync::CancellationToken::new(), engine.clone());
        assert!(global.allows(&p, &v));
        engine.cancel();
        assert!(!global.allows(&p, &v));
        let cloned = grant.clone();
        grant.revoke();
        assert!(!cloned.allows(&p, &v));
        let expired = CommandPermit::new(p.clone(), v.clone(), Duration::ZERO, || true);
        assert!(!expired.allows(&p, &v));
    }
    #[test]
    fn wall_expiration_or_reversal_refuses_a_still_live_monotonic_grant() {
        let p = PointId::from("synthetic:plug/on");
        let v = Value::Bool(true);
        for reverse in [false, true] {
            let mut grant =
                CommandPermit::new(p.clone(), v.clone(), Duration::from_secs(6), || true);
            assert!(grant.allows(&p, &v));
            let inner = Arc::get_mut(&mut grant.0).unwrap();
            assert!(Instant::now() < inner.until);
            if reverse {
                inner.issued_ms = moli_core::now_ms() + 60_000;
            } else {
                inner.until_ms = 0;
            }
            assert!(!grant.allows(&p, &v));
        }
    }
}
