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
            .publisher
            .head(&IndexTarget::from(capture.target.clone()))?
            .to_string();
        ensure!(
            req.expected_revision.is_empty() || expected == req.expected_revision.as_str(),
            DomainError::new(ErrorCode::HeadConflict, "")
        );
        let scratch = tempfile::Builder::new()
            .prefix("rust-index-")
            .tempdir_in(&self.store.home)?;
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
                .clone(),
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
        let rust_manifest = Manifest {
            files: capture
                .manifest
                .files
                .iter()
                .filter(|s| s.language == "rust" || s.scope == "CONTROL")
                .cloned()
                .collect(),
        };
        let mut rust_worker = worker::Worker::start_language(
            None,
            None,
            Some(&descriptor),
            &capture.root,
            &rust_manifest,
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
            let home = self.store.home.clone();
            Some(tokio::task::spawn_blocking(move || java::prepare(&c, &user, &home)).await??)
        } else {
            None
        };
        let mut java_worker = if let Some(c) = &java_capture {
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
            let store = self.store.clone();
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
        let analyzing = Instant::now();
        let revision = Revision {
            id: String::new(),
            target: capture.target.clone(),
            project: capture.project.clone(),
            checkout: capture.checkout.clone(),
            manifest: capture.manifest.clone(),
            profile: profile.clone(),
            producer: if java_worker.is_some() {
                "codeana-java-rust"
            } else {
                "rust-core"
            }
            .into(),
            created: 0,
        };
        let (result_mode, candidate) = if unchanged {
            rust_worker.retire_unused().await?;
            self.tasks.event(
                &TaskId::from(id),
                &Event::Progress("rust_analyze_unchanged".into()),
            )?;
            let header = AnalysisHeader {
                mode: "delta".into(),
                baseline: expected.clone(),
                manifest: capture.manifest.id(),
                profile: profile.clone(),
                producer: "rust-core".into(),
                replacements: vec![],
            };
            let store = self.store.clone();
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
            (AnalysisMode::Delta, candidate)
        } else if java_worker.is_none() {
            let mut staging = None;
            let mut stream_metrics = None;
            let header = rust_worker
                .analyze_into(
                    &rust_manifest,
                    &profile,
                    &expected,
                    old.as_ref(),
                    full,
                    cancel,
                    |header| {
                        let (sender, records) = codeana_contracts::stream::GraphStream::channel();
                        stream_metrics = Some(sender.metrics.clone());
                        let store = self.store.clone();
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
            (
                header.mode,
                staged.context("CANDIDATE_STREAM_NOT_STARTED")??,
            )
        } else {
            let result = rust_worker
                .analyze(
                    &rust_manifest,
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
            if let (Some(worker), Some(c)) = (&mut java_worker, &java_capture) {
                // Java retains its existing semantic backend. The merge is still one revision.
                let result = worker
                    .analyze(&c.manifest, &profile, &expected, None, true, cancel)
                    .await?;
                let root = scratch.path().to_path_buf();
                archive = tokio::task::spawn_blocking(move || -> Result<_> {
                    let mut combined =
                        GraphArchiveBuilder::new(Some(&root), result_bytes, fact_limit)?;
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
                            world: s.world.clone(),
                            producer: worker.context.producer.clone(),
                            kind: "source".into(),
                            id: s.path.clone(),
                        }),
                );
                if !full {
                    let reader = self.store.read(&expected)?;
                    replacements.extend(
                        reader
                            .revision()
                            .manifest
                            .files
                            .iter()
                            .filter(|s| s.language == "java")
                            .map(|s| Owner {
                                world: s.world.clone(),
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
                mode: if full { "full" } else { "delta" }.into(),
                baseline: expected.clone(),
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
            let store = self.store.clone();
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
            (result_mode, candidate)
        };
        let analyze_ms = analyzing.elapsed().as_millis() as u64;
        self.tasks.event(
            &TaskId::from(id),
            &Event::Progress(format!(
                "rust_worker_memory {}",
                serde_json::to_string(&rust_worker.memory_usage())?
            )),
        )?;
        let summary = candidate.summary.clone();
        let evidence = summary.evidence.clone();
        if req.strict && !evidence.semantic {
            let mut t = TaskResult::success(Output {
                producer: "rust-core".into(),
                evidence,
                ..Default::default()
            });
            t.gate = "rejected".into();
            t.publication = "retained".into();
            t.exit_code = 3;
            return Ok(t);
        }
        self.tasks
            .event(&TaskId::from(id), &Event::Progress("rust_publish".into()))?;
        let publishing = Instant::now();
        let store = self.store.clone();
        let publisher = self.publisher.clone();
        let blobs = std::mem::take(&mut capture.blobs);
        let task = TaskId::from(id);
        let cancellation = cancel.clone();
        let revision = tokio::task::spawn_blocking(move || -> Result<_> {
            let diagnostics = codeana_contracts::performance::Report::new("publish-detail");
            diagnostics.capture.sync(|| -> Result<_> {
                ensure!(
                    !cancellation.is_cancelled(),
                    DomainError::new(ErrorCode::Cancelled, "")
                );
                let _pins = store.pin_blobs(blobs.keys());
                store.put_blob_files(&blobs)?;
                ensure!(
                    !cancellation.is_cancelled(),
                    DomainError::new(ErrorCode::Cancelled, "")
                );
                publisher.publish(candidate, &task)
            })
        })
        .await??;
        let mut ack = if unchanged {
            None
        } else {
            rust_worker
                .ack(&revision.id)
                .await
                .err()
                .map(|e| e.to_string())
        };
        if let Some(worker) = &mut java_worker
            && let Err(e) = worker.ack(&revision.id).await
        {
            ack = Some(e.to_string());
        }
        if let Ok(memory) = serde_json::to_string(&rust_worker.memory_usage()) {
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
                worker_context: rust_worker.context.clone(),
                analyze_ms,
                publish_ms: publishing.elapsed().as_millis() as u64,
                index_elapsed_ms: started.elapsed().as_millis() as u64,
            },
        )
        .await
    }
}
