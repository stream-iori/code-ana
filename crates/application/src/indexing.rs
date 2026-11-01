//! Index phases own their worker/candidate handles; publication separates analysis from post-processing.
use super::*;
use crate::reports::{
    ReportConfig, ReportDiagnostics, ReportDocument, ReportSummary, ReportTiming,
};
pub(super) type StagedCandidate =
    <crate::ports::storage::IndexAdapter as IndexRepository>::Candidate;
struct IndexSetup {
    worker: worker::Worker,
    prepared: Option<java::PreparedJava>,
    expected: RevisionId,
    pool_key: Option<String>,
    profile: String,
    full: bool,
    old: Option<BaselineInput>,
    unchanged: bool,
}
struct AnalyzedCandidate {
    result_mode: AnalysisMode,
    candidate: StagedCandidate,
    analyze_ms: u64,
}
#[derive(Serialize)]
struct IndexReceipt<'a> {
    revision: &'a str,
    mode: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    analysis_mode: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    classpath_status: Option<&'a str>,
    facts: u64,
    manifest: String,
    profile: &'a str,
    producer: &'a str,
    ack_warning: Option<&'a str>,
    statistics: &'a CandidateStatistics,
}
pub(super) struct PublishedIndex {
    pub(super) revision: Revision,
    pub(super) result_mode: AnalysisMode,
    pub(super) summary: IndexSummary,
    pub(super) evidence: Evidence,
    pub(super) ack: Option<String>,
    pub(super) worker_context: BuildContext,
    pub(super) analyze_ms: u64,
    pub(super) publish_ms: u64,
    pub(super) index_elapsed_ms: u64,
}
pub(super) struct CandidatePublication<'a> {
    pub candidate: StagedCandidate,
    pub producer: &'a str,
    pub progress: Option<&'static str>,
}
pub(super) enum CandidateOutcome {
    Committed {
        revision: Revision,
        started: Instant,
    },
    Rejected(Box<TaskResult>),
}

impl Engine {
    pub(super) fn staging_context(&self, id: &str) -> Option<GraphStagingContext> {
        let jobs = self.jobs.lock().unwrap();
        let job = jobs.get(&TaskId::from(id))?;
        let coordinator = job.analysis_coordinator.lock().unwrap().clone()?;
        let sources = job.staging_sources.lock().unwrap().clone()?;
        Some(GraphStagingContext {
            hosts: coordinator.lock().unwrap().hosts(),
            source: Arc::new(move |hash, size| {
                let blob = sources.get(hash).ok_or_else(|| {
                    DomainError::new(ErrorCode::SourceHashMismatch, "captured source missing")
                })?;
                let limit = usize::try_from(size)
                    .map_err(|_| DomainError::new(ErrorCode::SourceHashMismatch, "size"))?;
                blob.read(limit).map_err(|error| {
                    DomainError::new(ErrorCode::SourceHashMismatch, error.to_string())
                })
            }),
        })
    }
    /// Reuse still creates a private candidate and follows the normal publication fence.
    /// Workers must finish their Reused receipt before this method is called.
    pub(super) async fn stage_reused_revision(
        &self,
        id: &str,
        revision: Revision,
        expected: RevisionId,
        producer: String,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, StagedCandidate)> {
        let header = AnalysisHeader {
            mode: AnalysisMode::Delta,
            baseline: expected.to_string(),
            manifest: revision.manifest.id(),
            profile: revision.profile.clone(),
            producer,
            replacements: vec![],
        };
        let store = self.index.clone().with_context(self.staging_context(id));
        let cancellation = cancel.clone();
        let candidate = tokio::task::spawn_blocking(move || {
            store.stage_graph_records(
                revision,
                &header,
                std::iter::empty(),
                &expected,
                || {
                    ensure!(
                        !cancellation.is_cancelled(),
                        DomainError::new(ErrorCode::Cancelled, "")
                    );
                    Ok(())
                },
                |_| Ok(()),
            )
        })
        .await??;
        Ok((AnalysisMode::Delta, candidate))
    }

    /// Every execution policy passes the same quality gate and publication fence.
    pub(super) async fn publish_candidate(
        &self,
        id: &str,
        req: &IndexCommand,
        capture: &mut Capture,
        publication: CandidatePublication<'_>,
        cancel: &CancellationToken,
    ) -> Result<CandidateOutcome> {
        let evidence = &self.index.summary(&publication.candidate).evidence;
        if req.strict && !evidence.semantic {
            let mut result = TaskResult::success(Output {
                producer: publication.producer.into(),
                evidence: evidence.clone(),
                ..Default::default()
            });
            result.gate = GateState::Rejected;
            result.publication = PublicationState::Retained;
            result.exit_code = 3;
            return Ok(CandidateOutcome::Rejected(Box::new(result)));
        }
        ensure!(
            !cancel.is_cancelled(),
            DomainError::new(ErrorCode::Cancelled, "")
        );
        if let Some(phase) = publication.progress {
            self.tasks
                .event(&TaskId::from(id), &Event::Progress(phase.into()))?;
        }
        let started = Instant::now();
        let revision = self
            .index
            .publish(
                publication.candidate,
                TaskId::from(id),
                std::mem::take(&mut capture.blobs),
                cancel.clone(),
                self.publication_fence(id)?,
            )
            .await?;
        Ok(CandidateOutcome::Committed { revision, started })
    }

    pub(super) fn analysis_binding(
        &self,
        id: &str,
        language: AnalysisLanguage,
        instance: &str,
    ) -> Option<worker::AnalysisBinding> {
        self.jobs
            .lock()
            .unwrap()
            .get(&TaskId::from(id))
            .and_then(|job| job.analysis_coordinator.lock().unwrap().clone())
            .map(|coordinator| worker::AnalysisBinding {
                coordinator,
                language,
                instance: instance.into(),
            })
    }

    pub(super) async fn index(
        &self,
        id: &str,
        req: &IndexCommand,
        mut c: Capture,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        // Admit the frozen input before selecting any language or reuse path.
        // This ledger admits frozen inputs and the plan; the coordinator assigns the
        // remaining shared account to language hosts and workers.
        if self.mock.is_none() {
            let user = codeana_config::load_user(self.index.home())?;
            let limits = c
                .effective
                .config
                .resources
                .index
                .analysis
                .resolve(&user.resources.index.analysis)?;
            let deadline = self
                .jobs
                .lock()
                .unwrap()
                .get(&TaskId::from(id))
                .and_then(|job| *job.control.deadline.lock().unwrap());
            let budget = AnalysisContext::new(limits, deadline)?;
            if let Some(job) = self.jobs.lock().unwrap().get(&TaskId::from(id)) {
                *job.analysis_admission.lock().unwrap() = Some((limits, budget.clone()));
            }
            let admit = || -> Result<()> {
                budget.charge(1, 0, 0)?;
                for file in &c.manifest.files {
                    ensure!(
                        !cancel.is_cancelled(),
                        DomainError::new(ErrorCode::Cancelled, "")
                    );
                    budget.charge(1, 1, file.size.saturating_add(file.path.len() as u64))?;
                }
                for (hash, blob) in &c.blobs {
                    budget.charge(
                        1,
                        1,
                        (256 + hash.len() + blob.as_ref().as_os_str().as_encoded_bytes().len())
                            as u64,
                    )?;
                }
                Ok(())
            };
            if let Err(error) = admit() {
                if let Some(failure) = error.downcast_ref::<AnalysisFailure>() {
                    return Err(DomainError::analysis(failure.clone()).into());
                }
                return Err(error);
            }
            if let Some(job) = self.jobs.lock().unwrap().get(&TaskId::from(id)) {
                *job.staging_sources.lock().unwrap() = Some(Arc::new(c.blobs.clone()));
            }
        }
        let plan = {
            let admission = self
                .jobs
                .lock()
                .unwrap()
                .get(&TaskId::from(id))
                .and_then(|job| job.analysis_admission.lock().unwrap().clone());
            crate::index_plan::IndexPlan::build(&c, admission.as_ref().map(|(_, budget)| budget))?
        };
        if self.mock.is_none() {
            let participants = &plan.participants;
            if !participants.is_empty() {
                let jobs = self.jobs.lock().unwrap();
                if let Some(job) = jobs.get(&TaskId::from(id)) {
                    let admission = job.analysis_admission.lock().unwrap();
                    if let Some((limits, budget)) = admission.as_ref() {
                        let coordinator = AnalysisCoordinator::new(
                            id,
                            *limits,
                            budget.usage(),
                            participants,
                            *job.control.deadline.lock().unwrap(),
                        )
                        .map_err(|failure| {
                            budget.fail(failure.clone());
                            DomainError::analysis(failure)
                        })?;
                        *job.analysis_coordinator.lock().unwrap() =
                            Some(Arc::new(Mutex::new(coordinator)));
                    }
                }
            }
        }
        use crate::index_plan::ExecutionPolicy;
        match plan.policy {
            ExecutionPolicy::Prepared => {
                return self.index_prepared(id, req, c, &plan, cancel).await;
            }
            ExecutionPolicy::Conservative => {
                return self.index_python(id, req, c, &plan, cancel).await;
            }
            ExecutionPolicy::RustIncremental => {
                return self.index_rust(id, req, c, &plan, cancel).await;
            }
            ExecutionPolicy::Java => {}
        }
        let started = Instant::now();
        let mut timings = timings::IndexTimings::start(id);
        let mut setup = self.setup_index(id, req, &c, cancel, &mut timings).await?;
        let AnalyzedCandidate {
            result_mode,
            candidate,
            analyze_ms,
        } = match self
            .analyze_index(id, &c, cancel, &mut timings, &mut setup)
            .await
        {
            Ok(analyzed) => analyzed,
            Err(error) => {
                if let Err(cleanup) = setup.worker.shutdown().await {
                    eprintln!("java_analysis_cleanup_warning: {cleanup:#}");
                }
                return Err(error);
            }
        };
        let IndexSetup {
            prepared,
            expected: _,
            worker,
            pool_key,
            profile: _,
            full: _,
            old: _,
            unchanged,
        } = setup;
        let mut worker_slot = Some(worker);
        let result: Result<TaskResult> = async {
            let worker = worker_slot.as_mut().context("INDEX_WORKER_REQUIRED")?;
            let limits = prepared
                .as_ref()
                .map(|p| codeana_contracts::java::OutputLimits::from_config(&p.config))
                .transpose()?
                .unwrap_or_default();
            ensure!(
                self.index.summary(&candidate).fact_count <= limits.facts,
                "JAVA_FACT_BUDGET count={} limit={}",
                self.index.summary(&candidate).fact_count,
                limits.facts
            );
            let summary = self.index.summary(&candidate).clone();
            if let Some(timings) = &mut timings {
                timings.phase("candidate_validated");
                timings.storage(&summary.phase_ms);
            }
            let evidence = summary.evidence.clone();
            let (revision, publishing) = match self
                .publish_candidate(
                    id,
                    req,
                    &mut c,
                    CandidatePublication {
                        candidate,
                        producer: &worker.context.producer,
                        progress: None,
                    },
                    cancel,
                )
                .await?
            {
                CandidateOutcome::Committed { revision, started } => (revision, started),
                CandidateOutcome::Rejected(result) => return Ok(*result),
            };
            let publish_ms = publishing.elapsed().as_millis() as u64;
            if let Some(timings) = &mut timings {
                timings.phase("published");
            }
            // Publication is durable; failure to acknowledge cannot turn it into a failed publication.
            let ack = if unchanged {
                None
            } else {
                crate::analysis_unit::acknowledge(
                    crate::analysis_unit::Units::One(Some(&mut *worker)),
                    &revision.id,
                )
                .await
            };
            let worker_context = worker.context.clone();
            if let Some(timings) = &mut timings {
                timings.phase("ack_finished");
            }
            if !unchanged
                && ack.is_none()
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
                    worker: worker_slot.take().context("INDEX_WORKER_REQUIRED")?,
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
            self.finish_index(
                id,
                &c,
                PublishedIndex {
                    revision,
                    result_mode,
                    summary,
                    evidence,
                    ack,
                    worker_context,
                    analyze_ms,
                    publish_ms,
                    index_elapsed_ms: started.elapsed().as_millis() as u64,
                },
            )
            .await
        }
        .await;
        if let Some(worker) = worker_slot.as_mut()
            && let Err(cleanup) = worker.shutdown().await
        {
            eprintln!("java_index_cleanup_warning: {cleanup:#}");
        }
        result
    }
    async fn setup_index(
        &self,
        id: &str,
        req: &IndexCommand,
        c: &Capture,
        cancel: &CancellationToken,
        timings: &mut Option<timings::IndexTimings>,
    ) -> Result<IndexSetup> {
        codeana_config::require_java(c)?;
        let prepared = if self.mock.is_none() {
            Some(self.prepare_java_unit(c.clone()).await?)
        } else {
            None
        };
        if let Some(timings) = timings {
            timings.phase("prepared");
        }
        let expected = self.index.head(&IndexTarget::from(c.target.clone()))?;
        if !req.expected_revision.is_empty() {
            ensure!(
                expected == req.expected_revision.as_str(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::HeadConflict, "")
            );
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
            codeana_model::DomainError::new(codeana_model::ErrorCode::ProjectWorkerMemoryLimit, "")
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
            if let Some(mut idle) = idle {
                if let (Some(prepared), Some(key)) = (prepared.as_ref(), pool_key.as_ref())
                    && idle.key == *key
                    && idle.revision == expected.as_str()
                    && idle.worker.alive().unwrap_or(false)
                {
                    let discovered = idle
                        .worker
                        .rediscover(
                            prepared,
                            &c.manifest,
                            cancel,
                            self.analysis_binding(id, AnalysisLanguage::Java, ""),
                        )
                        .await;
                    if let Err(error) = discovered {
                        if let Err(cleanup) = idle.worker.shutdown().await {
                            eprintln!("java_rediscover_cleanup_warning: {cleanup:#}");
                        }
                        return Err(error);
                    }
                    reused = Some(idle.worker);
                } else if let Err(cleanup) = idle.worker.shutdown().await {
                    eprintln!("java_idle_cleanup_warning: {cleanup:#}");
                }
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
                self.analysis_binding(id, AnalysisLanguage::Java, ""),
            )
            .await?
        };
        let validated: Result<_> = async {
            ensure!(
                worker.context.language == "java",
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::WorkerLanguageMismatch,
                    ""
                )
            );
            for module in &c.effective.config.modules {
                if module.language == "java" {
                    if let Some(version) = &module.analysis.source_version {
                        ensure!(
                            version == "auto" || version == &worker.context.source_version,
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::SourceVersionUnsupported,
                                ""
                            )
                        );
                    }
                    if self.mock.is_some()
                        && let Some(toolchain) = &module.analysis.toolchain
                    {
                        ensure!(
                            toolchain == "auto"
                                || toolchain
                                    == &format!("mock-jdk{}", worker.context.source_version),
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::MockToolchainUnsupported,
                                ""
                            )
                        );
                    }
                }
            }
            let profile = digest(&(
                codeana_comment_search::identity(),
                &c.effective.semantic_digest,
                &worker.context,
                "anatomist-schema32-graph1",
            ));
            if let Some(timings) = timings {
                timings.phase("worker_context");
            }
            let mut full =
                req.full || c.effective.config.index.mode == "full" || expected.is_empty();
            let mut old = None;
            let mut unchanged = false;
            if !expected.is_empty() && !full {
                let store = self.index.clone().with_context(self.staging_context(id));
                let baseline_id = expected.clone();
                let desired_profile = profile.clone();
                let desired_manifest = c.manifest.id();
                let desired_files = c.manifest.clone();
                let baseline_cancel = cancel.clone();
                let summary_root = prepared.as_ref().map(|p| p.disk_budget.root.clone());
                let (baseline_profile, baseline, same_inputs) = tokio::task::spawn_blocking(
                    move || -> Result<(String, Option<BaselineInput>, bool)> {
                        ensure!(
                            !baseline_cancel.is_cancelled(),
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::Cancelled,
                                ""
                            )
                        );
                        let reader = store.read(&baseline_id)?;
                        let profile = reader.revision().profile.clone();
                        let same = reader.revision().manifest.id() == desired_manifest;
                        let summary = if profile != desired_profile || same {
                            None
                        } else {
                            let summary = reader.baseline_summary_for(&desired_files)?;
                            if let Some(root) = summary_root {
                                let path = root.join("baseline-summary.json");
                                let bytes = serde_json::to_vec(&summary)?;
                                ensure!(
                                    bytes.len() <= 64 << 20,
                                    codeana_model::DomainError::new(
                                        codeana_model::ErrorCode::GraphBaselineBudget,
                                        ""
                                    )
                                );
                                store.write_baseline(&path, &bytes)?;
                                Some(BaselineInput::File(BaselineFile {
                                    path,
                                    sha256: codeana_model::hash(&bytes),
                                    bytes: bytes.len() as u64,
                                }))
                            } else {
                                Some(BaselineInput::Inline(summary))
                            }
                        };
                        Ok((profile, summary, same))
                    },
                )
                .await??;
                if baseline_profile != profile {
                    full = true;
                }
                unchanged = prepared.is_some() && !full && same_inputs;
                if !full {
                    old = baseline;
                }
            }
            Ok((profile, full, old, unchanged))
        }
        .await;
        let (profile, full, old, unchanged) = match validated {
            Ok(values) => values,
            Err(error) => {
                if let Err(cleanup) = worker.shutdown().await {
                    eprintln!("java_setup_cleanup_warning: {cleanup:#}");
                }
                return Err(error);
            }
        };
        Ok(IndexSetup {
            prepared,
            expected,
            worker,
            pool_key,
            profile,
            full,
            old,
            unchanged,
        })
    }
    async fn analyze_index(
        &self,
        id: &str,
        c: &Capture,
        cancel: &CancellationToken,
        timings: &mut Option<timings::IndexTimings>,
        setup: &mut IndexSetup,
    ) -> Result<AnalyzedCandidate> {
        let prepared = &setup.prepared;
        let expected = setup.expected.clone();
        let worker = &mut setup.worker;
        let profile = setup.profile.clone();
        let full = setup.full;
        let old = &setup.old;
        let unchanged = setup.unchanged;
        let analyzing = Instant::now();
        let streaming = prepared.is_some() && !unchanged;
        let analyze_ms;
        let (result_mode, candidate) = if streaming {
            let store = self.index.clone().with_context(self.staging_context(id));
            let revision = Revision {
                id: String::new(),
                target: c.target.clone(),
                project: c.project.clone(),
                checkout: c.checkout.clone(),
                manifest: c.manifest.clone(),
                profile: profile.clone(),
                producer: worker.context.producer.clone(),
                created: 0,
            };
            let baseline_id = expected.clone();
            let staging_cancel = cancel.clone();
            let candidate_disk = prepared.as_ref().map(|p| p.disk_budget.clone());
            let mut candidate_task = None;
            let header = worker
                .analyze_into(
                    &c.manifest,
                    &profile,
                    &expected,
                    old.as_ref(),
                    full,
                    cancel,
                    |header| {
                        let (sender, records) = codeana_contracts::stream::GraphStream::channel();
                        let store = store.clone();
                        let revision = revision.clone();
                        let header = header.clone();
                        let baseline_id = baseline_id.clone();
                        let staging_cancel = staging_cancel.clone();
                        let candidate_disk = candidate_disk.clone();
                        candidate_task = Some(tokio::task::spawn_blocking(move || {
                            let diagnostics =
                                codeana_contracts::performance::Report::new("storage-detail");
                            diagnostics.capture.sync(|| {
                                store.stage_graph_records(
                                    revision,
                                    &header,
                                    records,
                                    &baseline_id,
                                    || {
                                        ensure!(
                                            !staging_cancel.is_cancelled(),
                                            codeana_model::DomainError::new(
                                                codeana_model::ErrorCode::Cancelled,
                                                ""
                                            )
                                        );
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
                        }));
                        Ok(sender)
                    },
                )
                .await;
            analyze_ms = analyzing.elapsed().as_millis() as u64;
            if let Some(timings) = timings {
                timings.phase("analyzed");
            }
            let staged = match candidate_task {
                Some(task) => Some(task.await?),
                None => None,
            };
            let header = header?;
            if let Some(prepared) = &prepared {
                prepared.verify()?;
            }
            (
                header.mode,
                staged.context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::CandidateStreamNotStarted,
                    "",
                ))??,
            )
        } else {
            let result = if unchanged {
                // The captured manifest and semantic profile include adapter/toolchain identity.
                // Keep baseline export validation/budgets and normal candidate validation/publication,
                // but avoid sending unchanged facts through Java just to discover zero dirty owners.
                crate::analysis_unit::retire(crate::analysis_unit::Units::One(Some(&mut *worker)))
                    .await?;
                let prepared = prepared.as_ref().context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaRequired,
                    "",
                ))?;
                let limits = codeana_contracts::java::OutputLimits::from_config(&prepared.config)?;
                SpooledAnalysis {
                    header: AnalysisHeader {
                        mode: codeana_model::AnalysisMode::Delta,
                        baseline: expected.to_string(),
                        manifest: c.manifest.id(),
                        profile: profile.clone(),
                        producer: worker.context.producer.clone(),
                        replacements: vec![],
                    },
                    records: GraphArchiveBuilder::new(
                        Some(&prepared.disk_budget.root),
                        limits.bytes,
                        limits.facts,
                    )?
                    .seal()?,
                }
            } else {
                worker
                    .analyze(&c.manifest, &profile, &expected, old.as_ref(), full, cancel)
                    .await?
            };

            analyze_ms = analyzing.elapsed().as_millis() as u64;
            if let Some(timings) = timings {
                timings.phase("analyzed");
            }
            if let Some(prepared) = &prepared {
                prepared.verify()?;
            }
            let result_mode = result.header.mode;
            let revision = Revision {
                id: String::new(),
                target: c.target.clone(),
                project: c.project.clone(),
                checkout: c.checkout.clone(),
                manifest: c.manifest.clone(),
                profile: profile.clone(),
                producer: result.header.producer.clone(),
                created: 0,
            };
            let store = self.index.clone().with_context(self.staging_context(id));
            let baseline_id = expected.clone();
            let staging_cancel = cancel.clone();
            let candidate_disk = prepared.as_ref().map(|p| p.disk_budget.clone());
            let candidate = tokio::task::spawn_blocking(move || {
                let diagnostics = codeana_contracts::performance::Report::new("storage-detail");
                diagnostics.capture.sync(|| {
                    store.stage_graph_records(
                        revision,
                        &result.header,
                        result.records.records()?,
                        &baseline_id,
                        || {
                            ensure!(
                                !staging_cancel.is_cancelled(),
                                codeana_model::DomainError::new(
                                    codeana_model::ErrorCode::Cancelled,
                                    ""
                                )
                            );
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
            })
            .await??;
            (result_mode, candidate)
        };
        Ok(AnalyzedCandidate {
            result_mode,
            candidate,
            analyze_ms,
        })
    }
    pub(super) async fn finish_index(
        &self,
        id: &str,
        c: &Capture,
        published: PublishedIndex,
    ) -> Result<TaskResult> {
        let PublishedIndex {
            revision,
            result_mode,
            summary,
            evidence,
            ack,
            worker_context,
            analyze_ms,
            publish_ms,
            index_elapsed_ms,
        } = published;
        let mut t = TaskResult::success(Output {
            revision: revision.id.clone(),
            producer: revision.producer.clone(),
            evidence,
            records: vec![Record::display(&IndexReceipt {
                revision: &revision.id,
                mode: result_mode.as_str(),
                analysis_mode: summary.analysis_mode.as_deref(),
                classpath_status: summary.classpath_status.as_deref(),
                facts: summary.fact_count,
                manifest: revision.manifest.id(),
                profile: &revision.profile,
                producer: &revision.producer,
                ack_warning: ack.as_deref(),
                statistics: &summary.statistics,
            })?],
            cursor: String::new(),
        });
        t.publication = codeana_model::PublicationState::Published;
        if c.effective.config.observability.mode != "off" {
            t.report = id.into();
            let report_summary = ReportSummary {
                analysis_mode: summary.analysis_mode.clone(),
                classpath_status: summary.classpath_status.clone(),
                facts: summary.fact_count,
                files: revision.manifest.files.len(),
                counts: summary.counts.clone(),
                evidence: t.output.evidence.clone(),
                profile: revision.profile.clone(),
                capture_digest: c.effective.capture_digest.clone(),
                statistics: summary.statistics.clone(),
                timing: ReportTiming {
                    index_elapsed_ms,
                    analyze_ms,
                    publish_ms,
                    storage_phase_ms: summary.phase_ms.clone(),
                },
                diagnostics: ReportDiagnostics {
                    total: summary.diagnostic_total,
                    rows: summary.diagnostic_rows,
                    sampled_occurrences: summary.diagnostic_sampled_occurrences,
                    omitted_rows: summary
                        .diagnostic_rows
                        .saturating_sub(summary.diagnostic_samples.len() as u64),
                    groups: summary.diagnostic_groups.clone(),
                    groups_omitted: summary.diagnostic_groups_omitted,
                    sampled: summary.diagnostic_samples.len(),
                    omitted: summary
                        .diagnostic_total
                        .saturating_sub(summary.diagnostic_sampled_occurrences),
                },
            };
            let full = matches!(
                c.effective.config.observability.mode.as_str(),
                "full" | "detailed"
            );
            let report = ReportDocument {
                analysis_resources: self.analysis_admission_report(id),
                schema_version: 2,
                global: crate::reports::GlobalReport::from_capture(c, true),
                id: id.into(),
                created: now(),
                revision: revision.id.clone(),
                level: if full { "full" } else { "basic" }.into(),
                summary: report_summary,
                result: t.clone(),
                effective: ReportConfig::Basic {
                    config_digest: c.effective.config_digest.clone(),
                    origins: BTreeMap::new(),
                },
                context: worker_context,
                diagnostics: vec![],
            };
            let store = self.store.clone();
            let report_id = id.to_owned();
            let report_project = revision.project.clone();
            let report_revision = revision.id.clone();
            let saved = tokio::task::spawn_blocking(move || -> Result<()> {
                codeana_storage::test_fault(&store.home, "report_write")?;
                ensure!(
                    serde_json::to_vec(&report)?.len() <= 4 * 1024 * 1024,
                    codeana_model::DomainError::new(codeana_model::ErrorCode::ReportBudget, "")
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
}

pub(super) struct StreamRequest<'a> {
    pub manifest: &'a Manifest,
    pub revision: Revision,
    pub expected: &'a RevisionId,
    pub baseline: Option<&'a BaselineInput>,
    pub full: bool,
}

impl Engine {
    pub(super) async fn stage_language_stream(
        &self,
        id: &str,
        worker: &mut worker::Worker,
        request: StreamRequest<'_>,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, StagedCandidate)> {
        self.stage_language_stream_rows(id, worker, request, vec![], cancel)
            .await
    }
    pub(super) async fn stage_language_stream_rows(
        &self,
        id: &str,
        worker: &mut worker::Worker,
        request: StreamRequest<'_>,
        additional_rows: Vec<codeana_contracts::graph::Row>,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, StagedCandidate)> {
        let mut staging = None;
        let mut metrics = None;
        let profile = request.revision.profile.clone();
        let header = worker
            .analyze_into(
                request.manifest,
                &profile,
                request.expected,
                request.baseline,
                request.full,
                cancel,
                |header| {
                    let (sender, records) = codeana_contracts::stream::GraphStream::channel();
                    metrics = Some(sender.metrics.clone());
                    let store = self.index.clone().with_context(self.staging_context(id));
                    let revision = request.revision.clone();
                    let mut header = header.clone();
                    // The worker receipt binds its language view. Storage owns
                    // the complete revision, including captured resources.
                    header.manifest = revision.manifest.id();
                    header.producer = revision.producer.clone();
                    let additional = additional_rows.clone();
                    let baseline = request.expected.clone();
                    let cancellation = cancel.clone();
                    staging = Some(tokio::task::spawn_blocking(move || {
                        store.stage_graph_records(
                            revision,
                            &header,
                            records.chain(additional.into_iter().map(Ok)),
                            &baseline,
                            || {
                                ensure!(
                                    !cancellation.is_cancelled(),
                                    DomainError::new(ErrorCode::Cancelled, "")
                                );
                                Ok(())
                            },
                            |_| Ok(()),
                        )
                    }));
                    Ok(sender)
                },
            )
            .await;
        // Always join storage, including failed/cancelled delivery.
        let staged = match staging {
            Some(task) => Some(task.await?),
            None => None,
        };
        let (header, candidate) = match (header, staged) {
            (Err(transport), Some(Err(storage)))
                if transport
                    .downcast_ref::<DomainError>()
                    .is_some_and(|error| error.code == ErrorCode::CandidateStreamClosed) =>
            {
                return Err(storage);
            }
            (Err(error), _) => return Err(error),
            (Ok(header), Some(candidate)) => (header, candidate?),
            (Ok(_), None) => anyhow::bail!("CANDIDATE_STREAM_NOT_STARTED"),
        };
        if let Some(metrics) = metrics {
            self.tasks.event(
                &TaskId::from(id),
                &Event::Progress(format!(
                    "{}_stream {}",
                    worker.context.language,
                    serde_json::to_string(&metrics.summary())?
                )),
            )?;
        }
        Ok((header.mode, candidate))
    }
}

impl Engine {
    /// All End receipts are verified before a combined candidate can be staged.
    /// Used for any language set that requires a conservative full rebuild.
    #[allow(clippy::too_many_arguments)]
    pub(super) async fn stage_full_languages(
        &self,
        id: &str,
        sessions: &mut [(worker::Worker, Manifest)],
        revision: Revision,
        expected: &RevisionId,
        scratch: &Path,
        limits: codeana_contracts::graph_archive::OutputLimits,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, StagedCandidate)> {
        self.stage_full_language_rows(
            id,
            sessions,
            revision,
            expected,
            scratch,
            limits,
            vec![],
            cancel,
        )
        .await
    }
    #[allow(clippy::too_many_arguments)]
    pub(super) async fn stage_full_language_rows(
        &self,
        id: &str,
        sessions: &mut [(worker::Worker, Manifest)],
        revision: Revision,
        expected: &RevisionId,
        scratch: &Path,
        limits: codeana_contracts::graph_archive::OutputLimits,
        additional_rows: Vec<codeana_contracts::graph::Row>,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, StagedCandidate)> {
        let mut archives = Vec::new();
        let mut replacements = Vec::new();
        for (worker, manifest) in sessions {
            let result = worker
                .analyze(manifest, &revision.profile, expected, None, true, cancel)
                .await?;
            ensure!(
                result.header.mode == AnalysisMode::Full,
                "FULL_ANALYSIS_REQUIRED"
            );
            replacements.extend(result.header.replacements);
            archives.push(result.records);
        }
        replacements.sort();
        replacements.dedup();
        let header = AnalysisHeader {
            mode: AnalysisMode::Full,
            baseline: expected.to_string(),
            manifest: revision.manifest.id(),
            profile: revision.profile.clone(),
            producer: revision.producer.clone(),
            replacements,
        };
        let store = self.index.clone().with_context(self.staging_context(id));
        let expected = expected.clone();
        let cancel = cancel.clone();
        let scratch = scratch.to_path_buf();
        let candidate = tokio::task::spawn_blocking(move || -> Result<_> {
            // Worker output bounds have already been enforced independently.
            let mut archive = GraphArchiveBuilder::new(Some(&scratch), limits.bytes, limits.facts)?;
            for result in archives {
                for row in result.records()? {
                    ensure!(
                        !cancel.is_cancelled(),
                        DomainError::new(ErrorCode::Cancelled, "")
                    );
                    archive.push(&row?)?;
                }
            }
            for row in additional_rows {
                archive.push(&row)?;
            }
            let archive = archive.seal()?;
            store.stage_graph_records(
                revision,
                &header,
                archive.records()?,
                &expected,
                || {
                    ensure!(
                        !cancel.is_cancelled(),
                        DomainError::new(ErrorCode::Cancelled, "")
                    );
                    Ok(())
                },
                |_| Ok(()),
            )
        })
        .await??;
        Ok((AnalysisMode::Full, candidate))
    }
}
