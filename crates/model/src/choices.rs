//! Closed domain choices, serialized using the existing public spellings.
use serde::{Deserialize, Serialize};
use std::{fmt, ops::Deref, str::FromStr};

macro_rules! choice {
    ($name:ident, $code:ident, $first:ident = $first_text:literal $(, $variant:ident = $text:literal)* $(,)?) => {
        #[derive(Debug, Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
        pub enum $name {
            #[default]
            #[serde(rename = $first_text)] $first,
            $(#[serde(rename = $text)] $variant,)*
        }
        impl $name {
            pub const fn as_str(self) -> &'static str {
                match self { Self::$first => $first_text, $(Self::$variant => $text,)* }
            }
            pub fn clear(&mut self) { *self = Self::default(); }
        }
        impl FromStr for $name {
            type Err = crate::DomainError;
            fn from_str(value: &str) -> Result<Self, Self::Err> {
                match value { $first_text => Ok(Self::$first), $($text => Ok(Self::$variant),)*
                    _ => Err(crate::DomainError::new(crate::ErrorCode::$code, format!("{value:?}"))), }
            }
        }
        impl TryFrom<&str> for $name {
            type Error = crate::DomainError;
            fn try_from(value: &str) -> Result<Self, Self::Error> { value.parse() }
        }
        impl Deref for $name { type Target = str; fn deref(&self) -> &str { self.as_str() } }
        impl fmt::Display for $name { fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str(self.as_str()) } }
        impl PartialEq<&str> for $name { fn eq(&self, rhs: &&str) -> bool { self.as_str() == *rhs } }
    };
}
choice!(
    Operation,
    UnsupportedOperation,
    Unspecified = "",
    Index = "index",
    Doctor = "doctor",
    Resolve = "resolve",
    SearchCode = "search-code",
    Structure = "structure",
    Calls = "calls",
    CallSites = "call-sites",
    References = "references",
    Source = "source",
    Annotations = "annotations",
    TypeRelations = "type-relations",
    CallableRelations = "callable-relations",
    RuntimeImplementations = "runtime-implementations",
    Dispatch = "dispatch",
    Trace = "trace",
    Overview = "overview",
    SearchText = "search-text",
    Pipeline = "pipeline",
    Snapshot = "snapshot",
    SnapshotList = "snapshot-list",
    SnapshotRemove = "snapshot-remove",
    Diff = "diff",
    Report = "report",
    Annotate = "annotate",
    Gc = "gc",
    Recover = "recover"
);
choice!(
    Direction,
    InvalidDirection,
    Unspecified = "",
    Incoming = "in",
    Outgoing = "out",
    Both = "both"
);
choice!(
    Endpoint,
    PipelineInvalidEndpoint,
    Entity = "",
    Caller = "caller",
    Callee = "callee",
    Parent = "parent",
    Target = "target",
    Site = "site"
);
choice!(
    DispatchAlgorithm,
    InvalidDispatchAlgorithm,
    Default = "",
    Auto = "auto",
    Exact = "exact",
    Cha = "cha"
);
choice!(
    AnalysisWorld,
    InvalidAnalysisWorld,
    Default = "",
    WorkspaceOpen = "workspace-open",
    WorkspaceClosed = "workspace-closed",
    ClasspathOpen = "classpath-open"
);
choice!(
    Instantiability,
    InvalidInstantiability,
    Default = "",
    Yes = "yes",
    Unknown = "unknown",
    All = "all"
);
choice!(
    ExecutionState,
    InvalidTaskState,
    Completed = "completed",
    Failed = "failed",
    Cancelled = "cancelled"
);
choice!(
    Assessment,
    InvalidTaskState,
    Unknown = "unknown",
    Complete = "complete",
    Partial = "partial"
);
choice!(
    GateState,
    InvalidTaskState,
    NotEvaluated = "not_evaluated",
    Passed = "passed",
    Rejected = "rejected"
);
choice!(
    PublicationState,
    InvalidTaskState,
    NotRequested = "not_requested",
    Published = "published",
    Retained = "retained"
);

/// A report identifier is distinct from a lifecycle marker.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReportState {
    Off,
    Pending,
    Saved(String),
}
impl ReportState {
    pub fn as_str(&self) -> &str {
        match self {
            Self::Off => "off",
            Self::Pending => "pending",
            Self::Saved(id) => id,
        }
    }
}
impl From<String> for ReportState {
    fn from(value: String) -> Self {
        match value.as_str() {
            "off" => Self::Off,
            "pending" => Self::Pending,
            _ => Self::Saved(value),
        }
    }
}
impl From<&str> for ReportState {
    fn from(value: &str) -> Self {
        value.to_owned().into()
    }
}
impl Serialize for ReportState {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(self.as_str())
    }
}
impl<'de> Deserialize<'de> for ReportState {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Ok(String::deserialize(d)?.into())
    }
}
impl fmt::Display for ReportState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

choice!(
    AnnotationAction,
    InvalidAnnotationAction,
    List = "list",
    Add = "add",
    Update = "update",
    Remove = "remove"
);
choice!(
    AnnotationBinding,
    InvalidTaskState,
    Unbound = "unbound",
    Bound = "bound",
    Ambiguous = "ambiguous",
    Orphaned = "orphaned",
    Unavailable = "unavailable"
);

choice!(
    StageRecordType,
    PipelineTypeMismatch,
    Start = "start",
    Entity = "entity",
    Site = "site",
    Edge = "edge",
    Text = "text"
);

choice!(AnalysisMode, InvalidMode, Full = "full", Delta = "delta");

/// Shared producer states are open. Known semantics are typed; future values round-trip unchanged.
#[derive(Debug, Clone, Default, PartialEq, Eq, PartialOrd, Ord)]
pub enum FactStatus {
    #[default]
    Unspecified,
    Resolved,
    Possible,
    Unresolved,
    External,
    Complete,
    Partial,
    Other(String),
}
impl FactStatus {
    pub fn as_str(&self) -> &str {
        match self {
            Self::Unspecified => "",
            Self::Resolved => "resolved",
            Self::Possible => "possible",
            Self::Unresolved => "unresolved",
            Self::External => "external",
            Self::Complete => "complete",
            Self::Partial => "partial",
            Self::Other(value) => value,
        }
    }
}
impl From<String> for FactStatus {
    fn from(value: String) -> Self {
        match value.as_str() {
            "" => Self::Unspecified,
            "resolved" => Self::Resolved,
            "possible" => Self::Possible,
            "unresolved" => Self::Unresolved,
            "external" => Self::External,
            "complete" => Self::Complete,
            "partial" => Self::Partial,
            _ => Self::Other(value),
        }
    }
}
impl From<&str> for FactStatus {
    fn from(value: &str) -> Self {
        value.to_owned().into()
    }
}
impl std::fmt::Display for FactStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}
impl std::ops::Deref for FactStatus {
    type Target = str;
    fn deref(&self) -> &str {
        self.as_str()
    }
}
impl PartialEq<&str> for FactStatus {
    fn eq(&self, other: &&str) -> bool {
        self.as_str() == *other
    }
}
impl Serialize for FactStatus {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(self.as_str())
    }
}
impl<'de> Deserialize<'de> for FactStatus {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Ok(String::deserialize(d)?.into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn closed_choices_fail_without_panicking_and_producer_states_round_trip() {
        assert!(Operation::try_from("future-operation").is_err());
        assert!(Direction::try_from("sideways").is_err());
        for raw in [
            "",
            "resolved",
            "possible",
            "unresolved",
            "external",
            "complete",
            "partial",
            "future-java-state",
        ] {
            let encoded = serde_json::to_string(raw).unwrap();
            let state: FactStatus = serde_json::from_str(&encoded).unwrap();
            assert_eq!(state.as_str(), raw);
            assert_eq!(serde_json::to_string(&state).unwrap(), encoded);
        }
    }
}
