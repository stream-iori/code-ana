//! Stable user-content and maintenance results, independent of SQLite.
use crate::Span;
use crate::{Evidence, Fact};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GcResult {
    pub execute: bool,
    pub protected: BTreeSet<String>,
    pub revisions: Vec<String>,
    pub blobs: Vec<String>,
    pub expired_objects: BTreeSet<(String, String)>,
    pub retained_graph_bytes: u64,
    pub budget_unmet: bool,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RecoveryResult {
    pub execute: bool,
    pub orphans: Vec<String>,
    pub pending: Vec<String>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnnotationAnchor {
    pub revision: String,
    pub fact_id: crate::FactId,
    pub world: crate::WorldId,
    pub source: String,
    pub source_hash: String,
    pub span: Option<Span>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnnotationEntry {
    pub id: String,
    pub symbol: String,
    pub text: String,
    pub version: i64,
    pub annotation_revision: i64,
    pub anchor: Option<AnnotationAnchor>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub binding: Option<crate::AnnotationBinding>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub binding_revision: Option<String>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnnotationMutation {
    pub id: String,
    pub version: i64,
    pub annotation_revision: i64,
    pub action: crate::AnnotationAction,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum AnnotationResult {
    Entry(Box<AnnotationEntry>),
    Mutation(AnnotationMutation),
    List(Vec<AnnotationEntry>),
}

#[derive(Clone, Default)]
pub struct IndexSummary {
    pub statistics: CandidateStatistics,
    /// Task-local diagnostics; never part of Fact IR or the worker protocol.
    pub phase_ms: BTreeMap<String, u64>,
    pub fact_count: u64,
    pub counts: BTreeMap<String, u64>,
    pub diagnostic_total: u64,
    pub diagnostic_rows: u64,
    pub diagnostic_sampled_occurrences: u64,
    pub diagnostic_groups: BTreeMap<String, u64>,
    pub diagnostic_groups_omitted: u64,
    pub diagnostic_samples: Vec<Fact>,
    pub evidence: Evidence,
    pub analysis_mode: Option<String>,
    pub classpath_status: Option<String>,
}
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
pub struct CandidateStatistics {
    pub analyzed_indexes: u64,
    pub elapsed_ms: u64,
    pub warnings: Vec<String>,
}
