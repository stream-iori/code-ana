//! Provisional rows bounded by batches and decoded allocations. Only VerifiedEnd seals.
use anyhow::{Result, anyhow};
use std::sync::{
    Arc,
    atomic::{AtomicU64, Ordering},
};
use tokio::sync::{OwnedSemaphorePermit, Semaphore, mpsc};

pub const QUEUE_BYTES: usize = 8 << 20;
pub const BATCH_ROWS: usize = 64;
pub const BATCH_BYTES: usize = 256 << 10;
pub enum GraphStreamEvent {
    Batch(Vec<crate::graph::Row>),
    VerifiedEnd,
}
#[derive(Clone, Default)]
pub struct StreamMetrics(Arc<Counters>);
#[derive(Default)]
struct Counters {
    current: AtomicU64,
    peak: AtomicU64,
    wait_ns: AtomicU64,
    receive_wait_ns: AtomicU64,
}
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct StreamSummary {
    pub peak_bytes: u64,
    pub send_wait_ns: u64,
    pub receive_wait_ns: u64,
}
impl StreamMetrics {
    pub fn summary(&self) -> StreamSummary {
        StreamSummary {
            peak_bytes: self.0.peak.load(Ordering::Relaxed),
            send_wait_ns: self.0.wait_ns.load(Ordering::Relaxed),
            receive_wait_ns: self.0.receive_wait_ns.load(Ordering::Relaxed),
        }
    }
}
struct Lease {
    _permit: OwnedSemaphorePermit,
    bytes: u64,
    metrics: StreamMetrics,
}
impl Drop for Lease {
    fn drop(&mut self) {
        self.metrics
            .0
            .current
            .fetch_sub(self.bytes, Ordering::Relaxed);
    }
}
/// Keep this item alive until its rows have been processed/written.
pub struct StreamItem {
    pub event: GraphStreamEvent,
    _lease: Option<Lease>,
}
#[derive(Clone)]
pub struct SinkSender {
    sender: mpsc::Sender<StreamItem>,
    budget: Arc<Semaphore>,
    pub metrics: StreamMetrics,
}
pub struct GraphStream {
    receiver: mpsc::Receiver<StreamItem>,
    pending: std::vec::IntoIter<crate::graph::Row>,
    lease: Option<Lease>,
    ended: bool,
    metrics: StreamMetrics,
}
/// Allocation estimate includes decoded row/cell capacities, not only wire bytes.
pub fn batch_bytes(rows: &Vec<crate::graph::Row>) -> usize {
    rows.capacity() * std::mem::size_of::<crate::graph::Row>()
        + rows
            .iter()
            .map(|r| {
                r.world.capacity()
                    + r.cells.capacity() * std::mem::size_of::<crate::graph::Cell>()
                    + r.cells
                        .iter()
                        .map(|c| match &c.value {
                            Some(crate::graph::cell::Value::Text(s)) => s.capacity(),
                            Some(crate::graph::cell::Value::Blob(b)) => b.capacity(),
                            _ => 0,
                        })
                        .sum::<usize>()
            })
            .sum::<usize>()
}
impl SinkSender {
    pub async fn send(
        &self,
        event: GraphStreamEvent,
    ) -> Result<(), mpsc::error::SendError<GraphStreamEvent>> {
        let started = std::time::Instant::now();
        let bytes = match &event {
            GraphStreamEvent::Batch(rows) => batch_bytes(rows),
            _ => 0,
        };
        // An oversized legal batch acquires the entire budget and runs alone.
        let lease = if bytes > 0 {
            let permit = tokio::select! {biased; _=self.sender.closed()=>return Err(mpsc::error::SendError(event)),p=self.budget.clone().acquire_many_owned(bytes.min(QUEUE_BYTES) as u32)=>p.expect("stream budget never closed")};
            let current = self
                .metrics
                .0
                .current
                .fetch_add(bytes as u64, Ordering::Relaxed)
                + bytes as u64;
            self.metrics.0.peak.fetch_max(current, Ordering::Relaxed);
            Some(Lease {
                _permit: permit,
                bytes: bytes as u64,
                metrics: self.metrics.clone(),
            })
        } else {
            None
        };
        let result = self
            .sender
            .send(StreamItem {
                event,
                _lease: lease,
            })
            .await
            .map_err(|e| mpsc::error::SendError(e.0.event));
        self.metrics.0.wait_ns.fetch_add(
            started.elapsed().as_nanos().min(u64::MAX as u128) as u64,
            Ordering::Relaxed,
        );
        result
    }
    pub fn blocking_send(
        &self,
        event: GraphStreamEvent,
    ) -> Result<(), mpsc::error::SendError<GraphStreamEvent>> {
        tokio::runtime::Handle::current().block_on(self.send(event))
    }
}
impl GraphStream {
    pub fn channel() -> (SinkSender, Self) {
        let (sender, receiver) = mpsc::channel(4);
        let metrics = StreamMetrics::default();
        (
            SinkSender {
                sender,
                budget: Arc::new(Semaphore::new(QUEUE_BYTES)),
                metrics: metrics.clone(),
            },
            Self {
                receiver,
                pending: Vec::new().into_iter(),
                lease: None,
                ended: false,
                metrics,
            },
        )
    }
    pub async fn recv(&mut self) -> Option<StreamItem> {
        let started = std::time::Instant::now();
        let event = self.receiver.recv().await;
        self.metrics.0.receive_wait_ns.fetch_add(
            started.elapsed().as_nanos().min(u64::MAX as u128) as u64,
            Ordering::Relaxed,
        );
        event
    }
}
impl Iterator for GraphStream {
    type Item = Result<crate::graph::Row>;
    fn next(&mut self) -> Option<Self::Item> {
        loop {
            if let Some(row) = self.pending.next() {
                return Some(Ok(row));
            }
            self.lease = None;
            if self.ended {
                return None;
            }
            let started = std::time::Instant::now();
            let event = self.receiver.blocking_recv();
            self.metrics.0.receive_wait_ns.fetch_add(
                started.elapsed().as_nanos().min(u64::MAX as u128) as u64,
                Ordering::Relaxed,
            );
            match event {
                Some(StreamItem {
                    event: GraphStreamEvent::Batch(rows),
                    _lease,
                }) => {
                    self.pending = rows.into_iter();
                    self.lease = _lease;
                }
                Some(StreamItem {
                    event: GraphStreamEvent::VerifiedEnd,
                    ..
                }) => {
                    self.ended = true;
                    return None;
                }
                None => {
                    self.ended = true;
                    return Some(Err(anyhow!(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::GraphStreamIncomplete,
                        ""
                    ))));
                }
            }
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn premature_close_never_certifies_completion() {
        let (tx, mut records) = GraphStream::channel();
        drop(tx);
        assert!(
            tokio::task::spawn_blocking(move || records.next().unwrap().is_err())
                .await
                .unwrap()
        );
        let (tx, mut records) = GraphStream::channel();
        tx.send(GraphStreamEvent::VerifiedEnd).await.unwrap();
        drop(tx);
        assert!(
            tokio::task::spawn_blocking(move || records.next().is_none())
                .await
                .unwrap()
        );
    }
    #[tokio::test]
    async fn oversized_batch_is_exclusive_and_closed_receiver_unblocks_sender() {
        let (tx, mut records) = GraphStream::channel();
        let large = || {
            GraphStreamEvent::Batch(vec![crate::graph::Row {
                table: 1,
                world: "x".repeat(QUEUE_BYTES + 1),
                cells: vec![],
            }])
        };
        tx.send(large()).await.unwrap();
        let item = records.recv().await.unwrap();
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(20), tx.send(large()))
                .await
                .is_err()
        );
        drop(item);
        tx.send(large()).await.unwrap();
        drop(records);
        assert!(tx.send(large()).await.is_err());
        assert!(tx.metrics.summary().peak_bytes > QUEUE_BYTES as u64);
    }
}
