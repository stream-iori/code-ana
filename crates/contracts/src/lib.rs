#[allow(clippy::large_enum_variant)] // Generated protobuf oneof layout; framing bounds payloads.
pub mod v1 {
    tonic::include_proto!("codeana.v1");
}
pub mod worker {
    tonic::include_proto!("codeana.worker.v1");
}
pub mod archive;
pub mod java;
pub mod performance;
use anyhow::{Result, bail, ensure};
use codeana_model as m;
use prost::Message;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
pub fn convert<A: serde::Serialize, B: serde::de::DeserializeOwned>(a: &A) -> Result<B> {
    Ok(serde_json::from_value(serde_json::to_value(a)?)?)
}
pub fn fact_to_wire(f: &m::Fact) -> Result<worker::Fact> {
    let _timer = performance::Timer::start(performance::Metric::FactToWire, 0, true);
    Ok(worker::Fact {
        id: f.id.clone(),
        kind: f.kind.name().into(),
        owner: Some(worker::Owner {
            world: f.owner.world.clone(),
            producer: f.owner.producer.clone(),
            kind: f.owner.kind.clone(),
            id: f.owner.id.clone(),
        }),
        source: f.source.clone(),
        span: f.span.as_ref().map(|s| worker::Span {
            start: s.start,
            end: s.end,
        }),
        name: f.name.clone(),
        signature: f.signature.clone(),
        parent: f.parent.clone(),
        target: f.target.clone(),
        relation: f.relation.clone(),
        status: f.status.clone(),
        origin: f.origin.clone(),
        evidence_complete: f.evidence_complete,
        declaration_resolved: f.declaration_resolved,
        attributes_json: serde_json::to_vec(&f.attributes)?,
    })
}
pub fn fact_from_wire(f: worker::Fact) -> Result<m::Fact> {
    let _timer = performance::Timer::start(
        performance::Metric::FactFromWire,
        f.attributes_json.len() as u64,
        true,
    );
    let owner = f.owner.ok_or_else(|| anyhow::anyhow!("missing owner"))?;
    Ok(m::Fact {
        id: f.id,
        kind: serde_json::from_value(serde_json::Value::String(f.kind))?,
        owner: m::Owner {
            world: owner.world,
            producer: owner.producer,
            kind: owner.kind,
            id: owner.id,
        },
        source: f.source,
        span: f.span.map(|s| m::Span {
            start: s.start,
            end: s.end,
        }),
        name: f.name,
        signature: f.signature,
        parent: f.parent,
        target: f.target,
        relation: f.relation,
        status: f.status,
        origin: f.origin,
        evidence_complete: f.evidence_complete,
        declaration_resolved: f.declaration_resolved,
        attributes: serde_json::from_slice(&f.attributes_json)?,
    })
}
pub fn terminal(r: &m::TaskResult) -> v1::Terminal {
    v1::Terminal {
        execution: r.execution.clone(),
        assessment: r.assessment.clone(),
        gate: r.gate.clone(),
        publication: r.publication.clone(),
        report: r.report.clone(),
        revision: r.revision.clone(),
        producer: r.producer.clone(),
        code: r.code.clone(),
        message: r.message.clone(),
        exit_code: r.exit_code,
        evidence: Some(convert(&r.output.evidence).expect("evidence mapping")),
        cursor: r.output.cursor.clone(),
    }
}
pub async fn write_frame<W: AsyncWrite + Unpin>(w: &mut W, e: &worker::Envelope) -> Result<()> {
    if e.encoded_len() > m::MAX_FRAME {
        bail!("FRAME_BUDGET");
    }
    let b = performance::measure(
        performance::Metric::FrameEncode,
        e.encoded_len() as u64,
        false,
        || e.encode_to_vec(),
    );
    let _timer = performance::Timer::start(
        performance::Metric::FrameWriteWait,
        b.len() as u64 + 4,
        false,
    );
    w.write_u32(b.len() as u32).await?;
    w.write_all(&b).await?;
    w.flush().await?;
    Ok(())
}
pub async fn read_frame<R: AsyncRead + Unpin>(r: &mut R) -> Result<worker::Envelope> {
    let timer = performance::Timer::start(performance::Metric::FrameReadWait, 0, false);
    let n = r.read_u32().await? as usize;
    if n == 0 || n > m::MAX_FRAME {
        bail!("INVALID_FRAME_LENGTH");
    }
    let mut b = vec![0; n];
    r.read_exact(&mut b).await?;
    drop(timer);
    performance::measure(performance::Metric::FrameValidate, n as u64, false, || {
        validate_envelope_wire(&b)
    })?;
    Ok(performance::measure(
        performance::Metric::FrameDecode,
        n as u64,
        false,
        || worker::Envelope::decode(b.as_slice()),
    )?)
}
// Count repeated messages before prost allocation; a small frame must not expand
// into millions of empty Fact/Owner structs or hide multiple oneof deliveries.
fn wire_fields(mut bytes: &[u8], mut visit: impl FnMut(u64, &[u8]) -> Result<()>) -> Result<()> {
    fn varint(bytes: &mut &[u8]) -> Result<u64> {
        let mut value = 0;
        for shift in (0..70).step_by(7) {
            let (&b, rest) = bytes
                .split_first()
                .ok_or_else(|| anyhow::anyhow!("INVALID_PROTOBUF"))?;
            *bytes = rest;
            if shift == 63 && b > 1 {
                bail!("INVALID_VARINT");
            }
            value |= ((b & 127) as u64) << shift;
            if b < 128 {
                return Ok(value);
            }
        }
        bail!("INVALID_VARINT")
    }
    let mut count = 0;
    while !bytes.is_empty() {
        count += 1;
        if count > m::MAX_FACTS + 32 {
            bail!("DECODE_FIELD_BUDGET");
        }
        let tag = varint(&mut bytes)?;
        if tag >> 3 == 0 {
            bail!("INVALID_PROTOBUF_TAG");
        }
        let len = match tag & 7 {
            0 => {
                varint(&mut bytes)?;
                0
            }
            1 => 8,
            2 => usize::try_from(varint(&mut bytes)?)?,
            5 => 4,
            _ => bail!("UNSUPPORTED_PROTOBUF_WIRE"),
        };
        if len > bytes.len() {
            bail!("INVALID_PROTOBUF_LENGTH");
        }
        let (value, rest) = bytes.split_at(len);
        visit(tag >> 3, value)?;
        bytes = rest;
    }
    Ok(())
}
fn packed_count(bytes: &[u8]) -> Result<usize> {
    // Empty payload also represents a scalar (unpacked) varint in wire_fields.
    if bytes.is_empty() {
        return Ok(1);
    }
    let mut count = 0;
    let mut width = 0;
    for &value in bytes {
        width += 1;
        if width > 5 || (width == 5 && value > 15) {
            bail!("JAVA_INDEX_VARINT");
        }
        if value < 128 {
            count += 1;
            width = 0;
        }
    }
    ensure!(width == 0, "JAVA_INDEX_VARINT");
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
                "JAVA_INPUT_TABLE_DECODE_BUDGET"
            );
        }
        if field == 5 {
            contexts += 1;
            ensure!(contexts <= 256, "JAVA_CONTEXT_BUDGET");
            let mut context_indices = [0usize; 4];
            wire_fields(value, |field, value| {
                if matches!(field, 5 | 6 | 12) {
                    inputs += 1;
                    ensure!(
                        inputs <= 10000 && !value.is_empty(),
                        "JAVA_INPUT_DECODE_BUDGET"
                    );
                }
                if (14..=17).contains(&field) {
                    let count = packed_count(value)?;
                    indices += count;
                    context_indices[(field - 14) as usize] += count;
                    ensure!(
                        indices <= 1000000 && context_indices[(field - 14) as usize] <= 10000,
                        "JAVA_INDEX_DECODE_BUDGET"
                    );
                }
                Ok(())
            })?;
        }
        Ok(())
    })
}
fn validate_envelope_wire(bytes: &[u8]) -> Result<()> {
    let mut bodies = 0;
    let mut attempts = 0;
    wire_fields(bytes, |tag, body| {
        if tag == 1 {
            attempts += 1;
        } else if (2..=16).contains(&tag) {
            bodies += 1;
            let mut repeated = 0;
            wire_fields(body, |field, value| {
                if tag == 3 && field == 2 {
                    validate_java_config(value)?;
                }
                let limit = match (tag, field) {
                    (7, 2) => 1024,
                    (13, 1) | (13, 3) | (13, 5) => 1000,
                    (5, 5) | (14, 2) => m::MAX_FACTS,
                    (6, 6) => m::MAX_FACTS,
                    (4, 2) | (4, 4) | (4, 6) => 10000,
                    _ => 0,
                };
                if limit > 0 {
                    repeated += 1;
                    if repeated > limit {
                        bail!("DECODE_REPEATED_BUDGET");
                    }
                    if matches!((tag, field), (7, 2) | (5, 5) | (14, 2)) && value.len() < 4 {
                        bail!("EMPTY_FACT_MESSAGE");
                    }
                }
                Ok(())
            })?;
        } else {
            bail!("UNSUPPORTED_ENVELOPE_FIELD");
        }
        Ok(())
    })?;
    if bodies != 1 || attempts != 1 {
        bail!("AMBIGUOUS_ENVELOPE");
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
    #[tokio::test]
    async fn oversized_frame_rejected_before_allocation() {
        let (mut a, mut b) = tokio::io::duplex(32);
        a.write_u32((m::MAX_FRAME + 1) as u32).await.unwrap();
        assert!(read_frame(&mut b).await.is_err());
    }
    #[tokio::test]
    async fn excessive_advice_fields_rejected_before_decode() {
        let (mut writer, mut reader) = tokio::io::duplex(16384);
        let advice = worker::BuildAdvice {
            missing: vec![String::new(); 1001],
            ..Default::default()
        };
        write_frame(
            &mut writer,
            &envelope("attempt", worker::envelope::Body::Advice(advice)),
        )
        .await
        .unwrap();
        assert!(read_frame(&mut reader).await.is_err());
    }
    #[test]
    fn request_presence_roundtrip() {
        let r = m::Request {
            suggestions: Some(false),
            ..Default::default()
        };
        let w: v1::TaskRequest = convert(&r).unwrap();
        assert_eq!(w.suggestions, Some(false));
        assert_eq!(
            convert::<_, m::Request>(&w).unwrap().suggestions,
            Some(false)
        );
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
                manifest: None,
                java: Some(config),
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
                manifest: None,
                java: Some(java),
            }),
        );
        assert!(validate_envelope_wire(&frame.encode_to_vec()).is_err());
        let baseline = envelope(
            "a",
            worker::envelope::Body::BaselineChunk(worker::BaselineChunk {
                seq: 0,
                facts: vec![worker::Fact::default()],
            }),
        );
        assert!(validate_envelope_wire(&baseline.encode_to_vec()).is_err());
    }
}

#[cfg(test)]
mod query_option_tests {
    use super::*;
    #[test]
    fn hierarchy_options_survive_task_and_stage_protobuf_transport() {
        let request = m::Request {
            operation: "runtime-implementations".into(),
            instantiability: "unknown".into(),
            transitive: true,
            algorithm: "exact".into(),
            analysis_world: "workspace-closed".into(),
            stages: vec![m::Stage {
                operation: "type-relations".into(),
                instantiability: "all".into(),
                transitive: true,
                algorithm: "cha".into(),
                analysis_world: "classpath-open".into(),
                ..Default::default()
            }],
            ..Default::default()
        };
        let wire: v1::TaskRequest = convert(&request).unwrap();
        let decoded = v1::TaskRequest::decode(wire.encode_to_vec().as_slice()).unwrap();
        let restored: m::Request = convert(&decoded).unwrap();
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
            operation: "annotations".into(),
            meta: true,
            stages: vec![m::Stage {
                operation: "annotations".into(),
                meta: true,
                ..Default::default()
            }],
            ..Default::default()
        };
        let wire: v1::TaskRequest = convert(&request).unwrap();
        let decoded = v1::TaskRequest::decode(wire.encode_to_vec().as_slice()).unwrap();
        let restored: m::Request = convert(&decoded).unwrap();
        assert!(restored.meta);
        assert!(restored.stages[0].meta);
        let legacy = v1::TaskRequest::decode(&[][..]).unwrap();
        assert!(!legacy.meta);
    }
}

#[cfg(test)]
mod fact_mapping_tests {
    use super::*;
    #[test]
    fn wire_mapping_preserves_large_spans_unicode_and_validates_required_payloads() {
        let fact: m::Fact = serde_json::from_value(serde_json::json!({
            "id":"中文😀", "kind":"reference", "owner":{"world":"w","producer":"p","kind":"source","id":"A.java"},
            "span":{"start":0,"end":u64::MAX},"attributes":{"nested":[1,null,{"key":"值"}]}
        })).unwrap();
        let wire = fact_to_wire(&fact).unwrap();
        assert_eq!(fact_from_wire(wire.clone()).unwrap(), fact);
        let mut missing = wire.clone();
        missing.owner = None;
        assert!(fact_from_wire(missing).is_err());
        let mut invalid = wire.clone();
        invalid.attributes_json = b"{".to_vec();
        assert!(fact_from_wire(invalid).is_err());
        let mut invalid = wire;
        invalid.kind = "unknown".into();
        assert!(fact_from_wire(invalid).is_err());
    }
}
