//! Task lifetime owns cancellation, execution and terminal persistence.
use super::*;
#[derive(Default)]
pub(super) struct TaskControl {
    pub(super) token: CancellationToken,
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
    pub(super) fn submit(engine: &Arc<Engine>, mut req: Request) -> Result<String> {
        engine.touch();
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
            engine.store.register_project(
                root.to_str().context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::NonUtf8Project,
                    "",
                ))?,
                &project,
                &checkout,
            )?;
        } else {
            engine.store.project_path(root.to_str().context(
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
        let admission = engine.submissions.lock().unwrap();
        if let Some(id) = engine.tasks.find(&req)? {
            return Ok(id.into());
        }
        ensure!(
            !engine.draining.load(Ordering::SeqCst),
            DomainError::new(ErrorCode::DaemonDraining, "")
        );
        let command = TaskCommand::try_from(&req)?;
        let queue_permit = match engine.scheduler.admit(&command) {
            Ok(permit) => permit,
            Err(error) => {
                if let Some(id) = engine.tasks.find(&req)? {
                    return Ok(id.into());
                }
                return Err(error);
            }
        };
        let id = new_id("task");
        let (id, new) = engine.tasks.register(&TaskId::from(id), &req)?;
        if !new {
            return Ok(id.into());
        }
        let job = Arc::new(Job {
            _queue_permit: queue_permit,
            control: TaskControl::default(),
            submitting: AtomicBool::new(true),
            watchers: AtomicUsize::new(0),
            detached: req.background,
            disconnected: Mutex::new(Instant::now()),
        });
        engine.jobs.lock().unwrap().insert(id.clone(), job.clone());
        drop(admission);
        let capture_fault = codeana_storage::test_fault(&engine.store.home, "before_capture");
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
        let engine = engine.clone();
        let task = id.clone();
        tokio::spawn(async move {
            let report_capture = capture.as_ref().and_then(|c| c.as_ref().ok()).cloned();
            let result = Self::run(&engine, &task, &req, capture, job.clone())
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
                    result.publication = codeana_model::PublicationState::Published;
                    result.message = reason;
                }
            }
            if let Err(error) =
                engine.save_unpublished_report(&task, &req, report_capture.as_ref(), &mut result)
            {
                result.report = codeana_model::ReportState::Pending;
                if result.exit_code == 0 {
                    result.code = "REPORT_WRITE_FAILED".into();
                    result.message = error.to_string();
                    result.exit_code = 1;
                }
            }
            if let Err(e) = engine.render(&task, &req, &mut result) {
                if result.publication == codeana_model::PublicationState::Published {
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
    async fn run(
        engine: &Arc<Engine>,
        id: &str,
        req: &Request,
        capture: Option<Result<Capture>>,
        job: Arc<Job>,
    ) -> Result<TaskResult> {
        engine
            .tasks
            .event(&TaskId::from(id), &Event::Progress("queued".into()))?;
        let project = engine.store.project_path(&req.project)?.0;
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
        let work = async {
            let _permits = engine
                .scheduler
                .execute(&command, project, lock_key, &job.control.token)
                .await?;
            ensure!(
                !job.control.token.is_cancelled(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
            );
            engine.tasks.running(&TaskId::from(id))?;
            engine
                .tasks
                .event(&TaskId::from(id), &Event::Progress("running".into()))?;
            let capture = capture.transpose()?;
            let mut result = match req.operation {
                Operation::Index => {
                    engine
                        .index(
                            id,
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
                    engine
                        .snapshot(id, req, capture, &job.control.token)
                        .await?
                }
                Operation::SearchText if req.revision.is_empty() && req.cursor.is_empty() => {
                    let c = capture.context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::CaptureRequired,
                        "",
                    ))?;
                    let request = req.clone();
                    let engine = engine.clone();
                    let cancellation = job.control.token.clone();
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
                    let engine = engine.clone();
                    let cancellation = job.control.token.clone();
                    tokio::task::spawn_blocking(move || engine.execute(&request, &cancellation))
                        .await??
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
            _ = tokio::time::sleep(Duration::from_secs(if req.deadline_seconds == 0 { 120 } else { req.deadline_seconds })) => ErrorCode::Timeout,
            _ = job.control.token.cancelled() => ErrorCode::Cancelled,
        };
        job.control.cancel();
        // Never detach a storage operation. The future retains its permits until all
        // blocking operations and any in-flight catalog commit have actually finished.
        let outcome = work.await;
        match outcome {
            Ok(result) if result.publication == PublicationState::Published => Ok(result),
            _ => Err(DomainError::new(reason, "").into()),
        }
    }
}
