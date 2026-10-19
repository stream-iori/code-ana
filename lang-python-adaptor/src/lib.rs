//! Python semantic analysis over explicitly frozen inputs.
//!
//! Pyrefly's unstable Rust interfaces stay inside this crate. The backend is
//! pinned as a single set; upgrading it requires the Python fixture gates.
pub use codeana_model::python::BACKEND_COMMIT;
pub const BACKEND_VERSION: &str = "1.4.0";

mod facade;
pub mod frozen;
pub mod records;
pub use facade::{Analysis, Call, Declaration, FrozenModule, Reference, Target, analyze};
