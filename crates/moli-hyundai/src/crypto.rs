//! CCI/GSPA public protocol signing, ported from Hyundai-Kia-Connect API v4.45.0
//! (MIT; notice in data/). Constants are app protocol material, never account keys.
use base64::{
    Engine as _,
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
};
use ring::{
    hmac,
    rand::{SecureRandom, SystemRandom},
};
use serde_json::Value;
use std::sync::OnceLock;

fn parameters() -> &'static Value {
    static DATA: OnceLock<Value> = OnceLock::new();
    DATA.get_or_init(|| {
        serde_json::from_str(include_str!("../data/hyundai_cipher_params.json"))
            .expect("embedded public cipher parameters")
    })
}
fn word(v: &Value) -> u32 {
    v.as_u64()
        .and_then(|n| u32::try_from(n).ok())
        .unwrap_or_else(|| {
            u32::from_str_radix(v.as_str().unwrap_or("0").trim_start_matches("0x"), 16)
                .expect("cipher word")
        })
}
fn linear(columns: &Value, mut x: u32) -> u32 {
    let mut out = 0;
    let mut i = 0;
    while x != 0 {
        if x & 1 != 0 {
            out ^= word(&columns[i]);
        }
        x >>= 1;
        i += 1;
    }
    out
}
#[allow(clippy::many_single_char_names)] // Mathematical names mirror the reference affine cipher.
fn sub(group: &str, index: usize, x: u8) -> u32 {
    let p = parameters();
    let q = &p[group][index.to_string()];
    let a = linear(&q["M1"], u32::from(x) ^ word(&q["a"]));
    let s = word(&p["sbox"][usize::try_from(a).expect("byte")]);
    let b = linear(&q["M2"], s) ^ word(&q["t2"]);
    word(&q["c"])
        ^ if q["is_byte"].as_bool() == Some(true) {
            b & 255
        } else {
            linear(&q["Lcols"], b)
        }
}
fn block(mut state: [u8; 16]) -> [u8; 16] {
    const SHIFT: [usize; 16] = [0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11];
    for (sbox, lin, rounds) in [("X4", "X27", 8), ("GAP", "POST_X27", 5)] {
        for round in 0..rounds {
            let old = state;
            for i in 0..16 {
                state[i] = old[SHIFT[i]];
            }
            for col in 0..4 {
                let base = round * 16 + col * 4;
                let offset = col * 4;
                let mut inter = 0;
                for j in 0..4 {
                    inter ^= sub(sbox, base + j, state[offset + j]);
                }
                let bytes = inter.to_be_bytes();
                let mut out = 0;
                for (j, b) in bytes.iter().enumerate() {
                    let q = &parameters()[lin][(base + j).to_string()];
                    out ^= word(&q["lconst"]) ^ linear(&q["lcols"], u32::from(*b));
                }
                state[offset..offset + 4].copy_from_slice(&out.to_be_bytes());
            }
        }
    }
    let old = state;
    for i in 0..16 {
        state[i] = sub("X8", i, old[SHIFT[i]]).to_be_bytes()[3];
    }
    state
}
pub(crate) fn stamp(tsid: &str, seconds: u64, user: &str) -> String {
    let text = format!("{tsid}:{seconds}:{user}");
    let mut feedback = *b"iv.ccsp.stamp.eu";
    let mut encrypted = Vec::new();
    for chunk in text.as_bytes().chunks(16) {
        let key = block(feedback);
        for (i, b) in chunk.iter().enumerate() {
            feedback[i] = b ^ key[i];
            encrypted.push(feedback[i]);
        }
    }
    STANDARD.encode(encrypted)
}
pub(crate) fn random_hex(bytes: usize) -> Result<String, &'static str> {
    let mut data = vec![0; bytes];
    SystemRandom::new().fill(&mut data).map_err(|_| "random")?;
    Ok(hex(&data))
}
pub(crate) fn hex(data: &[u8]) -> String {
    use std::fmt::Write as _;
    data.iter().fold(String::new(), |mut out, b| {
        let _ = write!(out, "{b:02x}");
        out
    })
}
pub(crate) fn device_id() -> Result<String, &'static str> {
    let s = random_hex(16)?;
    Ok(format!(
        "{}-{}-4{}-a{}-{}",
        &s[..8],
        &s[8..12],
        &s[13..16],
        &s[17..20],
        &s[20..]
    ))
}
pub(crate) fn tsid(now_ms: u64, device: &str) -> Result<String, &'static str> {
    let id = device.replace('-', "");
    let node = (0..16)
        .step_by(2)
        .map(|i| u8::from_str_radix(id.get(i..i + 2).ok_or("device")?, 16).map_err(|_| "device"))
        .collect::<Result<Vec<_>, _>>()?;
    let mut out = now_ms.saturating_sub(1_577_836_800_000).to_be_bytes()[3..].to_vec();
    out.extend(node);
    out.push(6);
    Ok(STANDARD.encode(out).trim_end_matches('=').to_string())
}
pub(crate) fn fingerprint(timestamp: u64, body: &str) -> String {
    let identity = "com.hyundai.oneapp.euHyundaiEU1.4.0android";
    let key = format!("{identity}OsZDfDuCyVXyfqjCoA2JbX2MrLpGwNBC");
    let compact: String = body
        .chars()
        .filter(|c| !matches!(c, ' ' | '\t' | '\n' | '\r' | '\x0b' | '\x0c'))
        .collect();
    hex(hmac::sign(
        &hmac::Key::new(hmac::HMAC_SHA256, key.as_bytes()),
        format!("{identity}14{timestamp}{compact}").as_bytes(),
    )
    .as_ref())
}
/// RSA public-key encryption only; no private RSA key or decryption surface.
pub(crate) fn encrypt_password(n: &str, e: &str, password: &str) -> Result<String, &'static str> {
    use num_bigint::BigUint;
    let modulus = URL_SAFE_NO_PAD
        .decode(n.trim_end_matches('='))
        .map_err(|_| "certificate")?;
    if !(256..=512).contains(&modulus.len())
        || modulus[0] < 128
        || modulus[modulus.len() - 1] & 1 == 0
        || password.len() + 11 > modulus.len()
    {
        return Err("certificate");
    }
    let exponent = URL_SAFE_NO_PAD
        .decode(e.trim_end_matches('='))
        .map_err(|_| "certificate")?;
    if exponent != [1, 0, 1] {
        return Err("certificate");
    }
    let mut message = vec![0; modulus.len()];
    message[1] = 2;
    let random = SystemRandom::new();
    let padding = modulus.len() - password.len() - 3;
    for byte in &mut message[2..2 + padding] {
        while *byte == 0 {
            random
                .fill(std::slice::from_mut(byte))
                .map_err(|_| "random")?;
        }
    }
    message[3 + padding..].copy_from_slice(password.as_bytes());
    let out = BigUint::from_bytes_be(&message)
        .modpow(
            &BigUint::from_bytes_be(&exponent),
            &BigUint::from_bytes_be(&modulus),
        )
        .to_bytes_be();
    let mut padded = vec![0; modulus.len() - out.len()];
    padded.extend(out);
    Ok(hex(&padded))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn public_cipher_matches_reference_vectors() {
        let vectors: Value =
            serde_json::from_str(include_str!("../data/cipher-vectors.json")).unwrap();
        for v in vectors.as_array().unwrap() {
            assert_eq!(
                stamp(
                    v["tsid"].as_str().unwrap(),
                    v["seconds"].as_u64().unwrap(),
                    v["user"].as_str().unwrap()
                ),
                v["stamp"].as_str().unwrap()
            );
        }
    }
    #[test]
    fn timestamps_and_fingerprints_match() {
        assert_eq!(
            fingerprint(1_800_000_000_000, "{}"),
            include_str!("../data/fingerprint-vector.txt").trim()
        );
        assert_eq!(
            tsid(1_800_000_000_000, "00000000-0000-0000-0000-000000000000")
                .unwrap()
                .len(),
            19
        );
    }
}
