//! Admit business wire buffers and decoded records before allocation. The small fixed
//! control lane is independent of a failed task ledger so failures/cancel can terminate.
use crate::{
    decode_frame, graph as g, packed_count, validate_envelope_wire, wire_fields, worker as w,
};
use anyhow::{Result, ensure};
use codeana_model::{AnalysisContext, DomainError, ErrorCode};
use prost::Message;
use std::mem::size_of;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const CONTROL_BYTES: usize = 16 * 1024;
pub const BOOTSTRAP_BYTES: usize = 4096;
pub const CANCEL_BYTES: usize = 1024;

fn control_tag(tag: u64) -> bool {
    matches!(tag, 2 | 8 | 9 | 10 | 11 | 12 | 16 | 17 | 18)
}
fn is_control(frame: &w::Envelope) -> bool {
    use w::envelope::Body;
    matches!(
        frame.body,
        Some(
            Body::Hello(_)
                | Body::End(_)
                | Body::Replan(_)
                | Body::Cancel(_)
                | Body::Ack(_)
                | Body::Failure(_)
                | Body::Acked(_)
                | Body::Reuse(_)
                | Body::Reused(_)
        )
    )
}
fn body_tag(bytes: &[u8]) -> Result<u64> {
    let mut body = 0;
    wire_fields(bytes, |tag, _| {
        if tag != 1 {
            body = tag;
        }
        Ok(())
    })?;
    Ok(body)
}

#[derive(Default, Debug)]
struct Allocation {
    work: u64,
    nodes: u64,
    bytes: u64,
}
impl Allocation {
    fn record<T>(&mut self) {
        self.work += 1;
        self.nodes += 1;
        self.bytes += 4 * size_of::<T>() as u64;
    }
    fn slots<T>(&mut self, count: usize) {
        self.work += count as u64;
        self.bytes += 4 * (size_of::<T>() as u64) * (count as u64);
    }
    fn source(&mut self, bytes: &[u8]) -> Result<()> {
        self.record::<w::JavaSource>();
        wire_fields(bytes, |tag, _| {
            if tag == 1 {
                self.record::<w::SourceUnit>();
            }
            Ok(())
        })
    }
    fn manifest(&mut self, bytes: &[u8]) -> Result<()> {
        wire_fields(bytes, |tag, _| {
            if tag == 1 {
                self.record::<w::SourceUnit>();
            }
            Ok(())
        })
    }
    fn context(&mut self, bytes: &[u8]) -> Result<()> {
        self.record::<w::JavaContext>();
        wire_fields(bytes, |tag, value| {
            match tag {
                5 | 12 => self.source(value)?,
                6 | 21 | 22 | 23 => self.slots::<String>(1),
                14..=17 => self.slots::<u32>(if value.is_empty() {
                    1
                } else {
                    packed_count(value)?
                }),
                _ => {}
            }
            Ok(())
        })
    }
    fn java(&mut self, bytes: &[u8]) -> Result<()> {
        self.record::<w::JavaConfig>();
        wire_fields(bytes, |tag, value| {
            match tag {
                5 => self.context(value)?,
                11 => self.source(value)?,
                12 => self.slots::<String>(1),
                _ => {}
            }
            Ok(())
        })
    }
}
fn allocation(bytes: &[u8]) -> Result<Allocation> {
    let mut cost = Allocation {
        work: 1,
        nodes: 1,
        bytes: 2 * bytes.len() as u64 + size_of::<w::Envelope>() as u64 + 256,
    };
    wire_fields(bytes, |body, value| {
        if body == 1 {
            return Ok(());
        }
        wire_fields(value, |field, nested| {
            match (body, field) {
                (3 | 5, 1) => cost.manifest(nested)?,
                (3, 2) => cost.java(nested)?,
                (3, 3) => cost.record::<w::RustConfig>(),
                (3, 4) => cost.record::<w::PythonConfig>(),
                (4, 2 | 4 | 6) => cost.slots::<String>(1),
                (6, 6) => cost.record::<w::Owner>(),
                (7, 3) => {
                    cost.record::<g::Row>();
                    wire_fields(nested, |tag, _| {
                        if tag == 2 {
                            cost.slots::<g::Cell>(1);
                        }
                        Ok(())
                    })?;
                }
                (13, 1 | 3 | 5) => cost.slots::<String>(1),
                _ => {}
            }
            Ok(())
        })?;
        Ok(())
    })?;
    Ok(cost)
}

/// Prefix and fixed-body progress survive dropped read futures. A metered large
/// body is charged once before resize; no cancellation can renew its allowance.
pub struct FrameReader {
    prefix: [u8; 4],
    prefix_read: usize,
    fixed: Box<[u8]>,
    body: Vec<u8>,
    body_read: usize,
}
impl Default for FrameReader {
    fn default() -> Self {
        Self {
            prefix: [0; 4],
            prefix_read: 0,
            fixed: vec![0; CONTROL_BYTES].into_boxed_slice(),
            body: Vec::new(),
            body_read: 0,
        }
    }
}
impl FrameReader {
    pub async fn next<R: AsyncRead + Unpin>(&mut self, input: &mut R) -> Result<w::Envelope> {
        self.read(input, None, codeana_model::MAX_FRAME, None).await
    }
    pub async fn next_with_budget<R: AsyncRead + Unpin>(
        &mut self,
        input: &mut R,
        budget: &AnalysisContext,
    ) -> Result<w::Envelope> {
        self.read(input, Some(budget), codeana_model::MAX_FRAME, None)
            .await
    }
    pub async fn next_bootstrap<R: AsyncRead + Unpin>(
        &mut self,
        input: &mut R,
    ) -> Result<w::Envelope> {
        self.read(input, None, BOOTSTRAP_BYTES, Some(0)).await
    }
    pub async fn next_cancel<R: AsyncRead + Unpin>(
        &mut self,
        input: &mut R,
    ) -> Result<w::Envelope> {
        self.read(input, None, CANCEL_BYTES, Some(10)).await
    }
    /// Acknowledgements/terminal control never admit a business model.
    pub async fn next_control<R: AsyncRead + Unpin>(
        &mut self,
        input: &mut R,
    ) -> Result<w::Envelope> {
        self.read(input, None, CONTROL_BYTES, Some(u64::MAX - 1))
            .await
    }
    /// During cancellation, stream-discard provisional business frames without
    /// allocating/decoding them. A fixed-size terminal/control frame is returned.
    /// An empty envelope means one business frame was discarded.
    pub async fn next_terminal<R: AsyncRead + Unpin>(
        &mut self,
        input: &mut R,
    ) -> Result<w::Envelope> {
        self.read(input, None, codeana_model::MAX_FRAME, Some(u64::MAX))
            .await
    }
    /// Failure delivery may use only this bounded control identity, even when
    /// model admission failed. Producers put attempt first in the envelope.
    pub fn pending_attempt(&self) -> Option<String> {
        let bytes = if self.body.is_empty() {
            &self.fixed[..self.body_read.min(CONTROL_BYTES)]
        } else {
            &self.body[..self.body_read]
        };
        let mut attempt = None;
        let _ = wire_fields(bytes, |tag, value| {
            if tag == 1 && value.len() <= 256 {
                attempt = std::str::from_utf8(value).ok().map(str::to_owned);
            }
            anyhow::bail!("IDENTITY_ONLY")
        });
        attempt
    }
    async fn read<R: AsyncRead + Unpin>(
        &mut self,
        input: &mut R,
        budget: Option<&AnalysisContext>,
        maximum: usize,
        required: Option<u64>,
    ) -> Result<w::Envelope> {
        let _wait =
            crate::performance::Timer::start(crate::performance::Metric::FrameReadWait, 0, false);
        while self.prefix_read < 4 {
            let n = input.read(&mut self.prefix[self.prefix_read..]).await?;
            ensure!(n != 0, DomainError::new(ErrorCode::ControlFrameEof, ""));
            self.prefix_read += n;
        }
        let length = u32::from_be_bytes(self.prefix) as usize;
        ensure!(
            length > 0 && length <= maximum,
            DomainError::new(ErrorCode::InvalidFrameLength, "")
        );
        let small = length <= CONTROL_BYTES;
        let drain = required == Some(u64::MAX);
        if !small && !drain && self.body.is_empty() {
            if let Some(budget) = budget
                && let Err(error) =
                    budget.charge(2 + 3 * (length as u64).div_ceil(256), 0, length as u64 + 4)
            {
                // Read only a bounded control prefix, never the denied body.
                // This keeps a typed rejection tied to the new Analyze attempt.
                let end = length.min(512);
                let _ = tokio::time::timeout(std::time::Duration::from_millis(50), async {
                    while self.body_read < end {
                        let n = input.read(&mut self.fixed[self.body_read..end]).await?;
                        if n == 0 {
                            break;
                        }
                        self.body_read += n;
                    }
                    Ok::<_, std::io::Error>(())
                })
                .await;
                return Err(error.into());
            }

            self.body.reserve_exact(length);
            self.body.resize(length, 0);
        }
        while self.body_read < length {
            let target = if drain && !small {
                &mut self.fixed[..(length - self.body_read).min(CONTROL_BYTES)]
            } else if small {
                &mut self.fixed[self.body_read..length]
            } else {
                &mut self.body[self.body_read..]
            };
            let n = input.read(target).await?;
            ensure!(n != 0, DomainError::new(ErrorCode::ControlFrameEof, ""));
            self.body_read += n;
        }
        if drain && !small {
            self.reset();
            return Ok(w::Envelope::default());
        }
        let bytes = if small {
            &self.fixed[..length]
        } else {
            &self.body[..]
        };
        let tag = body_tag(bytes)?;
        if drain && !control_tag(tag) {
            self.reset();
            return Ok(w::Envelope::default());
        }
        if small
            && !control_tag(tag)
            && let Some(budget) = budget
        {
            budget.charge(1 + 3 * (length as u64).div_ceil(256), 0, 0)?;
        }
        validate_envelope_wire(bytes)?;
        if let Some(required) = required.filter(|r| *r != u64::MAX) {
            ensure!(
                if required == u64::MAX - 1 {
                    control_tag(tag)
                } else if required == 0 {
                    matches!(tag, 2 | 10)
                } else {
                    tag == required
                },
                "UNEXPECTED_CONTROL_FRAME"
            );
        }
        let control = control_tag(tag);
        ensure!(!control || small, "CONTROL_FRAME_BUDGET");
        if !control && let Some(budget) = budget {
            let cost = allocation(bytes)?;
            // Fixed input memory already exists. No business heap object is created
            // until all remaining wire/decoded capacities are admitted atomically.
            budget.charge(
                cost.work,
                cost.nodes,
                cost.bytes + if small { length as u64 + 4 } else { 0 },
            )?;
        }
        drop(_wait);
        let result = decode_frame(bytes);
        self.reset();
        result
    }
    fn reset(&mut self) {
        self.prefix_read = 0;
        self.body_read = 0;
        self.body.clear();
        if self.body.capacity() > 65536 {
            self.body = Vec::new();
        }
    }
}

pub async fn read_frame_with_budget<R: AsyncRead + Unpin>(
    input: &mut R,
    budget: Option<&AnalysisContext>,
) -> Result<w::Envelope> {
    let mut reader = FrameReader::default();
    if let Some(budget) = budget {
        reader.next_with_budget(input, budget).await
    } else {
        reader.next(input).await
    }
}

pub async fn write_frame_with_budget<W: AsyncWrite + Unpin>(
    output: &mut W,
    frame: &w::Envelope,
    budget: Option<&AnalysisContext>,
) -> Result<()> {
    let length = frame.encoded_len();
    ensure!(
        length > 0 && length <= codeana_model::MAX_FRAME,
        DomainError::new(ErrorCode::FrameBudget, "")
    );
    if is_control(frame) {
        ensure!(length <= CONTROL_BYTES, "CONTROL_FRAME_BUDGET");
        let mut fixed = vec![0u8; length].into_boxed_slice();
        crate::performance::measure(
            crate::performance::Metric::FrameEncode,
            length as u64,
            true,
            || frame.encode(&mut &mut fixed[..length]),
        )?;
        let _write = crate::performance::Timer::start(
            crate::performance::Metric::FrameWriteWait,
            length as u64,
            false,
        );
        output.write_u32(length as u32).await?;
        output.write_all(&fixed[..length]).await?;
    } else {
        if let Some(budget) = budget {
            budget.charge(1, 0, length as u64 + 4)?;
        }
        let bytes = crate::performance::measure(
            crate::performance::Metric::FrameEncode,
            length as u64,
            true,
            || frame.encode_to_vec(),
        );
        let _write = crate::performance::Timer::start(
            crate::performance::Metric::FrameWriteWait,
            length as u64,
            false,
        );
        output.write_u32(length as u32).await?;
        output.write_all(&bytes).await?;
    }
    output.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::AnalysisLimits;
    use tokio::io::AsyncWriteExt;

    fn budget(bytes: u64, nodes: u64) -> AnalysisContext {
        AnalysisContext::new(
            AnalysisLimits {
                max_payload_bytes: bytes,
                max_nodes: nodes,
                ..Default::default()
            },
            None,
        )
        .unwrap()
    }
    fn discover(count: usize, path: &str) -> w::Envelope {
        crate::envelope(
            "a",
            w::envelope::Body::Discover(w::Discover {
                manifest: Some(w::Manifest {
                    files: (0..count)
                        .map(|_| w::SourceUnit {
                            path: path.into(),
                            ..Default::default()
                        })
                        .collect(),
                }),
                ..Default::default()
            }),
        )
    }
    fn wire(frame: &w::Envelope) -> Vec<u8> {
        let body = frame.encode_to_vec();
        let mut bytes = (body.len() as u32).to_be_bytes().to_vec();
        bytes.extend(body);
        bytes
    }
    #[tokio::test]
    async fn large_wire_denied_before_read_or_heap_allocation() {
        let frame = discover(1, &"x".repeat(CONTROL_BYTES + 1));
        let bytes = wire(&frame);
        let mut cursor = std::io::Cursor::new(bytes);
        let mut reader = FrameReader::default();
        let budget = budget(32, 100);
        let error = reader
            .next_with_budget(&mut cursor, &budget)
            .await
            .unwrap_err();
        assert!(
            error
                .downcast_ref::<codeana_model::AnalysisFailure>()
                .is_some()
        );
        assert!(cursor.position() <= 516);
        assert_eq!(reader.pending_attempt().as_deref(), Some("a"));
        assert_eq!(reader.body.capacity(), 0);
        assert_eq!(budget.usage().payload_bytes, 0);
    }
    #[tokio::test]
    async fn tiny_repeated_messages_denied_before_prost_creates_records() {
        let frame = discover(100, "");
        let bytes = wire(&frame);
        assert!(bytes.len() < CONTROL_BYTES);
        let budget = budget(1 << 20, 10);
        let mut reader = FrameReader::default();
        let error = reader
            .next_with_budget(&mut bytes.as_slice(), &budget)
            .await
            .unwrap_err();
        assert!(
            error
                .downcast_ref::<codeana_model::AnalysisFailure>()
                .is_some()
        );
        assert_eq!(reader.pending_attempt().as_deref(), Some("a"));
        assert_eq!(budget.usage().nodes, 0);
        assert_eq!(budget.usage().payload_bytes, 0);
        assert_eq!(reader.body.capacity(), 0);
    }
    #[tokio::test]
    async fn bootstrap_rejects_business_and_oversize_before_allocating() {
        let mut reader = FrameReader::default();
        assert!(
            reader
                .next_bootstrap(&mut wire(&discover(1, "")).as_slice())
                .await
                .is_err()
        );
        let mut reader = FrameReader::default();
        let bytes = wire(&discover(1, &"x".repeat(BOOTSTRAP_BYTES)));
        let mut cursor = std::io::Cursor::new(bytes);
        assert!(reader.next_bootstrap(&mut cursor).await.is_err());
        assert_eq!(cursor.position(), 4);
        assert_eq!(reader.body.capacity(), 0);
    }
    #[tokio::test]
    async fn failed_budget_can_still_send_and_receive_terminal_receipt() {
        let budget = budget(1, 1);
        assert!(budget.charge(0, 0, 2).is_err());
        let grant = codeana_model::AnalysisGrant {
            id: "g".into(),
            language: codeana_model::AnalysisLanguage::Rust,
            limits: AnalysisLimits::default(),
        };
        let frame = crate::envelope(
            "a",
            w::envelope::Body::Failure(w::Failure {
                code: "ANALYSIS_RESOURCE_LIMIT".into(),
                message: "limit".into(),
                analysis_usage: Some(crate::analysis_budget::receipt(
                    &grant,
                    &budget.usage(),
                    "input_decode",
                )),
            }),
        );
        let mut bytes = Vec::new();
        write_frame_with_budget(&mut bytes, &frame, Some(&budget))
            .await
            .unwrap();
        assert_eq!(
            FrameReader::default()
                .next_with_budget(&mut bytes.as_slice(), &budget)
                .await
                .unwrap(),
            frame
        );
        assert_eq!(budget.usage().payload_bytes, 0);
    }
    #[tokio::test]
    async fn ack_control_lane_rejects_business_before_decode_or_large_allocation() {
        let mut reader = FrameReader::default();
        assert!(
            reader
                .next_control(&mut wire(&discover(100, "")).as_slice())
                .await
                .is_err()
        );
        assert_eq!(reader.body.capacity(), 0);
        let mut reader = FrameReader::default();
        let mut input = std::io::Cursor::new(wire(&discover(1, &"x".repeat(CONTROL_BYTES + 1))));
        assert!(reader.next_control(&mut input).await.is_err());
        assert_eq!(input.position(), 4);
        assert_eq!(reader.body.capacity(), 0);
    }
    #[tokio::test]
    async fn outgoing_rejection_writes_no_prefix() {
        let mut output = Vec::new();
        let budget = budget(1, 1000);
        assert!(
            write_frame_with_budget(&mut output, &discover(1, "abc"), Some(&budget))
                .await
                .is_err()
        );
        assert!(output.is_empty());
    }
    #[tokio::test]
    async fn cancelled_partial_large_read_is_charged_once_and_can_drain_without_decode() {
        let bytes = wire(&discover(1, &"x".repeat(CONTROL_BYTES + 32)));
        let baseline = budget(1 << 24, 1000);
        FrameReader::default()
            .next_with_budget(&mut bytes.as_slice(), &baseline)
            .await
            .unwrap();
        let budget = budget(1 << 24, 1000);
        let (mut sender, mut receiver) = tokio::io::duplex(1 << 17);
        sender.write_all(&bytes[..13]).await.unwrap();
        let mut reader = FrameReader::default();
        assert!(
            tokio::time::timeout(
                std::time::Duration::from_millis(5),
                reader.next_with_budget(&mut receiver, &budget)
            )
            .await
            .is_err()
        );
        sender.write_all(&bytes[13..]).await.unwrap();
        reader
            .next_with_budget(&mut receiver, &budget)
            .await
            .unwrap();
        assert_eq!(budget.usage(), baseline.usage());
        // A second interrupted read switches to cancellation's fixed discard lane.
        sender.write_all(&bytes[..13]).await.unwrap();
        assert!(
            tokio::time::timeout(
                std::time::Duration::from_millis(5),
                reader.next_with_budget(&mut receiver, &budget)
            )
            .await
            .is_err()
        );
        sender.write_all(&bytes[13..]).await.unwrap();
        let terminal = crate::envelope("a", w::envelope::Body::Cancel(w::Cancel {}));
        sender.write_all(&wire(&terminal)).await.unwrap();
        assert!(
            reader
                .next_terminal(&mut receiver)
                .await
                .unwrap()
                .body
                .is_none()
        );
        assert_eq!(reader.next_terminal(&mut receiver).await.unwrap(), terminal);
    }
    #[tokio::test]
    async fn terminal_drain_discards_large_frames_without_heap_allocation() {
        let bytes = wire(&discover(1, &"x".repeat(CONTROL_BYTES * 3)));
        let mut reader = FrameReader::default();
        assert!(
            reader
                .next_terminal(&mut bytes.as_slice())
                .await
                .unwrap()
                .body
                .is_none()
        );
        assert_eq!(reader.body.capacity(), 0);
    }
    #[test]
    fn error_formatting_stops_at_utf8_boundary() {
        let error = anyhow::anyhow!("{}", "字".repeat(20000));
        let text = crate::analysis_budget::bounded_error(&error);
        assert_eq!(text.len(), 8190);
    }
    #[tokio::test]
    async fn prepaid_cancel_lane_cannot_decode_business_payloads() {
        let mut reader = FrameReader::default();
        assert!(
            reader
                .next_cancel(&mut wire(&discover(1, "")).as_slice())
                .await
                .is_err()
        );
        assert_eq!(reader.body.capacity(), 0);
    }
}
