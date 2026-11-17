//! Host execution policies for a compiled index plan. No language adaptor owns fan-out.
use super::*;
use crate::indexing::PublishedIndex;
use crate::ports::RustBaselineReader;
use crate::rust_inputs::RustInput;
use codeana_contracts::graph_rows::{CoverageRow, GraphRow, ProjectMetaRow};

/// Use only after the complete semantic profile matches. The root config is a
/// FILES capture too; its presentation bytes must not force a semantic rerun.
fn same_sources_for_profile(before: &Manifest, after: &Manifest) -> bool {
    codeana_model::same_semantic_sources(&before.files, &after.files)
}

struct RustWorkers {
    rust: worker::Worker,
    java: Option<worker::Worker>,
    java_capture: Option<Capture>,
    prepared: Option<java::PreparedJava>,
}
struct RustBaseline {
    expected: RevisionId,
    profile: String,
    full: bool,
    old: Option<BaselineInput>,
    unchanged: bool,
}
struct RustAttempt {
    // Fields drop in declaration order: stop workers before deleting their inputs.
    workers: RustWorkers,
    input: RustInput,
    baseline: RustBaseline,
}

impl IndexTask {
    async fn shutdown_rust_workers(&self, id: &str, workers: &mut RustWorkers) {
        if let Some(warning) = crate::analysis_unit::shutdown(
            std::iter::once(&mut workers.rust)
                .chain(workers.java.iter_mut())
                .collect(),
        )
        .await
        {
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
        if let Some(warning) = crate::analysis_unit::shutdown(units.workers_mut()).await {
            let _ = self.task.event(
                &TaskId::from(id),
                &Event::Progress(format!("worker_cleanup_warning {warning}")),
            );
        }
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
                PreparedInput::Python(input.descriptor, request.python_guard.clone())
            }
        })
    }

    fn reserve_owned_input(
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

    pub(super) async fn index_rust(
        &self,
        id: &str,
        req: &IndexCommand,
        mut capture: Capture,
        plan: &crate::index_plan::IndexPlan,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let started = Instant::now();
        ensure!(!self.workers.is_mock(), "RUST_REAL_WORKER_REQUIRED");
        let expected = plan.baseline.clone();
        ensure!(
            req.expected_revision.is_empty() || expected == req.expected_revision.as_str(),
            DomainError::new(ErrorCode::HeadConflict, "")
        );
        let input = self.prepare_rust_inputs(id, &capture).await?;
        let mut workers = self
            .start_rust_workers(
                id,
                &input,
                &capture,
                plan.executes(AnalysisBackend::Java),
                cancel,
                plan,
            )
            .await?;
        let baseline_result = self
            .resolve_rust_baseline(id, req, &capture, &input, &workers, expected)
            .await;
        let baseline = match baseline_result {
            Ok(baseline) => baseline,
            Err(error) => {
                self.shutdown_rust_workers(id, &mut workers).await;
                return Err(error);
            }
        };
        let mut attempt = RustAttempt {
            input,
            workers,
            baseline,
        };
        let result: Result<TaskResult> = async {
            let analyzing = Instant::now();
            let (result_mode, candidate) = self
                .analyze_rust_index(id, &capture, &mut attempt, cancel)
                .await?;
            let analyze_ms = analyzing.elapsed().as_millis() as u64;
            self.task.event(
                &TaskId::from(id),
                &Event::Progress(format!(
                    "rust_worker_memory {}",
                    serde_json::to_string(&attempt.workers.rust.memory_usage())?
                )),
            )?;
            let summary = self.index.summary(&candidate).clone();
            let evidence = summary.evidence.clone();
            let (revision, publishing) = match self
                .publish_candidate(
                    id,
                    req,
                    &mut capture,
                    crate::indexing::CandidatePublication {
                        candidate,
                        producer: "rust-core",
                        progress: Some("rust_publish"),
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
            let units = (!attempt.baseline.unchanged)
                .then_some(&mut attempt.workers.rust)
                .into_iter()
                .chain(attempt.workers.java.iter_mut())
                .collect();
            let ack = crate::analysis_unit::acknowledge(units, &revision.id).await;
            if let Ok(memory) = serde_json::to_string(&attempt.workers.rust.memory_usage()) {
                let _ = self.task.event(
                    &TaskId::from(id),
                    &Event::Progress(format!("rust_worker_memory {memory}")),
                );
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
                    worker_contexts: std::iter::once(&attempt.workers.rust)
                        .chain(attempt.workers.java.iter())
                        .map(|worker| worker.context.clone())
                        .collect(),
                    analyze_ms,
                    publish_ms: publishing.elapsed().as_millis() as u64,
                    index_elapsed_ms: started.elapsed().as_millis() as u64,
                },
            )
            .await
        }
        .await;
        self.shutdown_rust_workers(id, &mut attempt.workers).await;
        result
    }
    async fn start_rust_workers(
        &self,
        id: &str,
        input: &RustInput,
        capture: &Capture,
        java_enabled: bool,
        cancel: &CancellationToken,
        plan: &crate::index_plan::IndexPlan,
    ) -> Result<RustWorkers> {
        let descriptor = &input.descriptor;
        let rust_manifest = &input.manifest;
        let memory = if java_enabled {
            input.memory / 2
        } else {
            input.memory
        };
        let rust_worker = worker::Worker::start_language(
            worker::LaunchSpec {
                fixtures: None,
                language: worker::Language::Rust(descriptor),
                checkout: &capture.root,
                manifest: rust_manifest,
                inputs: crate::supervisor::InputLease::new(capture, vec![input.scratch.clone()]),
                publication: crate::analysis_unit::publication(
                    capture,
                    AnalysisBackend::Rust,
                    plan.previous_sources("rust"),
                ),
            },
            cancel,
            memory,
            self.analysis_binding(id, AnalysisBackend::Rust, ""),
        )
        .await?;
        let mut workers = RustWorkers {
            rust: rust_worker,
            java: None,
            java_capture: None,
            prepared: None,
        };
        let started: Result<()> = async {
            ensure!(
                workers.rust.context.backend() == AnalysisBackend::Rust,
                "WORKER_LANGUAGE_MISMATCH"
            );
            workers.java_capture = if java_enabled {
                Some(capture.backend_capture(AnalysisBackend::Java)?)
            } else {
                None
            };
            workers.prepared = if let Some(c) = workers.java_capture.clone() {
                Some(self.prepare_java_unit(c).await?)
            } else {
                None
            };
            workers.java = if let Some(c) = &workers.java_capture {
                Some(
                    worker::Worker::start_language(
                        worker::LaunchSpec {
                            fixtures: None,
                            language: worker::Language::Java(workers.prepared.as_ref()),
                            checkout: &c.root,
                            manifest: &c.manifest,
                            inputs: crate::supervisor::InputLease::new(
                                c,
                                workers.prepared.iter().map(|p| p.scratch.clone()).collect(),
                            ),
                            publication: crate::analysis_unit::publication(
                                c,
                                AnalysisBackend::Java,
                                plan.previous_sources("java"),
                            ),
                        },
                        cancel,
                        memory,
                        self.analysis_binding(id, AnalysisBackend::Java, ""),
                    )
                    .await?,
                )
            } else {
                None
            };
            Ok(())
        }
        .await;
        if let Err(error) = started {
            self.shutdown_rust_workers(id, &mut workers).await;
            return Err(error);
        }
        Ok(workers)
    }
    async fn resolve_rust_baseline(
        &self,
        id: &str,
        req: &IndexCommand,
        capture: &Capture,
        input: &RustInput,
        workers: &RustWorkers,
        expected: RevisionId,
    ) -> Result<RustBaseline> {
        let config = &input.config;
        let rust_worker = &workers.rust;
        let java_worker = &workers.java;
        let scratch = &input.scratch;
        // Unpublished dependency bytes can alter bindings without a public source owner.
        // Rebuild on their change until external interface summaries are available.
        let dependency_inputs = config
            .files
            .iter()
            .filter(|file| {
                !config.inputs.crates.iter().any(|c| {
                    c.publish
                        && file.crates.contains(&c.id)
                        && !config
                            .excluded
                            .contains(&(file.path.clone(), c.world.clone()))
                })
            })
            .map(|f| (&f.path, &f.hash))
            .collect::<Vec<_>>();
        let profile = digest(&(
            codeana_comment_search::identity(),
            dependency_inputs,
            &capture.effective.semantic_digest,
            config.inputs.semantic_digest(),
            &rust_worker.context.semantic_digest,
            java_worker.as_ref().map(|w| &w.context.semantic_digest),
            "graph6-schema13",
        ));
        let mut full =
            req.full || expected.is_empty() || capture.effective.config.index.mode == "full";
        let mut old = None;
        let mut unchanged = false;
        if !full {
            let store = self.index.clone().with_context(self.staging_context(id));
            let baseline_id = expected.clone();
            let desired_profile = profile.clone();
            let desired_manifest = capture.manifest.id();
            let baseline_path = scratch.path().join("baseline-summary.json");
            let rust_only = java_worker.is_none();
            let (compatible, same, baseline) = tokio::task::spawn_blocking(move || -> Result<_> {
                let reader = store.read(&baseline_id)?;
                if reader.revision().profile != desired_profile {
                    return Ok((false, false, None));
                }
                if rust_only && reader.revision().manifest.id() == desired_manifest {
                    return Ok((true, true, None));
                }
                let bytes =
                    serde_json::to_vec(&RustBaselineReader::rust_baseline_summary(&reader)?)?;
                ensure!(bytes.len() <= 64 << 20, "RUST_BASELINE_BUDGET");
                std::fs::write(&baseline_path, &bytes)?;
                Ok((
                    true,
                    false,
                    Some(BaselineInput::File(BaselineFile {
                        path: baseline_path,
                        sha256: hash(&bytes),
                        bytes: bytes.len() as u64,
                    })),
                ))
            })
            .await??;
            full = !compatible;
            unchanged = same;
            old = baseline;
        }
        self.task.event(
            &TaskId::from(id),
            &Event::Progress(
                if full {
                    "rust_analyze_full"
                } else {
                    "rust_analyze_delta"
                }
                .into(),
            ),
        )?;
        Ok(RustBaseline {
            expected,
            profile,
            full,
            old,
            unchanged,
        })
    }
    async fn analyze_rust_index(
        &self,
        id: &str,
        capture: &Capture,
        attempt: &mut RustAttempt,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, crate::indexing::StagedCandidate)> {
        let revision = Revision {
            unit_ownership: self.ownership(
                capture,
                &std::iter::once(&attempt.workers.rust.context)
                    .chain(attempt.workers.java.iter().map(|w| &w.context))
                    .collect::<Vec<_>>(),
            )?,
            id: String::new(),
            target: capture.target.clone(),
            project: capture.project.clone(),
            checkout: capture.checkout.clone(),
            manifest: capture.manifest.clone(),
            profile: attempt.baseline.profile.clone(),
            producer: if attempt.workers.java.is_some() {
                "codeana-java-rust"
            } else {
                "rust-core"
            }
            .into(),
            created: 0,
        };
        if attempt.baseline.unchanged {
            self.stage_unchanged_rust(id, attempt, revision, cancel)
                .await
        } else if attempt.workers.java.is_none() {
            self.stage_rust_stream(id, attempt, revision, cancel).await
        } else {
            self.stage_mixed_languages(id, capture, attempt, revision, cancel)
                .await
        }
    }
    async fn stage_unchanged_rust(
        &self,
        id: &str,
        attempt: &mut RustAttempt,
        revision: Revision,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, crate::indexing::StagedCandidate)> {
        let rust_worker = &mut attempt.workers.rust;
        let expected = attempt.baseline.expected.clone();
        crate::analysis_unit::retire(vec![rust_worker]).await?;
        self.task.event(
            &TaskId::from(id),
            &Event::Progress("rust_analyze_unchanged".into()),
        )?;
        self.stage_reused_revision(id, revision, expected, "rust-core".into(), None, cancel)
            .await
    }
    async fn stage_rust_stream(
        &self,
        id: &str,
        attempt: &mut RustAttempt,
        revision: Revision,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, crate::indexing::StagedCandidate)> {
        self.stage_language_stream(
            id,
            &mut attempt.workers.rust,
            crate::indexing::StreamRequest {
                manifest: &attempt.input.manifest,
                revision,
                expected: &attempt.baseline.expected,
                baseline: attempt.baseline.old.as_ref(),
                full: attempt.baseline.full,
            },
            cancel,
        )
        .await
    }
    async fn stage_mixed_languages(
        &self,
        id: &str,
        capture: &Capture,
        attempt: &mut RustAttempt,
        revision: Revision,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, crate::indexing::StagedCandidate)> {
        let RustWorkers {
            rust: rust_worker,
            java: java_worker,
            java_capture,
            prepared,
        } = &mut attempt.workers;
        let rust_manifest = &attempt.input.manifest;
        let scratch = &attempt.input.scratch;
        let result_bytes = attempt.input.config.result_bytes;
        let fact_limit = attempt.input.config.fact_limit;
        let old = &attempt.baseline.old;
        let full = attempt.baseline.full;
        let expected = attempt.baseline.expected.clone();
        let profile = attempt.baseline.profile.clone();

        let result = rust_worker
            .analyze(
                rust_manifest,
                &profile,
                &expected,
                old.as_ref(),
                full,
                cancel,
            )
            .await?;
        self.task.event(
            &TaskId::from(id),
            &Event::Progress(format!(
                "rust_worker_memory {}",
                serde_json::to_string(&rust_worker.memory_usage())?
            )),
        )?;
        let mut archive = result.records;
        let mut replacements = result.header.replacements;
        if let (Some(worker), Some(c)) = (java_worker.as_mut(), java_capture.as_ref()) {
            // Java retains its existing semantic backend. The merge is still one revision.
            let result = worker
                .analyze(&c.manifest, &profile, &expected, None, true, cancel)
                .await?;
            let root = scratch.path().to_path_buf();
            archive = tokio::task::spawn_blocking(move || -> Result<_> {
                let mut combined = GraphArchiveBuilder::new(Some(&root), result_bytes, fact_limit)?;
                for row in archive.records()?.chain(result.records.records()?) {
                    combined.push(&row?)?;
                }
                combined.seal()
            })
            .await??;
            replacements.extend(
                c.manifest
                    .files
                    .iter()
                    .filter(|s| s.language == "java")
                    .map(|s| Owner {
                        world: s.world.clone().into(),
                        producer: worker.context.producer.clone(),
                        kind: "source".into(),
                        id: s.path.clone(),
                    }),
            );
            if !full {
                let reader = self.index.read(&expected)?;
                replacements.extend(
                    reader
                        .revision()
                        .manifest
                        .files
                        .iter()
                        .filter(|s| s.language == "java")
                        .map(|s| Owner {
                            world: s.world.clone().into(),
                            producer: worker.context.producer.clone(),
                            kind: "source".into(),
                            id: s.path.clone(),
                        }),
                );
            }
        }
        if let Some(prepared) = &prepared {
            prepared.verify()?;
        }
        replacements.sort();
        replacements.dedup();
        let producer = if java_worker.is_some() {
            "codeana-java-rust"
        } else {
            "rust-core"
        }
        .to_owned();
        let header = AnalysisHeader {
            mode: if full {
                AnalysisMode::Full
            } else {
                AnalysisMode::Delta
            },
            baseline: expected.to_string(),
            manifest: capture.manifest.id(),
            profile: profile.clone(),
            producer: producer.clone(),
            replacements,
        };
        self.task.event(
            &TaskId::from(id),
            &Event::Progress("rust_stage_graph".into()),
        )?;
        let result_mode = header.mode;
        let store = self.index.clone().with_context(self.staging_context(id));
        let cancellation = cancel.clone();
        let candidate = tokio::task::spawn_blocking(move || {
            store.stage_graph_records(
                revision,
                &header,
                archive.records()?,
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
        Ok((result_mode, candidate))
    }
}

impl IndexTask {
    pub(super) async fn index_python(
        &self,
        id: &str,
        req: &IndexCommand,
        mut capture: Capture,
        plan: &crate::index_plan::IndexPlan,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let started = Instant::now();
        ensure!(!self.workers.is_mock(), "PYTHON_REAL_WORKER_REQUIRED");

        let expected = plan.baseline.clone();
        ensure!(
            req.expected_revision.is_empty() || req.expected_revision == expected.as_str(),
            DomainError::new(ErrorCode::HeadConflict, "")
        );
        let scratch = tempfile::Builder::new()
            .prefix("python-index-")
            .tempdir_in(self.index.home())?;
        let crate::python_inputs::PreparedPython {
            descriptor,
            config,
            source_bytes,
        } = {
            let capture = capture.clone();
            let directory = scratch.path().to_path_buf();
            tokio::task::spawn_blocking(move || {
                crate::python_inputs::prepare_inputs(
                    &capture,
                    &directory,
                    crate::python_inputs::InputMode::Standard,
                )
            })
            .await??
        };
        let scratch_bytes = descriptor.scratch_bytes;
        let resources = &capture.effective.config.resources.index;
        let memory = resources
            .worker_memory_bytes
            .unwrap_or(self.workers.memory_cap());
        ensure!(
            memory > 0 && memory <= self.workers.memory_cap(),
            DomainError::new(ErrorCode::ProjectWorkerMemoryLimit, "")
        );
        let rust_input = if plan.executes(AnalysisBackend::Rust) {
            Some(self.prepare_rust_inputs(id, &capture).await?)
        } else {
            None
        };
        let java_enabled = plan.executes(AnalysisBackend::Java);
        let memory = memory / (1 + u64::from(rust_input.is_some()) + u64::from(java_enabled));
        ensure!(memory > 0, "WORKER_MEMORY_BUDGET");
        let java_capture = if plan.executes(AnalysisBackend::Java) {
            let mut c = capture.backend_capture(AnalysisBackend::Java)?;
            c.effective.set_worker_memory_limit(memory);
            Some(c)
        } else {
            None
        };
        let prepared = if let Some(c) = java_capture.clone() {
            Some(self.prepare_java_unit(c).await?)
        } else {
            None
        };
        // Admit ownership storage while inputs are still outside the running worker scope.
        // A rejected reservation must not drop an input underneath an already started worker.
        if rust_input.is_some() {
            self.reserve_owned_input(id, AnalysisBackend::Rust, "")?;
        }
        if prepared.is_some() {
            self.reserve_owned_input(id, AnalysisBackend::Java, "")?;
        }
        let python_capture = capture.backend_capture(AnalysisBackend::Python)?;
        let python_manifest = python_capture.manifest.clone();
        let merged_bytes = scratch_bytes - source_bytes - descriptor.bytes;
        let mut sessions = crate::analysis_unit::RunningUnits {
            units: vec![],
            scratch: Arc::new(scratch),
        };
        sessions
            .units
            .push(crate::analysis_unit::RunningUnit::prepared(
                crate::analysis_unit::PreparedInput::Python(descriptor, sessions.scratch.clone()),
                python_capture,
                python_manifest,
            )?);
        if let Some(input) = rust_input {
            let manifest = input.manifest.clone();
            sessions
                .units
                .push(crate::analysis_unit::RunningUnit::prepared(
                    crate::analysis_unit::PreparedInput::Rust(Box::new(input)),
                    capture.backend_capture(AnalysisBackend::Rust)?,
                    manifest,
                )?);
        }
        if let (Some(input), Some(c)) = (prepared, java_capture) {
            let manifest = c.manifest.clone();
            sessions
                .units
                .push(crate::analysis_unit::RunningUnit::prepared(
                    crate::analysis_unit::PreparedInput::Java(Box::new(input)),
                    c,
                    manifest,
                )?);
        }
        sessions
            .units
            .sort_by(|a, b| (a.backend(), a.unit.as_str()).cmp(&(b.backend(), b.unit.as_str())));
        let result: Result<TaskResult> = async {
            for unit in &mut sessions.units {
                unit.start(
                    cancel,
                    memory,
                    self.analysis_binding(id, unit.backend(), ""),
                    plan.previous_sources(&unit.unit),
                )
                .await?;
            }
            let contexts = sessions
                .units
                .iter()
                .map(|u| Ok(u.worker()?.context.semantic_digest.clone()))
                .collect::<Result<Vec<_>>>()?;
            let profile = digest(&(
                codeana_comment_search::identity(),
                &capture.effective.semantic_digest,
                &config.inputs,
                contexts,
                python::ADAPTER_VERSION,
            ));
            let producer = if sessions.units.len() == 1 {
                python::PRODUCER.to_owned()
            } else {
                "codeana-multi-language".into()
            };
            let revision = Revision {
                unit_ownership: self.ownership(
                    &capture,
                    &sessions
                        .units
                        .iter()
                        .map(|u| u.worker().map(|w| &w.context))
                        .collect::<Result<Vec<_>>>()?,
                )?,
                id: String::new(),
                target: capture.target.clone(),
                project: capture.project.clone(),
                checkout: capture.checkout.clone(),
                manifest: capture.manifest.clone(),
                profile: profile.clone(),
                producer: producer.clone(),
                created: 0,
            };
            let unchanged = !req.full
                && capture.effective.config.index.mode != "full"
                && !expected.is_empty()
                && {
                    let reader = self.index.read(&expected)?;
                    reader.revision().profile == profile
                        && same_sources_for_profile(&reader.revision().manifest, &capture.manifest)
                };
            let analyzing = Instant::now();
            let (result_mode, candidate) = if unchanged {
                crate::analysis_unit::retire(sessions.workers_mut()).await?;
                self.stage_reused_revision(
                    id,
                    revision,
                    expected.clone(),
                    producer.clone(),
                    None,
                    cancel,
                )
                .await?
            } else {
                self.task.event(
                    &TaskId::from(id),
                    &Event::Progress("python_analyze_full: input_or_environment_changed".into()),
                )?;
                if sessions.units.len() == 1 {
                    let (worker, manifest) = sessions.units[0].stream()?;
                    self.stage_language_stream(
                        id,
                        worker,
                        crate::indexing::StreamRequest {
                            manifest,
                            revision,
                            expected: &expected,
                            baseline: None,
                            full: true,
                        },
                        cancel,
                    )
                    .await?
                } else {
                    self.stage_full_languages(
                        id,
                        &mut sessions.units,
                        revision,
                        &expected,
                        sessions.scratch.path(),
                        codeana_contracts::graph_delivery::OutputLimits {
                            bytes: merged_bytes,
                            facts: 6_000_000,
                        },
                        cancel,
                    )
                    .await?
                }
            };
            for unit in &sessions.units {
                unit.input.verify()?;
            }
            let analyze_ms = analyzing.elapsed().as_millis() as u64;
            for unit in &sessions.units {
                let worker = unit.worker()?;
                self.task.event(
                    &TaskId::from(id),
                    &Event::Progress(format!(
                        "{}_worker_memory {}",
                        worker.context.backend().as_str(),
                        serde_json::to_string(&worker.memory_usage())?
                    )),
                )?;
            }
            let summary = self.index.summary(&candidate).clone();
            let evidence = summary.evidence.clone();
            let (revision, publishing) = match self
                .publish_candidate(
                    id,
                    req,
                    &mut capture,
                    crate::indexing::CandidatePublication {
                        candidate,
                        producer: python::PRODUCER,
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
            let ack = if unchanged {
                None
            } else {
                crate::analysis_unit::acknowledge(sessions.workers_mut(), &revision.id).await
            };
            self.finish_index(
                id,
                &capture,
                PublishedIndex {
                    revision,
                    result_mode,
                    summary,
                    evidence,
                    ack,
                    worker_contexts: sessions.contexts()?,
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
}

impl IndexTask {
    pub(super) async fn index_prepared(
        &self,
        id: &str,
        req: &IndexCommand,
        mut capture: Capture,
        plan: &crate::index_plan::IndexPlan,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        ensure!(!self.workers.is_mock(), "PREPARED_REAL_WORKER_REQUIRED");
        let started = Instant::now();
        let expected = plan.baseline.clone();
        ensure!(
            req.expected_revision.is_empty() || req.expected_revision == expected.as_str(),
            DomainError::new(ErrorCode::HeadConflict, "")
        );
        let scratch = tempfile::Builder::new()
            .prefix("prepared-index-")
            .tempdir_in(self.index.home())?;
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
            "PROJECT_WORKER_MEMORY_LIMIT"
        );
        let memory = memory / count;
        ensure!(memory > 0, "WORKER_MEMORY_BUDGET");
        capture.effective.set_worker_memory_limit(memory);
        let mut sessions = crate::analysis_unit::RunningUnits {
            units: vec![],
            scratch: Arc::new(scratch),
        };
        let result: Result<TaskResult> = async {
            let mut additional = vec![];
            for unit in plan.units.iter().filter(|u| {
                u.availability == UnitAvailability::FilesOnly && u.prepared_index.is_none()
            }) {
                let modules = capture
                    .effective
                    .config
                    .modules
                    .iter()
                    .filter(|m| crate::index_plan::language(&m.language) == Some(unit.language))
                    .map(|m| m.id.as_str())
                    .collect::<Vec<_>>();
                for module in if modules.is_empty() {
                    vec![unit.id.as_str()]
                } else {
                    modules
                } {
                    for scope in &capture.effective.config.scan.scopes {
                        additional.push(
                            CoverageRow {
                                source_file: Some("*".into()),
                                module: Some(module.into()),
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
                            .into_row(&key("input-availability", &[module])),
                        );
                    }
                }
            }
            let mut planned_units = plan
                .units
                .iter()
                .filter(|u| u.availability == UnitAvailability::Ready);
            let mut ordered = capture
                .effective
                .input_units
                .iter()
                .enumerate()
                .collect::<Vec<_>>();
            ordered.sort_by(|(_, a), (_, b)| {
                (
                    crate::index_plan::language(&a.prepared.language),
                    a.prepared.id.as_str(),
                )
                    .cmp(&(
                        crate::index_plan::language(&b.prepared.language),
                        b.prepared.id.as_str(),
                    ))
            });
            for (index, unit) in ordered {
                self.task.event(
                    &TaskId::from(id),
                    &Event::Progress(format!(
                        "input_unit {} {:?}",
                        unit.prepared.id, unit.prepared.mode
                    )),
                )?;
                additional.push(
                    ProjectMetaRow {
                        key: Some(format!("inputs.unit.{}", unit.prepared.id)),
                        value: Some(serde_json::to_string(&unit.prepared)?),
                    }
                    .into_row(""),
                );
                let Some(_) = &unit.context else {
                    let modules = if unit.prepared.modules.is_empty() {
                        &unit.prepared.entries
                    } else {
                        &unit.prepared.modules
                    };
                    for entry in modules {
                        for scope in &capture.effective.config.scan.scopes {
                            additional.push(
                                CoverageRow {
                                    source_file: Some("*".into()),
                                    module: Some(entry.clone()),
                                    scope: Some(scope.clone()),
                                    language: Some(unit.prepared.language.clone()),
                                    provider_id: Some("codeana-inputs".into()),
                                    capability: Some("AGGREGATE".into()),
                                    status: Some("partial".into()),
                                    occurrences: Some(1),
                                    groups_count: Some(1),
                                    codes: Some("[\"INPUT_CONTEXT_UNAVAILABLE\"]".into()),
                                    code_counts: Some("{\"INPUT_CONTEXT_UNAVAILABLE\":1}".into()),
                                    details_truncated: Some(0),
                                }
                                .into_row(&key("input-availability", &[entry])),
                            );
                        }
                    }
                    continue;
                };
                let child =
                    capture.unit_capture(&AnalysisUnitId::from(unit.prepared.id.clone()))?;
                let directory = sessions.scratch.path().join(&unit.prepared.id);
                std::fs::create_dir(&directory)?;
                let planned = planned_units.next().context("PREPARED_UNIT_MISSING")?;
                ensure!(
                    planned.prepared_index == Some(index),
                    "PREPARED_UNIT_INDEX_MISMATCH"
                );
                let language = planned.language;
                let binding = self.analysis_binding(id, language, &unit.prepared.id);
                let input = self
                    .prepare_analysis_unit(crate::analysis_unit::Preparation {
                        task: id,
                        instance: &unit.prepared.id,
                        language,
                        capture: &child,
                        python_directory: &directory,
                        python_guard: sessions.scratch.clone(),
                        python_mode: crate::python_inputs::InputMode::Prepared,
                    })
                    .await?;
                let manifest = child.manifest.clone();
                sessions
                    .units
                    .push(crate::analysis_unit::RunningUnit::prepared(
                        input, child, manifest,
                    )?);
                sessions
                    .units
                    .last_mut()
                    .context("PREPARED_UNIT_MISSING")?
                    .start(
                        cancel,
                        memory,
                        binding,
                        plan.previous_sources(&unit.prepared.id),
                    )
                    .await?;
            }
            let contexts = sessions
                .units
                .iter()
                .map(|u| Ok(u.worker()?.context.semantic_digest.clone()))
                .collect::<Result<Vec<_>>>()?;
            let profile = digest(&(
                codeana_comment_search::identity(),
                &capture.effective.semantic_digest,
                contexts,
                "prepared-inputs-v1",
            ));
            let producer = "codeana-prepared-inputs".to_owned();
            let revision = Revision {
                unit_ownership: self.ownership(
                    &capture,
                    &sessions
                        .units
                        .iter()
                        .map(|u| u.worker().map(|w| &w.context))
                        .collect::<Result<Vec<_>>>()?,
                )?,
                id: String::new(),
                target: capture.target.clone(),
                project: capture.project.clone(),
                checkout: capture.checkout.clone(),
                manifest: capture.manifest.clone(),
                profile: profile.clone(),
                producer: producer.clone(),
                created: 0,
            };
            let unchanged = !req.full
                && capture.effective.config.index.mode != "full"
                && !expected.is_empty()
                && {
                    let old = self.index.read(&expected)?;
                    old.revision().profile == profile
                        && same_sources_for_profile(&old.revision().manifest, &capture.manifest)
                };
            let analyzing = Instant::now();
            let (result_mode, candidate) = if unchanged {
                crate::analysis_unit::retire(sessions.workers_mut()).await?;
                self.stage_reused_revision(
                    id,
                    revision,
                    expected.clone(),
                    producer.clone(),
                    None,
                    cancel,
                )
                .await?
            } else if sessions.units.len() == 1 {
                let (worker, manifest) = sessions.units[0].stream()?;
                self.stage_language_stream_rows(
                    id,
                    worker,
                    crate::indexing::StreamRequest {
                        manifest,
                        revision,
                        expected: &expected,
                        baseline: None,
                        full: true,
                    },
                    additional,
                    cancel,
                )
                .await?
            } else {
                let bytes = capture
                    .effective
                    .config
                    .resources
                    .index
                    .python_scratch_bytes
                    .unwrap_or(2 << 30);
                self.stage_full_language_rows(
                    id,
                    &mut sessions.units,
                    revision,
                    &expected,
                    sessions.scratch.path(),
                    codeana_contracts::graph_delivery::OutputLimits {
                        bytes,
                        facts: 6_000_000,
                    },
                    additional,
                    cancel,
                )
                .await?
            };
            for unit in &sessions.units {
                unit.input.verify()?;
            }
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
            let publish_ms = publishing.elapsed().as_millis() as u64;
            let ack = if unchanged {
                None
            } else {
                crate::analysis_unit::acknowledge(sessions.workers_mut(), &revision.id).await
            };
            self.finish_index(
                id,
                &capture,
                PublishedIndex {
                    revision,
                    result_mode,
                    summary,
                    evidence,
                    ack,
                    worker_contexts: sessions.contexts()?,
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
}

#[cfg(test)]
mod reuse_source_tests {
    use super::*;
    #[test]
    fn presentation_capture_exception_keeps_all_analysis_owners_exact() {
        let config = SourceUnit {
            path: ".code-ana/config.toml".into(),
            hash: "old".into(),
            size: 10,
            module: String::new(),
            world: "files".into(),
            scope: "FILES".into(),
            language: String::new(),
            auxiliary: false,
        };
        let source = SourceUnit {
            path: "main.py".into(),
            hash: "source".into(),
            size: 20,
            module: "app".into(),
            world: "main".into(),
            scope: "MAIN".into(),
            language: "python".into(),
            auxiliary: false,
        };
        let before = Manifest {
            files: vec![config, source],
        };
        let mut after = before.clone();
        after.files[0].hash = "new".into();
        after.files[0].size = 100;
        assert!(same_sources_for_profile(&before, &after));
        after.files[1].hash = "changed source".into();
        assert!(!same_sources_for_profile(&before, &after));
        after = before.clone();
        after.files[0].world = "different owner".into();
        assert!(!same_sources_for_profile(&before, &after));
        after = before.clone();
        after.files[0].language = "python".into();
        assert!(!same_sources_for_profile(&before, &after));
        after = before.clone();
        after.files.remove(0);
        assert!(!same_sources_for_profile(&before, &after));
    }
    #[test]
    fn preparation_artifacts_can_move_but_cannot_hide_semantic_or_owned_input_changes() {
        let before = Manifest {
            files: vec![SourceUnit {
                path: "app.py".into(),
                hash: hash(b"source"),
                size: 6,
                module: "app".into(),
                world: "python-world".into(),
                scope: "MAIN".into(),
                language: "python".into(),
                auxiliary: false,
            }],
        };
        let mut after = before.clone();
        after.files.push(SourceUnit {
            path: ".code-ana/inputs/new-generation/inputs.json".into(),
            hash: hash(b"receipt"),
            size: 7,
            module: String::new(),
            world: "files".into(),
            scope: "PREPARATION".into(),
            language: String::new(),
            auxiliary: false,
        });
        assert!(same_sources_for_profile(&before, &after));
        for field in ["language", "world", "module", "scope", "auxiliary"] {
            let mut forged = after.clone();
            let input = forged.files.last_mut().unwrap();
            match field {
                "language" => input.language = "python".into(),
                "world" => input.world = "owned-world".into(),
                "module" => input.module = "app".into(),
                "scope" => input.scope = "CONTROL".into(),
                _ => input.auxiliary = true,
            }
            assert!(!same_sources_for_profile(&before, &forged));
        }
        after.files[0].hash = hash(b"changed");
        assert!(!same_sources_for_profile(&before, &after));
    }
}
