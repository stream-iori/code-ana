//! Java SDK facts shared by assembly and the worker; no engine loading policy.
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub fn validate_identity(
    manifest: &crate::bundle::BundleManifest,
    runtime: &str,
) -> anyhow::Result<()> {
    manifest.validate_protocol(codeana_model::AnalysisBackend::Java)?;
    let java = manifest.java()?;
    anyhow::ensure!(
        java.runtime.kind() == runtime
            && java.analysis_request == codeana_model::JAVA_ANALYSIS_VERSION
            && java.compiler_batch_protocol == 2
            && java.java_model == "javac-model-v51"
            && manifest.adapter == "anatomist-4a903272-codeana-graph6-resource2-analysis"
            && manifest.graph_schema == 36
            && java.target_java == [8, 17]
            && java.compiler_bridges
                == BTreeMap::from([
                    ("8".into(), "compiler-bridge.jar".into()),
                    ("17".into(), "compiler-bridge17.jar".into())
                ])
            && (runtime == "jvm" || java.runtime.abi() == Some(4)),
        "SDK_JAVA_VERSION_MISMATCH: rebuild SDK"
    );
    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Identity {
    pub analysis_request: u32,
    pub compiler_batch_protocol: u32,
    pub java_model: String,
    pub target_java: Vec<u32>,
    pub compiler_bridges: BTreeMap<String, String>,
    pub runtime: Runtime,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase", deny_unknown_fields)]
pub enum Runtime {
    Native {
        abi: u32,
        gc: NativeGc,
        build_mode: String,
    },
    Jvm {},
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum NativeGc {
    #[serde(rename = "G1")]
    G1,
    #[serde(rename = "serial")]
    Serial,
}

impl NativeGc {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::G1 => "G1",
            Self::Serial => "serial",
        }
    }
}
impl Runtime {
    pub const fn kind(&self) -> &'static str {
        match self {
            Self::Native { .. } => "native",
            Self::Jvm { .. } => "jvm",
        }
    }
    pub const fn abi(&self) -> Option<u32> {
        match self {
            Self::Native { abi, .. } => Some(*abi),
            Self::Jvm { .. } => None,
        }
    }
}
