//! JSON DTO for the Java engine. Domain/protobuf control data is mapped explicitly.
use crate::{java::OutputLimits, worker as w};
use anyhow::{Context, Result};
use codeana_model::{BaselineInput, DomainError, ErrorCode};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EngineDependencyMode {
    #[default]
    Resolved,
    SourceOnlyRequested,
    SourceOnlyFallback,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct EngineRequest {
    pub version: u32,
    pub compact_inputs: bool,
    pub input_table: Vec<w::JavaSource>,
    pub classpath_table: Vec<String>,
    pub facts: bool,
    pub java_home: String,
    pub bridge: String,
    pub scratch: PathBuf,
    pub contexts: Vec<EngineContext>,
    pub parallelism: u32,
    pub full: bool,
    pub baseline_summary: BaselineInput,
    pub profile: String,
    pub cache_root: Option<PathBuf>,
    pub signature_cache: bool,
    pub signature_pin_bytes: u64,
    pub result_bytes: u64,
    pub fact_limit: u64,
    pub compiler_heap_bytes: u64,
    pub compiler_timeout_seconds: u32,
    pub dependency_mode: EngineDependencyMode,
    pub dependency_failure: Option<w::MavenDependencyFailure>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct EngineContext {
    pub version: u32,
    pub type_graph: bool,
    pub context: String,
    pub module: String,
    pub world: String,
    pub scope: String,
    pub prerequisite: bool,
    pub dependency_modules: Vec<String>,
    pub source_roots: Vec<String>,
    pub sources: Vec<EngineSource>,
    pub classpath: Vec<String>,
    pub digest: String,
    pub lombok: String,
    pub lombok_strict: bool,
    pub external_policy_configured: bool,
    pub external_exclude_patterns: Vec<String>,
    pub spring: bool,
    pub spring_xml: bool,
    pub sofa: bool,
    pub model_decode_bytes: u64,
    pub model_archive_bytes: u64,
    pub model_record_limit: u32,
    pub resources: Vec<EngineResource>,
    pub source_indices: Vec<u32>,
    pub publish_indices: Vec<u32>,
    pub classpath_indices: Vec<u32>,
    pub resource_indices: Vec<u32>,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct EngineSource {
    pub path: String,
    pub hash: String,
    pub module: String,
    pub world: String,
    pub scope: String,
    pub auxiliary: bool,
    pub input: String,
    pub publish: bool,
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct EngineResource {
    pub unit: Option<w::SourceUnit>,
    pub input: String,
}
impl EngineRequest {
    pub fn new(
        config: &w::JavaConfig,
        analyze: &w::Analyze,
        cache_root: Option<PathBuf>,
    ) -> Result<Self> {
        let limits = OutputLimits::from_config(config)?;
        Ok(Self {
            version: 10,
            compact_inputs: config.compact_inputs,
            input_table: config.input_table.clone(),
            classpath_table: config.classpath_table.clone(),
            facts: true,
            java_home: config.java_home.clone(),
            bridge: config.bridge.clone(),
            scratch: config.scratch.clone().into(),
            contexts: config
                .contexts
                .iter()
                .map(EngineContext::try_from)
                .collect::<Result<_>>()?,
            parallelism: config.parallelism,
            full: analyze.full,
            baseline_summary: if analyze.baseline_summary_json.is_empty() {
                BaselineInput::default()
            } else {
                serde_json::from_slice(&analyze.baseline_summary_json)?
            },
            profile: analyze.profile.clone(),
            signature_cache: cache_root.is_some(),
            signature_pin_bytes: if cache_root.is_some() {
                128_u64 << 20
            } else {
                256_u64 << 20
            },
            cache_root,
            result_bytes: limits.bytes,
            fact_limit: limits.facts,
            compiler_heap_bytes: config.compiler_heap_bytes,
            compiler_timeout_seconds: if config.compiler_timeout_seconds == 0 {
                120
            } else {
                config.compiler_timeout_seconds
            },
            dependency_mode: match w::JavaDependencyMode::try_from(config.dependency_mode)
                .context("JAVA_DEPENDENCY_MODE")?
            {
                w::JavaDependencyMode::Resolved => EngineDependencyMode::Resolved,
                w::JavaDependencyMode::SourceOnlyRequested => {
                    EngineDependencyMode::SourceOnlyRequested
                }
                w::JavaDependencyMode::SourceOnlyFallback => {
                    EngineDependencyMode::SourceOnlyFallback
                }
            },
            dependency_failure: config.dependency_failure.clone(),
        })
    }
    pub fn rebase(&mut self, before: &std::path::Path, after: &std::path::Path) {
        fn path(value: &mut String, before: &std::path::Path, after: &std::path::Path) {
            let original = std::path::PathBuf::from(value.as_str());
            let canonical = original.canonicalize().unwrap_or(original);
            if let Ok(relative) = canonical.strip_prefix(before) {
                *value = after.join(relative).to_string_lossy().into_owned();
            }
        }
        if let Ok(relative) = self.scratch.strip_prefix(before) {
            self.scratch = after.join(relative);
        }
        path(&mut self.bridge, before, after);
        for source in &mut self.input_table {
            path(&mut source.input, before, after);
        }
        for entry in &mut self.classpath_table {
            path(entry, before, after);
        }
        if let BaselineInput::File(file) = &mut self.baseline_summary
            && let Ok(relative) = file.path.strip_prefix(before)
        {
            file.path = after.join(relative);
        }
        for context in &mut self.contexts {
            for source in &mut context.sources {
                path(&mut source.input, before, after);
            }
            for resource in &mut context.resources {
                path(&mut resource.input, before, after);
            }
            for entry in &mut context.classpath {
                path(entry, before, after);
            }
        }
    }
}
impl TryFrom<&w::JavaContext> for EngineContext {
    type Error = anyhow::Error;
    fn try_from(c: &w::JavaContext) -> Result<Self> {
        let sources = c
            .sources
            .iter()
            .map(|s| {
                let u = s
                    .unit
                    .as_ref()
                    .context(DomainError::new(ErrorCode::SourceUnitRequired, ""))?;
                Ok(EngineSource {
                    path: u.path.clone(),
                    hash: u.hash.clone(),
                    module: u.module.clone(),
                    world: u.world.clone(),
                    scope: u.scope.clone(),
                    auxiliary: u.auxiliary,
                    input: s.input.clone(),
                    publish: s.publish,
                })
            })
            .collect::<Result<_>>()?;
        Ok(Self {
            version: 10,
            type_graph: true,
            context: c.id.clone(),
            module: c.module.clone(),
            world: c.world.clone(),
            scope: c.scope.clone(),
            prerequisite: c.prerequisite,
            dependency_modules: c.dependency_modules.clone(),
            source_roots: c.source_roots.clone(),
            sources,
            classpath: c.classpath.clone(),
            digest: c.digest.clone(),
            lombok: c.lombok.clone(),
            lombok_strict: c.lombok_strict,
            external_policy_configured: c.external_policy_configured,
            external_exclude_patterns: c.external_exclude_patterns.clone(),
            spring: c.spring,
            spring_xml: c.spring_xml,
            sofa: c.sofa,
            model_decode_bytes: if c.model_decode_bytes == 0 {
                512 * 1024 * 1024
            } else {
                c.model_decode_bytes
            },
            model_archive_bytes: if c.model_archive_bytes == 0 {
                64 * 1024 * 1024
            } else {
                c.model_archive_bytes
            },
            model_record_limit: if c.model_record_limit == 0 {
                100000
            } else {
                c.model_record_limit
            },
            resources: c
                .resources
                .iter()
                .map(|r| EngineResource {
                    unit: r.unit.clone(),
                    input: r.input.clone(),
                })
                .collect(),
            source_indices: c.source_indices.clone(),
            publish_indices: c.publish_indices.clone(),
            classpath_indices: c.classpath_indices.clone(),
            resource_indices: c.resource_indices.clone(),
        })
    }
}
