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
    produce_with_budget(sender, result_bytes, fact_limit, check, None, emit)
}

pub fn produce_with_budget(
    sender: &SinkSender,
    result_bytes: u64,
    fact_limit: u64,
    check: &dyn Fn() -> Result<()>,
    budget: Option<&codeana_model::AnalysisContext>,
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
        let encoded_len = row.encoded_len();
        ensure!(
            encoded_len > 0 && encoded_len.checked_add(128).is_some_and(|n| n <= MAX_FRAME),
            "GRAPH_RECORD_BUDGET"
        );
        ensure!(
            count < fact_limit
                && (encoded_len as u64)
                    .checked_add(4)
                    .is_some_and(|n| n <= result_bytes.saturating_sub(bytes)),
            "GRAPH_ARCHIVE_BUDGET"
        );
        if let Some(budget) = budget {
            // Reserve the encoded buffer and retained row/batch slots before allocation.
            budget.charge(
                1,
                1,
                (encoded_len as u64)
                    .saturating_add(crate::stream::row_bytes(&row) as u64)
                    .saturating_add((2 * std::mem::size_of::<Row>()) as u64),
            )?;
        }
        let encoded = row.encode_to_vec();
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

#[cfg(test)]
mod budget_tests {
    use super::*;
    use crate::{
        graph::{Cell, cell::Value},
        stream::GraphStream,
    };
    use codeana_model::{AnalysisContext, AnalysisLimits};
    fn row() -> Row {
        Row {
            table: 1,
            world: "world".into(),
            cells: vec![Cell {
                value: Some(Value::Text("retained payload".into())),
            }],
        }
    }
    #[tokio::test]
    async fn rejected_output_never_certifies_the_stream() {
        let budget = AnalysisContext::new(
            AnalysisLimits {
                max_payload_bytes: 1,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        let meter = budget.clone();
        let (sender, mut stream) = GraphStream::channel();
        let result = tokio::task::spawn_blocking(move || {
            produce_with_budget(&sender, 1 << 20, 100, &|| Ok(()), Some(&meter), |push| {
                push(row())
            })
        })
        .await
        .unwrap();
        assert!(result.is_err());
        assert!(stream.recv().await.is_none());
        assert_eq!(budget.usage().payload_bytes, 0);
        assert!(budget.usage().failure.is_some());
    }
    async fn run(budget: Option<AnalysisContext>) -> (Vec<Row>, (u64, String)) {
        let (sender, mut stream) = GraphStream::channel();
        let work = tokio::task::spawn_blocking(move || {
            produce_with_budget(&sender, 1 << 20, 100, &|| Ok(()), budget.as_ref(), |push| {
                push(row())
            })
        });
        let mut rows = vec![];
        let mut complete = false;
        while let Some(item) = stream.recv().await {
            match item.event {
                GraphStreamEvent::Batch(batch) => rows.extend(batch),
                GraphStreamEvent::VerifiedEnd => complete = true,
            }
        }
        assert!(complete);
        (rows, work.await.unwrap().unwrap())
    }
    #[tokio::test]
    async fn accounting_keeps_normal_archive_rows_and_digest_identical() {
        let plain = run(None).await;
        let budget = AnalysisContext::new(Default::default(), None).unwrap();
        let metered = run(Some(budget.clone())).await;
        assert_eq!(plain, metered);
        assert_eq!(budget.usage().nodes, 1);
        assert!(budget.usage().payload_bytes > 0);
    }
}
