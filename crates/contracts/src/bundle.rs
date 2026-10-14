//! Stable bundle identity fields; additional producer metadata is preserved opaquely.
use codeana_model::OpaqueMetadata;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct BundleManifest {
    pub version: u32,
    pub backend: String,
    pub worker_protocol: u32,
    pub fact_ir: u32,
    pub abi: u32,
    pub analysis_request: u32,
    pub compiler_protocol: u32,
    pub compiler_batch_protocol: u32,
    pub adapter: String,
    pub java_model: String,
    pub graph_schema: u32,
    pub graph_encoding: String,
    pub os: String,
    pub arch: String,
    pub target_java: Vec<u32>,
    pub native_gc: Option<String>,
    pub files: BTreeMap<String, String>,
    #[serde(flatten)]
    pub extensions: OpaqueMetadata,
}
