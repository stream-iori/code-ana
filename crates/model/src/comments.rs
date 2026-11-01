//! Comment facts and the narrow query boundary. Product wiring is introduced separately.
use crate::{CommentId, DomainError, FactId, SourceSnippet, WorldId};
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
    LeadingStatement,
    TrailingStatement,
}
impl CommentRelation {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Documents => "documents",
            Self::EnclosingCallable => "enclosing_callable",
            Self::LeadingStatement => "leading_statement",
            Self::TrailingStatement => "trailing_statement",
        }
    }
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CommentBasis {
    LanguageRule,
    Heuristic,
}
impl CommentBasis {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::LanguageRule => "language_rule",
            Self::Heuristic => "heuristic",
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
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, deny_unknown_fields)]
pub struct CommentContext {
    pub before: u32,
    pub after: u32,
}
impl Default for CommentContext {
    fn default() -> Self {
        Self {
            before: 5,
            after: 5,
        }
    }
}
impl CommentContext {
    pub fn validate(self) -> Result<Self, DomainError> {
        if self.before > 100 || self.after > 100 {
            return Err(DomainError::new(
                crate::ErrorCode::CommentContextRange,
                "expected 0..=100",
            ));
        }
        Ok(self)
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct CommentQuery {
    pub text: String,
    pub filter: CommentFilter,
    pub context_before: Option<u32>,
    pub context_after: Option<u32>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NormalizedCommentQuery {
    pub text: String,
    pub filter: CommentFilter,
    pub context: CommentContext,
}
impl CommentQuery {
    /// Apply project defaults at the application boundary, before freezing the query.
    pub fn normalize(
        self,
        defaults: CommentContext,
    ) -> Result<NormalizedCommentQuery, DomainError> {
        defaults.validate()?;
        let context = CommentContext {
            before: self.context_before.unwrap_or(defaults.before),
            after: self.context_after.unwrap_or(defaults.after),
        }
        .validate()?;
        Ok(NormalizedCommentQuery {
            text: self.text,
            filter: self.filter,
            context,
        })
    }
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
    pub source: SourceSnippet,
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn explicit_zero_and_independent_overrides_survive_normalization() {
        let query = CommentQuery {
            context_before: Some(0),
            ..Default::default()
        }
        .normalize(CommentContext {
            before: 3,
            after: 8,
        })
        .unwrap();
        assert_eq!(
            query.context,
            CommentContext {
                before: 0,
                after: 8
            }
        );
        let frozen = serde_json::to_vec(&query).unwrap();
        let replay: NormalizedCommentQuery = serde_json::from_slice(&frozen).unwrap();
        assert_eq!(replay.context, query.context);
        assert_eq!(
            CommentQuery::default()
                .normalize(CommentContext::default())
                .unwrap()
                .context,
            CommentContext {
                before: 5,
                after: 5
            }
        );
    }
    #[test]
    fn context_range_including_shadowed_defaults_is_validated() {
        for value in [0, 100] {
            assert!(
                CommentContext {
                    before: value,
                    after: value
                }
                .validate()
                .is_ok()
            );
        }
        assert!(
            CommentQuery {
                context_before: Some(101),
                ..Default::default()
            }
            .normalize(CommentContext::default())
            .is_err()
        );
        assert!(
            CommentQuery {
                context_before: Some(0),
                ..Default::default()
            }
            .normalize(CommentContext {
                before: 101,
                after: 5
            })
            .is_err()
        );
        assert!(serde_json::from_str::<CommentQuery>(r#"{"context_before":-1}"#).is_err());
        assert!(serde_json::from_str::<CommentContext>(r#"{"unknown":1}"#).is_err());
    }
}

/// Only complete lines between two observations can be blank separators;
/// indentation on the final, unfinished line belongs to the following statement.
pub fn comment_gap_has_blank_line(gap: &str) -> bool {
    let (mut boundary, mut content, mut cr) = (false, false, false);
    for ch in gap.chars() {
        if ch == '\n' && cr {
            cr = false;
            continue;
        }
        if matches!(ch, '\r' | '\n') {
            if boundary && !content {
                return true;
            }
            boundary = true;
            content = false;
            cr = ch == '\r';
        } else {
            cr = false;
            content |= !ch.is_whitespace();
        }
    }
    false
}
#[cfg(test)]
mod gap_tests {
    #[test]
    fn indentation_is_not_a_blank_line_and_crlf_is_one_boundary() {
        for gap in [
            "",
            "\n    ",
            "\r\n    ",
            "\r    ",
            "\n // another comment\n    ",
        ] {
            assert!(!super::comment_gap_has_blank_line(gap), "{gap:?}");
        }
        for gap in ["\n\n    ", "\r\n  \r\n ", "\r\r    "] {
            assert!(super::comment_gap_has_blank_line(gap), "{gap:?}");
        }
    }
}
