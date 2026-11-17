//! Shared blocking row extraction, using the same encoder as archive-backed engines.
use crate::{delivery::Encoder, graph::Row, stream::SinkSender};
use anyhow::Result;
use codeana_contracts::graph_delivery::OutputLimits;
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
    let mut encoder = Encoder::new(
        sender.clone(),
        OutputLimits {
            bytes: result_bytes,
            facts: fact_limit,
        },
        budget.cloned(),
    );
    emit(&mut |row| {
        check()?;
        encoder.push(row)
    })?;
    check()?;
    let summary = encoder.finish()?;
    Ok((summary.count, summary.checksum))
}
#[cfg(test)]
mod budget_tests {
    use super::*;
    use crate::{
        graph::{Cell, cell::Value},
        stream::{GraphStream, GraphStreamEvent},
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
                GraphStreamEvent::VerifiedEnd(_) => complete = true,
                GraphStreamEvent::Begin { .. } => {
                    panic!("row extraction cannot begin a protocol session")
                }
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
