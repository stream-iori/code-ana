//! Application orchestration. CLI and Java adapters contain no product policy.
mod analysis_unit;
mod doctor;
mod history;
mod index_execution;
mod index_plan;
mod indexing;
mod input_process;
pub mod inputs;
mod java;
pub mod maven_inputs;
mod output;
pub mod ports;
mod publication;
mod python_inputs;
mod reports;
mod resources;
pub use reports::report_markdown;
mod query_service;
mod rust_inputs;
mod scheduler;
mod task_execution;
mod task_runner;
use ports::TaskRepository;
use ports::{IndexRepository, IndexService};
mod maintenance;
mod timings;
mod worker;
mod worker_service;
use anyhow::{Context, Result, bail, ensure};
use codeana_config::Capture;
use codeana_contracts::{graph_archive::Builder as GraphArchiveBuilder, terminal, v1 as p};
use codeana_model::*;
use codeana_storage::Store;
use indexing::IndexTask;
use serde::{Deserialize, Serialize};

fn resource_error(e: &anyhow::Error) -> bool {
    e.downcast_ref::<DomainError>()
        .is_some_and(|e| e.code == ErrorCode::ComputationLimit)
}

#[cfg(test)]
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, Weak,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio_util::sync::CancellationToken;
use worker::SpooledAnalysis;
#[derive(Clone, Serialize, Deserialize)]
pub struct Endpoint {
    pub address: String,
    pub token: String,
    pub generation: String,
    pub mock: bool,
}
pub fn default_home() -> Result<PathBuf> {
    Ok(PathBuf::from(
        std::env::var_os("HOME").context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::HomeRequired,
            "",
        ))?,
    )
    .join(".code-ana"))
}
pub fn private_home(path: &Path) -> Result<PathBuf> {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    if path.exists() {
        let m = std::fs::symlink_metadata(path)?;
        ensure!(
            !m.file_type().is_symlink() && m.is_dir(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::HomeMustBeDirectory, "")
        );
        ensure!(
            m.uid() == unsafe { libc::geteuid() },
            codeana_model::DomainError::new(codeana_model::ErrorCode::HomeOwnerMismatch, "")
        );
    }
    std::fs::create_dir_all(path)?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))?;
    Ok(path.canonicalize()?)
}
#[derive(Serialize, Deserialize, Clone)]
pub enum Event {
    Progress(String),
    Output(Vec<u8>),
    Terminal(Box<p::Terminal>),
}
impl Event {
    pub fn wire(self, task: String, seq: u64) -> p::TaskEvent {
        let body = match self {
            Self::Progress(phase) => p::task_event::Event::Progress(p::Progress { phase }),
            Self::Output(data) => p::task_event::Event::Output(p::OutputChunk { data }),
            Self::Terminal(t) => p::task_event::Event::Terminal(*t),
        };
        p::TaskEvent {
            task_id: task,
            seq,
            event: Some(body),
        }
    }
}
struct Job {
    analysis: Arc<task_runner::AnalysisTaskState>,
    query_budget: Option<ResourceBudget>,
    _queue_permit: OwnedSemaphorePermit,
    pub control: Arc<task_runner::TaskControl>,
    pub submitting: AtomicBool,
    pub watchers: AtomicUsize,
    pub detached: bool,
    pub disconnected: Mutex<Instant>,
}
pub struct TaskWatch {
    job: Arc<Job>,
}
impl Drop for TaskWatch {
    fn drop(&mut self) {
        *self.job.disconnected.lock().unwrap() = Instant::now();
        self.job.watchers.fetch_sub(1, Ordering::SeqCst);
    }
}
pub struct Engine {
    runtime: Arc<task_runner::TaskService>,
    execution: task_execution::TaskExecution,
    generation: String,
}
impl Engine {
    pub fn new(home: &Path, mock: Option<PathBuf>) -> Result<Arc<Self>> {
        let user = codeana_config::load_user(home)?;
        let workers = user.limits.max_workers.unwrap_or(2) as u64;
        let memory = user
            .limits
            .max_worker_memory_bytes
            .unwrap_or(256 * 1024 * 1024);
        ensure!(
            (16 * 1024 * 1024..=4 * 1024 * 1024 * 1024).contains(&memory),
            codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerMemoryLimitRange, "")
        );
        ensure!(
            memory * workers
                <= user
                    .limits
                    .max_total_worker_memory_bytes
                    .unwrap_or(4 * 1024 * 1024 * 1024),
            codeana_model::DomainError::new(codeana_model::ErrorCode::TotalWorkerMemoryLimit, "")
        );
        let store = Store::open(home)?;
        let generation = new_id("generation");
        let runtime = task_runner::TaskService::new(store.clone(), &user);
        let workers = Arc::new(worker_service::WorkerService::new(
            home.to_path_buf(),
            mock,
            user,
            memory,
        ));
        let history = history::HistoryService::new(store.clone());
        let reports = reports::ReportService::new(store.clone());
        let maintenance = maintenance::MaintenanceService::new(store.clone());
        let doctor = doctor::DoctorService::new(generation.clone(), workers.clone());
        let query = query_service::QueryService::new(
            store.clone(),
            history.clone(),
            reports.clone(),
            doctor,
        );
        let execution = task_execution::TaskExecution {
            index: IndexService::new(ports::storage::IndexAdapter(store)),
            workers,
            history,
            reports,
            maintenance,
            query,
        };
        runtime.recover_unfinished()?;
        Ok(Arc::new(Self {
            runtime,
            execution,
            generation,
        }))
    }
    pub fn generation(&self) -> &str {
        &self.generation
    }
    pub fn is_mock(&self) -> bool {
        self.execution.workers.is_mock()
    }
    pub fn java_available(&self) -> bool {
        self.execution.workers.java_available()
    }
    pub fn is_draining(&self) -> bool {
        self.runtime.is_draining()
    }
    pub fn drain(&self) {
        self.runtime.drain();
    }
    pub fn cancel_all(&self) {
        self.runtime.cancel_all();
    }
    pub fn shutdown_token(&self) -> CancellationToken {
        self.runtime.shutdown_token()
    }
    pub fn task(&self, id: &str) -> Result<ports::TaskSnapshot> {
        self.runtime.task(id)
    }
    pub fn task_events(&self, id: &str, after: u64) -> Result<Vec<(u64, Event)>> {
        self.runtime.task_events(id, after)
    }
    pub fn watch_task(&self, id: &str) -> Option<TaskWatch> {
        self.runtime.watch_task(id)
    }
    pub fn touch(&self) {
        self.runtime.touch();
    }
    pub fn active(&self) -> usize {
        self.runtime.active()
    }
    pub fn submit(self: &Arc<Self>, req: Request) -> Result<String> {
        task_runner::TaskRunner::submit(&self.runtime, self.execution.clone(), req)
    }
    pub fn cancel(&self, id: &str) -> Result<()> {
        self.runtime.cancel(id)
    }
    pub fn maintenance(self: &Arc<Self>) {
        self.runtime.maintenance();
    }
}

#[derive(Serialize)]
struct RemovedSnapshot<'a> {
    removed: &'a str,
}
struct FilesReader(Revision);
struct CancelableReader<'a> {
    inner: &'a dyn GraphReader,
    cancel: &'a CancellationToken,
}
impl CancelableReader<'_> {
    fn checked<T>(
        &self,
        read: impl FnOnce() -> std::result::Result<T, DomainError>,
    ) -> std::result::Result<T, DomainError> {
        if self.cancel.is_cancelled() {
            return Err(ErrorCode::Cancelled.as_str().into());
        }
        let facts = read()?;
        if self.cancel.is_cancelled() {
            return Err(ErrorCode::Cancelled.as_str().into());
        }
        Ok(facts)
    }
}
impl SourceContext for CancelableReader<'_> {
    fn charge(&self, work: u64, bytes: u64) -> std::result::Result<(), DomainError> {
        self.checked(|| self.inner.charge(work, bytes))
    }
    fn resource_budget(&self) -> Option<&ResourceBudget> {
        self.inner.resource_budget()
    }
    fn manifest(&self) -> &Manifest {
        self.inner.manifest()
    }
    fn revision(&self) -> &Revision {
        self.inner.revision()
    }
}
impl GraphAccess for CancelableReader<'_> {
    fn comment_reader(&self) -> Option<&dyn CommentReader> {
        self.inner
            .comment_reader()
            .map(|_| self as &dyn CommentReader)
    }
    fn scan(
        &self,
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<Fact>, DomainError> {
        self.checked(|| self.inner.scan(filter, after, limit))
    }
    fn scan_symbols(
        &self,
        text: &str,
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<Fact>, DomainError> {
        self.checked(|| self.inner.scan_symbols(text, filter, after, limit))
    }
    fn select(&self, filter: &FactFilter) -> std::result::Result<Vec<Fact>, DomainError> {
        self.checked(|| self.inner.select(filter))
    }
    fn search_symbols(&self, text: &str) -> std::result::Result<Vec<Fact>, DomainError> {
        self.checked(|| self.inner.search_symbols(text))
    }
    fn search_symbols_filtered(
        &self,
        text: &str,
        filter: &FactFilter,
    ) -> std::result::Result<Vec<Fact>, DomainError> {
        self.checked(|| self.inner.search_symbols_filtered(text, filter))
    }
}
impl SourceLocator for CancelableReader<'_> {
    fn source_span(&self, entity: &Fact) -> std::result::Result<Option<Span>, DomainError> {
        self.checked(|| self.inner.source_span(entity))
    }
}
impl AnnotationReader for CancelableReader<'_> {
    fn annotation_meta_edges(
        &self,
        names: &[String],
    ) -> std::result::Result<Vec<AnnotationMetaEdge>, DomainError> {
        self.checked(|| self.inner.annotation_meta_edges(names))
    }
}
impl OverviewReader for CancelableReader<'_> {
    fn overview_groups(
        &self,
        filter: &FactFilter,
        section: &str,
        direction: &str,
        relations: &[String],
    ) -> std::result::Result<Vec<OverviewGroup>, DomainError> {
        self.checked(|| {
            self.inner
                .overview_groups(filter, section, direction, relations)
        })
    }
    fn count_by_kind(
        &self,
        filter: &FactFilter,
    ) -> std::result::Result<BTreeMap<String, u64>, DomainError> {
        self.checked(|| self.inner.count_by_kind(filter))
    }
}
impl DependencyReader for CancelableReader<'_> {
    fn reverse_file_dependencies(
        &self,
        targets: &[FileKey],
        source_world: Option<&str>,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<FileDependency>, DomainError> {
        self.checked(|| {
            self.inner
                .reverse_file_dependencies(targets, source_world, after, limit)
        })
    }
    fn reverse_dependencies(
        &self,
        targets: &[String],
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<Fact>, DomainError> {
        self.checked(|| {
            self.inner
                .reverse_dependencies(targets, filter, after, limit)
        })
    }
    fn unresolved_consumers(
        &self,
        targets: &[String],
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<Fact>, DomainError> {
        self.checked(|| {
            self.inner
                .unresolved_consumers(targets, filter, after, limit)
        })
    }
}

impl SourceContext for FilesReader {
    fn manifest(&self) -> &Manifest {
        &self.0.manifest
    }
    fn revision(&self) -> &Revision {
        &self.0
    }
}

/// Canonicalize the existing ancestor so deleted checkouts retain their registered identity.
pub fn project_path(path: &Path) -> Result<PathBuf> {
    let absolute = std::path::absolute(path)?;
    if absolute.exists() {
        return Ok(absolute.canonicalize()?);
    }
    let mut ancestor = absolute.as_path();
    let mut tail = Vec::new();
    while !ancestor.exists() {
        tail.push(
            ancestor
                .file_name()
                .context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::InvalidProjectPath,
                    "",
                ))?
                .to_owned(),
        );
        ancestor = ancestor.parent().context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::InvalidProjectPath,
            "",
        ))?;
    }
    let mut resolved = ancestor.canonicalize()?;
    for part in tail.into_iter().rev() {
        resolved.push(part);
    }
    Ok(resolved)
}

impl CommentReader for CancelableReader<'_> {
    fn unavailable_comment_inputs(
        &self,
    ) -> std::result::Result<Vec<UnavailableCommentInput>, DomainError> {
        self.checked(|| {
            self.inner
                .comment_reader()
                .ok_or_else(|| DomainError::new(ErrorCode::UnsupportedCapability, "comments"))?
                .unavailable_comment_inputs()
        })
    }
    fn scan_comments(
        &self,
        terms: &[String],
        filter: &CommentFilter,
        after: Option<&CommentOrderKey>,
        limit: usize,
    ) -> std::result::Result<BoundedRead<CommentFact>, DomainError> {
        self.checked(|| {
            self.inner
                .comment_reader()
                .ok_or_else(|| DomainError::new(ErrorCode::UnsupportedCapability, "comments"))?
                .scan_comments(terms, filter, after, limit)
        })
    }
    fn comment_links(
        &self,
        ids: &[CommentId],
    ) -> std::result::Result<Vec<CommentLink>, DomainError> {
        self.checked(|| {
            self.inner
                .comment_reader()
                .ok_or_else(|| DomainError::new(ErrorCode::UnsupportedCapability, "comments"))?
                .comment_links(ids)
        })
    }
}

#[cfg(test)]
mod reader_tests {
    use super::*;
    struct IndexedReader;
    impl SourceContext for IndexedReader {
        fn manifest(&self) -> &Manifest {
            panic!("not needed for aggregate transport")
        }
        fn revision(&self) -> &Revision {
            panic!("not needed for aggregate transport")
        }
    }
    impl GraphAccess for IndexedReader {
        fn scan(
            &self,
            filter: &FactFilter,
            after: &str,
            limit: usize,
        ) -> std::result::Result<Vec<Fact>, DomainError> {
            let _ = (filter, after, limit);
            Err(ErrorCode::UnsupportedCapability.as_str().into())
        }

        fn scan_symbols(
            &self,
            _: &str,
            _: &FactFilter,
            after: &str,
            limit: usize,
        ) -> std::result::Result<Vec<Fact>, DomainError> {
            if after != "last" || limit != 512 {
                return Err("SCAN_ARGUMENTS_LOST".into());
            }
            Ok(Vec::new())
        }
        fn select(&self, _: &FactFilter) -> std::result::Result<Vec<Fact>, DomainError> {
            Err("BODY_SCAN_NOT_ALLOWED".into())
        }
        fn search_symbols_filtered(
            &self,
            _: &str,
            _: &FactFilter,
        ) -> std::result::Result<Vec<Fact>, DomainError> {
            Ok(Vec::new())
        }
    }
    impl SourceLocator for IndexedReader {}
    impl AnnotationReader for IndexedReader {}
    impl OverviewReader for IndexedReader {
        fn count_by_kind(
            &self,
            _: &FactFilter,
        ) -> std::result::Result<BTreeMap<String, u64>, DomainError> {
            Ok(BTreeMap::from([("declaration".into(), 100001)]))
        }
    }
    impl DependencyReader for IndexedReader {
        fn reverse_file_dependencies(
            &self,
            targets: &[FileKey],
            source_world: Option<&str>,
            after: &str,
            limit: usize,
        ) -> std::result::Result<Vec<FileDependency>, DomainError> {
            if targets
                != [FileKey {
                    world: "main".into(),
                    path: "Target.java".into(),
                }]
                || source_world != Some("test")
                || after != "last"
                || limit != 512
            {
                return Err("FILE_DEPENDENCY_ARGUMENTS_LOST".into());
            }
            Ok(Vec::new())
        }
        fn reverse_dependencies(
            &self,
            targets: &[String],
            _: &FactFilter,
            after: &str,
            limit: usize,
        ) -> std::result::Result<Vec<Fact>, DomainError> {
            if targets != ["target"] || after != "last" || limit != 512 {
                return Err("DEPENDENCY_ARGUMENTS_LOST".into());
            }
            Ok(Vec::new())
        }
        fn unresolved_consumers(
            &self,
            targets: &[String],
            _: &FactFilter,
            after: &str,
            limit: usize,
        ) -> std::result::Result<Vec<Fact>, DomainError> {
            if targets != ["missing()"] || after != "last" || limit != 512 {
                return Err("DEPENDENCY_ARGUMENTS_LOST".into());
            }
            Ok(Vec::new())
        }
    }
    #[test]
    fn cancellation_wrapper_preserves_sql_aggregate_and_filtered_search() {
        let cancel = CancellationToken::new();
        let reader = CancelableReader {
            inner: &IndexedReader,
            cancel: &cancel,
        };
        let files = [FileKey {
            world: "main".into(),
            path: "Target.java".into(),
        }];
        assert!(
            reader
                .reverse_file_dependencies(&files, Some("test"), "last", 512)
                .unwrap()
                .is_empty()
        );
        assert!(
            reader
                .reverse_dependencies(&["target".into()], &FactFilter::default(), "last", 512)
                .unwrap()
                .is_empty()
        );
        assert!(
            reader
                .unresolved_consumers(&["missing()".into()], &FactFilter::default(), "last", 512)
                .unwrap()
                .is_empty()
        );
        assert!(
            reader
                .scan_symbols("same", &FactFilter::default(), "last", 512)
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            reader.count_by_kind(&FactFilter::default()).unwrap()["declaration"],
            100001
        );
        assert!(
            reader
                .search_symbols_filtered("Api", &FactFilter::default())
                .unwrap()
                .is_empty()
        );
        cancel.cancel();
        assert_eq!(
            reader
                .reverse_file_dependencies(&files, Some("test"), "last", 512)
                .unwrap_err(),
            "CANCELLED"
        );
        assert_eq!(
            reader
                .reverse_dependencies(&["target".into()], &FactFilter::default(), "last", 512)
                .unwrap_err(),
            "CANCELLED"
        );
        assert_eq!(
            reader
                .unresolved_consumers(&["missing()".into()], &FactFilter::default(), "last", 512)
                .unwrap_err(),
            "CANCELLED"
        );
        assert_eq!(
            reader
                .scan_symbols("same", &FactFilter::default(), "last", 512)
                .unwrap_err(),
            "CANCELLED"
        );
        assert_eq!(
            reader.count_by_kind(&FactFilter::default()).unwrap_err(),
            "CANCELLED"
        );
        assert_eq!(
            reader
                .search_symbols_filtered("Api", &FactFilter::default())
                .unwrap_err(),
            "CANCELLED"
        );
    }
}
