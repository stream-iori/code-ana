//! Explicit domain/protobuf mappings. Field additions are checked by the compiler.
use crate::{v1, worker};
use codeana_model as m;

impl From<&m::SourceUnit> for worker::SourceUnit {
    fn from(value: &m::SourceUnit) -> Self {
        Self {
            path: value.path.clone(),
            hash: value.hash.clone(),
            size: value.size,
            module: value.module.clone(),
            world: value.world.clone(),
            scope: value.scope.clone(),
            language: value.language.clone(),
            auxiliary: value.auxiliary,
        }
    }
}

impl From<&worker::SourceUnit> for m::SourceUnit {
    fn from(value: &worker::SourceUnit) -> Self {
        Self {
            path: value.path.clone(),
            hash: value.hash.clone(),
            size: value.size,
            module: value.module.clone(),
            world: value.world.clone(),
            scope: value.scope.clone(),
            language: value.language.clone(),
            auxiliary: value.auxiliary,
        }
    }
}

impl From<&m::Owner> for worker::Owner {
    fn from(value: &m::Owner) -> Self {
        Self {
            world: value.world.to_string(),
            producer: value.producer.clone(),
            kind: value.kind.clone(),
            id: value.id.clone(),
        }
    }
}

impl From<&worker::Owner> for m::Owner {
    fn from(value: &worker::Owner) -> Self {
        Self {
            world: value.world.clone().into(),
            producer: value.producer.clone(),
            kind: value.kind.clone(),
            id: value.id.clone(),
        }
    }
}

impl From<&m::BuildContext> for worker::BuildContext {
    fn from(value: &m::BuildContext) -> Self {
        Self {
            language: value.language.clone(),
            modules: value.modules.clone(),
            source_version: value.source_version.clone(),
            classpath: value.classpath.clone(),
            producer: value.producer.clone(),
            capabilities: value.capabilities.clone(),
        }
    }
}

impl From<&worker::BuildContext> for m::BuildContext {
    fn from(value: &worker::BuildContext) -> Self {
        Self {
            language: value.language.clone(),
            modules: value.modules.clone(),
            source_version: value.source_version.clone(),
            classpath: value.classpath.clone(),
            producer: value.producer.clone(),
            capabilities: value.capabilities.clone(),
        }
    }
}

impl From<&m::BuildAdvice> for worker::BuildAdvice {
    fn from(value: &m::BuildAdvice) -> Self {
        Self {
            missing: value.missing.clone(),
            cwd: value.cwd.clone(),
            argv: value.argv.clone(),
            purpose: value.purpose.clone(),
            expected: value.expected.clone(),
            impact: value.impact.clone(),
            retry: value.retry.clone(),
            capture: value.capture.clone(),
            module: value.module.clone(),
            world: value.world.clone(),
        }
    }
}

impl From<&worker::BuildAdvice> for m::BuildAdvice {
    fn from(value: &worker::BuildAdvice) -> Self {
        Self {
            missing: value.missing.clone(),
            cwd: value.cwd.clone(),
            argv: value.argv.clone(),
            purpose: value.purpose.clone(),
            expected: value.expected.clone(),
            impact: value.impact.clone(),
            retry: value.retry.clone(),
            capture: value.capture.clone(),
            module: value.module.clone(),
            world: value.world.clone(),
        }
    }
}

impl From<&m::Evidence> for v1::Evidence {
    fn from(value: &m::Evidence) -> Self {
        Self {
            enumeration: value.enumeration,
            semantic: value.semantic,
            limits: value.limits.clone(),
            reasons: value.reasons.clone(),
        }
    }
}

impl From<&v1::Evidence> for m::Evidence {
    fn from(value: &v1::Evidence) -> Self {
        Self {
            enumeration: value.enumeration,
            semantic: value.semantic,
            limits: value.limits.clone(),
            reasons: value.reasons.clone(),
        }
    }
}

impl From<&m::Stage> for v1::Stage {
    fn from(value: &m::Stage) -> Self {
        Self {
            operation: value.operation.to_string(),
            selector: value.selector.clone(),
            direction: value.direction.to_string(),
            relation: value.relation.clone(),
            unique: value.unique,
            meta: value.meta,
            instantiability: value.instantiability.to_string(),
            transitive: value.transitive,
            algorithm: value.algorithm.to_string(),
            analysis_world: value.analysis_world.to_string(),
            depth: value.depth,
            endpoint: value.endpoint.to_string(),
        }
    }
}

impl TryFrom<&v1::Stage> for m::Stage {
    type Error = m::DomainError;
    fn try_from(value: &v1::Stage) -> Result<Self, Self::Error> {
        Ok(Self {
            operation: value.operation.parse()?,
            selector: value.selector.clone(),
            direction: value.direction.parse()?,
            relation: value.relation.clone(),
            unique: value.unique,
            meta: value.meta,
            instantiability: value.instantiability.parse()?,
            transitive: value.transitive,
            algorithm: value.algorithm.parse()?,
            analysis_world: value.analysis_world.parse()?,
            depth: value.depth,
            endpoint: value.endpoint.parse()?,
        })
    }
}

impl From<&m::Request> for v1::TaskRequest {
    fn from(value: &m::Request) -> Self {
        Self {
            request_id: value.request_id.to_string(),
            project: value.project.clone(),
            operation: value.operation.to_string(),
            selector: value.selector.clone(),
            revision: value.revision.clone(),
            scope: value.scope.clone(),
            world: value.world.clone(),
            module: value.module.clone(),
            direction: value.direction.to_string(),
            relation: value.relation.clone(),
            unique: value.unique,
            meta: value.meta,
            instantiability: value.instantiability.to_string(),
            transitive: value.transitive,
            algorithm: value.algorithm.to_string(),
            analysis_world: value.analysis_world.to_string(),
            full: value.full,
            strict: value.strict,
            execute: value.execute,
            background: value.background,
            base: value.base.clone(),
            target: value.target.clone(),
            cursor: value.cursor.clone(),
            name: value.name.clone(),
            stages: value.stages.iter().map(Into::into).collect(),
            max_records: value.max_records,
            max_output_bytes: value.max_output_bytes,
            depth: value.depth,
            deadline_seconds: value.deadline_seconds,
            source_version: value.source_version.clone(),
            toolchain: value.toolchain.clone(),
            suggestions: value.suggestions,
            expected_revision: value.expected_revision.clone(),
            signature: value.signature.clone(),
            package: value.package.clone(),
            package_prefix: value.package_prefix.clone(),
            paths: value.paths.clone(),
            entity_kind: value.entity_kind.clone(),
            annotation: value.annotation.clone(),
            visibility: value.visibility.clone(),
            synthetic: value.synthetic,
            text_regex: value.text_regex,
            text_ignore_case: value.text_ignore_case,
            text_context: value.text_context,
            overview_section: value.overview_section.clone(),
            overview_depth: value.overview_depth,
        }
    }
}

impl TryFrom<&v1::TaskRequest> for m::Request {
    type Error = m::DomainError;
    fn try_from(value: &v1::TaskRequest) -> Result<Self, Self::Error> {
        Ok(Self {
            request_id: value.request_id.clone().into(),
            project: value.project.clone(),
            operation: value.operation.parse()?,
            selector: value.selector.clone(),
            revision: value.revision.clone(),
            scope: value.scope.clone(),
            world: value.world.clone(),
            module: value.module.clone(),
            direction: value.direction.parse()?,
            relation: value.relation.clone(),
            unique: value.unique,
            meta: value.meta,
            instantiability: value.instantiability.parse()?,
            transitive: value.transitive,
            algorithm: value.algorithm.parse()?,
            analysis_world: value.analysis_world.parse()?,
            full: value.full,
            strict: value.strict,
            execute: value.execute,
            background: value.background,
            base: value.base.clone(),
            target: value.target.clone(),
            cursor: value.cursor.clone(),
            name: value.name.clone(),
            stages: value
                .stages
                .iter()
                .map(TryInto::try_into)
                .collect::<Result<_, _>>()?,
            max_records: value.max_records,
            max_output_bytes: value.max_output_bytes,
            depth: value.depth,
            deadline_seconds: value.deadline_seconds,
            source_version: value.source_version.clone(),
            toolchain: value.toolchain.clone(),
            suggestions: value.suggestions,
            expected_revision: value.expected_revision.clone(),
            signature: value.signature.clone(),
            package: value.package.clone(),
            package_prefix: value.package_prefix.clone(),
            paths: value.paths.clone(),
            entity_kind: value.entity_kind.clone(),
            annotation: value.annotation.clone(),
            visibility: value.visibility.clone(),
            synthetic: value.synthetic,
            text_regex: value.text_regex,
            text_ignore_case: value.text_ignore_case,
            text_context: value.text_context,
            overview_section: value.overview_section.clone(),
            overview_depth: value.overview_depth,
        })
    }
}

impl From<&m::Manifest> for worker::Manifest {
    fn from(value: &m::Manifest) -> Self {
        Self {
            files: value.files.iter().map(Into::into).collect(),
        }
    }
}

impl From<&worker::Manifest> for m::Manifest {
    fn from(value: &worker::Manifest) -> Self {
        Self {
            files: value.files.iter().map(Into::into).collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_wire_choices_are_rejected_before_dispatch() {
        let wire = v1::TaskRequest {
            operation: "unknown-operation".into(),
            ..Default::default()
        };
        assert_eq!(
            m::Request::try_from(&wire).unwrap_err().code,
            m::ErrorCode::UnsupportedOperation
        );
        let wire = v1::TaskRequest {
            operation: "pipeline".into(),
            stages: vec![v1::Stage {
                operation: "calls".into(),
                direction: "sideways".into(),
                ..Default::default()
            }],
            ..Default::default()
        };
        assert_eq!(
            m::Request::try_from(&wire).unwrap_err().code,
            m::ErrorCode::InvalidDirection
        );
    }
    #[test]
    fn context_wrapping_preserves_cancellation_identity() {
        use anyhow::Context;
        let error = Err::<(), _>(m::DomainError::new(
            m::ErrorCode::Cancelled,
            "original cause",
        ))
        .context("reading captured source")
        .unwrap_err();
        let restored = crate::read_error(error);
        assert_eq!(restored.code, m::ErrorCode::Cancelled);
        assert!(restored.context.contains("original cause"));
    }
}
