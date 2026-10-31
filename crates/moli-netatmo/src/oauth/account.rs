//! One renewal path for the running driver and stopped-host provisioning.
use super::{
    Error, Grant, Result,
    personal::{APPLICATION_SECRET, Application, ROTATION_SECRET, rotation_fingerprint},
};
use crate::client::{Https, Transport};
use moli_runtime::BoxFuture;

pub trait Vault: Send + Sync {
    fn get(&self, name: &str) -> Result<Option<String>>;
    fn put<'a>(&'a self, name: &'a str, value: &'a str) -> BoxFuture<'a, Result<()>>;
    fn remove<'a>(&'a self, name: &'a str) -> BoxFuture<'a, Result<()>>;
}
#[derive(Default, Debug)]
pub struct Account {
    pending: Option<Grant>,
}
impl Account {
    pub async fn ensure(&mut self, vault: &dyn Vault, now: u64, force: bool) -> Result<Grant> {
        self.ensure_with(&Https, vault, now, force).await
    }
    pub(crate) async fn ensure_with(
        &mut self,
        transport: &dyn Transport,
        vault: &dyn Vault,
        now: u64,
        force: bool,
    ) -> Result<Grant> {
        if let Some(pending) = self.pending.as_ref() {
            vault.put("oauth_tokens", &pending.vault_bundle()?).await?;
            self.pending = None;
        }
        let previous =
            Grant::from_personal_vault(&vault.get("oauth_tokens")?.ok_or(Error::SetupRequired)?)?;
        let fingerprint = rotation_fingerprint(&previous)?;
        if let Some(marker) = vault.get(ROTATION_SECRET)? {
            if marker == format!("revoked:{fingerprint}") {
                return Err(Error::Denied);
            }
            if marker == fingerprint {
                return Err(Error::Ambiguous);
            }
            // The persisted pair changed: a completed rotation survived interruption.
            vault.remove(ROTATION_SECRET).await?;
        }
        if !force && previous.expires_at() > now / 1000 + 60 {
            return Ok(previous);
        }
        let app = Application::parse(&vault.get(APPLICATION_SECRET)?.ok_or(Error::SetupRequired)?)?;
        vault.put(ROTATION_SECRET, &fingerprint).await?;
        let grant = match app.renew_with(transport, &previous, now).await {
            Ok(grant) => grant,
            Err(error @ (Error::Network | Error::Provider | Error::ClientRejected)) => {
                vault.remove(ROTATION_SECRET).await?;
                return Err(error);
            }
            Err(Error::RateLimited(s)) => {
                vault.remove(ROTATION_SECRET).await?;
                return Err(Error::RateLimited(s));
            }
            Err(Error::Denied) => {
                vault
                    .put(ROTATION_SECRET, &format!("revoked:{fingerprint}"))
                    .await?;
                return Err(Error::Denied);
            }
            Err(error) => return Err(error),
        };
        self.pending = Some(grant);
        vault
            .put(
                "oauth_tokens",
                &self
                    .pending
                    .as_ref()
                    .ok_or(Error::Storage)?
                    .vault_bundle()?,
            )
            .await?;
        self.pending = None;
        // Failure here is recoverable: the old fingerprint differs from the new pair.
        vault.remove(ROTATION_SECRET).await?;
        Grant::from_personal_vault(&vault.get("oauth_tokens")?.ok_or(Error::Storage)?)
    }
}
