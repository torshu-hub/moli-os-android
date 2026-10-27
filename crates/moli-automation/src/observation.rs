//! Explicit dry observation uses the existing executor from the actual native
//! trigger. No caller can select physical execution or confer command authority.
use super::{AutomationError, Automations, Run, Step, heating_locked};
use tokio_util::sync::CancellationToken;

impl Automations {
    /// Evaluate the exact disabled daily draft from a clock/publication trigger.
    /// This entry point is always dry, cancellable, and never enables/approves
    /// the stored graph. A changed version is refused before execution.
    pub async fn observe_trigger(
        &self,
        id: &str,
        fingerprint: &str,
        trigger: &str,
        cancel: CancellationToken,
    ) -> Result<Run, AutomationError> {
        let a = self.get(id).ok_or(AutomationError::Unknown)?;
        if !heating_locked(&a.graph)
            || a.fingerprint() != fingerprint
            || a.enabled
            || a.is_approved()
            || !a.graph.node(trigger).is_some_and(|node| {
                matches!(
                    node.step,
                    Step::Every { .. } | Step::WhenState { for_s: 0, .. }
                )
            })
        {
            return Err(AutomationError::Conflict);
        }
        Ok(crate::run::execute(
            self,
            &a,
            trigger,
            moli_i18n::tr!("moteur.pourquoi.essai"),
            true,
            cancel,
            self.next_id(),
        )
        .await)
    }
}
