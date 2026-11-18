//! Read-only product readiness projection from typed configuration and bundle metadata.
use super::*;
#[derive(Serialize)]
struct Capabilities<'a> {
    files_snapshot: &'a str,
    search_text: &'a str,
    java_index: &'a str,
    auto_discovery: &'a str,
    rust_source_analysis: &'a str,
    diff: &'a str,
    python_source_analysis: &'a str,
    typescript_source_analysis: &'a str,
}
#[derive(Serialize)]
struct ModuleSummary<'a> {
    id: &'a str,
    language: &'a str,
    worlds: &'a [codeana_config::World],
}
#[derive(Serialize)]
struct DoctorRecord<'a> {
    generation: &'a str,
    effective: &'a codeana_config::Effective,
    modules: Vec<ModuleSummary<'a>>,
    capabilities: Capabilities<'a>,
    java_adapter: &'a str,
    java_backend: Option<&'a str>,
    java_bundle: &'a Option<codeana_contracts::bundle::BundleManifest>,
    java_sdk_state: SdkMetadataState,
    java_sdk_error: Option<String>,
    java_toolchain: Option<java::ToolchainEvidence>,
    java_toolchain_error: Option<String>,
    worker_memory_enforcement: &'a str,
    worker_memory_cap_bytes: u64,
    rust_worker_memory_bytes: Option<u64>,
    python_worker: serde_json::Value,
    typescript_worker: serde_json::Value,
    preparation: Option<PreparationReadiness<'a>>,
}
#[derive(Serialize)]
struct PreparationReadiness<'a> {
    entries: &'a [codeana_config::inputs::InputEntry],
    manifest: &'a str,
    catalog_state: &'a str,
    native_expansion: &'a str,
    execution: &'a str,
    readiness: &'a str,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
enum SdkMetadataState {
    Missing,
    Invalid,
    PresentUnverified,
}

struct SdkInspection {
    bundle: Option<codeana_contracts::bundle::BundleManifest>,
    state: SdkMetadataState,
    error: Option<String>,
}

fn inspect_java_sdk(
    library: Option<&Path>,
    runtime: Option<&str>,
    locator_error: Option<String>,
) -> SdkInspection {
    let Some(path) = library else {
        return SdkInspection {
            bundle: None,
            state: SdkMetadataState::Missing,
            error: locator_error,
        };
    };
    let parsed = (|| -> Result<codeana_contracts::bundle::BundleManifest> {
        let parent = path.parent().context("SDK_MANIFEST_PATH")?;
        let bytes = codeana_config::bounded_read(&parent.join("manifest.json"), 1 << 20)?;
        let manifest = codeana_contracts::bundle::BundleManifest::parse(&bytes)?;
        codeana_contracts::java::sdk::validate_identity(
            &manifest,
            runtime.context("SDK_BACKEND_REQUIRED")?,
        )?;
        ensure!(manifest.platform_matches(), "SDK_PLATFORM_MISMATCH");
        Ok(manifest)
    })();
    match parsed {
        Ok(bundle) => SdkInspection {
            bundle: Some(bundle),
            state: SdkMetadataState::PresentUnverified,
            error: None,
        },
        Err(error) => SdkInspection {
            bundle: None,
            state: SdkMetadataState::Invalid,
            error: Some(format!("{error:#}")),
        },
    }
}
#[derive(Clone)]
pub(super) struct DoctorService {
    generation: String,
    workers: Arc<worker_service::WorkerService>,
}
impl DoctorService {
    pub(super) fn new(generation: String, workers: Arc<worker_service::WorkerService>) -> Self {
        Self {
            generation,
            workers,
        }
    }

    pub(super) fn doctor(&self, root: &Path, req: &Request) -> Result<Output> {
        let effective = codeana_config::resolve_files(root, req)?;
        let python_discovery =
            codeana_config::python_inputs::discover(root, &effective.config, true);
        let python_input_error = python_discovery
            .as_ref()
            .err()
            .map(|error| format!("{error:#}"));
        let python_inputs = python_discovery
            .as_ref()
            .ok()
            .and_then(|inputs| inputs.as_ref());
        let java = effective
            .config
            .languages
            .java
            .as_ref()
            .is_some_and(|l| l.enabled == Some(true));
        let (java_toolchain, java_toolchain_error) = if java && !self.workers.is_mock() {
            match self.workers.java_toolchain(&effective) {
                Ok(evidence) => (Some(evidence), None),
                Err(error) => (None, Some(format!("{error:#}"))),
            }
        } else {
            (None, None)
        };
        let discovery = effective.config.discovery.mode == "explicit";
        let (library, java_sdk_error) = match java::installed_library() {
            Ok(path) => (Some(path), None),
            Err(error) => (None, Some(format!("{error:#}"))),
        };
        let backend = library.as_ref().map(|path| {
            if path.extension().is_some_and(|s| s == "jar") {
                "jvm"
            } else {
                "native"
            }
        });
        let inspection = inspect_java_sdk(library.as_deref(), backend, java_sdk_error);
        let bundle = inspection.bundle;
        let java_sdk_state = inspection.state;
        let java_sdk_error = inspection.error;
        let adapter = bundle
            .as_ref()
            .map(|value| value.adapter.as_str())
            .filter(|value| !value.is_empty())
            .unwrap_or("unavailable");
        let preparation = effective
            .config
            .inputs
            .as_ref()
            .map(|inputs| PreparationReadiness {
                entries: &inputs.entries,
                manifest: &inputs.manifest,
                catalog_state: if root.join(&inputs.manifest).is_file() {
                    "present_unverified"
                } else {
                    "missing"
                },
                native_expansion: "determined_during_preparation",
                execution: "index --prepare; external metadata only; no target compilation",
                readiness: "requires_index_validation",
            });
        Ok(Output {
            records: vec![Record::display(&DoctorRecord {
                preparation,
                generation: &self.generation,
                effective: &effective,
                modules: effective
                    .config
                    .modules
                    .iter()
                    .map(|m| ModuleSummary {
                        id: &m.id,
                        language: &m.language,
                        worlds: &m.worlds,
                    })
                    .collect(),
                capabilities: Capabilities {
                    files_snapshot: "available",
                    search_text: "available",
                    java_index: if java && discovery && self.workers.is_mock() {
                        "mock_fixture_only"
                    } else if java && backend == Some("jvm") {
                        "java8_java17_jvm"
                    } else if java && backend == Some("native") {
                        "java8_java17_native"
                    } else {
                        "unavailable"
                    },
                    auto_discovery: if !self.workers.is_mock() && self.workers.java_available() {
                        "java_metadata_only"
                    } else {
                        "unavailable"
                    },
                    rust_source_analysis: "rust-analyzer-0.0.349; schema 2 frozen inputs; direct dependencies; no explicit macro expansion",
                    typescript_source_analysis: "pinned typescript-go; JS/TS shared frozen Program; full analysis or identical-input reuse; static bindings; no CHA",
                    diff: "existing_behavior",
                    python_source_analysis: "pyrefly-1.4.0; explicit frozen inputs; full analysis or identical-input reuse; static bindings; no CHA",
                },
                typescript_worker: {
                    let sdk = crate::typescript_inputs::installed_sdk();
                    serde_json::json!({
                        "binary_present": std::env::current_exe()?.with_file_name("codeana-typescript-worker").is_file(),
                        "sdk": sdk.as_ref().ok(),
                        "sdk_error": sdk.as_ref().err().map(|e| format!("{e:#}")),
                        "readiness": "requires_index_validation",
                        "worker_protocol": WORKER_PROTOCOL_VERSION,
                        "abi": typescript::ABI_VERSION,
                        "analysis_request": typescript::ANALYSIS_VERSION,
                        "input_schema": typescript::INPUT_SCHEMA,
                        "frozen_input_schema": typescript::FROZEN_INPUT_SCHEMA,
                        "minimum_adapter_revision": typescript::ADAPTER_REVISION,
                        "javascript_enabled": effective.config.languages.javascript.as_ref().is_some_and(|l| l.enabled == Some(true)),
                        "typescript_enabled": effective.config.languages.typescript.as_ref().is_some_and(|l| l.enabled == Some(true)),
                        "result_bytes": effective.config.resources.index.typescript_result_bytes.unwrap_or(MAX_RESULT as u64),
                        "fact_limit": effective.config.resources.index.typescript_fact_limit.unwrap_or(MAX_FACTS as u64),
                        "scratch_bytes": effective.config.resources.index.typescript_scratch_bytes.unwrap_or(512 << 20),
                    })
                },
                java_adapter: if self.workers.is_mock() {
                    "mock-fixture-v1"
                } else {
                    adapter
                },
                java_backend: backend,
                java_bundle: &bundle,
                java_sdk_state,
                java_sdk_error,
                java_toolchain,
                java_toolchain_error,
                python_worker: serde_json::json!({
                    "binary_present": std::env::current_exe()?.with_file_name("codeana-python-worker").is_file(),
                    "adapter_version": python::ADAPTER_VERSION,
                    "backend_commit": python::BACKEND_COMMIT,
                    "input_schema": python::INPUT_SCHEMA,
                    "accepted_input_schemas": [python::INPUT_SCHEMA],
                    "project_metadata": python_inputs.and_then(|i| i.metadata.as_ref()),
                    "controls": python_inputs.map(|i| &i.controls),
                    "input_error": python_input_error,
                    "worker_protocol": WORKER_PROTOCOL_VERSION,
                    "enabled": effective.config.languages.python.as_ref().is_some_and(|p| p.enabled == Some(true)),
                    "inputs_manifest": effective.config.languages.python.as_ref().and_then(|p| p.discovery.inputs_manifest.as_ref()),
                    "readiness": "requires_index_validation",
                    "result_bytes": effective.config.resources.index.python_result_bytes.unwrap_or(MAX_RESULT as u64),
                    "fact_limit": effective.config.resources.index.python_fact_limit.unwrap_or(MAX_FACTS as u64),
                    "scratch_bytes": effective.config.resources.index.python_scratch_bytes.unwrap_or(512 << 20),
                }),
                worker_memory_enforcement: "process_group_rss_sample_100ms",
                worker_memory_cap_bytes: self.workers.memory_cap(),
                rust_worker_memory_bytes: effective
                    .config
                    .languages
                    .rust
                    .as_ref()
                    .filter(|l| l.enabled == Some(true))
                    .map(|_| {
                        effective
                            .config
                            .resources
                            .index
                            .worker_memory_bytes
                            .unwrap_or(codeana_model::rust::DEFAULT_WORKER_MEMORY)
                    }),
            })?],
            evidence: Evidence::complete(),
            ..Default::default()
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn metadata_inspection_never_certifies_unverified_sdk_file_bytes() {
        let directory = tempfile::tempdir().unwrap();
        let library = directory.path().join("library.dylib");
        std::fs::write(&library, "different from the advertised SHA").unwrap();
        let manifest = json!({"version":4, "worker_protocol":WORKER_PROTOCOL_VERSION,
            "fact_ir":VERSION, "graph_schema":codeana_model::GRAPH_SCHEMA_VERSION, "graph_encoding":"GRA1-protobuf-row-v1",
            "os":if cfg!(target_os="macos") {"Darwin"} else {"Linux"},
            "arch":if cfg!(target_arch="aarch64") {"arm64"} else {"x86_64"},
            "adapter":"anatomist-4a903272-codeana-graph7-resource2-analysis",
            "files":{"native/library.dylib":"0".repeat(64)},
            "backend":{"kind":"java", "analysis_request":JAVA_ANALYSIS_VERSION,
                "compiler_batch_protocol":2, "java_model":"javac-model-v51", "target_java":[8,17],
                "compiler_bridges":{"8":"compiler-bridge.jar", "17":"compiler-bridge17.jar"},
                "runtime":{"kind":"native", "abi":4, "gc":"G1", "build_mode":"fixture"}}});
        std::fs::write(
            directory.path().join("manifest.json"),
            serde_json::to_vec(&manifest).unwrap(),
        )
        .unwrap();
        let inspected = inspect_java_sdk(Some(&library), Some("native"), None);
        assert_eq!(inspected.state, SdkMetadataState::PresentUnverified);
        assert!(inspected.bundle.is_some());
        assert!(inspected.error.is_none());
        assert_eq!(
            inspect_java_sdk(Some(&library), Some("jvm"), None).state,
            SdkMetadataState::Invalid
        );
    }

    #[test]
    fn missing_and_retired_sdk_metadata_remain_distinct_and_keep_errors() {
        let inspected = inspect_java_sdk(None, None, Some("locator failure".into()));
        assert_eq!(inspected.state, SdkMetadataState::Missing);
        assert_eq!(inspected.error.as_deref(), Some("locator failure"));
        let directory = tempfile::tempdir().unwrap();
        let library = directory.path().join("library.dylib");
        std::fs::write(
            directory.path().join("manifest.json"),
            br#"{"version":3,"backend":"native"}"#,
        )
        .unwrap();
        let inspected = inspect_java_sdk(Some(&library), Some("native"), None);
        assert_eq!(inspected.state, SdkMetadataState::Invalid);
        assert!(inspected.bundle.is_none());
        assert!(inspected.error.unwrap().contains("rebuild SDK"));
    }
}
