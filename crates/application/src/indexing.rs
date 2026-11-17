//! Index phases own their worker/candidate handles; publication separates analysis from post-processing.
use super::*;
use crate::reports::{
    ReportConfig, ReportDiagnostics, ReportDocument, ReportSummary, ReportTiming,
};
pub(super) type StagedCandidate =
    <crate::ports::storage::IndexAdapter as IndexRepository>::Candidate;
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
    pub(super) fn revision_for_contexts(
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
        c: Capture,
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
        if self.workers.is_mock() {
            return self.index_fixture(id, req, c, &plan, cancel).await;
        }
        self.index_units(id, req, c, &plan, baseline.as_ref(), cancel)
            .await
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
