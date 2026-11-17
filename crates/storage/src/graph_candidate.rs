//! Private Anatomist graph candidates; catalog publication remains in the parent.
use super::*;
use crate::{graph_calls, graph_store};
use codeana_contracts::graph::Row;
use sha2::Digest;

#[derive(Clone, Copy)]
enum GraphSeed<'a> {
    Analyze(&'a AnalysisHeader),
    Reused(&'a AnalysisHeader),
}
impl GraphSeed<'_> {
    fn header(&self) -> &AnalysisHeader {
        match self {
            Self::Analyze(h) | Self::Reused(h) => h,
        }
    }
    fn is_reused(self) -> bool {
        matches!(self, Self::Reused(_))
    }
}
fn validate_reused_baseline(
    before: &Revision,
    after: &Revision,
    context: Option<&GraphStagingContext>,
) -> Result<()> {
    let equal = match (&before.unit_ownership, &after.unit_ownership) {
        (Some(_), Some(_)) => {
            if let Some(context) = context {
                UnitMergePlan::all_reused_with_budget(before, after, &context.hosts)?;
                true
            } else {
                UnitMergePlan::all_reused(before, after).is_ok()
            }
        }
        (None, None) => before.manifest.files == after.manifest.files,
        _ => false,
    };
    ensure!(
        equal && before.profile == after.profile && before.producer == after.producer,
        DomainError::new(
            ErrorCode::ResultIdentityMismatch,
            "reuse requires the same semantic inputs and ownership"
        )
    );
    Ok(())
}
impl Store {
    pub fn stage_graph_records(
        &self,
        revision: Revision,
        header: &AnalysisHeader,
        records: impl Iterator<Item = Result<Row>>,
        expected: &str,
        cancellation: impl Fn() -> Result<()>,
        check_directory: impl Fn(&Path) -> Result<()>,
    ) -> Result<StagedCandidate> {
        self.stage_graph_records_with_context(
            revision,
            header,
            records,
            expected,
            cancellation,
            check_directory,
            None,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub fn stage_graph_records_with_context(
        &self,
        revision: Revision,
        header: &AnalysisHeader,
        records: impl Iterator<Item = Result<Row>>,
        expected: &str,
        cancellation: impl Fn() -> Result<()>,
        check_directory: impl Fn(&Path) -> Result<()>,
        context: Option<&GraphStagingContext>,
    ) -> Result<StagedCandidate> {
        self.stage_graph_records_inner(
            revision,
            GraphSeed::Analyze(header),
            records,
            expected,
            cancellation,
            check_directory,
            context,
        )
    }
    pub fn stage_reused_graph_with_context(
        &self,
        revision: Revision,
        expected: &str,
        cancellation: impl Fn() -> Result<()>,
        check_directory: impl Fn(&Path) -> Result<()>,
        context: Option<&GraphStagingContext>,
    ) -> Result<StagedCandidate> {
        let header = AnalysisHeader {
            mode: AnalysisMode::Delta,
            baseline: expected.into(),
            manifest: revision.manifest.id(),
            profile: revision.profile.clone(),
            producer: revision.producer.clone(),
            replacements: vec![],
        };
        self.stage_graph_records_inner(
            revision,
            GraphSeed::Reused(&header),
            std::iter::empty(),
            expected,
            cancellation,
            check_directory,
            context,
        )
    }
    #[allow(clippy::too_many_arguments)]
    fn stage_graph_records_inner(
        &self,
        mut revision: Revision,
        seed: GraphSeed<'_>,
        records: impl Iterator<Item = Result<Row>>,
        expected: &str,
        cancellation: impl Fn() -> Result<()>,
        check_directory: impl Fn(&Path) -> Result<()>,
        context: Option<&GraphStagingContext>,
    ) -> Result<StagedCandidate> {
        let header = seed.header();
        if let Some(ledger) = &revision.unit_ownership {
            ledger
                .validate(&revision.manifest)
                .map_err(anyhow::Error::msg)?;
        }
        header
            .validate(&revision.manifest)
            .map_err(anyhow::Error::msg)?;
        ensure!(
            header.profile == revision.profile && header.producer == revision.producer,
            codeana_model::DomainError::new(codeana_model::ErrorCode::ResultIdentityMismatch, "")
        );
        ensure!(
            self.head(&revision.target)? == expected,
            codeana_model::DomainError::new(codeana_model::ErrorCode::HeadConflict, "")
        );
        let baseline = if header.mode == "delta" {
            ensure!(
                header.baseline == expected,
                codeana_model::DomainError::new(codeana_model::ErrorCode::BaselineMismatch, "")
            );
            let r = self.read(expected)?;
            ensure!(
                r.revision.profile == revision.profile,
                codeana_model::DomainError::new(codeana_model::ErrorCode::ProfileMismatch, "")
            );
            if seed.is_reused() {
                validate_reused_baseline(&r.revision, &revision, context)?;
            }
            Some(r)
        } else {
            None
        };
        revision.id = new_id("rev");
        revision.created = now();
        let directory = {
            let mut lock = self.coord.lock().unwrap();
            let directory = tempfile::Builder::new()
                .prefix(&format!("{}-", revision.id))
                .tempdir_in(self.home.join("candidates"))?;
            let id = directory
                .path()
                .file_name()
                .context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::CandidateName,
                    "",
                ))?
                .to_string_lossy()
                .into_owned();
            lock.staged_candidates.insert(id.clone());
            CandidateDirectory {
                directory,
                _pin: CandidatePin {
                    id,
                    coord: self.coord.clone(),
                },
            }
        };
        let check = || {
            cancellation()?;
            check_directory(directory.path())
        };
        check()?;
        let mut phase_ms = BTreeMap::new();
        let mut started = std::time::Instant::now();
        let mut db = Connection::open(directory.path().join("facts.db"))?;
        if let Some(source) = &baseline {
            let backup = rusqlite::backup::Backup::new(&source.connection, &mut db)?;
            loop {
                check()?;
                match backup.step(128)? {
                    rusqlite::backup::StepResult::Done => break,
                    rusqlite::backup::StepResult::More => {}
                    rusqlite::backup::StepResult::Busy | rusqlite::backup::StepResult::Locked => {
                        std::thread::sleep(Duration::from_millis(1))
                    }
                    _ => anyhow::bail!(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::SqliteBackupStep,
                        ""
                    )),
                }
            }
        }
        graph_store::initialize(&db)?;
        db.execute_batch("PRAGMA journal_mode=OFF;PRAGMA synchronous=OFF;PRAGMA locking_mode=EXCLUSIVE;PRAGMA cache_size=-64000;PRAGMA temp_store=FILE;PRAGMA foreign_keys=ON;")?;
        if baseline.is_some() {
            crate::graph_comments::validate_baseline(&db)?;
        }
        let stage_path = directory.path().join("stage.db");
        let stage = Connection::open(&stage_path)?;
        graph_store::initialize_stage(&stage)?;
        // The retained baseline catalogue is available to reference resolution without
        // importing or materializing any serialized Fact IR.
        if baseline.is_some() {
            seed_baseline(&stage, &db, seed, &check)?;
        }
        phase_ms.insert(
            "candidate_setup_backup".into(),
            started.elapsed().as_millis() as u64,
        );
        started = std::time::Instant::now();
        stage.execute_batch("BEGIN")?;
        let sources: BTreeMap<_, _> = revision
            .manifest
            .files
            .iter()
            .map(|s| ((s.path.as_str(), s.world.as_str()), s))
            .collect();
        let replaced: BTreeSet<_> = header
            .replacements
            .iter()
            .filter(|o| o.kind == "source")
            .map(|o| (o.id.as_str(), o.world.as_str()))
            .collect();
        let mut positions = 0;
        let mut comment_source = crate::graph_comments::SourceCache::default();
        for row in records {
            if positions % 256 == 0 {
                check()?;
            }
            let row = row?;
            let owner = validate_record(&row, &sources, &replaced, header.mode == "delta")?;
            if matches!(
                codeana_contracts::graph::Table::try_from(row.table),
                Ok(codeana_contracts::graph::Table::Comment
                    | codeana_contracts::graph::Table::CommentLink)
            ) {
                crate::graph_comments::stage(
                    &stage,
                    &row,
                    &revision.manifest,
                    context,
                    &mut comment_source,
                    &check,
                )?;
            } else {
                graph_store::insert_with_world(
                    &stage,
                    &row,
                    owner.map(|s| s.world.as_str()).unwrap_or(&row.world),
                )?;
            }
            positions += 1;
            if positions % 10000 == 0 {
                stage.execute_batch("COMMIT;BEGIN")?;
            }
        }
        stage.execute_batch("COMMIT")?;
        phase_ms.insert(
            "record_validate_ingest".into(),
            started.elapsed().as_millis() as u64,
        );
        started = std::time::Instant::now();
        check()?;
        stage.execute_batch("BEGIN")?;
        let dropped = graph_store::resolve(&stage)?;
        graph_store::prepare_identities(&stage, &check)?;
        stage.execute_batch("COMMIT")?;
        phase_ms.insert(
            "stage_resolve_identities".into(),
            started.elapsed().as_millis() as u64,
        );
        started = std::time::Instant::now();
        drop(stage);
        db.execute(
            "ATTACH DATABASE ? AS staged",
            [stage_path.to_string_lossy().as_ref()],
        )?;
        db.execute_batch("BEGIN IMMEDIATE")?;
        let affected = if baseline.is_some() {
            Some(
                header
                    .replacements
                    .iter()
                    .map(|o| o.id.clone())
                    .collect::<BTreeSet<_>>(),
            )
        } else {
            None
        };
        let structures = if baseline.is_none() {
            graph_store::suspend_bulk(&db)?
        } else {
            Vec::new()
        };
        if affected.is_some() {
            replace_affected(&db, &header.replacements)?;
        }
        graph_store::promote(&db)?;
        crate::graph_comments::promote(&db, &revision.manifest, context, &check)?;
        db.execute(
            "INSERT OR REPLACE INTO project_meta VALUES('schema_version',?)",
            [graph_store::SOURCE_SCHEMA_VERSION.to_string()],
        )?;
        graph_calls::rebuild(&db, affected.as_ref(), &check)?;
        rebuild_wiring(&db, &check)?;
        if baseline.is_none() {
            graph_store::restore_bulk(&db, &structures, &check)?;
        }
        rebuild_dependencies(&db, &check)?;
        crate::graph_compact::seal(&db, &check)?;
        db.execute(
            "INSERT OR REPLACE INTO project_meta VALUES('codeana.revision',?)",
            [&revision.id],
        )?;
        db.execute(
            "INSERT OR REPLACE INTO project_meta VALUES('codeana.profile',?)",
            [&revision.profile],
        )?;
        db.execute(
            "INSERT OR REPLACE INTO project_meta VALUES('codeana.dropped_dangling',?)",
            [dropped.to_string()],
        )?;
        db.execute_batch("COMMIT;DETACH DATABASE staged;").context(
            codeana_model::DomainError::new(codeana_model::ErrorCode::GraphPromotionDetach, ""),
        )?;
        std::fs::remove_file(stage_path)?;
        phase_ms.insert(
            "promotion_calls_wiring".into(),
            started.elapsed().as_millis() as u64,
        );
        started = std::time::Instant::now();
        check()?;
        let integrity: String = db.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
        ensure!(
            integrity == "ok",
            codeana_model::DomainError::new(codeana_model::ErrorCode::CorruptCandidate, "")
        );
        ensure!(
            !db.prepare("PRAGMA foreign_key_check")?.exists([])?,
            codeana_model::DomainError::new(codeana_model::ErrorCode::GraphForeignKeyCheck, "")
        );
        db.execute(
            "INSERT INTO node_names(node_names) VALUES('integrity-check')",
            [],
        )?;
        db.execute(
            "INSERT INTO comment_terms(comment_terms,rank) VALUES('integrity-check',1)",
            [],
        )?;
        let mut summary = StagedSummary {
            evidence: Evidence::complete(),
            ..Default::default()
        };
        for table in [
            "nodes",
            "edges",
            "call_sites",
            "call_site_targets",
            "annotations",
            "annotation_meta_owned",
            "comments",
            "comment_links",
        ] {
            let count: i64 =
                db.query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0))?;
            summary.counts.insert(table.into(), count as u64);
            summary.fact_count += count as u64;
        }
        let counts = crate::graph_query::overview_counts(&db)?;
        db.execute(
            "INSERT OR REPLACE INTO project_meta VALUES('codeana.overview_counts',?)",
            [serde_json::to_string(&counts)?],
        )?;
        let incomplete: i64 = db.query_row(
            "SELECT count(DISTINCT source_file) FROM analysis_coverage WHERE status<>'complete' AND coalesce(source_file,'') NOT IN ('','*')",
            [],
            |r| r.get(0),
        )?;
        if incomplete > 0 {
            summary.evidence.semantic = false;
            summary
                .evidence
                .reasons
                .push(format!("coverage:partial_files:{incomplete}"));
        }
        let incomplete_contexts: i64 = db.query_row(
            "SELECT count(*) FROM (SELECT DISTINCT module,scope,language,provider_id,world FROM analysis_coverage WHERE status<>'complete' AND coalesce(source_file,'') IN ('','*'))",
            [], |r| r.get(0))?;
        if incomplete_contexts > 0 {
            summary.evidence.semantic = false;
            summary
                .evidence
                .reasons
                .push(format!("coverage:partial_contexts:{incomplete_contexts}"));
        }
        summary.analysis_mode = db
            .query_row(
                "SELECT value FROM project_meta WHERE key='analysis_mode'",
                [],
                |r| r.get(0),
            )
            .optional()?;
        summary.classpath_status = db
            .query_row(
                "SELECT value FROM project_meta WHERE key='classpath_status'",
                [],
                |r| r.get(0),
            )
            .optional()?;
        let fallback: bool = db.query_row("SELECT EXISTS(SELECT 1 FROM project_meta WHERE key='dependency_mode' AND value='source_only_fallback')", [], |r| r.get(0))?;
        if fallback {
            summary.evidence.semantic = false;
            summary
                .evidence
                .reasons
                .push("CLASSPATH_UNAVAILABLE".into());
        }
        summary.diagnostic_rows =
            db.query_row("SELECT count(*) FROM index_diagnostics", [], |r| {
                r.get::<_, i64>(0)
            })? as u64;
        summary.diagnostic_total = db.query_row(
            "SELECT coalesce(sum(occurrence_count),0) FROM index_diagnostics",
            [],
            |r| r.get::<_, i64>(0),
        )? as u64;
        summary.diagnostic_groups = db
            .prepare("SELECT code,sum(occurrence_count) FROM index_diagnostics GROUP BY code ORDER BY sum(occurrence_count) DESC,code LIMIT 20")?
            .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)? as u64)))?
            .collect::<rusqlite::Result<_>>()?;
        let group_count = db.query_row(
            "SELECT count(DISTINCT code) FROM index_diagnostics",
            [],
            |r| r.get::<_, i64>(0),
        )? as u64;
        summary.diagnostic_groups_omitted = group_count.saturating_sub(20);
        phase_ms.insert(
            "graph_integrity_summary".into(),
            started.elapsed().as_millis() as u64,
        );
        started = std::time::Instant::now();
        summary.statistics = prepare_statistics(&db, &cancellation)?;
        // Close all prepared statements and FTS cursors before changing journal
        // mode. A fresh connection cannot retain an OFF-mode table read lock.
        db.flush_prepared_statement_cache();
        db.execute_batch("PRAGMA locking_mode=NORMAL;")?;
        drop(db);
        let db = Connection::open(directory.path().join("facts.db"))?;
        db.execute_batch(
            "PRAGMA journal_mode=WAL;PRAGMA synchronous=NORMAL;PRAGMA wal_checkpoint(TRUNCATE);",
        )
        .context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::GraphFinalizeDurability,
            "",
        ))?;
        phase_ms.insert(
            "statistics_finalize".into(),
            started.elapsed().as_millis() as u64,
        );
        summary.phase_ms = phase_ms;
        check()?;
        drop(db);
        Ok(StagedCandidate {
            directory,
            revision,
            expected: expected.into(),
            _baseline: baseline,
            summary,
        })
    }
}

fn validate_record<'a>(
    row: &Row,
    sources: &BTreeMap<(&str, &str), &'a SourceUnit>,
    replaced: &BTreeSet<(&str, &str)>,
    delta: bool,
) -> Result<Option<&'a SourceUnit>> {
    use codeana_contracts::graph::{Table, cell};
    let table = Table::try_from(row.table).context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::GraphTableUnknown,
        "",
    ))?;
    let text = |index: usize| -> Result<Option<&str>> {
        match row.cells.get(index).and_then(|c| c.value.as_ref()) {
            None => Ok(None),
            Some(cell::Value::Text(v)) => Ok(Some(v)),
            _ => anyhow::bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::GraphTextColumn,
                format!("{table:?}:{index}")
            )),
        }
    };
    let mut captured_source = None;
    let columns = codeana_graph_io::scope::source_columns(table).map(|c| (c.source, c.identity));
    if let Some((source, identity)) = columns
        && let Some(path) = text(source)?.filter(|p| !p.is_empty() && *p != "*")
    {
        ensure!(
            safe_path(path),
            codeana_model::DomainError::new(codeana_model::ErrorCode::GraphSourceUnsafe, "")
        );
        let wanted = identity
            .map(|(module, scope)| -> Result<_> { Ok((text(module)?, text(scope)?)) })
            .transpose()?;
        let module_matches = |module: &str, unit: &SourceUnit| {
            module == unit.module
                || unit.module.is_empty() && unit.language.is_empty()
                || module == "*" && matches!(table, Table::Diagnostic | Table::Coverage)
        };
        let scope_matches = |scope: &str, unit: &SourceUnit| {
            scope == unit.scope
                || scope == "*" && matches!(table, Table::Diagnostic | Table::Coverage)
                || unit.scope == "RESOURCE" && matches!(scope, "MAIN" | "TEST")
        };
        let unit = sources
            .get(&(path, row.world.as_str()))
            .copied()
            .or_else(|| {
                if row.world.is_empty() {
                    let mut candidates = sources
                        .range((path, "")..)
                        .take_while(|((p, _), _)| *p == path)
                        .map(|(_, unit)| *unit);
                    let first = candidates.next()?;
                    // Java's existing wire rows may omit world. Match their
                    // explicit owner rather than an arbitrary source at this path.
                    // If no owner matches, keep the established precise error below.
                    let mut matching_module = None;
                    for unit in std::iter::once(first).chain(candidates) {
                        let (module, scope) = wanted.unwrap_or((None, None));
                        if module.is_none_or(|m| module_matches(m, unit)) {
                            matching_module.get_or_insert(unit);
                            if scope.is_none_or(|s| scope_matches(s, unit)) {
                                return Some(unit);
                            }
                        }
                    }
                    matching_module.or(Some(first))
                } else {
                    None
                }
            })
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::GraphSourceNotCaptured,
                "",
            ))?;
        if let Some((module, scope)) = identity {
            if let Some(module) = text(module)? {
                ensure!(
                    module_matches(module, unit),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::GraphModuleMismatch,
                        format!(
                            "table={table:?} path={path} expected={} actual={module}",
                            unit.module
                        )
                    )
                );
            }
            // Resource records inherit the owning compilation scope.
            if let Some(scope) = text(scope)? {
                ensure!(
                    scope_matches(scope, unit),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::GraphScopeMismatch,
                        ""
                    )
                );
            }
        }
        if row.world.is_empty() {
            let compatible = sources
                .range((path, "")..)
                .take_while(|((p, _), _)| *p == path)
                .filter(|(_, s)| {
                    wanted.is_none_or(|(module, scope)| {
                        module.is_none_or(|m| module_matches(m, s))
                            && scope.is_none_or(|sc| scope_matches(sc, s))
                    })
                })
                .count();
            ensure!(
                compatible == 1,
                DomainError::new(ErrorCode::GraphSourceWorldRequired, path)
            );
        }
        if matches!(table, Table::SourceState | Table::AnalysisDependency) {
            ensure!(
                unit.language == "rust" && text(1)? == Some(unit.world.as_str()),
                DomainError::new(
                    ErrorCode::GraphSourceWorldRequired,
                    "Rust state/dependency owner mismatch"
                )
            );
        }
        captured_source = Some(unit);
        if delta {
            ensure!(
                replaced.contains(&(path, unit.world.as_str())),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::GraphSourceNotReplaced,
                    ""
                )
            );
        }
    }
    if table == Table::ProjectMeta
        && let Some(key) = text(0)?
    {
        ensure!(
            !matches!(
                key,
                "schema_version"
                    | "codeana.revision"
                    | "codeana.profile"
                    | "codeana.comment_tokenizer"
                    | "codeana.overview_counts"
            ),
            codeana_model::DomainError::new(codeana_model::ErrorCode::GraphMetaReserved, "")
        );
    }
    Ok(captured_source)
}

fn replace_affected(db: &Connection, owners: &[Owner]) -> Result<()> {
    db.execute_batch("CREATE TEMP TABLE graph_replaced(source_file TEXT,world TEXT,PRIMARY KEY(source_file,world));")?;
    for owner in owners {
        db.execute(
            "INSERT OR IGNORE INTO graph_replaced VALUES(?,?)",
            params![owner.id, owner.world.as_str()],
        )?;
    }
    // Retain matching hash/ordinal pairs exactly as Anatomist incremental promotion does.
    db.execute_batch("DELETE FROM graph_comments WHERE EXISTS(SELECT 1 FROM source_files f JOIN graph_replaced r ON r.source_file=f.path WHERE f.file_pk=graph_comments.source_file AND r.world=graph_comments.world); DELETE FROM edges WHERE ((edges.source_file,edges.world) IN(SELECT source_file,world FROM graph_replaced) OR edges.producer_id='derived-wiring') AND NOT EXISTS(SELECT 1 FROM staged.stage_edges s WHERE s.relation<>'CALLS' AND s.fact_hash=edges.fact_hash AND s.fact_ordinal=edges.fact_ordinal AND s.world=edges.world);
      DELETE FROM annotations WHERE (annotations.source_file,annotations.world) IN(SELECT source_file,world FROM graph_replaced) AND NOT EXISTS(SELECT 1 FROM staged.stage_annotations s WHERE s.fact_hash=annotations.fact_hash AND s.fact_ordinal=annotations.fact_ordinal AND s.world=annotations.world);
      DELETE FROM annotation_meta_owned WHERE (source_file,world) IN(SELECT source_file,world FROM graph_replaced);
      DELETE FROM nodes WHERE (source_file,world) IN(SELECT source_file,world FROM graph_replaced) AND id NOT IN(SELECT id FROM staged.stage_nodes);
      DELETE FROM file_cache WHERE (source_file,world) IN(SELECT source_file,world FROM graph_replaced);
      DELETE FROM rust_analysis_dependencies WHERE (source_file,world) IN(SELECT source_file,world FROM graph_replaced);
      DELETE FROM rust_source_states WHERE (source_file,world) IN(SELECT source_file,world FROM graph_replaced);
      DELETE FROM analysis_coverage WHERE (source_file,world) IN(SELECT source_file,world FROM graph_replaced);
      DELETE FROM analysis_coverage WHERE coalesce(source_file,'') IN ('','*') AND EXISTS(SELECT 1 FROM staged.stage_coverage s WHERE coalesce(s.source_file,'') IN ('','*') AND s.module IS analysis_coverage.module AND s.scope IS analysis_coverage.scope AND s.provider_id IS analysis_coverage.provider_id AND s.capability=analysis_coverage.capability);
      DELETE FROM index_diagnostics;
      DELETE FROM index_providers;
      DELETE FROM project_meta WHERE key LIKE 'codeana.context.%' AND EXISTS(SELECT 1 FROM staged.stage_meta WHERE key LIKE 'codeana.context.%') AND key NOT IN(SELECT key FROM staged.stage_meta);")?;
    Ok(())
}

fn seed_baseline(
    stage: &Connection,
    db: &Connection,
    seed: GraphSeed<'_>,
    check: &dyn Fn() -> Result<()>,
) -> Result<()> {
    let header = seed.header();
    stage.execute(
        "ATTACH DATABASE ? AS baseline",
        [db.path().context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::BaselineDatabasePath,
            "",
        ))?],
    )?;
    stage.execute_batch(
        "CREATE TEMP TABLE replaced(source_file TEXT,world TEXT,PRIMARY KEY(source_file,world)); CREATE TEMP TABLE baseline_policy(reused INTEGER NOT NULL);",
    )?;
    stage.execute("INSERT INTO baseline_policy VALUES(?)", [seed.is_reused()])?;
    for owner in &header.replacements {
        ensure!(
            owner.kind == "source",
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::GraphReplacementSourceRequired,
                ""
            )
        );
        stage.execute(
            "INSERT OR IGNORE INTO replaced VALUES(?,?)",
            params![owner.id, owner.world.as_str()],
        )?;
    }
    check()?;
    stage.execute_batch("INSERT INTO stage_nodes(id,symbol_id,domain,language,provider_id,entity_kind,language_kind,label,kind,qualified_name,package,namespace,source_file,source_location,begin_line,begin_column,end_line,end_column,source_ordinal,module,scope,metadata,producer_id,world)
      SELECT id,symbol_id,domain,language,provider_id,entity_kind,language_kind,label,kind,qualified_name,package,namespace,source_file,source_location,begin_line,begin_column,end_line,end_column,source_ordinal,module,scope,metadata,producer_id,world FROM baseline.nodes WHERE (source_file,world) NOT IN(SELECT source_file,world FROM replaced);
      INSERT INTO stage_declarations(symbol_id,domain,language,provider_id,entity_kind,language_kind,qualified_name,label,kind,declaration_kind,type_kind,visibility,modifiers,declared_modifiers,implicit_modifiers,declaring_type,namespace,source_file,source_location,begin_line,begin_column,end_line,end_column,module,scope,nesting_depth,direct_member,synthetic,binding_resolved,producer_id,world)
      SELECT symbol_id,domain,language,provider_id,entity_kind,language_kind,qualified_name,label,kind,declaration_kind,type_kind,visibility,modifiers,declared_modifiers,implicit_modifiers,declaring_type,declaration_namespace,source_file,declaration_source_location,declaration_begin_line,declaration_begin_column,declaration_end_line,declaration_end_column,module,scope,nesting_depth,direct_member,synthetic,binding_resolved,producer_id,world FROM baseline.nodes WHERE declaration_kind IS NOT NULL AND (source_file,world) NOT IN(SELECT source_file,world FROM replaced);
      INSERT INTO stage_edges(source_ref,target_ref,external_target_fqn,external_target_symbol,external_target_language,external_target_provider_id,relation,semantic,mechanism,language,provider_id,call_kind,confidence,resolution,context,is_external,source_file,source_location,begin_line,begin_column,end_line,end_column,source_ordinal,syntax_target,receiver_static_type,metadata,source_module,source_scope,source_is_key,target_is_key,resolved_source,resolved_target,producer_id,world)
      SELECT e.source_id,e.target_id,e.external_target_fqn,e.external_target_symbol,e.external_target_language,e.external_target_provider_id,e.relation,e.semantic,e.mechanism,e.language,e.provider_id,e.call_kind,e.confidence,e.resolution,e.context,e.is_external,e.source_file,e.source_location,e.begin_line,e.begin_column,e.end_line,e.end_column,e.source_ordinal,e.syntax_target,e.receiver_static_type,e.metadata,n.module,n.scope,1,1,e.source_id,e.target_id,e.producer_id,e.world FROM baseline.edges e JOIN baseline.nodes n ON n.id=e.source_id WHERE e.producer_id<>'derived-wiring' AND (e.source_file IS NOT NULL OR (SELECT reused FROM baseline_policy)) AND (e.source_file,e.world) NOT IN(SELECT source_file,world FROM replaced);
      INSERT INTO stage_annotations(node_ref,annotation_fqn,raw_name,attributes,target_kind,target_path,language,provider_id,mechanism,resolution_status,source_file,source_location,begin_line,begin_column,end_line,end_column,source_module,source_scope,node_is_key,resolved_node,producer_id,world)
      SELECT a.node_id,a.annotation_fqn,a.raw_name,a.attributes,a.target_kind,a.target_path,a.language,a.provider_id,a.mechanism,a.resolution_status,a.source_file,a.source_location,a.begin_line,a.begin_column,a.end_line,a.end_column,n.module,n.scope,1,a.node_id,a.producer_id,a.world FROM baseline.annotations a JOIN baseline.nodes n ON n.id=a.node_id WHERE (a.source_file IS NOT NULL OR (SELECT reused FROM baseline_policy)) AND (a.source_file,a.world) NOT IN(SELECT source_file,world FROM replaced);
      INSERT INTO stage_annotation_meta(annotation_fqn,meta_annotation_fqn,raw_name,language,provider_id,mechanism,resolution_status,source_file,source_location,producer_id,world) SELECT annotation_fqn,meta_annotation_fqn,raw_name,language,provider_id,mechanism,resolution_status,source_file,source_location,producer_id,world FROM baseline.annotation_meta_owned WHERE (source_file IS NOT NULL OR (SELECT reused FROM baseline_policy)) AND (source_file,world) NOT IN(SELECT source_file,world FROM replaced);
      INSERT INTO stage_file_cache SELECT provider_id,source_file,hash,schema_version,node_count,edge_count,file_size,contract_hash,world FROM baseline.file_cache WHERE (source_file,world) NOT IN(SELECT source_file,world FROM replaced);
      INSERT INTO stage_coverage SELECT * FROM baseline.analysis_coverage WHERE (source_file IS NOT NULL OR (SELECT reused FROM baseline_policy)) AND (source_file,world) NOT IN(SELECT source_file,world FROM replaced) AND ((SELECT reused FROM baseline_policy) OR NOT (coalesce(source_file,'') IN ('','*') AND codes LIKE '%CLASSPATH_UNAVAILABLE%'));
      INSERT INTO stage_diagnostics SELECT severity,code,phase,source_file,module,scope,symbol,language,provider_id,provider_reason,reason_code,sample_details,occurrence_count,sample,world FROM baseline.index_diagnostics WHERE (code<>'CLASSPATH_UNAVAILABLE' OR (SELECT reused FROM baseline_policy)) AND (source_file IS NOT NULL OR (SELECT reused FROM baseline_policy)) AND (source_file,world) NOT IN(SELECT source_file,world FROM replaced);
      INSERT INTO stage_providers SELECT * FROM baseline.index_providers;")?;
    // Persisted call sites are the canonical source; seed their exact range and
    // targets, including unresolved sites, without manufacturing method CALLS.
    stage.execute_batch("INSERT INTO stage_call_sites(source_ref,target_ref,external_target_fqn,external_target_symbol,external_target_language,external_target_provider_id,relation,language,provider_id,call_kind,confidence,is_external,source_file,begin_line,begin_column,end_line,end_column,source_ordinal,syntax_target,receiver_static_type,context,metadata,source_module,source_scope,source_is_key,target_is_key,resolved_source,resolved_target,producer_id,world)
      SELECT o.caller_id,t.target_id,t.external_target_fqn,t.external_target_symbol,t.external_target_language,t.external_target_provider_id,'CALLS',c.language,c.provider_id,c.dispatch_kind,t.confidence,t.target_id IS NULL,o.source_file,c.begin_line,c.begin_column,c.end_line,c.end_column,c.ordinal,c.syntax_target,c.receiver_static_type,c.context,c.metadata,n.module,n.scope,1,1,o.caller_id,t.target_id,c.producer_id,o.world FROM baseline.call_sites c JOIN baseline.call_site_owners o USING(owner_pk) JOIN baseline.nodes n ON n.id=o.caller_id LEFT JOIN baseline.call_site_targets t ON t.call_site_pk=c.site_pk WHERE (o.source_file,o.world) NOT IN(SELECT source_file,world FROM replaced);")?;
    check()?;
    stage.execute_batch("DETACH DATABASE baseline;")?;
    Ok(())
}

fn rebuild_dependencies(db: &Connection, check: &dyn Fn() -> Result<()>) -> Result<()> {
    check()?;
    db.execute_batch("DELETE FROM file_dependencies;DELETE FROM symbol_dependencies;")?;
    db.execute_batch(include_str!("anatomist-dependencies.sql"))?;
    let mut statement = db.prepare(include_str!("anatomist-symbol-dependencies.sql"))?;
    let mut rows = statement.query([])?;
    let mut insert = db.prepare_cached(
        "INSERT OR IGNORE INTO symbol_dependencies(target_key,source_file,world) VALUES(?,?,?)",
    )?;
    let mut count = 0usize;
    while let Some(row) = rows.next()? {
        if count.is_multiple_of(2048) {
            check()?;
        }
        count += 1;
        let value: String = row.get(2)?;
        let file: String = row.get(1)?;
        let key = sha2::Sha256::digest(value.as_bytes());
        let world: String = row.get(4)?;
        insert.execute(params![&key[..8], file, world])?;
    }
    Ok(())
}

fn rebuild_wiring(db: &Connection, check: &dyn Fn() -> Result<()>) -> Result<()> {
    db.execute("DELETE FROM edges WHERE producer_id='derived-wiring'", [])?;
    let mut implementations = BTreeMap::<String, BTreeSet<String>>::new();
    let mut statement=db.prepare("SELECT source_id,target_id FROM edges WHERE relation='IMPLEMENTS' AND is_external=0 AND source_id IS NOT NULL AND target_id IS NOT NULL ORDER BY id")?;
    for row in statement.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))? {
        let (source, target) = row?;
        implementations.entry(target).or_default().insert(source);
    }
    drop(statement);
    let mut statement=db.prepare("SELECT source_id,target_id,source_file,source_location,world FROM edges WHERE relation='INJECTS' AND is_external=0 AND source_id IS NOT NULL AND target_id IS NOT NULL ORDER BY id")?;
    let mut rows = statement.query([])?;
    while let Some(row) = rows.next()? {
        check()?;
        let source: String = row.get(0)?;
        let target: String = row.get(1)?;
        let Some(candidates) = implementations.get(&target) else {
            continue;
        };
        let confidence = if candidates.len() == 1 {
            "INFERRED"
        } else {
            "AMBIGUOUS"
        };
        let file: Option<String> = row.get(2)?;
        let location: Option<String> = row.get(3)?;
        let world: String = row.get(4)?;
        let metadata = format!(
            "{{\"via\":\"injection\",\"source\":{},\"candidates\":{}}}",
            serde_json::to_string(&target)?,
            serde_json::to_string(candidates)?
        );
        for candidate in candidates {
            let exists:bool=db.query_row("SELECT EXISTS(SELECT 1 FROM edges WHERE relation='WIRES' AND source_id=? AND target_id=? AND external_target_fqn IS NULL AND call_kind IS NULL AND source_location IS ?)",params![source,candidate,location],|r|r.get(0))?;
            if !exists {
                db.execute("INSERT INTO edges(source_id,target_id,relation,confidence,is_external,source_file,source_location,metadata,producer_id,world) VALUES(?,?,'WIRES',?,0,?,?,?,'derived-wiring',?)",params![source,candidate,confidence,file,location,metadata,world])?;
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_contracts::{
        fixture_graph,
        graph::{Cell, Table, cell},
    };
    #[test]
    fn worldless_java_rows_match_their_explicit_owner_at_a_shared_path() -> Result<()> {
        let source = |module: &str, scope: &str, world: &str| SourceUnit {
            path: "Shared.java".into(),
            hash: hash(b"shared"),
            size: 6,
            module: module.into(),
            scope: scope.into(),
            world: world.into(),
            language: "java".into(),
            auxiliary: false,
        };
        let first = source("first", "MAIN", "a");
        let second = source("second", "TEST", "b");
        let sources = BTreeMap::from([
            (("Shared.java", "a"), &first),
            (("Shared.java", "b"), &second),
        ]);
        let row = |module: &str, scope: &str, world: &str| {
            let mut cells = vec![Cell { value: None }; 28];
            for (index, value) in [(16, "Shared.java"), (26, module), (27, scope)] {
                cells[index].value = Some(cell::Value::Text(value.into()));
            }
            Row {
                table: Table::Edge as i32,
                cells,
                world: world.into(),
            }
        };
        for (module, scope) in [("first", "MAIN"), ("second", "TEST")] {
            validate_record(&row(module, scope, ""), &sources, &BTreeSet::new(), false)?;
        }
        for (module, scope, world, code) in [
            ("other", "MAIN", "", "GRAPH_MODULE_MISMATCH"),
            ("second", "MAIN", "", "GRAPH_SCOPE_MISMATCH"),
            ("second", "TEST", "a", "GRAPH_MODULE_MISMATCH"),
            ("second", "TEST", "missing", "GRAPH_SOURCE_NOT_CAPTURED"),
        ] {
            assert!(
                validate_record(
                    &row(module, scope, world),
                    &sources,
                    &BTreeSet::new(),
                    false
                )
                .unwrap_err()
                .to_string()
                .contains(code)
            );
        }
        Ok(())
    }
    pub(super) fn captured(store: &Store) -> (Revision, AnalysisHeader, Vec<Row>) {
        let bytes = b"class A {}".to_vec();
        let digest = hash(&bytes);
        store
            .put_blobs(&BTreeMap::from([(digest.clone(), bytes)]))
            .unwrap();
        let mut revision = crate::tests::revision();
        revision.manifest.files.push(SourceUnit {
            path: "A.java".into(),
            hash: digest,
            size: 10,
            module: "app".into(),
            world: "main".into(),
            scope: "MAIN".into(),
            language: "java".into(),
            auxiliary: false,
        });
        let mut result = crate::tests::result();
        result.manifest = revision.manifest.id();
        result.facts.push(serde_json::from_value(serde_json::json!({"id":"a","signature":"A","kind":"declaration","source":"A.java","owner":{"world":"main","producer":"mock","kind":"source","id":"A.java"},"origin":"mock"})).unwrap());
        let rows = fixture_graph::rows(&revision.manifest, &result.facts);
        (revision, result.header(), rows)
    }
    #[test]
    fn context_only_coverage_prevents_semantic_completeness() -> Result<()> {
        for source in [serde_json::json!("*"), serde_json::json!("")] {
            let directory = tempfile::tempdir()?;
            let store = Store::open(directory.path())?;
            let (rev, header, mut rows) = captured(&store);
            rows.push(fixture_graph::row(
                Table::Coverage,
                vec![
                    source,
                    serde_json::json!("app"),
                    serde_json::json!("MAIN"),
                    serde_json::json!("java"),
                    serde_json::json!("java-core"),
                    serde_json::json!("AGGREGATE"),
                    serde_json::json!("partial"),
                    serde_json::json!(1),
                    serde_json::json!(1),
                    serde_json::json!("[\"CLASSPATH_UNAVAILABLE\"]"),
                    serde_json::json!("{\"CLASSPATH_UNAVAILABLE\":1}"),
                    serde_json::json!(0),
                ],
            ));
            let candidate = store.stage_graph_records(
                rev,
                &header,
                rows.into_iter().map(Ok),
                "",
                || Ok(()),
                |_| Ok(()),
            )?;
            assert!(!candidate.summary.evidence.semantic);
            assert!(
                candidate
                    .summary
                    .evidence
                    .reasons
                    .iter()
                    .any(|r| r == "coverage:partial_contexts:1")
            );
        }
        Ok(())
    }
    #[test]
    fn malformed_or_unsealed_rows_never_change_head() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let store = Store::open(directory.path())?;
        let (rev, header, rows) = captured(&store);
        let candidate = store.stage_graph_records(
            rev.clone(),
            &header,
            rows.clone().into_iter().map(Ok),
            "",
            || Ok(()),
            |_| Ok(()),
        )?;
        let first = store.publish_staged(candidate, "first")?;
        for (index, value, message) in [
            (12, "../A.java", "GRAPH_SOURCE_UNSAFE"),
            (19, "other", "GRAPH_MODULE_MISMATCH"),
            (20, "TEST", "GRAPH_SCOPE_MISMATCH"),
        ] {
            let mut row = rows[0].clone();
            row.cells[index] = Cell {
                value: Some(cell::Value::Text(value.into())),
            };
            let error = store
                .stage_graph_records(
                    rev.clone(),
                    &header,
                    std::iter::once(Ok(row)),
                    &first.id,
                    || Ok(()),
                    |_| Ok(()),
                )
                .err()
                .context("malformed rows accepted")?;
            assert!(error.to_string().contains(message), "{error:#}");
            assert_eq!(store.head("t")?, first.id);
        }
        let error = store
            .stage_graph_records(
                rev.clone(),
                &header,
                rows.clone()
                    .into_iter()
                    .map(Ok)
                    .chain(std::iter::once(Err(anyhow::anyhow!(
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::GraphStreamIncomplete,
                            ""
                        )
                    )))),
                &first.id,
                || Ok(()),
                |_| Ok(()),
            )
            .err()
            .context("unsealed rows accepted")?;
        assert!(error.to_string().contains("GRAPH_STREAM_INCOMPLETE"));
        assert_eq!(store.head("t")?, first.id);
        let reserved = fixture_graph::row(
            Table::ProjectMeta,
            vec![serde_json::json!("schema_version"), serde_json::json!("1")],
        );
        assert!(
            store
                .stage_graph_records(
                    rev,
                    &header,
                    std::iter::once(Ok(reserved)),
                    &first.id,
                    || Ok(()),
                    |_| Ok(())
                )
                .err()
                .unwrap()
                .to_string()
                .contains("GRAPH_META_RESERVED")
        );
        assert_eq!(store.head("t")?, first.id);
        Ok(())
    }
    #[test]
    fn cancellation_removes_private_candidate_and_keeps_baseline() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let store = Store::open(directory.path())?;
        let (rev, header, rows) = captured(&store);
        let count = std::cell::Cell::new(0);
        let error = store
            .stage_graph_records(
                rev,
                &header,
                rows.into_iter().map(Ok),
                "",
                || {
                    count.set(count.get() + 1);
                    ensure!(
                        count.get() < 3,
                        codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
                    );
                    Ok(())
                },
                |_| Ok(()),
            )
            .err()
            .context("cancelled rows accepted")?;
        assert!(error.to_string().contains("CANCELLED"));
        assert_eq!(store.head("t")?, "");
        assert_eq!(
            std::fs::read_dir(directory.path().join("candidates"))?.count(),
            0
        );
        Ok(())
    }
}

#[cfg(test)]
#[path = "graph_world_tests.rs"]
mod world_tests;
