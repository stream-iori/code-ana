//! The native handle stays on its owning OS thread; only sealed CAF reads move off-thread.
use anyhow::{Context, Result, ensure};
use codeana_contracts::{graph::Row, graph_archive};
use codeana_model::{MAX_FRAME, Owner};
use prost::Message;
use sha2::{Digest, Sha256};

pub enum Event {
    Begin {
        mode: String,
        replacements: Vec<Owner>,
    },
    Chunk(Vec<Row>),
    End {
        chunks: u64,
        count: u64,
        checksum: String,
    },
}
pub struct Encoder {
    sender: tokio::sync::mpsc::Sender<Event>,
    batch: Vec<Row>,
    batch_bytes: usize,
    checksum: Sha256,
    count: u64,
    chunks: u64,
    bytes: u64,
    limits: codeana_contracts::java::OutputLimits,
}
impl Encoder {
    pub fn new(
        sender: tokio::sync::mpsc::Sender<Event>,
        limits: codeana_contracts::java::OutputLimits,
    ) -> Self {
        let mut checksum = Sha256::new();
        checksum.update(graph_archive::MAGIC);
        Self {
            sender,
            batch: Vec::with_capacity(64),
            batch_bytes: 0,
            checksum,
            count: 0,
            chunks: 0,
            bytes: 4,
            limits,
        }
    }
    pub fn push(&mut self, fact: Row) -> Result<()> {
        let bytes = fact.encode_to_vec();
        ensure!(
            bytes.len() <= MAX_FRAME,
            codeana_model::DomainError::new(codeana_model::ErrorCode::FactFrameBudget, "")
        );
        ensure!(
            self.count < self.limits.facts
                && self.bytes + bytes.len() as u64 + 4 <= self.limits.bytes,
            codeana_model::DomainError::new(codeana_model::ErrorCode::FactArchiveBudget, "")
        );
        self.bytes += bytes.len() as u64 + 4;
        graph_archive::update_digest(&mut self.checksum, &bytes);
        self.count += 1;
        let size = fact.encoded_len() + 10;
        ensure!(
            size <= MAX_FRAME - 4096,
            codeana_model::DomainError::new(codeana_model::ErrorCode::FactFrameBudget, "")
        );
        if self.batch.len() == 64 || self.batch_bytes + size > MAX_FRAME - 4096 {
            self.flush()?;
        }
        self.batch_bytes += size;
        self.batch.push(fact);
        Ok(())
    }
    fn flush(&mut self) -> Result<()> {
        if !self.batch.is_empty() {
            self.sender
                .blocking_send(Event::Chunk(std::mem::take(&mut self.batch)))
                .context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::DeliveryCancelled,
                    "",
                ))?;
            self.batch_bytes = 0;
            self.chunks += 1;
        }
        Ok(())
    }
    /// Called only after complete original CAF EOF/SHA validation succeeds.
    pub fn finish(mut self) -> Result<()> {
        self.flush()?;
        self.sender
            .blocking_send(Event::End {
                chunks: self.chunks,
                count: self.count,
                checksum: format!("{:x}", self.checksum.finalize()),
            })
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::DeliveryCancelled,
                "",
            ))?;
        Ok(())
    }
}
