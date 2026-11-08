//! Task lifetime owns cancellation, execution and terminal persistence.
use super::*;
#[derive(Default)]
pub(super) struct TaskControl {
    pub(super) token: CancellationToken,
    pub(super) deadline: Mutex<Option<Instant>>,
    pub(super) publication: Arc<PublicationFence>,
}
impl TaskControl {
    pub(super) fn cancel(&self) {
        self.publication.cancel();
        self.token.cancel();
    }
}
pub(super) struct TaskRunner;
impl TaskRunner {
    pub(super) fn submit(
        runtime: &Arc<TaskService>,
        execution: crate::task_execution::TaskExecution,
        mut req: Request,
    ) -> Result<String> {
        runtime.touch();
        ensure!(
            !req.request_id.is_empty(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::RequestIdRequired, "")
        );
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
            runtime.store.register_project(
                root.to_str().context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::NonUtf8Project,
                    "",
                ))?,
                &project,
                &checkout,
            )?;
        } else {
            runtime.store.project_path(root.to_str().context(
                codeana_model::DomainError::new(codeana_model::ErrorCode::NonUtf8Project, ""),
            )?)?;
        }
        req.project = root
            .to_str()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::NonUtf8Project,
                "",
            ))?
            .into();
        let admission = runtime.submissions.lock().unwrap();
        if let Some(id) = runtime.tasks.find(&req)? {
            return Ok(id.into());
        }
        ensure!(
            !runtime.draining.load(Ordering::SeqCst),
            DomainError::new(ErrorCode::DaemonDraining, "")
        );
        let command = TaskCommand::try_from(&req)?;
        let queue_permit = match runtime.scheduler.admit(&command) {
            Ok(permit) => permit,
            Err(error) => {
                if let Some(id) = runtime.tasks.find(&req)? {
                    return Ok(id.into());
                }
                return Err(error);
            }
        };
        let query_budget = if req.operation == Operation::SearchText
            && req.revision.is_empty()
            && req.cursor.is_empty()
        {
            Some(ResourceBudget::new(codeana_config::query_limits(
                &root,
                &runtime.store.home,
            )?))
        } else {
            None
        };
        let id = new_id("task");
        let (id, new) = runtime.tasks.register(&TaskId::from(id), &req)?;
        if !new {
            return Ok(id.into());
        }
        let job = Arc::new(Job {
            analysis: Arc::new(AnalysisTaskState::default()),
            query_budget,
            _queue_permit: queue_permit,
            control: Arc::new(TaskControl::default()),
            submitting: AtomicBool::new(true),
            watchers: AtomicUsize::new(0),
            detached: req.background,
            disconnected: Mutex::new(Instant::now()),
        });
        runtime.jobs.lock().unwrap().insert(id.clone(), job.clone());
        drop(admission);
        let capture_fault = codeana_storage::test_fault(&runtime.store.home, "before_capture");
        // Freeze project config and source bytes before queueing indexing work.
        let mut capture_timings = if matches!(req.operation.as_str(), "index" | "snapshot") {
            timings::IndexTimings::capture(&id)
        } else {
            None
        };
        let capture = if matches!(req.operation.as_str(), "index" | "snapshot" | "search-text")
            && (req.target.is_empty() || req.target == "WORKTREE")
            && (req.operation != codeana_model::Operation::SearchText
                || (req.revision.is_empty() && req.cursor.is_empty()))
        {
            Some(if let Err(error) = capture_fault {
                Err(error)
            } else if job.control.token.is_cancelled() {
                Err(DomainError::new(ErrorCode::Cancelled, "").into())
            } else if let Some(budget) = &job.query_budget {
                codeana_config::capture_query(&root, &req, budget)
            } else {
                codeana_config::capture(&root, &req)
            })
        } else {
            None
        };
        if let Some(timings) = &mut capture_timings {
            timings.phase("captured");
        }
        drop(capture_timings);
        *job.disconnected.lock().unwrap() = Instant::now();
        job.submitting.store(false, Ordering::SeqCst);
        let runtime = runtime.clone();
        let task = id.clone();
        tokio::spawn(async move {
            let report_capture = capture.as_ref().and_then(|c| c.as_ref().ok()).cloned();
            let result = Self::run(&runtime, &execution, &task, &req, capture, job.clone())
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
            if result.publication != codeana_model::PublicationState::Published
                && let Ok(Some(revision)) = runtime.tasks.committed(&task)
            {
                let reason = result.message.clone();
                if let Ok(read) = runtime.store.read(&revision) {
                    result = TaskResult::success(Output {
                        revision: revision.into(),
                        producer: read.revision().producer.clone(),
                        evidence: Evidence {
                            resource_usage: None,
                            enumeration: false,
                            semantic: false,
                            reasons: vec!["post_publication_interruption".into()],
                            limits: vec![],
                        },
                        ..Default::default()
                    });
                    result.publication = codeana_model::PublicationState::Published;
                    result.message = reason;
                }
            }
            if let Err(error) = execution.reports.save_unpublished_report(
                &task,
                &req,
                report_capture.as_ref(),
                job.analysis.analysis_admission_report(),
                &mut result,
            ) {
                result.report = codeana_model::ReportState::Pending;
                if result.exit_code == 0 {
                    result.code = "REPORT_WRITE_FAILED".into();
                    result.message = error.to_string();
                    result.exit_code = 1;
                }
            }
            if let Err(e) = runtime.render(&task, &req, &mut result) {
                if result.publication == codeana_model::PublicationState::Published {
                    result.code = "OUTPUT_FAILURE".into();
                    result.message = e.to_string();
                    result.exit_code = 1;
                } else {
                    result = TaskResult::failure("OUTPUT_FAILURE", e.to_string(), 1);
                }
            }
            if let Err(e) = runtime.tasks.finish(
                &task,
                &result,
                &Event::Terminal(Box::new(terminal(&result))),
            ) {
                eprintln!("task persistence failed: {e}");
            }
            job.analysis.staging_sources.lock().unwrap().take();
            runtime.jobs.lock().unwrap().remove(&task);
            runtime.touch();
        });
        Ok(id.into())
    }
    async fn run(
        runtime: &Arc<TaskService>,
        execution: &crate::task_execution::TaskExecution,
        id: &str,
        req: &Request,
        capture: Option<Result<Capture>>,
        job: Arc<Job>,
    ) -> Result<TaskResult> {
        runtime
            .tasks
            .event(&TaskId::from(id), &Event::Progress("queued".into()))?;
        let project = runtime.store.project_path(&req.project)?.0;
        let command = TaskCommand::try_from(req)?;
        let lock_key = if matches!(req.operation.as_str(), "index" | "snapshot") {
            capture
                .as_ref()
                .and_then(|r| r.as_ref().ok())
                .map(|c| c.target.clone())
                .unwrap_or_else(|| project.clone())
        } else {
            project.clone()
        };
        let deadline = Instant::now()
            + Duration::from_secs(if req.deadline_seconds == 0 {
                120
            } else {
                req.deadline_seconds
            });
        *job.control.deadline.lock().unwrap() = Some(deadline);
        job.control.publication.tighten_deadline(deadline);
        let work = async {
            let _permits = runtime
                .scheduler
                .execute(&command, project.clone(), lock_key, &job.control.token)
                .await?;
            ensure!(
                !job.control.token.is_cancelled(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
            );
            runtime.tasks.running(&TaskId::from(id))?;
            runtime
                .tasks
                .event(&TaskId::from(id), &Event::Progress("running".into()))?;
            let mut result = if let Some(budget) = &job.query_budget
                && budget.exhausted()
            {
                execution
                    .history
                    .resource_page(req, &project, "", budget, vec![])?
            } else {
                let capture = capture.transpose()?;
                match req.operation {
                    Operation::Index => {
                        execution
                            .index(
                                TaskContext::new(TaskId::from(id), &job, runtime.tasks.clone()),
                                &IndexCommand::from(req),
                                capture.context(codeana_model::DomainError::new(
                                    codeana_model::ErrorCode::CaptureRequired,
                                    "",
                                ))?,
                                &job.control.token,
                            )
                            .await?
                    }
                    Operation::Snapshot => {
                        execution
                            .snapshot(
                                TaskContext::new(TaskId::from(id), &job, runtime.tasks.clone()),
                                req,
                                capture,
                                &job.control.token,
                            )
                            .await?
                    }
                    Operation::SearchText if req.revision.is_empty() && req.cursor.is_empty() => {
                        let c = capture.context(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::CaptureRequired,
                            "",
                        ))?;
                        let request = req.clone();
                        let history = execution.history.clone();
                        let cancellation = job.control.token.clone();
                        let budget = job
                            .query_budget
                            .clone()
                            .expect("active text capture owns a root budget");
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
                            let reader = BudgetedSource {
                                inner: &reader,
                                budget: &budget,
                            };
                            history.text_page(
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
                        let runtime = runtime.clone();
                        let query = execution.query.clone();
                        let cancellation = job.control.token.clone();
                        tokio::task::spawn_blocking(move || {
                            query.execute(&request, &cancellation, runtime.active())
                        })
                        .await??
                    }
                }
            };
            if req.strict
                && (!result.output.evidence.semantic || !result.output.evidence.enumeration)
            {
                result.gate = codeana_model::GateState::Rejected;
                result.exit_code = 3;
            }
            Ok::<_, anyhow::Error>(result)
        };
        tokio::pin!(work);
        let reason = tokio::select! {
            result = &mut work => return result,
            _ = tokio::time::sleep_until(tokio::time::Instant::from_std(deadline)) => ErrorCode::Timeout,
            _ = job.control.token.cancelled() => ErrorCode::Cancelled,
        };
        job.control.cancel();
        if let Some((_, budget)) = job.analysis.admission.lock().unwrap().as_ref() {
            budget.cancel();
        }
        if let Some(coordinator) = job.analysis.coordinator.lock().unwrap().as_ref() {
            coordinator.lock().unwrap().cancel();
        }
        // Never detach a storage operation. The future retains its permits until all
        // blocking operations and any in-flight catalog commit have actually finished.
        let outcome = work.await;
        match outcome {
            Ok(result) if result.publication == PublicationState::Published => Ok(result),
            _ => Err(DomainError::new(reason, "").into()),
        }
    }
}

impl AnalysisTaskState {
    pub(super) fn analysis_admission_report(
        &self,
    ) -> Option<crate::reports::AnalysisAdmissionReport> {
        let ledger = self.admission.lock().unwrap();
        ledger.as_ref().map(|(limits, context)| {
            let coordinator = self
                .coordinator
                .lock()
                .unwrap()
                .as_ref()
                .map(|c| c.lock().unwrap().snapshot());
            let has_worker_usage = coordinator.as_ref().is_some_and(|c| {
                c.languages.iter().any(|l| {
                    l.worker.usage.is_some() || l.workers.iter().any(|w| w.usage.is_some())
                })
            });
            crate::reports::AnalysisAdmissionReport {
                coverage: if has_worker_usage {
                    "input_admission_and_worker_usage"
                } else {
                    "input_admission_only"
                }
                .into(),
                limits: *limits,
                usage: coordinator
                    .as_ref()
                    .map(|c| c.known_usage.clone())
                    .unwrap_or_else(|| context.usage()),
                coordinator,
            }
        })
    }
}

#[derive(Default)]
pub(super) struct AnalysisTaskState {
    pub staging_sources: Mutex<Option<Arc<BTreeMap<String, codeana_config::CapturedBlob>>>>,
    pub coordinator: Mutex<Option<Arc<Mutex<AnalysisCoordinator>>>>,
    pub admission: Mutex<Option<(AnalysisLimits, AnalysisContext)>>,
}
/// Only this task's resources and publication control; no job registry or scheduler.
pub(super) struct TaskContext {
    pub id: TaskId,
    pub analysis: Arc<AnalysisTaskState>,
    pub control: Arc<TaskControl>,
    events: Arc<dyn TaskRepository>,
}
impl TaskContext {
    pub(super) fn new(id: TaskId, job: &Job, events: Arc<dyn TaskRepository>) -> Self {
        Self {
            id,
            analysis: job.analysis.clone(),
            control: job.control.clone(),
            events,
        }
    }
    pub(super) fn event(&self, id: &TaskId, event: &Event) -> Result<u64> {
        ensure!(
            *id == self.id,
            DomainError::new(ErrorCode::TaskNotFound, "event task")
        );
        self.events.event(id, event)
    }
}

pub(super) struct TaskService {
    store: Store,
    tasks: Arc<dyn TaskRepository>,
    jobs: Mutex<BTreeMap<TaskId, Arc<Job>>>,
    submissions: Mutex<()>,
    scheduler: scheduler::Scheduler,
    draining: AtomicBool,
    shutdown: CancellationToken,
    last_activity: Mutex<Instant>,
    idle_seconds: u64,
}
impl TaskService {
    pub(super) fn new(store: Store, user: &codeana_config::UserConfig) -> Arc<Self> {
        Arc::new(Self {
            tasks: Arc::new(store.clone()),
            store,
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
        })
    }
    pub(super) fn recover_unfinished(&self) -> Result<()> {
        for (id, published) in self.tasks.unfinished()? {
            let result = if let Some(rev) = published {
                let r = self.store.read(&rev)?;
                let mut t = TaskResult::success(Output {
                    revision: rev.into(),
                    producer: r.revision().producer.clone(),
                    evidence: Evidence::complete(),
                    ..Default::default()
                });
                t.publication = PublicationState::Published;
                t.message = "Recovered durable publication; output was not delivered".into();
                t
            } else {
                TaskResult::failure("DAEMON_RESTARTED", "Task interrupted before publication", 1)
            };
            self.tasks
                .finish(&id, &result, &Event::Terminal(Box::new(terminal(&result))))?;
        }
        self.store.recover(true)?;
        Ok(())
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
    pub fn touch(&self) {
        *self.last_activity.lock().unwrap() = Instant::now();
    }
    pub fn active(&self) -> usize {
        self.jobs.lock().unwrap().len()
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
