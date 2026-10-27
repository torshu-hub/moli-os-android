//! Volatile admission predicates for the locked daily candidate. They never
//! issue command authority. The release interlock remains independent.
use super::{Automation, AutomationError, Automations, heating_locked, lock};
use moli_core::{PointId, Value};
use std::sync::Arc;

pub trait DraftAdmission: std::fmt::Debug + Send + Sync + 'static {
    fn allows(&self, point: &PointId, value: &Value) -> bool;
}
#[derive(Debug)]
pub(crate) struct Admission {
    fingerprint: String,
    predicate: Arc<dyn DraftAdmission>,
}
impl Automations {
    /// Trusted in-process adapter only: no REST/MCP method accepts predicates.
    /// Replacing the graph or restarting invalidates this admission. It grants
    /// no physical rights, even to a matching, human-approved candidate.
    pub fn admit_draft(
        &self,
        id: &str,
        fingerprint: &str,
        predicate: Arc<dyn DraftAdmission>,
    ) -> Result<(), AutomationError> {
        let a = self.get(id).ok_or(AutomationError::Unknown)?;
        if !heating_locked(&a.graph)
            || a.fingerprint() != fingerprint
            || a.enabled
            || a.is_approved()
        {
            return Err(AutomationError::Conflict);
        }
        lock(&self.0.admissions).insert(
            id.into(),
            Admission {
                fingerprint: fingerprint.into(),
                predicate,
            },
        );
        Ok(())
    }
    pub(crate) fn admits(&self, a: &Automation, point: &PointId, value: &Value) -> bool {
        if !heating_locked(&a.graph) {
            return true;
        }
        let admissions = lock(&self.0.admissions);
        admissions
            .get(&a.id)
            .is_some_and(|p| p.fingerprint == a.fingerprint() && p.predicate.allows(point, value))
    }
}
