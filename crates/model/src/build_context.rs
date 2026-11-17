//! Compiler-neutral discovered context; engine settings cannot cross backend variants.
use crate::{AnalysisBackend, AnalysisUnitId, Manifest, SourceLanguage, digest};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BuildContext {
    pub unit: AnalysisUnitId,
    pub source_languages: Vec<SourceLanguage>,
    pub modules: Vec<String>,
    pub worlds: Vec<String>,
    pub semantic_digest: String,
    pub producer: String,
    pub capabilities: Vec<String>,
    pub details: BackendContext,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "backend", rename_all = "lowercase", deny_unknown_fields)]
pub enum BackendContext {
    Java {
        source_version: String,
        classpath: Vec<String>,
        toolchain: JavaToolchainContext,
    },
    Rust {
        toolchain: String,
        adapter: String,
    },
    Python {
        python_version: String,
        platform: String,
        adapter: String,
        backend_commit: String,
    },
    TypeScript {
        compiler_version: String,
        upstream_commit: String,
        adapter: String,
    },
}
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct JavaToolchainContext {
    pub digest: String,
    pub home: String,
    pub version: String,
    pub aliases: Vec<String>,
    pub compiler_bridge: String,
    pub adapter: String,
}
impl BackendContext {
    fn semantic_identity(&self) -> String {
        match self {
            Self::Java {
                source_version,
                classpath,
                toolchain,
            } => digest(&(
                "java",
                source_version,
                classpath,
                &toolchain.digest,
                &toolchain.version,
                &toolchain.aliases,
                &toolchain.compiler_bridge,
                &toolchain.adapter,
            )),
            _ => digest(self),
        }
    }
    pub const fn backend(&self) -> AnalysisBackend {
        match self {
            Self::Java { .. } => AnalysisBackend::Java,
            Self::Rust { .. } => AnalysisBackend::Rust,
            Self::Python { .. } => AnalysisBackend::Python,
            Self::TypeScript { .. } => AnalysisBackend::TypeScript,
        }
    }
}
impl BuildContext {
    pub fn new(unit: &str, details: BackendContext) -> Self {
        let source_languages = match details.backend() {
            AnalysisBackend::Java => vec![SourceLanguage::Java],
            AnalysisBackend::Rust => vec![SourceLanguage::Rust],
            AnalysisBackend::Python => vec![SourceLanguage::Python],
            AnalysisBackend::TypeScript => vec![SourceLanguage::TypeScript],
        };
        let mut result = Self {
            unit: unit.into(),
            source_languages,
            modules: vec![],
            worlds: vec![],
            semantic_digest: String::new(),
            producer: String::new(),
            capabilities: vec![],
            details,
        };
        result.seal();
        result
    }
    pub fn backend(&self) -> AnalysisBackend {
        self.details.backend()
    }
    /// Source content and IPC usage must not turn a body edit into a profile change.
    pub fn expected_digest(&self) -> String {
        digest(&(
            &self.unit,
            &self.source_languages,
            &self.modules,
            &self.worlds,
            &self.producer,
            &self.capabilities,
            self.details.semantic_identity(),
        ))
    }
    pub fn seal(&mut self) {
        self.semantic_digest = self.expected_digest();
    }
    pub fn worlds_for(manifest: &Manifest, backend: AnalysisBackend) -> Vec<String> {
        manifest
            .files
            .iter()
            .filter(|s| SourceLanguage::lookup(&s.language).is_some_and(|l| l.backend() == backend))
            .map(|s| s.world.clone())
            .collect::<std::collections::BTreeSet<_>>()
            .into_iter()
            .collect()
    }
    pub fn java_source_version(&self) -> Option<&str> {
        match &self.details {
            BackendContext::Java { source_version, .. } => Some(source_version),
            _ => None,
        }
    }
}

#[cfg(test)]
mod semantic_tests {
    use super::*;
    #[test]
    fn java_toolchain_location_is_provenance_while_digest_and_dependency_order_are_semantic() {
        let mut context = BuildContext::new(
            "app",
            BackendContext::Java {
                source_version: "17".into(),
                classpath: vec!["sha-a".into(), "sha-b".into()],
                toolchain: JavaToolchainContext {
                    home: "/jdk/a".into(),
                    digest: "compiler".into(),
                    ..Default::default()
                },
            },
        );
        let identity = context.semantic_digest.clone();
        if let BackendContext::Java { toolchain, .. } = &mut context.details {
            toolchain.home = "/jdk/b".into();
        }
        context.seal();
        assert_eq!(identity, context.semantic_digest);
        if let BackendContext::Java { classpath, .. } = &mut context.details {
            classpath.reverse();
        }
        context.seal();
        assert_ne!(identity, context.semantic_digest);
    }
}
