//! A trusted adapter may narrow commands of a human-approved device link.
//! Graph approval, ordinary engine triggers, guard, transport and journal stay original.
use super::{Automation, AutomationError, Automations, DEVICE_LINK, lock};
use moli_core::{PointId, Value};
use moli_runtime::CommandPermit;
use std::{sync::Arc, time::Duration};
pub trait CommandAdmission: std::fmt::Debug + Send + Sync + 'static {
    fn allows(&self, point: &PointId, value: &Value) -> bool;
}
#[derive(Debug)]
pub(crate) struct Admission {
    fingerprint: String,
    predicate: Arc<dyn CommandAdmission>,
}
pub(crate) struct Authority(pub CommandPermit);
impl Drop for Authority {
    fn drop(&mut self) {
        self.0.revoke();
    }
}
fn linked(a: &Automation) -> bool {
    a.graph.nodes.iter().any(|n| n.id == DEVICE_LINK)
}
impl Automations {
    /// In-process only; registering a predicate never approves or enables a graph.
    pub fn admit_link(
        &self,
        id: &str,
        fingerprint: &str,
        predicate: Arc<dyn CommandAdmission>,
    ) -> Result<(), AutomationError> {
        let a = self.get(id).ok_or(AutomationError::Unknown)?;
        if !linked(&a) || a.fingerprint() != fingerprint {
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
        if !linked(a) {
            return true;
        }
        lock(&self.0.admissions)
            .get(&a.id)
            .is_some_and(|p| p.fingerprint == a.fingerprint() && p.predicate.allows(point, value))
    }
    pub(crate) fn command_authority(
        &self,
        a: &Automation,
        point: &PointId,
        value: &Value,
        token: &tokio_util::sync::CancellationToken,
    ) -> Option<Authority> {
        if !linked(a) || !a.is_live() {
            return None;
        }
        let version = a.fingerprint();
        let id = a.id.clone();
        let autos = self.clone();
        let expected_point = point.clone();
        let expected_value = value.clone();
        let permit = CommandPermit::new(
            point.clone(),
            value.clone(),
            Duration::from_secs(6),
            move || {
                autos.get(&id).is_some_and(|current| {
                    current.is_live()
                        && current.fingerprint() == version
                        && autos.admits(&current, &expected_point, &expected_value)
                })
            },
        );
        permit.bind(token.clone(), self.0.cancel.clone());
        Some(Authority(permit))
    }
}
