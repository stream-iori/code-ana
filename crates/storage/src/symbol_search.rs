//! Literal symbol search using Anatomist's external-content FTS5 approach.
use super::*;

pub(crate) fn prepare(db: &Connection) -> Result<()> {
    let exists: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE name='symbol_names' AND type='table')",
        [],
        |r| r.get(0),
    )?;
    db.execute_batch(include_str!("symbol-search.sql"))?;
    if !exists {
        db.execute(
            "INSERT INTO symbol_names(symbol_names) VALUES('rebuild')",
            [],
        )?;
    }
    Ok(())
}

pub(crate) fn select(db: &Connection, text: &str) -> Result<Vec<Fact>> {
    let indexed: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE name='symbol_names' AND type='table')",
        [],
        |r| r.get(0),
    )?;
    // Old read-only revisions and short/NUL selectors keep exact substring
    // behavior through SQL. FTS candidates are still checked with instr().
    let trigram = indexed && text.chars().count() >= 3 && !text.contains('\0');
    let predicate = "kind IN ('declaration','external_symbol') AND (instr(name,?1)>0 OR instr(COALESCE(json_extract(body,'$.signature'),''),?1)>0)";
    let sql = if trigram {
        format!(
            "SELECT body FROM facts WHERE rowid IN (SELECT rowid FROM symbol_names WHERE symbol_names MATCH ?2) AND {predicate} ORDER BY id LIMIT 100001"
        )
    } else {
        format!("SELECT body FROM facts WHERE {predicate} ORDER BY id LIMIT 100001")
    };
    let mut statement = db.prepare(&sql)?;
    let query = format!("\"{}\"", text.replace('"', "\"\""));
    let mut rows = if trigram {
        statement.query(params![text, query])?
    } else {
        statement.query([text])?
    };
    let mut output = Vec::new();
    while let Some(row) = rows.next()? {
        output.push(serde_json::from_str(&row.get::<_, String>(0)?)?);
    }
    ensure!(output.len() <= MAX_FACTS, "QUERY_WORK_BUDGET");
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn insert(db: &Connection, id: &str, kind: FactKind, name: &str, signature: &str) {
        let f: Fact = serde_json::from_value(serde_json::json!({"id":id,"kind":kind.name(),"owner":{"world":"w","producer":"mock","kind":"aggregate","id":"o"},"origin":"mock","name":name,"signature":signature})).unwrap();
        db.execute(
            "INSERT INTO facts VALUES(?,?,?,?,?,?,?,?,?,?)",
            params![
                id,
                kind.name(),
                f.owner.key(),
                "",
                "",
                "",
                name,
                "",
                "w",
                json(&f).unwrap()
            ],
        )
        .unwrap();
    }
    #[test]
    fn indexed_and_legacy_search_preserve_literal_text_and_delta_changes() {
        let db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        insert(
            &db,
            "a",
            FactKind::Declaration,
            "WidgetFactory",
            "pkg.WidgetFactory.create() ",
        );
        insert(&db, "b", FactKind::ExternalSymbol, "组件查询", "p.组件查询");
        insert(
            &db,
            "c",
            FactKind::Declaration,
            "a\"quoted name",
            "a\"quoted name.foo()",
        );
        insert(
            &db,
            "d",
            FactKind::Facet,
            "WidgetFactory",
            "pkg.WidgetFactory",
        );
        let selectors = [
            "Widget",
            "widget",
            "getFac",
            "Factory.create()",
            "组",
            "组件查",
            "a\"quoted",
            "quoted name",
            "",
            "\0",
        ];
        let before: Vec<Vec<Fact>> = selectors.iter().map(|s| select(&db, s).unwrap()).collect();
        prepare(&db).unwrap();
        prepare(&db).unwrap();
        for (i, text) in selectors.iter().enumerate() {
            assert_eq!(before[i], select(&db, text).unwrap(), "{text:?}");
        }
        db.execute("DELETE FROM facts WHERE id='a'", []).unwrap();
        assert!(select(&db, "Widget").unwrap().is_empty());
        insert(
            &db,
            "a",
            FactKind::Declaration,
            "Changed",
            "pkg.NewThing() ",
        );
        assert_eq!(select(&db, "NewThing").unwrap()[0].id, "a");
        db.execute("UPDATE facts SET kind='facet' WHERE id='a'", [])
            .unwrap();
        assert!(select(&db, "NewThing").unwrap().is_empty());
        db.execute(
            "INSERT INTO symbol_names(symbol_names,rank) VALUES('integrity-check',1)",
            [],
        )
        .unwrap();
    }
    #[test]
    fn selective_search_does_not_load_all_symbols_before_filtering() {
        let mut db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        let tx = db.transaction().unwrap();
        for i in 0..=MAX_FACTS {
            insert(
                &tx,
                &format!("bulk_{i}"),
                FactKind::Declaration,
                "Common",
                "",
            );
        }
        insert(
            &tx,
            "needle",
            FactKind::Declaration,
            "RareSearchNeedle",
            "pkg.RareSearchNeedle() ",
        );
        tx.commit().unwrap();
        prepare(&db).unwrap();
        let found = select(&db, "RareSearchNeedle").unwrap();
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].id, "needle");
        assert!(
            select(&db, "Common")
                .unwrap_err()
                .to_string()
                .contains("QUERY_WORK_BUDGET")
        );
    }
}
