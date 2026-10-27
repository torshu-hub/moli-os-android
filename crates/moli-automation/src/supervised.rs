//! One human-confirmed, non-scheduled, bounded execution of an existing graph.
use super::{Actor, AutomationError, Automations, Graph, Run, Step, lock, run};
use moli_i18n::tr;
use moli_runtime::CommandPermit;
struct Grant(Automations, u64, CommandPermit);
impl Drop for Grant {
    fn drop(&mut self) {
        self.2.revoke();
        lock(&self.0.0.scopes).remove(&self.1);
    }
}
impl Automations {
    /// Uses the original executor, guard, journal and driver path. The grant is
    /// confined to this run and always revoked, including task cancellation.
    /// Human confirmation is per call; no approval is persisted or inherited.
    pub async fn run_supervised(
        &self,
        id: &str,
        fingerprint: &str,
        by: Actor,
        scope: CommandPermit,
    ) -> Result<Run, AutomationError> {
        if !by.human {
            return Err(AutomationError::NeedsHuman);
        }
        let a = self.get(id).ok_or(AutomationError::Unknown)?;
        if a.fingerprint() != fingerprint {
            return Err(AutomationError::Conflict);
        }
        if a.enabled || !self.check(&a.graph).ok() {
            return Err(AutomationError::Invalid(
                "supervised execution requires a valid disabled version".into(),
            ));
        }
        manual_only(&a.graph)?;
        for n in &a.graph.nodes {
            if let Step::Set { point, value } = &n.step
                && !scope.allows(&point.as_str().into(), &moli_core::Value::from_json(value))
            {
                return Err(AutomationError::Conflict);
            }
        }
        let start = a
            .graph
            .nodes
            .iter()
            .find(|n| matches!(n.step, Step::Manual))
            .ok_or(AutomationError::Unknown)?
            .id
            .clone();
        if self
            .0
            .shutting_down
            .load(std::sync::atomic::Ordering::Acquire)
        {
            return Err(AutomationError::Conflict);
        }
        let token = lock(&self.0.stops)
            .entry(a.id.clone())
            .or_default()
            .child_token();
        scope.bind(token.clone(), self.0.cancel.clone());
        let run_id = self.next_id();
        lock(&self.0.scopes).insert(run_id, scope.clone());
        let _grant = Grant(self.clone(), run_id, scope);
        Ok(run::execute(
            self,
            &a,
            &start,
            tr!("moteur.pourquoi.main"),
            false,
            token,
            run_id,
        )
        .await)
    }
}
fn manual_only(g: &Graph) -> Result<(), AutomationError> {
    if g.nodes
        .iter()
        .filter(|n| matches!(n.step, Step::Manual))
        .count()
        != 1
        || g.nodes
            .iter()
            .filter(|n| matches!(n.step, Step::Set { .. }))
            .count()
            != 1
        || g.nodes
            .iter()
            .any(|n| !matches!(n.step, Step::Manual | Step::If { .. } | Step::Set { .. }))
    {
        return Err(AutomationError::Invalid(
            "supervised graph requires one manual trigger and one bounded set".into(),
        ));
    }
    Ok(())
}
