//! Storage- and transport-independent analysis facts and product contracts.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

pub const VERSION: u32 = 1;
pub const WORKER_PROTOCOL_VERSION: u32 = 2;
pub const MAX_FRAME: usize = 8 * 1024 * 1024;
pub const MAX_RESULT: usize = 64 * 1024 * 1024;
pub const MAX_FACTS: usize = 100_000;
pub fn hash(bytes: impl AsRef<[u8]>) -> String {
    format!("{:x}", Sha256::digest(bytes.as_ref()))
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
impl AnalysisResult {
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
            if f.kind == FactKind::CallTarget && f.status == "resolved" && !f.declaration_resolved {
                return Err("UNCONFIRMED_TARGET".into());
            }
            merged.insert(f.id.clone(), f.clone());
        }
        for f in merged.values() {
            if !f.parent.is_empty() && !merged.contains_key(&f.parent) {
                return Err(format!("DANGLING_PARENT: {}", f.id));
            }
            if !f.target.is_empty() && !merged.contains_key(&f.target) {
                return Err(format!("DANGLING_TARGET: {}", f.id));
            }
            if !f.source.is_empty() && !files.contains_key(f.source.as_str()) {
                return Err("STALE_SOURCE_FACT".into());
            }
            if f.kind == FactKind::Facet
                && !matches!(
                    f.relation.as_str(),
                    "java.type"
                        | "java.method"
                        | "java.annotation"
                        | "java.bean"
                        | "java.binding"
                        | "java.lombok"
                )
            {
                return Err("UNSUPPORTED_REQUIRED_FACET".into());
            }
        }
        for s in manifest.files.iter().filter(|s| s.language == "java") {
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
    pub depth: u32,
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
    pub relation: Option<String>,
    pub name: Option<String>,
    pub source: Option<String>,
    pub world: Option<String>,
}
pub trait FactReader {
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
}
