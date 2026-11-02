//! Crash-safe CCI token rotation. The vault holds one complete account bundle;
//! an ambiguous refresh is never retried with the same possibly spent token.
use crate::{
    client::{Client, Error, Transport},
    model::Vehicle,
};
use moli_runtime::BoxFuture;
use serde::{Deserialize, Serialize};
pub const SECRET: &str = "hyundai_account";
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Account {
    pub session: crate::client::Session,
    pub vehicles: Vec<Vehicle>,
    #[serde(default)]
    pub phase: Phase,
}
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Phase {
    #[default]
    Ready,
    Rotating,
    Exchange,
}
pub trait Vault: Send + Sync {
    fn get(&self) -> Result<Option<String>, Error>;
    fn put<'a>(&'a self, text: &'a str) -> BoxFuture<'a, Result<(), Error>>;
}
impl Account {
    pub fn parse(s: &str) -> Result<Self, Error> {
        serde_json::from_str(s).map_err(|_| Error::Storage)
    }
    pub fn encode(&self) -> Result<String, Error> {
        serde_json::to_string(self).map_err(|_| Error::Storage)
    }
    pub async fn password_authorization(
        username: &str,
        password: &str,
        pin: Option<String>,
        mode: crate::auth::ApiMode,
    ) -> Result<crate::client::Session, crate::auth::LoginFailure> {
        crate::auth::check_pin(pin.as_deref())
            .map_err(|e| crate::auth::LoginFailure::local("pin", e))?;
        let mut client = Client::new();
        let device = client
            .login_identity(mode)
            .await
            .map_err(|e| client.failure(e))?;
        match tokio::time::timeout(
            std::time::Duration::from_secs(120),
            client.login_device(username, password, pin, device, mode),
        )
        .await
        {
            Ok(result) => result.map_err(|e| client.failure(e)),
            Err(_) => Err(client.failure(Error::NetworkFailure(
                moli_net::web_diagnostics::Phase::Headers,
                true,
            ))),
        }
    }
    pub async fn discover(
        session: crate::client::Session,
    ) -> Result<Self, crate::auth::LoginFailure> {
        let mut client = Client::new();
        Self::discover_with(&mut client, session).await
    }
    pub(crate) async fn discover_with<T: Transport>(
        client: &mut Client<T>,
        session: crate::client::Session,
    ) -> Result<Self, crate::auth::LoginFailure> {
        let data = client
            .vehicles(&session)
            .await
            .map_err(|e| client.failure(e))?;
        let vehicles = crate::model::vehicles(&data);
        if vehicles.is_empty() {
            return Err(crate::auth::LoginFailure::local(
                "fleet_discovery",
                Error::Unsupported,
            ));
        }
        if session.api_mode == crate::auth::ApiMode::Gspa {
            // Device enrollment is not a vehicle order. Do not replay on quota.
            if let Err(error @ Error::RateLimit(_)) = client.register_device(&session).await {
                return Err(client.failure(error));
            }
        }
        Ok(Self {
            session,
            vehicles,
            phase: Phase::Ready,
        })
    }
    pub(crate) async fn ensure<T: Transport>(
        &mut self,
        client: &mut Client<T>,
        vault: &dyn Vault,
    ) -> Result<(), Error> {
        if self.phase == Phase::Rotating {
            return Err(Error::Authentication);
        }
        if self.phase == Phase::Ready && self.session.expires > moli_core::now_ms() / 1000 + 60 {
            return Ok(());
        }
        if self.phase == Phase::Ready {
            self.phase = Phase::Rotating;
            vault.put(&self.encode()?).await?;
            if let Err(error) = client.refresh(&mut self.session).await {
                if matches!(error, Error::RateLimit(_)) {
                    self.phase = Phase::Ready;
                    vault.put(&self.encode()?).await?;
                }
                return Err(error);
            }

            self.phase = Phase::Exchange;
            vault.put(&self.encode()?).await?;
        }
        client.exchange(&mut self.session).await?;
        let mut durable = self.clone();
        durable.phase = Phase::Ready;
        vault.put(&durable.encode()?).await?;
        self.phase = Phase::Ready;
        Ok(())
    }
    pub fn public(&self) -> serde_json::Value {
        serde_json::json!({"api_mode":self.session.api_mode,"connected":true,"phase":format!("{:?}",self.phase),"expires_at":self.session.expires,"pin_present":self.session.pin.is_some(),"vehicles":self.vehicles.iter().map(|v|serde_json::json!({"key":v.key,"name":v.name,"model":v.model,"ccs2":v.ccs2})).collect::<Vec<_>>()})
    }
}
