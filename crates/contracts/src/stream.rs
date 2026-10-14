//! Bounded provisional records. A closed channel is an error until a verified End arrives.
use anyhow::{Result, anyhow};
use tokio::sync::mpsc;

pub enum GraphStreamEvent {
    Batch(Vec<crate::graph::Row>),
    VerifiedEnd,
}
pub struct GraphStream {
    receiver: mpsc::Receiver<GraphStreamEvent>,
    pending: std::vec::IntoIter<crate::graph::Row>,
    ended: bool,
}
impl GraphStream {
    pub fn channel() -> (mpsc::Sender<GraphStreamEvent>, Self) {
        let (sender, receiver) = mpsc::channel(4);
        (
            sender,
            Self {
                receiver,
                pending: Vec::new().into_iter(),
                ended: false,
            },
        )
    }
}
impl Iterator for GraphStream {
    type Item = Result<crate::graph::Row>;
    fn next(&mut self) -> Option<Self::Item> {
        loop {
            if let Some(row) = self.pending.next() {
                return Some(Ok(row));
            }
            if self.ended {
                return None;
            }
            match self.receiver.blocking_recv() {
                Some(GraphStreamEvent::Batch(rows)) => self.pending = rows.into_iter(),
                Some(GraphStreamEvent::VerifiedEnd) => {
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
    #[test]
    fn premature_close_never_certifies_completion() {
        let (tx, mut records) = GraphStream::channel();
        drop(tx);
        assert!(records.next().unwrap().is_err());
        assert!(records.next().is_none());
        let (tx, mut records) = GraphStream::channel();
        tx.try_send(GraphStreamEvent::VerifiedEnd).unwrap();
        drop(tx);
        assert!(records.next().is_none());
    }
}
