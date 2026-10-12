//! SQLite fact storage schema. All upgrades run in a private candidate.
use anyhow::Result;
use rusqlite::Connection;

pub(crate) fn initialize(db: &Connection) -> Result<()> {
    let projected: bool = db.query_row("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE name='declaration_projection' AND type='table')", [], |r| r.get(0))?;
    db.execute_batch(include_str!("schema.sql"))?;
    if !projected {
        db.execute(
            "INSERT INTO declaration_projection SELECT * FROM declaration_rows",
            [],
        )?;
    }
    Ok(())
}

/// Full candidates start empty. Defer disposable selectors and ingestion indexes.
pub(crate) fn begin_full_load(db: &Connection) -> Result<()> {
    db.execute_batch("DROP TRIGGER declaration_insert; DROP TRIGGER declaration_update; DROP TRIGGER declaration_delete;
        DROP INDEX fact_owner; DROP INDEX fact_source;
        DROP INDEX declaration_signature; DROP INDEX declaration_scope;")?;
    Ok(())
}

/// Restore all selectors and maintenance triggers before derivation can read/write facts.
pub(crate) fn finish_full_load(db: &Connection) -> Result<()> {
    db.execute(
        "INSERT INTO declaration_projection SELECT * FROM declaration_rows",
        [],
    )?;
    db.execute_batch(include_str!("schema.sql"))?;
    Ok(())
}

pub(crate) fn prepare_queries(db: &Connection) -> Result<()> {
    // Old revisions have single-column indexes. Upgrade only the copied
    // candidate; never alter a published revision or rebuild indexes on every delta.
    for (name, width) in [("fact_kind", 3), ("fact_parent", 4), ("fact_target", 4)] {
        let mut statement = db.prepare(&format!("PRAGMA index_info({name})"))?;
        let columns = statement
            .query_map([], |_| Ok(()))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        drop(statement);
        if !columns.is_empty() && columns.len() != width {
            db.execute_batch(&format!("DROP INDEX {name}"))?;
        }
    }
    // Symbol search uses FTS candidates plus exact literal checks. No reader
    // needs the obsolete full-table name index. Unresolved consumers have their
    // own partial name/ID index; upgrade only the private candidate.
    db.execute_batch("DROP INDEX IF EXISTS fact_name")?;
    db.execute_batch(include_str!("query-indexes.sql"))?;
    super::symbol_search::prepare(db)?;
    Ok(())
}

pub(crate) fn refresh_file_dependencies(db: &Connection) -> Result<()> {
    db.execute_batch(include_str!("file-dependencies.sql"))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bulk_projection_is_ready_before_derivation_and_restores_delta_triggers() {
        let db = Connection::open_in_memory().unwrap();
        initialize(&db).unwrap();
        begin_full_load(&db).unwrap();
        db.execute(
            "INSERT INTO facts VALUES('a','declaration','owner','','','','Api','A.java','main',?)",
            [r#"{"signature":"p.Api","attributes":{"element_kind":"CLASS"}}"#],
        )
        .unwrap();
        assert_eq!(
            db.query_row("SELECT count(*) FROM declaration_projection", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
        finish_full_load(&db).unwrap();
        prepare_queries(&db).unwrap();
        assert_eq!(
            db.query_row(
                "SELECT signature FROM declaration_projection WHERE id='a'",
                [],
                |r| r.get::<_, String>(0)
            )
            .unwrap(),
            "p.Api"
        );
        // A derived declaration must enter selectors immediately, then survive delta updates.
        db.execute("INSERT INTO facts VALUES('derived','declaration','derived','','','','Bean','','main','{\"signature\":\"p.Bean\"}')", []).unwrap();
        assert_eq!(
            db.query_row("SELECT count(*) FROM declaration_projection", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            2
        );
        db.execute("UPDATE facts SET kind='reference' WHERE id='derived'", [])
            .unwrap();
        db.execute("DELETE FROM facts WHERE owner='owner'", [])
            .unwrap();
        assert_eq!(
            db.query_row("SELECT count(*) FROM declaration_projection", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
        assert_eq!(
            db.query_row("PRAGMA integrity_check", [], |r| r.get::<_, String>(0))
                .unwrap(),
            "ok"
        );
    }
    #[test]
    fn file_dependencies_follow_endpoints_annotations_and_owner_deletion() {
        let db = Connection::open_in_memory().unwrap();
        initialize(&db).unwrap();
        let add = |id: &str,
                   kind: &str,
                   world: &str,
                   source: &str,
                   target: &str,
                   relation: &str,
                   attrs: serde_json::Value| {
            let body = serde_json::json!({"signature":id,"attributes":attrs});
            db.execute(
                "INSERT INTO facts VALUES(?,?,?,'',?,?,?,?,?,?)",
                rusqlite::params![
                    id,
                    kind,
                    source,
                    target,
                    relation,
                    id,
                    source,
                    world,
                    body.to_string()
                ],
            )
            .unwrap();
        };
        let provider = serde_json::json!({"provider_id":"java-core"});
        add(
            "A",
            "declaration",
            "main",
            "A.java",
            "",
            "",
            provider.clone(),
        );
        add(
            "B",
            "declaration",
            "main",
            "B.java",
            "",
            "",
            provider.clone(),
        );
        add(
            "Note",
            "declaration",
            "main",
            "Note.java",
            "",
            "",
            provider.clone(),
        );
        add(
            "OtherNote",
            "declaration",
            "other",
            "Other.java",
            "",
            "",
            serde_json::json!({"provider_id":"other"}),
        );
        add(
            "ab",
            "reference",
            "main",
            "A.java",
            "B",
            "type_reference",
            provider.clone(),
        );
        add(
            "ab-again",
            "reference",
            "main",
            "A.java",
            "B",
            "type_reference",
            provider.clone(),
        );
        add(
            "ba",
            "relation",
            "main",
            "B.java",
            "A",
            "extends",
            provider.clone(),
        );
        add(
            "self",
            "reference",
            "main",
            "A.java",
            "A",
            "type_reference",
            provider.clone(),
        );
        add(
            "test-b",
            "call_target",
            "test",
            "Test.java",
            "B",
            "invokes",
            provider.clone(),
        );
        add(
            "annotation",
            "facet",
            "main",
            "A.java",
            "",
            "java.annotation",
            serde_json::json!({"provider_id":"java-core","annotation":"Note"}),
        );
        add(
            "meta",
            "facet",
            "main",
            "B.java",
            "",
            "java.annotation.meta",
            serde_json::json!({"provider_id":"java-core","meta_annotation":"Note"}),
        );
        prepare_queries(&db).unwrap();
        refresh_file_dependencies(&db).unwrap();
        let rows = || {
            db.prepare("SELECT source_world,source_file,target_world,target_file FROM file_dependencies ORDER BY source_world,source_file,target_file").unwrap()
            .query_map([], |r| Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?,r.get::<_,String>(3)?))).unwrap()
            .collect::<rusqlite::Result<Vec<_>>>().unwrap()
        };
        assert_eq!(
            rows(),
            [
                (
                    "main".into(),
                    "A.java".into(),
                    "main".into(),
                    "B.java".into()
                ),
                (
                    "main".into(),
                    "A.java".into(),
                    "main".into(),
                    "Note.java".into()
                ),
                (
                    "main".into(),
                    "B.java".into(),
                    "main".into(),
                    "A.java".into()
                ),
                (
                    "main".into(),
                    "B.java".into(),
                    "main".into(),
                    "Note.java".into()
                ),
                (
                    "test".into(),
                    "Test.java".into(),
                    "main".into(),
                    "B.java".into()
                ),
            ]
        );
        db.execute("DELETE FROM facts WHERE owner='B.java'", [])
            .unwrap();
        prepare_queries(&db).unwrap();
        refresh_file_dependencies(&db).unwrap();
        assert_eq!(
            rows(),
            [(
                "main".into(),
                "A.java".into(),
                "main".into(),
                "Note.java".into()
            )]
        );
    }

    #[test]
    fn declaration_projection_tracks_identity_changes_and_owner_deletion() {
        let db = Connection::open_in_memory().unwrap();
        initialize(&db).unwrap();
        db.execute("INSERT INTO facts VALUES('a','declaration','owner','','','','Api','A.java','main',?)", [r#"{"signature":"p.Api","attributes":{"package":"p","element_kind":"CLASS","entity_kind":"type","visibility":"public","synthetic":false}}"#]).unwrap();
        let row: (String, String, String, bool) = db.query_row("SELECT signature,package,entity_kind,synthetic FROM declaration_projection WHERE id='a'", [], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?))).unwrap();
        assert_eq!(row, ("p.Api".into(), "p".into(), "type".into(), false));
        db.execute("UPDATE facts SET body=? WHERE id='a'", [r#"{"signature":"q.Api","attributes":{"package":"q","element_kind":"INTERFACE","synthetic":true}}"#]).unwrap();
        assert_eq!(
            db.query_row(
                "SELECT signature FROM declaration_projection WHERE id='a'",
                [],
                |r| r.get::<_, String>(0)
            )
            .unwrap(),
            "q.Api"
        );
        db.execute("UPDATE facts SET kind='reference' WHERE id='a'", [])
            .unwrap();
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM declaration_projection", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
        db.execute("UPDATE facts SET kind='declaration' WHERE id='a'", [])
            .unwrap();
        db.execute("DELETE FROM facts WHERE owner='owner'", [])
            .unwrap();
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM declaration_projection", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
    }

    #[test]
    fn legacy_indexes_upgrade_without_changing_facts_and_prepare_is_idempotent() {
        let db = Connection::open_in_memory().unwrap();
        initialize(&db).unwrap();
        db.execute_batch("CREATE INDEX fact_parent ON facts(parent); CREATE INDEX fact_name ON facts(name); INSERT INTO facts VALUES('a','declaration','o','','','','A','A.java','main','{\"signature\":\"A\"}');").unwrap();
        prepare_queries(&db).unwrap();
        prepare_queries(&db).unwrap();
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM facts", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            1
        );
        assert_eq!(
            db.query_row(
                "SELECT count(*) FROM sqlite_master WHERE name='fact_name'",
                [],
                |r| r.get::<_, i64>(0)
            )
            .unwrap(),
            0
        );
        assert_eq!(
            db.query_row(
                "SELECT name FROM facts WHERE instr(name,'A') > 0",
                [],
                |r| r.get::<_, String>(0)
            )
            .unwrap(),
            "A"
        );
        let columns: Vec<String> = db
            .prepare("PRAGMA index_info(fact_parent)")
            .unwrap()
            .query_map([], |r| r.get(2))
            .unwrap()
            .collect::<rusqlite::Result<_>>()
            .unwrap();
        assert_eq!(columns, ["parent", "kind", "relation", "id"]);
        for (sql, expected) in [
            (
                "SELECT body FROM facts WHERE kind='reference' AND relation='xml_ref' AND parent='bean' ORDER BY id",
                "fact_parent",
            ),
            (
                "SELECT body FROM facts WHERE kind='declaration' AND source='A.java' AND world='main' AND json_extract(body,'$.signature')='A' ORDER BY id",
                "fact_declaration_identity",
            ),
            (
                "SELECT body FROM facts WHERE kind='call_target' AND target='callee' ORDER BY id",
                "fact_call_targets_reverse",
            ),
        ] {
            let plan: Vec<String> = db
                .prepare(&format!("EXPLAIN QUERY PLAN {sql}"))
                .unwrap()
                .query_map([], |r| r.get(3))
                .unwrap()
                .collect::<rusqlite::Result<_>>()
                .unwrap();
            assert!(plan.iter().any(|s| s.contains(expected)), "{plan:?}");
            // The directed reference and declaration indexes also supply ID order.
            assert!(!plan.iter().any(|s| s.contains("TEMP B-TREE")), "{plan:?}");
        }
        assert_eq!(
            db.query_row("PRAGMA integrity_check", [], |r| r.get::<_, String>(0))
                .unwrap(),
            "ok"
        );
    }
}
