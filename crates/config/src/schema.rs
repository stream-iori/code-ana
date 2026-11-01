//! Strict, explicit project and user configuration schemas.
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct ProjectConfig {
    pub schema_version: u32,
    pub kind: String,
    pub project: Project,
    pub discovery: Discovery,
    pub scan: Scan,
    pub build: Build,
    pub index: Index,
    pub resources: Resources,
    pub query: Query,
    pub observability: Observability,
    pub languages: Languages,
    pub modules: Vec<Module>,
    pub inputs: Option<crate::inputs::InputPreparation>,
    pub versions: Versions,
    pub search: Search,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Project {
    pub name: String,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Discovery {
    pub mode: String,
    pub build_system: String,
    pub inputs_manifest: Option<String>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Scan {
    pub scopes: Vec<String>,
    pub include: Vec<String>,
    pub exclude: Vec<String>,
    pub text_only: Vec<String>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Build {
    pub suggestions: Option<bool>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Index {
    pub mode: String,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Resources {
    pub index: IndexResources,
    pub query: QueryResources,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct IndexResources {
    pub analysis: AnalysisResources,
    pub worker_memory_bytes: Option<u64>,
    pub rust_result_bytes: Option<u64>,
    pub rust_fact_limit: Option<u64>,
    pub python_result_bytes: Option<u64>,
    pub python_fact_limit: Option<u64>,
    pub python_scratch_bytes: Option<u64>,
    pub java_compiler_heap_bytes: Option<u64>,
    pub java_parallelism: Option<u32>,
    pub java_model_record_limit: Option<u32>,
    pub java_model_archive_bytes: Option<u64>,
    pub java_model_decode_bytes: Option<u64>,
    pub java_compiler_timeout_seconds: Option<u32>,
    pub java_native_heap_bytes: Option<u64>,
    pub java_result_bytes: Option<u64>,
    pub java_fact_limit: Option<u32>,
    pub java_scratch_bytes: Option<u64>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct QueryResources {
    pub max_work: Option<u64>,
    pub max_payload_bytes: Option<u64>,
    pub diff_spill_bytes: Option<u64>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Query {
    pub comments: CommentOptions,
    pub scopes: Vec<String>,
    pub max_output_bytes: Option<u64>,
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct CommentOptions {
    pub context_before: u32,
    pub context_after: u32,
}
impl Default for CommentOptions {
    fn default() -> Self {
        Self {
            context_before: 5,
            context_after: 5,
        }
    }
}
impl CommentOptions {
    pub fn validate(self) -> Result<codeana_model::CommentContext, codeana_model::DomainError> {
        codeana_model::CommentContext {
            before: self.context_before,
            after: self.context_after,
        }
        .validate()
    }
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Observability {
    pub mode: String,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Languages {
    pub java: Option<Language>,
    pub rust: Option<Language>,
    pub python: Option<PythonLanguage>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct PythonLanguage {
    pub enabled: Option<bool>,
    pub discovery: PythonDiscovery,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct PythonDiscovery {
    pub inputs_manifest: Option<String>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Language {
    pub enabled: Option<bool>,
    pub mode: String,
    pub discovery: Discovery,
    pub analysis: Analysis,
    pub lombok: Lombok,
    pub extensions: Extensions,
    pub external: External,
    pub dependencies: Option<RustDependencyScan>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct RustDependencyScan {
    pub include: Vec<String>,
    pub exclude: Vec<String>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Analysis {
    pub source_version: Option<String>,
    pub toolchain: Option<String>,
    pub classpath: Option<String>,
    pub vm_classpath: Option<bool>,
    pub edition: Option<String>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Lombok {
    pub mode: Option<String>,
    pub strict: Option<bool>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Extensions {
    pub spring: Spring,
    pub sofa: Sofa,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Spring {
    pub enabled: Option<bool>,
    pub xml: Option<bool>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Sofa {
    pub enabled: Option<bool>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct External {
    pub exclude_patterns: Option<Vec<String>>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Module {
    pub id: String,
    pub language: String,
    pub root: String,
    pub manifest: Option<String>,
    pub source_roots: Vec<SourceRoot>,
    pub analysis: Analysis,
    pub lombok: Lombok,
    pub extensions: Extensions,
    pub external: External,
    pub scan: Scan,
    pub worlds: Vec<World>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct SourceRoot {
    pub path: String,
    pub scope: String,
    pub label: Option<String>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct World {
    pub id: String,
    pub target: String,
    pub default_features: Option<bool>,
    pub features: Vec<String>,
    pub cfg: Vec<String>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Versions {
    pub capture: CapturePolicy,
    pub gc: GcPolicy,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct CapturePolicy {
    pub include_ignored: Vec<String>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct GcPolicy {
    pub auto: Option<bool>,
    pub keep: Option<u32>,
    pub max_bytes: Option<u64>,
    pub max_age_days: Option<u32>,
    pub include_caches: Option<bool>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Search {
    pub text: TextSearch,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct TextSearch {
    pub backend: String,
    pub include: Vec<String>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct UserConfig {
    pub resources: UserResources,
    pub schema_version: u32,
    pub kind: String,
    pub daemon: DaemonPolicy,
    pub limits: Limits,
    pub toolchains: BTreeMap<String, Toolchain>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct DaemonPolicy {
    pub idle_timeout_seconds: Option<u64>,
    pub java_warm_workers: Option<bool>,
    pub java_signature_cache: Option<bool>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Limits {
    pub max_workers: Option<usize>,
    pub max_query_tasks: Option<usize>,
    pub max_worker_memory_bytes: Option<u64>,
    pub max_total_worker_memory_bytes: Option<u64>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Toolchain {
    pub language: String,
    pub home: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct UserResources {
    pub index: UserIndexResources,
    pub query: QueryResources,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct UserIndexResources {
    pub analysis: AnalysisResources,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct AnalysisResources {
    pub max_work: Option<u64>,
    pub max_nodes: Option<u64>,
    pub max_payload_bytes: Option<u64>,
    pub max_depth: Option<u32>,
}
impl AnalysisResources {
    pub fn resolve(
        &self,
        user: &Self,
    ) -> Result<codeana_model::AnalysisLimits, codeana_model::AnalysisFailure> {
        self.validate()?;
        user.validate()?;
        let d = codeana_model::AnalysisLimits::default();
        let limits = codeana_model::AnalysisLimits {
            max_work: self.max_work.or(user.max_work).unwrap_or(d.max_work),
            max_nodes: self.max_nodes.or(user.max_nodes).unwrap_or(d.max_nodes),
            max_payload_bytes: self
                .max_payload_bytes
                .or(user.max_payload_bytes)
                .unwrap_or(d.max_payload_bytes),
            max_depth: self.max_depth.or(user.max_depth).unwrap_or(d.max_depth),
        };
        limits.validate()?;
        Ok(limits)
    }
    pub fn validate(&self) -> Result<(), codeana_model::AnalysisFailure> {
        let d = codeana_model::AnalysisLimits::default();
        codeana_model::AnalysisLimits {
            max_work: self.max_work.unwrap_or(d.max_work),
            max_nodes: self.max_nodes.unwrap_or(d.max_nodes),
            max_payload_bytes: self.max_payload_bytes.unwrap_or(d.max_payload_bytes),
            max_depth: self.max_depth.unwrap_or(d.max_depth),
        }
        .validate()
    }
}

#[cfg(test)]
mod analysis_resource_tests {
    use super::*;
    #[test]
    fn analysis_fields_inherit_and_validate_independently() {
        let user = AnalysisResources {
            max_nodes: Some(500),
            max_work: Some(4294967296),
            ..Default::default()
        };
        let project = AnalysisResources {
            max_nodes: Some(7),
            ..Default::default()
        };
        let limits = project.resolve(&user).unwrap();
        assert_eq!(limits.max_nodes, 7);
        assert_eq!(limits.max_work, 4294967296);
        assert_eq!(limits.max_depth, 256);
        assert!(
            AnalysisResources {
                max_depth: Some(4097),
                ..Default::default()
            }
            .validate()
            .is_err()
        );
        assert!(
            AnalysisResources {
                max_work: Some(0),
                ..Default::default()
            }
            .resolve(&user)
            .is_err()
        );
    }
    #[test]
    fn project_and_user_tables_parse_with_strict_types() {
        let user: UserConfig = toml::from_str(
            "schema_version=1\nkind='user'\n[resources.index.analysis]\nmax_nodes=11",
        )
        .unwrap();
        assert_eq!(user.resources.index.analysis.max_nodes, Some(11));
        let project: ProjectConfig = toml::from_str(
            "schema_version=1\nkind='project'\n[resources.index.analysis]\nmax_work=12",
        )
        .unwrap();
        assert_eq!(project.resources.index.analysis.max_work, Some(12));
        for input in [
            "max_nodes=-1",
            "max_nodes='1'",
            "max_depth=4294967296",
            "unknown=1",
        ] {
            assert!(
                toml::from_str::<ProjectConfig>(&format!(
                    "schema_version=1\nkind='project'\n[resources.index.analysis]\n{input}"
                ))
                .is_err()
            );
        }
    }
}
