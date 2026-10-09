//! SQLite fact storage schema. All upgrades run in a private candidate.
use anyhow::Result;
use rusqlite::Connection;

pub(crate) fn initialize(db: &Connection) -> Result<()> {
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
    // uses name equality; remove the obsolete full-table name index only here.
    db.execute_batch("DROP INDEX IF EXISTS fact_name")?;
    db.execute_batch(include_str!("query-indexes.sql"))?;
    super::symbol_search::prepare(db)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

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
