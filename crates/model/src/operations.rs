//! Stable user-content and maintenance results, independent of SQLite.
use crate::Span;
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
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
    pub fact_id: String,
    pub world: String,
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
