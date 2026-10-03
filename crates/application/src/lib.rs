//! Application orchestration. CLI and Java adapters contain no product policy.
mod history;
mod java;
pub mod maven_inputs;
mod output;
mod worker;
use anyhow::{Context, Result, bail, ensure};
use codeana_config::Capture;
use codeana_contracts::{archive::FactArchive, terminal, v1 as p};
use codeana_model::*;
use codeana_storage::{AnnotationCommand, Store};
use serde::{Deserialize, Serialize};
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
#[derive(Clone, Serialize, Deserialize)]
pub struct Endpoint {
    pub address: String,
    pub token: String,
    pub generation: String,
    pub mock: bool,
}
pub fn default_home() -> Result<PathBuf> {
    Ok(PathBuf::from(std::env::var_os("HOME").context("HOME_REQUIRED")?).join(".code-ana"))
}
pub fn private_home(path: &Path) -> Result<PathBuf> {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    if path.exists() {
        let m = std::fs::symlink_metadata(path)?;
        ensure!(
            !m.file_type().is_symlink() && m.is_dir(),
            "HOME_MUST_BE_DIRECTORY"
        );
        ensure!(m.uid() == unsafe { libc::geteuid() }, "HOME_OWNER_MISMATCH");
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
    pub mock: Option<PathBuf>,
    pub generation: String,
    pub jobs: Mutex<BTreeMap<String, Arc<Job>>>,
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
            (16 * 1024 * 1024..=2 * 1024 * 1024 * 1024).contains(&memory),
            "WORKER_MEMORY_LIMIT_RANGE"
        );
        ensure!(
            memory * workers
                <= user
                    .limits
                    .max_total_worker_memory_bytes
                    .unwrap_or(4 * 1024 * 1024 * 1024),
            "TOTAL_WORKER_MEMORY_LIMIT"
        );
        let s = Store::open(home)?;
        let engine = Arc::new(Self {
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
        for (id, published) in engine.store.unfinished()? {
            let result = if let Some(rev) = published {
                let r = engine.store.read(&rev)?;
                let mut t = TaskResult::success(Output {
                    revision: rev,
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
                .store
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
        ensure!(!self.draining.load(Ordering::SeqCst), "DAEMON_DRAINING");
        ensure!(!req.request_id.is_empty(), "REQUEST_ID_REQUIRED");
        let queue_permit = self
            .queue
            .clone()
            .try_acquire_owned()
            .map_err(|_| anyhow::anyhow!("QUEUE_BUDGET"))?;
        ensure!(
            req.deadline_seconds <= MAX_TASK_DEADLINE_SECONDS,
            "DEADLINE_BUDGET"
        );
        ensure!(
            req.max_output_bytes <= 8 * 1024 * 1024,
            "OUTPUT_BUDGET: maximum 8 MiB"
        );
        codeana_query::preflight(&req)?;
        let input = PathBuf::from(&req.project);
        let root = project_path(&input)?;
        if root.exists() {
            let (project, checkout) = codeana_config::identity(&root);
            self.store.register_project(
                root.to_str().context("NON_UTF8_PROJECT")?,
                &project,
                &checkout,
            )?;
        } else {
            self.store
                .project_path(root.to_str().context("NON_UTF8_PROJECT")?)?;
        }
        req.project = root.to_str().context("NON_UTF8_PROJECT")?.into();
        let id = new_id("task");
        let (id, new) = self.store.register_task(&id, &req)?;
        if !new {
            return Ok(id);
        }
        // Freeze project config and source bytes before queueing indexing work.
        let capture = if matches!(req.operation.as_str(), "index" | "snapshot" | "search-text")
            && (req.target.is_empty() || req.target == "WORKTREE")
        {
            Some(codeana_config::capture(&root, &req))
        } else {
            None
        };
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
                    let code = message.split([':', ' ']).next().unwrap_or("FAILED");
                    TaskResult::failure(
                        code,
                        &message,
                        if code == "CANCELLED" {
                            130
                        } else if code == "TIMEOUT" {
                            124
                        } else {
                            1
                        },
                    )
                });
            let mut result = result;
            // A timeout/disconnect after catalog commit cannot revoke publication.
            if result.publication != "published"
                && let Ok(Some(revision)) = engine.store.committed(&task)
            {
                let reason = result.message.clone();
                if let Ok(read) = engine.store.read(&revision) {
                    result = TaskResult::success(Output {
                        revision,
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
            if let Err(e) = engine.store.finish(
                &task,
                &result,
                &Event::Terminal(Box::new(terminal(&result))),
            ) {
                eprintln!("task persistence failed: {e}");
            }
            engine.jobs.lock().unwrap().remove(&task);
            engine.touch();
        });
        Ok(id)
    }
    async fn run_task(
        self: &Arc<Self>,
        id: &str,
        req: &Request,
        capture: Option<Result<Capture>>,
        job: Arc<Job>,
    ) -> Result<TaskResult> {
        self.store.event(id, &Event::Progress("queued".into()))?;
        let project = self.store.project_path(&req.project)?.0;
        let analysis =
            req.operation == "index" || (req.operation == "snapshot" && req.name == "analysis");
        let analysis_gate = self.gate(&self.analysis_projects, project.clone());
        let writer = matches!(
            req.operation.as_str(),
            "index" | "snapshot" | "annotate" | "snapshot-remove" | "recover"
        ) || (req.operation == "gc" && req.execute);
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
            ensure!(!job.cancel.is_cancelled(), "CANCELLED");
            self.store.running(id)?;
            self.store.event(id, &Event::Progress("running".into()))?;
            let capture = capture.transpose()?;
            let mut result = match req.operation.as_str() {
                "index" => {
                    self.index(id, req, capture.context("CAPTURE_REQUIRED")?, &job.cancel)
                        .await?
                }
                "snapshot" => self.snapshot(id, req, capture, &job.cancel).await?,
                "search-text" if req.revision.is_empty() => {
                    let c = capture.context("CAPTURE_REQUIRED")?;
                    let request = req.clone();
                    let engine = self.clone();
                    let cancellation = job.cancel.clone();
                    tokio::task::spawn_blocking(move || {
                        ensure!(!cancellation.is_cancelled(), "CANCELLED");
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
                        let output = codeana_query::run(&reader, &request, &|s| {
                            ensure!(!cancellation.is_cancelled(), "CANCELLED");
                            c.blobs
                                .get(&s.hash)
                                .context("CAPTURE_BLOB_MISSING")?
                                .read(MAX_FRAME)
                        })?;
                        ensure!(!cancellation.is_cancelled(), "CANCELLED");
                        engine.page(&request, &c.project, output, vec![])
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
        tokio::select! {r=tokio::time::timeout(Duration::from_secs(if req.deadline_seconds==0{120}else{req.deadline_seconds}),work)=>match r {Ok(result)=>result,Err(_)=>{job.cancel.cancel();bail!("TIMEOUT")}},_=job.cancel.cancelled()=>bail!("CANCELLED")}
    }
    async fn index(
        &self,
        id: &str,
        req: &Request,
        mut c: Capture,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let started = Instant::now();
        codeana_config::require_java(&c)?;
        let prepared = if self.mock.is_none() {
            let captured = c.clone();
            let user = self.user.clone();
            let home = self.store.home.clone();
            Some(
                tokio::task::spawn_blocking(move || java::prepare(&captured, &user, &home))
                    .await??,
            )
        } else {
            None
        };
        let expected = self.store.head(&c.target)?;
        if !req.expected_revision.is_empty() {
            ensure!(expected == req.expected_revision, "HEAD_CONFLICT");
        }
        let memory = c
            .effective
            .config
            .resources
            .index
            .worker_memory_bytes
            .unwrap_or(self.worker_memory);
        ensure!(
            memory > 0 && memory <= self.worker_memory,
            "PROJECT_WORKER_MEMORY_LIMIT"
        );
        let pool_key = prepared.as_ref().map(|p| {
            digest(&(
                &c.target,
                &c.effective.semantic_digest,
                &p.library,
                &p.config.toolchain_digest,
                &p.config.semantic_identity,
                memory,
            ))
        });
        let mut reused = None;
        if self.mock.is_none() && self.user.daemon.java_warm_workers.unwrap_or(true) {
            let idle = self.idle_java.lock().unwrap().take();
            if let (Some(mut idle), Some(prepared), Some(key)) =
                (idle, prepared.as_ref(), pool_key.as_ref())
                && idle.key == *key
                && idle.revision == expected
                && idle.worker.alive().unwrap_or(false)
                && idle
                    .worker
                    .rediscover(prepared, &c.manifest, cancel)
                    .await
                    .is_ok()
            {
                reused = Some(idle.worker);
            }
        }
        let mut worker = if let Some(worker) = reused {
            worker
        } else {
            worker::Worker::start(
                self.mock.as_deref(),
                prepared.as_ref(),
                &c.root,
                &c.manifest,
                cancel,
                memory,
            )
            .await?
        };
        ensure!(
            worker.context.language == "java",
            "WORKER_LANGUAGE_MISMATCH"
        );
        for module in &c.effective.config.modules {
            if module.language == "java" {
                if let Some(version) = &module.analysis.source_version {
                    ensure!(
                        version == "auto" || version == &worker.context.source_version,
                        "SOURCE_VERSION_UNSUPPORTED"
                    );
                }
                if self.mock.is_some()
                    && let Some(toolchain) = &module.analysis.toolchain
                {
                    ensure!(
                        toolchain == "auto"
                            || toolchain == &format!("mock-jdk{}", worker.context.source_version),
                        "MOCK_TOOLCHAIN_UNSUPPORTED"
                    );
                }
            }
        }
        let profile = digest(&(&c.effective.semantic_digest, &worker.context));
        let mut full = req.full || c.effective.config.index.mode == "full" || expected.is_empty();
        let mut old = None;
        if !expected.is_empty() {
            let store = self.store.clone();
            let baseline_id = expected.clone();
            let desired_profile = profile.clone();
            let requested_full = full;
            let baseline_cancel = cancel.clone();
            let baseline_disk = prepared.as_ref().map(|p| p.disk_budget.clone());
            let output_limits = prepared
                .as_ref()
                .map(|p| codeana_contracts::java::OutputLimits::from_config(&p.config))
                .transpose()?
                .unwrap_or_default();
            let (baseline_profile, baseline) =
                tokio::task::spawn_blocking(move || -> Result<(String, Option<FactArchive>)> {
                    let reader = store.read(&baseline_id)?;
                    let profile = reader.revision().profile.clone();
                    let facts = if requested_full || profile != desired_profile {
                        None
                    } else {
                        Some(reader.export_archive_checked_in(
                            output_limits.bytes,
                            output_limits.facts,
                            baseline_disk.as_ref().map(|budget| budget.root.as_path()),
                            || {
                                ensure!(!baseline_cancel.is_cancelled(), "CANCELLED");
                                if let Some(budget) = &baseline_disk {
                                    budget.check()?;
                                }
                                Ok(())
                            },
                        )?)
                    };
                    Ok((profile, facts))
                })
                .await??;
            if baseline_profile != profile {
                full = true;
            }
            if !full {
                old = baseline;
            }
        }
        self.store.event(
            id,
            &Event::Progress(
                if full {
                    "analyze_full"
                } else {
                    "analyze_delta"
                }
                .into(),
            ),
        )?;
        let analyzing = Instant::now();
        let result = worker
            .analyze(&c.manifest, &profile, &expected, old.as_ref(), full, cancel)
            .await?;
        let analyze_ms = analyzing.elapsed().as_millis() as u64;
        if let Some(prepared) = &prepared {
            prepared.verify()?;
        }
        let result_mode = result.header.mode.clone();
        let revision = Revision {
            id: String::new(),
            target: c.target.clone(),
            project: c.project.clone(),
            checkout: c.checkout.clone(),
            manifest: c.manifest.clone(),
            profile,
            producer: result.header.producer.clone(),
            created: 0,
        };
        let store = self.store.clone();
        let baseline_id = expected.clone();
        let staging_cancel = cancel.clone();
        let candidate_disk = prepared.as_ref().map(|p| p.disk_budget.clone());
        let candidate = tokio::task::spawn_blocking(move || {
            store.stage_archive_with_scratch_check(
                revision,
                &result.header,
                &result.records,
                &baseline_id,
                || {
                    ensure!(!staging_cancel.is_cancelled(), "CANCELLED");
                    Ok(())
                },
                |path| {
                    if let Some(budget) = &candidate_disk {
                        budget.check_candidate(Some(path))?;
                    }
                    Ok(())
                },
            )
        })
        .await??;
        let limits = prepared
            .as_ref()
            .map(|p| codeana_contracts::java::OutputLimits::from_config(&p.config))
            .transpose()?
            .unwrap_or_default();
        ensure!(
            candidate.summary.fact_count <= limits.facts,
            "JAVA_FACT_BUDGET count={} limit={}",
            candidate.summary.fact_count,
            limits.facts
        );
        let summary = candidate.summary.clone();
        let evidence = summary.evidence.clone();
        if req.strict && !evidence.semantic {
            let mut t = TaskResult::success(Output {
                producer: worker.context.producer.clone(),
                evidence,
                ..Default::default()
            });
            t.gate = "rejected".into();
            t.publication = "retained".into();
            t.exit_code = 3;
            return Ok(t);
        }
        ensure!(!cancel.is_cancelled(), "CANCELLED");
        let store = self.store.clone();
        let blobs = std::mem::take(&mut c.blobs);
        let task_id = id.to_owned();
        let cancellation = cancel.clone();
        let publishing = Instant::now();
        let revision = tokio::task::spawn_blocking(move || -> Result<Revision> {
            ensure!(!cancellation.is_cancelled(), "CANCELLED");
            codeana_storage::test_fault(&store.home, "before_publish")?;
            store.put_blob_files(&blobs)?;
            ensure!(!cancellation.is_cancelled(), "CANCELLED");
            store.publish_staged(candidate, &task_id)
        })
        .await??;
        let publish_ms = publishing.elapsed().as_millis() as u64;
        // Publication is durable; failure to acknowledge cannot turn it into a failed publication.
        let ack = worker.ack(&revision.id).await.err().map(|e| e.to_string());
        let worker_context = worker.context.clone();
        if ack.is_none()
            && self.mock.is_none()
            && self.user.daemon.java_warm_workers.unwrap_or(true)
            && let Some(key) = pool_key
        {
            let id = new_id("idle-java");
            let mut idle = self.idle_java.lock().unwrap();
            *idle = Some(IdleJava {
                key,
                revision: revision.id.clone(),
                id: id.clone(),
                worker,
            });
            drop(idle);
            let pool = self.idle_java.clone();
            tokio::spawn(async move {
                tokio::time::sleep(Duration::from_secs(60)).await;
                let mut pool = pool.lock().unwrap();
                if pool.as_ref().is_some_and(|item| item.id == id) {
                    pool.take();
                }
            });
        }
        let mut t = TaskResult::success(Output {
            revision: revision.id.clone(),
            producer: revision.producer.clone(),
            evidence,
            records: vec![
                json!({"revision":revision.id,"mode":result_mode,"facts":summary.fact_count,"manifest":revision.manifest.id(),"profile":revision.profile,"producer":revision.producer,"ack_warning":ack}),
            ],
            cursor: String::new(),
        });
        t.publication = "published".into();
        if c.effective.config.observability.mode != "off" {
            t.report = id.into();
            let report_summary = json!({"facts":summary.fact_count,"files":revision.manifest.files.len(),"counts":summary.counts,"evidence":t.output.evidence,"profile":revision.profile,"capture_digest":c.effective.capture_digest,"timing":{"index_elapsed_ms":started.elapsed().as_millis() as u64,"analyze_ms":analyze_ms,"publish_ms":publish_ms},"diagnostics":{"total":summary.diagnostic_total,"groups":summary.diagnostic_groups,"groups_omitted":summary.diagnostic_groups_omitted,"sampled":summary.diagnostic_samples.len(),"omitted":summary.diagnostic_total.saturating_sub(summary.diagnostic_samples.len() as u64)}});
            let full = matches!(
                c.effective.config.observability.mode.as_str(),
                "full" | "detailed"
            );
            let report = json!({"id":id,"created":now(),"revision":revision.id,"level":if full{"full"}else{"basic"},"summary":report_summary,"result":t,"effective":if full {serde_json::to_value(&c.effective)?} else {json!({"config_digest":c.effective.config_digest,"origins":c.effective.origins})},"context":worker_context,"diagnostics":if full {summary.diagnostic_samples.iter().map(serde_json::to_value).collect::<std::result::Result<Vec<_>,_>>()?}else{vec![]}});
            let store = self.store.clone();
            let report_id = id.to_owned();
            let report_project = revision.project.clone();
            let report_revision = revision.id.clone();
            let saved = tokio::task::spawn_blocking(move || -> Result<()> {
                codeana_storage::test_fault(&store.home, "report_write")?;
                ensure!(
                    serde_json::to_vec(&report)?.len() <= 4 * 1024 * 1024,
                    "REPORT_BUDGET"
                );
                store.put_object(
                    "report",
                    &report_id,
                    &report_project,
                    &report,
                    &[report_revision],
                )
            })
            .await?;
            match saved {
                Ok(()) => t.report = id.into(),
                Err(e) => {
                    t.report = "pending".into();
                    t.message = format!("Report persistence pending: {e}");
                    t.code = "REPORT_WRITE_FAILED".into();
                    t.exit_code = 1;
                }
            }
        }
        let policy = c.effective.config.versions.gc.clone();
        if policy.auto.unwrap_or(true) {
            let store = self.store.clone();
            let project = revision.project.clone();
            if let Err(e) = tokio::task::spawn_blocking(move || {
                store.gc_policy(
                    true,
                    Some(&project),
                    policy.keep.unwrap_or(20) as usize,
                    policy.max_age_days.unwrap_or(30) as u64,
                    policy.max_bytes.unwrap_or(0),
                )
            })
            .await?
            {
                t.message = format!("Published; GC pending: {e}");
            }
        }
        Ok(t)
    }
    fn execute(&self, req: &Request, cancel: &CancellationToken) -> Result<TaskResult> {
        ensure!(!cancel.is_cancelled(), "CANCELLED");
        let root = Path::new(&req.project);
        let (project, checkout) = self.store.project_path(&req.project)?;
        let output = match req.operation.as_str() {
            "doctor" => {
                let effective = codeana_config::resolve_files(root, req)?;
                let java = effective
                    .config
                    .languages
                    .java
                    .as_ref()
                    .is_some_and(|l| l.enabled == Some(true));
                let discovery = effective.config.discovery.mode == "explicit";
                Output {
                    records: vec![
                        json!({"generation":self.generation,"effective":effective,"modules":effective.config.modules.iter().map(|m|json!({"id":m.id,"language":m.language,"worlds":m.worlds})).collect::<Vec<_>>(),"capabilities":{"files_snapshot":"available","search_text":"available","java_index":if java&&discovery&&self.mock.is_some(){"mock_fixture_only"}else if java&&self.java_available(){"java8_native"}else{"unavailable"},"auto_discovery":if self.mock.is_none()&&self.java_available(){"java_metadata_only"}else{"unavailable"},"rust_source_analysis":"unavailable","diff":"existing_behavior"},"java_adapter":if self.mock.is_some(){"mock-fixture-v1"}else if self.java_available(){"javac-java8-v1"}else{"unavailable"},"worker_memory_enforcement":"process_group_rss_sample_100ms"}),
                    ],
                    evidence: Evidence::complete(),
                    ..Default::default()
                }
            }
            "gc" => Output {
                records: vec![{
                    let policy = codeana_config::resolve(root, req)?.config.versions.gc;
                    self.store.gc_policy(
                        req.execute,
                        Some(&project),
                        policy.keep.unwrap_or(20) as usize,
                        policy.max_age_days.unwrap_or(30) as u64,
                        policy.max_bytes.unwrap_or(0),
                    )?
                }],
                evidence: Evidence::complete(),
                ..Default::default()
            },
            "recover" => {
                ensure!(self.active() <= 1, "RECOVERY_REQUIRES_DRAIN");
                Output {
                    records: vec![self.store.recover(req.execute)?],
                    evidence: Evidence::complete(),
                    ..Default::default()
                }
            }
            "snapshot-list" => Output {
                records: self.store.objects("snapshot", &project)?,
                evidence: Evidence::complete(),
                ..Default::default()
            },
            "snapshot-remove" => {
                self.store
                    .delete_object("snapshot", &req.selector, &project)?;
                Output {
                    records: vec![json!({"removed":req.selector})],
                    evidence: Evidence::complete(),
                    ..Default::default()
                }
            }
            "diff" => self.diff(req, &project)?,
            "report" => {
                let value: Value = self.store.object("report", &req.selector, &project)?;
                let records = if req.base.is_empty() {
                    vec![value]
                } else {
                    let base: Value = self.store.object("report", &req.base, &project)?;
                    let a = report_summary(&base)?;
                    let b = report_summary(&value)?;
                    let comparable = a["profile"] == b["profile"]
                        && a["capture_digest"] == b["capture_digest"]
                        && base["level"] == value["level"];
                    ensure!(
                        comparable,
                        "REPORTS_NOT_COMPARABLE: profile, capture policy or level changed"
                    );
                    let mut deltas = BTreeMap::<String, i64>::new();
                    for key in ["facts", "files"] {
                        deltas.insert(
                            key.into(),
                            b[key].as_i64().unwrap_or(0) - a[key].as_i64().unwrap_or(0),
                        );
                    }
                    for (group, keys) in [
                        ("timing", ["index_elapsed_ms", "analyze_ms", "publish_ms"]),
                        ("diagnostics", ["total", "sampled", "omitted"]),
                    ] {
                        for key in keys {
                            deltas.insert(
                                format!("{group}.{key}"),
                                b[group][key].as_i64().unwrap_or(0)
                                    - a[group][key].as_i64().unwrap_or(0),
                            );
                        }
                    }
                    if let (Some(ac), Some(bc)) = (a["counts"].as_object(), b["counts"].as_object())
                    {
                        for key in ac.keys().chain(bc.keys()) {
                            deltas.insert(
                                format!("kind:{key}"),
                                bc.get(key).and_then(Value::as_i64).unwrap_or(0)
                                    - ac.get(key).and_then(Value::as_i64).unwrap_or(0),
                            );
                        }
                    }
                    if let (Some(ac), Some(bc)) = (
                        a["diagnostics"]["groups"].as_object(),
                        b["diagnostics"]["groups"].as_object(),
                    ) {
                        for key in ac.keys().chain(bc.keys()) {
                            deltas.insert(
                                format!("diagnostic:{key}"),
                                bc.get(key).and_then(Value::as_i64).unwrap_or(0)
                                    - ac.get(key).and_then(Value::as_i64).unwrap_or(0),
                            );
                        }
                    }
                    vec![
                        json!({"base":req.base,"target":req.selector,"comparable":true,"delta":deltas,"base_summary":a,"target_summary":b}),
                    ]
                };
                Output {
                    records,
                    evidence: Evidence::complete(),
                    ..Default::default()
                }
            }
            "annotate" => {
                let selected = if let Some(id) = req.revision.strip_prefix("snapshot:") {
                    let snapshot: history::Snapshot =
                        self.store.object("snapshot", id, &project)?;
                    ensure!(!snapshot.revision.is_empty(), "ANALYSIS_SNAPSHOT_REQUIRED");
                    Some(snapshot.revision)
                } else if !req.revision.is_empty() {
                    Some(req.revision.clone())
                } else {
                    self.store.latest(&project, &checkout).ok()
                };
                let anchor = if req.name == "add" {
                    let rid = selected
                        .as_deref()
                        .context("ANNOTATION_REVISION_REQUIRED")?;
                    let reader = self.store.read(rid)?;
                    ensure!(reader.revision().project == project, "PROJECT_MISMATCH");
                    let fact = codeana_query::resolve(&reader, &req.selector, true)?
                        .pop()
                        .context("ANNOTATION_SYMBOL_REQUIRED")?;
                    let source_hash = reader
                        .manifest()
                        .files
                        .iter()
                        .find(|s| s.path == fact.source)
                        .map(|s| s.hash.clone())
                        .unwrap_or_default();
                    Some(
                        json!({"revision":rid,"fact_id":fact.id,"world":fact.owner.world,"source":fact.source,"source_hash":source_hash,"span":fact.span}),
                    )
                } else {
                    None
                };
                let mut record = self.store.annotation(AnnotationCommand {
                    project: &project,
                    action: &req.name,
                    id: &req.base,
                    symbol: &req.selector,
                    text: &req.text,
                    version: &req.expected_revision,
                    anchor: anchor.as_ref(),
                })?;
                if req.name == "list" {
                    let reader = if let Some(id) = selected.as_deref() {
                        match self.store.read(id) {
                            Ok(r) => Some(r),
                            Err(e) if req.revision.is_empty() => {
                                let _ = e;
                                None
                            }
                            Err(e) => return Err(e),
                        }
                    } else {
                        None
                    };
                    if let Some(rows) = record.as_array_mut() {
                        for row in rows {
                            let anchor = &row["anchor"];
                            let binding = if anchor.is_null() {
                                "unbound"
                            } else if let Some(r) = reader.as_ref() {
                                let id = anchor["fact_id"].as_str().unwrap_or("");
                                let facts = r
                                    .select(&FactFilter {
                                        ids: vec![id.into()],
                                        ..Default::default()
                                    })
                                    .map_err(anyhow::Error::msg)?;
                                if facts.is_empty() {
                                    "orphaned"
                                } else if facts.len() != 1
                                    || facts[0].owner.world
                                        != anchor["world"].as_str().unwrap_or("")
                                {
                                    "ambiguous"
                                } else {
                                    let actual = r
                                        .manifest()
                                        .files
                                        .iter()
                                        .find(|s| s.path == facts[0].source)
                                        .map(|s| s.hash.as_str())
                                        .unwrap_or("");
                                    if actual == anchor["source_hash"].as_str().unwrap_or("") {
                                        "bound"
                                    } else {
                                        "ambiguous"
                                    }
                                }
                            } else {
                                "unavailable"
                            };
                            row["binding"] = json!(binding);
                            row["binding_revision"] = json!(selected);
                        }
                    }
                }
                Output {
                    records: vec![record],
                    evidence: Evidence::complete(),
                    ..Default::default()
                }
            }
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
                            "ANALYSIS_SNAPSHOT_REQUIRED"
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
                        let guarded = CancelableReader {
                            inner: &reader,
                            cancel,
                        };
                        let out = codeana_query::run(&guarded, req, &|s| {
                            ensure!(!cancel.is_cancelled(), "CANCELLED");
                            self.store.blob(&s.hash)
                        })?;
                        ensure!(!cancel.is_cancelled(), "CANCELLED");
                        return self.page(req, &project, out, vec![]);
                    }
                }
                let id = if req.revision.is_empty() {
                    self.store.latest(&project, &checkout)?
                } else if let Some(id) = req.revision.strip_prefix("snapshot:") {
                    let s: history::Snapshot = self.store.object("snapshot", id, &project)?;
                    ensure!(!s.revision.is_empty(), "ANALYSIS_SNAPSHOT_REQUIRED");
                    s.revision
                } else {
                    req.revision.clone()
                };
                let reader = self.store.read(&id)?;
                ensure!(reader.revision().project == project, "PROJECT_MISMATCH");
                let guarded = CancelableReader {
                    inner: &reader,
                    cancel,
                };
                let source = |s: &SourceUnit| -> Result<Vec<u8>> {
                    ensure!(!cancel.is_cancelled(), "CANCELLED");
                    if req.revision.is_empty() {
                        let p = root.join(&s.path);
                        let real = p.canonicalize()?;
                        ensure!(real.starts_with(root), "SOURCE_ESCAPE");
                        let b = codeana_config::bounded_read(&real, MAX_FRAME)?;
                        ensure!(
                            hash(&b) == s.hash,
                            "SOURCE_HASH_MISMATCH: re-index or select --revision"
                        );
                        Ok(b)
                    } else {
                        self.store.blob(&s.hash)
                    }
                };
                let out = codeana_query::run(&guarded, req, &source)?;
                ensure!(!cancel.is_cancelled(), "CANCELLED");
                return self.page(req, &project, out, vec![id]);
            }
            _ => bail!("UNSUPPORTED_OPERATION: {}", req.operation),
        };
        Ok(TaskResult::success(output))
    }
    fn render(&self, id: &str, req: &Request, result: &mut TaskResult) -> Result<()> {
        let bytes = output::render(req, result)?;
        for chunk in bytes.chunks(16384) {
            self.store.event(id, &Event::Output(chunk.to_vec()))?;
        }
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

struct FilesReader(Revision);
struct CancelableReader<'a> {
    inner: &'a dyn FactReader,
    cancel: &'a CancellationToken,
}
impl FactReader for CancelableReader<'_> {
    fn select(&self, filter: &FactFilter) -> std::result::Result<Vec<Fact>, String> {
        if self.cancel.is_cancelled() {
            return Err("CANCELLED".into());
        }
        let facts = self.inner.select(filter)?;
        if self.cancel.is_cancelled() {
            return Err("CANCELLED".into());
        }
        Ok(facts)
    }
    fn manifest(&self) -> &Manifest {
        self.inner.manifest()
    }
    fn revision(&self) -> &Revision {
        self.inner.revision()
    }
}
fn report_summary(value: &Value) -> Result<Value> {
    value
        .get("summary")
        .filter(|s| s.is_object())
        .cloned()
        .context("REPORT_FORMAT_UNSUPPORTED")
}
impl FactReader for FilesReader {
    fn select(&self, _: &FactFilter) -> std::result::Result<Vec<Fact>, String> {
        Ok(vec![])
    }
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
                .context("INVALID_PROJECT_PATH")?
                .to_owned(),
        );
        ancestor = ancestor.parent().context("INVALID_PROJECT_PATH")?;
    }
    let mut resolved = ancestor.canonicalize()?;
    for part in tail.into_iter().rev() {
        resolved.push(part);
    }
    Ok(resolved)
}
