//! Typed query records. JSON is encoded only for persistence and presentation.
use crate::{Fact, Span};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "record_type", content = "record")]
pub enum Record {
    Fact(Box<FactRecord>),
    Call(CallRelation),
    Trace(TracePath),
    Dispatch(DispatchCandidate),
    Source(SourceSnippet),
    Text(TextMatch),
    Project(ProjectOverview),
    Group(OverviewGroup),
    /// Already rendered operation or historical data. Never a pipeline input.
    Display(DisplayRecord),
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DisplayRecord(serde_json::Value);
impl Record {
    pub fn display<T: Serialize>(value: &T) -> Result<Self, serde_json::Error> {
        Ok(Self::Display(DisplayRecord(serde_json::to_value(value)?)))
    }
    /// The public text contract stays independent of the persisted enum layout.
    pub fn wire(&self) -> Result<serde_json::Value, serde_json::Error> {
        match self {
            Self::Fact(v) => serde_json::to_value(v),
            Self::Call(v) => serde_json::to_value(v),
            Self::Trace(v) => serde_json::to_value(v),
            Self::Dispatch(v) => {
                let mut value = serde_json::to_value(v)?;
                let fields = value.as_object_mut().expect("typed record object");
                fields.remove("modeled");
                if let Some(modeled) = &v.modeled {
                    fields.insert(
                        "operation".into(),
                        serde_json::to_value(&modeled.operation)?,
                    );
                    fields.insert("basis".into(), serde_json::to_value(&modeled.basis)?);
                    fields.insert(
                        "recovery_phase".into(),
                        serde_json::to_value(modeled.recovery_phase)?,
                    );
                }
                Ok(value)
            }
            Self::Source(v) => serde_json::to_value(v),
            Self::Text(v) => serde_json::to_value(v),
            Self::Project(v) => serde_json::to_value(v),
            Self::Group(v) => serde_json::to_value(v),
            Self::Display(v) => Ok(v.0.clone()),
        }
    }
    pub fn endpoint(&self, endpoint: crate::Endpoint) -> Result<Vec<&str>, crate::DomainError> {
        use crate::Endpoint;
        let missing = || {
            crate::DomainError::new(
                crate::ErrorCode::PipelineEndpointUnavailable,
                endpoint.to_string(),
            )
        };
        let id = match (self, endpoint) {
            (Self::Fact(v), Endpoint::Entity) => &v.fact.id,
            (Self::Fact(v), Endpoint::Caller | Endpoint::Parent) => &v.fact.parent,
            (Self::Fact(v), Endpoint::Callee | Endpoint::Target) => &v.fact.target,
            (Self::Call(v), Endpoint::Caller | Endpoint::Parent) => &v.from,
            (Self::Call(v), Endpoint::Callee | Endpoint::Target) => &v.to,
            (Self::Trace(v), Endpoint::Caller | Endpoint::Parent) => &v.from,
            (Self::Trace(v), Endpoint::Callee | Endpoint::Target) => &v.to,
            (Self::Dispatch(v), Endpoint::Callee | Endpoint::Target) => {
                v.target.as_ref().ok_or_else(missing)?
            }
            (Self::Call(v), Endpoint::Site) => {
                return Ok(v.sites.iter().map(String::as_str).collect());
            }
            _ => return Err(missing()),
        };
        if id.is_empty() {
            return Err(missing());
        }
        Ok(vec![id])
    }
    pub fn source_key(&self) -> Option<(&str, u64)> {
        match self {
            Self::Source(v) => Some((&v.path, v.span.start)),
            _ => None,
        }
    }
}
impl From<Fact> for Record {
    fn from(fact: Fact) -> Self {
        Self::Fact(Box::new(FactRecord {
            fact,
            depth: None,
            runtime_implementation: None,
        }))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FactRecord {
    #[serde(flatten)]
    pub fact: Fact,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub depth: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub runtime_implementation: Option<RuntimeImplementation>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuntimeImplementation {
    pub instantiability: String,
    pub reason: String,
    pub proof: Vec<String>,
    pub depth: u32,
    pub world: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CallRelation {
    pub from: String,
    pub to: String,
    pub sites: Vec<String>,
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub depth: Option<u32>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TracePath {
    pub from: String,
    pub to: String,
    pub nodes: Vec<String>,
    pub hops: Vec<CallRelation>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct DispatchCandidate {
    pub call_site: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub binding: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub binding_origin: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub candidate: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub algorithm: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub executable: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub analysis_world: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub type_proof: Option<Vec<String>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub modeled: Option<ModeledBinding>,
    pub proof: Vec<String>,
    pub possible: bool,
    pub world: String,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ModeledBinding {
    pub operation: Option<String>,
    pub basis: Option<String>,
    pub recovery_phase: Option<u32>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SourceSnippet {
    pub path: String,
    pub hash: String,
    pub span: Span,
    pub text: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TextLine {
    pub line: usize,
    pub text: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TextMatch {
    pub path: String,
    pub line: usize,
    pub text: String,
    pub hash: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub context_before: Option<Vec<TextLine>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub context_after: Option<Vec<TextLine>>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProjectOverview {
    pub counts: BTreeMap<String, u64>,
    pub sources: usize,
    pub profile: String,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct OverviewGroup {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub module: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub package: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_package: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub relation: Option<String>,
    pub count: u64,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

// Old frozen pages and task results are presentation data, never semantic inputs.
#[derive(Deserialize)]
#[serde(remote = "Record", tag = "record_type", content = "record")]
enum RecordDecoder {
    Fact(Box<FactRecord>),
    Call(CallRelation),
    Trace(TracePath),
    Dispatch(DispatchCandidate),
    Source(SourceSnippet),
    Text(TextMatch),
    Project(ProjectOverview),
    Group(OverviewGroup),
    /// Already rendered operation or historical data. Never a pipeline input.
    Display(DisplayRecord),
}
impl<'de> Deserialize<'de> for Record {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let value = serde_json::Value::deserialize(d)?;
        if value.get("record_type").is_some() {
            RecordDecoder::deserialize(value).map_err(serde::de::Error::custom)
        } else {
            Ok(Self::Display(DisplayRecord(value)))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn endpoints_are_typed_and_display_records_cannot_enter_a_pipeline() {
        let record = Record::Call(CallRelation {
            from: "a".into(),
            to: "b".into(),
            sites: vec!["s1".into(), "s2".into()],
            kind: "resolved_call".into(),
            depth: Some(1),
        });
        assert_eq!(record.endpoint(crate::Endpoint::Caller).unwrap(), vec!["a"]);
        assert_eq!(record.endpoint(crate::Endpoint::Target).unwrap(), vec!["b"]);
        assert_eq!(
            record.endpoint(crate::Endpoint::Site).unwrap(),
            vec!["s1", "s2"]
        );
        assert!(record.endpoint(crate::Endpoint::Entity).is_err());
        let persisted = serde_json::to_vec(&record).unwrap();
        let restored: Record = serde_json::from_slice(&persisted).unwrap();
        assert_eq!(restored.wire().unwrap(), record.wire().unwrap());
        let historical: Record = serde_json::from_value(record.wire().unwrap()).unwrap();
        assert_eq!(historical.wire().unwrap(), record.wire().unwrap());
        assert!(historical.endpoint(crate::Endpoint::Target).is_err());
    }
}
