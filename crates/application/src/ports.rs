//! Narrow task and index capabilities. Concrete storage is confined to the adapter module.
use crate::Event;
use anyhow::{Result, ensure};
use codeana_config::CapturedBlob;
use codeana_contracts::graph::Row;
use codeana_model::*;
use std::{collections::BTreeMap, path::Path};
use tokio_util::sync::CancellationToken;

#[derive(Debug)]
pub struct TaskSnapshot {
    pub state: TaskPhase,
    pub result: Option<TaskResult>,
    pub last_seq: u64,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskPhase {
    Queued,
    Running,
    Terminal,
    Expired,
}
impl TaskPhase {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Queued => "queued",
            Self::Running => "running",
            Self::Terminal => "terminal",
            Self::Expired => "expired",
        }
    }
    fn parse(value: &str) -> Result<Self> {
        Ok(match value {
            "queued" => Self::Queued,
            "running" => Self::Running,
            "terminal" => Self::Terminal,
            "expired" => Self::Expired,
            _ => return Err(DomainError::new(ErrorCode::InvalidTaskState, value).into()),
        })
    }
}
pub trait TaskRepository: Send + Sync {
    fn find(&self, request: &Request) -> Result<Option<TaskId>>;
    fn register(&self, id: &TaskId, request: &Request) -> Result<(TaskId, bool)>;
    fn running(&self, id: &TaskId) -> Result<()>;
    fn event(&self, id: &TaskId, event: &Event) -> Result<u64>;
    fn event_batch(&self, id: &TaskId, events: &[Event]) -> Result<Option<u64>>;
    fn finish(&self, id: &TaskId, result: &TaskResult, event: &Event) -> Result<()>;
    fn committed(&self, id: &TaskId) -> Result<Option<RevisionId>>;
    fn unfinished(&self) -> Result<Vec<(TaskId, Option<RevisionId>)>>;
    fn get(&self, id: &TaskId) -> Result<TaskSnapshot>;
    fn events(&self, id: &TaskId, after: u64) -> Result<Vec<(u64, Event)>>;
}
pub trait IndexBaselineReader: SourceContext {
    fn baseline_summary_for(&self, next: &Manifest) -> Result<BaselineSummary>;
    fn rust_baseline_summary(&self) -> Result<BaselineSummary>;
}
pub trait IndexRepository: Clone + Send + Sync + 'static {
    type Candidate: Send + 'static;
    type Baseline: IndexBaselineReader;
    fn home(&self) -> &Path;
    fn head(&self, target: &IndexTarget) -> Result<RevisionId>;
    fn write_baseline(&self, path: &Path, bytes: &[u8]) -> Result<()>;
    fn read(&self, id: &str) -> Result<Self::Baseline>;
    fn summary(candidate: &Self::Candidate) -> &IndexSummary;
    fn stage(
        &self,
        request: StageRequest<'_>,
        records: impl Iterator<Item = Result<Row>>,
    ) -> Result<Self::Candidate>;
    fn persist_and_publish(
        &self,
        candidate: Self::Candidate,
        task: &TaskId,
        blobs: BTreeMap<String, CapturedBlob>,
        cancel: &CancellationToken,
        fence: &PublicationFence,
    ) -> Result<Revision>;
}
pub struct StageRequest<'a> {
    pub revision: Revision,
    pub header: &'a AnalysisHeader,
    pub expected: &'a str,
    pub cancellation: &'a dyn Fn() -> Result<()>,
    pub check_directory: &'a dyn Fn(&Path) -> Result<()>,
}
#[derive(Clone)]
pub(super) struct IndexService<R: IndexRepository> {
    repository: R,
}
impl<R: IndexRepository> IndexService<R> {
    pub fn new(repository: R) -> Self {
        Self { repository }
    }
    pub fn home(&self) -> &Path {
        self.repository.home()
    }
    pub fn head(&self, target: &IndexTarget) -> Result<RevisionId> {
        self.repository.head(target)
    }
    pub fn write_baseline(&self, path: &Path, bytes: &[u8]) -> Result<()> {
        self.repository.write_baseline(path, bytes)
    }
    pub fn read(&self, id: &str) -> Result<R::Baseline> {
        self.repository.read(id)
    }
    pub fn summary<'a>(&self, candidate: &'a R::Candidate) -> &'a IndexSummary {
        R::summary(candidate)
    }
    pub fn stage_graph_records(
        &self,
        revision: Revision,
        header: &AnalysisHeader,
        records: impl Iterator<Item = Result<Row>>,
        expected: &str,
        cancellation: impl Fn() -> Result<()>,
        check_directory: impl Fn(&Path) -> Result<()>,
    ) -> Result<R::Candidate> {
        self.repository.stage(
            StageRequest {
                revision,
                header,
                expected,
                cancellation: &cancellation,
                check_directory: &check_directory,
            },
            records,
        )
    }
    pub async fn publish(
        &self,
        candidate: R::Candidate,
        task: TaskId,
        blobs: BTreeMap<String, CapturedBlob>,
        cancel: CancellationToken,
        fence: std::sync::Arc<PublicationFence>,
    ) -> Result<Revision> {
        crate::publication::PublicationCoordinator::publish(
            self.repository.clone(),
            candidate,
            task,
            blobs,
            cancel,
            fence,
        )
        .await
    }
}

// Only composition and this adapter know the physical candidate and Store layout.
pub(super) mod storage {
    use super::*;
    use codeana_storage::{ReadContext, StagedCandidate, Store};
    #[derive(Clone)]
    pub struct IndexAdapter(pub Store);
    impl IndexRepository for IndexAdapter {
        type Candidate = StagedCandidate;
        type Baseline = ReadContext;
        fn home(&self) -> &Path {
            &self.0.home
        }
        fn write_baseline(&self, path: &Path, bytes: &[u8]) -> Result<()> {
            codeana_storage::durable_write(path, bytes)
        }
        fn head(&self, target: &IndexTarget) -> Result<RevisionId> {
            Ok(self.0.head(target)?.into())
        }
        fn read(&self, id: &str) -> Result<ReadContext> {
            self.0.read(id)
        }
        fn summary(candidate: &StagedCandidate) -> &IndexSummary {
            &candidate.summary
        }
        fn stage(
            &self,
            request: StageRequest<'_>,
            records: impl Iterator<Item = Result<Row>>,
        ) -> Result<StagedCandidate> {
            self.0.stage_graph_records(
                request.revision,
                request.header,
                records,
                request.expected,
                request.cancellation,
                request.check_directory,
            )
        }
        fn persist_and_publish(
            &self,
            candidate: StagedCandidate,
            task: &TaskId,
            blobs: BTreeMap<String, CapturedBlob>,
            cancel: &CancellationToken,
            fence: &PublicationFence,
        ) -> Result<Revision> {
            ensure!(
                !cancel.is_cancelled(),
                DomainError::new(ErrorCode::Cancelled, "")
            );
            codeana_storage::test_fault(&self.0.home, "before_publish")?;
            let _pins = self.0.pin_blobs(blobs.keys());
            self.0.put_blob_files(&blobs)?;
            ensure!(
                !cancel.is_cancelled(),
                DomainError::new(ErrorCode::Cancelled, "")
            );
            self.0.publish_staged_controlled(candidate, task, fence)
        }
    }
    impl IndexBaselineReader for ReadContext {
        fn baseline_summary_for(&self, next: &Manifest) -> Result<BaselineSummary> {
            ReadContext::baseline_summary_for(self, next)
        }
        fn rust_baseline_summary(&self) -> Result<BaselineSummary> {
            ReadContext::rust_baseline_summary(self)
        }
    }
    impl TaskRepository for Store {
        fn find(&self, request: &Request) -> Result<Option<TaskId>> {
            Ok(self.find_task(request)?.map(Into::into))
        }
        fn get(&self, id: &TaskId) -> Result<TaskSnapshot> {
            let (state, result, last_seq) = Store::task(self, id)?;
            Ok(TaskSnapshot {
                state: TaskPhase::parse(&state)?,
                result,
                last_seq,
            })
        }
        fn events(&self, id: &TaskId, after: u64) -> Result<Vec<(u64, Event)>> {
            Store::events(self, id, after)
        }
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
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };

    struct Candidate {
        summary: IndexSummary,
        drops: Arc<AtomicUsize>,
    }
    impl Drop for Candidate {
        fn drop(&mut self) {
            self.drops.fetch_add(1, Ordering::SeqCst);
        }
    }
    struct Baseline {
        revision: Revision,
    }
    impl SourceContext for Baseline {
        fn manifest(&self) -> &Manifest {
            &self.revision.manifest
        }
        fn revision(&self) -> &Revision {
            &self.revision
        }
    }
    impl IndexBaselineReader for Baseline {
        fn baseline_summary_for(&self, _: &Manifest) -> Result<BaselineSummary> {
            Ok(BaselineSummary::default())
        }
        fn rust_baseline_summary(&self) -> Result<BaselineSummary> {
            Ok(BaselineSummary::default())
        }
    }
    #[derive(Clone)]
    struct Repository {
        publications: Arc<AtomicUsize>,
        conflict: bool,
    }
    impl IndexRepository for Repository {
        type Candidate = Candidate;
        type Baseline = Baseline;
        fn write_baseline(&self, path: &Path, bytes: &[u8]) -> Result<()> {
            Ok(std::fs::write(path, bytes)?)
        }
        fn home(&self) -> &Path {
            Path::new(".")
        }
        fn head(&self, _: &IndexTarget) -> Result<RevisionId> {
            Ok("old".into())
        }
        fn read(&self, _: &str) -> Result<Baseline> {
            Err(DomainError::new(ErrorCode::TaskNotFound, "fixture has no baseline").into())
        }
        fn summary(candidate: &Candidate) -> &IndexSummary {
            &candidate.summary
        }
        fn stage(
            &self,
            _: StageRequest<'_>,
            _: impl Iterator<Item = Result<Row>>,
        ) -> Result<Candidate> {
            Err(DomainError::new(ErrorCode::InvalidMode, "fixture has no rows").into())
        }
        fn persist_and_publish(
            &self,
            _candidate: Candidate,
            _: &TaskId,
            _: BTreeMap<String, CapturedBlob>,
            _: &CancellationToken,
            _: &PublicationFence,
        ) -> Result<Revision> {
            ensure!(
                !self.conflict,
                DomainError::new(ErrorCode::HeadConflict, "")
            );
            self.publications.fetch_add(1, Ordering::SeqCst);
            Ok(Revision {
                id: "new".into(),
                target: "t".into(),
                project: "p".into(),
                checkout: "c".into(),
                manifest: Manifest::default(),
                profile: "profile".into(),
                producer: "producer".into(),
                created: 0,
            })
        }
    }
    #[tokio::test]
    async fn injected_candidates_drop_on_cancel_and_conflict_and_publish_once() {
        for (cancelled, conflict) in [(true, false), (false, true), (false, false)] {
            let publications = Arc::new(AtomicUsize::new(0));
            let drops = Arc::new(AtomicUsize::new(0));
            let service = IndexService::new(Repository {
                publications: publications.clone(),
                conflict,
            });
            let candidate = Candidate {
                summary: IndexSummary::default(),
                drops: drops.clone(),
            };
            let cancel = CancellationToken::new();
            if cancelled {
                cancel.cancel();
            }
            let result = service
                .publish(
                    candidate,
                    "task".into(),
                    BTreeMap::new(),
                    cancel,
                    std::sync::Arc::new(PublicationFence::default()),
                )
                .await;
            assert_eq!(result.is_ok(), !cancelled && !conflict);
            assert_eq!(
                publications.load(Ordering::SeqCst),
                usize::from(!cancelled && !conflict)
            );
            assert_eq!(drops.load(Ordering::SeqCst), 1);
        }
    }
}
