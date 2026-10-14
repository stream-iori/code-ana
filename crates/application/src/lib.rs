//! Application orchestration. CLI and Java adapters contain no product policy.
mod annotations;
mod doctor;
mod history;
mod indexing;
mod java;
pub mod maven_inputs;
mod output;
pub mod ports;
mod reports;
use ports::{PublicationRepository, TaskRepository};
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
pub struct Job {
    _queue_permit: OwnedSemaphorePermit,
    pub cancel: CancellationToken,
    pub watchers: AtomicUsize,
    pub detached: bool,
    pub disconnected: Mutex<Instant>,
}
struct IdleJava {
    key: String,
    revision: String,
    id: String,
    worker: worker::Worker,
}
pub struct Engine {
    pub store: Store,
    tasks: Arc<dyn TaskRepository>,
    publisher: Arc<dyn PublicationRepository>,
    pub mock: Option<PathBuf>,
    pub generation: String,
    pub jobs: Mutex<BTreeMap<TaskId, Arc<Job>>>,
    slots: Arc<Semaphore>,
    queue: Arc<Semaphore>,
    projects: Mutex<BTreeMap<String, Weak<Semaphore>>>,
    analysis_projects: Mutex<BTreeMap<String, Weak<Semaphore>>>,
    pub draining: AtomicBool,
    pub shutdown: CancellationToken,
    pub last_activity: Mutex<Instant>,
    pub idle_seconds: u64,
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
            publisher: Arc::new(s.clone()),
            store: s,
            mock,
            generation: new_id("generation"),
            jobs: Mutex::default(),
            slots: Arc::new(Semaphore::new(user.limits.max_workers.unwrap_or(2))),
            queue: Arc::new(Semaphore::new(8)),
            projects: Mutex::default(),
            analysis_projects: Mutex::default(),
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
                t.publication = "published".into();
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
    pub fn java_available(&self) -> bool {
        java::installed_library().is_ok()
    }
    pub fn touch(&self) {
        *self.last_activity.lock().unwrap() = Instant::now();
    }
    fn gate(&self, map: &Mutex<BTreeMap<String, Weak<Semaphore>>>, key: String) -> Arc<Semaphore> {
        let mut gates = map.lock().unwrap();
        gates.retain(|_, gate| gate.strong_count() > 0);
        if let Some(gate) = gates.get(&key).and_then(Weak::upgrade) {
            return gate;
        }
        let gate = Arc::new(Semaphore::new(1));
        gates.insert(key, Arc::downgrade(&gate));
        gate
    }
    pub fn active(&self) -> usize {
        self.jobs.lock().unwrap().len()
    }
    pub fn submit(self: &Arc<Self>, mut req: Request) -> Result<String> {
        self.touch();
        ensure!(
            !self.draining.load(Ordering::SeqCst),
            codeana_model::DomainError::new(codeana_model::ErrorCode::DaemonDraining, "")
        );
        ensure!(
            !req.request_id.is_empty(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::RequestIdRequired, "")
        );
        let queue_permit = self.queue.clone().try_acquire_owned().map_err(|_| {
            anyhow::anyhow!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::QueueBudget,
                ""
            ))
        })?;
        ensure!(
            req.deadline_seconds <= MAX_TASK_DEADLINE_SECONDS,
            codeana_model::DomainError::new(codeana_model::ErrorCode::DeadlineBudget, "")
        );
        ensure!(
            req.max_output_bytes <= 8 * 1024 * 1024,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::OutputBudget,
                "maximum 8 MiB".to_string()
            )
        );
        codeana_query::preflight(&req)?;
        let input = PathBuf::from(&req.project);
        let root = project_path(&input)?;
        if root.exists() {
            let (project, checkout) = codeana_config::identity(&root);
            self.store.register_project(
                root.to_str().context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::NonUtf8Project,
                    "",
                ))?,
                &project,
                &checkout,
            )?;
        } else {
            self.store
                .project_path(root.to_str().context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::NonUtf8Project,
                    "",
                ))?)?;
        }
        req.project = root
            .to_str()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::NonUtf8Project,
                "",
            ))?
            .into();
        let id = new_id("task");
        let (id, new) = self.tasks.register(&TaskId::from(id), &req)?;
        if !new {
            return Ok(id.into());
        }
        // Freeze project config and source bytes before queueing indexing work.
        let mut capture_timings = if matches!(req.operation.as_str(), "index" | "snapshot") {
            timings::IndexTimings::capture(&id)
        } else {
            None
        };
        let capture = if matches!(req.operation.as_str(), "index" | "snapshot" | "search-text")
            && (req.target.is_empty() || req.target == "WORKTREE")
            && (req.operation != "search-text"
                || (req.revision.is_empty() && req.cursor.is_empty()))
        {
            Some(codeana_config::capture(&root, &req))
        } else {
            None
        };
        if let Some(timings) = &mut capture_timings {
            timings.phase("captured");
        }
        drop(capture_timings);
        let job = Arc::new(Job {
            _queue_permit: queue_permit,
            cancel: CancellationToken::new(),
            watchers: AtomicUsize::new(0),
            detached: req.background,
            disconnected: Mutex::new(Instant::now()),
        });
        self.jobs.lock().unwrap().insert(id.clone(), job.clone());
        let engine = self.clone();
        let task = id.clone();
        tokio::spawn(async move {
            let result = engine
                .run_task(&task, &req, capture, job.clone())
                .await
                .unwrap_or_else(|e| {
                    let message = format!("{e:#}");
                    let code = e
                        .downcast_ref::<DomainError>()
                        .map(|e| e.code.as_str())
                        .unwrap_or("FAILED");
                    TaskResult::failure(
                        code,
                        &message,
                        if code == "CANCELLED" {
                            130
                        } else if matches!(code, "TIMEOUT" | "CAPTURE_TIMEOUT") {
                            124
                        } else {
                            1
                        },
                    )
                });
            let mut result = result;
            // A timeout/disconnect after catalog commit cannot revoke publication.
            if result.publication != "published"
                && let Ok(Some(revision)) = engine.tasks.committed(&task)
            {
                let reason = result.message.clone();
                if let Ok(read) = engine.store.read(&revision) {
                    result = TaskResult::success(Output {
                        revision: revision.into(),
                        producer: read.revision().producer.clone(),
                        evidence: Evidence {
                            enumeration: false,
                            semantic: false,
                            reasons: vec!["post_publication_interruption".into()],
                            limits: vec![],
                        },
                        ..Default::default()
                    });
                    result.publication = "published".into();
                    result.message = reason;
                }
            }
            if let Err(e) = engine.render(&task, &req, &mut result) {
                if result.publication == "published" {
                    result.code = "OUTPUT_FAILURE".into();
                    result.message = e.to_string();
                    result.exit_code = 1;
                } else {
                    result = TaskResult::failure("OUTPUT_FAILURE", e.to_string(), 1);
                }
            }
            if let Err(e) = engine.tasks.finish(
                &task,
                &result,
                &Event::Terminal(Box::new(terminal(&result))),
            ) {
                eprintln!("task persistence failed: {e}");
            }
            engine.jobs.lock().unwrap().remove(&task);
            engine.touch();
        });
        Ok(id.into())
    }
    async fn run_task(
        self: &Arc<Self>,
        id: &str,
        req: &Request,
        capture: Option<Result<Capture>>,
        job: Arc<Job>,
    ) -> Result<TaskResult> {
        self.tasks
            .event(&TaskId::from(id), &Event::Progress("queued".into()))?;
        let project = self.store.project_path(&req.project)?.0;
        let command = TaskCommand::try_from(req)?;
        let analysis = command.requires_analysis();
        let analysis_gate = self.gate(&self.analysis_projects, project.clone());
        let writer = command.requires_writer();
        let lock_key = if matches!(req.operation.as_str(), "index" | "snapshot") {
            capture
                .as_ref()
                .and_then(|r| r.as_ref().ok())
                .map(|c| c.target.clone())
                .unwrap_or_else(|| project.clone())
        } else {
            project
        };
        let gate = self.gate(&self.projects, lock_key);
        let work = async {
            let _analysis = if analysis {
                Some(analysis_gate.acquire().await?)
            } else {
                None
            };
            let _project = if writer {
                Some(gate.acquire().await?)
            } else {
                None
            };
            let _slot = self.slots.acquire().await?;
            ensure!(
                !job.cancel.is_cancelled(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
            );
            self.tasks.running(&TaskId::from(id))?;
            self.tasks
                .event(&TaskId::from(id), &Event::Progress("running".into()))?;
            let capture = capture.transpose()?;
            let mut result = match req.operation.as_str() {
                "index" => {
                    self.index(
                        id,
                        &IndexCommand::from(req),
                        capture.context(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::CaptureRequired,
                            "",
                        ))?,
                        &job.cancel,
                    )
                    .await?
                }
                "snapshot" => self.snapshot(id, req, capture, &job.cancel).await?,
                "search-text" if req.revision.is_empty() && req.cursor.is_empty() => {
                    let c = capture.context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::CaptureRequired,
                        "",
                    ))?;
                    let request = req.clone();
                    let engine = self.clone();
                    let cancellation = job.cancel.clone();
                    tokio::task::spawn_blocking(move || {
                        ensure!(
                            !cancellation.is_cancelled(),
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::Cancelled,
                                ""
                            )
                        );
                        let profile = digest(&(
                            c.effective.capture_digest.clone(),
                            &c.effective.config.search.text.include,
                        ));
                        let reader = FilesReader(Revision {
                            id: key("worktree_text", &[&c.manifest.id(), &profile]),
                            target: c.target,
                            project: c.project.clone(),
                            checkout: c.checkout,
                            manifest: c.manifest,
                            profile,
                            producer: "rust-text-v1".into(),
                            created: now(),
                        });
                        engine.text_page(
                            &request,
                            &reader,
                            &|s| {
                                ensure!(
                                    !cancellation.is_cancelled(),
                                    codeana_model::DomainError::new(
                                        codeana_model::ErrorCode::Cancelled,
                                        ""
                                    )
                                );
                                c.blobs
                                    .get(&s.hash)
                                    .context(codeana_model::DomainError::new(
                                        codeana_model::ErrorCode::CaptureBlobMissing,
                                        "",
                                    ))?
                                    .read(MAX_FRAME)
                            },
                            vec![],
                            &cancellation,
                        )
                    })
                    .await??
                }
                _ => {
                    let request = req.clone();
                    let engine = self.clone();
                    let cancellation = job.cancel.clone();
                    tokio::task::spawn_blocking(move || engine.execute(&request, &cancellation))
                        .await??
                }
            };
            if req.strict
                && (!result.output.evidence.semantic || !result.output.evidence.enumeration)
            {
                result.gate = "rejected".into();
                result.exit_code = 3;
            }
            Ok::<_, anyhow::Error>(result)
        };
        tokio::select! {r=tokio::time::timeout(Duration::from_secs(if req.deadline_seconds==0{120}else{req.deadline_seconds}),work)=>match r {Ok(result)=>result,Err(_)=>{job.cancel.cancel();bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Timeout, ""))}},_=job.cancel.cancelled()=>bail!(codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, ""))}
    }
    fn execute(&self, req: &Request, cancel: &CancellationToken) -> Result<TaskResult> {
        ensure!(
            !cancel.is_cancelled(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
        );
        let root = Path::new(&req.project);
        let (project, checkout) = self.store.project_path(&req.project)?;
        let output = match req.operation.as_str() {
            "doctor" => self.doctor(root, req)?,
            "gc" => Output {
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
            "recover" => {
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
            "snapshot-list" => Output {
                records: self
                    .store
                    .objects::<history::Snapshot>("snapshot", &project)?
                    .iter()
                    .map(Record::display)
                    .collect::<Result<_, _>>()?,
                evidence: Evidence::complete(),
                ..Default::default()
            },
            "snapshot-remove" => {
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
            "diff" => self.diff(req, &project)?,
            "report" => self.report(req, &project)?,
            "annotate" => self.annotate(req, &project, &checkout)?,
            operation if codeana_query::OPERATIONS.contains(&operation) => {
                if !req.cursor.is_empty() {
                    return self.continue_page(req, &project);
                }
                if let Some(sid) = req.revision.strip_prefix("snapshot:") {
                    let snapshot: history::Snapshot =
                        self.store.object("snapshot", sid, &project)?;
                    if snapshot.revision.is_empty() {
                        ensure!(
                            matches!(req.operation.as_str(), "source" | "search-text"),
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
                        if req.operation == "search-text" {
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
                if req.operation == "search-code" {
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
                if req.operation == "search-text" {
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
        self.store.task(id)?;
        if let Some(job) = self.jobs.lock().unwrap().get(id) {
            job.cancel.cancel();
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
                        && j.watchers.load(Ordering::SeqCst) == 0
                        && j.disconnected.lock().unwrap().elapsed() > Duration::from_secs(10)
                    {
                        j.cancel.cancel();
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
