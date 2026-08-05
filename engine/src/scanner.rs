//! The streaming scan loop.
//!
//! Reads input line by line so memory use stays flat regardless of how large
//! the batch is, and writes one finding per matched value.

use crate::pattern::{first_match, CompiledPattern};
use crate::record::{finding_to_json, is_meta_line, parse_input, Finding};
use std::io::{BufRead, Write};

/// Summary statistics returned once a batch has been fully scanned.
#[derive(Debug, Default, PartialEq)]
pub struct ScanStats {
    pub records_read: u64,
    pub findings: u64,
}

/// Scan every record from `reader`, writing candidate findings to `writer`.
///
/// Empty and metadata lines are skipped. A malformed value line aborts the
/// scan with an error rather than being silently dropped.
pub fn scan<R: BufRead, W: Write>(
    reader: R,
    writer: &mut W,
    patterns: &[CompiledPattern],
) -> Result<ScanStats, String> {
    let mut stats = ScanStats::default();

    for line in reader.lines() {
        let line = line.map_err(|e| format!("read error: {}", e))?;
        let trimmed = line.trim();
        if trimmed.is_empty() || is_meta_line(trimmed) {
            continue;
        }

        let record = parse_input(trimmed)?;
        stats.records_read += 1;

        if let Some((pattern, matched)) = first_match(patterns, &record.value) {
            let finding = Finding {
                pattern_id: pattern.id.clone(),
                validator: pattern.validator.clone(),
                matched: matched.to_string(),
                value: record.value,
                count: record.count,
            };
            writeln!(writer, "{}", finding_to_json(&finding))
                .map_err(|e| format!("write error: {}", e))?;
            stats.findings += 1;
        }
    }

    writer.flush().map_err(|e| format!("flush error: {}", e))?;
    Ok(stats)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pattern::load_patterns;
    use std::io::{BufReader, Write};
    use tempfile::NamedTempFile;

    fn patterns() -> Vec<CompiledPattern> {
        let mut f = NamedTempFile::new().unwrap();
        let body = r#"[
            {"id":"KR_RRN","regex":"\\d{6}-?[1-4]\\d{6}","validator":"kr_rrn"},
            {"id":"CARD","regex":"\\d{4}-?\\d{4}-?\\d{4}-?\\d{4}","validator":"luhn"},
            {"id":"EMAIL","regex":"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}","validator":"none"}
        ]"#;
        f.write_all(body.as_bytes()).unwrap();
        load_patterns(f.path()).unwrap()
    }

    fn run(input: &str) -> (String, ScanStats) {
        let ps = patterns();
        let reader = BufReader::new(input.as_bytes());
        let mut out = Vec::new();
        let stats = scan(reader, &mut out, &ps).unwrap();
        (String::from_utf8(out).unwrap(), stats)
    }

    #[test]
    fn skips_blank_and_meta_lines() {
        let input = concat!(
            "{\"_meta\":{\"batch\":1},\"column\":\"C\"}\n",
            "\n",
            "{\"value\":\"just words\",\"count\":1}\n"
        );
        let (out, stats) = run(input);
        assert_eq!(stats.records_read, 1);
        assert_eq!(stats.findings, 0);
        assert!(out.is_empty());
    }

    #[test]
    fn emits_one_finding_per_match_with_first_pattern() {
        let input = concat!(
            "{\"value\":\"901231-1234567\",\"count\":3}\n",
            "{\"value\":\"1234-5678-9012-3456\",\"count\":2}\n",
            "{\"value\":\"a@b.co\",\"count\":5}\n",
            "{\"value\":\"nothing\",\"count\":9}\n"
        );
        let (out, stats) = run(input);
        assert_eq!(stats.records_read, 4);
        assert_eq!(stats.findings, 3);
        let lines: Vec<_> = out.lines().collect();
        assert!(lines[0].contains("\"patternId\":\"KR_RRN\""));
        assert!(lines[0].contains("\"count\":3"));
        assert!(lines[1].contains("\"patternId\":\"CARD\""));
        assert!(lines[2].contains("\"patternId\":\"EMAIL\""));
    }

    #[test]
    fn malformed_line_is_an_error() {
        let ps = patterns();
        let reader = BufReader::new(&b"{not json}"[..]);
        let mut out = Vec::new();
        assert!(scan(reader, &mut out, &ps).is_err());
    }
}
