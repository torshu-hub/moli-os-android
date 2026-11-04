//! Private, offline operator adapter. One JSON request per line, no HTTP service.
use serde_json::{Value, json};
use std::io::{self, BufRead, Read, Write};
fn main() {
    if std::env::args().nth(1).as_deref() != Some("--engine-stopped") {
        eprintln!(
            "Stop the Moli engine, then use --engine-stopped. Private JSON lines on stdin; MOLI_MASTER_KEY and MOLI_DATA_DIR select the existing vault. Configuration candidates are returned, never installed automatically."
        );
        std::process::exit(2);
    }
    let Ok(master) = std::env::var("MOLI_MASTER_KEY") else {
        eprintln!("Master key unavailable");
        std::process::exit(2)
    };
    let Ok(data) = std::env::var("MOLI_DATA_DIR") else {
        eprintln!("Data directory unavailable");
        std::process::exit(2)
    };
    let mut input = io::stdin().lock();
    let mut output = io::stdout().lock();
    loop {
        let mut line = Vec::new();
        let read = input.by_ref().take(65_537).read_until(b'\n', &mut line);
        if matches!(read, Ok(0)) {
            break;
        }
        if read.is_err() || line.len() > 65_536 {
            eprintln!("Request exceeds limit or cannot be read");
            std::process::exit(2);
        }
        let result = serde_json::from_slice::<Value>(&line)
            .map_err(|_| "input")
            .and_then(|mut request| {
                if !request.is_object() {
                    return Err("input");
                }
                request["master"] = json!(master);
                request["data"] = json!(data);
                match request["action"].as_str() {
                    Some("hyundai") => moli_setup::hyundai::run(&request),
                    Some("netatmo") => moli_setup::netatmo::run(&request),
                    Some("meross") => moli_setup::meross::run(&request),
                    _ => Err("input"),
                }
            });
        let response = result.unwrap_or_else(|code| json!({"error":code}));
        if serde_json::to_writer(&mut output, &response).is_err()
            || writeln!(output).is_err()
            || output.flush().is_err()
        {
            break;
        }
    }
}
