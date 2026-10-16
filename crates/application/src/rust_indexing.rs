//! Language fan-out joins sealed archives before one atomic store publication.
use super::*;
use crate::indexing::PublishedIndex;
use codeana_contracts::worker::RustConfig;
use codeana_model::rust::{AnalysisConfig, FrozenFile};
#[derive(serde::Serialize)]
struct InputSummary {
    crates: usize,
    published_crates: usize,
    files: usize,
    file_instances: usize,
    source_bytes: u64,
    source_instance_bytes: u64,
    selection: codeana_model::rust::SelectionSummary,
}

struct RustInput {
    scratch: tempfile::TempDir,
    config: AnalysisConfig,
    descriptor: RustConfig,
    manifest: Manifest,
    memory: u64,
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

impl Engine {
    pub(super) async fn index_rust(
        &self,
        id: &str,
        req: &IndexCommand,
        mut capture: Capture,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let started = Instant::now();
        ensure!(self.mock.is_none(), "RUST_REAL_WORKER_REQUIRED");
        let expected = self
            .index
            .head(&IndexTarget::from(capture.target.clone()))?;
        ensure!(
            req.expected_revision.is_empty() || expected == req.expected_revision.as_str(),
            DomainError::new(ErrorCode::HeadConflict, "")
        );
        let input = self.prepare_rust_inputs(id, &capture).await?;
        let workers = self.start_rust_workers(&input, &capture, cancel).await?;
        let baseline = self
            .resolve_rust_baseline(id, req, &capture, &input, &workers, expected)
            .await?;
        let mut attempt = RustAttempt {
            input,
            workers,
            baseline,
        };
        let analyzing = Instant::now();
        let (result_mode, candidate) = self
            .analyze_rust_index(id, &capture, &mut attempt, cancel)
            .await?;
        let analyze_ms = analyzing.elapsed().as_millis() as u64;
        self.tasks.event(
            &TaskId::from(id),
            &Event::Progress(format!(
                "rust_worker_memory {}",
                serde_json::to_string(&attempt.workers.rust.memory_usage())?
            )),
        )?;
        let summary = self.index.summary(&candidate).clone();
        let evidence = summary.evidence.clone();
        if req.strict && !evidence.semantic {
            let mut t = TaskResult::success(Output {
                producer: "rust-core".into(),
                evidence,
                ..Default::default()
            });
            t.gate = codeana_model::GateState::Rejected;
            t.publication = codeana_model::PublicationState::Retained;
            t.exit_code = 3;
            return Ok(t);
        }
        self.tasks
            .event(&TaskId::from(id), &Event::Progress("rust_publish".into()))?;
        let publishing = Instant::now();
        let blobs = std::mem::take(&mut capture.blobs);
        let revision = self
            .index
            .publish(
                candidate,
                TaskId::from(id),
                blobs,
                cancel.clone(),
                self.publication_fence(id)?,
            )
            .await?;
        let mut ack = if attempt.baseline.unchanged {
            None
        } else {
            attempt
                .workers
                .rust
                .ack(&revision.id)
                .await
                .err()
                .map(|e| e.to_string())
        };
        if let Some(worker) = &mut attempt.workers.java
            && let Err(e) = worker.ack(&revision.id).await
        {
            ack = Some(e.to_string());
        }
        if let Ok(memory) = serde_json::to_string(&attempt.workers.rust.memory_usage()) {
            let _ = self.tasks.event(
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
    async fn prepare_rust_inputs(&self, id: &str, capture: &Capture) -> Result<RustInput> {
        let scratch = tempfile::Builder::new()
            .prefix("rust-index-")
            .tempdir_in(self.index.home())?;
        let inputs = capture
            .effective
            .rust_inputs
            .clone()
            .context("RUST_INPUTS_REQUIRED")?;
        let mut files = BTreeMap::<String, FrozenFile>::new();
        for source in capture
            .manifest
            .files
            .iter()
            .filter(|s| s.language == "rust")
        {
            let file = files
                .entry(source.path.clone())
                .or_insert_with(|| FrozenFile {
                    path: source.path.clone(),
                    hash: source.hash.clone(),
                    input: capture.blobs[&source.hash].as_ref().to_path_buf(),
                    crates: vec![],
                });
            file.crates.extend(
                inputs
                    .crates
                    .iter()
                    .filter(|c| c.world == source.world)
                    .map(|c| c.id.clone()),
            );
        }
        let excluded = capture
            .manifest
            .files
            .iter()
            .filter(|s| s.language == "rust" && s.auxiliary)
            .map(|s| (s.path.clone(), s.world.clone()))
            .collect();
        let limits = &capture.effective.config.resources.index;
        let result_bytes = limits.rust_result_bytes.unwrap_or(MAX_RESULT as u64);
        let fact_limit = limits.rust_fact_limit.unwrap_or(MAX_FACTS as u64);
        ensure!(
            (65536..=1073741824).contains(&result_bytes) && (1..=2000000).contains(&fact_limit),
            "RUST_OUTPUT_LIMIT_RANGE"
        );
        let config = AnalysisConfig {
            baseline: None,
            excluded,
            inputs,
            files: files.into_values().collect(),
            mode: capture
                .effective
                .config
                .languages
                .rust
                .as_ref()
                .unwrap()
                .mode
                .parse()?,
            scratch: scratch.path().into(),
            result_bytes,
            fact_limit,
        };
        let config_io = config.clone();
        let input = scratch.path().join("inputs.json");
        let (descriptor, summary) = tokio::task::spawn_blocking(move || -> Result<_> {
            let config = config_io;
            let bytes = serde_json::to_vec(&config)?;
            let mut summary = InputSummary {
                crates: config.inputs.crates.len(),
                published_crates: config.inputs.crates.iter().filter(|c| c.publish).count(),
                files: config.files.len(),
                file_instances: 0,
                source_bytes: 0,
                source_instance_bytes: 0,
                selection: config.inputs.selection.clone(),
            };
            for file in &config.files {
                let size = std::fs::metadata(&file.input)?.len();
                summary.file_instances += file.crates.len();
                summary.source_bytes += size;
                summary.source_instance_bytes += size * file.crates.len() as u64;
            }
            std::fs::write(&input, &bytes)?;
            Ok((
                RustConfig {
                    result_bytes,
                    fact_limit,
                    input: input.to_string_lossy().into_owned(),
                    sha256: hash(&bytes),
                    bytes: bytes.len() as u64,
                },
                summary,
            ))
        })
        .await??;
        self.tasks.event(
            &TaskId::from(id),
            &Event::Progress(format!(
                "rust_input_summary {}",
                serde_json::to_string(&summary)?
            )),
        )?;
        let memory = capture
            .effective
            .config
            .resources
            .index
            .worker_memory_bytes
            .unwrap_or(codeana_model::rust::DEFAULT_WORKER_MEMORY);
        ensure!(
            memory > 0 && memory <= self.worker_memory,
            DomainError::new(ErrorCode::ProjectWorkerMemoryLimit, "")
        );
        let memory = if capture
            .effective
            .config
            .modules
            .iter()
            .any(|m| m.language == "java")
        {
            memory / 2
        } else {
            memory
        };
        let manifest = Manifest {
            files: capture
                .manifest
                .files
                .iter()
                .filter(|s| s.language == "rust" || s.scope == "CONTROL")
                .cloned()
                .collect(),
        };
        Ok(RustInput {
            scratch,
            config,
            descriptor,
            manifest,
            memory,
        })
    }
    async fn start_rust_workers(
        &self,
        input: &RustInput,
        capture: &Capture,
        cancel: &CancellationToken,
    ) -> Result<RustWorkers> {
        let descriptor = &input.descriptor;
        let rust_manifest = &input.manifest;
        let memory = input.memory;
        let rust_worker = worker::Worker::start_language(
            None,
            None,
            Some(descriptor),
            &capture.root,
            rust_manifest,
            cancel,
            memory,
        )
        .await?;
        ensure!(
            rust_worker.context.language == "rust",
            "WORKER_LANGUAGE_MISMATCH"
        );
        let java_capture = if capture
            .effective
            .config
            .modules
            .iter()
            .any(|m| m.language == "java")
        {
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
        let prepared = if let Some(c) = java_capture.clone() {
            let user = self.user.clone();
            let home = self.index.home().to_path_buf();
            Some(tokio::task::spawn_blocking(move || java::prepare(&c, &user, &home)).await??)
        } else {
            None
        };
        let java_worker = if let Some(c) = &java_capture {
            Some(
                worker::Worker::start(
                    None,
                    prepared.as_ref(),
                    &c.root,
                    &c.manifest,
                    cancel,
                    memory,
                )
                .await?,
            )
        } else {
            None
        };
        Ok(RustWorkers {
            rust: rust_worker,
            java: java_worker,
            java_capture,
            prepared,
        })
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
            dependency_inputs,
            &capture.effective.semantic_digest,
            config.inputs.semantic_digest(),
            &rust_worker.context,
            java_worker.as_ref().map(|w| &w.context),
            "graph3-schema9",
        ));
        let mut full =
            req.full || expected.is_empty() || capture.effective.config.index.mode == "full";
        let mut old = None;
        let mut unchanged = false;
        if !full {
            let store = self.index.clone();
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
                let bytes = serde_json::to_vec(&reader.rust_baseline_summary()?)?;
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
        self.tasks.event(
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
            self.stage_unchanged_rust(id, capture, attempt, revision, cancel)
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
        capture: &Capture,
        attempt: &mut RustAttempt,
        revision: Revision,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, crate::indexing::StagedCandidate)> {
        let rust_worker = &mut attempt.workers.rust;
        let expected = attempt.baseline.expected.clone();
        let profile = attempt.baseline.profile.clone();

        rust_worker.retire_unused().await?;
        self.tasks.event(
            &TaskId::from(id),
            &Event::Progress("rust_analyze_unchanged".into()),
        )?;
        let header = AnalysisHeader {
            mode: codeana_model::AnalysisMode::Delta,
            baseline: expected.to_string(),
            manifest: capture.manifest.id(),
            profile: profile.clone(),
            producer: "rust-core".into(),
            replacements: vec![],
        };
        let store = self.index.clone();
        let baseline = expected.clone();
        let cancellation = cancel.clone();
        let candidate = tokio::task::spawn_blocking(move || {
            store.stage_graph_records(
                revision,
                &header,
                std::iter::empty(),
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
        })
        .await??;
        Ok((AnalysisMode::Delta, candidate))
    }
    async fn stage_rust_stream(
        &self,
        id: &str,
        attempt: &mut RustAttempt,
        revision: Revision,
        cancel: &CancellationToken,
    ) -> Result<(AnalysisMode, crate::indexing::StagedCandidate)> {
        let rust_worker = &mut attempt.workers.rust;
        let rust_manifest = &attempt.input.manifest;
        let old = &attempt.baseline.old;
        let full = attempt.baseline.full;
        let expected = attempt.baseline.expected.clone();
        let profile = attempt.baseline.profile.clone();

        let mut staging = None;
        let mut stream_metrics = None;
        let header = rust_worker
            .analyze_into(
                rust_manifest,
                &profile,
                &expected,
                old.as_ref(),
                full,
                cancel,
                |header| {
                    let (sender, records) = codeana_contracts::stream::GraphStream::channel();
                    stream_metrics = Some(sender.metrics.clone());
                    let store = self.index.clone();
                    let revision = revision.clone();
                    let header = header.clone();
                    let baseline = expected.clone();
                    let cancellation = cancel.clone();
                    staging = Some(tokio::task::spawn_blocking(move || {
                        store.stage_graph_records(
                            revision,
                            &header,
                            records,
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
        let staged = match staging {
            Some(task) => Some(task.await?),
            None => None,
        };
        let header = header?;
        if let Some(metrics) = stream_metrics {
            self.tasks.event(
                &TaskId::from(id),
                &Event::Progress(format!(
                    "rust_stream {}",
                    serde_json::to_string(&metrics.summary())?
                )),
            )?;
        }
        Ok((
            header.mode,
            staged.context("CANDIDATE_STREAM_NOT_STARTED")??,
        ))
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
        self.tasks.event(
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
        self.tasks.event(
            &TaskId::from(id),
            &Event::Progress("rust_stage_graph".into()),
        )?;
        let result_mode = header.mode;
        let store = self.index.clone();
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
