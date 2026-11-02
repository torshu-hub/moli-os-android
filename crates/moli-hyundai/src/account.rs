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
    pub async fn login(username: &str, password: &str, pin: Option<String>) -> Result<Self, Error> {
        if pin
            .as_ref()
            .is_some_and(|p| p.len() != 4 || !p.bytes().all(|b| b.is_ascii_digit()))
        {
            return Err(Error::Pin);
        }
        let mut client = Client::new();
        let session = client.login(username, password, pin).await?;
        let vehicles = crate::model::vehicles(&client.vehicles(&session).await?);
        if vehicles.is_empty() {
            return Err(Error::Unsupported);
        }
        // CCI device enrollment is part of the reference login, not a vehicle
        // order or a new push service. It is optional except for quota handling.
        if let Err(error @ Error::RateLimit(_)) = client.register_device(&session).await {
            return Err(error);
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
        serde_json::json!({"connected":true,"phase":format!("{:?}",self.phase),"expires_at":self.session.expires,"pin_present":self.session.pin.is_some(),"vehicles":self.vehicles.iter().map(|v|serde_json::json!({"key":v.key,"name":v.name,"model":v.model,"ccs2":v.ccs2})).collect::<Vec<_>>()})
    }
}
