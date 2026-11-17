//! Index phases own their worker/candidate handles; publication separates analysis from post-processing.
use super::*;
use crate::reports::{
    ReportConfig, ReportDiagnostics, ReportDocument, ReportSummary, ReportTiming,
};
pub(super) type StagedCandidate =
    <crate::ports::storage::IndexAdapter as IndexRepository>::Candidate;
struct JavaAttempt {
    unit: crate::analysis_unit::RunningUnit,
    expected: RevisionId,
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
    pub(super) worker_contexts: Vec<BuildContext>,
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
        revision: Box<Revision>,
        started: Instant,
    },
    Rejected(Box<TaskResult>),
}

pub(super) struct IndexTask {
    pub index: IndexService<ports::storage::IndexAdapter>,
    pub task: task_runner::TaskContext,
    pub workers: Arc<worker_service::WorkerService>,
    pub reports: reports::ReportService,
    pub maintenance: maintenance::MaintenanceService,
}

impl IndexTask {
    fn publication_fence(&self, id: &str) -> Result<Arc<PublicationFence>> {
        ensure!(
            self.task.id.as_str() == id,
            DomainError::new(ErrorCode::TaskNotFound, "publication owner")
        );
        Ok(self.task.control.publication.clone())
    }
    fn analysis_admission_report(&self, _id: &str) -> Option<reports::AnalysisAdmissionReport> {
        self.task.analysis.analysis_admission_report()
    }

    pub(super) fn staging_context(&self, _id: &str) -> Option<GraphStagingContext> {
        let coordinator = self.task.analysis.coordinator.lock().unwrap().clone()?;
        let sources = self.task.analysis.staging_sources.lock().unwrap().clone()?;
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
    fn revision_for_contexts(
        &self,
        capture: &Capture,
        contexts: &[&BuildContext],
        profile: &str,
        producer: &str,
    ) -> Result<Revision> {
        Ok(Revision {
            unit_ownership: self.ownership(capture, contexts)?,
            id: String::new(),
            target: capture.target.clone(),
            project: capture.project.clone(),
            checkout: capture.checkout.clone(),
            manifest: capture.manifest.clone(),
            profile: profile.into(),
            producer: producer.into(),
            created: 0,
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
        disk: Option<crate::resources::DiskBudget>,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, StagedCandidate)> {
        ensure!(
            revision.producer == producer,
            DomainError::new(ErrorCode::ResultIdentityMismatch, "reuse producer")
        );
        let store = self.index.clone().with_context(self.staging_context(id));
        let cancellation = cancel.clone();
        let candidate = tokio::task::spawn_blocking(move || {
            store.stage_reused_graph(
                revision,
                &expected,
                || {
                    ensure!(
                        !cancellation.is_cancelled(),
                        DomainError::new(ErrorCode::Cancelled, "")
                    );
                    Ok(())
                },
                |path| {
                    if let Some(disk) = &disk {
                        disk.check_candidate(Some(path))?;
                    }
                    Ok(())
                },
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
                delivery: Default::default(),
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
            self.task
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
        Ok(CandidateOutcome::Committed {
            revision: Box::new(revision),
            started,
        })
    }

    pub(super) fn analysis_binding(
        &self,
        _id: &str,
        language: AnalysisBackend,
        instance: &str,
    ) -> Option<worker::AnalysisBinding> {
        self.task
            .analysis
            .coordinator
            .lock()
            .unwrap()
            .clone()
            .map(|coordinator| worker::AnalysisBinding {
                coordinator,
                language,
                instance: if instance.is_empty() {
                    language.as_str().into()
                } else {
                    instance.into()
                },
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
        if !self.workers.is_mock() {
            let user = codeana_config::load_user(self.index.home())?;
            let limits = c
                .effective
                .config
                .resources
                .index
                .analysis
                .resolve(&user.resources.index.analysis)?;
            let deadline = *self.task.control.deadline.lock().unwrap();
            let budget = AnalysisContext::new(limits, deadline)?;
            *self.task.analysis.admission.lock().unwrap() = Some((limits, budget.clone()));
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
            *self.task.analysis.staging_sources.lock().unwrap() = Some(Arc::new(c.blobs.clone()));
        }
        let baseline_head = self.index.head(&IndexTarget::from(c.target.clone()))?;
        ensure!(
            req.expected_revision.is_empty() || req.expected_revision == baseline_head.as_str(),
            DomainError::new(ErrorCode::HeadConflict, "")
        );
        let (baseline, _baseline_pin) = if baseline_head.is_empty() {
            (None, None)
        } else {
            let admission = self.task.analysis.admission.lock().unwrap().clone();
            let (revision, pin) = self
                .index
                .pin_baseline(&baseline_head, admission.as_ref().map(|(_, b)| b))?;
            (Some(revision), Some(pin))
        };
        if let Some(old) = baseline.as_ref().filter(|_| !self.workers.is_mock()) {
            match &old.unit_ownership {
                Some(ledger) if ledger.version == UNIT_OWNERSHIP_VERSION => {
                    ledger.validate(&old.manifest).map_err(anyhow::Error::msg)?
                }
                _ => ensure!(
                    req.full || c.effective.config.index.mode == "full",
                    DomainError::new(ErrorCode::UnitOwnershipRebuildRequired, "run index --full")
                ),
            }
        }
        let mut plan = {
            let admission = self.task.analysis.admission.lock().unwrap().clone();
            crate::index_plan::IndexPlan::with_baseline(
                &c,
                baseline
                    .as_ref()
                    .and_then(|r| r.unit_ownership.as_ref())
                    .filter(|l| l.version == UNIT_OWNERSHIP_VERSION),
                admission.as_ref().map(|(_, budget)| budget),
            )?
        };
        plan.baseline = baseline_head;
        if self.workers.is_mock()
            && let Some(old) = &baseline
        {
            plan.fixture_sources = old.manifest.files.clone();
        }
        ensure!(
            self.workers.is_mock()
                || plan
                    .units
                    .iter()
                    .any(|u| u.availability == UnitAvailability::Ready)
                || !c.effective.input_units.is_empty()
                || baseline.is_some(),
            DomainError::new(
                ErrorCode::NoEnabledLanguage,
                "semantic indexing requires an enabled language; existing units can be removed without workers"
            )
        );
        for removed in plan.removed() {
            self.task.event(
                &TaskId::from(id),
                &Event::Progress(format!(
                    "unit_removed {} sources={}",
                    removed.unit,
                    removed.sources.len()
                )),
            )?;
        }
        if !self.workers.is_mock() {
            let participants = &plan.participants;
            if !participants.is_empty() {
                {
                    let admission = self.task.analysis.admission.lock().unwrap();
                    if let Some((limits, budget)) = admission.as_ref() {
                        let mut coordinator = AnalysisCoordinator::new(
                            id,
                            *limits,
                            budget.usage(),
                            participants,
                            *self.task.control.deadline.lock().unwrap(),
                        )
                        .map_err(|failure| {
                            budget.fail(failure.clone());
                            DomainError::analysis(failure)
                        })?;
                        plan.configure(&mut coordinator)?;
                        *self.task.analysis.coordinator.lock().unwrap() =
                            Some(Arc::new(Mutex::new(coordinator)));
                    }
                }
            }
        }
        use crate::index_plan::ExecutionPolicy;
        if !self.workers.is_mock()
            && !plan
                .units
                .iter()
                .any(|u| u.availability == UnitAvailability::Ready)
        {
            return self.index_prepared(id, req, c, &plan, cancel).await;
        }
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
        let mut setup = self
            .prepare_java_attempt(id, req, &c, &plan, cancel, &mut timings)
            .await?;
        let AnalyzedCandidate {
            result_mode,
            candidate,
            analyze_ms,
        } = match self
            .analyze_java_attempt(id, &c, cancel, &mut timings, &mut setup)
            .await
        {
            Ok(analyzed) => analyzed,
            Err(error) => {
                if let Err(cleanup) = setup.unit.shutdown().await {
                    eprintln!("java_analysis_cleanup_warning: {cleanup:#}");
                }
                return Err(error);
            }
        };
        let JavaAttempt {
            mut unit,
            unchanged,
            ..
        } = setup;
        let result: Result<TaskResult> = async {
            let (worker, _, input, _) = unit.analysis()?;
            let prepared = input.java();
            let limits = prepared
                .as_ref()
                .map(|p| codeana_contracts::java::output_limits(&p.config))
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
                CandidateOutcome::Committed { revision, started } => (*revision, started),
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
                crate::analysis_unit::acknowledge(vec![&mut *worker], &revision.id).await
            };
            let worker_contexts = vec![worker.context.clone()];
            if let Some(timings) = &mut timings {
                timings.phase("ack_finished");
            }
            if ack.is_none() {
                unit.park(&self.workers, &revision.id);
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
                    worker_contexts,
                    analyze_ms,
                    publish_ms,
                    index_elapsed_ms: started.elapsed().as_millis() as u64,
                },
            )
            .await
        }
        .await;
        if let Err(cleanup) = unit.shutdown().await {
            eprintln!("java_index_cleanup_warning: {cleanup:#}");
        }
        result
    }
    async fn prepare_java_attempt(
        &self,
        id: &str,
        req: &IndexCommand,
        c: &Capture,
        plan: &crate::index_plan::IndexPlan,
        cancel: &CancellationToken,
        timings: &mut Option<timings::IndexTimings>,
    ) -> Result<JavaAttempt> {
        codeana_config::require_java(c)?;
        let selected = c.backend_capture(AnalysisBackend::Java)?;
        let input = if !self.workers.is_mock() {
            self.reserve_owned_input(id, AnalysisBackend::Java, "")?;
            crate::analysis_unit::PreparedInput::Java(Box::new(
                self.prepare_java_unit(selected.clone()).await?,
            ))
        } else {
            crate::analysis_unit::PreparedInput::Fixture(
                self.workers
                    .mock_fixtures()
                    .context("MOCK_FIXTURES_REQUIRED")?
                    .to_owned(),
            )
        };
        let manifest = self.own_unit_manifest(id, AnalysisBackend::Java, "", &selected.manifest)?;
        let mut unit = crate::analysis_unit::RunningUnit::prepared(input, selected, manifest)?;
        let c = unit.capture();
        if let Some(timings) = timings {
            timings.phase("prepared");
        }
        let expected = plan.baseline.clone();
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
            .unwrap_or(self.workers.memory_cap());
        ensure!(
            memory > 0 && memory <= self.workers.memory_cap(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::ProjectWorkerMemoryLimit, "")
        );
        unit.start(
            &self.workers,
            cancel,
            memory,
            self.analysis_binding(id, AnalysisBackend::Java, ""),
            plan.previous_sources("java"),
            expected.as_str(),
        )
        .await?;
        let (worker, _, input, c) = unit.analysis()?;
        let prepared = input.java();
        let validated: Result<_> = async {
            ensure!(
                worker.context.backend() == AnalysisBackend::Java,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::WorkerLanguageMismatch,
                    ""
                )
            );
            for module in &c.effective.config.modules {
                if module.language == "java" {
                    if let Some(version) = &module.analysis.source_version {
                        ensure!(
                            version == "auto"
                                || version
                                    == worker
                                        .context
                                        .java_source_version()
                                        .context("JAVA_CONTEXT_REQUIRED")?,
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::SourceVersionUnsupported,
                                ""
                            )
                        );
                    }
                    if self.workers.is_mock()
                        && let Some(toolchain) = &module.analysis.toolchain
                    {
                        ensure!(
                            toolchain == "auto"
                                || toolchain
                                    == &format!(
                                        "mock-jdk{}",
                                        worker
                                            .context
                                            .java_source_version()
                                            .context("JAVA_CONTEXT_REQUIRED")?
                                    ),
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
                codeana_graph_io::records::POLICY_VERSION,
                &c.effective.semantic_digest,
                &worker.context.semantic_digest,
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
                        let previous =
                            reader
                                .revision()
                                .unit_ownership
                                .as_ref()
                                .and_then(|ledger| {
                                    ledger.units.iter().find(|unit| unit.unit == "java")
                                });
                        let same = previous.is_some_and(|unit| {
                            codeana_model::same_semantic_sources(&unit.inputs, &desired_files.files)
                        });
                        let summary = if profile != desired_profile || same {
                            None
                        } else {
                            let summary = reader.baseline_summary_for(
                                &desired_files,
                                &AnalysisUnitId::from("java"),
                            )?;
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
                if let Err(cleanup) = unit.shutdown().await {
                    eprintln!("java_setup_cleanup_warning: {cleanup:#}");
                }
                return Err(error);
            }
        };
        Ok(JavaAttempt {
            unit,
            expected,
            profile,
            full,
            old,
            unchanged,
        })
    }
    async fn analyze_java_attempt(
        &self,
        id: &str,
        c: &Capture,
        cancel: &CancellationToken,
        timings: &mut Option<timings::IndexTimings>,
        setup: &mut JavaAttempt,
    ) -> Result<AnalyzedCandidate> {
        let (worker, unit_manifest, input, _) = setup.unit.analysis()?;
        let origin = worker.record_origin()?;
        let prepared = input.java();
        let expected = setup.expected.clone();
        let profile = setup.profile.clone();
        let full = setup.full;
        let old = &setup.old;
        let unchanged = setup.unchanged;
        let analyzing = Instant::now();
        let streaming = prepared.is_some() && !unchanged;
        let analyze_ms;
        let (result_mode, candidate) = if streaming {
            let store = self.index.clone().with_context(self.staging_context(id));
            let revision = self.revision_for_contexts(
                c,
                &[&worker.context],
                &profile,
                &worker.context.producer,
            )?;
            let baseline_id = expected.clone();
            let staging_cancel = cancel.clone();
            let candidate_disk = prepared.as_ref().map(|p| p.disk_budget.clone());
            let mut candidate_task = None;
            let header = worker
                .analyze_into(
                    unit_manifest,
                    &profile,
                    &expected,
                    old.as_ref(),
                    full,
                    cancel,
                    |header| {
                        let (sender, records) = codeana_graph_io::stream::GraphStream::channel();
                        let store = store.clone();
                        let revision = revision.clone();
                        let mut header = header.clone();
                        header.manifest = revision.manifest.id();
                        let baseline_id = baseline_id.clone();
                        let staging_cancel = staging_cancel.clone();
                        let candidate_disk = candidate_disk.clone();
                        let origin = origin.clone();
                        candidate_task = Some(tokio::task::spawn_blocking(move || {
                            let diagnostics =
                                codeana_contracts::performance::Report::new("storage-detail");
                            diagnostics.capture.sync(|| {
                                store.stage_graph_records(
                                    revision,
                                    &header,
                                    records.map(|r| r.map(|row| origin.record(row))),
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
        } else if unchanged {
            worker.reuse_java().await?;
            if let Some(prepared) = prepared {
                prepared.verify()?;
            }
            let revision = self.revision_for_contexts(
                c,
                &[&worker.context],
                &profile,
                &worker.context.producer,
            )?;
            analyze_ms = analyzing.elapsed().as_millis() as u64;
            if let Some(timings) = timings {
                timings.phase("analyzed");
            }
            self.stage_reused_revision(
                id,
                revision,
                expected.clone(),
                worker.context.producer.clone(),
                prepared.as_ref().map(|p| p.disk_budget.clone()),
                cancel,
            )
            .await?
        } else {
            let mut result = worker
                .analyze(
                    unit_manifest,
                    &profile,
                    &expected,
                    old.as_ref(),
                    full,
                    cancel,
                )
                .await?;

            analyze_ms = analyzing.elapsed().as_millis() as u64;
            if let Some(timings) = timings {
                timings.phase("analyzed");
            }
            if let Some(prepared) = &prepared {
                prepared.verify()?;
            }
            let result_mode = result.header.mode;
            let revision = self.revision_for_contexts(
                c,
                &[&worker.context],
                &profile,
                &result.header.producer,
            )?;
            let store = self.index.clone().with_context(self.staging_context(id));
            let baseline_id = expected.clone();
            let staging_cancel = cancel.clone();
            let candidate_disk = prepared.as_ref().map(|p| p.disk_budget.clone());
            result.header.manifest = revision.manifest.id();
            let candidate = tokio::task::spawn_blocking(move || {
                let diagnostics = codeana_contracts::performance::Report::new("storage-detail");
                diagnostics.capture.sync(|| {
                    store.stage_graph_records(
                        revision,
                        &result.header,
                        result
                            .records
                            .records()?
                            .map(|r| r.map(|row| origin.record(row))),
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
            worker_contexts,
            analyze_ms,
            publish_ms,
            index_elapsed_ms,
        } = published;
        let mut t = TaskResult::success(Output {
            delivery: Default::default(),
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
                schema_version: 4,
                global: crate::reports::GlobalReport::from_capture(c, true),
                id: id.into(),
                created: now(),
                revision: revision.id.clone(),
                level: if full { "full" } else { "basic" }.into(),
                summary: report_summary,
                result: t.clone(),
                effective: ReportConfig {
                    config_digest: c.effective.config_digest.clone(),
                    origins: BTreeMap::new(),
                },
                contexts: worker_contexts,
                diagnostics: vec![],
            };
            let saved = self
                .reports
                .save_published(report, revision.project.clone())
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
        if policy.auto.unwrap_or(true)
            && let Err(e) = self
                .maintenance
                .after_publish(revision.project.clone(), policy)
                .await?
        {
            t.message = format!("Published; GC pending: {e}");
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

impl IndexTask {
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
        let origin = worker.record_origin()?;
        let header = worker
            .analyze_into(
                request.manifest,
                &profile,
                request.expected,
                request.baseline,
                request.full,
                cancel,
                |header| {
                    let (sender, records) = codeana_graph_io::stream::GraphStream::channel();
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
                    let origin = origin.clone();
                    staging = Some(tokio::task::spawn_blocking(move || {
                        store.stage_graph_records(
                            revision,
                            &header,
                            records.map(|r| r.map(|row| origin.record(row))).chain(
                                additional.into_iter().map(|row| {
                                    Ok(codeana_graph_io::records::Origin::Host.record(row))
                                }),
                            ),
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
            self.task.event(
                &TaskId::from(id),
                &Event::Progress(format!(
                    "{}_stream {}",
                    worker.context.backend().as_str(),
                    serde_json::to_string(&metrics.summary())?
                )),
            )?;
        }
        Ok((header.mode, candidate))
    }
}

impl IndexTask {
    /// All End receipts are verified before a combined candidate can be staged.
    /// Used for any language set that requires a conservative full rebuild.
    #[allow(clippy::too_many_arguments)]
    pub(super) async fn stage_full_languages(
        &self,
        id: &str,
        sessions: &mut [crate::analysis_unit::RunningUnit],
        revision: Revision,
        expected: &RevisionId,
        limits: codeana_contracts::graph_delivery::OutputLimits,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, StagedCandidate)> {
        self.stage_full_language_rows(id, sessions, revision, expected, limits, vec![], cancel)
            .await
    }
    #[allow(clippy::too_many_arguments)]
    pub(super) async fn stage_full_language_rows(
        &self,
        id: &str,
        sessions: &mut [crate::analysis_unit::RunningUnit],
        revision: Revision,
        expected: &RevisionId,
        limits: codeana_contracts::graph_delivery::OutputLimits,
        additional_rows: Vec<codeana_contracts::graph::Row>,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, StagedCandidate)> {
        let mut archives = Vec::new();
        let mut replacements = Vec::new();
        let staging_context = self.staging_context(id);
        for unit in sessions {
            let (worker, manifest) = unit.stream()?;
            let origin = worker.record_origin()?;
            let budget = staging_context
                .as_ref()
                .and_then(|c| c.hosts.get(&worker.context.backend()))
                .cloned();
            let result = worker
                .analyze(manifest, &revision.profile, expected, None, true, cancel)
                .await?;
            ensure!(
                result.header.mode == AnalysisMode::Full,
                "FULL_ANALYSIS_REQUIRED"
            );
            replacements.extend(result.header.replacements);
            archives.push(codeana_graph_io::records::ArchiveInput {
                origin,
                archive: result.records,
                budget,
            });
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
        let store = self.index.clone().with_context(staging_context);
        let expected = expected.clone();
        let cancel = cancel.clone();
        let candidate = tokio::task::spawn_blocking(move || -> Result<_> {
            let records =
                codeana_graph_io::records::Archives::new(archives, additional_rows, limits)?;
            store.stage_graph_records(
                revision,
                &header,
                records,
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
