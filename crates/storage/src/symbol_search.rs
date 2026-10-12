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
    select_scoped(db, text, "1=1", Vec::new())
}
pub(crate) fn select_scoped(
    db: &Connection,
    text: &str,
    scope: &str,
    scope_values: Vec<String>,
) -> Result<Vec<Fact>> {
    let output = scan_scoped(db, text, scope, scope_values, "", MAX_FACTS + 1)?;
    ensure!(output.len() <= MAX_FACTS, "QUERY_WORK_BUDGET");
    Ok(output)
}
pub(crate) fn scan_scoped(
    db: &Connection,
    text: &str,
    scope: &str,
    scope_values: Vec<String>,
    after: &str,
    limit: usize,
) -> Result<Vec<Fact>> {
    let indexed: bool = db.query_row(
        "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE name='symbol_names' AND type='table')",
        [],
        |r| r.get(0),
    )?;
    // Old read-only revisions and short/NUL selectors keep exact substring
    // behavior through SQL. FTS candidates are still checked with instr().
    let trigram = indexed && text.chars().count() >= 3 && !text.contains('\0');
    let entity = "(f.kind IN ('declaration','external_symbol') OR (f.kind='facet' AND COALESCE(json_extract(f.body,'$.signature'),'')<>'' AND f.relation IN ('java.bean','java.sofa','java.spring.xml','java.spring.mvc','java.spring.handler')))";
    let predicate = format!(
        "{entity} AND (instr(f.name,?)>0 OR instr(COALESCE(json_extract(f.body,'$.signature'),''),?)>0)"
    );
    let mut values = vec![text.to_owned(), text.to_owned()];
    let sql = if trigram {
        format!(
            "SELECT f.body FROM facts f WHERE {predicate} AND (f.kind='facet' OR f.rowid IN (SELECT rowid FROM symbol_names WHERE symbol_names MATCH ?)) AND ({scope}) AND f.id>? ORDER BY f.id LIMIT ?"
        )
    } else {
        format!(
            "SELECT f.body FROM facts f WHERE {predicate} AND ({scope}) AND f.id>? ORDER BY f.id LIMIT ?"
        )
    };
    let mut statement = db.prepare(&sql)?;
    let query = format!("\"{}\"", text.replace('"', "\"\""));
    if trigram {
        values.push(query);
    }
    values.extend(scope_values);
    values.push(after.to_owned());
    values.push(limit.to_string());
    let mut rows = statement.query(rusqlite::params_from_iter(values))?;
    let mut output = Vec::new();
    while let Some(row) = rows.next()? {
        output.push(serde_json::from_str(&row.get::<_, String>(0)?)?);
    }
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
    fn configuration_entities_remain_searchable_in_legacy_indexed_and_delta_revisions() {
        let db = Connection::open_in_memory().unwrap();
        schema::initialize(&db).unwrap();
        for (id, relation) in [
            ("bean", "java.bean"),
            ("xml", "java.spring.xml"),
            ("binding", "java.binding"),
            ("syntax", "java.syntax"),
        ] {
            insert(
                &db,
                id,
                FactKind::Facet,
                "ConfiguredWidget",
                "bean:ConfiguredWidget@beans.xml",
            );
            let body: String = db
                .query_row("SELECT body FROM facts WHERE id=?", [id], |row| row.get(0))
                .unwrap();
            let mut fact: Fact = serde_json::from_str(&body).unwrap();
            fact.relation = relation.into();
            db.execute(
                "UPDATE facts SET relation=?,body=? WHERE id=?",
                params![relation, json(&fact).unwrap(), id],
            )
            .unwrap();
        }
        for indexed in [false, true] {
            if indexed {
                prepare(&db).unwrap();
            }
            for selector in [
                "Widget",
                "ConfiguredWidget",
                "bean:ConfiguredWidget@beans.xml",
                "Wi",
            ] {
                assert_eq!(
                    select(&db, selector)
                        .unwrap()
                        .iter()
                        .map(|f| f.id.as_str())
                        .collect::<Vec<_>>(),
                    ["bean", "xml"]
                );
            }
            assert_eq!(
                scan_scoped(&db, "Widget", "1=1", vec![], "bean", 1).unwrap()[0].id,
                "xml"
            );
        }
        db.execute("DELETE FROM facts WHERE id='bean'", []).unwrap();
        assert_eq!(select(&db, "Widget").unwrap().len(), 1);
        db.execute(
            "UPDATE facts SET relation='java.binding' WHERE id='xml'",
            [],
        )
        .unwrap();
        assert!(select(&db, "Widget").unwrap().is_empty());
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
