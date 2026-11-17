//! One task executor: backend policy chooses each unit, then one candidate is published.
use super::*;
use crate::analysis_unit::{PreparedInput, RunningUnit, RunningUnits};
use crate::indexing::PublishedIndex;
use crate::ports::RustBaselineReader;
use crate::rust_inputs::RustInput;
use codeana_contracts::graph_rows::{CoverageRow, GraphRow, ProjectMetaRow};
use codeana_graph_io::records::{ArchiveInput, Archives, Origin};
use std::collections::BTreeSet;

impl IndexTask {
    async fn stop_running_units(&self, id: &str, units: &mut crate::analysis_unit::RunningUnits) {
        if let Some(warning) = crate::analysis_unit::shutdown(units.workers_mut()).await {
            let _ = self.task.event(
                &TaskId::from(id),
                &Event::Progress(format!("worker_cleanup_warning {warning}")),
            );
        }
    }

    async fn finish_running_units(
        &self,
        id: &str,
        units: &mut crate::analysis_unit::RunningUnits,
        result: Result<TaskResult>,
    ) -> Result<TaskResult> {
        self.stop_running_units(id, units).await;
        result
    }

    pub(super) async fn prepare_java_unit(&self, captured: Capture) -> Result<java::PreparedJava> {
        self.workers
            .prepare_java(captured.backend_capture(AnalysisBackend::Java)?)
            .await
    }

    async fn prepare_analysis_unit(
        &self,
        request: crate::analysis_unit::Preparation<'_>,
    ) -> Result<crate::analysis_unit::PreparedInput> {
        use crate::analysis_unit::PreparedInput;
        self.reserve_owned_input(request.task, request.language, request.instance)?;
        Ok(match request.language {
            AnalysisBackend::TypeScript => bail!("TYPESCRIPT_INPUT_NOT_READY"),
            AnalysisBackend::Java => PreparedInput::Java(Box::new(
                self.prepare_java_unit(request.capture.clone()).await?,
            )),
            AnalysisBackend::Rust => PreparedInput::Rust(Box::new(
                self.prepare_rust_inputs(request.task, request.capture)
                    .await?,
            )),
            AnalysisBackend::Python => {
                let captured = request.capture.clone();
                let directory = request.python_directory.to_path_buf();
                let mode = request.python_mode;
                let input = tokio::task::spawn_blocking(move || {
                    crate::python_inputs::prepare_inputs(&captured, &directory, mode)
                })
                .await??;
                PreparedInput::Python(input, request.python_guard.clone())
            }
        })
    }

    pub(super) fn own_unit_manifest(
        &self,
        id: &str,
        backend: AnalysisBackend,
        instance: &str,
        manifest: &Manifest,
    ) -> Result<Manifest> {
        if let Some(binding) = self.analysis_binding(id, backend, instance) {
            let host = binding
                .coordinator
                .lock()
                .unwrap()
                .host(backend)
                .map_err(DomainError::analysis)?;
            codeana_contracts::json_budget::clone_with_budget(manifest, &host)
        } else {
            Ok(manifest.clone())
        }
    }

    pub(super) fn reserve_owned_input(
        &self,
        id: &str,
        language: AnalysisBackend,
        instance: &str,
    ) -> Result<()> {
        if let Some(binding) = self.analysis_binding(id, language, instance) {
            let host = binding
                .coordinator
                .lock()
                .unwrap()
                .host(language)
                .map_err(DomainError::analysis)?;
            let bytes = match language {
                AnalysisBackend::Java => std::mem::size_of::<java::PreparedJava>(),
                AnalysisBackend::Rust => std::mem::size_of::<RustInput>(),
                AnalysisBackend::Python => 0,
                AnalysisBackend::TypeScript => bail!("TYPESCRIPT_INPUT_NOT_READY"),
            };
            host.charge(1, 0, bytes as u64)
                .map_err(DomainError::analysis)?;
        }
        Ok(())
    }

    pub(super) async fn index_units(
        &self,
        id: &str,
        req: &IndexCommand,
        mut capture: Capture,
        plan: &crate::index_plan::IndexPlan,
        baseline: Option<&Revision>,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let started = Instant::now();
        let mut timings = timings::IndexTimings::start(id);
        let scratch = Arc::new(
            tempfile::Builder::new()
                .prefix("analysis-index-")
                .tempdir_in(self.index.home())?,
        );
        let mut sessions = RunningUnits {
            units: vec![],
            scratch,
        };
        let result: Result<TaskResult> = async {
            let count = plan
                .units
                .iter()
                .filter(|u| u.availability == UnitAvailability::Ready)
                .count()
                .max(1) as u64;
            let memory = capture
                .effective
                .config
                .resources
                .index
                .worker_memory_bytes
                .unwrap_or(self.workers.memory_cap());
            ensure!(
                memory > 0 && memory <= self.workers.memory_cap(),
                DomainError::new(ErrorCode::ProjectWorkerMemoryLimit, "")
            );
            let memory = memory / count;
            ensure!(memory > 0, "WORKER_MEMORY_BUDGET");
            for unit in plan
                .units
                .iter()
                .filter(|u| u.availability == UnitAvailability::Ready)
            {
                let memory = if capture
                    .effective
                    .config
                    .resources
                    .index
                    .worker_memory_bytes
                    .is_none()
                    && unit.language == AnalysisBackend::Rust
                {
                    memory.min(codeana_model::rust::DEFAULT_WORKER_MEMORY)
                } else {
                    memory
                };
                let mut child = capture.unit_capture(&unit.id)?;
                child.effective.set_worker_memory_limit(memory);
                let directory = sessions.scratch.path().join(unit.id.as_str());
                std::fs::create_dir(&directory)?;
                let input = self
                    .prepare_analysis_unit(crate::analysis_unit::Preparation {
                        task: id,
                        instance: &unit.id,
                        language: unit.language,
                        capture: &child,
                        python_directory: &directory,
                        python_guard: sessions.scratch.clone(),
                        python_mode: if unit.prepared_index.is_some() {
                            crate::python_inputs::InputMode::Prepared
                        } else {
                            crate::python_inputs::InputMode::Standard
                        },
                    })
                    .await?;
                let manifest = self.own_unit_manifest(
                    id,
                    unit.language,
                    &unit.id,
                    match &input {
                        PreparedInput::Rust(input) => &input.manifest,
                        _ => &child.manifest,
                    },
                )?;
                sessions
                    .units
                    .push(RunningUnit::prepared(input, child, manifest)?);
                let running = sessions.units.last_mut().context("ANALYSIS_UNIT_MISSING")?;
                running
                    .start(
                        &self.workers,
                        cancel,
                        memory,
                        self.analysis_binding(id, unit.language, &unit.id),
                        plan.previous_sources(&unit.id),
                        plan.baseline.as_str(),
                    )
                    .await?;
                validate_java_context(running, false)?;
            }
            if let Some(timings) = &mut timings {
                timings.phase("worker_context");
            }
            let contexts = sessions
                .units
                .iter()
                .map(|u| u.worker().map(|w| &w.context))
                .collect::<Result<Vec<_>>>()?;
            let ownership = self
                .ownership(&capture, &contexts)?
                .context("UNIT_OWNERSHIP_REQUIRED")?;
            let producer = if contexts.len() == 1 {
                contexts[0].producer.clone()
            } else {
                "codeana-multi-language".into()
            };
            let profile = digest(&(
                codeana_comment_search::identity(),
                codeana_graph_io::records::POLICY_VERSION,
                "unit-execution-v1",
                &capture.effective.semantic_digest,
                ownership
                    .units
                    .iter()
                    .map(|u| (&u.unit, &u.semantic_profile))
                    .collect::<Vec<_>>(),
            ));
            let revision = Revision {
                unit_ownership: Some(ownership),
                id: String::new(),
                target: capture.target.clone(),
                project: capture.project.clone(),
                checkout: capture.checkout.clone(),
                manifest: capture.manifest.clone(),
                profile,
                producer: producer.clone(),
                created: 0,
            };
            let empty = Revision {
                unit_ownership: Some(UnitOwnershipLedger::default()),
                manifest: Manifest::default(),
                id: String::new(),
                profile: String::new(),
                producer: String::new(),
                target: revision.target.clone(),
                project: revision.project.clone(),
                checkout: revision.checkout.clone(),
                created: 0,
            };
            let before = baseline
                .filter(|r| {
                    r.unit_ownership
                        .as_ref()
                        .is_some_and(|l| l.version == UNIT_OWNERSHIP_VERSION)
                })
                .unwrap_or(&empty);
            let staging_context = self.staging_context(id);
            let updates = UnitMergePlan::select_updates(
                before,
                &revision,
                req.full || capture.effective.config.index.mode == "full",
                &[AnalysisBackend::Java, AnalysisBackend::Rust],
                staging_context.as_ref().map(|c| &c.hosts),
            )?;
            let mut updates: BTreeMap<_, _> =
                updates.into_iter().map(|u| (u.unit.clone(), u)).collect();
            let mut additional = availability_rows(
                &capture,
                plan,
                staging_context
                    .as_ref()
                    .and_then(|c| c.hosts.values().next()),
            )?;
            for update in updates.values() {
                self.task.event(
                    &TaskId::from(id),
                    &Event::Progress(format!(
                        "unit_update {} {}",
                        update.unit,
                        update.kind.as_str()
                    )),
                )?;
            }
            let analyzing = Instant::now();
            let disks = sessions
                .units
                .iter()
                .filter_map(|u| u.worker().ok().and_then(|w| w.candidate_disk()))
                .collect::<Vec<_>>();
            for unit in &mut sessions.units {
                if updates[&unit.unit].kind == UnitUpdateKind::Reuse {
                    let (worker, _) = unit.stream()?;
                    if worker.context.backend() == AnalysisBackend::Java {
                        worker.reuse_java().await?;
                    } else {
                        worker.retire_unused().await?;
                    }
                    self.unit_result(id, &worker.context.unit, UnitUpdateKind::Reuse)?;
                }
            }
            let executed: BTreeSet<_> = sessions
                .units
                .iter()
                .filter(|u| {
                    matches!(
                        updates[&u.unit].kind,
                        UnitUpdateKind::Full | UnitUpdateKind::Delta
                    )
                })
                .map(|u| u.unit.clone())
                .collect();
            let executing = executed.len();
            let (result_mode, candidate) = if executing == 1 {
                let unit = sessions
                    .units
                    .iter_mut()
                    .find(|u| {
                        matches!(
                            updates[&u.unit].kind,
                            UnitUpdateKind::Full | UnitUpdateKind::Delta
                        )
                    })
                    .context("ANALYSIS_UNIT_MISSING")?;
                let update = &updates[&unit.unit];
                let old = if update.kind == UnitUpdateKind::Delta {
                    self.unit_baseline(id, unit, &plan.baseline).await?
                } else {
                    None
                };
                let unit_profile = revision
                    .unit_ownership
                    .as_ref()
                    .unwrap()
                    .units
                    .iter()
                    .find(|u| u.unit == unit.unit)
                    .context("UNIT_OWNERSHIP_REQUIRED")?
                    .semantic_profile
                    .clone();
                self.stream_unit(
                    id,
                    unit,
                    revision,
                    updates.into_values().collect(),
                    &unit_profile,
                    old.as_ref(),
                    &plan.baseline,
                    std::mem::take(&mut additional),
                    disks,
                    cancel,
                )
                .await?
            } else {
                let mut archives = vec![];
                let mut limits = codeana_contracts::graph_delivery::OutputLimits {
                    bytes: 4,
                    facts: additional.len() as u64,
                };
                for row in &additional {
                    limits.bytes = limits
                        .bytes
                        .saturating_add(prost::Message::encoded_len(row) as u64 + 4);
                }
                for unit in &mut sessions.units {
                    let update = updates
                        .get_mut(&unit.unit)
                        .context("ANALYSIS_UNIT_MISSING")?;
                    if !matches!(update.kind, UnitUpdateKind::Full | UnitUpdateKind::Delta) {
                        continue;
                    }
                    let old = if update.kind == UnitUpdateKind::Delta {
                        self.unit_baseline(id, unit, &plan.baseline).await?
                    } else {
                        None
                    };
                    let unit_profile = &revision
                        .unit_ownership
                        .as_ref()
                        .unwrap()
                        .units
                        .iter()
                        .find(|u| u.unit == unit.unit)
                        .context("UNIT_OWNERSHIP_REQUIRED")?
                        .semantic_profile;
                    let (worker, manifest) = unit.stream()?;
                    let bound = worker.delivery_limits();
                    limits.bytes = limits.bytes.saturating_add(bound.bytes);
                    limits.facts = limits.facts.saturating_add(bound.facts);
                    let result = worker
                        .analyze(
                            manifest,
                            unit_profile,
                            &plan.baseline,
                            old.as_ref(),
                            update.kind == UnitUpdateKind::Full,
                            cancel,
                        )
                        .await?;
                    record_outcome(update, &result.header)?;
                    self.unit_result(id, &update.unit, update.kind)?;
                    archives.push(ArchiveInput {
                        origin: worker.record_origin()?,
                        archive: result.records,
                        budget: staging_context
                            .as_ref()
                            .and_then(|c| c.hosts.get(&unit.backend()))
                            .cloned(),
                    });
                    unit.input.verify()?;
                }
                let store = self.index.clone().with_context(staging_context);
                let expected = plan.baseline.clone();
                let cancel = cancel.clone();
                tokio::task::spawn_blocking(move || {
                    stage_outcomes(
                        store,
                        revision,
                        updates.into_values().collect(),
                        Archives::new(archives, additional, limits)?,
                        &expected,
                        &cancel,
                        &disks,
                    )
                })
                .await??
            };
            for unit in plan
                .units
                .iter()
                .filter(|u| u.availability != UnitAvailability::Ready)
            {
                self.unit_result(
                    id,
                    &unit.id,
                    match unit.availability {
                        UnitAvailability::Removed => UnitUpdateKind::Removed,
                        UnitAvailability::FilesOnly => UnitUpdateKind::FilesOnly,
                        UnitAvailability::Ready => unreachable!(),
                    },
                )?;
            }
            for unit in &sessions.units {
                unit.input.verify()?;
            }
            let analyze_ms = analyzing.elapsed().as_millis() as u64;
            let summary = self.index.summary(&candidate).clone();
            if let Some(timings) = &mut timings {
                timings.phase("candidate_validated");
                timings.storage(&summary.phase_ms);
            }
            let evidence = summary.evidence.clone();
            let worker_contexts = sessions.contexts()?;
            let (revision, publishing) = match self
                .publish_candidate(
                    id,
                    req,
                    &mut capture,
                    crate::indexing::CandidatePublication {
                        candidate,
                        producer: &producer,
                        progress: None,
                    },
                    cancel,
                )
                .await?
            {
                crate::indexing::CandidateOutcome::Committed { revision, started } => {
                    (*revision, started)
                }
                crate::indexing::CandidateOutcome::Rejected(result) => return Ok(*result),
            };
            if let Some(timings) = &mut timings {
                timings.phase("published");
            }
            let publish_ms = publishing.elapsed().as_millis() as u64;
            let mut ack = None;
            for unit in &mut sessions.units {
                let was_executed = executed.contains(&unit.unit);
                let (worker, _) = unit.stream()?;
                // Reused receipts release their account; acknowledged workers do too.
                // A non-Java reused worker has already exited and needs no Ack.
                if was_executed && let Err(error) = worker.ack(&revision.id).await {
                    ack = Some(error.to_string());
                    continue;
                }
                unit.park(&self.workers, &revision.id);
            }
            self.finish_index(
                id,
                &capture,
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
        self.finish_running_units(id, &mut sessions, result).await
    }

    /// Explicit development fixtures use the same owner and publication boundary,
    /// but never claim a real backend receipt or a production unit ledger.
    pub(super) async fn index_fixture(
        &self,
        id: &str,
        req: &IndexCommand,
        mut capture: Capture,
        plan: &crate::index_plan::IndexPlan,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        ensure!(self.workers.is_mock(), "MOCK_FIXTURES_REQUIRED");
        let started = Instant::now();
        let selected = capture.backend_capture(AnalysisBackend::Java)?;
        let manifest = selected.manifest.clone();
        let input = PreparedInput::Fixture(
            self.workers
                .mock_fixtures()
                .context("MOCK_FIXTURES_REQUIRED")?
                .to_owned(),
        );
        let mut unit = RunningUnit::prepared(input, selected, manifest)?;
        let result: Result<TaskResult> = async {
            let memory = capture
                .effective
                .config
                .resources
                .index
                .worker_memory_bytes
                .unwrap_or(self.workers.memory_cap());
            ensure!(
                memory > 0 && memory <= self.workers.memory_cap(),
                DomainError::new(ErrorCode::ProjectWorkerMemoryLimit, "")
            );
            unit.start(
                &self.workers,
                cancel,
                memory,
                None,
                plan.previous_sources("java"),
                &plan.baseline,
            )
            .await?;
            validate_java_context(&unit, true)?;
            let context = unit.worker()?.context.clone();
            let profile = digest(&(
                codeana_comment_search::identity(),
                codeana_graph_io::records::POLICY_VERSION,
                &unit.capture().effective.semantic_digest,
                &context.semantic_digest,
                "fixture-execution-v1",
            ));
            let mut full = req.full
                || capture.effective.config.index.mode == "full"
                || plan.baseline.is_empty();
            let old = if !full {
                let reader = self.index.read(&plan.baseline)?;
                if reader.revision().profile == profile {
                    Some(BaselineInput::Inline(
                        reader.baseline_summary_for(&unit.manifest, &unit.unit)?,
                    ))
                } else {
                    full = true;
                    None
                }
            } else {
                None
            };
            let analyzing = Instant::now();
            let (worker, manifest) = unit.stream()?;
            let origin = worker.record_origin()?;
            let mut result = worker
                .analyze(
                    manifest,
                    &profile,
                    &plan.baseline,
                    old.as_ref(),
                    full,
                    cancel,
                )
                .await?;
            let result_mode = result.header.mode;
            let revision =
                self.revision_for_contexts(&capture, &[&context], &profile, &context.producer)?;
            result.header.manifest = revision.manifest.id();
            let store = self.index.clone();
            let expected = plan.baseline.clone();
            let cancelled = cancel.clone();
            let candidate = tokio::task::spawn_blocking(move || {
                store.stage_graph_records(
                    revision,
                    &result.header,
                    result
                        .records
                        .records()?
                        .map(|r| r.map(|row| origin.record(row))),
                    &expected,
                    || {
                        ensure!(
                            !cancelled.is_cancelled(),
                            DomainError::new(ErrorCode::Cancelled, "")
                        );
                        Ok(())
                    },
                    |_| Ok(()),
                )
            })
            .await??;
            let analyze_ms = analyzing.elapsed().as_millis() as u64;
            let summary = self.index.summary(&candidate).clone();
            let evidence = summary.evidence.clone();
            let (revision, publishing) = match self
                .publish_candidate(
                    id,
                    req,
                    &mut capture,
                    crate::indexing::CandidatePublication {
                        candidate,
                        producer: &context.producer,
                        progress: None,
                    },
                    cancel,
                )
                .await?
            {
                crate::indexing::CandidateOutcome::Committed { revision, started } => {
                    (*revision, started)
                }
                crate::indexing::CandidateOutcome::Rejected(result) => return Ok(*result),
            };
            let publish_ms = publishing.elapsed().as_millis() as u64;
            let ack = worker.ack(&revision.id).await.err().map(|e| e.to_string());
            self.finish_index(
                id,
                &capture,
                PublishedIndex {
                    revision,
                    result_mode,
                    summary,
                    evidence,
                    ack,
                    worker_contexts: vec![context],
                    analyze_ms,
                    publish_ms,
                    index_elapsed_ms: started.elapsed().as_millis() as u64,
                },
            )
            .await
        }
        .await;
        if let Err(error) = unit.shutdown().await {
            let _ = self.task.event(
                &TaskId::from(id),
                &Event::Progress(format!("worker_cleanup_warning {error}")),
            );
        }
        result
    }

    fn unit_result(&self, id: &str, unit: &AnalysisUnitId, kind: UnitUpdateKind) -> Result<()> {
        self.task.event(
            &TaskId::from(id),
            &Event::Progress(format!("unit_result {unit} {}", kind.as_str())),
        )?;
        Ok(())
    }

    async fn unit_baseline(
        &self,
        id: &str,
        unit: &RunningUnit,
        expected: &RevisionId,
    ) -> Result<Option<BaselineInput>> {
        let store = self.index.clone().with_context(self.staging_context(id));
        let expected = expected.clone();
        let owner = unit.unit.clone();
        let backend = unit.backend();
        let manifest = unit.manifest.clone();
        let directory = unit
            .input
            .directories()
            .into_iter()
            .next()
            .context("UNIT_BASELINE_DIRECTORY_REQUIRED")?;
        let budget = self
            .staging_context(id)
            .and_then(|c| c.hosts.get(&backend).cloned());
        tokio::task::spawn_blocking(move || {
            let reader = store.read(&expected)?;
            let summary = match backend {
                AnalysisBackend::Java => {
                    let mut summary = reader.baseline_summary_for(&manifest, &owner)?;
                    // Java consumes `files` only as deletion owners. Controls and
                    // read-only bindings remain in the frozen input/node context,
                    // but can never authorize removal of a published source range.
                    summary
                        .files
                        .retain(|s| publication_language(s) == Some(SourceLanguage::Java));
                    summary
                }
                AnalysisBackend::Rust => {
                    RustBaselineReader::rust_baseline_summary_for(&reader, &owner)?
                }
                _ => bail!("UNIT_INCREMENTAL_BASELINE_UNSUPPORTED"),
            };
            if let Some(budget) = budget {
                budget
                    .charge(1, 0, retained_size(&summary)?.saturating_mul(3))
                    .map_err(DomainError::analysis)?;
            }
            let bytes = serde_json::to_vec(&summary)?;
            ensure!(
                bytes.len() <= 64 << 20,
                DomainError::new(ErrorCode::GraphBaselineBudget, "")
            );
            let path = directory.path().join("baseline-summary.json");
            store.write_baseline(&path, &bytes)?;
            Ok(Some(BaselineInput::File(BaselineFile {
                path,
                sha256: hash(&bytes),
                bytes: bytes.len() as u64,
            })))
        })
        .await?
    }

    #[allow(clippy::too_many_arguments)]
    async fn stream_unit(
        &self,
        id: &str,
        unit: &mut RunningUnit,
        revision: Revision,
        updates: Vec<UnitUpdate>,
        profile: &str,
        old: Option<&BaselineInput>,
        expected: &RevisionId,
        additional: Vec<codeana_contracts::graph::Row>,
        disks: Vec<crate::resources::DiskBudget>,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, crate::indexing::StagedCandidate)> {
        let owner = unit.unit.clone();
        let full = updates
            .iter()
            .find(|u| u.unit == owner)
            .context("ANALYSIS_UNIT_MISSING")?
            .kind
            == UnitUpdateKind::Full;
        let (worker, manifest) = unit.stream()?;
        let origin = worker.record_origin()?;
        let mut staging = None;
        let mut metrics = None;
        let mut payload = Some((revision, updates, origin, additional, disks));
        let receipt = worker
            .analyze_into(manifest, profile, expected, old, full, cancel, |header| {
                let (revision, mut updates, origin, additional, disks) =
                    payload.take().context("DUPLICATE_CANDIDATE_STREAM_BEGIN")?;
                record_outcome(
                    updates
                        .iter_mut()
                        .find(|u| u.unit == owner)
                        .context("ANALYSIS_UNIT_MISSING")?,
                    header,
                )?;
                let (sender, records) = codeana_graph_io::stream::GraphStream::channel();
                metrics = Some(sender.metrics.clone());
                let store = self.index.clone().with_context(self.staging_context(id));
                let expected = expected.clone();
                let cancel = cancel.clone();
                staging = Some(tokio::task::spawn_blocking(move || {
                    stage_outcomes(
                        store,
                        revision,
                        updates,
                        records.map(move |r| r.map(|row| origin.record(row))).chain(
                            additional
                                .into_iter()
                                .map(|row| Ok(Origin::Host.record(row))),
                        ),
                        &expected,
                        &cancel,
                        &disks,
                    )
                }));
                Ok(sender)
            })
            .await;
        let staged = match staging {
            Some(task) => Some(task.await?),
            None => None,
        };
        if let Ok(header) = &receipt {
            self.unit_result(
                id,
                &owner,
                match header.mode {
                    AnalysisMode::Full => UnitUpdateKind::Full,
                    AnalysisMode::Delta => UnitUpdateKind::Delta,
                },
            )?;
        }
        let result = match (receipt, staged) {
            (Err(transport), Some(Err(storage)))
                if transport
                    .downcast_ref::<DomainError>()
                    .is_some_and(|e| e.code == ErrorCode::CandidateStreamClosed) =>
            {
                Err(storage)
            }
            (Err(error), _) => Err(error),
            (Ok(_), Some(candidate)) => candidate,
            (Ok(_), None) => bail!("CANDIDATE_STREAM_NOT_STARTED"),
        }?;
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
        Ok(result)
    }
}

fn record_outcome(update: &mut UnitUpdate, header: &AnalysisHeader) -> Result<()> {
    ensure!(
        matches!(update.kind, UnitUpdateKind::Full | UnitUpdateKind::Delta),
        "UNIT_UPDATE_EXECUTION_REQUIRED"
    );
    ensure!(
        update.kind != UnitUpdateKind::Full || header.mode == AnalysisMode::Full,
        "UNIT_UPDATE_FULL_REQUIRED"
    );
    update.kind = match header.mode {
        AnalysisMode::Full => UnitUpdateKind::Full,
        AnalysisMode::Delta => UnitUpdateKind::Delta,
    };
    update.replacements = if update.kind == UnitUpdateKind::Delta {
        header.replacements.clone()
    } else {
        vec![]
    };
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn stage_outcomes(
    store: IndexService<ports::storage::IndexAdapter>,
    revision: Revision,
    updates: Vec<UnitUpdate>,
    records: impl Iterator<Item = Result<codeana_graph_io::records::Record>>,
    expected: &str,
    cancel: &CancellationToken,
    disks: &[crate::resources::DiskBudget],
) -> Result<(AnalysisMode, crate::indexing::StagedCandidate)> {
    let cancellation = || {
        ensure!(
            !cancel.is_cancelled(),
            DomainError::new(ErrorCode::Cancelled, "")
        );
        Ok(())
    };
    let check = |path: &Path| {
        for disk in disks {
            disk.check_candidate(Some(path))?;
        }
        Ok(())
    };
    // A complete replacement needs no copy of an obsolete physical schema.
    // Mixed updates retain the pinned baseline and validate each original owner.
    if updates
        .iter()
        .all(|u| !matches!(u.kind, UnitUpdateKind::Reuse | UnitUpdateKind::Delta))
    {
        let header = AnalysisHeader {
            mode: AnalysisMode::Full,
            baseline: expected.into(),
            manifest: revision.manifest.id(),
            profile: revision.profile.clone(),
            producer: revision.producer.clone(),
            replacements: vec![],
        };
        Ok((
            AnalysisMode::Full,
            store.stage_graph_records(revision, &header, records, expected, cancellation, check)?,
        ))
    } else {
        Ok((
            AnalysisMode::Delta,
            store.stage_unit_records(revision, updates, records, expected, cancellation, check)?,
        ))
    }
}

fn validate_java_context(unit: &RunningUnit, fixture: bool) -> Result<()> {
    if unit.backend() != AnalysisBackend::Java {
        return Ok(());
    }
    let worker = unit.worker()?;
    for module in &unit.capture().effective.config.modules {
        if module.language != "java" {
            continue;
        }
        let source = worker
            .context
            .java_source_version()
            .context("JAVA_CONTEXT_REQUIRED")?;
        if let Some(version) = &module.analysis.source_version {
            ensure!(
                version == "auto" || version == source,
                DomainError::new(ErrorCode::SourceVersionUnsupported, "")
            );
        }
        if fixture && let Some(toolchain) = &module.analysis.toolchain {
            ensure!(
                toolchain == "auto" || toolchain == &format!("mock-jdk{source}"),
                DomainError::new(ErrorCode::MockToolchainUnsupported, "")
            );
        }
    }
    Ok(())
}

fn availability_rows(
    capture: &Capture,
    plan: &crate::index_plan::IndexPlan,
    host: Option<&AnalysisContext>,
) -> Result<Vec<codeana_contracts::graph::Row>> {
    let mut rows = vec![];
    for unit in &capture.effective.input_units {
        if let Some(host) = host {
            host.charge(
                1,
                0,
                retained_size(&unit.prepared)?
                    .saturating_mul(3)
                    .saturating_add(512),
            )
            .map_err(DomainError::analysis)?;
        }
        rows.push(
            ProjectMetaRow {
                key: Some(format!("inputs.unit.{}", unit.prepared.id)),
                value: Some(serde_json::to_string(&unit.prepared)?),
            }
            .into_row(""),
        );
    }
    for unit in plan
        .units
        .iter()
        .filter(|u| u.availability == UnitAvailability::FilesOnly)
    {
        if let Some(host) = host {
            host.charge(
                1,
                0,
                retained_size(&capture.effective.config.modules)?
                    .saturating_mul(3)
                    .saturating_add(1024),
            )
            .map_err(DomainError::analysis)?;
        }
        let modules = match unit.prepared_index {
            Some(index) => {
                let p = &capture.effective.input_units[index].prepared;
                if p.modules.is_empty() {
                    p.entries.clone()
                } else {
                    p.modules.clone()
                }
            }
            None => capture
                .effective
                .config
                .modules
                .iter()
                .filter(|m| crate::index_plan::language(&m.language) == Some(unit.language))
                .map(|m| m.id.clone())
                .collect(),
        };
        for module in if modules.is_empty() {
            vec![unit.id.to_string()]
        } else {
            modules
        } {
            for scope in &capture.effective.config.scan.scopes {
                if let Some(host) = host {
                    host.charge(1, 0, 4096 + 4 * (module.len() + scope.len()) as u64)
                        .map_err(DomainError::analysis)?;
                }
                rows.push(
                    CoverageRow {
                        source_file: Some("*".into()),
                        module: Some(module.clone()),
                        scope: Some(scope.clone()),
                        language: Some(unit.language.as_str().into()),
                        provider_id: Some("codeana-inputs".into()),
                        capability: Some("AGGREGATE".into()),
                        status: Some("partial".into()),
                        occurrences: Some(1),
                        groups_count: Some(1),
                        codes: Some("[\"INPUT_CONTEXT_UNAVAILABLE\"]".into()),
                        code_counts: Some("{\"INPUT_CONTEXT_UNAVAILABLE\":1}".into()),
                        details_truncated: Some(0),
                    }
                    .into_row(&key("input-availability", &[&module])),
                );
            }
        }
    }
    Ok(rows)
}
