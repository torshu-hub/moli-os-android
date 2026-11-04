//! Exercises the installed-style operator in separate processes, with a synthetic vault.
use serde_json::{Value, json};
use std::{
    io::Write,
    path::Path,
    process::{Command, Stdio},
};
fn invoke(data: &Path, request: &Value) -> Value {
    let mut child = Command::new(env!("CARGO_BIN_EXE_moli-setup"))
        .arg("--engine-stopped")
        .env("MOLI_MASTER_KEY", "22".repeat(32))
        .env("MOLI_DATA_DIR", data)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    writeln!(child.stdin.take().unwrap(), "{request}").unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(output.status.success());
    assert_eq!(output.stderr, [] as [u8; 0]);
    serde_json::from_slice(&output.stdout).unwrap()
}
#[test]
fn portable_operator_resumes_private_setup_and_preserves_existing_vault_and_config() {
    use moli_runtime::{MasterKey, read_secret, set_secret};
    let dir = std::env::temp_dir().join(format!("moli-operator-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let original = "# preserved\n[[driver]]\nid='energy'\nkind='netatmo'\nenabled=false\n[driver.options]\nhome_id='home-test'\n";
    std::fs::write(dir.join("moli.toml"), original).unwrap();
    let key = MasterKey::new("22".repeat(32));
    let path = dir.join("secrets.enc");
    let application =
        moli_netatmo::oauth::personal::Application::new("app-test", "synthetic-secret", true)
            .unwrap();
    let runtime = tokio::runtime::Runtime::new().unwrap();
    runtime.block_on(async {
        for (name, value) in [
            ("unrelated/secret", "synthetic-preserved".into()),
            ("meross-account/session", "synthetic-session".into()),
            ("energy/oauth_personal_application", application.bundle().unwrap()),
            ("energy/oauth_tokens", json!({"access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_at":moli_core::now_ms()/1000+3600,"write":false}).to_string()),
        ] { set_secret(&path,&key,name,&value).await.unwrap(); }
    });
    let energy = invoke(
        &dir,
        &json!({"action":"netatmo","operation":"status","instance":"energy"}),
    );
    assert_eq!(energy["credential_present"], true);
    assert_eq!(energy["selected_home"], "home-test");
    assert!(!energy.to_string().contains("synthetic-secret"));
    assert!(!energy.to_string().contains("synthetic-refresh"));
    assert_eq!(
        invoke(&dir, &json!({"action":"meross","operation":"status"}))["connected"],
        true
    );
    let mut login =
        json!({"action":"hyundai","operation":"begin_login","api_mode":"gspa","consent":false});
    assert_eq!(invoke(&dir, &login)["error"], "setup_not_confirmed");
    login["consent"] = json!(true);
    assert!(invoke(&dir, &login)["browser_login"]["authorization_url"].is_string());
    assert!(
        read_secret(&path, &key, "hyundai-eu/browser_login")
            .unwrap()
            .is_some()
    );
    // A separate process restores the persisted ticket and rejects the wrong state offline.
    login["operation"] = json!("complete_login");
    login["callback"] =
        json!("https://oneapp.hyundai.com/redirect?code=synthetic-code&state=synthetic-wrong");
    let result = invoke(&dir, &login);
    assert_eq!(result["diagnostic"]["cause"], "callback_invalid_or_expired");
    assert!(!result.to_string().contains("synthetic-code"));
    assert!(
        read_secret(&path, &key, "hyundai-eu/browser_login")
            .unwrap()
            .is_some()
    );
    let status = invoke(&dir, &json!({"action":"hyundai","operation":"status"}));
    assert_eq!(status["diagnostic"]["cause"], "callback_invalid_or_expired");
    assert_eq!(
        std::fs::read_to_string(dir.join("moli.toml")).unwrap(),
        original
    );
    assert_eq!(
        read_secret(&path, &key, "unrelated/secret")
            .unwrap()
            .as_deref(),
        Some("synthetic-preserved")
    );
    assert_eq!(invoke(&dir, &json!([]))["error"], "input");
    login["operation"] = json!("cancel_login");
    invoke(&dir, &login);
    assert!(
        read_secret(&path, &key, "hyundai-eu/browser_login")
            .unwrap()
            .is_none()
    );
    std::fs::remove_dir_all(dir).unwrap();
}
