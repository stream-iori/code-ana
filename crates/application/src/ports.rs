//! Application ports expose task and publication behavior, never SQL or connections.
use crate::Event;
use anyhow::Result;
use codeana_model::{IndexTarget, Request, Revision, RevisionId, TaskId, TaskResult};
use codeana_storage::{StagedCandidate, Store};
pub trait TaskRepository: Send + Sync {
    fn register(&self, id: &TaskId, request: &Request) -> Result<(TaskId, bool)>;
    fn running(&self, id: &TaskId) -> Result<()>;
    fn event(&self, id: &TaskId, event: &Event) -> Result<u64>;
    fn event_batch(&self, id: &TaskId, events: &[Event]) -> Result<Option<u64>>;
    fn finish(&self, id: &TaskId, result: &TaskResult, event: &Event) -> Result<()>;
    fn committed(&self, id: &TaskId) -> Result<Option<RevisionId>>;
    fn unfinished(&self) -> Result<Vec<(TaskId, Option<RevisionId>)>>;
}
pub trait PublicationRepository: Send + Sync {
    fn head(&self, target: &IndexTarget) -> Result<RevisionId>;
    fn publish(&self, candidate: StagedCandidate, task: &TaskId) -> Result<Revision>;
}
impl TaskRepository for Store {
    fn register(&self, id: &TaskId, request: &Request) -> Result<(TaskId, bool)> {
        let (id, new) = self.register_task(id, request)?;
        Ok((id.into(), new))
    }
    fn running(&self, id: &TaskId) -> Result<()> {
        Store::running(self, id)
    }
    fn event(&self, id: &TaskId, event: &Event) -> Result<u64> {
        Store::event(self, id, event)
    }
    fn event_batch(&self, id: &TaskId, events: &[Event]) -> Result<Option<u64>> {
        Store::event_batch(self, id, events.iter())
    }
    fn finish(&self, id: &TaskId, result: &TaskResult, event: &Event) -> Result<()> {
        Store::finish(self, id, result, event)
    }
    fn committed(&self, id: &TaskId) -> Result<Option<RevisionId>> {
        Ok(Store::committed(self, id)?.map(Into::into))
    }
    fn unfinished(&self) -> Result<Vec<(TaskId, Option<RevisionId>)>> {
        Ok(Store::unfinished(self)?
            .into_iter()
            .map(|(id, rev)| (id.into(), rev.map(Into::into)))
            .collect())
    }
}
impl PublicationRepository for Store {
    fn head(&self, target: &IndexTarget) -> Result<RevisionId> {
        Ok(Store::head(self, target)?.into())
    }
    fn publish(&self, candidate: StagedCandidate, task: &TaskId) -> Result<Revision> {
        Store::publish_staged(self, candidate, task)
    }
}
