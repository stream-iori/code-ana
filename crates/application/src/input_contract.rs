//! Public application input contract, shared by CLI help and task admission.
use anyhow::{Result, ensure};
use codeana_model::*;

pub fn input_fields(operation: Operation) -> Option<Vec<&'static str>> {
    let mut fields = if let Some(fields) = codeana_query::input_contract::input_fields(operation) {
        fields
    } else {
        match operation {
            Operation::Index => vec![
                "prepare",
                "full",
                "scope",
                "module",
                "world",
                "source_version",
                "toolchain",
                "suggestions",
                "expected_revision",
            ],
            Operation::Doctor => vec!["source_version", "toolchain", "suggestions"],
            Operation::Snapshot => vec![
                "selector",
                "entity_kind",
                "target",
                "full",
                "scope",
                "module",
                "world",
                "source_version",
                "toolchain",
                "suggestions",
                "expected_revision",
            ],
            Operation::SnapshotList => vec![],
            Operation::SnapshotRemove => vec!["selector"],
            Operation::Diff => vec!["base", "target", "section", "depth"],
            Operation::Report => vec!["selector", "base"],
            Operation::Gc | Operation::Recover => vec!["execute"],
            _ => return None,
        }
    };
    fields.extend_from_slice(&[
        "strict",
        "background",
        "deadline_seconds",
        "request_id",
        "max_records",
        "max_output_bytes",
        "format",
        "detail",
        "export",
    ]);
    if codeana_query::input_contract::input_fields(operation).is_some()
        || operation == Operation::Diff
    {
        fields.push("cursor");
    }
    Some(fields)
}
pub(crate) fn validate(request: &Request) -> Result<()> {
    let fields = input_fields(request.operation).ok_or_else(|| {
        DomainError::new(
            ErrorCode::UnsupportedOperation,
            request.operation.to_string(),
        )
    })?;
    for field in &request.delivery.supplied {
        ensure!(
            fields.contains(&field.as_str()) || matches!(field.as_str(), "project" | "home"),
            DomainError::new(
                ErrorCode::InvalidArgument,
                format!("{} does not accept {field}", request.operation)
            )
        );
    }
    for (present, field) in [
        (request.full, "full"),
        (request.execute, "execute"),
        (!request.base.is_empty(), "base"),
        (!request.target.is_empty(), "target"),
        (!request.revision.is_empty(), "revision"),
        (!request.world.is_empty(), "world"),
        (!request.module.is_empty(), "module"),
        (!request.scope.is_empty() && request.scope != "ALL", "scope"),
        (!request.paths.is_empty(), "paths"),
        (!request.package.is_empty(), "package"),
        (!request.package_prefix.is_empty(), "package_prefix"),
        (!request.signature.is_empty(), "signature"),
        (!request.entity_kind.is_empty(), "entity_kind"),
        (!request.annotation.is_empty(), "annotation"),
        (!request.visibility.is_empty(), "visibility"),
        (request.synthetic.is_some(), "synthetic"),
        (request.source_version.is_some(), "source_version"),
        (request.toolchain.is_some(), "toolchain"),
        (request.suggestions.is_some(), "suggestions"),
        (!request.expected_revision.is_empty(), "expected_revision"),
    ] {
        ensure!(
            !present || fields.contains(&field),
            DomainError::new(
                ErrorCode::InvalidArgument,
                format!("{} does not accept {field}", request.operation)
            )
        );
    }
    ensure!(
        !request.background || request.cursor.is_empty(),
        DomainError::new(
            ErrorCode::InvalidArgument,
            "background and cursor are incompatible"
        )
    );
    ensure!(
        request.max_output_bytes == 0 || request.max_output_bytes >= 2048,
        DomainError::new(ErrorCode::OutputBudgetTooSmall, "minimum 2048")
    );
    if request.operation == Operation::Diff && request.name != "impact" {
        ensure!(
            request.depth <= 1
                && !request
                    .delivery
                    .supplied
                    .iter()
                    .any(|field| field == "depth"),
            DomainError::new(
                ErrorCode::InvalidArgument,
                "diff --depth requires --section impact"
            )
        );
    }
    if request.operation == Operation::Pipeline && !request.target.is_empty() {
        ensure!(
            request
                .stages
                .iter()
                .any(|stage| stage.operation == Operation::Trace),
            DomainError::new(
                ErrorCode::InvalidArgument,
                "pipeline --target requires a trace stage"
            )
        );
    }
    if request.operation == Operation::Snapshot && request.name != "analysis" {
        ensure!(
            !request.full
                && request.source_version.is_none()
                && request.toolchain.is_none()
                && request.suggestions.is_none()
                && request.expected_revision.is_empty(),
            DomainError::new(
                ErrorCode::InvalidArgument,
                "analysis index options require snapshot --kind analysis"
            )
        );
    }
    Ok(())
}
