//! Embed the actual official packages, never a separately maintained catalogue.
use std::{env, fmt::Write as _, fs, path::PathBuf};

fn main() -> std::io::Result<()> {
    let root = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap())
        .join("../../integrations")
        .canonicalize()?;
    println!("cargo:rerun-if-changed={}", root.display());
    let mut directories: Vec<_> = fs::read_dir(&root)?
        .map(|entry| entry.map(|entry| entry.path()))
        .collect::<Result<_, _>>()?;
    directories.sort();
    let mut index = String::from("const PACKAGES: &[(&str, &str, &str)] = &[\n");
    for directory in directories {
        let manifest = directory.join("integration.toml");
        if !manifest.is_file() {
            continue;
        }
        let onboarding = directory.join("onboarding.md");
        println!("cargo:rerun-if-changed={}", manifest.display());
        println!("cargo:rerun-if-changed={}", onboarding.display());
        writeln!(
            index,
            "({:?}, include_str!({:?}), include_str!({:?})),",
            directory.file_name().unwrap().to_str().unwrap(),
            manifest.to_str().unwrap(),
            onboarding.to_str().unwrap()
        )
        .unwrap();
    }
    index.push_str("];\n");
    fs::write(
        PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("catalogue.rs"),
        index,
    )
}
