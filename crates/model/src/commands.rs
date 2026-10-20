//! Domain commands separate transport parameters from the query and index policies.
use crate::*;
use serde::{Deserialize, Serialize};
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct QueryRequest {
    pub operation: Operation,
    pub selector: String,
    pub scope: String,
    pub world: String,
    pub module: String,
    pub direction: Direction,
    pub relation: String,
    pub unique: bool,
    pub meta: bool,
    pub instantiability: Instantiability,
    pub transitive: bool,
    pub algorithm: DispatchAlgorithm,
    pub analysis_world: AnalysisWorld,
    pub target: String,
    pub name: String,
    pub stages: Vec<Stage>,
    pub max_records: u32,
    pub max_output_bytes: u64,
    pub depth: u32,
    pub signature: String,
    pub package: String,
    pub package_prefix: String,
    pub paths: Vec<String>,
    pub entity_kind: String,
    pub annotation: String,
    pub visibility: String,
    pub synthetic: Option<bool>,
    pub text_regex: bool,
    pub text_ignore_case: bool,
    pub text_context: u32,
    pub overview_section: String,
    pub overview_depth: u32,
}
impl From<&Request> for QueryRequest {
    fn from(value: &Request) -> Self {
        Self {
            operation: value.operation,
            selector: value.selector.clone(),
            scope: value.scope.clone(),
            world: value.world.clone(),
            module: value.module.clone(),
            direction: value.direction,
            relation: value.relation.clone(),
            unique: value.unique,
            meta: value.meta,
            instantiability: value.instantiability,
            transitive: value.transitive,
            algorithm: value.algorithm,
            analysis_world: value.analysis_world,
            target: value.target.clone(),
            name: value.name.clone(),
            stages: value.stages.clone(),
            max_records: value.max_records,
            max_output_bytes: value.max_output_bytes,
            depth: value.depth,
            signature: value.signature.clone(),
            package: value.package.clone(),
            package_prefix: value.package_prefix.clone(),
            paths: value.paths.clone(),
            entity_kind: value.entity_kind.clone(),
            annotation: value.annotation.clone(),
            visibility: value.visibility.clone(),
            synthetic: value.synthetic,
            text_regex: value.text_regex,
            text_ignore_case: value.text_ignore_case,
            text_context: value.text_context,
            overview_section: value.overview_section.clone(),
            overview_depth: value.overview_depth,
        }
    }
}

impl From<&QueryRequest> for QueryRequest {
    fn from(value: &QueryRequest) -> Self {
        value.clone()
    }
}
#[derive(Debug, Clone)]
pub struct IndexCommand {
    pub full: bool,
    pub strict: bool,
    pub expected_revision: RevisionId,
}
impl From<&Request> for IndexCommand {
    fn from(r: &Request) -> Self {
        Self {
            full: r.full,
            strict: r.strict,
            expected_revision: r.expected_revision.clone().into(),
        }
    }
}
#[derive(Debug, Clone)]
pub enum TaskCommand {
    Index(IndexCommand),
    Query(Box<QueryRequest>),
    Snapshot { analysis: bool, index: IndexCommand },
    Doctor,
    Diff,
    Report,
    SnapshotList,
    SnapshotRemove,
    Gc { execute: bool },
    Recover,
}
impl TryFrom<&Request> for TaskCommand {
    type Error = DomainError;
    fn try_from(r: &Request) -> Result<Self, Self::Error> {
        use Operation::*;
        Ok(match r.operation {
            Index => Self::Index(r.into()),
            Snapshot => Self::Snapshot {
                analysis: r.name == "analysis",
                index: r.into(),
            },
            Doctor => Self::Doctor,
            Diff => Self::Diff,
            Report => Self::Report,
            SnapshotList => Self::SnapshotList,
            SnapshotRemove => Self::SnapshotRemove,
            Gc => Self::Gc { execute: r.execute },
            Recover => Self::Recover,
            Unspecified => return Err(DomainError::new(ErrorCode::UnsupportedOperation, "")),
            _ => Self::Query(Box::new(r.into())),
        })
    }
}
impl TaskCommand {
    pub fn requires_analysis(&self) -> bool {
        matches!(self, Self::Index(_) | Self::Snapshot { analysis: true, .. })
    }
    pub fn requires_writer(&self) -> bool {
        matches!(
            self,
            Self::Index(_)
                | Self::Snapshot { .. }
                | Self::SnapshotRemove
                | Self::Recover
                | Self::Gc { execute: true }
        )
    }
}
