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
        use worker::build_context::Details as D;
        let details = match &value.details {
            m::BackendContext::Java {
                source_version,
                classpath,
                toolchain,
            } => D::Java(Box::new(worker::JavaBuildContext {
                source_version: source_version.clone(),
                classpath: classpath.clone(),
                toolchain_digest: toolchain.digest.clone(),
                java_home: toolchain.home.clone(),
                actual_java_version: toolchain.version.clone(),
                toolchain_aliases: toolchain.aliases.clone(),
                compiler_bridge: toolchain.compiler_bridge.clone(),
                adapter: toolchain.adapter.clone(),
            })),
            m::BackendContext::Rust { toolchain, adapter } => D::Rust(worker::RustBuildContext {
                toolchain: toolchain.clone(),
                adapter: adapter.clone(),
            }),
            m::BackendContext::Python {
                python_version,
                platform,
                adapter,
                backend_commit,
            } => D::Python(worker::PythonBuildContext {
                python_version: python_version.clone(),
                platform: platform.clone(),
                adapter: adapter.clone(),
                backend_commit: backend_commit.clone(),
            }),
            m::BackendContext::TypeScript {
                compiler_version,
                upstream_commit,
                adapter,
            } => D::Typescript(worker::TypeScriptBuildContext {
                compiler_version: compiler_version.clone(),
                upstream_commit: upstream_commit.clone(),
                adapter: adapter.clone(),
            }),
        };
        Self {
            unit: value.unit.to_string(),
            source_languages: value
                .source_languages
                .iter()
                .map(|l| l.as_str().to_owned())
                .collect(),
            modules: value.modules.clone(),
            worlds: value.worlds.clone(),
            semantic_digest: value.semantic_digest.clone(),
            producer: value.producer.clone(),
            capabilities: value.capabilities.clone(),
            details: Some(details),
            analysis_usage: None,
        }
    }
}
impl TryFrom<&worker::BuildContext> for m::BuildContext {
    type Error = m::DomainError;
    fn try_from(value: &worker::BuildContext) -> Result<Self, Self::Error> {
        use worker::build_context::Details as D;
        let invalid = || {
            m::DomainError::new(
                m::ErrorCode::WorkerLanguageMismatch,
                "invalid build context",
            )
        };
        let details = match value.details.as_ref().ok_or_else(invalid)? {
            D::Java(v) => m::BackendContext::Java {
                source_version: v.source_version.clone(),
                classpath: v.classpath.clone(),
                toolchain: m::JavaToolchainContext {
                    digest: v.toolchain_digest.clone(),
                    home: v.java_home.clone(),
                    version: v.actual_java_version.clone(),
                    aliases: v.toolchain_aliases.clone(),
                    compiler_bridge: v.compiler_bridge.clone(),
                    adapter: v.adapter.clone(),
                },
            },
            D::Rust(v) => m::BackendContext::Rust {
                toolchain: v.toolchain.clone(),
                adapter: v.adapter.clone(),
            },
            D::Python(v) => m::BackendContext::Python {
                python_version: v.python_version.clone(),
                platform: v.platform.clone(),
                adapter: v.adapter.clone(),
                backend_commit: v.backend_commit.clone(),
            },
            D::Typescript(v) => m::BackendContext::TypeScript {
                compiler_version: v.compiler_version.clone(),
                upstream_commit: v.upstream_commit.clone(),
                adapter: v.adapter.clone(),
            },
        };
        let context = Self {
            unit: value.unit.clone().into(),
            source_languages: value
                .source_languages
                .iter()
                .map(|s| m::SourceLanguage::lookup(s).ok_or_else(invalid))
                .collect::<Result<_, _>>()?,
            modules: value.modules.clone(),
            worlds: value.worlds.clone(),
            semantic_digest: value.semantic_digest.clone(),
            producer: value.producer.clone(),
            capabilities: value.capabilities.clone(),
            details,
        };
        crate::build_context::validate(&context)?;
        Ok(context)
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
            resource_usage: value.resource_usage.as_ref().map(Into::into),
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
            resource_usage: value.resource_usage.as_ref().map(Into::into),
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
            output_format: value.delivery.format.map(|v| v.to_string()),
            detail: value.delivery.detail,
            explicit_options: value.delivery.supplied.clone(),
            project_explicit: value.delivery.project_explicit,
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
            search_in: value.search_in.to_string(),
            overview_section: value.overview_section.clone(),
            overview_depth: value.overview_depth,
        }
    }
}

impl TryFrom<&v1::TaskRequest> for m::Request {
    type Error = m::DomainError;
    fn try_from(value: &v1::TaskRequest) -> Result<Self, Self::Error> {
        Ok(Self {
            delivery: m::DeliveryOptions {
                format: value.output_format.as_deref().map(str::parse).transpose()?,
                detail: value.detail,
                supplied: value.explicit_options.clone(),
                project_explicit: value.project_explicit,
            },
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
            search_in: if value.search_in.is_empty() {
                m::SearchIn::Names
            } else {
                value.search_in.parse()?
            },
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

impl From<&m::ResourceUsage> for v1::ResourceUsage {
    fn from(v: &m::ResourceUsage) -> Self {
        Self {
            limits: Some(v1::ResourceLimits {
                max_work: v.limits.max_work,
                max_payload_bytes: v.limits.max_payload_bytes,
            }),
            work: v.work,
            payload_bytes: v.payload_bytes,
            exhausted: v.exhausted.as_ref().map(|e| v1::ResourceLimit {
                resource: e.resource.clone(),
                limit: e.limit,
                used: e.used,
                requested: e.requested,
            }),
        }
    }
}
impl From<&v1::ResourceUsage> for m::ResourceUsage {
    fn from(v: &v1::ResourceUsage) -> Self {
        Self {
            limits: v
                .limits
                .as_ref()
                .map(|l| m::ResourceLimits {
                    max_work: l.max_work,
                    max_payload_bytes: l.max_payload_bytes,
                })
                .unwrap_or_default(),
            work: v.work,
            payload_bytes: v.payload_bytes,
            exhausted: v.exhausted.as_ref().map(|e| m::ResourceLimit {
                resource: e.resource.clone(),
                limit: e.limit,
                used: e.used,
                requested: e.requested,
            }),
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
    fn delivery_presence_and_terminal_dimensions_survive_the_wire() {
        let request = m::Request {
            operation: m::Operation::SearchCode,
            delivery: m::DeliveryOptions {
                format: Some(m::OutputFormat::Json),
                detail: Some(false),
                supplied: vec!["format".into()],
                project_explicit: true,
            },
            ..Default::default()
        };
        let wire = v1::TaskRequest::from(&request);
        let restored = m::Request::try_from(&wire).unwrap();
        assert_eq!(restored.delivery, request.delivery);
        let absent = m::Request::try_from(&v1::TaskRequest {
            operation: "search-code".into(),
            ..Default::default()
        })
        .unwrap();
        assert_eq!(absent.delivery.format, None);
        let mut result = m::TaskResult::success(m::Output {
            evidence: m::Evidence::complete(),
            ..Default::default()
        });
        result.publication = m::PublicationState::Published;
        result.gate = m::GateState::Unknown;
        result.report = m::ReportState::Unknown;
        result.output.delivery.state = m::DeliveryState::Partial;
        result.output.delivery.strict_rejected = true;
        result.output.delivery.projection_version = m::PUBLIC_PROJECTION_VERSION;
        result.output.delivery.context.format = m::OutputFormat::Json;
        result.output.delivery.context.comparison = Some(m::ComparisonContext {
            section: "files".into(),
            base: "before".into(),
            target: "after".into(),
            base_kind: "files".into(),
            target_kind: "analysis".into(),
            base_identity: "hash".into(),
            target_identity: "revision".into(),
        });
        let restored = crate::task_result(&crate::terminal(&result)).unwrap();
        assert_eq!(restored.output.delivery, result.output.delivery);
        assert_eq!(restored.publication, result.publication);
        assert_eq!(restored.gate, result.gate);
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
