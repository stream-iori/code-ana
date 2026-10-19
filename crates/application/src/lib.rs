//! Application orchestration. CLI and Java adapters contain no product policy.
mod annotations;
mod doctor;
mod history;
mod indexing;
mod input_process;
pub mod inputs;
mod java;
pub mod maven_inputs;
mod output;
pub mod ports;
mod prepared_indexing;
mod publication;
mod python_indexing;
mod reports;
pub use reports::report_markdown;
mod rust_indexing;
mod scheduler;
mod task_runner;
use ports::TaskRepository;
use ports::{IndexRepository, IndexService};
mod timings;
mod worker;
use anyhow::{Context, Result, bail, ensure};
use codeana_config::Capture;
use codeana_contracts::{graph_archive::Builder as GraphArchiveBuilder, terminal, v1 as p};
use codeana_model::*;
use codeana_storage::{AnnotationCommand, Store};
use serde::{Deserialize, Serialize};
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
    _queue_permit: OwnedSemaphorePermit,
    pub control: task_runner::TaskControl,
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
struct IdleJava {
    key: String,
    revision: String,
    id: String,
    worker: worker::Worker,
}
pub struct Engine {
    store: Store,
    tasks: Arc<dyn TaskRepository>,
    index: IndexService<ports::storage::IndexAdapter>,
    mock: Option<PathBuf>,
    generation: String,
    jobs: Mutex<BTreeMap<TaskId, Arc<Job>>>,
    submissions: Mutex<()>,
    scheduler: scheduler::Scheduler,
    draining: AtomicBool,
    shutdown: CancellationToken,
    last_activity: Mutex<Instant>,
    idle_seconds: u64,
    worker_memory: u64,
    user: codeana_config::UserConfig,
    idle_java: Arc<Mutex<Option<IdleJava>>>,
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
        let s = Store::open(home)?;
        let engine = Arc::new(Self {
            tasks: Arc::new(s.clone()),
            index: IndexService::new(ports::storage::IndexAdapter(s.clone())),
            store: s,
            mock,
            generation: new_id("generation"),
            jobs: Mutex::default(),
            submissions: Mutex::default(),
            scheduler: scheduler::Scheduler::new(
                user.limits.max_workers.unwrap_or(2),
                user.limits.max_query_tasks.unwrap_or(2),
            ),
            draining: AtomicBool::new(false),
            shutdown: CancellationToken::new(),
            last_activity: Mutex::new(Instant::now()),
            idle_seconds: user.daemon.idle_timeout_seconds.unwrap_or(600),
            worker_memory: user
                .limits
                .max_worker_memory_bytes
                .unwrap_or(256 * 1024 * 1024),
            idle_java: Arc::new(Mutex::new(None)),
            user,
        });
        for (id, published) in engine.tasks.unfinished()? {
            let result = if let Some(rev) = published {
                let r = engine.store.read(&rev)?;
                let mut t = TaskResult::success(Output {
                    revision: rev.into(),
                    producer: r.revision().producer.clone(),
                    evidence: Evidence::complete(),
                    ..Default::default()
                });
                t.publication = codeana_model::PublicationState::Published;
                t.message = "Recovered durable publication; output was not delivered".into();
                t
            } else {
                TaskResult::failure("DAEMON_RESTARTED", "Task interrupted before publication", 1)
            };
            engine
                .tasks
                .finish(&id, &result, &Event::Terminal(Box::new(terminal(&result))))?;
        }
        engine.store.recover(true)?;
        Ok(engine)
    }
    pub fn generation(&self) -> &str {
        &self.generation
    }
    pub fn is_mock(&self) -> bool {
        self.mock.is_some()
    }
    pub fn is_draining(&self) -> bool {
        self.draining.load(Ordering::SeqCst)
    }
    pub fn drain(&self) {
        self.draining.store(true, Ordering::SeqCst);
    }
    pub fn cancel_all(&self) {
        self.drain();
        for job in self.jobs.lock().unwrap().values() {
            job.control.cancel();
        }
    }
    pub fn shutdown_token(&self) -> CancellationToken {
        self.shutdown.clone()
    }
    pub fn task(&self, id: &str) -> Result<ports::TaskSnapshot> {
        self.tasks.get(&TaskId::from(id))
    }
    pub fn task_events(&self, id: &str, after: u64) -> Result<Vec<(u64, Event)>> {
        self.tasks.events(&TaskId::from(id), after)
    }
    pub fn watch_task(&self, id: &str) -> Option<TaskWatch> {
        let job = self.jobs.lock().unwrap().get(id).cloned()?;
        job.watchers.fetch_add(1, Ordering::SeqCst);
        Some(TaskWatch { job })
    }
    pub fn java_available(&self) -> bool {
        java::installed_library().is_ok()
    }
    pub fn touch(&self) {
        *self.last_activity.lock().unwrap() = Instant::now();
    }
    pub fn active(&self) -> usize {
        self.jobs.lock().unwrap().len()
    }
    pub fn submit(self: &Arc<Self>, req: Request) -> Result<String> {
        task_runner::TaskRunner::submit(self, req)
    }
    fn publication_fence(&self, id: &str) -> Result<Arc<PublicationFence>> {
        self.jobs
            .lock()
            .unwrap()
            .get(id)
            .map(|job| job.control.publication.clone())
            .context(DomainError::new(
                ErrorCode::TaskNotFound,
                "publication owner",
            ))
    }
    fn execute(&self, req: &Request, cancel: &CancellationToken) -> Result<TaskResult> {
        ensure!(
            !cancel.is_cancelled(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
        );
        let root = Path::new(&req.project);
        let (project, checkout) = self.store.project_path(&req.project)?;
        let output = match req.operation {
            Operation::Doctor => self.doctor(root, req)?,
            Operation::Gc => Output {
                records: vec![{
                    let policy = codeana_config::resolve(root, req)?.config.versions.gc;
                    Record::display(&self.store.gc_policy(
                        req.execute,
                        Some(&project),
                        policy.keep.unwrap_or(20) as usize,
                        policy.max_age_days.unwrap_or(30) as u64,
                        policy.max_bytes.unwrap_or(0),
                    )?)?
                }],
                evidence: Evidence::complete(),
                ..Default::default()
            },
            Operation::Recover => {
                ensure!(
                    self.active() <= 1,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::RecoveryRequiresDrain,
                        ""
                    )
                );
                Output {
                    records: vec![Record::display(&self.store.recover(req.execute)?)?],
                    evidence: Evidence::complete(),
                    ..Default::default()
                }
            }
            Operation::SnapshotList => Output {
                records: self
                    .store
                    .objects::<history::Snapshot>("snapshot", &project)?
                    .iter()
                    .map(Record::display)
                    .collect::<Result<_, _>>()?,
                evidence: Evidence::complete(),
                ..Default::default()
            },
            Operation::SnapshotRemove => {
                self.store
                    .delete_object("snapshot", &req.selector, &project)?;
                Output {
                    records: vec![Record::display(&RemovedSnapshot {
                        removed: &req.selector,
                    })?],
                    evidence: Evidence::complete(),
                    ..Default::default()
                }
            }
            Operation::Diff => self.diff(req, &project)?,
            Operation::Report => self.report(req, &project)?,
            Operation::Annotate => self.annotate(req, &project, &checkout)?,
            operation if codeana_query::OPERATIONS.contains(&operation.as_str()) => {
                if !req.cursor.is_empty() {
                    return self.continue_page(req, &project);
                }
                if let Some(sid) = req.revision.strip_prefix("snapshot:") {
                    let snapshot: history::Snapshot =
                        self.store.object("snapshot", sid, &project)?;
                    if snapshot.revision.is_empty() {
                        ensure!(
                            matches!(req.operation, Operation::Source | Operation::SearchText),
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::AnalysisSnapshotRequired,
                                ""
                            )
                        );
                        let reader = FilesReader(Revision {
                            id: format!("snapshot:{sid}"),
                            target: String::new(),
                            project: project.clone(),
                            checkout: checkout.clone(),
                            manifest: snapshot.manifest,
                            profile: snapshot.profile,
                            producer: "files-capture-v1".into(),
                            created: snapshot.created,
                        });
                        if req.operation == codeana_model::Operation::SearchText {
                            return self.text_page(
                                req,
                                &reader,
                                &|s| {
                                    ensure!(
                                        !cancel.is_cancelled(),
                                        codeana_model::DomainError::new(
                                            codeana_model::ErrorCode::Cancelled,
                                            ""
                                        )
                                    );
                                    self.store.blob(&s.hash)
                                },
                                vec![],
                                cancel,
                            );
                        }
                        let out = codeana_query::run_files(&reader, req, &|s| {
                            ensure!(
                                !cancel.is_cancelled(),
                                codeana_model::DomainError::new(
                                    codeana_model::ErrorCode::Cancelled,
                                    ""
                                )
                            );
                            self.store.blob(&s.hash)
                        })?;
                        ensure!(
                            !cancel.is_cancelled(),
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::Cancelled,
                                ""
                            )
                        );
                        return self.page(req, &project, out, vec![]);
                    }
                }
                let id = if req.revision.is_empty() {
                    self.store.latest(&project, &checkout)?
                } else if let Some(id) = req.revision.strip_prefix("snapshot:") {
                    let s: history::Snapshot = self.store.object("snapshot", id, &project)?;
                    ensure!(
                        !s.revision.is_empty(),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::AnalysisSnapshotRequired,
                            ""
                        )
                    );
                    s.revision
                } else {
                    req.revision.clone()
                };
                let reader = self.store.read(&id)?;
                ensure!(
                    reader.revision().project == project,
                    codeana_model::DomainError::new(codeana_model::ErrorCode::ProjectMismatch, "")
                );
                let guarded = CancelableReader {
                    inner: &reader,
                    cancel,
                };
                if req.operation == codeana_model::Operation::SearchCode {
                    return self.search_page(req, &project, &guarded, vec![id], cancel);
                }
                let source = |s: &SourceUnit| -> Result<Vec<u8>> {
                    ensure!(
                        !cancel.is_cancelled(),
                        codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
                    );
                    if req.revision.is_empty() {
                        let p = root.join(&s.path);
                        let real = p.canonicalize()?;
                        ensure!(
                            real.starts_with(root),
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::SourceEscape,
                                ""
                            )
                        );
                        let b = codeana_config::bounded_read(&real, MAX_FRAME)?;
                        ensure!(
                            hash(&b) == s.hash,
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::SourceHashMismatch,
                                "re-index or select --revision".to_string()
                            )
                        );
                        Ok(b)
                    } else {
                        self.store.blob(&s.hash)
                    }
                };
                if req.operation == codeana_model::Operation::SearchText {
                    return self.text_page(req, &guarded, &source, vec![id], cancel);
                }
                let out = codeana_query::run(&guarded, req, &source)?;
                ensure!(
                    !cancel.is_cancelled(),
                    codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
                );
                return self.page(req, &project, out, vec![id]);
            }
            _ => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::UnsupportedOperation,
                format!("{}", req.operation)
            )),
        };
        Ok(TaskResult::success(output))
    }
    fn render(&self, id: &str, req: &Request, result: &mut TaskResult) -> Result<()> {
        let bytes = output::render(req, result)?;
        self.tasks.event_batch(
            &TaskId::from(id),
            &bytes
                .chunks(16384)
                .map(|chunk| Event::Output(chunk.to_vec()))
                .collect::<Vec<_>>(),
        )?;
        Ok(())
    }
    pub fn cancel(&self, id: &str) -> Result<()> {
        self.task(id)?;
        if let Some(job) = self.jobs.lock().unwrap().get(id) {
            job.control.cancel();
        }
        Ok(())
    }
    pub fn maintenance(self: &Arc<Self>) {
        let e = self.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(1)).await;
                if e.shutdown.is_cancelled() {
                    break;
                }
                for j in e.jobs.lock().unwrap().values() {
                    if !j.detached
                        && !j.submitting.load(Ordering::SeqCst)
                        && j.watchers.load(Ordering::SeqCst) == 0
                        && j.disconnected.lock().unwrap().elapsed() > Duration::from_secs(10)
                    {
                        j.control.cancel();
                    }
                }
                if e.active() == 0
                    && ((e.draining.load(Ordering::SeqCst))
                        || (e.idle_seconds > 0
                            && e.last_activity.lock().unwrap().elapsed()
                                > Duration::from_secs(e.idle_seconds)))
                {
                    e.shutdown.cancel();
                }
            }
        });
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
    fn manifest(&self) -> &Manifest {
        self.inner.manifest()
    }
    fn revision(&self) -> &Revision {
        self.inner.revision()
    }
}
impl GraphAccess for CancelableReader<'_> {
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
