//! Prepared native workspaces retain independent contexts, sharing one publication.
use super::*;
use crate::indexing::PublishedIndex;
use codeana_config::inputs::InputMode;
use codeana_contracts::{
    graph_rows::{CoverageRow, GraphRow, ProjectMetaRow},
    worker::PythonConfig,
};

fn python_input(capture: &Capture, scratch: &Path) -> Result<PythonConfig> {
    let inputs = capture
        .effective
        .python_inputs
        .clone()
        .context("PYTHON_INPUTS_REQUIRED")?;
    let mut files = vec![];
    for root in &inputs.roots {
        for file in &root.files {
            files.push(python::FrozenFile {
                root: root.id.clone(),
                relative: file.path.clone(),
                source: codeana_config::python_inputs::source_path(root, &file.path),
                input: capture
                    .blobs
                    .get(&file.sha256)
                    .context("PYTHON_CAPTURE_MISSING")?
                    .as_ref()
                    .into(),
                sha256: file.sha256.clone(),
                bytes: file.bytes,
            });
        }
    }
    let limits = &capture.effective.config.resources.index;
    let result_bytes = limits.python_result_bytes.unwrap_or(MAX_RESULT as u64);
    let fact_limit = limits.python_fact_limit.unwrap_or(MAX_FACTS as u64);
    let scratch_bytes = limits.python_scratch_bytes.unwrap_or(512 << 20);
    ensure!(
        (65536..=1073741824).contains(&result_bytes)
            && (1..=2000000).contains(&fact_limit)
            && scratch_bytes <= 32u64 << 30,
        "PYTHON_OUTPUT_LIMIT_RANGE"
    );
    let source_bytes = files.iter().map(|f| f.bytes).sum::<u64>();
    let config = python::AnalysisConfig {
        inputs,
        files,
        excluded: capture
            .manifest
            .files
            .iter()
            .filter(|s| s.language == "python" && s.auxiliary)
            .map(|s| (s.path.clone(), s.world.clone()))
            .collect(),
        scratch: scratch.into(),
        result_bytes,
        fact_limit,
        scratch_bytes,
    };
    let bytes = serde_json::to_vec(&config)?;
    ensure!(
        bytes.len() <= 32 << 20
            && source_bytes
                .checked_add(result_bytes)
                .and_then(|n| n.checked_add(bytes.len() as u64))
                .is_some_and(|n| n <= scratch_bytes),
        "PYTHON_SCRATCH_BUDGET"
    );
    let path = scratch.join("inputs.json");
    std::fs::write(&path, &bytes)?;
    Ok(PythonConfig {
        input: path.to_str().context("INPUT_PATH_UTF8")?.into(),
        sha256: hash(&bytes),
        bytes: bytes.len() as u64,
        result_bytes,
        fact_limit,
        scratch_bytes,
    })
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

impl Engine {
    pub(super) async fn index_prepared(
        &self,
        id: &str,
        req: &IndexCommand,
        mut capture: Capture,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        ensure!(self.mock.is_none(), "PREPARED_REAL_WORKER_REQUIRED");
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
        let count = capture
            .effective
            .input_units
            .iter()
            .filter(|u| u.effective.is_some())
            .count()
            .max(1) as u64;
        let memory = capture
            .effective
            .config
            .resources
            .index
            .worker_memory_bytes
            .unwrap_or(self.worker_memory);
        ensure!(
            memory > 0 && memory <= self.worker_memory,
            "PROJECT_WORKER_MEMORY_LIMIT"
        );
        let memory = memory / count;
        ensure!(memory > 0, "WORKER_MEMORY_BUDGET");
        let mut java_inputs = vec![];
        let mut rust_inputs = vec![];
        let mut sessions = vec![];
        let mut additional = vec![];
        for unit in &capture.effective.input_units {
            self.tasks.event(
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
            let directory = scratch.path().join(&unit.prepared.id);
            std::fs::create_dir(&directory)?;
            let worker = match unit.prepared.language.as_str() {
                "python" => {
                    let c = child.clone();
                    let descriptor =
                        tokio::task::spawn_blocking(move || python_input(&c, &directory)).await??;
                    worker::Worker::start_language(
                        None,
                        worker::Language::Python(&descriptor),
                        &capture.root,
                        &child.manifest,
                        cancel,
                        memory,
                    )
                    .await?
                }
                "rust" => {
                    let input = self.prepare_rust_inputs(id, &child).await?;
                    let worker = worker::Worker::start_language(
                        None,
                        worker::Language::Rust(&input.descriptor),
                        &capture.root,
                        &child.manifest,
                        cancel,
                        memory,
                    )
                    .await?;
                    rust_inputs.push(input);
                    worker
                }
                "java" => {
                    let c = child.clone();
                    let user = self.user.clone();
                    let home = self.index.home().to_path_buf();
                    let prepared =
                        tokio::task::spawn_blocking(move || java::prepare(&c, &user, &home))
                            .await??;
                    let worker = worker::Worker::start(
                        None,
                        Some(&prepared),
                        &capture.root,
                        &child.manifest,
                        cancel,
                        memory,
                    )
                    .await?;
                    java_inputs.push(prepared);
                    worker
                }
                _ => anyhow::bail!("INPUT_LANGUAGE"),
            };
            sessions.push((worker, child.manifest));
        }
        let contexts = sessions.iter().map(|(w, _)| &w.context).collect::<Vec<_>>();
        let profile = digest(&(
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
        let unchanged =
            !req.full && capture.effective.config.index.mode != "full" && !expected.is_empty() && {
                let old = self.index.read(&expected)?;
                old.revision().profile == profile
                    && old.revision().manifest.id() == capture.manifest.id()
            };
        let analyzing = Instant::now();
        let (result_mode, candidate) = if unchanged {
            for (worker, _) in &mut sessions {
                worker.retire_unused().await?;
            }
            let header = AnalysisHeader {
                mode: AnalysisMode::Delta,
                baseline: expected.to_string(),
                manifest: capture.manifest.id(),
                profile,
                producer: producer.clone(),
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
            (AnalysisMode::Delta, candidate)
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
                &mut sessions,
                revision,
                &expected,
                scratch.path(),
                codeana_contracts::graph_archive::OutputLimits {
                    bytes,
                    facts: 6_000_000,
                },
                additional,
                cancel,
            )
            .await?
        };
        for prepared in &java_inputs {
            prepared.verify()?;
        }
        let analyze_ms = analyzing.elapsed().as_millis() as u64;
        let summary = self.index.summary(&candidate).clone();
        let evidence = summary.evidence.clone();
        if req.strict && !evidence.semantic {
            let mut result = TaskResult::success(Output {
                producer: producer.clone(),
                evidence,
                ..Default::default()
            });
            result.gate = GateState::Rejected;
            result.publication = PublicationState::Retained;
            result.exit_code = 3;
            return Ok(result);
        }
        let publishing = Instant::now();
        let revision = self
            .index
            .publish(
                candidate,
                TaskId::from(id),
                std::mem::take(&mut capture.blobs),
                cancel.clone(),
                self.publication_fence(id)?,
            )
            .await?;
        let publish_ms = publishing.elapsed().as_millis() as u64;
        let mut ack = None;
        if !unchanged {
            for (worker, _) in &mut sessions {
                if let Err(error) = worker.ack(&revision.id).await {
                    ack = Some(error.to_string());
                }
            }
        }
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
}
