//! JSONL record types exchanged with the Java stage.
//!
//! Input lines describe a distinct column value and how many times it occurred
//! (`count`), so the engine can work on a de-duplicated frequency table rather
//! than every raw row. Output lines describe a candidate finding.

use serde::{Deserialize, Serialize};

/// One input value to scan.
///
/// The producer collapses identical values and records their frequency, so a
/// value seen 10_000 times is matched once and carries `count: 10000`.
#[derive(Deserialize, Debug, Clone)]
pub struct InputRecord {
    /// The raw column value.
    pub value: String,
    /// How many rows held this value. Defaults to 1 when omitted.
    #[serde(default = "one")]
    pub count: u64,
}

fn one() -> u64 {
    1
}

/// A candidate finding emitted for a value that matched a pattern.
///
/// This is only a *candidate*: the downstream stage still applies the
/// `validator` (checksum) before treating it as a confirmed hit.
#[derive(Serialize, Debug, Clone, PartialEq)]
pub struct Finding {
    /// Id of the pattern that matched.
    pub pattern_id: String,
    /// Validator hint carried over from the pattern definition.
    pub validator: String,
    /// The exact substring that matched.
    pub matched: String,
    /// The full original value.
    pub value: String,
    /// Frequency carried over from the input record.
    pub count: u64,
}

/// True when a line is a metadata/header line rather than a value record.
///
/// The producer may prepend a single object carrying a `"_meta"` key (batch id,
/// source column, etc.). Such lines are skipped by the matcher.
pub fn is_meta_line(line: &str) -> bool {
    line.contains("\"_meta\"")
}

/// Parse one input JSONL line into an [`InputRecord`].
pub fn parse_input(line: &str) -> Result<InputRecord, String> {
    serde_json::from_str(line).map_err(|e| format!("bad input line ({}): {}", e, line))
}

/// Serialize a [`Finding`] to a single JSON line (no trailing newline).
pub fn finding_to_json(f: &Finding) -> String {
    // Field renaming is done here rather than with serde attributes so the wire
    // format stays compact and explicit regardless of Rust field names.
    serde_json::json!({
        "patternId": f.pattern_id,
        "validator": f.validator,
        "matched": f.matched,
        "value": f.value,
        "count": f.count,
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_meta_lines() {
        assert!(is_meta_line(r#"{"_meta":{"batch":1},"column":"PHONE"}"#));
        assert!(!is_meta_line(r#"{"value":"x","count":1}"#));
    }

    #[test]
    fn parses_value_and_count() {
        let r = parse_input(r#"{"value":"901231-1234567","count":3}"#).unwrap();
        assert_eq!(r.value, "901231-1234567");
        assert_eq!(r.count, 3);
    }

    #[test]
    fn count_defaults_to_one() {
        let r = parse_input(r#"{"value":"solo"}"#).unwrap();
        assert_eq!(r.count, 1);
    }

    #[test]
    fn preserves_special_characters() {
        let r = parse_input(r#"{"value":"has \"quotes\" and \\ slash","count":2}"#).unwrap();
        assert_eq!(r.value, "has \"quotes\" and \\ slash");
    }

    #[test]
    fn finding_round_trips_as_valid_json() {
        let f = Finding {
            pattern_id: "CARD".into(),
            validator: "luhn".into(),
            matched: "1234-5678-9012-3456".into(),
            value: "card: 1234-5678-9012-3456 \"x\"".into(),
            count: 7,
        };
        let line = finding_to_json(&f);
        let v: serde_json::Value = serde_json::from_str(&line).unwrap();
        assert_eq!(v["patternId"], "CARD");
        assert_eq!(v["validator"], "luhn");
        assert_eq!(v["count"], 7);
        assert_eq!(v["value"], "card: 1234-5678-9012-3456 \"x\"");
    }
}
