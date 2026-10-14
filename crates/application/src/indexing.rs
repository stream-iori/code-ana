//! Index phases own their worker/candidate handles; publication separates analysis from post-processing.
use super::*;
use crate::reports::{
    ReportConfig, ReportDiagnostics, ReportDocument, ReportSummary, ReportTiming,
};
use codeana_storage::{StagedCandidate, StagedSummary};
struct IndexSetup {
    prepared: Option<java::PreparedJava>,
    expected: String,
    worker: worker::Worker,
    pool_key: Option<String>,
    undiscovered_warm: bool,
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
    facts: u64,
    manifest: String,
    profile: &'a str,
    producer: &'a str,
    ack_warning: Option<&'a str>,
    statistics: &'a codeana_storage::CandidateStatistics,
}
struct PublishedIndex {
    revision: Revision,
    result_mode: AnalysisMode,
    summary: StagedSummary,
    evidence: Evidence,
    ack: Option<String>,
    worker_context: BuildContext,
    analyze_ms: u64,
    publish_ms: u64,
    index_elapsed_ms: u64,
}
impl Engine {
    pub(super) async fn index(
        &self,
        id: &str,
        req: &IndexCommand,
        mut c: Capture,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let started = Instant::now();
        let mut timings = timings::IndexTimings::start(id);
        let mut setup = self.setup_index(id, req, &c, cancel, &mut timings).await?;
        let AnalyzedCandidate {
            result_mode,
            candidate,
            analyze_ms,
        } = self
            .analyze_index(&c, cancel, &mut timings, &mut setup)
            .await?;
        let IndexSetup {
            prepared,
            expected: _,
            mut worker,
            pool_key,
            undiscovered_warm,
            profile: _,
            full: _,
            old: _,
            unchanged,
        } = setup;
        let limits = prepared
            .as_ref()
            .map(|p| codeana_contracts::java::OutputLimits::from_config(&p.config))
            .transpose()?
            .unwrap_or_default();
        ensure!(
            candidate.summary.fact_count <= limits.facts,
            "JAVA_FACT_BUDGET count={} limit={}",
            candidate.summary.fact_count,
            limits.facts
        );
        let summary = candidate.summary.clone();
        if let Some(timings) = &mut timings {
            timings.phase("candidate_validated");
            timings.storage(&summary.phase_ms);
        }
        let evidence = summary.evidence.clone();
        if req.strict && !evidence.semantic {
            let mut t = TaskResult::success(Output {
                producer: worker.context.producer.clone(),
                evidence,
                ..Default::default()
            });
            t.gate = "rejected".into();
            t.publication = "retained".into();
            t.exit_code = 3;
            return Ok(t);
        }
        ensure!(
            !cancel.is_cancelled(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
        );
        let store = self.store.clone();
        let blobs = std::mem::take(&mut c.blobs);
        let task_id = TaskId::from(id);
        let publisher = self.publisher.clone();
        let cancellation = cancel.clone();
        let publishing = Instant::now();
        let revision = tokio::task::spawn_blocking(move || -> Result<Revision> {
            let diagnostics = codeana_contracts::performance::Report::new("publish-detail");
            diagnostics.capture.sync(|| {
                ensure!(
                    !cancellation.is_cancelled(),
                    codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
                );
                codeana_storage::test_fault(&store.home, "before_publish")?;
                store.put_blob_files(&blobs)?;
                ensure!(
                    !cancellation.is_cancelled(),
                    codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
                );
                publisher.publish(candidate, &task_id)
            })
        })
        .await??;
        let publish_ms = publishing.elapsed().as_millis() as u64;
        if let Some(timings) = &mut timings {
            timings.phase("published");
        }
        // Publication is durable; failure to acknowledge cannot turn it into a failed publication.
        let ack = if unchanged {
            None
        } else {
            worker.ack(&revision.id).await.err().map(|e| e.to_string())
        };
        let worker_context = worker.context.clone();
        if let Some(timings) = &mut timings {
            timings.phase("ack_finished");
        }
        if (!unchanged || undiscovered_warm)
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
                worker,
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
            let captured = c.clone();
            let user = self.user.clone();
            let home = self.store.home.clone();
            Some(
                tokio::task::spawn_blocking(move || java::prepare(&captured, &user, &home))
                    .await??,
            )
        } else {
            None
        };
        if let Some(timings) = timings {
            timings.phase("prepared");
        }
        let expected = self
            .publisher
            .head(&IndexTarget::from(c.target.clone()))?
            .to_string();
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
        // A warm worker is still idle after its previous Ack. For identical captures,
        // leave it idle instead of opening a new analysis context we will never use.
        let identical_capture = !expected.is_empty()
            && !req.full
            && c.effective.config.index.mode != "full"
            && self.store.read(&expected)?.revision().manifest.id() == c.manifest.id();
        let mut undiscovered_warm = false;
        let mut reused = None;
        if self.mock.is_none() && self.user.daemon.java_warm_workers.unwrap_or(true) {
            let idle = self.idle_java.lock().unwrap().take();
            if let (Some(mut idle), Some(prepared), Some(key)) =
                (idle, prepared.as_ref(), pool_key.as_ref())
                && idle.key == *key
                && idle.revision == expected
                && idle.worker.alive().unwrap_or(false)
                && (identical_capture
                    || idle
                        .worker
                        .rediscover(prepared, &c.manifest, cancel)
                        .await
                        .is_ok())
            {
                undiscovered_warm = identical_capture;
                reused = Some(idle.worker);
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
            )
            .await?
        };
        ensure!(
            worker.context.language == "java",
            codeana_model::DomainError::new(codeana_model::ErrorCode::WorkerLanguageMismatch, "")
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
                            || toolchain == &format!("mock-jdk{}", worker.context.source_version),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::MockToolchainUnsupported,
                            ""
                        )
                    );
                }
            }
        }
        let profile = digest(&(
            &c.effective.semantic_digest,
            &worker.context,
            "anatomist-schema32-graph1",
        ));
        if let Some(timings) = timings {
            timings.phase("worker_context");
        }
        let mut full = req.full || c.effective.config.index.mode == "full" || expected.is_empty();
        let mut old = None;
        let mut unchanged = false;
        if !expected.is_empty() && !full {
            let store = self.store.clone();
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
                        codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
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
                            codeana_storage::durable_write(&path, &bytes)?;
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
        if undiscovered_warm && !unchanged {
            let previous_context = digest(&worker.context);
            worker
                .rediscover(
                    prepared.as_ref().context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::JavaRequired,
                        "",
                    ))?,
                    &c.manifest,
                    cancel,
                )
                .await?;
            ensure!(
                digest(&worker.context) == previous_context,
                codeana_model::DomainError::new(codeana_model::ErrorCode::WarmContextChanged, "")
            );
        }
        if let Some(timings) = timings {
            timings.phase("baseline_exported");
        }
        self.tasks.event(
            &TaskId::from(id),
            &Event::Progress(
                if full {
                    "analyze_full"
                } else {
                    "analyze_delta"
                }
                .into(),
            ),
        )?;
        Ok(IndexSetup {
            prepared,
            expected,
            worker,
            pool_key,
            undiscovered_warm,
            profile,
            full,
            old,
            unchanged,
        })
    }
    async fn analyze_index(
        &self,
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
        let undiscovered_warm = setup.undiscovered_warm;
        let analyzing = Instant::now();
        let streaming = prepared.is_some() && !unchanged;
        let analyze_ms;
        let (result_mode, candidate) = if streaming {
            let store = self.store.clone();
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
                if !undiscovered_warm {
                    worker.retire_unused().await?;
                }
                let prepared = prepared.as_ref().context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::JavaRequired,
                    "",
                ))?;
                let limits = codeana_contracts::java::OutputLimits::from_config(&prepared.config)?;
                SpooledAnalysis {
                    header: AnalysisHeader {
                        mode: "delta".into(),
                        baseline: expected.clone(),
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
            let store = self.store.clone();
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
    async fn finish_index(
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
                facts: summary.fact_count,
                manifest: revision.manifest.id(),
                profile: &revision.profile,
                producer: &revision.producer,
                ack_warning: ack.as_deref(),
                statistics: &summary.statistics,
            })?],
            cursor: String::new(),
        });
        t.publication = "published".into();
        if c.effective.config.observability.mode != "off" {
            t.report = id.into();
            let report_summary = ReportSummary {
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
                id: id.into(),
                created: now(),
                revision: revision.id.clone(),
                level: if full { "full" } else { "basic" }.into(),
                summary: report_summary,
                result: t.clone(),
                effective: if full {
                    ReportConfig::Full(Box::new(c.effective.clone()))
                } else {
                    ReportConfig::Basic {
                        config_digest: c.effective.config_digest.clone(),
                        origins: c.effective.origins.clone(),
                    }
                },
                context: worker_context,
                diagnostics: if full {
                    summary.diagnostic_samples.clone()
                } else {
                    vec![]
                },
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
