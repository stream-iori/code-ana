//! Opaque metadata sharing and transaction-owned dictionary reclamation.
use anyhow::{Result, ensure};
use rusqlite::{Connection, OptionalExtension, params};
use sha2::{Digest, Sha256};

fn intern_metadata(db: &Connection, digest: &[u8], value: &str) -> Result<i64> {
    // A digest is a lookup hint, never an identity. Compare the original UTF-8
    // text as well, so collisions cannot merge different observations.
    if let Some(id) = db.query_row(
        "SELECT value_pk FROM metadata_values WHERE digest=? AND value=? COLLATE BINARY LIMIT 1",
        params![digest, value], |row| row.get(0),
    ).optional()? {
        return Ok(id);
    }
    db.execute(
        "INSERT INTO metadata_values(digest,value) VALUES(?,?)",
        params![digest, value],
    )?;
    Ok(db.last_insert_rowid())
}

pub fn seal(db: &Connection, check: &dyn Fn() -> Result<()>) -> Result<()> {
    check()?;
    // Restore shared values before counting both inherited and newly inserted
    // observations. This also unshares values whose last duplicate was deleted.
    for table in ["graph_nodes", "graph_edges", "graph_call_sites"] {
        db.execute_batch(&format!("UPDATE {table} SET metadata=(SELECT value FROM metadata_values m WHERE m.value_pk={table}.metadata_pk),metadata_pk=NULL WHERE metadata_pk IS NOT NULL;"))?;
    }
    db.execute_batch("CREATE TEMP TABLE compact_metadata_wanted(value TEXT PRIMARY KEY,value_pk INTEGER);
      INSERT INTO compact_metadata_wanted(value) SELECT value FROM (
        SELECT metadata value FROM graph_nodes UNION ALL SELECT metadata FROM graph_edges
        UNION ALL SELECT metadata FROM graph_call_sites)
      WHERE value IS NOT NULL AND length(cast(value AS BLOB))>=256 GROUP BY value HAVING count(*)>=2;")?;
    let mut cursor = 0i64;
    loop {
        check()?;
        // One value at a time; no whole-project metadata materialization.
        let row: Option<(i64,String)> = db.query_row(
            "SELECT rowid,value FROM compact_metadata_wanted WHERE rowid>? ORDER BY rowid LIMIT 1",
            [cursor], |r| Ok((r.get(0)?,r.get(1)?)),
        ).optional()?;
        let Some((id, value)) = row else { break };
        let pk = intern_metadata(db, &Sha256::digest(value.as_bytes()), &value)?;
        db.execute(
            "UPDATE compact_metadata_wanted SET value_pk=? WHERE rowid=?",
            params![pk, id],
        )?;
        cursor = id;
    }
    for table in ["graph_nodes", "graph_edges", "graph_call_sites"] {
        check()?;
        db.execute_batch(&format!("UPDATE {table} SET metadata_pk=(SELECT value_pk FROM compact_metadata_wanted w WHERE w.value={table}.metadata),metadata=NULL WHERE metadata IN(SELECT value FROM compact_metadata_wanted);"))?;
    }
    db.execute_batch("DROP TABLE compact_metadata_wanted;
      DELETE FROM annotation_meta_relations WHERE id NOT IN(SELECT relation_id FROM annotation_meta_owners);
      DELETE FROM annotation_meta_sources WHERE id NOT IN(SELECT source_id FROM annotation_meta_owners);
      UPDATE annotation_meta_relations SET resolution_status=(SELECT min(resolution_status) FROM annotation_meta_owners WHERE relation_id=annotation_meta_relations.id);
      DELETE FROM metadata_values WHERE value_pk NOT IN(
        SELECT metadata_pk FROM graph_nodes WHERE metadata_pk IS NOT NULL UNION
        SELECT metadata_pk FROM graph_edges WHERE metadata_pk IS NOT NULL UNION
        SELECT metadata_pk FROM graph_call_sites WHERE metadata_pk IS NOT NULL);
      DELETE FROM external_targets WHERE external_pk NOT IN(
        SELECT external_pk FROM graph_edges WHERE external_pk IS NOT NULL UNION
        SELECT external_pk FROM graph_call_site_targets WHERE external_pk IS NOT NULL);")?;
    db.execute_batch("DELETE FROM rust_strings WHERE string_pk NOT IN(SELECT world FROM graph_rust_source_states UNION SELECT world FROM graph_rust_analysis_dependencies UNION SELECT target_world FROM graph_rust_analysis_dependencies UNION SELECT target_key FROM graph_rust_analysis_dependencies)")?;
    let mut references = Vec::new();
    for table in [
        "graph_nodes",
        "graph_edges",
        "graph_call_site_owners",
        "graph_annotations",
        "graph_semantic_annotations",
        "graph_file_cache",
        "graph_file_dependencies",
        "graph_symbol_dependencies",
        "graph_index_diagnostics",
        "graph_analysis_coverage",
        "annotation_meta_sources",
        "graph_rust_source_states",
    ] {
        references.push(format!(
            "SELECT source_file FROM {table} WHERE source_file IS NOT NULL"
        ));
    }
    references.push("SELECT depends_on_file FROM graph_file_dependencies".into());
    db.execute_batch(&format!(
        "DELETE FROM source_files WHERE file_pk NOT IN({});",
        references.join(" UNION ")
    ))?;
    ensure!(!db.prepare("SELECT 1 FROM edges WHERE NOT ((is_external=0 AND target_id IS NOT NULL AND external_target_fqn IS NULL) OR (is_external=1 AND target_id IS NULL AND external_target_fqn IS NOT NULL)) LIMIT 1")?.exists([])?, codeana_model::DomainError::new(codeana_model::ErrorCode::GraphTargetShape, ""));
    check()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn database() -> Result<Connection> {
        let db = Connection::open_in_memory()?;
        crate::graph_store::initialize(&db)?;
        db.execute_batch("PRAGMA foreign_keys=ON")?;
        Ok(db)
    }
    fn node(db: &Connection, id: &str, file: &str, metadata: Option<&str>, hash: u8) -> Result<()> {
        db.execute("INSERT INTO nodes(id,symbol_id,label,kind,qualified_name,source_file,module,scope,metadata,fact_hash) VALUES(?,?,?,'CLASS',?,?,'app','MAIN',?,?)",
            params![id,id,id,id,file,metadata,vec![hash;16]])?;
        Ok(())
    }
    #[test]
    fn integer_references_preserve_provenance_and_upsert_pk() -> Result<()> {
        let db = database()?;
        node(&db, "A", "A.java", None, 1)?;
        node(&db, "B", "B.java", None, 1)?;
        db.execute("INSERT INTO edges(source_id,target_id,relation,source_file) VALUES('A','B','CONFIGURES','beans.xml')",[])?;
        let pk: i64 = db.query_row("SELECT node_pk FROM graph_nodes WHERE id='A'", [], |r| {
            r.get(0)
        })?;
        node(&db, "A", "A.java", None, 2)?;
        assert_eq!(
            db.query_row("SELECT node_pk FROM graph_nodes WHERE id='A'", [], |r| r
                .get::<_, i64>(0))?,
            pk
        );
        assert_eq!(
            db.query_row(
                "SELECT source_id,target_id,source_file FROM edges",
                [],
                |r| Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?
                ))
            )?,
            ("A".into(), "B".into(), "beans.xml".into())
        );
        assert_eq!(
            db.query_row("SELECT typeof(source_id) FROM graph_edges", [], |r| r
                .get::<_, String>(0))?,
            "integer"
        );
        assert!(
            db.execute(
                "INSERT INTO edges(source_id,target_id,relation) VALUES('missing','B','CONTAINS')",
                []
            )
            .is_err()
        );
        assert!(!db.prepare("PRAGMA foreign_key_check")?.exists([])?);
        Ok(())
    }
    #[test]
    fn opaque_metadata_shares_duplicates_and_releases_last_reference() -> Result<()> {
        let db = database()?;
        let value = format!("{{\"text\":\"{}\",\"v\":1}}", "汉字🧪".repeat(50));
        node(&db, "A", "A.java", Some(&value), 1)?;
        node(&db, "B", "B.java", Some(&value), 1)?;
        node(&db, "C", "C.java", None, 1)?;
        node(&db, "D", "D.java", Some(""), 1)?;
        seal(&db, &|| Ok(()))?;
        assert_eq!(
            db.query_row("SELECT count(*) FROM metadata_values", [], |r| r
                .get::<_, i64>(0))?,
            1
        );
        assert_eq!(
            db.query_row(
                "SELECT count(*) FROM graph_nodes WHERE metadata_pk IS NOT NULL",
                [],
                |r| r.get::<_, i64>(0)
            )?,
            2
        );
        assert_eq!(
            db.query_row("SELECT metadata FROM nodes WHERE id='A'", [], |r| r
                .get::<_, String>(0))?,
            value
        );
        assert_eq!(
            db.query_row("SELECT metadata FROM nodes WHERE id='C'", [], |r| r
                .get::<_, Option<String>>(0))?,
            None
        );
        assert_eq!(
            db.query_row("SELECT metadata FROM nodes WHERE id='D'", [], |r| r
                .get::<_, String>(0))?,
            ""
        );
        db.execute("DELETE FROM nodes WHERE id='B'", [])?;
        seal(&db, &|| Ok(()))?;
        assert_eq!(
            db.query_row("SELECT count(*) FROM metadata_values", [], |r| r
                .get::<_, i64>(0))?,
            0
        );
        assert_eq!(
            db.query_row("SELECT metadata FROM nodes WHERE id='A'", [], |r| r
                .get::<_, String>(0))?,
            value
        );
        Ok(())
    }
    #[test]
    fn shared_annotation_owners_keep_first_location_and_refresh_status() -> Result<()> {
        let db = database()?;
        let insert = "INSERT INTO annotation_meta_owned(annotation_fqn,meta_annotation_fqn,raw_name,language,provider_id,mechanism,resolution_status,source_file,source_location,producer_id) VALUES('p.A','p.Meta','p.Meta','java','java-core','java.annotation.meta',?,?,?,'java-core')";
        db.execute(insert, params!["unresolved", "A.java", "L1:1"])?;
        db.execute(insert, params!["exact", "A.java", "L9:1"])?;
        db.execute(insert, params!["exact", "B.java", "L2:1"])?;
        seal(&db, &|| Ok(()))?;
        assert_eq!(
            db.query_row("SELECT count(*) FROM annotation_meta_relations", [], |r| {
                r.get::<_, i64>(0)
            })?,
            1
        );
        assert_eq!(
            db.query_row(
                "SELECT source_location FROM annotation_meta_owned WHERE source_file='A.java'",
                [],
                |r| r.get::<_, String>(0)
            )?,
            "L1:1"
        );
        assert_eq!(
            db.query_row(
                "SELECT resolution_status FROM annotation_meta_relations",
                [],
                |r| r.get::<_, String>(0)
            )?,
            "exact"
        );
        db.execute(
            "DELETE FROM annotation_meta_owned WHERE source_file='B.java'",
            [],
        )?;
        seal(&db, &|| Ok(()))?;
        assert_eq!(
            db.query_row(
                "SELECT resolution_status FROM annotation_meta_relations",
                [],
                |r| r.get::<_, String>(0)
            )?,
            "unresolved"
        );
        db.execute(
            "DELETE FROM annotation_meta_owned WHERE source_file='A.java'",
            [],
        )?;
        seal(&db, &|| Ok(()))?;
        assert_eq!(
            db.query_row("SELECT count(*) FROM annotation_meta_relations", [], |r| {
                r.get::<_, i64>(0)
            })?,
            0
        );
        assert_eq!(
            db.query_row("SELECT count(*) FROM source_files", [], |r| r
                .get::<_, i64>(0))?,
            0
        );
        Ok(())
    }
    #[test]
    fn external_identity_keeps_null_distinct_from_empty() -> Result<()> {
        let db = database()?;
        node(&db, "A", "A.java", None, 1)?;
        let insert = "INSERT INTO edges(source_id,external_target_fqn,external_target_symbol,external_target_language,relation,is_external) VALUES('A','p.External','p.External',?,'REFERENCES',1)";
        db.execute(insert, params![None::<String>])?;
        db.execute(insert, params![Some("")])?;
        seal(&db, &|| Ok(()))?;
        assert_eq!(
            db.query_row("SELECT count(*) FROM external_targets", [], |r| r
                .get::<_, i64>(0))?,
            2
        );
        assert_eq!(
            db.query_row(
                "SELECT count(*) FROM edges WHERE external_target_language IS NULL",
                [],
                |r| r.get::<_, i64>(0)
            )?,
            1
        );
        assert_eq!(
            db.query_row(
                "SELECT count(*) FROM edges WHERE external_target_language=''",
                [],
                |r| r.get::<_, i64>(0)
            )?,
            1
        );
        Ok(())
    }
    #[test]
    fn digest_collision_retains_distinct_original_values() -> Result<()> {
        let db = Connection::open_in_memory()?;
        crate::graph_store::initialize(&db)?;
        let digest = [0u8; 32];
        let a = intern_metadata(&db, &digest, "{\"v\":1}")?;
        let b = intern_metadata(&db, &digest, "{\"v\":2}")?;
        assert_ne!(a, b);
        assert_eq!(intern_metadata(&db, &digest, "{\"v\":1}")?, a);
        Ok(())
    }
}
