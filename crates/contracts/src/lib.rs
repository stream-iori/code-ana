#[allow(clippy::large_enum_variant)] // Generated protobuf oneof layout; framing bounds payloads.
pub mod v1 {
    tonic::include_proto!("codeana.v1");
}
pub mod protobuf {
    pub mod graph {
        pub mod v1 {
            tonic::include_proto!("codeana.graph.v1");
        }
    }
    pub mod worker {
        pub mod v1 {
            tonic::include_proto!("codeana.worker.v1");
        }
    }
}
pub use protobuf::{graph::v1 as graph, worker::v1 as worker};
pub mod analysis_budget;
pub mod bundle;
pub mod fixture_graph;
pub mod frame_admission;
pub mod graph_delivery;
pub mod graph_rows;
pub mod java;
pub mod json_budget;
mod mapping;
pub mod performance;
use anyhow::{Result, bail, ensure};
use codeana_model as m;
use prost::Message;
/// Only explicitly implemented domain/protobuf conversions are accepted.
pub fn map<'a, A, B>(value: &'a A) -> Result<B>
where
    B: TryFrom<&'a A>,
    B::Error: std::error::Error + Send + Sync + 'static,
{
    B::try_from(value).map_err(anyhow::Error::new)
}
pub fn terminal(r: &m::TaskResult) -> v1::Terminal {
    v1::Terminal {
        delivery: Some(v1::Delivery {
            output_format: r.output.delivery.context.format.to_string(),
            detail: r.output.delivery.context.detail,
            operation: r.output.delivery.context.operation.to_string(),
            max_output_bytes: r.output.delivery.context.max_output_bytes,
            historical: r.output.delivery.context.historical,
            comparison: r
                .output
                .delivery
                .context
                .comparison
                .as_ref()
                .map(|context| v1::ComparisonContext {
                    section: context.section.clone(),
                    base: context.base.clone(),
                    target: context.target.clone(),
                    base_kind: context.base_kind.clone(),
                    target_kind: context.target_kind.clone(),
                    base_identity: context.base_identity.clone(),
                    target_identity: context.target_identity.clone(),
                }),
            state: match r.output.delivery.state {
                m::DeliveryState::Complete => "complete",
                m::DeliveryState::Page => "page",
                m::DeliveryState::Partial => "partial",
                m::DeliveryState::Failed => "failed",
            }
            .into(),
            projection_version: r.output.delivery.projection_version,
            returned: r.output.delivery.returned as u64,
            offset: r.output.delivery.offset as u64,
            range_known: r.output.delivery.range_known,
            total: r.output.delivery.total.map(|n| n as u64),
            strict_rejected: r.output.delivery.strict_rejected,
        }),
        execution: r.execution.to_string(),
        assessment: r.assessment.to_string(),
        gate: r.gate.to_string(),
        publication: r.publication.to_string(),
        report: r.report.to_string(),
        revision: r.revision.clone(),
        producer: r.producer.clone(),
        code: r.code.to_string(),
        message: r.message.clone(),
        exit_code: r.exit_code,
        evidence: Some(map(&r.output.evidence).expect("evidence mapping")),
        cursor: r.output.cursor.clone(),
    }
}
/// Decode a task result without deriving publication or quality from exit status.
pub fn task_result(value: &v1::Terminal) -> Result<m::TaskResult> {
    Ok(m::TaskResult {
        execution: value.execution.parse()?,
        assessment: value.assessment.parse()?,
        gate: value.gate.parse()?,
        publication: value.publication.parse()?,
        report: value.report.clone().into(),
        revision: value.revision.clone(),
        producer: value.producer.clone(),
        code: value.code.clone().into(),
        message: value.message.clone(),
        exit_code: value.exit_code,
        output: m::Output {
            evidence: value
                .evidence
                .as_ref()
                .map(map)
                .transpose()?
                .unwrap_or_default(),
            cursor: value.cursor.clone(),
            delivery: if let Some(delivery) = &value.delivery {
                m::DeliveryStatus {
                    context: m::DeliveryContext {
                        format: if delivery.output_format.is_empty() {
                            m::OutputFormat::Text
                        } else {
                            delivery.output_format.parse()?
                        },
                        detail: delivery.detail,
                        operation: delivery.operation.parse()?,
                        max_output_bytes: delivery.max_output_bytes,
                        historical: delivery.historical,
                        comparison: delivery.comparison.as_ref().map(|context| {
                            m::ComparisonContext {
                                section: context.section.clone(),
                                base: context.base.clone(),
                                target: context.target.clone(),
                                base_kind: context.base_kind.clone(),
                                target_kind: context.target_kind.clone(),
                                base_identity: context.base_identity.clone(),
                                target_identity: context.target_identity.clone(),
                            }
                        }),
                    },
                    state: match delivery.state.as_str() {
                        "complete" => m::DeliveryState::Complete,
                        "page" => m::DeliveryState::Page,
                        "partial" => m::DeliveryState::Partial,
                        "failed" => m::DeliveryState::Failed,
                        _ => bail!(m::DomainError::new(
                            m::ErrorCode::OutputFormatUnavailable,
                            "unknown delivery state"
                        )),
                    },
                    projection_version: delivery.projection_version,
                    returned: delivery.returned as usize,
                    offset: delivery.offset as usize,
                    range_known: delivery.range_known,
                    total: delivery.total.map(|n| n as usize),
                    strict_rejected: delivery.strict_rejected,
                }
            } else {
                m::DeliveryStatus::default()
            },
            ..Default::default()
        },
    })
}
pub fn decode_frame(bytes: &[u8]) -> Result<worker::Envelope> {
    validate_envelope_wire(bytes)?;
    Ok(worker::Envelope::decode(bytes)?)
}

// Count repeated messages before prost allocation; a small frame must not expand
// into millions of empty Fact/Owner structs or hide multiple oneof deliveries.
pub fn wire_fields(
    mut bytes: &[u8],
    mut visit: impl FnMut(u64, &[u8]) -> Result<()>,
) -> Result<()> {
    fn varint(bytes: &mut &[u8]) -> Result<u64> {
        let mut value = 0;
        for shift in (0..70).step_by(7) {
            let (&b, rest) = bytes.split_first().ok_or_else(|| {
                anyhow::anyhow!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::InvalidProtobuf,
                    ""
                ))
            })?;
            *bytes = rest;
            if shift == 63 && b > 1 {
                bail!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::InvalidVarint,
                    ""
                ));
            }
            value |= ((b & 127) as u64) << shift;
            if b < 128 {
                return Ok(value);
            }
        }
        bail!(codeana_model::DomainError::new(
            codeana_model::ErrorCode::InvalidVarint,
            ""
        ))
    }
    let mut count = 0;
    while !bytes.is_empty() {
        count += 1;
        if count > m::MAX_FACTS + 32 {
            bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::DecodeFieldBudget,
                ""
            ));
        }
        let tag = varint(&mut bytes)?;
        if tag >> 3 == 0 {
            bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::InvalidProtobufTag,
                ""
            ));
        }
        let len = match tag & 7 {
            0 => {
                varint(&mut bytes)?;
                0
            }
            1 => 8,
            2 => usize::try_from(varint(&mut bytes)?)?,
            5 => 4,
            _ => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::UnsupportedProtobufWire,
                ""
            )),
        };
        if len > bytes.len() {
            bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::InvalidProtobufLength,
                ""
            ));
        }
        let (value, rest) = bytes.split_at(len);
        visit(tag >> 3, value)?;
        bytes = rest;
    }
    Ok(())
}
pub fn packed_count(bytes: &[u8]) -> Result<usize> {
    // Empty payload also represents a scalar (unpacked) varint in wire_fields.
    if bytes.is_empty() {
        return Ok(1);
    }
    let mut count = 0;
    let mut width = 0;
    for &value in bytes {
        width += 1;
        if width > 5 || (width == 5 && value > 15) {
            bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::JavaIndexVarint,
                ""
            ));
        }
        if value < 128 {
            count += 1;
            width = 0;
        }
    }
    ensure!(
        width == 0,
        codeana_model::DomainError::new(codeana_model::ErrorCode::JavaIndexVarint, "")
    );
    Ok(count)
}
fn validate_java_config(bytes: &[u8]) -> Result<()> {
    let mut contexts = 0;
    let mut inputs = 0;
    let mut table = 0;
    let mut indices = 0;
    wire_fields(bytes, |field, value| {
        if matches!(field, 11 | 12) {
            table += 1;
            ensure!(
                table <= 20000 && !value.is_empty(),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaInputTableDecodeBudget,
                    ""
                )
            );
        }
        if field == 5 {
            contexts += 1;
            ensure!(
                contexts <= 256,
                codeana_model::DomainError::new(codeana_model::ErrorCode::JavaContextBudget, "")
            );
            let mut context_indices = [0usize; 4];
            wire_fields(value, |field, value| {
                if matches!(field, 5 | 6 | 12) {
                    inputs += 1;
                    ensure!(
                        inputs <= 10000 && !value.is_empty(),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::JavaInputDecodeBudget,
                            ""
                        )
                    );
                }
                if (14..=17).contains(&field) {
                    let count = if value.is_empty() {
                        1
                    } else {
                        packed_count(value)?
                    };
                    indices += count;
                    context_indices[(field - 14) as usize] += count;
                    ensure!(
                        indices <= 1000000 && context_indices[(field - 14) as usize] <= 10000,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::JavaIndexDecodeBudget,
                            ""
                        )
                    );
                }
                Ok(())
            })?;
        }
        Ok(())
    })
}
/// Admission for host-created typed rows. Rust's types already exclude unknown
/// fields, duplicate singular fields, invalid UTF-8, and ambiguous cell oneofs.
pub fn validate_graph_row(row: &graph::Row) -> Result<()> {
    ensure!(
        row.table != 0,
        codeana_model::DomainError::new(codeana_model::ErrorCode::GraphTableRequired, "")
    );
    ensure!(
        row.cells.len() <= 34,
        codeana_model::DomainError::new(codeana_model::ErrorCode::GraphColumnBudget, "")
    );
    ensure!(row.world.len() <= 256, "GRAPH_WORLD_INVALID");
    Ok(())
}
pub fn validate_graph_row_wire(bytes: &[u8]) -> Result<()> {
    let mut columns = 0;
    let mut tables = 0;
    let mut worlds = 0;
    wire_fields(bytes, |tag, value| {
        match tag {
            1 => {
                tables += 1;
                ensure!(
                    tables == 1,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::GraphTableDuplicate,
                        ""
                    )
                );
            }
            2 => {
                columns += 1;
                ensure!(
                    columns <= 34,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::GraphColumnBudget,
                        ""
                    )
                );
                let mut alternatives = 0;
                wire_fields(value, |tag, _| {
                    ensure!(
                        (1..=3).contains(&tag),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::GraphCellField,
                            ""
                        )
                    );
                    alternatives += 1;
                    ensure!(
                        alternatives == 1,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::GraphCellAmbiguous,
                            ""
                        )
                    );
                    Ok(())
                })?;
            }
            3 => {
                worlds += 1;
                ensure!(
                    worlds == 1 && value.len() <= 256 && std::str::from_utf8(value).is_ok(),
                    "GRAPH_WORLD_INVALID"
                );
            }
            _ => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::GraphRowField,
                ""
            )),
        }
        Ok(())
    })?;
    ensure!(
        tables == 1,
        codeana_model::DomainError::new(codeana_model::ErrorCode::GraphTableRequired, "")
    );
    Ok(())
}
pub fn validate_envelope_wire(bytes: &[u8]) -> Result<()> {
    let mut bodies = 0;
    let mut attempts = 0;
    wire_fields(bytes, |tag, body| {
        if tag == 1 {
            attempts += 1;
        } else if (2..=13).contains(&tag) || (16..=18).contains(&tag) {
            bodies += 1;
            let mut repeated = 0;
            let mut discovery_configs = 0;
            let mut unit_fields = 0;
            let mut context_details = 0;
            let mut source_languages = 0;
            wire_fields(body, |field, value| {
                if matches!((tag, field), (3, 8) | (4, 1)) {
                    unit_fields += 1;
                    ensure!(
                        unit_fields == 1
                            && std::str::from_utf8(value).is_ok_and(build_context::valid_unit),
                        "CONTEXT_UNIT_AMBIGUOUS_OR_INVALID"
                    );
                }
                if matches!((tag, field), (3, 9) | (4, 14)) {
                    source_languages += 1;
                    ensure!(source_languages <= 2, "CONTEXT_SOURCE_LANGUAGE_BUDGET");
                }
                if tag == 4 && (10..=13).contains(&field) {
                    context_details += 1;
                    ensure!(context_details == 1, "CONTEXT_DETAILS_AMBIGUOUS");
                    if field == 10 {
                        let mut classpath = 0;
                        wire_fields(value, |field, _| {
                            if matches!(field, 2 | 6) {
                                classpath += 1;
                                ensure!(classpath <= 10000, "CONTEXT_CLASSPATH_BUDGET");
                            }
                            Ok(())
                        })?;
                    }
                }
                ensure!(
                    !matches!((tag, field), (4, 3) | (4, 4)),
                    "LEGACY_BUILD_CONTEXT_REJECTED"
                );
                if tag == 3 && matches!(field, 2 | 3 | 4 | 7) {
                    discovery_configs += 1;
                    ensure!(discovery_configs == 1, "DISCOVER_CONFIG_AMBIGUOUS");
                }
                ensure!(
                    !matches!((tag, field), (5, 5) | (7, 2)),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::LegacyFactIrRejected,
                        ""
                    )
                );
                if tag == 7 && field == 3 {
                    validate_graph_row_wire(value)?;
                }
                if tag == 3 && field == 2 {
                    validate_java_config(value)?;
                }
                let limit = match (tag, field) {
                    (7, 3) => 64,
                    (13, 1) | (13, 3) | (13, 5) => 1000,

                    (6, 6) => m::MAX_FACTS,
                    (4, 2) | (4, 6) | (4, 8) => 10000,
                    _ => 0,
                };
                if limit > 0 {
                    repeated += 1;
                    if repeated > limit {
                        bail!(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::DecodeRepeatedBudget,
                            ""
                        ));
                    }
                    if matches!((tag, field), (7, 3)) && value.len() < 2 {
                        bail!(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::EmptyFactMessage,
                            ""
                        ));
                    }
                }
                Ok(())
            })?;
            if tag == 3 {
                ensure!(discovery_configs == 1, "DISCOVER_CONFIG_REQUIRED");
            }
            if tag == 3 || tag == 4 {
                ensure!(
                    unit_fields == 1 && source_languages > 0,
                    "CONTEXT_IDENTITY_REQUIRED"
                );
            }
            if tag == 4 {
                ensure!(context_details == 1, "CONTEXT_DETAILS_REQUIRED");
            }
        } else {
            bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::UnsupportedEnvelopeField,
                ""
            ));
        }
        Ok(())
    })?;
    if bodies != 1 || attempts != 1 {
        bail!(codeana_model::DomainError::new(
            codeana_model::ErrorCode::AmbiguousEnvelope,
            ""
        ));
    }
    Ok(())
}
pub fn envelope(attempt: &str, body: worker::envelope::Body) -> worker::Envelope {
    worker::Envelope {
        attempt: attempt.into(),
        body: Some(body),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn comment_query_wire_preserves_operation_and_rejects_unknown_search_mode() {
        let request = m::Request {
            operation: m::Operation::SearchComments,
            ..Default::default()
        };
        let wire: v1::TaskRequest = map(&request).unwrap();
        let decoded = v1::TaskRequest::decode(wire.encode_to_vec().as_slice()).unwrap();
        let restored: m::Request = map(&decoded).unwrap();
        assert_eq!(restored.operation, m::Operation::SearchComments);
        let legacy = v1::TaskRequest::default();
        let absent: m::Request = map(&legacy).unwrap();
        assert_eq!(absent.search_in, m::SearchIn::Names);
        let invalid = v1::TaskRequest {
            search_in: "unknown".into(),
            ..legacy
        };
        assert!(map::<_, m::Request>(&invalid).is_err());
    }
    #[test]
    fn request_presence_roundtrip() {
        let r = m::Request {
            suggestions: Some(false),
            ..Default::default()
        };
        let w: v1::TaskRequest = map(&r).unwrap();
        assert_eq!(w.suggestions, Some(false));
        assert_eq!(map::<_, m::Request>(&w).unwrap().suggestions, Some(false));
    }
    #[test]
    fn repeated_fact_allocation_and_duplicate_body_rejected() {
        let mut empty = vec![10, 1, b'a', 58, 2, 18, 0];
        assert!(validate_envelope_wire(&empty).is_err());
        empty = vec![10, 1, b'a', 18, 0, 18, 0];
        assert!(validate_envelope_wire(&empty).is_err());
    }
}

#[cfg(test)]
mod java_protocol_tests {
    use super::*;
    #[test]
    fn packed_java_indices_are_bounded_before_prost_allocation() {
        let config = worker::JavaConfig {
            compact_inputs: true,
            contexts: vec![worker::JavaContext {
                source_indices: vec![0; 10001],
                ..Default::default()
            }],
            ..Default::default()
        };
        let frame = envelope(
            "a",
            worker::envelope::Body::Discover(worker::Discover {
                unit: "java".into(),
                source_languages: vec!["java".into()],
                deadline_ms: Some(0),
                analysis_grant: None,

                manifest: None,
                config: Some(worker::discover::Config::Java(Box::new(config))),
            }),
        );
        assert!(
            validate_envelope_wire(&frame.encode_to_vec())
                .unwrap_err()
                .to_string()
                .contains("JAVA_INDEX_DECODE_BUDGET")
        );
        assert!(packed_count(&[128]).is_err());
        assert!(packed_count(&[255, 255, 255, 255, 16]).is_err());
        assert_eq!(packed_count(&[0, 128, 1]).unwrap(), 2);
    }
    #[test]
    fn bounded_java_discovery_and_baseline_frames() {
        let java = worker::JavaConfig {
            contexts: vec![worker::JavaContext::default(); 257],
            ..Default::default()
        };
        let frame = envelope(
            "a",
            worker::envelope::Body::Discover(worker::Discover {
                unit: "java".into(),
                source_languages: vec!["java".into()],
                deadline_ms: Some(0),
                analysis_grant: None,

                manifest: None,
                config: Some(worker::discover::Config::Java(Box::new(java))),
            }),
        );
        assert!(validate_envelope_wire(&frame.encode_to_vec()).is_err());
        // Reserved legacy payloads are rejected before protobuf can ignore them.
        let legacy = [0x0a, 1, b'a', 0x72, 0];
        assert!(validate_envelope_wire(&legacy).is_err());
    }
}

#[cfg(test)]
mod discovery_protocol_tests {
    use super::*;

    #[test]
    fn raw_duplicate_and_mixed_discover_variants_are_rejected_before_prost() {
        // Prost alone accepts these by merging or keeping the final oneof value.
        for body in [
            &[0x12, 0, 0x1a, 0][..],
            &[0x1a, 0, 0x1a, 0],
            &[0x3a, 0, 0x22, 0],
        ] {
            let mut raw = vec![0x0a, 1, b'a', 0x1a, body.len() as u8];
            raw.extend_from_slice(body);
            assert!(worker::Envelope::decode(raw.as_slice()).is_ok());
            assert!(
                decode_frame(&raw)
                    .unwrap_err()
                    .to_string()
                    .contains("DISCOVER_CONFIG_AMBIGUOUS")
            );
        }
        let absent = [0x0a, 1, b'a', 0x1a, 0];
        assert!(
            decode_frame(&absent)
                .unwrap_err()
                .to_string()
                .contains("DISCOVER_CONFIG_REQUIRED")
        );
    }

    #[test]
    fn descriptor_backend_and_grant_must_match_before_discovery() {
        use worker::discover::Config;
        for (config, backend) in [
            (Config::Java(Box::default()), m::AnalysisBackend::Java),
            (Config::Rust(Box::default()), m::AnalysisBackend::Rust),
            (Config::Python(Box::default()), m::AnalysisBackend::Python),
            (
                Config::Typescript(Box::default()),
                m::AnalysisBackend::TypeScript,
            ),
        ] {
            let grant = m::AnalysisGrant {
                id: "task/backend/worker".into(),
                language: backend,
                limits: Default::default(),
            };
            let discovery = worker::Discover {
                unit: backend.as_str().into(),
                source_languages: vec![backend.as_str().into()],
                config: Some(config),
                analysis_grant: Some(Box::new(analysis_budget::encode_grant(&grant))),
                deadline_ms: Some(0),
                ..Default::default()
            };
            let frame = envelope(
                "attempt",
                worker::envelope::Body::Discover(discovery.clone()),
            );
            assert!(decode_frame(&frame.encode_to_vec()).is_ok());
            assert_eq!(
                analysis_budget::discovery_backend(&discovery).unwrap(),
                backend
            );
            build_context::discovery(&discovery, backend).unwrap();
        }
    }
}

#[cfg(test)]
mod query_option_tests {
    use super::*;
    #[test]
    fn removed_annotation_operation_is_rejected_at_rpc_boundary() {
        let wire = v1::TaskRequest {
            operation: "annotate".into(),
            ..Default::default()
        };
        assert!(map::<_, m::Request>(&wire).is_err());
    }

    #[test]
    fn hierarchy_options_survive_task_and_stage_protobuf_transport() {
        let request = m::Request {
            operation: codeana_model::Operation::RuntimeImplementations,
            instantiability: codeana_model::Instantiability::Unknown,
            transitive: true,
            algorithm: codeana_model::DispatchAlgorithm::Exact,
            analysis_world: codeana_model::AnalysisWorld::WorkspaceClosed,
            stages: vec![m::Stage {
                operation: codeana_model::Operation::TypeRelations,
                instantiability: codeana_model::Instantiability::All,
                transitive: true,
                algorithm: codeana_model::DispatchAlgorithm::Cha,
                analysis_world: codeana_model::AnalysisWorld::ClasspathOpen,
                ..Default::default()
            }],
            ..Default::default()
        };
        let wire: v1::TaskRequest = map(&request).unwrap();
        let decoded = v1::TaskRequest::decode(wire.encode_to_vec().as_slice()).unwrap();
        let restored: m::Request = map(&decoded).unwrap();
        assert_eq!(restored.instantiability, "unknown");
        assert!(restored.transitive);
        assert_eq!(restored.algorithm, "exact");
        assert_eq!(restored.analysis_world, "workspace-closed");
        assert_eq!(restored.stages[0].instantiability, "all");
        assert!(restored.stages[0].transitive);
        assert_eq!(restored.stages[0].algorithm, "cha");
        assert_eq!(restored.stages[0].analysis_world, "classpath-open");
        let legacy = v1::TaskRequest::decode(&[][..]).unwrap();
        assert!(legacy.instantiability.is_empty());
        assert!(!legacy.transitive);
    }
    #[test]
    fn meta_annotation_options_survive_task_and_stage_protobuf_transport() {
        let request = m::Request {
            operation: codeana_model::Operation::Annotations,
            meta: true,
            stages: vec![m::Stage {
                operation: codeana_model::Operation::Annotations,
                meta: true,
                ..Default::default()
            }],
            ..Default::default()
        };
        let wire: v1::TaskRequest = map(&request).unwrap();
        let decoded = v1::TaskRequest::decode(wire.encode_to_vec().as_slice()).unwrap();
        let restored: m::Request = map(&decoded).unwrap();
        assert!(restored.meta);
        assert!(restored.stages[0].meta);
        let legacy = v1::TaskRequest::decode(&[][..]).unwrap();
        assert!(!legacy.meta);
    }
}

/// Keep stable identity when a storage/read boundary adds infrastructure context.
pub fn read_error(error: anyhow::Error) -> m::DomainError {
    let code = error
        .downcast_ref::<m::DomainError>()
        .map(|e| e.code.clone())
        .unwrap_or(m::ErrorCode::Failed);
    m::DomainError::new(code, format!("{error:#}"))
}

pub mod build_context;
