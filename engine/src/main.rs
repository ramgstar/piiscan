//! Command-line entry point for the matching engine.
//!
//! Usage:
//! ```text
//! piiscan-engine --patterns patterns.json --input batch.jsonl --output findings.jsonl
//! ```
//!
//! Exit codes are stable so the Java stage can distinguish failure modes:
//! * 0 — success
//! * 1 — input read failure
//! * 2 — pattern load/compile failure
//! * 3 — output write failure

use clap::Parser;
use piiscan_engine::{pattern, scanner};
use std::fs::File;
use std::io::{BufReader, BufWriter};
use std::path::PathBuf;
use std::process::ExitCode;

#[derive(Parser, Debug)]
#[command(name = "piiscan-engine", version, about = "Regex matching stage of the piiscan pipeline")]
struct Args {
    /// Pattern definitions (shared JSON file).
    #[arg(long)]
    patterns: PathBuf,

    /// Input JSONL file of `{ "value", "count" }` records.
    #[arg(long)]
    input: PathBuf,

    /// Output JSONL file of candidate findings.
    #[arg(long)]
    output: PathBuf,

    /// Print a one-line summary to stderr when finished.
    #[arg(long)]
    stats: bool,
}

fn run(args: &Args) -> Result<scanner::ScanStats, (String, u8)> {
    let patterns = pattern::load_patterns(&args.patterns).map_err(|e| (e, 2))?;

    let input = File::open(&args.input)
        .map_err(|e| (format!("cannot open input {}: {}", args.input.display(), e), 1))?;
    let reader = BufReader::new(input);

    let output = File::create(&args.output)
        .map_err(|e| (format!("cannot create output {}: {}", args.output.display(), e), 3))?;
    let mut writer = BufWriter::new(output);

    scanner::scan(reader, &mut writer, &patterns).map_err(|e| (e, 1))
}

fn main() -> ExitCode {
    let args = Args::parse();
    match run(&args) {
        Ok(stats) => {
            if args.stats {
                eprintln!(
                    "read={} findings={}",
                    stats.records_read, stats.findings
                );
            }
            ExitCode::from(0)
        }
        Err((msg, code)) => {
            eprintln!("error: {}", msg);
            ExitCode::from(code)
        }
    }
}
