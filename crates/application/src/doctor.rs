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
    worker_memory_enforcement: &'a str,
    worker_memory_cap_bytes: u64,
    rust_worker_memory_bytes: Option<u64>,
}
impl Engine {
    pub(super) fn doctor(&self, root: &Path, req: &Request) -> Result<Output> {
        let effective = codeana_config::resolve_files(root, req)?;
        let java = effective
            .config
            .languages
            .java
            .as_ref()
            .is_some_and(|l| l.enabled == Some(true));
        let discovery = effective.config.discovery.mode == "explicit";
        let library = java::installed_library().ok();
        let backend = library.as_ref().map(|path| {
            if path.extension().is_some_and(|s| s == "jar") {
                "jvm"
            } else {
                "native"
            }
        });
        let bundle = library
            .as_ref()
            .and_then(|path| path.parent())
            .and_then(|root| std::fs::read(root.join("manifest.json")).ok())
            .and_then(|bytes| {
                serde_json::from_slice::<codeana_contracts::bundle::BundleManifest>(&bytes).ok()
            });
        let adapter = bundle
            .as_ref()
            .map(|value| value.adapter.as_str())
            .filter(|value| !value.is_empty())
            .unwrap_or("unavailable");
        Ok(Output {
            records: vec![Record::display(&DoctorRecord {
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
                    java_index: if java && discovery && self.mock.is_some() {
                        "mock_fixture_only"
                    } else if java && backend == Some("jvm") {
                        "java8_jvm"
                    } else if java && backend == Some("native") {
                        "java8_native"
                    } else {
                        "unavailable"
                    },
                    auto_discovery: if self.mock.is_none() && self.java_available() {
                        "java_metadata_only"
                    } else {
                        "unavailable"
                    },
                    rust_source_analysis: "rust-analyzer-0.0.349; schema 2 frozen inputs; direct dependencies; no explicit macro expansion",
                    diff: "existing_behavior",
                },
                java_adapter: if self.mock.is_some() {
                    "mock-fixture-v1"
                } else {
                    adapter
                },
                java_backend: backend,
                java_bundle: &bundle,
                worker_memory_enforcement: "process_group_rss_sample_100ms",
                worker_memory_cap_bytes: self.worker_memory,
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
