//! Context identity validation shared by every worker and its host.
use crate::worker as w;
use codeana_model::{
    AnalysisBackend, BuildContext, DomainError, ErrorCode, Manifest, SourceLanguage,
};

pub fn valid_unit(unit: &str) -> bool {
    !unit.is_empty()
        && unit.len() <= 128
        && unit
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'))
}
pub fn validate(context: &BuildContext) -> Result<(), DomainError> {
    let details_valid = match &context.details {
        codeana_model::BackendContext::Java {
            source_version,
            toolchain,
            ..
        } => {
            !source_version.is_empty()
                && [
                    &toolchain.digest,
                    &toolchain.home,
                    &toolchain.version,
                    &toolchain.compiler_bridge,
                    &toolchain.adapter,
                ]
                .iter()
                .all(|s| !s.is_empty())
        }
        codeana_model::BackendContext::Rust { toolchain, adapter } => {
            !toolchain.is_empty() && !adapter.is_empty()
        }
        codeana_model::BackendContext::Python {
            python_version,
            platform,
            adapter,
            backend_commit,
        } => [python_version, platform, adapter, backend_commit]
            .iter()
            .all(|s| !s.is_empty()),
        codeana_model::BackendContext::TypeScript {
            compiler_version,
            upstream_commit,
            adapter,
        } => [compiler_version, upstream_commit, adapter]
            .iter()
            .all(|s| !s.is_empty()),
    };
    let languages = &context.source_languages;
    if !details_valid
        || !valid_unit(&context.unit)
        || context.producer.is_empty()
        || languages.is_empty()
        || languages.len() > 2
        || languages.iter().any(|l| !context.backend().supports(*l))
        || languages
            .iter()
            .collect::<std::collections::BTreeSet<_>>()
            .len()
            != languages.len()
        || context.worlds.windows(2).any(|w| w[0] >= w[1])
        || context.semantic_digest != context.expected_digest()
    {
        return Err(DomainError::new(
            ErrorCode::WorkerLanguageMismatch,
            "invalid context identity or semantic digest",
        ));
    }
    Ok(())
}
pub fn discovery(
    discover: &w::Discover,
    backend: AnalysisBackend,
) -> anyhow::Result<Vec<SourceLanguage>> {
    anyhow::ensure!(valid_unit(&discover.unit), "DISCOVER_UNIT_REQUIRED");
    let languages = discover
        .source_languages
        .iter()
        .map(|s| {
            SourceLanguage::lookup(s).ok_or_else(|| anyhow::anyhow!("DISCOVER_SOURCE_LANGUAGE"))
        })
        .collect::<anyhow::Result<Vec<_>>>()?;
    anyhow::ensure!(
        !languages.is_empty()
            && languages.len() <= 2
            && languages.iter().all(|l| backend.supports(*l))
            && languages
                .iter()
                .collect::<std::collections::BTreeSet<_>>()
                .len()
                == languages.len(),
        "DISCOVER_SOURCE_LANGUAGE"
    );
    Ok(languages)
}
pub fn accept(
    context: &BuildContext,
    unit: &str,
    backend: AnalysisBackend,
    languages: &[SourceLanguage],
    producer: &str,
    manifest: &Manifest,
) -> anyhow::Result<()> {
    validate(context)?;
    anyhow::ensure!(
        context.unit.as_str() == unit
            && context.backend() == backend
            && context.producer == producer
            && context.source_languages == languages
            && context.worlds == BuildContext::worlds_for(manifest, backend),
        "WORKER_CONTEXT_IDENTITY_MISMATCH"
    );
    Ok(())
}

#[cfg(test)]
#[path = "build_context_tests.rs"]
mod tests;
