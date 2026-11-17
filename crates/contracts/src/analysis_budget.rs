//! Explicit analysis-budget wire conversion; invalid or absent grants never get defaults.
use crate::worker as w;
use codeana_model::{
    AnalysisBackend, AnalysisFailure, AnalysisGrant, AnalysisLimits, AnalysisUsage,
};

pub fn required_grant(
    raw: Option<&w::AnalysisBudgetGrant>,
    language: AnalysisBackend,
) -> Result<AnalysisGrant, AnalysisFailure> {
    let grant = decode_grant(raw.ok_or(AnalysisFailure::InvalidUsage)?)?;
    if grant.language != language {
        return Err(AnalysisFailure::InvalidUsage);
    }
    Ok(grant)
}

/// Identify the sole typed discovery descriptor without executing a policy.
pub fn discovery_backend(discover: &w::Discover) -> anyhow::Result<AnalysisBackend> {
    use w::discover::Config;
    match discover.config.as_ref() {
        Some(Config::Java(_)) => Ok(AnalysisBackend::Java),
        Some(Config::Rust(_)) => Ok(AnalysisBackend::Rust),
        Some(Config::Python(_)) => Ok(AnalysisBackend::Python),
        Some(Config::Typescript(_)) => Ok(AnalysisBackend::TypeScript),
        None => anyhow::bail!("DISCOVER_CONFIG_REQUIRED"),
    }
}

pub fn encode_grant(grant: &AnalysisGrant) -> w::AnalysisBudgetGrant {
    w::AnalysisBudgetGrant {
        id: grant.id.clone(),
        language: match grant.language {
            AnalysisBackend::Java => "java",
            AnalysisBackend::Rust => "rust",
            AnalysisBackend::Python => "python",
            AnalysisBackend::TypeScript => "typescript",
        }
        .into(),
        limits: Some(w::AnalysisBudgetLimits {
            max_work: grant.limits.max_work,
            max_nodes: grant.limits.max_nodes,
            max_payload_bytes: grant.limits.max_payload_bytes,
            max_depth: grant.limits.max_depth,
        }),
    }
}
pub fn decode_grant(value: &w::AnalysisBudgetGrant) -> Result<AnalysisGrant, AnalysisFailure> {
    if value.id.is_empty() {
        return Err(AnalysisFailure::InvalidUsage);
    }
    let language = match value.language.as_str() {
        "java" => AnalysisBackend::Java,
        "rust" => AnalysisBackend::Rust,
        "python" => AnalysisBackend::Python,
        "typescript" => AnalysisBackend::TypeScript,
        _ => return Err(AnalysisFailure::InvalidUsage),
    };
    let raw = value
        .limits
        .as_ref()
        .ok_or(AnalysisFailure::InvalidLimits)?;
    let limits = AnalysisLimits {
        max_work: raw.max_work,
        max_nodes: raw.max_nodes,
        max_payload_bytes: raw.max_payload_bytes,
        max_depth: raw.max_depth,
    };
    limits.validate()?;
    Ok(AnalysisGrant {
        id: value.id.clone(),
        language,
        limits,
    })
}
pub fn encode_usage(value: &AnalysisUsage) -> w::AnalysisBudgetUsage {
    let failure = value.failure.as_ref().map(|failure| {
        let mut raw = w::AnalysisBudgetFailure::default();
        match failure {
            AnalysisFailure::Limit {
                resource,
                limit,
                used,
                requested,
            } => {
                raw.code = "ANALYSIS_RESOURCE_LIMIT".into();
                raw.resource = resource.clone();
                raw.limit = *limit;
                raw.used = *used;
                raw.requested = *requested;
            }
            AnalysisFailure::Cancelled => raw.code = "CANCELLED".into(),
            AnalysisFailure::Deadline => raw.code = "DEADLINE_EXCEEDED".into(),
            AnalysisFailure::InvalidUsage => raw.code = "ANALYSIS_USAGE_INVALID".into(),
            AnalysisFailure::InvalidLimits => raw.code = "ANALYSIS_LIMITS_INVALID".into(),
        }
        raw
    });
    w::AnalysisBudgetUsage {
        work: value.work,
        nodes: value.nodes,
        payload_bytes: value.payload_bytes,
        max_depth: value.max_depth,
        failure,
    }
}
pub fn decode_usage(value: &w::AnalysisBudgetUsage) -> Result<AnalysisUsage, AnalysisFailure> {
    let failure = value
        .failure
        .as_ref()
        .map(|raw| match raw.code.as_str() {
            "ANALYSIS_RESOURCE_LIMIT" => {
                if !matches!(
                    raw.resource.as_str(),
                    "work" | "nodes" | "payload_bytes" | "depth"
                ) || raw.used > raw.limit
                    || raw.requested <= raw.limit.saturating_sub(raw.used)
                {
                    return Err(AnalysisFailure::InvalidUsage);
                }
                Ok(AnalysisFailure::Limit {
                    resource: raw.resource.clone(),
                    limit: raw.limit,
                    used: raw.used,
                    requested: raw.requested,
                })
            }
            "CANCELLED"
            | "DEADLINE_EXCEEDED"
            | "ANALYSIS_USAGE_INVALID"
            | "ANALYSIS_LIMITS_INVALID" => {
                if !raw.resource.is_empty() || raw.limit != 0 || raw.used != 0 || raw.requested != 0
                {
                    return Err(AnalysisFailure::InvalidUsage);
                }
                Ok(match raw.code.as_str() {
                    "CANCELLED" => AnalysisFailure::Cancelled,
                    "DEADLINE_EXCEEDED" => AnalysisFailure::Deadline,
                    "ANALYSIS_USAGE_INVALID" => AnalysisFailure::InvalidUsage,
                    _ => AnalysisFailure::InvalidLimits,
                })
            }
            _ => Err(AnalysisFailure::InvalidUsage),
        })
        .transpose()?;
    if [value.work, value.nodes, value.payload_bytes]
        .iter()
        .any(|n| *n > i64::MAX as u64)
        || value.max_depth > 4096
    {
        return Err(AnalysisFailure::InvalidUsage);
    }
    Ok(AnalysisUsage {
        work: value.work,
        nodes: value.nodes,
        payload_bytes: value.payload_bytes,
        max_depth: value.max_depth,
        failure,
    })
}
pub fn receipt(
    grant: &AnalysisGrant,
    usage: &AnalysisUsage,
    phase: &str,
) -> w::AnalysisBudgetReceipt {
    w::AnalysisBudgetReceipt {
        grant_id: grant.id.clone(),
        usage: Some(encode_usage(usage)),
        phase: phase.into(),
        usage_known: Some(true),
    }
}
pub fn decode_receipt(
    value: &w::AnalysisBudgetReceipt,
    grant: &AnalysisGrant,
) -> Result<AnalysisUsage, AnalysisFailure> {
    if value.usage_known.is_none()
        || value.grant_id != grant.id
        || !codeana_model::valid_analysis_phase(&value.phase)
    {
        return Err(AnalysisFailure::InvalidUsage);
    }
    decode_usage(value.usage.as_ref().ok_or(AnalysisFailure::InvalidUsage)?)
}
/// Error formatting itself must stay bounded even after the task ledger failed.
pub fn bounded_error(error: &anyhow::Error) -> String {
    use std::fmt::Write;
    struct Bounded(String);
    impl std::fmt::Write for Bounded {
        fn write_str(&mut self, value: &str) -> std::fmt::Result {
            let mut n = value.len().min(8192usize.saturating_sub(self.0.len()));
            while !value.is_char_boundary(n) {
                n -= 1;
            }
            self.0.push_str(&value[..n]);
            if n < value.len() {
                Err(std::fmt::Error)
            } else {
                Ok(())
            }
        }
    }
    let mut text = Bounded(String::with_capacity(8192));
    let _ = write!(&mut text, "{error:#}");
    text.0
}
