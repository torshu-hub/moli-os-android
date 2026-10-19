//! Linux CLI; Android uses the library without Clap or process-wide signals.

fn main() -> anyhow::Result<()> {
    moli_os::run_cli()
}
