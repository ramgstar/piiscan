//! End-to-end test: run the public library API over a realistic batch and
//! assert the emitted findings, exercising the same code path the binary uses.

use piiscan_engine::{pattern, scanner};
use std::io::{BufReader, Write};
use tempfile::NamedTempFile;

fn write_temp(body: &str) -> NamedTempFile {
    let mut f = NamedTempFile::new().unwrap();
    f.write_all(body.as_bytes()).unwrap();
    f
}

#[test]
fn scans_a_mixed_batch_end_to_end() {
    let patterns_file = write_temp(
        r#"[
            {"id":"KR_RRN","name":"resident","regex":"\\d{6}-?[1-4]\\d{6}","validator":"kr_rrn"},
            {"id":"CARD","name":"card","regex":"\\d{4}-?\\d{4}-?\\d{4}-?\\d{4}","validator":"luhn"},
            {"id":"KR_BRN","name":"business","regex":"\\d{3}-?\\d{2}-?\\d{5}","validator":"kr_brn"},
            {"id":"EMAIL","name":"email","regex":"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}","validator":"none"}
        ]"#,
    );
    let patterns = pattern::load_patterns(patterns_file.path()).unwrap();

    let input = concat!(
        "{\"_meta\":{\"batch\":1},\"column\":\"CUSTOMERS.MEMO\"}\n",
        "{\"value\":\"901231-1234567\",\"count\":3}\n",
        "{\"value\":\"hello world\",\"count\":1}\n",
        "{\"value\":\"1234-5678-9012-3456\",\"count\":2}\n",
        "{\"value\":\"contact user@example.com please\",\"count\":5}\n",
        "{\"value\":\"123-45-67890\",\"count\":4}\n",
        "{\"value\":\"\",\"count\":1}\n"
    );

    let reader = BufReader::new(input.as_bytes());
    let mut out = Vec::new();
    let stats = scanner::scan(reader, &mut out, &patterns).unwrap();

    assert_eq!(stats.records_read, 6);
    assert_eq!(stats.findings, 4);

    let out = String::from_utf8(out).unwrap();
    let lines: Vec<serde_json::Value> = out
        .lines()
        .map(|l| serde_json::from_str(l).unwrap())
        .collect();

    assert_eq!(lines[0]["patternId"], "KR_RRN");
    assert_eq!(lines[1]["patternId"], "CARD");
    assert_eq!(lines[2]["patternId"], "EMAIL");
    assert_eq!(lines[2]["matched"], "user@example.com"); // substring, not whole value
    assert_eq!(lines[3]["patternId"], "KR_BRN");
    assert_eq!(lines[3]["validator"], "kr_brn");
}
