//! Durable confidential account core, without a listener or deployment.
//!
//! One dispatcher must own one `Service` per account and serialize its calls.
//! All records use the existing encrypted Moli vault. A future HTTPS adapter
//! must authenticate the device key and bind its route to this fixed account;
//! no request may choose the vault namespace, application or device instance.
use std::{fmt, sync::Arc, time::Instant};

use moli_core::InstanceId;
use moli_runtime::Hub;
use serde::{Deserialize, Serialize};

use super::super::{Broker, CallbackSession, Error, Grant, Policy, Reply, Result, SESSION_MS};
use crate::client::{self, Https, Tokens, Transport};

#[path = "service/http.rs"]
pub mod endpoint;

const RECORD: &str = "oauth_broker_record";
const DEVICE_KEY: &str = "broker_device_key";
const MAX_RECORD: usize = 32 * 1024;

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Record {
    version: u8,
    binding: String,
    tokens: Option<Tokens>,
    flow: Option<Flow>,
    in_flight: Option<String>,
    previous: Option<String>,
    retry_at: u64,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Flow {
    ticket: String,
    state: String,
    challenge: String,
    created: u64,
    status: Status,
}
#[derive(Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
enum Status {
    Pending,
    Exchanging,
    Approved,
    Denied,
}

/// Prepared server-side account. No client secret is compiled into Android.
pub struct Service {
    policy: Policy,
    hub: Hub,
    account: InstanceId,
    device_instance: String,
    record: Record,
    unsaved: Option<Record>,
    opened: u64,
    monotonic: Instant,
    transport: Arc<dyn Transport>,
}
impl fmt::Debug for Service {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ConfidentialOAuthService")
            .field("save_pending", &self.unsaved.is_some())
            .finish_non_exhaustive()
    }
}

fn fingerprint(text: &str) -> String {
    super::super::proof_challenge(text)
}
fn same(left: &str, right: &str) -> bool {
    left.len() == right.len()
        && left
            .bytes()
            .zip(right.bytes())
            .fold(0_u8, |d, (a, b)| d | (a ^ b))
            == 0
}
fn tokens_fingerprint(tokens: &Tokens) -> Result<String> {
    Ok(fingerprint(
        &serde_json::to_string(tokens).map_err(|_| Error::Tokens)?,
    ))
}
impl Record {
    fn empty(binding: String) -> Self {
        Self {
            version: 1,
            binding,
            tokens: None,
            flow: None,
            in_flight: None,
            previous: None,
            retry_at: 0,
        }
    }
    fn validate(&self, binding: &str) -> Result<()> {
        if self.version != 1
            || !same(&self.binding, binding)
            || self
                .previous
                .as_deref()
                .is_some_and(|v| !super::super::nonce(v))
            || self
                .in_flight
                .as_deref()
                .is_some_and(|v| v != "authorization" && !super::super::nonce(v))
        {
            return Err(Error::State);
        }
        if let Some(tokens) = &self.tokens {
            Tokens::parse(&serde_json::to_string(tokens).map_err(|_| Error::Tokens)?)
                .map_err(|_| Error::Tokens)?;
        }
        if let Some(flow) = &self.flow {
            for value in [&flow.ticket, &flow.state, &flow.challenge] {
                if !super::super::nonce(value) {
                    return Err(Error::State);
                }
            }
        }
        Ok(())
    }
}

impl Service {
    /// Open an already provisioned server vault, never create keys or accounts.
    /// `broker_device_key` is an independent 256-bit device credential, not a PIN
    /// or provider secret. The caller fixes the account and mobile instance.
    ///
    /// # Errors
    /// Refuses missing storage/keys, malformed records and changed app/bindings.
    pub fn open(
        policy: Policy,
        hub: Hub,
        account: InstanceId,
        device_instance: &str,
        now: u64,
    ) -> Result<Self> {
        if !hub.has_secret_store() {
            return Err(Error::Storage);
        }
        if !crate::model::valid_id(device_instance) {
            return Err(Error::Input);
        }
        if !hub
            .secret(&account, DEVICE_KEY)
            .is_some_and(|v| super::super::nonce(&v))
            || hub
                .secret(&account, "client_secret")
                .is_none_or(|v| v.is_empty())
        {
            return Err(Error::SetupRequired);
        }
        let binding = fingerprint(
            &serde_json::to_string(&(
                policy.client_id(),
                policy.redirect_uri(),
                account.as_str(),
                device_instance,
            ))
            .map_err(|_| Error::Input)?,
        );
        let record = if let Some(raw) = hub.secret(&account, RECORD) {
            if raw.len() > MAX_RECORD {
                return Err(Error::Storage);
            }
            serde_json::from_str::<Record>(&raw).map_err(|_| Error::Storage)?
        } else {
            Record::empty(binding.clone())
        };
        record.validate(&binding)?;
        Ok(Self {
            policy,
            hub,
            account,
            device_instance: device_instance.to_owned(),
            record,
            unsaved: None,
            opened: now,
            monotonic: Instant::now(),
            transport: Arc::new(Https),
        })
    }
    fn authenticate(&self, key: &str) -> Result<()> {
        let expected = self
            .hub
            .secret(&self.account, DEVICE_KEY)
            .ok_or(Error::Denied)?;
        if !super::super::nonce(key) || !same(&fingerprint(key), &fingerprint(&expected)) {
            return Err(Error::Denied);
        }
        Ok(())
    }
    fn time(&self, now: u64) -> Result<u64> {
        if now < self.opened {
            return Err(Error::Expired);
        }
        Ok(now.max(self.opened.saturating_add(
            u64::try_from(self.monotonic.elapsed().as_millis()).unwrap_or(u64::MAX),
        )))
    }
    async fn save(&mut self, next: Record) -> Result<()> {
        self.unsaved = Some(next);
        self.retry_save().await
    }
    async fn retry_save(&mut self) -> Result<()> {
        if let Some(next) = &self.unsaved {
            let raw = serde_json::to_string(next).map_err(|_| Error::Storage)?;
            if raw.len() > MAX_RECORD {
                return Err(Error::Storage);
            }
            self.hub
                .store_secret(&self.account, RECORD, &raw)
                .await
                .map_err(|_| Error::Storage)?;
            self.record = self.unsaved.take().ok_or(Error::Storage)?;
        }
        Ok(())
    }
    fn flow(&self, ticket: &str, now: u64) -> Result<&Flow> {
        let flow = self.record.flow.as_ref().ok_or(Error::State)?;
        if !same(&flow.ticket, ticket) {
            return Err(Error::State);
        }
        if now < flow.created || now - flow.created >= SESSION_MS {
            return Err(Error::Expired);
        }
        Ok(flow)
    }
    /// Persist a bound transaction before the external browser is opened.
    ///
    /// # Errors
    /// Refuses unauthenticated devices, duplicate live flows and failed saves.
    pub async fn begin(
        &mut self,
        key: &str,
        state: &str,
        challenge: &str,
        now: u64,
    ) -> Result<String> {
        self.authenticate(key)?;
        let now = self.time(now)?;
        self.retry_save().await?;
        if !super::super::nonce(state) || !super::super::nonce(challenge) {
            return Err(Error::Input);
        }
        if self.record.retry_at > now {
            return Err(Error::RateLimited(
                (self.record.retry_at - now).div_ceil(1000),
            ));
        }
        if self.record.flow.as_ref().is_some_and(|flow| {
            flow.status == Status::Pending && now >= flow.created && now - flow.created < SESSION_MS
        }) {
            return Err(Error::State);
        }
        let ticket = super::super::random()?;
        let mut next = self.record.clone();
        next.flow = Some(Flow {
            ticket: ticket.clone(),
            state: state.to_owned(),
            challenge: challenge.to_owned(),
            created: now,
            status: Status::Pending,
        });
        self.save(next).await?;
        Ok(ticket)
    }
    /// Consume an exact provider callback and durably mark it before exchange.
    /// The HTTP adapter must not log, echo or redirect the sensitive URL.
    ///
    /// # Errors
    /// Rejects forged/expired/replayed callbacks and uncertain exchanges.
    pub async fn callback(&mut self, callback: &str, now: u64) -> Result<()> {
        let now = self.time(now)?;
        self.retry_save().await?;
        let flow = self.record.flow.as_ref().ok_or(Error::State)?;
        if flow.status != Status::Pending {
            return Err(Error::Replay);
        }
        let mut session = CallbackSession::new(self.policy.clone(), &flow.state, flow.created)?;
        let code = match session.accept(callback, now) {
            Ok(code) => code,
            Err(Error::Denied) => {
                let mut next = self.record.clone();
                next.flow.as_mut().ok_or(Error::State)?.status = Status::Denied;
                self.save(next).await?;
                return Err(Error::Denied);
            }
            Err(error) => return Err(error),
        };
        let mut next = self.record.clone();
        next.flow.as_mut().ok_or(Error::State)?.status = Status::Exchanging;
        next.in_flight = Some("authorization".into());
        self.save(next).await?;
        let secret = self
            .hub
            .secret(&self.account, "client_secret")
            .ok_or(Error::SetupRequired)?;
        let grant =
            match super::exchange_with(self.transport.as_ref(), &self.policy, code, &secret, now)
                .await
            {
                Ok(grant) => grant,
                Err(Error::RateLimited(seconds)) => {
                    let seconds = seconds.clamp(60, 7 * 86400);
                    let mut next = self.record.clone();
                    next.retry_at = now.saturating_add(seconds * 1000);
                    self.save(next).await?;
                    return Err(Error::RateLimited(seconds));
                }
                Err(error) => return Err(error),
            };
        let mut next = self.record.clone();
        next.tokens = Some(grant.tokens);
        next.previous = None;
        next.in_flight = None;
        next.flow.as_mut().ok_or(Error::State)?.status = Status::Approved;
        self.save(next).await
    }
    /// Recover the same committed result with the device key and handoff proof.
    /// Reading this result never replays a provider code or token exchange.
    ///
    /// # Errors
    /// Refuses another device, ticket/proof, expiry and incomplete persistence.
    pub async fn poll(
        &mut self,
        key: &str,
        ticket: &str,
        verifier: &str,
        now: u64,
    ) -> Result<Reply> {
        self.authenticate(key)?;
        let now = self.time(now)?;
        self.retry_save().await?;
        let flow = self.flow(ticket, now)?;
        if !super::super::nonce(verifier) || !same(&flow.challenge, &fingerprint(verifier)) {
            return Err(Error::State);
        }
        match flow.status {
            Status::Pending => Ok(Reply::Pending),
            Status::Denied => Ok(Reply::Denied),
            Status::Exchanging => Err(Error::Ambiguous),
            Status::Approved => self.current(now).map(Reply::Approved),
        }
    }
    fn current(&self, now: u64) -> Result<Grant> {
        let tokens = self.record.tokens.as_ref().ok_or(Error::Tokens)?;
        if tokens.expires_at <= now / 1000 || tokens.expires_at > (now / 1000).saturating_add(86400)
        {
            return Err(Error::Expired);
        }
        Ok(Grant {
            tokens: tokens.clone(),
        })
    }
    /// Renew once, persist the complete pair, recover it after a lost response.
    ///
    /// # Errors
    /// Refuses foreign/stale pairs and fails closed after an ambiguous send or
    /// crash. A quota sets a durable deadline; save retry never refreshes again.
    pub async fn renew(
        &mut self,
        key: &str,
        instance: &str,
        previous: &Grant,
        now: u64,
    ) -> Result<Grant> {
        self.authenticate(key)?;
        let now = self.time(now)?;
        if instance != self.device_instance {
            return Err(Error::State);
        }
        self.retry_save().await?;
        let digest = tokens_fingerprint(&previous.tokens)?;
        if self
            .record
            .previous
            .as_ref()
            .is_some_and(|old| same(old, &digest))
        {
            return self.current(now);
        }
        if self.record.in_flight.is_some() {
            return Err(Error::Ambiguous);
        }
        let tokens = self.record.tokens.as_ref().ok_or(Error::SetupRequired)?;
        if !same(&tokens_fingerprint(tokens)?, &digest) {
            return Err(Error::State);
        }
        if self.record.retry_at > now {
            return Err(Error::RateLimited(
                (self.record.retry_at - now).div_ceil(1000),
            ));
        }
        let old = tokens.clone();
        let mut next = self.record.clone();
        next.in_flight = Some(digest.clone());
        self.save(next).await?;
        let secret = self
            .hub
            .secret(&self.account, "client_secret")
            .ok_or(Error::SetupRequired)?;
        let result = client::refresh(
            self.transport.as_ref(),
            &old,
            self.policy.client_id(),
            &secret,
            now / 1000,
        )
        .await;
        match result {
            Ok(tokens) => {
                let mut next = self.record.clone();
                next.tokens = Some(tokens);
                next.previous = Some(digest);
                next.in_flight = None;
                next.retry_at = 0;
                self.save(next).await?;
                self.current(now)
            }
            Err(client::Failure::RateLimited(seconds)) => {
                let seconds = seconds.clamp(60, 7 * 86400);
                let mut next = self.record.clone();
                next.in_flight = None;
                next.retry_at = now.saturating_add(seconds * 1000);
                self.save(next).await?;
                Err(Error::RateLimited(seconds))
            }
            Err(client::Failure::Network | client::Failure::InvalidResponse) => {
                Err(Error::Ambiguous)
            }
            Err(_) => Err(Error::Denied),
        }
    }
    /// Cancel only this pending flow; preserve any previously connected account.
    ///
    /// # Errors
    /// Refuses another device/ticket or a failed durable write.
    pub async fn cancel(&mut self, key: &str, ticket: &str) -> Result<()> {
        self.authenticate(key)?;
        self.retry_save().await?;
        if !self
            .record
            .flow
            .as_ref()
            .is_some_and(|flow| same(&flow.ticket, ticket))
        {
            return Err(Error::State);
        }
        let mut next = self.record.clone();
        next.flow = None;
        self.save(next).await
    }
    /// Forget only this account's local record, retaining application/device
    /// credentials and configuration. This does not revoke at Netatmo.
    ///
    /// # Errors
    /// Refuses another device or a failed durable write.
    pub async fn forget(&mut self, key: &str) -> Result<()> {
        self.authenticate(key)?;
        // Discard an unsaved reply before forgetting so it cannot be resurrected.
        self.unsaved = None;
        self.save(Record::empty(self.record.binding.clone())).await
    }
}

/// In-process adapter for a Moli Linux confidential host and synthetic tests.
/// It is deliberately unavailable on Android; a remote HTTPS adapter is still
/// required for an autonomous Android engine. No listener is started here.
pub struct LocalBroker {
    service: tokio::sync::Mutex<Service>,
    policy: Policy,
    device_key: String,
}
impl fmt::Debug for LocalBroker {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("LocalOAuthBroker(<redacted>)")
    }
}
impl LocalBroker {
    /// Consume the sole account owner. Authenticate before accepting the key.
    ///
    /// # Errors
    /// Refuses an unprovisioned or foreign device credential.
    pub fn new(service: Service, device_key: String) -> Result<Self> {
        service.authenticate(&device_key)?;
        Ok(Self {
            policy: service.policy.clone(),
            service: tokio::sync::Mutex::new(service),
            device_key,
        })
    }
    /// Receive a validated provider redirect through the future host's handler.
    ///
    /// # Errors
    /// See `Service::callback`.
    pub async fn callback(&self, callback: &str, now: u64) -> Result<()> {
        self.service.lock().await.callback(callback, now).await
    }
}
impl Broker for LocalBroker {
    fn policy(&self) -> Option<&Policy> {
        Some(&self.policy)
    }
    fn begin<'a>(
        &'a self,
        state: &'a str,
        challenge: &'a str,
    ) -> moli_runtime::BoxFuture<'a, Result<String>> {
        Box::pin(async move {
            self.service
                .lock()
                .await
                .begin(&self.device_key, state, challenge, moli_core::now_ms())
                .await
        })
    }
    fn poll<'a>(
        &'a self,
        ticket: &'a str,
        verifier: &'a str,
    ) -> moli_runtime::BoxFuture<'a, Result<Reply>> {
        Box::pin(async move {
            self.service
                .lock()
                .await
                .poll(&self.device_key, ticket, verifier, moli_core::now_ms())
                .await
        })
    }
    fn cancel<'a>(&'a self, ticket: &'a str) -> moli_runtime::BoxFuture<'a, Result<()>> {
        Box::pin(async move {
            self.service
                .lock()
                .await
                .cancel(&self.device_key, ticket)
                .await
        })
    }
    fn renew<'a>(
        &'a self,
        instance: &'a str,
        previous: &'a Grant,
        now: u64,
    ) -> moli_runtime::BoxFuture<'a, Result<Grant>> {
        Box::pin(async move {
            self.service
                .lock()
                .await
                .renew(&self.device_key, instance, previous, now)
                .await
        })
    }
}

#[cfg(test)]
mod tests;
