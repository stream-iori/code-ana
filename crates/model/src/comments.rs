//! Comment facts and the narrow query boundary. Product wiring is introduced separately.
use crate::{CommentId, DomainError, FactId, WorldId};
use serde::{Deserialize, Serialize};

pub type CapturedSourceReader = dyn Fn(&str, u64) -> Result<Vec<u8>, DomainError> + Send + Sync;
/// Task-owned accounts and captured bytes; staging never creates a replacement ledger.
#[derive(Clone)]
pub struct GraphStagingContext {
    pub hosts: std::collections::BTreeMap<crate::AnalysisLanguage, crate::AnalysisContext>,
    pub source: std::sync::Arc<CapturedSourceReader>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CommentKind {
    Line,
    Block,
    Doc,
    DocAttribute,
    Docstring,
}
impl CommentKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Line => "line",
            Self::Block => "block",
            Self::Doc => "doc",
            Self::DocAttribute => "doc_attribute",
            Self::Docstring => "docstring",
        }
    }
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CommentRelation {
    Documents,
    EnclosingCallable,
}
impl CommentRelation {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Documents => "documents",
            Self::EnclosingCallable => "enclosing_callable",
        }
    }
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CommentBasis {
    LanguageRule,
}
impl CommentBasis {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::LanguageRule => "language_rule",
        }
    }
}

/// Byte offsets are UTF-8 half-open; lines/columns are one-based UTF-16.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct CommentRange {
    pub byte_start: u64,
    pub byte_end: u64,
    pub begin_line: u32,
    pub begin_column: u32,
    pub end_line: u32,
    pub end_column: u32,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct CommentBodySegment {
    pub body_start: u64,
    pub body_end: u64,
    pub source_start: u64,
    pub source_end: u64,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct CommentFact {
    pub id: CommentId,
    pub source_file: String,
    pub source_hash: String,
    pub module: String,
    pub scope: String,
    pub world: WorldId,
    pub language: String,
    pub provider: String,
    pub producer: String,
    pub kind: CommentKind,
    pub range: CommentRange,
    pub body: String,
    pub body_map: Vec<CommentBodySegment>,
    pub rule_version: String,
}
impl CommentFact {
    pub fn expected_id(&self) -> CommentId {
        crate::key(
            "comment",
            &[
                &self.producer,
                &self.language,
                &self.module,
                &self.scope,
                self.world.as_str(),
                &self.source_file,
                &self.source_hash,
                &self.range.byte_start.to_string(),
                &self.range.byte_end.to_string(),
                self.kind.as_str(),
            ],
        )
        .into()
    }
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct CommentLink {
    pub comment: CommentId,
    pub relation: CommentRelation,
    pub declaration: Option<FactId>,
    pub range: CommentRange,
    pub basis: CommentBasis,
    pub rule_version: String,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, deny_unknown_fields)]
pub struct CommentFilter {
    pub module: String,
    pub scope: String,
    pub world: WorldId,
    pub paths: Vec<String>,
}
/// Persist this complete key, never an SQLite rowid, when resuming an ordered scan.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(deny_unknown_fields)]
pub struct CommentOrderKey {
    pub source_file: String,
    pub byte_start: u64,
    pub byte_end: u64,
    pub world: WorldId,
    pub id: CommentId,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CommentHitRange {
    pub body_start: u64,
    pub body_end: u64,
    pub source: CommentRange,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CommentMatch {
    pub comment: CommentFact,
    pub hits: Vec<CommentHitRange>,
    pub links: Vec<CommentLink>,
}

/// Frozen prepared-input gaps, independent of binding completeness and comment matches.
#[derive(Debug)]
pub struct UnavailableCommentInput {
    pub language: String,
    pub roots: Vec<String>,
    pub scopes: Vec<CommentInputScope>,
}
#[derive(Debug)]
pub struct CommentInputScope {
    pub module: String,
    pub scope: String,
    pub world: WorldId,
}
