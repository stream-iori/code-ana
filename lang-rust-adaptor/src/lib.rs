//! Rust source analysis over frozen inputs. No Cargo or target-code execution.
pub const ADAPTER_VERSION: &str = "rust-analyzer-0.0.349-codeana-3-stream-noexpand";
mod extract;
mod facade;
pub use extract::{Analysis, Declaration, Relation, analyze};
pub mod incremental;

pub mod records;
