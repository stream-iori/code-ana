//! Private Anatomist graph candidates; catalog publication remains in the parent.
use super::*;
use crate::{graph_calls, graph_store};
use codeana_contracts::graph::Row;
use sha2::Digest;

impl Store {
    pub fn stage_graph_records(
        &self,
        mut revision: Revision,
        header: &AnalysisHeader,
        records: impl Iterator<Item = Result<Row>>,
        expected: &str,
        cancellation: impl Fn() -> Result<()>,
        check_directory: impl Fn(&Path) -> Result<()>,
    ) -> Result<StagedCandidate> {
        header
            .validate(&revision.manifest)
            .map_err(anyhow::Error::msg)?;
        ensure!(
            header.profile == revision.profile && header.producer == revision.producer,
            "RESULT_IDENTITY_MISMATCH"
        );
        ensure!(self.head(&revision.target)? == expected, "HEAD_CONFLICT");
        let baseline = if header.mode == "delta" {
            ensure!(header.baseline == expected, "BASELINE_MISMATCH");
            let r = self.read(expected)?;
            ensure!(r.revision.profile == revision.profile, "PROFILE_MISMATCH");
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
                .context("CANDIDATE_NAME")?
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
                    _ => anyhow::bail!("SQLITE_BACKUP_STEP"),
                }
            }
        }
        graph_store::initialize(&db)?;
        db.execute_batch("PRAGMA journal_mode=OFF;PRAGMA synchronous=OFF;PRAGMA locking_mode=EXCLUSIVE;PRAGMA cache_size=-64000;PRAGMA temp_store=FILE;PRAGMA foreign_keys=ON;")?;
        let stage_path = directory.path().join("stage.db");
        let stage = Connection::open(&stage_path)?;
        graph_store::initialize_stage(&stage)?;
        // The retained baseline catalogue is available to reference resolution without
        // importing or materializing any serialized Fact IR.
        if baseline.is_some() {
            seed_baseline(&stage, &db, header, &check)?;
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
            .map(|s| (s.path.as_str(), s))
            .collect();
        let replaced: BTreeSet<_> = header
            .replacements
            .iter()
            .filter(|o| o.kind == "source")
            .map(|o| o.id.as_str())
            .collect();
        let mut positions = 0;
        for row in records {
            if positions % 256 == 0 {
                check()?;
            }
            let row = row?;
            validate_record(&row, &sources, &replaced, header.mode == "delta")?;
            graph_store::insert(&stage, &row)?;
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
        if let Some(files) = &affected {
            replace_affected(&db, files)?;
        }
        graph_store::promote(&db)?;
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
        db.execute_batch("COMMIT;DETACH DATABASE staged;")
            .context("GRAPH_PROMOTION_DETACH")?;
        std::fs::remove_file(stage_path)?;
        phase_ms.insert(
            "promotion_calls_wiring".into(),
            started.elapsed().as_millis() as u64,
        );
        started = std::time::Instant::now();
        check()?;
        let integrity: String = db.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
        ensure!(integrity == "ok", "CORRUPT_CANDIDATE");
        ensure!(
            !db.prepare("PRAGMA foreign_key_check")?.exists([])?,
            "GRAPH_FOREIGN_KEY_CHECK"
        );
        db.execute(
            "INSERT INTO node_names(node_names) VALUES('integrity-check')",
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
            "SELECT count(DISTINCT source_file) FROM analysis_coverage WHERE status<>'complete'",
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
        summary.diagnostic_rows =
            db.query_row("SELECT count(*) FROM index_diagnostics", [], |r| {
                r.get::<_, i64>(0)
            })? as u64;
        summary.diagnostic_total = db.query_row(
            "SELECT coalesce(sum(occurrence_count),0) FROM index_diagnostics",
            [],
            |r| r.get::<_, i64>(0),
        )? as u64;
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
        .context("GRAPH_FINALIZE_DURABILITY")?;
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

fn validate_record(
    row: &Row,
    sources: &BTreeMap<&str, &SourceUnit>,
    replaced: &BTreeSet<&str>,
    delta: bool,
) -> Result<()> {
    use codeana_contracts::graph::{Table, cell};
    let table = Table::try_from(row.table).context("GRAPH_TABLE_UNKNOWN")?;
    let text = |index: usize| -> Result<Option<&str>> {
        match row.cells.get(index).and_then(|c| c.value.as_ref()) {
            None => Ok(None),
            Some(cell::Value::Text(v)) => Ok(Some(v)),
            _ => anyhow::bail!("GRAPH_TEXT_COLUMN: {table:?}:{index}"),
        }
    };
    let columns = match table {
        Table::Node => Some((12, Some((19, 20)))),
        Table::Declaration => Some((17, Some((23, 24)))),
        Table::Edge => Some((16, Some((26, 27)))),
        Table::Annotation => Some((10, Some((16, 17)))),
        Table::UnresolvedCall => Some((1, Some((13, 14)))),
        Table::Coverage => Some((0, Some((1, 2)))),
        Table::Diagnostic => Some((3, Some((4, 5)))),
        Table::FileCache => Some((1, None)),
        Table::AnnotationMeta => Some((7, None)),
        Table::SemanticAnnotation => Some((7, Some((8, 9)))),
        _ => None,
    };
    if let Some((source, identity)) = columns
        && let Some(path) = text(source)?.filter(|p| !p.is_empty() && *p != "*")
    {
        ensure!(safe_path(path), "GRAPH_SOURCE_UNSAFE");
        let unit = sources.get(path).context("GRAPH_SOURCE_NOT_CAPTURED")?;
        if let Some((module, scope)) = identity {
            if let Some(module) = text(module)? {
                ensure!(
                    module == unit.module
                        || unit.module.is_empty() && unit.language.is_empty()
                        || module == "*" && matches!(table, Table::Diagnostic | Table::Coverage),
                    "GRAPH_MODULE_MISMATCH: table={table:?} path={path} expected={} actual={module}",
                    unit.module
                );
            }
            // Resource records inherit the owning compilation scope.
            if let Some(scope) = text(scope)? {
                ensure!(
                    scope == unit.scope
                        || scope == "*" && matches!(table, Table::Diagnostic | Table::Coverage)
                        || unit.scope == "RESOURCE" && matches!(scope, "MAIN" | "TEST"),
                    "GRAPH_SCOPE_MISMATCH"
                );
            }
        }
        if delta {
            ensure!(replaced.contains(path), "GRAPH_SOURCE_NOT_REPLACED");
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
                    | "codeana.overview_counts"
            ),
            "GRAPH_META_RESERVED"
        );
    }
    Ok(())
}

fn replace_affected(db: &Connection, files: &BTreeSet<String>) -> Result<()> {
    db.execute_batch("CREATE TEMP TABLE graph_replaced(source_file TEXT PRIMARY KEY);")?;
    for file in files {
        db.execute("INSERT INTO graph_replaced VALUES(?)", [file])?;
    }
    // Retain matching hash/ordinal pairs exactly as Anatomist incremental promotion does.
    db.execute_batch("DELETE FROM edges WHERE (edges.source_file IN(SELECT source_file FROM graph_replaced) OR edges.producer_id='derived-wiring') AND NOT EXISTS(SELECT 1 FROM staged.stage_edges s WHERE s.relation<>'CALLS' AND s.fact_hash=edges.fact_hash AND s.fact_ordinal=edges.fact_ordinal);
      DELETE FROM annotations WHERE annotations.source_file IN(SELECT source_file FROM graph_replaced) AND NOT EXISTS(SELECT 1 FROM staged.stage_annotations s WHERE s.fact_hash=annotations.fact_hash AND s.fact_ordinal=annotations.fact_ordinal);
      DELETE FROM semantic_annotations WHERE source_file IN(SELECT source_file FROM graph_replaced);
      DELETE FROM annotation_meta_owned WHERE source_file IN(SELECT source_file FROM graph_replaced);
      DELETE FROM nodes WHERE source_file IN(SELECT source_file FROM graph_replaced) AND id NOT IN(SELECT id FROM staged.stage_nodes);
      DELETE FROM file_cache WHERE source_file IN(SELECT source_file FROM graph_replaced);
      DELETE FROM analysis_coverage WHERE source_file IN(SELECT source_file FROM graph_replaced);
      DELETE FROM index_diagnostics;
      DELETE FROM index_providers;
      DELETE FROM project_meta WHERE key LIKE 'codeana.context.%' AND EXISTS(SELECT 1 FROM staged.stage_meta WHERE key LIKE 'codeana.context.%') AND key NOT IN(SELECT key FROM staged.stage_meta);")?;
    Ok(())
}

fn seed_baseline(
    stage: &Connection,
    db: &Connection,
    header: &AnalysisHeader,
    check: &dyn Fn() -> Result<()>,
) -> Result<()> {
    stage.execute(
        "ATTACH DATABASE ? AS baseline",
        [db.path().context("BASELINE_DATABASE_PATH")?],
    )?;
    stage.execute_batch("CREATE TEMP TABLE replaced(source_file TEXT PRIMARY KEY);")?;
    for owner in &header.replacements {
        ensure!(owner.kind == "source", "GRAPH_REPLACEMENT_SOURCE_REQUIRED");
        stage.execute("INSERT OR IGNORE INTO replaced VALUES(?)", [&owner.id])?;
    }
    check()?;
    stage.execute_batch("INSERT INTO stage_nodes(id,symbol_id,domain,language,provider_id,entity_kind,language_kind,label,kind,qualified_name,package,namespace,source_file,source_location,begin_line,begin_column,end_line,end_column,source_ordinal,module,scope,metadata,producer_id)
      SELECT id,symbol_id,domain,language,provider_id,entity_kind,language_kind,label,kind,qualified_name,package,namespace,source_file,source_location,begin_line,begin_column,end_line,end_column,source_ordinal,module,scope,metadata,producer_id FROM baseline.nodes WHERE source_file NOT IN(SELECT source_file FROM replaced);
      INSERT INTO stage_declarations(symbol_id,domain,language,provider_id,entity_kind,language_kind,qualified_name,label,kind,declaration_kind,type_kind,visibility,modifiers,declared_modifiers,implicit_modifiers,declaring_type,namespace,source_file,source_location,begin_line,begin_column,end_line,end_column,module,scope,nesting_depth,direct_member,synthetic,binding_resolved,producer_id)
      SELECT symbol_id,domain,language,provider_id,entity_kind,language_kind,qualified_name,label,kind,declaration_kind,type_kind,visibility,modifiers,declared_modifiers,implicit_modifiers,declaring_type,declaration_namespace,source_file,declaration_source_location,declaration_begin_line,declaration_begin_column,declaration_end_line,declaration_end_column,module,scope,nesting_depth,direct_member,synthetic,binding_resolved,producer_id FROM baseline.nodes WHERE declaration_kind IS NOT NULL AND source_file NOT IN(SELECT source_file FROM replaced);
      INSERT INTO stage_edges(source_ref,target_ref,external_target_fqn,external_target_symbol,external_target_language,external_target_provider_id,relation,semantic,mechanism,language,provider_id,call_kind,confidence,resolution,context,is_external,source_file,source_location,begin_line,begin_column,end_line,end_column,source_ordinal,syntax_target,receiver_static_type,metadata,source_module,source_scope,source_is_key,target_is_key,resolved_source,resolved_target,producer_id)
      SELECT e.source_id,e.target_id,e.external_target_fqn,e.external_target_symbol,e.external_target_language,e.external_target_provider_id,e.relation,e.semantic,e.mechanism,e.language,e.provider_id,e.call_kind,e.confidence,e.resolution,e.context,e.is_external,e.source_file,e.source_location,e.begin_line,e.begin_column,e.end_line,e.end_column,e.source_ordinal,e.syntax_target,e.receiver_static_type,e.metadata,n.module,n.scope,1,1,e.source_id,e.target_id,e.producer_id FROM baseline.edges e JOIN baseline.nodes n ON n.id=e.source_id WHERE e.producer_id<>'derived-wiring' AND e.source_file NOT IN(SELECT source_file FROM replaced);
      INSERT INTO stage_annotations(node_ref,annotation_fqn,raw_name,attributes,target_kind,target_path,language,provider_id,mechanism,resolution_status,source_file,source_location,begin_line,begin_column,end_line,end_column,source_module,source_scope,node_is_key,resolved_node,producer_id)
      SELECT a.node_id,a.annotation_fqn,a.raw_name,a.attributes,a.target_kind,a.target_path,a.language,a.provider_id,a.mechanism,a.resolution_status,a.source_file,a.source_location,a.begin_line,a.begin_column,a.end_line,a.end_column,n.module,n.scope,1,a.node_id,a.producer_id FROM baseline.annotations a JOIN baseline.nodes n ON n.id=a.node_id WHERE a.source_file NOT IN(SELECT source_file FROM replaced);
      INSERT INTO stage_annotation_meta(annotation_fqn,meta_annotation_fqn,raw_name,language,provider_id,mechanism,resolution_status,source_file,source_location,producer_id) SELECT annotation_fqn,meta_annotation_fqn,raw_name,language,provider_id,mechanism,resolution_status,source_file,source_location,producer_id FROM baseline.annotation_meta_owned WHERE source_file NOT IN(SELECT source_file FROM replaced);
      INSERT INTO stage_semantic_annotations(node_ref,category,business_label,business_description,domain_context,source,confidence,source_file,source_module,source_scope,node_is_key,resolved_node,producer_id)
      SELECT a.node_id,a.category,a.business_label,a.business_description,a.domain_context,a.source,a.confidence,a.source_file,n.module,n.scope,1,a.node_id,a.producer_id FROM baseline.semantic_annotations a JOIN baseline.nodes n ON n.id=a.node_id WHERE a.source_file IS NULL OR a.source_file NOT IN(SELECT source_file FROM replaced);
      INSERT INTO stage_file_cache SELECT provider_id,source_file,hash,schema_version,node_count,edge_count,file_size,contract_hash FROM baseline.file_cache WHERE source_file NOT IN(SELECT source_file FROM replaced);
      INSERT INTO stage_coverage SELECT * FROM baseline.analysis_coverage WHERE source_file NOT IN(SELECT source_file FROM replaced);
      INSERT INTO stage_diagnostics SELECT severity,code,phase,source_file,module,scope,symbol,language,provider_id,provider_reason,reason_code,sample_details,occurrence_count,sample FROM baseline.index_diagnostics WHERE source_file NOT IN(SELECT source_file FROM replaced);
      INSERT INTO stage_providers SELECT * FROM baseline.index_providers;")?;
    // Persisted call sites are the canonical source; seed their exact range and
    // targets, including unresolved sites, without manufacturing method CALLS.
    stage.execute_batch("INSERT INTO stage_call_sites(source_ref,target_ref,external_target_fqn,external_target_symbol,external_target_language,external_target_provider_id,relation,language,provider_id,call_kind,confidence,is_external,source_file,begin_line,begin_column,end_line,end_column,source_ordinal,syntax_target,receiver_static_type,context,metadata,source_module,source_scope,source_is_key,target_is_key,resolved_source,resolved_target,producer_id)
      SELECT o.caller_id,t.target_id,t.external_target_fqn,t.external_target_symbol,t.external_target_language,t.external_target_provider_id,'CALLS',c.language,c.provider_id,c.dispatch_kind,t.confidence,t.target_id IS NULL,o.source_file,c.begin_line,c.begin_column,c.end_line,c.end_column,c.ordinal,c.syntax_target,c.receiver_static_type,c.context,c.metadata,n.module,n.scope,1,1,o.caller_id,t.target_id,c.producer_id FROM baseline.call_sites c JOIN baseline.call_site_owners o USING(owner_pk) JOIN baseline.nodes n ON n.id=o.caller_id LEFT JOIN baseline.call_site_targets t ON t.call_site_pk=c.site_pk WHERE o.source_file NOT IN(SELECT source_file FROM replaced);")?;
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
        "INSERT OR IGNORE INTO symbol_dependencies(target_key,source_file) VALUES(?,?)",
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
        insert.execute(params![&key[..8], file])?;
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
    let mut statement=db.prepare("SELECT source_id,target_id,source_file,source_location FROM edges WHERE relation='INJECTS' AND is_external=0 AND source_id IS NOT NULL AND target_id IS NOT NULL ORDER BY id")?;
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
        let metadata = format!(
            "{{\"via\":\"injection\",\"source\":{},\"candidates\":{}}}",
            serde_json::to_string(&target)?,
            serde_json::to_string(candidates)?
        );
        for candidate in candidates {
            let exists:bool=db.query_row("SELECT EXISTS(SELECT 1 FROM edges WHERE relation='WIRES' AND source_id=? AND target_id=? AND external_target_fqn IS NULL AND call_kind IS NULL AND source_location IS ?)",params![source,candidate,location],|r|r.get(0))?;
            if !exists {
                db.execute("INSERT INTO edges(source_id,target_id,relation,confidence,is_external,source_file,source_location,metadata,producer_id) VALUES(?,?,'WIRES',?,0,?,?,?,'derived-wiring')",params![source,candidate,confidence,file,location,metadata])?;
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
    fn captured(store: &Store) -> (Revision, AnalysisHeader, Vec<Row>) {
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
                        "GRAPH_STREAM_INCOMPLETE"
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
                    ensure!(count.get() < 3, "CANCELLED");
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
