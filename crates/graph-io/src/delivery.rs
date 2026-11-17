//! One row verifier and encoder for archive-backed engines, direct extractors and IPC receivers.
use crate::{
    archive,
    graph::Row,
    stream::{BATCH_BYTES, BATCH_ROWS, GraphStreamEvent, SinkSender, row_bytes},
};
use anyhow::{Context, Result, ensure};
use codeana_contracts::graph_delivery::OutputLimits;
use codeana_model::{AnalysisContext, DomainError, ErrorCode, MAX_FRAME};
use prost::Message;
use sha2::{Digest, Sha256};
/// A completed row stream. Construction is restricted to successful verification/encoding.
#[derive(Debug, Clone)]
pub struct VerifiedEnd {
    pub(crate) chunks: u64,
    pub(crate) count: u64,
    pub(crate) checksum: String,
}
impl VerifiedEnd {
    pub fn chunks(&self) -> u64 {
        self.chunks
    }
    pub fn count(&self) -> u64 {
        self.count
    }
    pub fn checksum(&self) -> &str {
        &self.checksum
    }
    pub fn into_parts(self) -> (u64, u64, String) {
        (self.chunks, self.count, self.checksum)
    }
}
pub struct Verifier {
    digest: Sha256,
    count: u64,
    chunks: u64,
    bytes: u64,
    limits: OutputLimits,
    budget: Option<AnalysisContext>,
    failed: bool,
}
impl Verifier {
    pub fn new(limits: OutputLimits, budget: Option<AnalysisContext>) -> Self {
        let mut digest = Sha256::new();
        digest.update(archive::MAGIC);
        Self {
            digest,
            count: 0,
            chunks: 0,
            bytes: 4,
            limits,
            budget,
            failed: false,
        }
    }
    pub fn chunks(&self) -> u64 {
        self.chunks
    }
    pub fn record_chunk(&mut self) {
        self.chunks += 1;
    }
    pub fn push(&mut self, row: &Row) -> Result<usize> {
        let result = self.push_inner(row);
        if result.is_err() {
            self.failed = true;
        }
        result
    }
    fn push_inner(&mut self, row: &Row) -> Result<usize> {
        ensure!(!self.failed, "GRAPH_DELIVERY_FAILED");
        let size = row.encoded_len();
        ensure!(
            size > 0 && size.checked_add(128).is_some_and(|n| n <= MAX_FRAME),
            "GRAPH_RECORD_BUDGET"
        );
        ensure!(
            self.count < self.limits.facts
                && (size as u64)
                    .checked_add(4)
                    .is_some_and(|n| n <= self.limits.bytes.saturating_sub(self.bytes)),
            DomainError::new(ErrorCode::GraphArchiveBudget, "")
        );
        if let Some(budget) = &self.budget {
            budget
                .charge(
                    1,
                    1,
                    (size as u64)
                        .saturating_add(row_bytes(row) as u64)
                        .saturating_add((2 * std::mem::size_of::<Row>()) as u64),
                )
                .map_err(DomainError::analysis)?;
        }
        let encoded = row.encode_to_vec();
        codeana_contracts::validate_graph_row_wire(&encoded)?;
        archive::update_digest(&mut self.digest, &encoded);
        self.count += 1;
        self.bytes += size as u64 + 4;
        Ok(size)
    }
    fn seal(self) -> Result<VerifiedEnd> {
        ensure!(!self.failed, "GRAPH_DELIVERY_FAILED");
        if let Some(budget) = &self.budget {
            budget.checkpoint().map_err(DomainError::analysis)?;
        }
        ensure!(
            self.bytes <= self.limits.bytes,
            DomainError::new(ErrorCode::GraphArchiveBudget, "")
        );
        Ok(VerifiedEnd {
            chunks: self.chunks,
            count: self.count,
            checksum: format!("{:x}", self.digest.finalize()),
        })
    }
    pub fn verify_end(self, chunks: u64, count: u64, checksum: &str) -> Result<VerifiedEnd> {
        let end = self.seal()?;
        ensure!(
            end.chunks == chunks && end.count == count && end.checksum == checksum,
            DomainError::new(ErrorCode::ResultChecksumMismatch, "")
        );
        Ok(end)
    }
}
pub struct Encoder {
    sender: SinkSender,
    batch: Vec<Row>,
    batch_bytes: usize,
    verifier: Verifier,
}
impl Encoder {
    pub fn new(sender: SinkSender, limits: OutputLimits, budget: Option<AnalysisContext>) -> Self {
        Self {
            sender,
            batch: vec![],
            batch_bytes: 0,
            verifier: Verifier::new(limits, budget),
        }
    }
    pub fn push(&mut self, row: Row) -> Result<()> {
        let size = self.verifier.push(&row)?;
        if !self.batch.is_empty()
            && (self.batch.len() == BATCH_ROWS || self.batch_bytes + size > BATCH_BYTES)
        {
            self.flush()?;
        }
        self.batch_bytes += size;
        self.batch.push(row);
        Ok(())
    }
    fn flush(&mut self) -> Result<()> {
        if !self.batch.is_empty() {
            self.sender
                .blocking_send(GraphStreamEvent::Batch(std::mem::take(&mut self.batch)))
                .context("CANDIDATE_STREAM_CLOSED")?;
            self.batch_bytes = 0;
            self.verifier.record_chunk();
        }
        Ok(())
    }
    /// Invoke only after the extractor/archive reader returned success.
    pub fn finish(mut self) -> Result<VerifiedEnd> {
        self.flush()?;
        let end = self.verifier.seal()?;
        self.sender
            .blocking_send(GraphStreamEvent::VerifiedEnd(end.clone()))
            .context("CANDIDATE_STREAM_CLOSED")?;
        Ok(end)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::stream::GraphStream;
    #[test]
    fn wrong_receipts_and_ignored_push_errors_never_produce_a_verified_end() {
        let row = Row {
            table: 1,
            world: "w".into(),
            cells: vec![],
        };
        let checksum = format!(
            "{:x}",
            Sha256::digest([b"GRA1".as_slice(), &[0, 0, 0, 5, 8, 1, 26, 1, b'w']].concat())
        );
        for (chunks, count, sha) in [
            (0, 1, checksum.as_str()),
            (1, 0, checksum.as_str()),
            (1, 1, "wrong"),
        ] {
            let mut verifier = Verifier::new(OutputLimits::default(), None);
            verifier.push(&row).unwrap();
            verifier.record_chunk();
            assert!(verifier.verify_end(chunks, count, sha).is_err());
        }
        let mut verifier = Verifier::new(OutputLimits::default(), None);
        verifier.push(&row).unwrap();
        verifier.record_chunk();
        assert!(
            verifier
                .push(&Row {
                    table: 0,
                    world: "w".into(),
                    cells: vec![]
                })
                .is_err()
        );
        assert!(verifier.verify_end(1, 1, &checksum).is_err());
    }
    #[tokio::test]
    async fn boundaries_share_exact_rows_batches_and_canonical_digest() {
        for count in [0, 1, 64, 65] {
            let (sender, mut stream) = GraphStream::channel();
            let work = tokio::task::spawn_blocking(move || -> Result<VerifiedEnd> {
                let mut encoder = Encoder::new(sender, OutputLimits::default(), None);
                for _ in 0..count {
                    encoder.push(Row {
                        table: 1,
                        world: "w".into(),
                        cells: vec![],
                    })?;
                }
                encoder.finish()
            });
            let mut rows = 0;
            let mut chunks = 0;
            let mut end = None;
            while let Some(item) = stream.recv().await {
                match item.event {
                    GraphStreamEvent::Batch(batch) => {
                        rows += batch.len();
                        chunks += 1;
                    }
                    GraphStreamEvent::VerifiedEnd(summary) => end = Some(summary),
                    GraphStreamEvent::Begin { .. } => panic!("encoder does not own Begin"),
                }
            }
            let result = work.await.unwrap().unwrap();
            let end = end.unwrap();
            assert_eq!(rows, count);
            assert_eq!(chunks, (count as u64).div_ceil(64));
            assert_eq!(result.count, count as u64);
            assert_eq!(end.checksum, result.checksum);
            assert_eq!(end.chunks, chunks);
            let mut raw = archive::MAGIC.to_vec();
            for _ in 0..count {
                raw.extend_from_slice(&[0, 0, 0, 5, 8, 1, 26, 1, b'w']);
            }
            assert_eq!(result.checksum, format!("{:x}", Sha256::digest(raw)));
        }
    }
}
