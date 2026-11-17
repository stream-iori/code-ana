//! SDK identity contract. Engine-specific fields are explicit variants, never defaults.
use codeana_model::{AnalysisBackend, OpaqueMetadata};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
mod json;
pub const SCHEMA_VERSION: u32 = 4;
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BundleManifest {
    pub version: u32,
    pub worker_protocol: u32,
    pub fact_ir: u32,
    pub adapter: String,
    pub graph_schema: u32,
    pub graph_encoding: String,
    pub os: String,
    pub arch: String,
    pub files: BTreeMap<String, String>,
    pub backend: BackendIdentity,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub producer_metadata: Option<OpaqueMetadata>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum BackendIdentity {
    Java(crate::java::sdk::Identity),
    TypeScript(TypeScriptIdentity),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TypeScriptIdentity {
    pub abi: u32,
    pub analysis_request: u32,
    pub upstream_commit: String,
    pub go_version: String,
    pub build_options: Vec<String>,
    pub standard_library: BTreeMap<String, String>,
    pub header_sha256: String,
}

impl BundleManifest {
    pub fn parse(bytes: &[u8]) -> anyhow::Result<Self> {
        use anyhow::Context;
        anyhow::ensure!(bytes.len() <= 1 << 20, "SDK_MANIFEST_BUDGET");
        json::check(bytes).context("SDK_MANIFEST_INVALID: rebuild SDK for the current format")?;
        let value: Self = serde_json::from_slice(bytes)
            .context("SDK_MANIFEST_INVALID: rebuild SDK for the current format")?;
        anyhow::ensure!(
            value.version == SCHEMA_VERSION,
            "SDK_MANIFEST_VERSION: rebuild SDK for the current format"
        );
        value.validate_structure()?;
        Ok(value)
    }
    fn validate_structure(&self) -> anyhow::Result<()> {
        fn digest(value: &str) -> bool {
            value.len() == 64
                && value
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        }
        anyhow::ensure!(
            self.worker_protocol > 0
                && self.fact_ir > 0
                && self.graph_schema > 0
                && self.graph_encoding == "GRA1-protobuf-row-v1"
                && !self.adapter.is_empty()
                && !self.os.is_empty()
                && !self.arch.is_empty(),
            "SDK_MANIFEST_INVALID"
        );
        anyhow::ensure!(
            !self.files.is_empty()
                && self
                    .files
                    .iter()
                    .all(|(name, value)| codeana_model::safe_path(name)
                        && !name.contains('\\')
                        && digest(value)),
            "SDK_FILE_IDENTITY_INVALID"
        );
        match &self.backend {
            BackendIdentity::Java(java) => {
                anyhow::ensure!(
                    java.analysis_request > 0
                        && java.compiler_batch_protocol > 0
                        && !java.java_model.is_empty()
                        && java.target_java == [8, 17]
                        && java.compiler_bridges
                            == BTreeMap::from([
                                ("8".into(), "compiler-bridge.jar".into()),
                                ("17".into(), "compiler-bridge17.jar".into())
                            ]),
                    "SDK_JAVA_IDENTITY_INVALID"
                );
                if let crate::java::sdk::Runtime::Native {
                    abi, build_mode, ..
                } = &java.runtime
                {
                    anyhow::ensure!(
                        *abi > 0 && !build_mode.is_empty(),
                        "SDK_JAVA_RUNTIME_INVALID"
                    );
                }
            }
            BackendIdentity::TypeScript(ts) => {
                anyhow::ensure!(
                    ts.abi > 0
                        && ts.analysis_request > 0
                        && ts.upstream_commit.len() == 40
                        && ts
                            .upstream_commit
                            .bytes()
                            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                        && !ts.go_version.is_empty()
                        && !ts.build_options.is_empty()
                        && ts.build_options.iter().all(|v| !v.is_empty())
                        && !ts.standard_library.is_empty()
                        && ts
                            .standard_library
                            .iter()
                            .all(|(name, value)| !name.is_empty() && digest(value))
                        && digest(&ts.header_sha256),
                    "SDK_TYPESCRIPT_IDENTITY_INVALID"
                );
            }
        }
        Ok(())
    }
    pub fn analysis_backend(&self) -> AnalysisBackend {
        match self.backend {
            BackendIdentity::Java(_) => AnalysisBackend::Java,
            BackendIdentity::TypeScript(_) => AnalysisBackend::TypeScript,
        }
    }
    pub fn validate_protocol(&self, expected: AnalysisBackend) -> anyhow::Result<()> {
        anyhow::ensure!(
            self.version == SCHEMA_VERSION
                && self.worker_protocol == codeana_model::WORKER_PROTOCOL_VERSION
                && self.fact_ir == codeana_model::VERSION
                && self.analysis_backend() == expected
                && self.graph_schema > 0
                && self.graph_encoding == "GRA1-protobuf-row-v1"
                && !self.adapter.is_empty(),
            "SDK_PROTOCOL_MISMATCH: rebuild SDK"
        );
        Ok(())
    }
    pub fn platform_matches(&self) -> bool {
        self.os
            == if cfg!(target_os = "macos") {
                "Darwin"
            } else if cfg!(target_os = "linux") {
                "Linux"
            } else {
                "unsupported"
            }
            && self.arch
                == if cfg!(target_arch = "aarch64") {
                    "arm64"
                } else if cfg!(target_arch = "x86_64") {
                    "x86_64"
                } else {
                    "unsupported"
                }
    }
    pub fn java(&self) -> anyhow::Result<&crate::java::sdk::Identity> {
        match &self.backend {
            BackendIdentity::Java(identity) => Ok(identity),
            BackendIdentity::TypeScript(_) => anyhow::bail!("SDK_BACKEND_MISMATCH: expected java"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{Value, json};

    fn manifest(backend: Value) -> Value {
        json!({"version": SCHEMA_VERSION, "worker_protocol": codeana_model::WORKER_PROTOCOL_VERSION,
            "fact_ir": codeana_model::VERSION, "adapter": "test", "graph_schema": 36,
            "graph_encoding": "GRA1-protobuf-row-v1", "os": "Darwin", "arch": "arm64",
            "files": {"library.dylib": "0".repeat(64)}, "backend": backend})
    }
    fn java(runtime: Value) -> Value {
        json!({"kind": "java", "analysis_request": 13, "compiler_batch_protocol": 2,
            "java_model": "javac-model-v51", "target_java": [8,17],
            "compiler_bridges": {"8":"compiler-bridge.jar", "17":"compiler-bridge17.jar"},
            "runtime": runtime})
    }

    #[test]
    fn explicit_engine_identities_roundtrip_without_foreign_defaults() {
        for runtime in [
            json!({"kind":"jvm"}),
            json!({"kind":"native", "abi":4, "gc":"G1", "build_mode":"fixture"}),
        ] {
            let source = manifest(java(runtime));
            let typed = BundleManifest::parse(&serde_json::to_vec(&source).unwrap()).unwrap();
            typed.validate_protocol(AnalysisBackend::Java).unwrap();
            assert_eq!(serde_json::to_value(&typed).unwrap(), source);
        }
        let mut source = manifest(json!({"kind":"typescript", "abi":1, "analysis_request":1,
            "upstream_commit":"a".repeat(40), "go_version":"go1.26.2", "build_options":["-buildmode=c-shared"],
            "standard_library":{"lib.d.ts":"b".repeat(64)}, "header_sha256":"c".repeat(64)}));
        source["producer_metadata"] = json!({"backend":"unregistered"});
        let typed = BundleManifest::parse(&serde_json::to_vec(&source).unwrap()).unwrap();
        typed
            .validate_protocol(AnalysisBackend::TypeScript)
            .unwrap();
        assert!(typed.java().is_err());
        assert_eq!(serde_json::to_value(typed).unwrap(), source);
        source["backend"]["target_java"] = json!([8, 17]);
        assert!(BundleManifest::parse(&serde_json::to_vec(&source).unwrap()).is_err());
    }

    #[test]
    fn required_fields_legacy_shapes_and_duplicate_tags_are_rejected() {
        let source = manifest(java(json!({"kind":"jvm"})));
        for field in source.as_object().unwrap().keys() {
            let mut invalid = source.clone();
            invalid.as_object_mut().unwrap().remove(field);
            assert!(
                BundleManifest::parse(&serde_json::to_vec(&invalid).unwrap()).is_err(),
                "{field}"
            );
        }
        let bytes = serde_json::to_string(&source).unwrap();
        let duplicate = bytes.replace("\"version\":4", "\"version\":4,\"version\":4");
        assert_ne!(duplicate, bytes);
        assert!(BundleManifest::parse(duplicate.as_bytes()).is_err());
        let duplicate = bytes.replace("\"kind\":\"java\"", "\"kind\":\"java\",\"kind\":\"java\"");
        assert!(BundleManifest::parse(duplicate.as_bytes()).is_err());
        let duplicate = bytes.replace("\"files\":{", "\"files\":{\"library.dylib\":\"0000000000000000000000000000000000000000000000000000000000000000\",");
        assert!(BundleManifest::parse(duplicate.as_bytes()).is_err());
        let duplicate = bytes.replace(
            "\"files\":{",
            "\"producer_metadata\":{\"x\":1,\"\\u0078\":2},\"files\":{",
        );
        assert!(BundleManifest::parse(duplicate.as_bytes()).is_err());
        assert!(BundleManifest::parse(br#"{"version":3,"backend":"native","abi":4}"#).is_err());
        let mut invalid = source;
        invalid["backend"]["runtime"]["gc"] = json!("G1");
        assert!(BundleManifest::parse(&serde_json::to_vec(&invalid).unwrap()).is_err());
    }
    #[test]
    fn sdk_json_expansion_is_bounded_before_typed_metadata_allocation() {
        let mut source = manifest(java(json!({"kind":"jvm"})));
        source["producer_metadata"] = json!({"values": vec![0;65536]});
        let error = BundleManifest::parse(&serde_json::to_vec(&source).unwrap()).unwrap_err();
        assert!(format!("{error:#}").contains("SDK_JSON_BUDGET"));
    }
}
