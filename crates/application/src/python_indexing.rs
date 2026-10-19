//! Python full analysis and byte-identical reuse through the shared publication boundary.
use super::*;
use crate::indexing::PublishedIndex;
use codeana_contracts::worker::PythonConfig;

impl Engine {
    pub(super) async fn index_python(
        &self,
        id: &str,
        req: &IndexCommand,
        mut capture: Capture,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let started = Instant::now();
        ensure!(self.mock.is_none(), "PYTHON_REAL_WORKER_REQUIRED");

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
        let inputs = capture
            .effective
            .python_inputs
            .clone()
            .context("PYTHON_INPUTS_REQUIRED")?;
        let mut files = Vec::new();
        for root in &inputs.roots {
            for file in &root.files {
                let source = codeana_config::python_inputs::source_path(root, &file.path);
                let blob = capture
                    .blobs
                    .get(&file.sha256)
                    .context("PYTHON_CAPTURE_MISSING")?;
                files.push(python::FrozenFile {
                    root: root.id.clone(),
                    relative: file.path.clone(),
                    source,
                    input: blob.as_ref().to_path_buf(),
                    sha256: file.sha256.clone(),
                    bytes: file.bytes,
                });
            }
        }
        let resources = &capture.effective.config.resources.index;
        let result_bytes = resources.python_result_bytes.unwrap_or(MAX_RESULT as u64);
        let fact_limit = resources.python_fact_limit.unwrap_or(MAX_FACTS as u64);
        let scratch_bytes = resources.python_scratch_bytes.unwrap_or(512 << 20);
        ensure!(
            (65536..=1073741824).contains(&result_bytes)
                && (1..=2000000).contains(&fact_limit)
                && scratch_bytes >= result_bytes
                && scratch_bytes <= 32u64 << 30,
            "PYTHON_OUTPUT_LIMIT_RANGE"
        );
        let source_bytes = files.iter().map(|f| f.bytes).sum::<u64>();
        ensure!(
            source_bytes
                .checked_add(result_bytes)
                .is_some_and(|n| n <= scratch_bytes),
            "PYTHON_SCRATCH_BUDGET"
        );
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
            scratch: scratch.path().into(),
            result_bytes,
            fact_limit,
            scratch_bytes,
        };
        let input = scratch.path().join("inputs.json");
        let bytes = serde_json::to_vec(&config)?;
        ensure!(bytes.len() <= 32 << 20, "PYTHON_INPUT_BUDGET");
        ensure!(
            source_bytes + result_bytes + bytes.len() as u64 <= scratch_bytes,
            "PYTHON_SCRATCH_BUDGET"
        );
        let descriptor = PythonConfig {
            input: input.to_string_lossy().into_owned(),
            sha256: hash(&bytes),
            bytes: bytes.len() as u64,
            result_bytes,
            fact_limit,
            scratch_bytes,
        };
        tokio::task::spawn_blocking(move || std::fs::write(input, bytes)).await??;
        let memory = resources.worker_memory_bytes.unwrap_or(self.worker_memory);
        ensure!(
            memory > 0 && memory <= self.worker_memory,
            DomainError::new(ErrorCode::ProjectWorkerMemoryLimit, "")
        );
        let rust_input = if capture.effective.rust_inputs.is_some() {
            Some(self.prepare_rust_inputs(id, &capture).await?)
        } else {
            None
        };
        let java_enabled = capture
            .effective
            .config
            .modules
            .iter()
            .any(|m| m.language == "java");
        let memory = memory / (1 + u64::from(rust_input.is_some()) + u64::from(java_enabled));
        ensure!(memory > 0, "WORKER_MEMORY_BUDGET");
        let java_capture = if capture
            .effective
            .config
            .modules
            .iter()
            .any(|m| m.language == "java")
        {
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
            let user = self.user.clone();
            let home = self.index.home().to_path_buf();
            Some(tokio::task::spawn_blocking(move || java::prepare(&c, &user, &home)).await??)
        } else {
            None
        };
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
        )
        .await?;
        let mut sessions = vec![(worker, python_manifest)];
        if let Some(input) = &rust_input {
            let worker = worker::Worker::start_language(
                None,
                worker::Language::Rust(&input.descriptor),
                &capture.root,
                &input.manifest,
                cancel,
                memory,
            )
            .await?;
            sessions.push((worker, input.manifest.clone()));
        }
        if let (Some(prepared), Some(c)) = (&prepared, &java_capture) {
            let worker = worker::Worker::start(
                None,
                Some(prepared),
                &capture.root,
                &c.manifest,
                cancel,
                memory,
            )
            .await?;
            sessions.push((worker, c.manifest.clone()));
        }
        let contexts = sessions.iter().map(|(w, _)| &w.context).collect::<Vec<_>>();
        let profile = digest(&(
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
        let unchanged =
            !req.full && capture.effective.config.index.mode != "full" && !expected.is_empty() && {
                let reader = self.index.read(&expected)?;
                reader.revision().profile == profile
                    && reader.revision().manifest.id() == capture.manifest.id()
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
        } else {
            self.tasks.event(
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
                    &mut sessions,
                    revision,
                    &expected,
                    scratch.path(),
                    codeana_contracts::graph_archive::OutputLimits {
                        bytes: scratch_bytes - source_bytes - descriptor.bytes,
                        facts: 6_000_000,
                    },
                    cancel,
                )
                .await?
            }
        };
        if let Some(prepared) = &prepared {
            prepared.verify()?;
        }
        let analyze_ms = analyzing.elapsed().as_millis() as u64;
        for (worker, _) in &sessions {
            self.tasks.event(
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
        if req.strict && !evidence.semantic {
            let mut result = TaskResult::success(Output {
                producer: python::PRODUCER.into(),
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
}
