//! Narrow Java bridge for engine lifetime, profile onboarding, local equipment consent and explicit weather checks.
//! Inputs are copied through the jni crate; panics never unwind into the JVM.

mod discovery;
mod equipment;
mod profiles;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::{Mutex, OnceLock};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jint;
use moli_os::EmbeddedEngine;
use moli_runtime::MasterKey;

fn engine() -> &'static Mutex<Option<EmbeddedEngine>> {
    static ENGINE: OnceLock<Mutex<Option<EmbeddedEngine>>> = OnceLock::new();
    ENGINE.get_or_init(|| Mutex::new(None))
}

// SAFETY: these names and signatures exactly match NativeServer.java; the
// library is packaged in this APK, not loaded from user-supplied storage.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_moli_android_NativeServer_start(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    data: JString<'_>,
    key: JString<'_>,
    token: JString<'_>,
    pin: JString<'_>,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(
        || -> Result<jint, Box<dyn std::error::Error>> {
            let data: String = env.get_string(&data)?.into();
            let key: String = env.get_string(&key)?.into();
            let token: String = env.get_string(&token)?.into();
            let pin: String = env.get_string(&pin)?.into();
            let mut slot = engine()
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if slot.is_none() {
                *slot = Some(EmbeddedEngine::start(
                    Path::new(&data),
                    MasterKey::new(key),
                    &token,
                    &pin,
                )?);
            }
            Ok(i32::from(
                slot.as_ref().ok_or("server not started")?.address().port(),
            ))
        },
    ));
    if let Ok(Ok(port)) = result {
        port
    } else {
        // Never put credentials or an arbitrary panic payload in Java/logs.
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native server could not start",
        );
        0
    }
}

// SAFETY: matches the zero-argument native stop declaration in NativeServer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_moli_android_NativeServer_stop(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut slot = engine()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        slot.take().map_or(Ok(()), |mut running| running.stop())
    }));
    if !matches!(result, Ok(Ok(()))) {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Native server could not stop cleanly",
        );
    }
}

// SAFETY: private Java bridge; text is copied, all errors are bounded codes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_moli_android_NativeServer_profile(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    text: JString<'_>,
    operation: jint,
) -> jni::sys::jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let text: String = env.get_string(&text).map_err(|_| "input")?.into();
        if text.len() > 524_288 {
            return Err("size");
        }
        match operation {
            0 => profiles::review(&text),
            1 => profiles::configuration(&text).map(|v| serde_json::json!({"config":v})),
            2 => profiles::weather_probe(&text),
            3 => equipment::validate(&serde_json::from_str(&text).map_err(|_| "input")?),
            4 => {
                let slot = engine()
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                if slot.is_some() {
                    return Err("running");
                }
                equipment::store_keys(&serde_json::from_str(&text).map_err(|_| "input")?)
            }
            5 => discovery::review(&serde_json::from_str(&text).map_err(|_| "input")?),
            _ => Err("input"),
        }
    }));
    let reply = match result {
        Ok(Ok(value)) => value,
        Ok(Err(code)) => serde_json::json!({"error":code}),
        Err(_) => serde_json::json!({"error":"internal"}),
    };
    env.new_string(reply.to_string())
        .map_or(std::ptr::null_mut(), jni::objects::JString::into_raw)
}
