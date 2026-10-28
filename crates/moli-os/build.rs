use std::{env, fmt::Write as _, fs, path::PathBuf};

fn main() {
    let root = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap()).join("../../integrations");
    println!("cargo:rerun-if-changed={}", root.display());
    let mut manifests: Vec<_> = fs::read_dir(root)
        .unwrap()
        .map(|e| e.unwrap().path().join("integration.toml"))
        .filter(|p| p.is_file())
        .collect();
    manifests.sort();
    let mut source = String::from("&[\n");
    for path in manifests {
        let path = path.to_str().expect("UTF-8 integration path");
        writeln!(source, "include_str!({path:?}),").unwrap();
    }
    source.push(']');
    fs::write(
        PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("integration_manifests.rs"),
        source,
    )
    .unwrap();
}
