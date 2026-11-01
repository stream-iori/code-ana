//! Rust-only frozen input preparation; host execution owns language fan-out.
use super::*;
use codeana_contracts::worker::RustConfig;
use codeana_model::rust::{AnalysisConfig, FrozenFile};
#[derive(serde::Serialize)]
struct InputSummary {
    crates: usize,
    published_crates: usize,
    files: usize,
    file_instances: usize,
    source_bytes: u64,
    source_instance_bytes: u64,
    selection: codeana_model::rust::SelectionSummary,
}

pub(super) struct RustInput {
    pub(super) scratch: tempfile::TempDir,
    pub(super) config: AnalysisConfig,
    pub(super) descriptor: RustConfig,
    pub(super) manifest: Manifest,
    pub(super) memory: u64,
}
impl Engine {
    pub(super) async fn prepare_rust_inputs(
        &self,
        id: &str,
        capture: &Capture,
    ) -> Result<RustInput> {
        let scratch = tempfile::Builder::new()
            .prefix("rust-index-")
            .tempdir_in(self.index.home())?;
        let inputs = capture
            .effective
            .rust_inputs
            .clone()
            .context("RUST_INPUTS_REQUIRED")?;
        let mut files = BTreeMap::<String, FrozenFile>::new();
        for source in capture
            .manifest
            .files
            .iter()
            .filter(|s| s.language == "rust")
        {
            let file = files
                .entry(source.path.clone())
                .or_insert_with(|| FrozenFile {
                    path: source.path.clone(),
                    hash: source.hash.clone(),
                    input: capture.blobs[&source.hash].as_ref().to_path_buf(),
                    crates: vec![],
                });
            file.crates.extend(
                inputs
                    .crates
                    .iter()
                    .filter(|c| c.world == source.world)
                    .map(|c| c.id.clone()),
            );
        }
        let excluded = capture
            .manifest
            .files
            .iter()
            .filter(|s| s.language == "rust" && s.auxiliary)
            .map(|s| (s.path.clone(), s.world.clone()))
            .collect();
        let limits = &capture.effective.config.resources.index;
        let result_bytes = limits.rust_result_bytes.unwrap_or(MAX_RESULT as u64);
        let fact_limit = limits.rust_fact_limit.unwrap_or(MAX_FACTS as u64);
        ensure!(
            (65536..=1073741824).contains(&result_bytes) && (1..=2000000).contains(&fact_limit),
            "RUST_OUTPUT_LIMIT_RANGE"
        );
        let config = AnalysisConfig {
            baseline: None,
            excluded,
            inputs,
            files: files.into_values().collect(),
            mode: capture
                .effective
                .config
                .languages
                .rust
                .as_ref()
                .unwrap()
                .mode
                .parse()?,
            scratch: scratch.path().into(),
            result_bytes,
            fact_limit,
        };
        let config_io = config.clone();
        let input = scratch.path().join("inputs.json");
        let (descriptor, summary) = tokio::task::spawn_blocking(move || -> Result<_> {
            let config = config_io;
            let bytes = serde_json::to_vec(&config)?;
            let mut summary = InputSummary {
                crates: config.inputs.crates.len(),
                published_crates: config.inputs.crates.iter().filter(|c| c.publish).count(),
                files: config.files.len(),
                file_instances: 0,
                source_bytes: 0,
                source_instance_bytes: 0,
                selection: config.inputs.selection.clone(),
            };
            for file in &config.files {
                let size = std::fs::metadata(&file.input)?.len();
                summary.file_instances += file.crates.len();
                summary.source_bytes += size;
                summary.source_instance_bytes += size * file.crates.len() as u64;
            }
            std::fs::write(&input, &bytes)?;
            Ok((
                RustConfig {
                    result_bytes,
                    fact_limit,
                    input: input.to_string_lossy().into_owned(),
                    sha256: hash(&bytes),
                    bytes: bytes.len() as u64,
                },
                summary,
            ))
        })
        .await??;
        self.tasks.event(
            &TaskId::from(id),
            &Event::Progress(format!(
                "rust_input_summary {}",
                serde_json::to_string(&summary)?
            )),
        )?;
        let memory = capture
            .effective
            .config
            .resources
            .index
            .worker_memory_bytes
            .unwrap_or(codeana_model::rust::DEFAULT_WORKER_MEMORY);
        ensure!(
            memory > 0 && memory <= self.worker_memory,
            DomainError::new(ErrorCode::ProjectWorkerMemoryLimit, "")
        );
        let manifest = Manifest {
            files: capture
                .manifest
                .files
                .iter()
                .filter(|s| s.language == "rust" || s.scope == "CONTROL")
                .cloned()
                .collect(),
        };
        Ok(RustInput {
            scratch,
            config,
            descriptor,
            manifest,
            memory,
        })
    }
}
