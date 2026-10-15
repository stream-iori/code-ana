//! Typed incremental analysis inputs; file descriptors keep large summaries off IPC frames.
use crate::SourceUnit;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::path::PathBuf;
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct BaselineSummary {
    #[serde(default)]
    pub rust_states: Vec<crate::rust::SourceState>,
    #[serde(default)]
    pub contexts: BTreeMap<String, String>,
    #[serde(default)]
    pub nodes: Vec<BaselineNode>,
    #[serde(default)]
    pub type_relations: Vec<BaselineRelation>,
    #[serde(default)]
    pub files: Vec<SourceUnit>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub changed_sources: Option<BTreeSet<String>>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BaselineNode {
    pub id: String,
    pub symbol_id: String,
    pub kind: String,
    pub module: String,
    pub scope: String,
    pub source_file: String,
    pub metadata: Option<String>,
    pub world: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BaselineRelation {
    pub source_symbol: String,
    pub target_symbol: String,
    pub relation: String,
    pub source_file: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BaselineFile {
    pub path: PathBuf,
    pub sha256: String,
    pub bytes: u64,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum BaselineInput {
    File(BaselineFile),
    Inline(BaselineSummary),
}
impl Default for BaselineInput {
    fn default() -> Self {
        Self::Inline(BaselineSummary::default())
    }
}
