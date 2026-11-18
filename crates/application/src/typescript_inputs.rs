//! TypeScript descriptors retain the captured content and authenticated SDK identity.
use super::*;
use codeana_contracts::worker::TypeScriptConfig;
use codeana_model::typescript::{AnalysisConfig, CompilerIdentity};

pub(super) struct TypeScriptInput {
    pub descriptor: TypeScriptConfig,
    pub sdk: PathBuf,
    pub compiler: CompilerIdentity,
    pub languages: Vec<SourceLanguage>,
    pub scratch: Arc<tempfile::TempDir>,
}

pub(super) fn installed_sdk() -> Result<PathBuf> {
    let binary = std::env::current_exe()?;
    let sdk = std::env::var_os("CODEANA_TYPESCRIPT_SDK")
        .map(PathBuf::from)
        .unwrap_or_else(|| binary.parent().unwrap().join("../lib/typescript/current"));
    ensure!(
        sdk.is_absolute() && sdk.is_dir(),
        "TYPESCRIPT_SDK_REQUIRED: install the TypeScript SDK or set CODEANA_TYPESCRIPT_SDK"
    );
    Ok(sdk.canonicalize()?)
}

pub(super) fn prepare_inputs(
    capture: &Capture,
    directory: &Path,
    guard: Arc<tempfile::TempDir>,
    budget: Option<&AnalysisContext>,
) -> Result<TypeScriptInput> {
    let sdk = installed_sdk()?;
    if let Some(budget) = budget {
        budget
            .charge(1, 0, retained_size(&capture.manifest)?.saturating_mul(4))
            .map_err(DomainError::analysis)?;
    }
    let snapshot = codeana_config::typescript_inputs::frozen_inputs(capture)?;
    let resources = &capture.effective.config.resources.index;
    let result_bytes = resources
        .typescript_result_bytes
        .unwrap_or(MAX_RESULT as u64);
    let fact_limit = resources.typescript_fact_limit.unwrap_or(MAX_FACTS as u64);
    let scratch_bytes = resources.typescript_scratch_bytes.unwrap_or(512 << 20);
    ensure!(
        (65536..=MAX_RESULT as u64).contains(&result_bytes)
            && (1..=MAX_FACTS as u64).contains(&fact_limit)
            && scratch_bytes >= result_bytes
            && scratch_bytes <= 32u64 << 30,
        DomainError::new(
            ErrorCode::from("TYPESCRIPT_OUTPUT_LIMIT"),
            "invalid TypeScript output limits"
        )
    );
    let directory = directory.canonicalize()?;
    let config = AnalysisConfig {
        snapshot,
        scratch: directory.join("engine"),
        result_bytes,
        fact_limit,
        scratch_bytes,
    };
    let bytes = if let Some(budget) = budget {
        codeana_contracts::json_budget::admit_owned_copy(&config, budget)?;
        serde_json::to_vec(&config)?
    } else {
        serde_json::to_vec(&config)?
    };
    ensure!(bytes.len() <= 32 << 20, "TYPESCRIPT_INPUT_BUDGET");
    ensure!(
        (bytes.len() as u64)
            .checked_add(result_bytes)
            .is_some_and(|n| n <= scratch_bytes),
        DomainError::new(
            ErrorCode::from("TYPESCRIPT_SCRATCH_BUDGET"),
            "descriptor and result exceed task scratch"
        )
    );
    let input = directory.join("inputs.json");
    let descriptor = TypeScriptConfig {
        input: input.to_str().context("INPUT_PATH_UTF8")?.into(),
        sha256: hash(&bytes),
        bytes: bytes.len() as u64,
        result_bytes,
        fact_limit,
        scratch_bytes,
    };
    std::fs::create_dir(&config.scratch)?;
    std::fs::write(&input, bytes)?;
    Ok(TypeScriptInput {
        descriptor,
        sdk,
        compiler: config.snapshot.inputs.compiler.clone(),
        languages: config.snapshot.inputs.contexts[0].publish_languages.clone(),
        scratch: guard,
    })
}
