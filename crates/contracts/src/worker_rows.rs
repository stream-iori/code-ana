//! Shared blocking row production with bounded batches and an authenticated End.
use crate::{
    graph::Row,
    graph_archive::{MAGIC, update_digest},
    stream::{BATCH_BYTES, BATCH_ROWS, GraphStreamEvent, SinkSender},
};
use anyhow::{Result, ensure};
use codeana_model::MAX_FRAME;
use prost::Message;
use sha2::{Digest, Sha256};

pub fn produce(
    sender: &SinkSender,
    result_bytes: u64,
    fact_limit: u64,
    check: &dyn Fn() -> Result<()>,
    emit: impl FnOnce(&mut dyn FnMut(Row) -> Result<()>) -> Result<()>,
) -> Result<(u64, String)> {
    let mut digest = Sha256::new();
    digest.update(MAGIC);
    let mut count = 0;
    let mut bytes = 4u64;
    let mut batch = Vec::new();
    let mut batch_size = 0;
    emit(&mut |row| {
        check()?;
        let encoded = row.encode_to_vec();
        ensure!(
            !encoded.is_empty() && encoded.len() + 128 <= MAX_FRAME,
            "GRAPH_RECORD_BUDGET"
        );
        ensure!(
            count < fact_limit && bytes + encoded.len() as u64 + 4 <= result_bytes,
            "GRAPH_ARCHIVE_BUDGET"
        );
        if !batch.is_empty()
            && (batch.len() == BATCH_ROWS || batch_size + encoded.len() > BATCH_BYTES)
        {
            sender
                .blocking_send(GraphStreamEvent::Batch(std::mem::take(&mut batch)))
                .map_err(|_| anyhow::anyhow!("CANDIDATE_STREAM_CLOSED"))?;
            batch_size = 0;
        }
        update_digest(&mut digest, &encoded);
        count += 1;
        bytes += encoded.len() as u64 + 4;
        batch_size += encoded.len();
        batch.push(row);
        Ok(())
    })?;
    check()?;
    if !batch.is_empty() {
        sender
            .blocking_send(GraphStreamEvent::Batch(batch))
            .map_err(|_| anyhow::anyhow!("CANDIDATE_STREAM_CLOSED"))?;
    }
    sender
        .blocking_send(GraphStreamEvent::VerifiedEnd)
        .map_err(|_| anyhow::anyhow!("CANDIDATE_STREAM_CLOSED"))?;
    Ok((count, format!("{:x}", digest.finalize())))
}
