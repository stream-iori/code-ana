//! Anatomist's typed staging, identity preparation and promotion, implemented in Rust.
use anyhow::{Context, Result, ensure};
use codeana_contracts::graph::{Row, Table, cell};
use rusqlite::{
    Connection, params, params_from_iter,
    types::{Value, ValueRef},
};
use sha2::{Digest, Sha256};

mod inserts {
    include!("anatomist-inserts.rs");
}
mod hashes {
    include!("anatomist-hashes.rs");
}
pub const SCHEMA_VERSION: i64 = 10;
pub const SOURCE_SCHEMA_VERSION: i64 = 33;
pub const PAGE_SIZE: i64 = 4096;

pub fn initialize(db: &Connection) -> Result<()> {
    let version: i64 = db.query_row("PRAGMA user_version", [], |r| r.get(0))?;
    ensure!(
        version == 0 || version == SCHEMA_VERSION,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::GraphSchemaRebuildRequired,
            format!("expected={SCHEMA_VERSION} actual={version}; run index --full")
        )
    );
    if version == 0 {
        db.pragma_update(None, "page_size", PAGE_SIZE)?;
        db.execute_batch(include_str!("compact-schema.sql"))?;
        db.pragma_update(None, "user_version", SCHEMA_VERSION)?;
    }
    db.execute(
        "INSERT OR REPLACE INTO project_meta(key,value) VALUES('schema_version',?)",
        [SOURCE_SCHEMA_VERSION.to_string()],
    )?;
    Ok(())
}

/// Match StagedGraphStore's bulk index/FTS suspension. Unique constraints remain active.
pub fn suspend_bulk(db: &Connection) -> Result<Vec<String>> {
    let mut query=db.prepare("SELECT type,name,sql FROM sqlite_schema WHERE sql IS NOT NULL AND ((type='index' AND tbl_name IN ('graph_nodes','graph_edges','graph_annotations','annotation_meta_relations','graph_semantic_annotations') AND upper(ltrim(sql)) NOT LIKE 'CREATE UNIQUE INDEX%') OR (type='trigger' AND tbl_name='graph_nodes' AND name IN ('nodes_ai','nodes_ad','nodes_au'))) ORDER BY CASE type WHEN 'index' THEN 0 ELSE 1 END,name")?;
    let structures = query
        .query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
            ))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(query);
    for (kind, name, _) in &structures {
        let kind = if kind == "trigger" {
            "TRIGGER"
        } else {
            "INDEX"
        };
        db.execute_batch(&format!(
            "DROP {kind} IF EXISTS \"{}\"",
            name.replace('"', "\"\"")
        ))?;
    }
    Ok(structures.into_iter().map(|(_, _, sql)| sql).collect())
}
pub fn restore_bulk(
    db: &Connection,
    structures: &[String],
    check: &dyn Fn() -> Result<()>,
) -> Result<()> {
    check()?;
    db.execute("INSERT INTO node_names(node_names) VALUES('rebuild')", [])?;
    for sql in structures {
        check()?;
        db.execute_batch(sql)?;
    }
    Ok(())
}

pub fn initialize_stage(db: &Connection) -> Result<()> {
    db.execute_batch("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA temp_store=FILE; PRAGMA foreign_keys=OFF; PRAGMA cache_size=-64000;")?;
    db.execute_batch(include_str!("anatomist-stage.sql"))?;
    db.execute_batch("CREATE TABLE stage_file_cache(provider_id TEXT,source_file TEXT,hash TEXT,schema_version INTEGER,node_count INTEGER,edge_count INTEGER,file_size INTEGER,contract_hash TEXT);
      CREATE TABLE stage_file_dependencies(source_provider_id TEXT,source_file TEXT,depends_on_provider_id TEXT,depends_on_file TEXT);
      CREATE TABLE stage_symbol_dependencies(target_key BLOB,source_file TEXT);
      CREATE TABLE stage_diagnostics(severity TEXT,code TEXT,phase TEXT,source_file TEXT,module TEXT,scope TEXT,symbol TEXT,language TEXT,provider_id TEXT,provider_reason TEXT,reason_code TEXT,sample_details TEXT,occurrence_count INTEGER,sample TEXT,world TEXT NOT NULL DEFAULT '');
      CREATE TABLE stage_coverage(source_file TEXT,module TEXT,scope TEXT,language TEXT,provider_id TEXT,capability TEXT,status TEXT,occurrences INTEGER,groups_count INTEGER,codes TEXT,code_counts TEXT,details_truncated INTEGER,world TEXT NOT NULL DEFAULT '');
      CREATE TABLE stage_providers(provider_id TEXT,language TEXT,provider_version TEXT,operations TEXT,limitations TEXT,profile_hash TEXT);
      CREATE TABLE stage_meta(key TEXT PRIMARY KEY,value TEXT);
      CREATE TABLE stage_rust_source_states(source_file TEXT,world TEXT,state TEXT,PRIMARY KEY(source_file,world));
      CREATE TABLE stage_rust_dependencies(source_file TEXT,world TEXT,kind TEXT,target_world TEXT,target_key TEXT);")?;
    Ok(())
}

pub fn insert(db: &Connection, row: &Row) -> Result<()> {
    let table = Table::try_from(row.table).context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::GraphTableUnknown,
        "",
    ))?;
    let sql = match table {
        Table::Node => inserts::NODE_INSERT,
        Table::Edge => inserts::EDGE_INSERT,
        Table::Declaration => inserts::DECLARATION_INSERT,
        Table::Annotation => inserts::ANNOTATION_INSERT,
        Table::AnnotationMeta => inserts::ANNOTATION_META_INSERT,
        Table::SemanticAnnotation => inserts::SEMANTIC_INSERT,
        Table::UnresolvedCall => {
            "INSERT INTO stage_call_sites(source_ref,source_file,begin_line,begin_column,end_line,end_column,source_ordinal,syntax_target,receiver_static_type,call_kind,context,metadata,producer_id,source_module,source_scope,resolved_source,relation,language,provider_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'CALLS','java','java-core')"
        }
        Table::FileCache => "INSERT INTO stage_file_cache VALUES(?,?,?,?,?,?,?,?)",
        Table::FileDependency => "INSERT INTO stage_file_dependencies VALUES(?,?,?,?)",
        Table::SymbolDependency => "INSERT INTO stage_symbol_dependencies VALUES(?,?)",
        Table::Diagnostic => {
            "INSERT INTO stage_diagnostics(severity,code,phase,source_file,module,scope,symbol,language,provider_id,provider_reason,reason_code,sample_details,occurrence_count,sample) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        }
        Table::Coverage => {
            "INSERT INTO stage_coverage(source_file,module,scope,language,provider_id,capability,status,occurrences,groups_count,codes,code_counts,details_truncated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
        }
        Table::Provider => "INSERT INTO stage_providers VALUES(?,?,?,?,?,?)",
        Table::SourceState => {
            "INSERT OR REPLACE INTO stage_rust_source_states VALUES(?,?,json_remove(?,'$.dependencies'))"
        }
        Table::AnalysisDependency => "INSERT INTO stage_rust_dependencies VALUES(?,?,?,?,?)",
        Table::ProjectMeta => "INSERT OR REPLACE INTO stage_meta VALUES(?,?)",
        Table::Unspecified => anyhow::bail!(codeana_model::DomainError::new(
            codeana_model::ErrorCode::GraphTableRequired,
            ""
        )),
    };
    let integer_columns: &[usize] = match table {
        Table::Node => &[14, 15, 16, 17, 18],
        Table::Edge => &[15, 18, 19, 20, 21, 22, 28, 29],
        Table::Declaration => &[19, 20, 21, 22, 25, 26, 27, 28],
        Table::Annotation => &[12, 13, 14, 15, 18],
        Table::SemanticAnnotation => &[10],
        Table::UnresolvedCall => &[2, 3, 4, 5, 6],
        Table::FileCache => &[3, 4, 5, 6],
        Table::Diagnostic => &[12],
        Table::Coverage => &[7, 8, 11],
        _ => &[],
    };
    for (index, c) in row.cells.iter().enumerate() {
        if let Some(value) = &c.value {
            let valid = if integer_columns.contains(&index) {
                matches!(value, cell::Value::Integer(_))
            } else if table == Table::SymbolDependency && index == 0 {
                matches!(value, cell::Value::Blob(_))
            } else {
                matches!(value, cell::Value::Text(_))
            };
            ensure!(
                valid,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::GraphColumnType,
                    format!("table={table:?} column={index}")
                )
            );
        }
    }
    let mut statement = db.prepare_cached(sql)?;
    ensure!(
        statement.parameter_count() == row.cells.len(),
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::GraphColumnCount,
            format!(
                "table={table:?} expected={} actual={}",
                statement.parameter_count(),
                row.cells.len()
            )
        )
    );
    let values = row.cells.iter().map(|c| match &c.value {
        None => Value::Null,
        Some(cell::Value::Text(s)) => Value::Text(s.clone()),
        Some(cell::Value::Integer(n)) => Value::Integer(*n),
        Some(cell::Value::Blob(b)) => Value::Blob(b.clone()),
    });
    statement.execute(params_from_iter(values))?;
    if !row.world.is_empty() {
        match table {
            Table::Node => {
                db.execute(
                    "UPDATE stage_nodes SET world=? WHERE id=?",
                    params![
                        row.world,
                        match &row.cells[0].value {
                            Some(cell::Value::Text(id)) => id,
                            _ => anyhow::bail!("RUST_NODE_ID"),
                        }
                    ],
                )?;
            }
            Table::Coverage => {
                db.execute(
                    "UPDATE stage_coverage SET world=? WHERE rowid=last_insert_rowid()",
                    [&row.world],
                )?;
            }
            Table::Diagnostic => {
                db.execute(
                    "UPDATE stage_diagnostics SET world=? WHERE rowid=last_insert_rowid()",
                    [&row.world],
                )?;
            }
            _ => {}
        }
    }
    Ok(())
}

fn resolve_reference(
    db: &Connection,
    table: &str,
    output: &str,
    reference: &str,
    additional: &str,
) -> Result<()> {
    for candidate in [
        "n.source_file=t.source_file",
        "n.module=t.source_module AND n.scope=t.source_scope",
        "n.module=t.source_module AND n.scope='MAIN'",
        "n.scope='MAIN'",
        "1=1",
    ] {
        let language = if matches!(table, "stage_edges" | "stage_call_sites") {
            " AND t.language<>'python' AND n.language=t.language"
        } else {
            ""
        };
        let matches = format!("n.symbol_id=t.{reference} AND {candidate}{language}");
        db.execute_batch(&format!("UPDATE {table} AS t SET {output}=(SELECT min(n.id) FROM stage_nodes n WHERE {matches}) WHERE {output} IS NULL AND t.{reference} IS NOT NULL AND {additional} AND (SELECT count(*) FROM stage_nodes n WHERE {matches})=1"))?;
    }
    Ok(())
}

pub fn resolve(db: &Connection) -> Result<u64> {
    resolve_reference(db, "stage_edges", "resolved_source", "source_ref", "1=1")?;
    resolve_reference(
        db,
        "stage_call_sites",
        "resolved_source",
        "source_ref",
        "1=1",
    )?;
    // UNRESOLVED_CALL has no language columns; its verified source owns them.
    db.execute_batch("UPDATE stage_call_sites SET language=(SELECT n.language FROM stage_nodes n WHERE n.id=resolved_source),provider_id=(SELECT n.provider_id FROM stage_nodes n WHERE n.id=resolved_source) WHERE resolved_source IS NOT NULL;")?;
    resolve_reference(
        db,
        "stage_edges",
        "resolved_target",
        "target_ref",
        "is_external=0",
    )?;
    resolve_reference(
        db,
        "stage_edges",
        "resolved_target",
        "external_target_fqn",
        "is_external=1",
    )?;
    db.execute_batch("UPDATE stage_edges SET external_target_fqn=NULL,is_external=0,resolution=CASE WHEN relation='CALLS' AND confidence='INFERRED' THEN NULL ELSE resolution END WHERE is_external=1 AND resolved_target IS NOT NULL;
      UPDATE stage_edges SET external_target_fqn=target_ref,external_target_symbol=target_ref,external_target_language=language,external_target_provider_id=provider_id,resolution='source_fallback',target_ref=NULL,is_external=1,confidence='AMBIGUOUS' WHERE is_external=0 AND resolved_target IS NULL AND target_ref IS NOT NULL AND target_is_key=0;")?;
    for (match_key, condition) in [
        ("n.id=stage_edges.external_target_fqn", "1=1"),
        ("n.symbol_id=stage_edges.external_target_fqn", "1=1"),
        (
            "n.symbol_id=replace(stage_edges.external_target_fqn,'$','.')",
            "instr(external_target_fqn,'$')>0 AND metadata LIKE '%\"via\":\"reflection\"%'",
        ),
        (
            "n.arity_key=stage_edges.target_arity_key",
            "target_arity_key IS NOT NULL",
        ),
    ] {
        db.execute_batch(&format!("UPDATE stage_edges SET resolved_target=(SELECT min(n.id) FROM stage_nodes n WHERE {match_key} AND n.language=stage_edges.language),external_target_fqn=NULL,is_external=0,resolution=CASE WHEN relation='CALLS' AND confidence='INFERRED' THEN NULL ELSE resolution END WHERE is_external=1 AND language<>'python' AND {condition} AND (SELECT count(*) FROM stage_nodes n WHERE {match_key} AND n.language=stage_edges.language)=1"))?;
    }
    resolve_reference(db, "stage_annotations", "resolved_node", "node_ref", "1=1")?;
    resolve_reference(
        db,
        "stage_semantic_annotations",
        "resolved_node",
        "node_ref",
        "1=1",
    )?;
    db.execute_batch("UPDATE stage_edges SET source_file=(SELECT n.source_file FROM stage_nodes n WHERE n.id=stage_edges.resolved_source) WHERE source_file IS NULL AND resolved_source IS NOT NULL;
      UPDATE stage_annotations SET source_file=(SELECT n.source_file FROM stage_nodes n WHERE n.id=stage_annotations.resolved_node) WHERE source_file IS NULL AND resolved_node IS NOT NULL;")?;
    let mut dropped = 0;
    for sql in [
        "DELETE FROM stage_edges WHERE resolved_source IS NULL OR (is_external=0 AND resolved_target IS NULL)",
        "DELETE FROM stage_annotations WHERE resolved_node IS NULL",
        "DELETE FROM stage_call_sites WHERE resolved_source IS NULL",
    ] {
        dropped += db.execute(sql, [])? as u64;
    }
    Ok(dropped)
}

/// Java String.length() counts UTF-16 units, not UTF-8 bytes or Unicode scalars.
pub fn stable_hash(values: &[String], bytes: usize) -> Vec<u8> {
    let mut canonical = String::new();
    for value in values {
        canonical.push_str(&format!("{}:{value};", value.encode_utf16().count()));
    }
    Sha256::digest(canonical.as_bytes())[..bytes].to_vec()
}
fn hash_rows(
    db: &Connection,
    table: &str,
    sql: &str,
    check: &dyn Fn() -> Result<()>,
) -> Result<()> {
    let mut cursor = 0i64;
    let mut select = db.prepare(sql)?;
    let mut update = db.prepare(&format!("UPDATE {table} SET fact_hash=? WHERE seq=?"))?;
    loop {
        check()?;
        let columns = select.column_count();
        let mut rows = select.query(params![cursor, 2048])?;
        let mut batch = Vec::new();
        while let Some(row) = rows.next()? {
            let seq: i64 = row.get(0)?;
            let values = (1..columns)
                .map(|i| {
                    Ok(match row.get_ref(i)? {
                        ValueRef::Null => String::new(),
                        ValueRef::Text(s) => std::str::from_utf8(s)?.to_owned(),
                        ValueRef::Integer(n) => n.to_string(),
                        ValueRef::Real(n) => n.to_string(),
                        ValueRef::Blob(_) => anyhow::bail!(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::GraphCanonicalBlobUnexpected,
                            ""
                        )),
                    })
                })
                .collect::<Result<Vec<_>>>()?;
            batch.push((seq, stable_hash(&values, 16)));
            cursor = seq;
        }
        drop(rows);
        if batch.is_empty() {
            break;
        }
        for (seq, hash) in batch {
            update.execute(params![hash, seq])?;
        }
    }
    Ok(())
}
pub fn prepare_identities(db: &Connection, check: &dyn Fn() -> Result<()>) -> Result<()> {
    ensure!(!db.prepare("SELECT d.symbol_id FROM stage_declarations d LEFT JOIN stage_nodes n ON n.provider_id=d.provider_id AND n.symbol_id=d.symbol_id AND n.module=d.module AND n.scope=d.scope AND n.source_file=d.source_file AND n.producer_id=d.producer_id GROUP BY d.seq HAVING count(n.id)<>1 LIMIT 1")?.exists([])?, codeana_model::DomainError::new(codeana_model::ErrorCode::DeclarationNodeMissing, ""));
    hash_rows(db, "stage_nodes", hashes::NODE_HASH, check)?;
    hash_rows(db, "stage_edges", hashes::EDGE_HASH, check)?;
    hash_rows(db, "stage_annotations", hashes::ANNOTATION_HASH, check)?;
    for (table, predicate) in [
        ("stage_edges", "relation<>'CALLS' AND fact_hash IS NOT NULL"),
        ("stage_annotations", "fact_hash IS NOT NULL"),
    ] {
        check()?;
        db.execute_batch(&format!("WITH ranked AS(SELECT seq,row_number() OVER(PARTITION BY producer_id,coalesce(source_file,''),fact_hash ORDER BY seq)-1 AS ordinal FROM {table} WHERE {predicate}) UPDATE {table} SET fact_ordinal=(SELECT ordinal FROM ranked WHERE ranked.seq={table}.seq) WHERE {predicate}"))?;
    }
    db.execute_batch("CREATE INDEX stage_edges_fact ON stage_edges(fact_hash,fact_ordinal) WHERE relation<>'CALLS'; CREATE INDEX stage_annotations_fact ON stage_annotations(fact_hash,fact_ordinal);")?;
    Ok(())
}

pub fn promote(db: &Connection) -> Result<()> {
    db.execute_batch(include_str!("anatomist-promote.sql"))?;
    db.execute_batch("INSERT INTO file_cache(provider_id,source_file,hash,schema_version,node_count,edge_count,file_size,contract_hash) SELECT * FROM staged.stage_file_cache;
      INSERT OR IGNORE INTO file_dependencies SELECT * FROM staged.stage_file_dependencies;
      INSERT OR IGNORE INTO symbol_dependencies SELECT * FROM staged.stage_symbol_dependencies;
      INSERT INTO index_diagnostics(severity,code,phase,source_file,module,scope,symbol,language,provider_id,provider_reason,reason_code,sample_details,occurrence_count,sample,world) SELECT * FROM staged.stage_diagnostics;
      INSERT INTO analysis_coverage SELECT * FROM staged.stage_coverage;
      INSERT OR REPLACE INTO index_providers SELECT * FROM staged.stage_providers;
      INSERT OR REPLACE INTO project_meta SELECT * FROM staged.stage_meta;
      INSERT INTO rust_source_states SELECT * FROM staged.stage_rust_source_states;
      INSERT INTO rust_analysis_dependencies SELECT * FROM staged.stage_rust_dependencies;")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rust_metadata_is_normalized_and_old_schema_requires_full_rebuild() -> Result<()> {
        let db = Connection::open_in_memory()?;
        db.pragma_update(None, "user_version", 8)?;
        assert!(
            initialize(&db)
                .unwrap_err()
                .to_string()
                .contains("run index --full")
        );
        assert_eq!(
            db.query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))?,
            8
        );
        db.pragma_update(None, "user_version", 9)?;
        assert!(
            initialize(&db)
                .unwrap_err()
                .to_string()
                .contains("run index --full")
        );
        db.pragma_update(None, "user_version", 0)?;
        initialize(&db)?;
        db.execute_batch("PRAGMA foreign_keys=ON")?;
        for world in ["one", "two"] {
            let text=serde_json::json!({"file":"src/lib.rs","world":world,"source_hash":"source","interface_hash":"interface","names":["name"],"dependencies":[{"kind":"lookup","world":"dependency","key":"missing"}]}).to_string();
            db.execute(
                "INSERT INTO rust_source_states VALUES(?,?,?)",
                params!["src/lib.rs", world, text],
            )?;
            for kind in ["file", "symbol", "lookup", "macro", "impl_set"] {
                db.execute(
                    "INSERT INTO rust_analysis_dependencies VALUES(?,?,?,?,?)",
                    params!["src/lib.rs", world, kind, "dependency", "missing"],
                )?;
            }
        }
        assert_eq!(
            db.query_row("SELECT count(*) FROM rust_analysis_dependencies", [], |r| r
                .get::<_, i64>(0))?,
            10
        );
        assert_eq!(db.query_row("SELECT count(*) FROM rust_source_states WHERE json_type(state,'$.dependencies') IS NOT NULL",[],|r|r.get::<_,i64>(0))?,0);
        assert_eq!(
            db.query_row("SELECT count(*) FROM rust_strings", [], |r| r
                .get::<_, i64>(0))?,
            4
        );
        db.execute("DELETE FROM rust_source_states WHERE world='one'", [])?;
        assert_eq!(
            db.query_row("SELECT count(*) FROM rust_analysis_dependencies", [], |r| r
                .get::<_, i64>(0))?,
            5
        );
        assert!(db.execute("INSERT INTO rust_analysis_dependencies VALUES('missing','two','lookup','dependency','missing')",[]).is_err());
        Ok(())
    }
    #[test]
    fn upstream_layout_and_utf16_hash_are_preserved() -> Result<()> {
        let db = Connection::open_in_memory()?;
        initialize(&db)?;
        assert_eq!(
            db.query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))?,
            SCHEMA_VERSION
        );
        assert_eq!(
            db.query_row(
                "SELECT count(*) FROM sqlite_master WHERE name='facts'",
                [],
                |r| r.get::<_, i64>(0)
            )?,
            0
        );
        let stage = Connection::open_in_memory()?;
        initialize_stage(&stage)?;
        let expected = Sha256::digest("2:😀;0:;".as_bytes());
        assert_eq!(stable_hash(&["😀".into(), "".into()], 16), expected[..16]);
        assert!(
            insert(
                &stage,
                &Row {
                    world: String::new(),
                    table: Table::Node as i32,
                    cells: vec![]
                }
            )
            .is_err()
        );
        Ok(())
    }
}
