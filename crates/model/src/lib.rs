//! Storage- and transport-independent analysis facts and product contracts.
pub mod java_type_graph;
mod java_xml_configuration;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

pub const VERSION: u32 = 2;
pub const WORKER_PROTOCOL_VERSION: u32 = 18;
pub const DAEMON_PROTOCOL_VERSION: u32 = 3;
pub const MAX_FRAME: usize = 8 * 1024 * 1024;
pub const MAX_RESULT: usize = 64 * 1024 * 1024;
pub const MAX_FACTS: usize = 100_000;
/// Explicit disk budgets for large Java tasks; control frames and defaults stay bounded separately.
pub const MAX_JAVA_RESULT_BYTES: u64 = 8 * 1024 * 1024 * 1024;
pub const MAX_JAVA_SCRATCH_BYTES: u64 = 32 * 1024 * 1024 * 1024;
/// Explicit task deadline ceiling; compiler contexts keep their own smaller limit.
pub const MAX_TASK_DEADLINE_SECONDS: u64 = 24 * 60 * 60;
pub fn hash(bytes: impl AsRef<[u8]>) -> String {
    format!("{:x}", Sha256::digest(bytes.as_ref()))
}
/// Streaming file hashing keeps toolchain verification independent of artifact size.
pub fn hash_file(path: &std::path::Path) -> std::io::Result<String> {
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}
pub fn digest<T: Serialize>(value: &T) -> String {
    hash(serde_json::to_vec(value).expect("serializable domain value"))
}
pub fn new_id(prefix: &str) -> String {
    format!("{prefix}_{}", uuid::Uuid::new_v4().simple())
}
pub fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
pub fn key(namespace: &str, parts: &[&str]) -> String {
    format!("{namespace}_{}", digest(&parts))
}
pub fn safe_path(path: &str) -> bool {
    !path.is_empty()
        && !path.contains('\\')
        && !path.contains('\0')
        && std::path::Path::new(path)
            .components()
            .all(|c| matches!(c, std::path::Component::Normal(_)))
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(deny_unknown_fields)]
pub struct Owner {
    pub world: String,
    pub producer: String,
    pub kind: String,
    pub id: String,
}
impl Owner {
    pub fn key(&self) -> String {
        digest(self)
    }
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "snake_case")]
pub enum FactKind {
    Declaration,
    ExternalSymbol,
    Reference,
    CallSite,
    CallTarget,
    Relation,
    Dependency,
    Diagnostic,
    Coverage,
    Provenance,
    Facet,
}
impl FactKind {
    pub fn name(self) -> &'static str {
        match self {
            Self::Declaration => "declaration",
            Self::ExternalSymbol => "external_symbol",
            Self::Reference => "reference",
            Self::CallSite => "call_site",
            Self::CallTarget => "call_target",
            Self::Relation => "relation",
            Self::Dependency => "dependency",
            Self::Diagnostic => "diagnostic",
            Self::Coverage => "coverage",
            Self::Provenance => "provenance",
            Self::Facet => "facet",
        }
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct Span {
    pub start: u64,
    pub end: u64,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct Fact {
    pub id: String,
    pub kind: FactKind,
    pub owner: Owner,
    #[serde(default)]
    pub source: String,
    #[serde(default)]
    pub span: Option<Span>,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub signature: String,
    #[serde(default)]
    pub parent: String,
    #[serde(default)]
    pub target: String,
    #[serde(default)]
    pub relation: String,
    #[serde(default)]
    pub status: String,
    #[serde(default)]
    pub origin: String,
    #[serde(default)]
    pub evidence_complete: bool,
    #[serde(default)]
    pub declaration_resolved: bool,
    #[serde(default)]
    pub attributes: BTreeMap<String, serde_json::Value>,
}
impl Fact {
    /// Public configuration nodes are entities even though their authoritative
    /// payload is a language facet. Syntax/binding/type helper facets are not.
    pub fn is_public_entity(&self) -> bool {
        matches!(self.kind, FactKind::Declaration | FactKind::ExternalSymbol)
            || (self.kind == FactKind::Facet
                && !self.signature.is_empty()
                && matches!(
                    self.relation.as_str(),
                    "java.bean"
                        | "java.sofa"
                        | "java.spring.xml"
                        | "java.spring.mvc"
                        | "java.spring.handler"
                ))
    }
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct SourceUnit {
    pub path: String,
    pub hash: String,
    pub size: u64,
    pub module: String,
    pub world: String,
    pub scope: String,
    pub language: String,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub auxiliary: bool,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub files: Vec<SourceUnit>,
}
impl Manifest {
    pub fn id(&self) -> String {
        digest(self)
    }
    pub fn content_digest(&self) -> String {
        digest(
            &self
                .files
                .iter()
                .map(|s| (&s.path, &s.hash))
                .collect::<BTreeMap<_, _>>(),
        )
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BuildContext {
    pub language: String,
    pub modules: Vec<String>,
    pub source_version: String,
    pub classpath: Vec<String>,
    pub producer: String,
    pub capabilities: Vec<String>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BuildAdvice {
    pub missing: Vec<String>,
    pub cwd: String,
    pub argv: Vec<String>,
    pub purpose: String,
    pub expected: Vec<String>,
    #[serde(default)]
    pub impact: String,
    #[serde(default)]
    pub retry: String,
    #[serde(default)]
    pub capture: String,
    #[serde(default)]
    pub module: String,
    #[serde(default)]
    pub world: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AnalysisResult {
    pub mode: String,
    pub baseline: String,
    pub manifest: String,
    pub profile: String,
    pub producer: String,
    pub replacements: Vec<Owner>,
    pub facts: Vec<Fact>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AnalysisHeader {
    pub mode: String,
    pub baseline: String,
    pub manifest: String,
    pub profile: String,
    pub producer: String,
    pub replacements: Vec<Owner>,
}
/// Shape checks have no whole-graph allocation. Graph references are checked by the staged store.
pub struct FactValidator<'a> {
    files: BTreeMap<&'a str, &'a SourceUnit>,
}
impl<'a> FactValidator<'a> {
    pub fn new(manifest: &'a Manifest) -> Self {
        Self {
            files: manifest
                .files
                .iter()
                .map(|s| (s.path.as_str(), s))
                .collect(),
        }
    }
    pub fn validate(&self, f: &Fact) -> Result<(), String> {
        if f.id.is_empty()
            || f.owner.world.is_empty()
            || f.owner.producer.is_empty()
            || f.origin.is_empty()
        {
            return Err("FACT_IDENTITY_REQUIRED".into());
        }
        if !f.source.is_empty() {
            let source = self.files.get(f.source.as_str()).ok_or("UNKNOWN_SOURCE")?;
            if source.auxiliary {
                return Err("AUXILIARY_SOURCE_PUBLISHED".into());
            }
            if f.owner.world != source.world {
                return Err("WORLD_MISMATCH".into());
            }
            if f.span
                .as_ref()
                .is_some_and(|s| s.end < s.start || s.end > source.size)
            {
                return Err("INVALID_SPAN".into());
            }
        } else if f.span.is_some() {
            return Err("SPAN_WITHOUT_SOURCE".into());
        }
        if f.kind == FactKind::CallTarget && f.status == "resolved" && !f.declaration_resolved {
            return Err("UNCONFIRMED_TARGET".into());
        }
        if f.kind == FactKind::CallTarget
            && f.attributes.contains_key("binding_evidence")
            && (f.parent.is_empty()
                || f.attributes
                    .get("binding_evidence")
                    .and_then(|v| v.as_str())
                    != Some(f.parent.as_str()))
        {
            return Err("INVALID_BINDING_EVIDENCE".into());
        }
        java_type_graph::validate(
            &f.attributes,
            f.kind == FactKind::Facet
                && (f.relation == "java.type"
                    && f.attributes.get("schema_version") == Some(&serde_json::json!(3))
                    || f.relation == "java.binding"
                        && f.attributes.get("schema_version") == Some(&serde_json::json!(4))),
        )?;
        java_xml_configuration::validate(f, self.files.get(f.source.as_str()).map(|s| s.size))?;
        if f.kind == FactKind::Facet {
            if !matches!(
                f.relation.as_str(),
                "java.type"
                    | "java.syntax"
                    | "java.method"
                    | "java.annotation"
                    | "java.annotation.meta"
                    | "java.bean"
                    | "java.binding"
                    | "java.lombok"
                    | "java.sofa"
                    | "java.spring.mvc"
                    | "java.spring.handler"
                    | "java.spring.injection"
                    | "java.spring.xml"
                    | "java.reflection"
            ) {
                return Err("UNSUPPORTED_REQUIRED_FACET".into());
            }
            let schema = f
                .attributes
                .get("schema_version")
                .and_then(serde_json::Value::as_u64);
            if schema != Some(1)
                && !(schema == Some(2)
                    && matches!(
                        f.relation.as_str(),
                        "java.type" | "java.binding" | "java.annotation" | "java.syntax"
                    ))
                && !(schema == Some(3)
                    && matches!(f.relation.as_str(), "java.type" | "java.binding"))
                && !(schema == Some(4) && f.relation == "java.binding")
            {
                return Err("UNSUPPORTED_FACET_VERSION".into());
            }
            if f.relation == "java.binding"
                && matches!(schema, Some(3 | 4))
                && (f.parent.is_empty()
                    || f.attributes.get("evidence_owner").and_then(|v| v.as_str())
                        != Some(f.parent.as_str())
                    || !f
                        .attributes
                        .get("resolved")
                        .is_some_and(serde_json::Value::is_boolean)
                    || !f
                        .attributes
                        .get("complete")
                        .is_some_and(serde_json::Value::is_boolean))
            {
                return Err("INVALID_BINDING_EVIDENCE".into());
            }
            if f.relation == "java.syntax" && schema == Some(2) {
                let invalid = || "INVALID_SYNTAX_PARTITION".to_string();
                let source = self.files.get(f.source.as_str()).ok_or_else(invalid)?;
                let attributes = &f.attributes;
                let index = attributes
                    .get("chunk_index")
                    .and_then(|v| v.as_u64())
                    .ok_or_else(invalid)?;
                let offset = attributes
                    .get("node_offset")
                    .and_then(|v| v.as_u64())
                    .ok_or_else(invalid)?;
                let total = attributes
                    .get("total_nodes")
                    .and_then(|v| v.as_u64())
                    .ok_or_else(invalid)?;
                let last = attributes
                    .get("last_chunk")
                    .and_then(|v| v.as_bool())
                    .ok_or_else(invalid)?;
                let nodes = attributes
                    .get("nodes")
                    .and_then(|v| v.as_array())
                    .ok_or_else(invalid)?;
                let end = offset.checked_add(nodes.len() as u64).ok_or_else(invalid)?;
                if f.owner.kind != "source"
                    || f.owner.id != f.source
                    || attributes.get("source_hash").and_then(|v| v.as_str())
                        != Some(source.hash.as_str())
                    || nodes.is_empty()
                    || end > total
                    || last != (end == total)
                    || (index == 0) != (offset == 0)
                    || index > offset
                {
                    return Err(invalid());
                }
            }
        }
        Ok(())
    }
}
impl AnalysisHeader {
    pub fn validate(&self, manifest: &Manifest) -> Result<(), String> {
        if self.manifest != manifest.id() {
            return Err("INPUT_DRIFT".into());
        }
        if !matches!(self.mode.as_str(), "full" | "delta") {
            return Err("INVALID_MODE".into());
        }
        if self.mode == "delta" && self.baseline.is_empty() {
            return Err("BASELINE_REQUIRED".into());
        }
        if self.replacements.iter().collect::<BTreeSet<_>>().len() != self.replacements.len() {
            return Err("DUPLICATE_OWNER".into());
        }
        Ok(())
    }
}
impl AnalysisResult {
    pub fn header(&self) -> AnalysisHeader {
        AnalysisHeader {
            mode: self.mode.clone(),
            baseline: self.baseline.clone(),
            manifest: self.manifest.clone(),
            profile: self.profile.clone(),
            producer: self.producer.clone(),
            replacements: self.replacements.clone(),
        }
    }
    pub fn validate(&self, manifest: &Manifest, baseline: &[Fact]) -> Result<Vec<Fact>, String> {
        if self.manifest != manifest.id() {
            return Err("INPUT_DRIFT".into());
        }
        if !matches!(self.mode.as_str(), "full" | "delta") {
            return Err("INVALID_MODE".into());
        }
        if self.mode == "delta" && self.baseline.is_empty() {
            return Err("BASELINE_REQUIRED".into());
        }
        if self.facts.len() > MAX_FACTS {
            return Err("FACT_BUDGET".into());
        }
        let owners: BTreeSet<_> = self.replacements.iter().cloned().collect();
        if owners.len() != self.replacements.len() {
            return Err("DUPLICATE_OWNER".into());
        }
        let facet_validator = FactValidator::new(manifest);
        let files: BTreeMap<_, _> = manifest
            .files
            .iter()
            .map(|x| (x.path.as_str(), x))
            .collect();
        let mut merged: BTreeMap<String, Fact> = if self.mode == "delta" {
            baseline
                .iter()
                .filter(|f| !owners.contains(&f.owner))
                .map(|f| (f.id.clone(), f.clone()))
                .collect()
        } else {
            BTreeMap::new()
        };
        let mut ids = BTreeSet::new();
        for f in &self.facts {
            if f.id.is_empty()
                || f.owner.world.is_empty()
                || f.owner.producer.is_empty()
                || f.origin.is_empty()
            {
                return Err("FACT_IDENTITY_REQUIRED".into());
            }
            if !ids.insert(&f.id) || merged.contains_key(&f.id) {
                return Err(format!("DUPLICATE_FACT: {}", f.id));
            }
            if self.mode == "delta" && !owners.contains(&f.owner) {
                return Err("OWNER_OUTSIDE_REPLACEMENT".into());
            }
            if !f.source.is_empty() {
                let s = files.get(f.source.as_str()).ok_or("UNKNOWN_SOURCE")?;
                if s.auxiliary {
                    return Err("AUXILIARY_SOURCE_PUBLISHED".into());
                }
                if f.owner.world != s.world {
                    return Err("WORLD_MISMATCH".into());
                }
                if let Some(span) = &f.span
                    && (span.end < span.start || span.end > s.size)
                {
                    return Err("INVALID_SPAN".into());
                }
            } else if f.span.is_some() {
                return Err("SPAN_WITHOUT_SOURCE".into());
            }
            if f.kind == FactKind::Facet {
                facet_validator.validate(f)?;
            }
            if f.kind == FactKind::CallTarget && f.status == "resolved" && !f.declaration_resolved {
                return Err("UNCONFIRMED_TARGET".into());
            }
            merged.insert(f.id.clone(), f.clone());
        }
        for f in merged.values() {
            java_type_graph::validate(
                &f.attributes,
                f.kind == FactKind::Facet
                    && (f.relation == "java.type"
                        && f.attributes.get("schema_version") == Some(&serde_json::json!(3))
                        || f.relation == "java.binding"
                            && f.attributes.get("schema_version") == Some(&serde_json::json!(4))),
            )?;
            if !f.parent.is_empty() && !merged.contains_key(&f.parent) {
                return Err(format!("DANGLING_PARENT: {}", f.id));
            }
            if !f.target.is_empty() && !merged.contains_key(&f.target) {
                return Err(format!("DANGLING_TARGET: {}", f.id));
            }
            if f.kind == FactKind::CallTarget
                && f.attributes.contains_key("binding_evidence")
                && (merged.get(&f.parent).is_none_or(|site| {
                    site.kind != FactKind::CallSite
                        || site.owner != f.owner
                        || site.source != f.source
                }) || f
                    .attributes
                    .get("binding_evidence")
                    .and_then(|v| v.as_str())
                    != Some(f.parent.as_str()))
            {
                return Err("INVALID_BINDING_EVIDENCE".into());
            }
            if f.kind == FactKind::Facet
                && f.relation == "java.binding"
                && matches!(
                    f.attributes
                        .get("schema_version")
                        .and_then(serde_json::Value::as_u64),
                    Some(3 | 4)
                )
                && (merged.get(&f.parent).is_none_or(|site| {
                    site.kind != FactKind::CallSite
                        || site.owner != f.owner
                        || site.source != f.source
                }) || f.attributes.get("evidence_owner").and_then(|v| v.as_str())
                    != Some(f.parent.as_str())
                    || !f
                        .attributes
                        .get("resolved")
                        .is_some_and(serde_json::Value::is_boolean)
                    || !f
                        .attributes
                        .get("complete")
                        .is_some_and(serde_json::Value::is_boolean))
            {
                return Err("INVALID_BINDING_EVIDENCE".into());
            }
            if !f.source.is_empty() && !files.contains_key(f.source.as_str()) {
                return Err("STALE_SOURCE_FACT".into());
            }
            if files.get(f.source.as_str()).is_some_and(|s| s.auxiliary) {
                return Err("AUXILIARY_SOURCE_PUBLISHED".into());
            }
            if f.kind == FactKind::Facet
                && !matches!(
                    f.relation.as_str(),
                    "java.type"
                        | "java.syntax"
                        | "java.method"
                        | "java.annotation"
                        | "java.annotation.meta"
                        | "java.bean"
                        | "java.binding"
                        | "java.lombok"
                        | "java.sofa"
                        | "java.spring.mvc"
                        | "java.spring.handler"
                        | "java.spring.injection"
                        | "java.spring.xml"
                        | "java.reflection"
                )
            {
                return Err("UNSUPPORTED_REQUIRED_FACET".into());
            }
        }
        for s in manifest
            .files
            .iter()
            .filter(|s| s.language == "java" && !s.auxiliary)
        {
            if !merged.values().any(|f| {
                f.kind == FactKind::Coverage && f.source == s.path && f.owner.world == s.world
            }) {
                return Err(format!("MISSING_COVERAGE: {}", s.path));
            }
        }
        Ok(merged.into_values().collect())
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct Evidence {
    pub enumeration: bool,
    pub semantic: bool,
    pub limits: Vec<String>,
    pub reasons: Vec<String>,
}
impl Evidence {
    pub fn complete() -> Self {
        Self {
            enumeration: true,
            semantic: true,
            ..Self::default()
        }
    }
    pub fn merge(&mut self, other: &Self) {
        self.enumeration &= other.enumeration;
        self.semantic &= other.semantic;
        self.limits.extend(other.limits.clone());
        self.reasons.extend(other.reasons.clone());
        self.limits.sort();
        self.limits.dedup();
        self.reasons.sort();
        self.reasons.dedup();
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Stage {
    pub operation: String,
    pub selector: String,
    pub direction: String,
    pub relation: String,
    pub unique: bool,
    pub meta: bool,
    pub instantiability: String,
    pub transitive: bool,
    pub algorithm: String,
    pub analysis_world: String,
    pub depth: u32,
    pub endpoint: String,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Request {
    pub request_id: String,
    pub project: String,
    pub operation: String,
    pub selector: String,
    pub revision: String,
    pub scope: String,
    pub world: String,
    pub module: String,
    pub direction: String,
    pub relation: String,
    pub unique: bool,
    pub meta: bool,
    pub instantiability: String,
    pub transitive: bool,
    pub algorithm: String,
    pub analysis_world: String,
    pub full: bool,
    pub strict: bool,
    pub execute: bool,
    pub background: bool,
    pub base: String,
    pub target: String,
    pub cursor: String,
    pub name: String,
    pub text: String,
    pub stages: Vec<Stage>,
    pub max_records: u32,
    pub max_output_bytes: u64,
    pub depth: u32,
    pub deadline_seconds: u64,
    pub source_version: Option<String>,
    pub toolchain: Option<String>,
    pub suggestions: Option<bool>,
    pub expected_revision: String,
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
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Output {
    pub revision: String,
    pub producer: String,
    pub records: Vec<serde_json::Value>,
    pub evidence: Evidence,
    pub cursor: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResult {
    pub execution: String,
    pub assessment: String,
    pub gate: String,
    pub publication: String,
    pub report: String,
    pub revision: String,
    pub producer: String,
    pub code: String,
    pub message: String,
    pub exit_code: i32,
    pub output: Output,
}
impl TaskResult {
    pub fn success(output: Output) -> Self {
        Self {
            execution: "completed".into(),
            assessment: if output.evidence.semantic {
                "complete"
            } else {
                "partial"
            }
            .into(),
            gate: "passed".into(),
            publication: "not_requested".into(),
            report: "off".into(),
            revision: output.revision.clone(),
            producer: output.producer.clone(),
            code: "OK".into(),
            message: String::new(),
            exit_code: 0,
            output,
        }
    }
    pub fn failure(code: &str, message: impl Into<String>, exit_code: i32) -> Self {
        let mut r = Self::success(Output::default());
        r.execution = if code == "CANCELLED" {
            "cancelled"
        } else {
            "failed"
        }
        .into();
        r.assessment = "unknown".into();
        r.gate = "not_evaluated".into();
        r.publication = "retained".into();
        r.code = code.into();
        r.message = message.into();
        r.exit_code = exit_code;
        r
    }
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Revision {
    pub id: String,
    pub target: String,
    pub project: String,
    pub checkout: String,
    pub manifest: Manifest,
    pub profile: String,
    pub producer: String,
    pub created: u64,
}
#[derive(Debug, Clone, Default)]
pub struct FactFilter {
    pub kinds: Vec<FactKind>,
    pub ids: Vec<String>,
    pub parents: Vec<String>,
    pub parent: Option<String>,
    pub target: Option<String>,
    pub targets: Vec<String>,
    pub relation: Option<String>,
    pub name: Option<String>,
    pub source: Option<String>,
    pub world: Option<String>,
    pub module: Option<String>,
    pub scope: Option<String>,
    pub signature: Option<String>,
    pub package: Option<String>,
    pub package_prefix: Option<String>,
    pub sources: Vec<String>,
    pub entity_kind: Option<String>,
    pub annotation: Option<String>,
    pub visibility: Option<String>,
    pub synthetic: Option<bool>,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct FileKey {
    pub world: String,
    pub path: String,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct FileDependency {
    pub id: String,
    pub source_world: String,
    pub source_file: String,
    pub target_world: String,
    pub target_file: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnnotationMetaEdge {
    pub annotation: String,
    pub meta_annotation: String,
    pub attributes: BTreeMap<String, serde_json::Value>,
}
pub trait GraphReader {
    /// Source bytes stay outside the semantic graph. SQL implementations map
    /// upstream line/UTF-16 coordinates against the captured, verified blob.
    fn source_span(&self, entity: &Fact) -> Result<Option<Span>, String> {
        Ok(entity.span.clone())
    }
    /// Frozen meta catalogue grouping, restricted to up to 500 annotation names.
    /// SQL readers aggregate duplicate producer facts before applying the budget.
    fn annotation_meta_edges(&self, names: &[String]) -> Result<Vec<AnnotationMetaEdge>, String> {
        if names.is_empty() || names.len() > 500 {
            return Err("ANNOTATION_META_KEY_BUDGET".into());
        }
        let mut pairs = BTreeMap::<(String, String), BTreeMap<String, serde_json::Value>>::new();
        for fact in self.select(&FactFilter {
            kinds: vec![FactKind::Facet],
            relation: Some("java.annotation.meta".into()),
            ..Default::default()
        })? {
            let annotation = fact
                .attributes
                .get("annotation")
                .and_then(serde_json::Value::as_str);
            let meta = fact
                .attributes
                .get("meta_annotation")
                .and_then(serde_json::Value::as_str);
            let (Some(annotation), Some(meta)) = (annotation, meta) else {
                continue;
            };
            if !names.iter().any(|name| name == annotation) {
                continue;
            }
            let attrs = pairs.entry((annotation.into(), meta.into())).or_default();
            for key in [
                "raw_name",
                "language",
                "mechanism",
                "resolution",
                "upstream_producer",
            ] {
                if let Some(value) = fact.attributes.get(key).and_then(serde_json::Value::as_str)
                    && attrs
                        .get(key)
                        .and_then(serde_json::Value::as_str)
                        .is_none_or(|old| value < old)
                {
                    attrs.insert(key.into(), serde_json::json!(value));
                }
            }
        }
        if pairs.len() > 10000 {
            return Err("QUERY_WORK_BUDGET".into());
        }
        Ok(pairs
            .into_iter()
            .map(
                |((annotation, meta_annotation), attributes)| AnnotationMetaEdge {
                    annotation,
                    meta_annotation,
                    attributes,
                },
            )
            .collect())
    }
    /// Group published projections without loading authoritative fact bodies.
    fn overview_groups(
        &self,
        _filter: &FactFilter,
        _section: &str,
        _direction: &str,
        _relations: &[String],
    ) -> Result<Vec<serde_json::Value>, String> {
        Err("OVERVIEW_PROJECTION_UNAVAILABLE".into())
    }
    /// Exact recorded file dependencies. Unsupported historical projections
    /// are explicit so the adapter can choose a conservative full analysis.
    fn reverse_file_dependencies(
        &self,
        _targets: &[FileKey],
        _source_world: Option<&str>,
        _after: &str,
        _limit: usize,
    ) -> Result<Vec<FileDependency>, String> {
        Err("FILE_DEPENDENCIES_UNAVAILABLE".into())
    }
    /// Read only recorded dependency edges. The adapter supplies endpoint IDs;
    /// storage neither infers dependencies nor chooses an invalidation closure.
    fn reverse_dependencies(
        &self,
        targets: &[String],
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> Result<Vec<Fact>, String> {
        if targets.len() > 500 || !(1..=1024).contains(&limit) {
            return Err("INVALID_DEPENDENCY_BATCH".into());
        }
        if targets.is_empty() {
            return Ok(Vec::new());
        }
        if !filter.targets.is_empty() || filter.target.is_some() {
            return Err("DEPENDENCY_TARGETS_ALREADY_FILTERED".into());
        }
        let mut filter = filter.clone();
        filter.targets = targets.to_vec();
        if filter.kinds.is_empty() {
            filter.kinds = vec![
                FactKind::Dependency,
                FactKind::Reference,
                FactKind::Relation,
                FactKind::CallTarget,
            ];
        }
        self.scan(&filter, after, limit)
    }

    /// Exact upstream syntax targets, without deriving names from source text.
    /// Production stores override this with an indexed, bounded read.
    fn unresolved_consumers(
        &self,
        syntax_targets: &[String],
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> Result<Vec<Fact>, String> {
        if syntax_targets.len() > 500 || !(1..=1024).contains(&limit) {
            return Err("INVALID_DEPENDENCY_BATCH".into());
        }
        if syntax_targets.is_empty() {
            return Ok(Vec::new());
        }
        let mut filter = filter.clone();
        filter.kinds = vec![FactKind::CallSite];
        let mut facts = self.select(&filter)?;
        facts.retain(|fact| {
            fact.id.as_str() > after
                && fact.status == "unresolved"
                && syntax_targets.contains(&fact.name)
        });
        facts.sort_by(|a, b| a.id.cmp(&b.id));
        facts.truncate(limit);
        Ok(facts)
    }

    /// Internal keyset cursor; unrelated to the public frozen result cursor.
    /// Production stores override this with bounded SQL reads.
    fn scan(&self, filter: &FactFilter, after: &str, limit: usize) -> Result<Vec<Fact>, String> {
        if !(1..=1024).contains(&limit) {
            return Err("INVALID_SCAN_BATCH".into());
        }
        let mut facts = self.select(filter)?;
        facts.retain(|fact| fact.id.as_str() > after);
        facts.sort_by(|a, b| a.id.cmp(&b.id));
        facts.truncate(limit);
        Ok(facts)
    }
    fn scan_symbols(
        &self,
        text: &str,
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> Result<Vec<Fact>, String> {
        if !(1..=1024).contains(&limit) {
            return Err("INVALID_SCAN_BATCH".into());
        }
        let mut facts = self.search_symbols_filtered(text, filter)?;
        facts.retain(|fact| fact.id.as_str() > after);
        facts.sort_by(|a, b| a.id.cmp(&b.id));
        facts.truncate(limit);
        Ok(facts)
    }
    fn search_symbols_filtered(
        &self,
        text: &str,
        filter: &FactFilter,
    ) -> Result<Vec<Fact>, String> {
        let mut filter = filter.clone();
        filter.kinds = vec![
            FactKind::Declaration,
            FactKind::ExternalSymbol,
            FactKind::Facet,
        ];
        Ok(self
            .select(&filter)?
            .into_iter()
            .filter(|f| {
                f.is_public_entity() && (f.name.contains(text) || f.signature.contains(text))
            })
            .collect())
    }
    /// Aggregate counts without decoding authoritative fact bodies when supported.
    fn count_by_kind(&self, filter: &FactFilter) -> Result<BTreeMap<String, u64>, String> {
        let mut counts = BTreeMap::new();
        for fact in self.select(filter)? {
            *counts.entry(fact.kind.name().into()).or_default() += 1;
        }
        Ok(counts)
    }
    /// Literal, case-sensitive substring search. Storage may use an index, but
    /// must preserve the same name/signature semantics and stable ID order.
    fn search_symbols(&self, text: &str) -> std::result::Result<Vec<Fact>, String> {
        Ok(self
            .select(&FactFilter {
                kinds: vec![FactKind::Declaration, FactKind::ExternalSymbol],
                ..Default::default()
            })?
            .into_iter()
            .filter(|f| f.name.contains(text) || f.signature.contains(text))
            .collect())
    }
    fn select(&self, filter: &FactFilter) -> Result<Vec<Fact>, String>;
    fn manifest(&self) -> &Manifest;
    fn revision(&self) -> &Revision;
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Fixture {
    pub schema_version: u32,
    pub states: Vec<FixtureState>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FixtureState {
    pub name: String,
    pub content_digest: String,
    pub context: BuildContext,
    #[serde(default)]
    pub advice: Option<BuildAdvice>,
    pub facts: Vec<Fact>,
    #[serde(default)]
    pub behavior: String,
    #[serde(default)]
    pub delay_ms: u64,
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shared_binding_evidence_requires_its_call_site() {
        for version in [3, 4] {
            let manifest = Manifest::default();
            let owner =
                serde_json::json!({"world":"w","producer":"javac","kind":"context","id":"c"});
            let site: Fact = serde_json::from_value(serde_json::json!({
                "id":"site", "kind":"call_site", "owner":owner, "origin":"javac",
                "attributes":{"selected_type_model":{"kind":"EXECUTABLE"}}
            }))
            .unwrap();
            let binding: Fact = serde_json::from_value(serde_json::json!({
            "id":"binding", "kind":"facet", "parent":"site", "owner":owner,
            "origin":"javac", "relation":"java.binding",
            "attributes":{"schema_version":version,"evidence_owner":"site","resolved":true,"complete":true}
        })).unwrap();
            let validator = FactValidator::new(&manifest);
            assert!(validator.validate(&binding).is_ok());
            let mut result = AnalysisResult {
                mode: "full".into(),
                baseline: String::new(),
                manifest: manifest.id(),
                profile: "p".into(),
                producer: "javac".into(),
                replacements: vec![],
                facts: vec![site, binding],
            };
            assert!(result.validate(&manifest, &[]).is_ok());
            result.facts[0].kind = FactKind::Declaration;
            assert_eq!(
                result.validate(&manifest, &[]).unwrap_err(),
                "INVALID_BINDING_EVIDENCE"
            );
            result.facts[0].kind = FactKind::CallSite;
            result.facts[1]
                .attributes
                .insert("evidence_owner".into(), "other".into());
            assert_eq!(
                validator.validate(&result.facts[1]).unwrap_err(),
                "INVALID_BINDING_EVIDENCE"
            );
            assert_eq!(
                result.validate(&manifest, &[]).unwrap_err(),
                "INVALID_BINDING_EVIDENCE"
            );
            let target: Fact = serde_json::from_value(serde_json::json!({
                "id":"target", "kind":"call_target", "parent":"site", "owner":owner,
                "origin":"javac", "attributes":{"binding_evidence":"site"}
            }))
            .unwrap();
            assert!(validator.validate(&target).is_ok());
            result.facts = vec![result.facts[0].clone(), target];
            assert!(result.validate(&manifest, &[]).is_ok());
            result.facts[1]
                .attributes
                .insert("binding_evidence".into(), "other".into());
            assert_eq!(
                validator.validate(&result.facts[1]).unwrap_err(),
                "INVALID_BINDING_EVIDENCE"
            );
            assert_eq!(
                result.validate(&manifest, &[]).unwrap_err(),
                "INVALID_BINDING_EVIDENCE"
            );
        }
    }
    #[test]
    fn graph_facets_are_checked_by_both_publication_paths() {
        let manifest = Manifest::default();
        let mut fact: Fact = serde_json::from_value(serde_json::json!({
            "id":"type", "kind":"facet", "owner":{"world":"w","producer":"p","kind":"context","id":"c"},
            "origin":"javac", "relation":"java.type", "attributes":{
                "schema_version":3,"declared":{"$java_type":[0,0]},
                "java_type_graphs":[{"version":3,"roots":[0],"nodes":[{"version":3,"kind":"INT","complete":true}]}]
            }
        })).unwrap();
        let validator = FactValidator::new(&manifest);
        assert!(validator.validate(&fact).is_ok());
        let mut result = AnalysisResult {
            mode: "full".into(),
            baseline: String::new(),
            manifest: manifest.id(),
            profile: "p".into(),
            producer: "javac".into(),
            replacements: vec![],
            facts: vec![fact.clone()],
        };
        assert!(result.validate(&manifest, &[]).is_ok());
        fact.attributes
            .insert("declared".into(), serde_json::json!({"$java_type":[0,1]}));
        result.facts[0] = fact.clone();
        assert_eq!(
            validator.validate(&fact).unwrap_err(),
            "INVALID_JAVA_TYPE_GRAPH"
        );
        assert_eq!(
            result.validate(&manifest, &[]).unwrap_err(),
            "INVALID_JAVA_TYPE_GRAPH"
        );
    }
    #[test]
    fn java_model_four_facets_reject_unknown_versions_and_namespaces() {
        let manifest = Manifest::default();
        for (namespace, version, accepted) in [
            ("java.syntax", 1, true),
            ("java.annotation", 2, true),
            ("java.annotation.meta", 1, true),
            ("java.annotation.meta", 2, false),
            ("java.spring.mvc", 1, true),
            ("java.spring.handler", 1, true),
            ("java.spring.injection", 1, true),
            ("java.spring.xml", 1, true),
            ("java.spring.xml", 2, false),
            ("java.reflection", 1, true),
            ("java.reflection", 2, false),
            ("java.spring.mvc", 2, false),
            ("java.syntax", 2, false),
            ("java.annotation", 3, false),
            ("java.unknown", 1, false),
        ] {
            let fact: Fact = serde_json::from_value(serde_json::json!({
                "id":"facet", "kind":"facet", "owner":{"world":"w","producer":"p","kind":"context","id":"c"},
                "origin":"javac", "relation":namespace, "attributes":{"schema_version":version}
            })).unwrap();
            let result = AnalysisResult {
                mode: "full".into(),
                baseline: String::new(),
                manifest: manifest.id(),
                profile: "p".into(),
                producer: "javac".into(),
                replacements: vec![],
                facts: vec![fact],
            };
            assert_eq!(
                result.validate(&manifest, &[]).is_ok(),
                accepted,
                "{namespace}@{version}"
            );
        }
    }
    #[test]
    fn syntax_partitions_validate_source_hash_offsets_and_tail() {
        let manifest = Manifest {
            files: vec![SourceUnit {
                auxiliary: false,
                path: "Large.java".into(),
                hash: "f".repeat(64),
                size: 100,
                module: "app".into(),
                world: "w".into(),
                scope: "MAIN".into(),
                language: "java".into(),
            }],
        };
        let row = serde_json::json!({
            "id":"syntax", "kind":"facet", "source":"Large.java",
            "owner":{"world":"w","producer":"p","kind":"source","id":"Large.java"},
            "origin":"javac", "relation":"java.syntax",
            "attributes":{"schema_version":2, "source_hash":"f".repeat(64),
                "chunk_index":0,"node_offset":0,"total_nodes":2,"last_chunk":false,
                "nodes":[{"id":"first"}]}
        });
        let validator = FactValidator::new(&manifest);
        let validate = |value| validator.validate(&serde_json::from_value::<Fact>(value).unwrap());
        assert!(validate(row.clone()).is_ok());
        let mut tail = row.clone();
        tail["attributes"]["chunk_index"] = 1.into();
        tail["attributes"]["node_offset"] = 1.into();
        tail["attributes"]["last_chunk"] = true.into();
        tail["id"] = "syntax-tail".into();
        assert!(validate(tail.clone()).is_ok());
        let coverage = serde_json::json!({
            "id":"coverage", "kind":"coverage", "source":"Large.java", "status":"complete",
            "owner":{"world":"w","producer":"p","kind":"source","id":"Large.java"},
            "origin":"javac"
        });
        let mut publication = AnalysisResult {
            mode: "full".into(),
            baseline: String::new(),
            manifest: manifest.id(),
            profile: "p".into(),
            producer: "javac".into(),
            replacements: vec![],
            facts: vec![
                serde_json::from_value(coverage).unwrap(),
                serde_json::from_value(row.clone()).unwrap(),
                serde_json::from_value(tail).unwrap(),
            ],
        };
        assert!(publication.validate(&manifest, &[]).is_ok());
        for (name, value) in [
            ("chunk_index", serde_json::json!(1)),
            ("node_offset", serde_json::json!(1)),
            ("total_nodes", serde_json::json!(0)),
            ("last_chunk", serde_json::json!(true)),
            ("nodes", serde_json::json!([])),
            ("source_hash", serde_json::json!("different")),
        ] {
            let mut broken = row.clone();
            broken["attributes"][name] = value;
            publication.facts[1] = serde_json::from_value(broken.clone()).unwrap();
            assert_eq!(
                publication.validate(&manifest, &[]),
                Err("INVALID_SYNTAX_PARTITION".into()),
                "publication:{name}"
            );
            assert_eq!(
                validate(broken),
                Err("INVALID_SYNTAX_PARTITION".into()),
                "{name}"
            );
        }
    }
    #[test]
    fn framed_keys_do_not_collide() {
        assert_ne!(key("x", &["ab", "c"]), key("x", &["a", "bc"]));
    }
    #[test]
    fn path_rejects_escape() {
        for p in ["../x", "/tmp/x", "a/../x", "a\\b", ""] {
            assert!(!safe_path(p));
        }
        assert!(safe_path("src/a.java"));
    }
    #[test]
    fn evidence_cannot_erase_upstream() {
        let mut a = Evidence::complete();
        a.semantic = false;
        a.reasons.push("missing".into());
        a.merge(&Evidence::complete());
        assert!(!a.semantic);
    }
    #[test]
    fn evidence_bounded_empty_result_keeps_every_limit_and_disallows_absence() {
        let mut result = Evidence::complete();
        for limit in ["page", "depth", "records", "page"] {
            result.merge(&Evidence {
                enumeration: false,
                semantic: true,
                limits: vec![limit.into()],
                ..Default::default()
            });
        }
        assert!(!result.enumeration);
        assert!(result.semantic);
        assert_eq!(result.limits, ["depth", "page", "records"]);
        result.merge(&Evidence::complete());
        assert!(!result.enumeration);
        let encoded = serde_json::to_value(&result).unwrap();
        assert_eq!(encoded["enumeration"], false);
        assert_eq!(
            encoded["limits"],
            serde_json::json!(["depth", "page", "records"])
        );
    }
    #[test]
    fn evidence_bounded_positive_and_diagnostic_results_keep_their_meaning() {
        let bounded = Evidence {
            enumeration: false,
            semantic: true,
            limits: vec!["page".into()],
            ..Default::default()
        };
        let mut positive = Evidence::complete();
        positive.merge(&bounded);
        assert!(positive.semantic);
        assert!(!positive.enumeration);
        let mut diagnostic = Evidence {
            enumeration: true,
            semantic: false,
            reasons: vec!["MISSING:unresolved".into()],
            ..Default::default()
        };
        diagnostic.merge(&bounded);
        diagnostic.merge(&Evidence::complete());
        assert!(!diagnostic.semantic);
        assert!(!diagnostic.enumeration);
        assert_eq!(diagnostic.reasons, ["MISSING:unresolved"]);
        assert_eq!(diagnostic.limits, ["page"]);
    }
}
