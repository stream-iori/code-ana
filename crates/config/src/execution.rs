//! Resolve execution-only limits without requiring semantic input discovery.
use super::*;

/// Resolve once at the root index boundary, before worker discovery.
pub fn analysis_limits(root: &Path, home: &Path) -> Result<codeana_model::AnalysisLimits> {
    let user = load_user(home)?;
    let project = execution_project(root)?;
    Ok(project
        .as_ref()
        .map(|p| &p.resources.index.analysis)
        .unwrap_or(&AnalysisResources::default())
        .resolve(&user.resources.index.analysis)?)
}
fn execution_project(root: &Path) -> Result<Option<ProjectConfig>> {
    let path = root.join(".code-ana/config.toml");
    let project = match std::fs::metadata(&path) {
        Ok(_) => {
            ensure!(
                path.canonicalize()?.starts_with(root.canonicalize()?),
                DomainError::new(ErrorCode::ConfigPathEscape, "")
            );
            Some(parse_project(std::str::from_utf8(&bounded_read(
                &path,
                1024 * 1024,
            )?)?)?)
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
        Err(e) => return Err(e.into()),
    };
    if let Some(project) = &project {
        ensure!(
            project.schema_version == 1 && project.kind == "project",
            "INVALID_PROJECT_CONFIG"
        );
    }
    Ok(project)
}
/// Execution-only limits, deliberately independent of semantic input resolution.
pub fn query_limits(root: &Path, home: &Path) -> Result<ResourceLimits> {
    let user = load_user(home)?;
    let project = execution_project(root)?;
    let p = project.as_ref().map(|p| &p.resources.query);
    let u = &user.resources.query;
    let defaults = ResourceLimits::default();
    let limits = ResourceLimits {
        max_work: p
            .and_then(|p| p.max_work)
            .or(u.max_work)
            .unwrap_or(defaults.max_work),
        max_payload_bytes: p
            .and_then(|p| p.max_payload_bytes)
            .or(u.max_payload_bytes)
            .unwrap_or(defaults.max_payload_bytes),
    };
    ensure!(
        (1..=i64::MAX as u64).contains(&limits.max_work)
            && (1..=i64::MAX as u64).contains(&limits.max_payload_bytes),
        "INVALID_QUERY_RESOURCE_LIMIT"
    );
    // Validate shadowed fields too: a project override must not hide invalid user settings.
    for q in [Some(u), p].into_iter().flatten() {
        for v in [q.max_work, q.max_payload_bytes].into_iter().flatten() {
            ensure!(
                (1..=i64::MAX as u64).contains(&v),
                "INVALID_QUERY_RESOURCE_LIMIT"
            );
        }
    }
    Ok(limits)
}

pub(super) fn validate_query_resources(q: &QueryResources) -> Result<()> {
    for v in [q.max_work, q.max_payload_bytes].into_iter().flatten() {
        ensure!(
            (1..=i64::MAX as u64).contains(&v),
            "INVALID_QUERY_RESOURCE_LIMIT"
        );
    }
    Ok(())
}
