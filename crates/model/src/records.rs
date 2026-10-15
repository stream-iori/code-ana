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
        let id: &str = match (self, endpoint) {
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
    /// Stable query order uses semantic identity, independent of JSON presentation.
    /// Equal keys retain producer order and multiplicity.
    pub fn query_cmp(&self, other: &Self) -> std::cmp::Ordering {
        fn family(record: &Record) -> u8 {
            match record {
                Record::Fact(_) => 0,
                Record::Call(_) => 1,
                Record::Trace(_) => 2,
                Record::Dispatch(_) => 3,
                Record::Source(_) => 4,
                Record::Text(_) => 5,
                Record::Project(_) => 6,
                Record::Group(_) => 7,
                Record::Display(_) => 8,
            }
        }
        let order = family(self).cmp(&family(other));
        if order != std::cmp::Ordering::Equal {
            return order;
        }
        match (self, other) {
            (Self::Fact(a), Self::Fact(b)) => {
                (&a.fact.owner.world, &a.fact.id).cmp(&(&b.fact.owner.world, &b.fact.id))
            }
            (Self::Call(a), Self::Call(b)) => (&a.from, &a.to, &a.kind, a.depth, &a.sites)
                .cmp(&(&b.from, &b.to, &b.kind, b.depth, &b.sites)),
            (Self::Trace(a), Self::Trace(b)) => (&a.from, &a.to, &a.nodes)
                .cmp(&(&b.from, &b.to, &b.nodes))
                .then_with(|| {
                    for (a, b) in a.hops.iter().zip(&b.hops) {
                        let order = (&a.from, &a.to, &a.kind, a.depth, &a.sites)
                            .cmp(&(&b.from, &b.to, &b.kind, b.depth, &b.sites));
                        if order != std::cmp::Ordering::Equal {
                            return order;
                        }
                    }
                    a.hops.len().cmp(&b.hops.len())
                }),
            (Self::Dispatch(a), Self::Dispatch(b)) => (
                &a.world,
                &a.call_site,
                &a.target,
                &a.binding,
                &a.candidate,
                &a.algorithm,
            )
                .cmp(&(
                    &b.world,
                    &b.call_site,
                    &b.target,
                    &b.binding,
                    &b.candidate,
                    &b.algorithm,
                )),
            (Self::Source(a), Self::Source(b)) => {
                (&a.path, a.span.start, a.span.end).cmp(&(&b.path, b.span.start, b.span.end))
            }
            (Self::Text(a), Self::Text(b)) => (&a.path, a.line).cmp(&(&b.path, b.line)),
            (Self::Group(a), Self::Group(b)) => (
                &a.module,
                &a.package,
                &a.target_package,
                &a.relation,
            )
                .cmp(&(&b.module, &b.package, &b.target_package, &b.relation)),
            _ => std::cmp::Ordering::Equal,
        }
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
    pub binding: Option<crate::FactStatus>,
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
    fn query_order_uses_identity_and_keeps_equal_records() {
        let fact = |id: &str, name: &str| -> Record {
            serde_json::from_value::<Fact>(serde_json::json!({"id":id,"kind":"declaration","owner":{"world":"w","producer":"p","kind":"source","id":"a"},"name":name})).unwrap().into()
        };
        let mut records = [fact("b", "A"), fact("a", "Z"), fact("a", "A")];
        records.sort_by(Record::query_cmp);
        let names = records
            .iter()
            .map(|record| match record {
                Record::Fact(value) => value.fact.name.as_str(),
                _ => unreachable!(),
            })
            .collect::<Vec<_>>();
        assert_eq!(names, vec!["Z", "A", "A"]);
        let mut calls = [
            Record::Call(CallRelation {
                from: "b".into(),
                to: "a".into(),
                kind: "calls".into(),
                sites: vec![],
                depth: None,
            }),
            Record::Call(CallRelation {
                from: "a".into(),
                to: "b".into(),
                kind: "calls".into(),
                sites: vec![],
                depth: None,
            }),
        ];
        calls.sort_by(Record::query_cmp);
        assert_eq!(
            calls[0].endpoint(crate::Endpoint::Caller).unwrap(),
            vec!["a"]
        );
    }
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

/// Persisted page metadata only; old pages have no policy and retain frozen order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum QueryOrderPolicy {
    BusinessV1,
    PublicIdV1,
    SourceLinesV1,
}
impl QueryOrderPolicy {
    pub fn for_operation(operation: crate::Operation) -> Self {
        match operation {
            crate::Operation::SearchCode => Self::PublicIdV1,
            crate::Operation::SearchText => Self::SourceLinesV1,
            _ => Self::BusinessV1,
        }
    }
}
