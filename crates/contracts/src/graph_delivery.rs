//! Typed graph archive descriptors and limits; no runtime IO.
use std::path::PathBuf;

/// Sealed graph descriptor shared by result admission and diagnostic copies.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Descriptor {
    pub format: String,
    pub path: PathBuf,
    pub count: u64,
    pub bytes: u64,
    pub sha256: String,
}

#[derive(Clone, Copy, Debug)]
pub struct OutputLimits {
    pub bytes: u64,
    pub facts: u64,
}
impl Default for OutputLimits {
    fn default() -> Self {
        Self {
            bytes: codeana_model::MAX_RESULT as u64,
            facts: codeana_model::MAX_FACTS as u64,
        }
    }
}
