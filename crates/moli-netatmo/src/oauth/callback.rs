//! Exact callback validation, shared with the opt-in personal flow. Android
//! receives no redirect Intent; the personal flow requires the full pasted URL.
use super::{Error, Policy, Result, SESSION_MS, nonce};
use std::{collections::BTreeMap, fmt};

pub struct Code(String);
impl fmt::Debug for Code {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("AuthorizationCode(<redacted>)")
    }
}
impl Code {
    /// Intended solely for the Rust token exchange, never UI/status or logs.
    pub fn expose(&self) -> &str {
        &self.0
    }
}
/// A one-use server transaction; durable authenticated session storage is a
/// deployment requirement, not supplied by this in-memory building block.
pub struct CallbackSession {
    policy: Policy,
    state: String,
    created: u64,
    consumed: bool,
}
impl fmt::Debug for CallbackSession {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("CallbackSession")
            .field("consumed", &self.consumed)
            .finish_non_exhaustive()
    }
}
impl CallbackSession {
    pub fn new(policy: Policy, state: &str, now: u64) -> Result<Self> {
        if !nonce(state) {
            return Err(Error::State);
        }
        Ok(Self {
            policy,
            state: state.to_owned(),
            created: now,
            consumed: false,
        })
    }
    pub fn accept(&mut self, callback: &str, now: u64) -> Result<Code> {
        if self.consumed {
            return Err(Error::Replay);
        }
        if now < self.created || now - self.created >= SESSION_MS {
            self.consumed = true;
            return Err(Error::Expired);
        }
        if callback.len() > 4096 || callback.contains('#') {
            return Err(Error::Input);
        }
        let query = callback
            .strip_prefix(&format!("{}?", self.policy.redirect))
            .ok_or(Error::State)?;
        let fields = query_fields(query)?;
        if fields.get("state") != Some(&self.state) {
            return Err(Error::State);
        }
        if let Some(error) = fields.get("error") {
            if fields.contains_key("code") {
                return Err(Error::Input);
            }
            self.consumed = true;
            return Err(if error == "access_denied" {
                Error::Denied
            } else {
                Error::Input
            });
        }
        let code = fields
            .get("code")
            .filter(|s| !s.is_empty() && s.len() <= 2048)
            .ok_or(Error::Input)?;
        self.consumed = true;
        Ok(Code(code.clone()))
    }
}
fn decode(raw: &str) -> Result<String> {
    let mut bytes = Vec::new();
    let mut chars = raw.bytes();
    while let Some(b) = chars.next() {
        match b {
            b'%' => {
                let a = chars
                    .next()
                    .and_then(|c| char::from(c).to_digit(16))
                    .ok_or(Error::Input)?;
                let b = chars
                    .next()
                    .and_then(|c| char::from(c).to_digit(16))
                    .ok_or(Error::Input)?;
                bytes.push(u8::try_from(a * 16 + b).map_err(|_| Error::Input)?);
            }
            b'+' => bytes.push(b' '),
            other => bytes.push(other),
        }
    }
    let s = String::from_utf8(bytes).map_err(|_| Error::Input)?;
    if s.chars().any(char::is_control) {
        return Err(Error::Input);
    }
    Ok(s)
}
fn query_fields(query: &str) -> Result<BTreeMap<String, String>> {
    let mut fields = BTreeMap::new();
    for field in query.split('&') {
        let (key, value) = field.split_once('=').ok_or(Error::Input)?;
        let key = decode(key)?;
        if !matches!(
            key.as_str(),
            "state" | "code" | "error" | "error_description"
        ) || fields.insert(key, decode(value)?).is_some()
        {
            return Err(Error::Input);
        }
    }
    Ok(fields)
}
