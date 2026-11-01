//! Host execution policies for a compiled index plan. No language adaptor owns fan-out.
use super::*;
use crate::indexing::PublishedIndex;
use crate::ports::RustBaselineReader;
use crate::rust_inputs::RustInput;
use codeana_config::inputs::InputMode;
use codeana_contracts::graph_rows::{CoverageRow, GraphRow, ProjectMetaRow};

/// Use only after the complete semantic profile matches. The root config is a
/// FILES capture too; its presentation bytes must not force a semantic rerun.
fn same_sources_for_profile(before: &Manifest, after: &Manifest) -> bool {
    before.files.len() == after.files.len()
        && before.files.iter().zip(&after.files).all(|(old, new)| {
            old == new
                || old.path == ".code-ana/config.toml"
                    && new.path == old.path
                    && old.language.is_empty()
                    && new.language.is_empty()
                    && old.world == new.world
                    && old.module == new.module
                    && old.scope == new.scope
                    && old.auxiliary == new.auxiliary
        })
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
        if let Some(warning) = crate::analysis_unit::shutdown(crate::analysis_unit::Units::Pair(
            Some(&mut workers.rust),
            workers.java.as_mut(),
        ))
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
        if let Some(warning) = crate::analysis_unit::shutdown(
            crate::analysis_unit::Units::Sessions(units.sessions.iter_mut()),
        )
        .await
        {
            let _ = self.task.event(
                &TaskId::from(id),
                &Event::Progress(format!("worker_cleanup_warning {warning}")),
            );
        }
        result
    }

    pub(super) async fn prepare_java_unit(&self, captured: Capture) -> Result<java::PreparedJava> {
        self.workers.prepare_java(captured).await
    }

    async fn prepare_analysis_unit(
        &self,
        request: crate::analysis_unit::Preparation<'_>,
    ) -> Result<crate::analysis_unit::PreparedInput> {
        use crate::analysis_unit::PreparedInput;
        self.reserve_owned_input(request.task, request.language, request.instance)?;
        Ok(match request.language {
            AnalysisLanguage::Java => PreparedInput::Java(Box::new(
                self.prepare_java_unit(request.capture.clone()).await?,
            )),
            AnalysisLanguage::Rust => PreparedInput::Rust(Box::new(
                self.prepare_rust_inputs(request.task, request.capture)
                    .await?,
            )),
            AnalysisLanguage::Python => {
                let captured = request.capture.clone();
                let directory = request.python_directory.to_path_buf();
                let mode = request.python_mode;
                let input = tokio::task::spawn_blocking(move || {
                    crate::python_inputs::prepare_inputs(&captured, &directory, mode)
                })
                .await??;
                PreparedInput::Python(input.descriptor)
            }
        })
    }

    fn reserve_owned_input(
        &self,
        id: &str,
        language: AnalysisLanguage,
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
                AnalysisLanguage::Java => std::mem::size_of::<java::PreparedJava>(),
                AnalysisLanguage::Rust => std::mem::size_of::<RustInput>(),
                AnalysisLanguage::Python => 0,
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
        let expected = self
            .index
            .head(&IndexTarget::from(capture.target.clone()))?;
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
                plan.executes(AnalysisLanguage::Java),
                cancel,
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
                    (revision, started)
                }
                crate::indexing::CandidateOutcome::Rejected(result) => return Ok(*result),
            };
            let units = crate::analysis_unit::Units::Pair(
                (!attempt.baseline.unchanged).then_some(&mut attempt.workers.rust),
                attempt.workers.java.as_mut(),
            );
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
                    worker_context: attempt.workers.rust.context.clone(),
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
    ) -> Result<RustWorkers> {
        let descriptor = &input.descriptor;
        let rust_manifest = &input.manifest;
        let memory = if java_enabled {
            input.memory / 2
        } else {
            input.memory
        };
        let rust_worker = worker::Worker::start_language(
            None,
            worker::Language::Rust(descriptor),
            &capture.root,
            rust_manifest,
            cancel,
            memory,
            self.analysis_binding(id, AnalysisLanguage::Rust, ""),
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
                workers.rust.context.language == "rust",
                "WORKER_LANGUAGE_MISMATCH"
            );
            workers.java_capture = if java_enabled {
                let mut c = capture.clone();
                c.manifest.files.retain(|s| s.language != "rust");
                let hashes = c
                    .manifest
                    .files
                    .iter()
                    .map(|s| s.hash.clone())
                    .collect::<std::collections::BTreeSet<_>>();
                c.blobs.retain(|hash, _| hashes.contains(hash));
                Some(c)
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
                    worker::Worker::start(
                        None,
                        workers.prepared.as_ref(),
                        &c.root,
                        &c.manifest,
                        cancel,
                        memory,
                        self.analysis_binding(id, AnalysisLanguage::Java, ""),
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
            &rust_worker.context,
            java_worker.as_ref().map(|w| &w.context),
            "graph5-schema12",
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
        crate::analysis_unit::retire(crate::analysis_unit::Units::One(Some(rust_worker))).await?;
        self.task.event(
            &TaskId::from(id),
            &Event::Progress("rust_analyze_unchanged".into()),
        )?;
        self.stage_reused_revision(id, revision, expected, "rust-core".into(), cancel)
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

        let expected = self
            .index
            .head(&IndexTarget::from(capture.target.clone()))?;
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
        let rust_input = if plan.executes(AnalysisLanguage::Rust) {
            Some(self.prepare_rust_inputs(id, &capture).await?)
        } else {
            None
        };
        let java_enabled = plan.executes(AnalysisLanguage::Java);
        let memory = memory / (1 + u64::from(rust_input.is_some()) + u64::from(java_enabled));
        ensure!(memory > 0, "WORKER_MEMORY_BUDGET");
        let java_capture = if plan.executes(AnalysisLanguage::Java) {
            let mut c = capture.clone();
            c.effective.config.resources.index.worker_memory_bytes = Some(memory);
            c.manifest
                .files
                .retain(|s| s.language == "java" || s.scope == "CONTROL");
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
            self.reserve_owned_input(id, AnalysisLanguage::Rust, "")?;
        }
        if prepared.is_some() {
            self.reserve_owned_input(id, AnalysisLanguage::Java, "")?;
        }
        let python_manifest = Manifest {
            files: capture
                .manifest
                .files
                .iter()
                .filter(|s| s.language == "python" || s.scope == "CONTROL")
                .cloned()
                .collect(),
        };
        let worker = worker::Worker::start_language(
            None,
            worker::Language::Python(&descriptor),
            &capture.root,
            &python_manifest,
            cancel,
            memory,
            self.analysis_binding(id, AnalysisLanguage::Python, ""),
        )
        .await?;
        let merged_bytes = scratch_bytes - source_bytes - descriptor.bytes;
        let mut sessions = crate::analysis_unit::RunningUnits {
            sessions: vec![(worker, python_manifest)],
            inputs: vec![crate::analysis_unit::PreparedInput::Python(descriptor)],
            scratch,
        };
        if let Some(input) = rust_input {
            sessions
                .inputs
                .push(crate::analysis_unit::PreparedInput::Rust(Box::new(input)));
        }
        if let Some(input) = prepared {
            sessions
                .inputs
                .push(crate::analysis_unit::PreparedInput::Java(Box::new(input)));
        }
        let result: Result<TaskResult> = async {
            if let Some(input) = sessions.inputs.iter().find_map(|input| match input {
                crate::analysis_unit::PreparedInput::Rust(input) => Some(input.as_ref()),
                _ => None,
            }) {
                let worker = worker::Worker::start_language(
                    None,
                    worker::Language::Rust(&input.descriptor),
                    &capture.root,
                    &input.manifest,
                    cancel,
                    memory,
                    self.analysis_binding(id, AnalysisLanguage::Rust, ""),
                )
                .await?;
                let manifest = input.manifest.clone();
                sessions.push((worker, manifest));
            }
            if let (Some(prepared), Some(c)) = (
                sessions.inputs.iter().find_map(|input| match input {
                    crate::analysis_unit::PreparedInput::Java(input) => Some(input.as_ref()),
                    _ => None,
                }),
                &java_capture,
            ) {
                let worker = worker::Worker::start(
                    None,
                    Some(prepared),
                    &capture.root,
                    &c.manifest,
                    cancel,
                    memory,
                    self.analysis_binding(id, AnalysisLanguage::Java, ""),
                )
                .await?;
                sessions.push((worker, c.manifest.clone()));
            }
            let contexts = sessions.iter().map(|(w, _)| &w.context).collect::<Vec<_>>();
            let profile = digest(&(
                codeana_comment_search::identity(),
                &capture.effective.semantic_digest,
                &config.inputs,
                contexts,
                python::ADAPTER_VERSION,
            ));
            let producer = if sessions.len() == 1 {
                python::PRODUCER.to_owned()
            } else {
                "codeana-multi-language".into()
            };
            let revision = Revision {
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
                crate::analysis_unit::retire(crate::analysis_unit::Units::Sessions(
                    sessions.iter_mut(),
                ))
                .await?;
                self.stage_reused_revision(id, revision, expected.clone(), producer.clone(), cancel)
                    .await?
            } else {
                self.task.event(
                    &TaskId::from(id),
                    &Event::Progress("python_analyze_full: input_or_environment_changed".into()),
                )?;
                if sessions.len() == 1 {
                    let (worker, manifest) = &mut sessions[0];
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
                        &mut sessions.sessions,
                        revision,
                        &expected,
                        sessions.scratch.path(),
                        codeana_contracts::graph_archive::OutputLimits {
                            bytes: merged_bytes,
                            facts: 6_000_000,
                        },
                        cancel,
                    )
                    .await?
                }
            };
            for input in &sessions.inputs {
                input.verify()?;
            }
            let analyze_ms = analyzing.elapsed().as_millis() as u64;
            for (worker, _) in &sessions.sessions {
                self.task.event(
                    &TaskId::from(id),
                    &Event::Progress(format!(
                        "{}_worker_memory {}",
                        worker.context.language,
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
                    (revision, started)
                }
                crate::indexing::CandidateOutcome::Rejected(result) => return Ok(*result),
            };
            let publish_ms = publishing.elapsed().as_millis() as u64;
            let ack = if unchanged {
                None
            } else {
                crate::analysis_unit::acknowledge(
                    crate::analysis_unit::Units::Sessions(sessions.iter_mut()),
                    &revision.id,
                )
                .await
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
                    worker_context: sessions[0].0.context.clone(),
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

fn unit_capture(capture: &Capture, effective: &codeana_config::Effective) -> Capture {
    let worlds = if let Some(inputs) = &effective.python_inputs {
        inputs
            .contexts
            .iter()
            .map(|c| c.world(&inputs.roots))
            .collect::<std::collections::BTreeSet<_>>()
    } else if let Some(inputs) = &effective.rust_inputs {
        inputs.crates.iter().map(|c| c.world.clone()).collect()
    } else {
        effective
            .config
            .modules
            .iter()
            .flat_map(|m| ["MAIN", "TEST", "GENERATED"].map(|scope| key("world", &[&m.id, scope])))
            .collect()
    };
    let mut result = capture.clone();
    result.effective = effective.clone();
    result
        .manifest
        .files
        .retain(|s| s.scope == "CONTROL" || worlds.contains(&s.world));
    result
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
        let expected = self
            .index
            .head(&IndexTarget::from(capture.target.clone()))?;
        ensure!(
            req.expected_revision.is_empty() || req.expected_revision == expected.as_str(),
            DomainError::new(ErrorCode::HeadConflict, "")
        );
        let scratch = tempfile::Builder::new()
            .prefix("prepared-index-")
            .tempdir_in(self.index.home())?;
        let count = plan.units.len().max(1) as u64;
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
        let analysis = self.task.analysis.coordinator.lock().unwrap().clone();
        if let Some(coordinator) = &analysis {
            for language in [
                AnalysisLanguage::Rust,
                AnalysisLanguage::Python,
                AnalysisLanguage::Java,
            ] {
                let mut ids = Vec::new();
                for unit in plan.units.iter().filter(|unit| unit.language == language) {
                    let instance = &capture.effective.input_units
                        [unit.prepared_index.context("PREPARED_UNIT_INDEX")?]
                    .prepared
                    .id;
                    let host = coordinator
                        .lock()
                        .unwrap()
                        .host(language)
                        .map_err(DomainError::analysis)?;
                    host.charge(
                        1,
                        0,
                        (instance.len() + 2 * std::mem::size_of::<String>()) as u64,
                    )
                    .map_err(DomainError::analysis)?;
                    ids.push(instance.clone());
                }
                if !ids.is_empty() {
                    coordinator
                        .lock()
                        .unwrap()
                        .configure_instances(language, &ids)
                        .map_err(DomainError::analysis)?;
                }
            }
        }
        let mut sessions = crate::analysis_unit::RunningUnits {
            sessions: vec![],
            inputs: vec![],
            scratch,
        };
        let result: Result<TaskResult> = async {
            let mut additional = vec![];
            let mut planned_units = plan.units.iter();
            for (index, unit) in capture.effective.input_units.iter().enumerate() {
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
                let Some(effective) = &unit.effective else {
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
                let mut child = unit_capture(&capture, effective);
                child.effective.config.resources.index.worker_memory_bytes = Some(memory);
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
                        python_mode: crate::python_inputs::InputMode::Prepared,
                    })
                    .await?;
                let worker = worker::Worker::start_language(
                    None,
                    input.language(),
                    &capture.root,
                    &child.manifest,
                    cancel,
                    memory,
                    binding,
                )
                .await?;
                sessions.inputs.push(input);
                sessions.push((worker, child.manifest));
            }
            let contexts = sessions.iter().map(|(w, _)| &w.context).collect::<Vec<_>>();
            let profile = digest(&(
                codeana_comment_search::identity(),
                &capture.effective.semantic_digest,
                contexts,
                "prepared-inputs-v1",
            ));
            let producer = "codeana-prepared-inputs".to_owned();
            let revision = Revision {
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
                crate::analysis_unit::retire(crate::analysis_unit::Units::Sessions(
                    sessions.iter_mut(),
                ))
                .await?;
                self.stage_reused_revision(id, revision, expected.clone(), producer.clone(), cancel)
                    .await?
            } else if sessions.len() == 1 {
                let (worker, manifest) = &mut sessions[0];
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
                    &mut sessions.sessions,
                    revision,
                    &expected,
                    sessions.scratch.path(),
                    codeana_contracts::graph_archive::OutputLimits {
                        bytes,
                        facts: 6_000_000,
                    },
                    additional,
                    cancel,
                )
                .await?
            };
            for input in &sessions.inputs {
                input.verify()?;
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
                    (revision, started)
                }
                crate::indexing::CandidateOutcome::Rejected(result) => return Ok(*result),
            };
            let publish_ms = publishing.elapsed().as_millis() as u64;
            let ack = if unchanged {
                None
            } else {
                crate::analysis_unit::acknowledge(
                    crate::analysis_unit::Units::Sessions(sessions.iter_mut()),
                    &revision.id,
                )
                .await
            };
            let context = BuildContext {
                language: "multi".into(),
                modules: capture
                    .effective
                    .config
                    .modules
                    .iter()
                    .map(|m| m.id.clone())
                    .collect(),
                source_version: String::new(),
                classpath: vec![],
                producer,
                capabilities: vec![
                    "prepared-inputs-v1".into(),
                    if capture
                        .effective
                        .input_units
                        .iter()
                        .any(|u| u.prepared.mode == InputMode::FilesOnly)
                    {
                        "semantic-inputs-partial"
                    } else {
                        "semantic-inputs-available"
                    }
                    .into(),
                ],
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
                    worker_context: context,
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
}
