//! Explicit, local input preparation. The daemon never executes this entry.
use crate::{inputs, native::Native};
use anyhow::{Context, Result, ensure};
use clap::Args;
use codeana_contracts::{analysis_budget, protobuf::typescript::v1 as p};
use codeana_model::{AnalysisBackend, AnalysisContext, AnalysisGrant, AnalysisLimits};
use codeana_worker_runtime::ffi::{BudgetHandoff, Notification, Owner};
use prost::Message;
use std::{
    os::fd::AsRawFd,
    path::{Path, PathBuf},
    time::{Duration, Instant},
};

#[derive(Args, Default)]
pub struct PreparationArgs {
    #[arg(long)]
    pub prepare_project: Option<PathBuf>,
    #[arg(long)]
    pub prepare_config: Option<PathBuf>,
    #[arg(long)]
    pub output: Option<PathBuf>,
    #[arg(long)]
    pub scratch: Option<PathBuf>,
    #[arg(long)]
    pub unit: Option<String>,
    #[arg(long)]
    pub module: Option<String>,
    #[arg(long)]
    pub world: Option<String>,
    #[arg(long, default_value = "MAIN")]
    pub scope: String,
    #[arg(long)]
    pub publish_language: Vec<String>,
    #[arg(long)]
    pub allowed_root: Vec<PathBuf>,
    #[arg(long, default_value_t = 120_000)]
    pub prepare_deadline_ms: u64,
    #[arg(long)]
    pub max_work: Option<u64>,
    #[arg(long)]
    pub max_nodes: Option<u64>,
    #[arg(long)]
    pub max_payload_bytes: Option<u64>,
    #[arg(long)]
    pub max_depth: Option<u32>,
}

pub async fn run(args: PreparationArgs, owner: &mut Owner<Native>) -> Result<()> {
    let started = Instant::now();
    let project = args
        .prepare_project
        .context("--prepare-project required")?
        .canonicalize()?;
    let config = args.prepare_config.context("--prepare-config required")?;
    ensure!(
        config.is_absolute()
            && !config.is_symlink()
            && config.canonicalize()?.starts_with(&project),
        "TYPESCRIPT_CONFIG_PATH"
    );
    let config = config.canonicalize()?;
    let output = args.output.context("--output required")?;
    let scratch = args.scratch.context("--scratch required")?;
    ensure!(
        output.is_absolute()
            && !output.exists()
            && scratch.is_absolute()
            && !scratch.exists()
            && !scratch.starts_with(&project),
        "TYPESCRIPT_NEW_OUTPUT_REQUIRED"
    );
    ensure!(
        matches!(args.scope.as_str(), "MAIN" | "TEST")
            && (1..=codeana_model::MAX_TASK_DEADLINE_SECONDS * 1000)
                .contains(&args.prepare_deadline_ms),
        "TYPESCRIPT_PREPARATION_OPTIONS"
    );
    let unit = args.unit.context("--unit required")?;
    let mut roots = vec![project.clone()];
    for root in args.allowed_root {
        let root = root.canonicalize()?;
        ensure!(
            root != Path::new("/") && root.is_dir(),
            "TYPESCRIPT_ALLOWED_ROOT"
        );
        if !roots.contains(&root) {
            roots.push(root);
        }
    }
    let languages = args
        .publish_language
        .iter()
        .map(|language| match language.as_str() {
            "javascript" => Ok(p::SourceLanguage::Javascript as i32),
            "typescript" => Ok(p::SourceLanguage::Typescript as i32),
            _ => anyhow::bail!("TYPESCRIPT_PUBLICATION_LANGUAGE"),
        })
        .collect::<Result<Vec<_>>>()?;
    ensure!(!languages.is_empty(), "--publish-language required");
    let defaults = AnalysisLimits::default();
    let limits = AnalysisLimits {
        max_work: args.max_work.unwrap_or(defaults.max_work),
        max_nodes: args.max_nodes.unwrap_or(defaults.max_nodes),
        max_payload_bytes: args.max_payload_bytes.unwrap_or(defaults.max_payload_bytes),
        max_depth: args.max_depth.unwrap_or(defaults.max_depth),
    };
    let budget = AnalysisContext::new(
        limits,
        Some(started + Duration::from_millis(args.prepare_deadline_ms)),
    )?;
    let grant = AnalysisGrant {
        id: format!("prepare/{unit}"),
        language: AnalysisBackend::TypeScript,
        limits,
    };
    let (runtime, compiler) = owner
        .call(|native| Ok((native.runtime.clone(), native.compiler.clone())))
        .await?;
    let case_sensitive = codeana_input_io::case_sensitive_path(&config, |work, bytes| {
        budget.charge(work, 0, bytes)?;
        Ok(())
    })?;
    std::fs::create_dir(&scratch)?;
    let scratch = scratch.canonicalize()?;
    let mut request = p::Request {
        version: codeana_model::typescript::ANALYSIS_VERSION,
        request_id: format!("prepare/{unit}"),
        expected_runtime: Some(runtime.clone()),
        grant: Some(analysis_budget::encode_grant(&grant)),
        seed: Some(Default::default()),
        deadline_ms: Some(
            args.prepare_deadline_ms
                .saturating_sub(started.elapsed().as_millis() as u64),
        ),
        operation: Some(p::request::Operation::Prepare(p::Prepare {
            unit: unit.clone(),
            project_root: project.to_string_lossy().into_owned(),
            config_path: config.to_string_lossy().into_owned(),
            publish_languages: languages,
            allowed_roots: roots
                .iter()
                .map(|root| root.to_string_lossy().into_owned())
                .collect(),
            module: args.module.context("--module required")?,
            scope: args.scope,
            world: args.world.context("--world required")?,
            scratch: scratch.to_string_lossy().into_owned(),
            inventory_bytes: 32 << 20,
            file_limit: 100_000,
            scratch_bytes: 32 << 20,
            case_sensitive: Some(case_sensitive),
        })),
    };
    budget.charge(1, 16, (request.encoded_len() as u64 + 128) * 16 + (4 << 20))?;
    let seed = budget.usage();
    request.seed = Some(analysis_budget::encode_usage(&seed));
    let data = request.encode_to_vec();
    let (notification, writer) = Notification::pair()?;
    let mut handoff = BudgetHandoff::begin(&budget, &seed)?;
    owner
        .call(move |native| {
            native.prepare(data, writer.as_raw_fd())?;
            native.run()
        })
        .await?;
    let waited = tokio::time::timeout(
        Duration::from_millis(
            args.prepare_deadline_ms
                .saturating_sub(started.elapsed().as_millis() as u64)
                + 1000,
        ),
        notification.wait_eof(),
    )
    .await;
    if waited.is_err() {
        let _ = owner.call(Native::cancel).await;
        anyhow::bail!("TYPESCRIPT_PREPARE_TIMEOUT");
    }
    waited??;
    let bytes = owner.call(Native::result).await?;
    let result = codeana_contracts::typescript::decode_result(&bytes)?;
    ensure!(
        result.runtime.as_ref() == Some(&runtime) && result.request_id == request.request_id,
        "TYPESCRIPT_PREPARATION_IDENTITY"
    );
    let receipt = result
        .receipt
        .as_ref()
        .context("TYPESCRIPT_PREPARATION_RECEIPT")?;
    ensure!(
        receipt.usage_known == Some(true),
        "TYPESCRIPT_PREPARATION_USAGE_UNKNOWN"
    );
    handoff.complete(analysis_budget::decode_receipt(receipt, &grant)?)?;
    drop(handoff);
    let archive = match result.outcome {
        Some(p::result::Outcome::Prepared(prepared)) => prepared
            .inventory
            .context("TYPESCRIPT_INVENTORY_REQUIRED")?,
        Some(p::result::Outcome::Failure(failure)) => anyhow::bail!(
            "TYPESCRIPT_PREPARE_FAILED: {} {}",
            failure.code,
            failure.message
        ),
        _ => anyhow::bail!("TYPESCRIPT_PREPARATION_OUTCOME"),
    };
    ensure!(
        codeana_model::safe_path(&archive.path)
            && archive.bytes <= 32 << 20
            && archive.count <= 100_000,
        "TYPESCRIPT_INVENTORY_LIMIT"
    );
    let path = scratch.join(&archive.path);
    let metadata = std::fs::symlink_metadata(&path)?;
    ensure!(
        metadata.is_file()
            && !metadata.file_type().is_symlink()
            && metadata.len() == archive.bytes
            && path.canonicalize()?.starts_with(&scratch),
        "TYPESCRIPT_INVENTORY_FILE"
    );
    budget.charge(
        archive.count + 1,
        archive.bytes + 1,
        archive.bytes * 128 + 4096,
    )?;
    let bytes = std::fs::read(&path)?;
    ensure!(
        bytes.len() as u64 == archive.bytes && codeana_model::hash(&bytes) == archive.sha256,
        "TYPESCRIPT_INVENTORY_SHA"
    );
    let inventory = codeana_contracts::typescript::decode_inventory(&bytes)?;
    ensure!(
        inventory.files.len() as u64 == archive.count
            && inventory
                .context
                .as_ref()
                .is_some_and(|context| context.unit == unit),
        "TYPESCRIPT_INVENTORY_CONTEXT"
    );
    let inputs = inputs::from_inventory(inventory, compiler)?;
    let mut encoded = MeteredJson {
        bytes: Vec::new(),
        budget: &budget,
    };
    serde_json::to_writer_pretty(&mut encoded, &inputs)?;
    let bytes = encoded.bytes;
    use std::io::Write;
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&output)?;
    file.write_all(&bytes)?;
    file.sync_all()?;
    owner.call(Native::close).await?;
    owner.shutdown().await?;
    println!(
        "{}",
        serde_json::json!({"prepared":true,"output":output,"contexts":inputs.contexts.len(),"usage":budget.usage()})
    );
    Ok(())
}

struct MeteredJson<'a> {
    bytes: Vec<u8>,
    budget: &'a AnalysisContext,
}
impl std::io::Write for MeteredJson<'_> {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        let length = self
            .bytes
            .len()
            .checked_add(bytes.len())
            .ok_or_else(|| std::io::Error::other("TYPESCRIPT_INVENTORY_LIMIT"))?;
        if length > 32 << 20 {
            return Err(std::io::Error::other("TYPESCRIPT_INVENTORY_LIMIT"));
        }
        self.budget
            .charge(1, 0, bytes.len() as u64 * 2 + 128)
            .map_err(std::io::Error::other)?;
        if self.bytes.capacity() < length {
            let capacity = self
                .bytes
                .capacity()
                .max(2048)
                .saturating_mul(2)
                .max(length)
                .min(32 << 20);
            self.budget
                .charge(1, 0, (capacity + self.bytes.capacity()) as u64)
                .map_err(std::io::Error::other)?;
            self.bytes
                .try_reserve_exact(capacity - self.bytes.len())
                .map_err(std::io::Error::other)?;
        }
        self.bytes.extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}
