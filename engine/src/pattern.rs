//! Pattern loading and compilation.
//!
//! Patterns are described in a JSON file shared with the Java stage. Only the
//! `regex` is used here; the `validator` string is carried through untouched so
//! the downstream stage can decide how to confirm a candidate match.

use regex::Regex;
use serde::Deserialize;
use std::fs;
use std::path::Path;

/// A single pattern definition as it appears in `patterns.json`.
#[derive(Deserialize, Debug, Clone)]
pub struct PatternDef {
    /// Stable identifier, e.g. `"KR_RRN"`.
    pub id: String,
    /// Human-readable name, e.g. `"Korean resident registration number"`.
    #[serde(default)]
    pub name: String,
    /// The regular expression used to locate candidates.
    pub regex: String,
    /// Hint telling the downstream stage which checksum to apply.
    /// `"none"` (or empty) means the regex match is accepted as-is.
    #[serde(default)]
    pub validator: String,
}

/// A pattern whose regex has been compiled and is ready to match.
pub struct CompiledPattern {
    pub id: String,
    pub name: String,
    pub validator: String,
    pub regex: Regex,
}

/// Read and compile every pattern in `path`.
///
/// Fails fast if the file cannot be read, the JSON is malformed, or any
/// individual regex fails to compile — a broken pattern set should never be
/// silently ignored.
pub fn load_patterns(path: &Path) -> Result<Vec<CompiledPattern>, String> {
    let raw = fs::read_to_string(path)
        .map_err(|e| format!("cannot read patterns file {}: {}", path.display(), e))?;

    let defs: Vec<PatternDef> = serde_json::from_str(&raw)
        .map_err(|e| format!("cannot parse patterns JSON: {}", e))?;

    if defs.is_empty() {
        return Err("patterns file contains no patterns".to_string());
    }

    let mut compiled = Vec::with_capacity(defs.len());
    for def in defs {
        let regex = Regex::new(&def.regex).map_err(|e| {
            format!("invalid regex for pattern '{}' ({}): {}", def.id, def.regex, e)
        })?;
        compiled.push(CompiledPattern {
            id: def.id,
            name: def.name,
            validator: def.validator,
            regex,
        });
    }
    Ok(compiled)
}

/// Return the first pattern that matches somewhere inside `value`.
///
/// Patterns are tested in file order, so more specific patterns should be
/// listed first. Returns the matching pattern together with the exact matched
/// substring.
pub fn first_match<'a, 'p>(
    patterns: &'p [CompiledPattern],
    value: &'a str,
) -> Option<(&'p CompiledPattern, &'a str)> {
    for p in patterns {
        if let Some(m) = p.regex.find(value) {
            return Some((p, m.as_str()));
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::NamedTempFile;

    fn sample_file() -> NamedTempFile {
        let mut f = NamedTempFile::new().unwrap();
        let body = r#"[
            {"id":"KR_RRN","name":"resident","regex":"\\d{6}-?[1-4]\\d{6}","validator":"kr_rrn"},
            {"id":"CARD","name":"card","regex":"\\d{4}-?\\d{4}-?\\d{4}-?\\d{4}","validator":"luhn"},
            {"id":"EMAIL","name":"email","regex":"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"}
        ]"#;
        f.write_all(body.as_bytes()).unwrap();
        f
    }

    #[test]
    fn loads_all_patterns_in_order() {
        let f = sample_file();
        let ps = load_patterns(f.path()).unwrap();
        assert_eq!(ps.len(), 3);
        assert_eq!(ps[0].id, "KR_RRN");
        assert_eq!(ps[0].validator, "kr_rrn");
        assert_eq!(ps[2].validator, ""); // defaulted
    }

    #[test]
    fn matches_first_pattern_by_order() {
        let f = sample_file();
        let ps = load_patterns(f.path()).unwrap();
        let (p, m) = first_match(&ps, "my id is 901231-1234567 ok").unwrap();
        assert_eq!(p.id, "KR_RRN");
        assert_eq!(m, "901231-1234567");
    }

    #[test]
    fn returns_none_when_nothing_matches() {
        let f = sample_file();
        let ps = load_patterns(f.path()).unwrap();
        assert!(first_match(&ps, "just some words").is_none());
    }

    #[test]
    fn rejects_empty_pattern_set() {
        let mut f = NamedTempFile::new().unwrap();
        f.write_all(b"[]").unwrap();
        assert!(load_patterns(f.path()).is_err());
    }

    #[test]
    fn rejects_invalid_regex() {
        let mut f = NamedTempFile::new().unwrap();
        f.write_all(br#"[{"id":"BAD","regex":"("}]"#).unwrap();
        assert!(load_patterns(f.path()).is_err());
    }
}
