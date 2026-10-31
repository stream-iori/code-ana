//! Rust source analysis over frozen inputs. No Cargo or target-code execution.
pub const ADAPTER_VERSION: &str = "rust-analyzer-0.0.349-codeana-4-analysis-resources";
mod extract;
mod facade;
pub use extract::{Analysis, Declaration, Relation, analyze, analyze_with_context};
pub mod incremental;

pub mod records;
