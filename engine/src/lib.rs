//! piiscan-engine
//!
//! A small, fast regex matching engine used as the first stage of the
//! `piiscan` pipeline. It intentionally does **only** pattern matching:
//! it reads column values from a JSONL file, tests each value against a set
//! of compiled regular expressions, and writes candidate findings back out
//! as JSONL. Semantic confirmation (checksum validation such as Luhn or the
//! Korean RRN/BRN check digits) is deliberately left to the downstream Java
//! stage, which owns the `validator` hint carried in each finding.
//!
//! Splitting the work this way keeps the hot matching loop in native code
//! while the domain-specific validation logic lives where it is easiest to
//! test and evolve.

pub mod pattern;
pub mod record;
pub mod scanner;
