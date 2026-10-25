use super::*;
use moli_netatmo::oauth::{Grant, Policy, Reply};
use moli_runtime::{BoxFuture, read_secret};
struct Fixture {
    root: PathBuf,
    key: MasterKey,
}
impl Fixture {
    fn new() -> Self {
        static N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let root = std::env::temp_dir().join(format!(
            "moli-oauth-{}-{}",
            std::process::id(),
            N.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
        ));
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("moli.toml"),"[[driver]]\nid='energy-test'\nkind='netatmo'\nenabled=false\n[driver.options]\nhome_id='home-test'\n").unwrap();
        std::fs::write(
            root.join("home.json"),
            "{\"favorites\":[\"stable-device\"]}",
        )
        .unwrap();
        std::fs::write(
            root.join("android-equipment.json"),
            r#"{"meross":[{"id":"stable-plug","uuid":"00000000000000000000000000000001"}]}"#,
        )
        .unwrap();
        std::fs::write(root.join("labels.toml"), "# synthetic existing labels\n").unwrap();
        std::fs::write(
            root.join("journal.jsonl"),
            "{\"event\":\"synthetic-existing\"}\n",
        )
        .unwrap();
        Self {
            root,
            key: MasterKey::new("33".repeat(32)),
        }
    }
    fn request(&self, action: &str) -> Value {
        json!({"data":self.root,"master":"33".repeat(32),"instance":"energy-test","action":action,"consent":true})
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        std::fs::remove_dir_all(&self.root).unwrap();
    }
}
struct Mock {
    policy: Policy,
}
impl Broker for Mock {
    fn policy(&self) -> Option<&Policy> {
        Some(&self.policy)
    }
    fn begin<'a>(
        &'a self,
        _: &'a str,
        _: &'a str,
    ) -> BoxFuture<'a, moli_netatmo::oauth::Result<String>> {
        Box::pin(async { Ok("a".repeat(64)) })
    }
    fn poll<'a>(
        &'a self,
        _: &'a str,
        _: &'a str,
    ) -> BoxFuture<'a, moli_netatmo::oauth::Result<Reply>> {
        Box::pin(async {
            Ok(Reply::Approved(Grant::from_broker(&json!({"access_token":"synthetic-access","refresh_token":"synthetic-refresh","expires_at":now()/1000+10800,"scope":SCOPE}),now()).unwrap()))
        })
    }
    fn cancel<'a>(&'a self, _: &'a str) -> BoxFuture<'a, moli_netatmo::oauth::Result<()>> {
        Box::pin(async { Ok(()) })
    }
}
#[test]
fn unconfigured_client_never_starts_a_connection_or_accepts_tokens_redirects_or_client_secrets() {
    let f = Fixture::new();
    let snapshot = run(&f.request("status")).unwrap();
    assert_eq!(snapshot["setup_required"], true);
    assert_eq!(snapshot["credential_present"], false);
    assert_eq!(run(&f.request("begin")), Err("oauth_setup"));
    for field in [
        "client_secret",
        "access_token",
        "refresh_token",
        "redirect_uri",
        "url",
    ] {
        let mut input = f.request("begin");
        input[field] = json!("synthetic-private-value");
        assert_eq!(run(&input), Err("input"));
    }
    let mut forged = f.request("disconnect");
    forged["instance"] = json!("other-driver");
    assert_eq!(run(&forged), Err("input"));
    assert!(!f.root.join("secrets.enc").exists());
}
#[tokio::test]
async fn approved_pair_is_encrypted_without_changing_settings_labels_or_other_associations() {
    let f = Fixture::new();
    let mut client = Client::default();
    let broker = Mock {
        policy: Policy::new("test-client", "https://oauth.example.com/return").unwrap(),
    };
    let config = std::fs::read(f.root.join("moli.toml")).unwrap();
    let home = std::fs::read(f.root.join("home.json")).unwrap();
    set_secret(
        &f.root.join("secrets.enc"),
        &f.key,
        "other-driver/key",
        "synthetic-other-key",
    )
    .await
    .unwrap();
    operate(
        &f.request("begin"),
        &f.root,
        "energy-test",
        &f.key,
        &mut client,
        &broker,
    )
    .await
    .unwrap();
    let result = operate(
        &f.request("check"),
        &f.root,
        "energy-test",
        &f.key,
        &mut client,
        &broker,
    )
    .await
    .unwrap();
    assert_eq!(result["state"], "stored");
    assert_eq!(result["credential_present"], true);
    let public = result.to_string();
    for value in [
        "synthetic-access",
        "synthetic-refresh",
        "master",
        "client_secret",
    ] {
        assert!(!public.contains(value));
    }
    let raw = std::fs::read(f.root.join("secrets.enc")).unwrap();
    assert!(!String::from_utf8_lossy(&raw).contains("synthetic-access"));
    let mut reopened = Client::default();
    let status = snapshot(&f.root, "energy-test", &f.key, &mut reopened, &broker).unwrap();
    assert_eq!(status["credential_present"], true);
    assert_eq!(status["state"], "idle");
    let disconnected = operate(
        &f.request("disconnect"),
        &f.root,
        "energy-test",
        &f.key,
        &mut reopened,
        &broker,
    )
    .await
    .unwrap();
    assert_eq!(disconnected["credential_present"], false);
    assert_eq!(disconnected["remote_revocation_required"], true);
    assert_eq!(
        read_secret(&f.root.join("secrets.enc"), &f.key, "other-driver/key")
            .unwrap()
            .as_deref(),
        Some("synthetic-other-key")
    );
    assert_eq!(std::fs::read(f.root.join("moli.toml")).unwrap(), config);
    assert_eq!(std::fs::read(f.root.join("home.json")).unwrap(), home);
}

#[test]
fn a_pending_configuration_transaction_blocks_oauth_without_touching_its_journal() {
    let f = Fixture::new();
    let journal = f.root.join("android-configuration.undo.json");
    std::fs::write(&journal, b"synthetic-pending-transaction").unwrap();
    assert_eq!(run(&f.request("status")), Err("configuration_storage"));
    assert_eq!(
        std::fs::read(journal).unwrap(),
        b"synthetic-pending-transaction"
    );
    assert!(!f.root.join("secrets.enc").exists());
}
#[tokio::test]
async fn failed_atomic_save_retains_previous_credentials_and_allows_the_same_pair_to_retry() {
    let f = Fixture::new();
    let mut client = Client::default();
    let broker = Mock {
        policy: Policy::new("test-client", "https://oauth.example.com/return").unwrap(),
    };
    set_secret(
        &f.root.join("secrets.enc"),
        &f.key,
        "energy-test/oauth_tokens",
        "synthetic-old-bundle",
    )
    .await
    .unwrap();
    let old = std::fs::read(f.root.join("secrets.enc")).unwrap();
    let files = [
        "moli.toml",
        "home.json",
        "android-equipment.json",
        "labels.toml",
        "journal.jsonl",
    ];
    let before: Vec<_> = files
        .iter()
        .map(|name| std::fs::read(f.root.join(name)).unwrap())
        .collect();
    operate(
        &f.request("begin"),
        &f.root,
        "energy-test",
        &f.key,
        &mut client,
        &broker,
    )
    .await
    .unwrap();
    std::fs::create_dir(f.root.join("secrets.enc.tmp")).unwrap();
    assert_eq!(
        operate(
            &f.request("check"),
            &f.root,
            "energy-test",
            &f.key,
            &mut client,
            &broker
        )
        .await,
        Err("storage")
    );
    assert_eq!(std::fs::read(f.root.join("secrets.enc")).unwrap(), old);
    assert_eq!(client.state(now()), State::Saving);
    std::fs::remove_dir(f.root.join("secrets.enc.tmp")).unwrap();
    let saved = operate(
        &f.request("check"),
        &f.root,
        "energy-test",
        &f.key,
        &mut client,
        &broker,
    )
    .await
    .unwrap();
    assert_eq!(saved["state"], "stored");
    for (file, bytes) in files.iter().zip(before) {
        assert_eq!(std::fs::read(f.root.join(file)).unwrap(), bytes);
    }
}
