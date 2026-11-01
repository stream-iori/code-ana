//! Explicit language completion policies translated to batch SQL.
//! Exact producer IDs have already been resolved by typed row ingestion.
use super::*;

pub(super) fn resolve_reference(
    db: &Connection,
    table: &str,
    output: &str,
    reference: &str,
    additional: &str,
) -> Result<()> {
    for policy in codeana_model::LanguagePolicy::ALL
        .into_iter()
        .filter(|p| p.reference_completion())
    {
        for candidate in [
            "n.source_file=t.source_file",
            "n.module=t.source_module AND n.scope=t.source_scope",
            "n.module=t.source_module AND n.scope='MAIN'",
            "n.scope='MAIN'",
            "1=1",
        ] {
            let language = format!(
                " AND t.language='{}' AND n.language=t.language",
                policy.language()
            );
            let matches = format!("n.symbol_id=t.{reference} AND {candidate}{language}");
            db.execute_batch(&format!("UPDATE {table} AS t SET {output}=(SELECT min(n.id) FROM stage_nodes n WHERE {matches}) WHERE {output} IS NULL AND t.{reference} IS NOT NULL AND {additional} AND (SELECT count(*) FROM stage_nodes n WHERE {matches})=1"))?;
        }
    }
    Ok(())
}

pub(super) fn resolve_external(db: &Connection) -> Result<()> {
    for policy in codeana_model::LanguagePolicy::ALL
        .into_iter()
        .filter(|p| p.reference_completion())
    {
        let language = policy.language();
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
            db.execute_batch(&format!("UPDATE stage_edges SET resolved_target=(SELECT min(n.id) FROM stage_nodes n WHERE {match_key} AND n.language=stage_edges.language),external_target_fqn=NULL,is_external=0,resolution=CASE WHEN relation='CALLS' AND confidence='INFERRED' THEN NULL ELSE resolution END WHERE is_external=1 AND language='{language}' AND {condition} AND (SELECT count(*) FROM stage_nodes n WHERE {match_key} AND n.language=stage_edges.language)=1"))?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_declared_language_completes_symbols_reflection_and_arity() -> Result<()> {
        for language in [
            Some("java"),
            Some("rust"),
            Some("python"),
            Some("future"),
            None,
        ] {
            let db = Connection::open_in_memory()?;
            initialize_stage(&db)?;
            db.execute("INSERT INTO stage_nodes(id,symbol_id,language,source_file,module,scope,arity_key,producer_id) VALUES('owner','Owner',?,'Main','m','MAIN',NULL,'test')", [language])?;
            db.execute("INSERT INTO stage_nodes(id,symbol_id,language,source_file,module,scope,arity_key,producer_id) VALUES('target','Outer.Inner',?,'Main','m','MAIN','work/1','test')", [language])?;
            for (key, arity, metadata) in [
                ("target", None, "{}"),
                ("Outer.Inner", None, "{}"),
                ("Outer$Inner", None, r#"{"via":"reflection"}"#),
                ("unknown", Some("work/1"), "{}"),
            ] {
                db.execute("INSERT INTO stage_edges(resolved_source,external_target_fqn,is_external,language,target_arity_key,metadata,producer_id) VALUES('owner',?,1,?,?,?,'test')", params![key,language,arity,metadata])?;
            }
            resolve_external(&db)?;
            let count: i64 = db.query_row(
                "SELECT count(*) FROM stage_edges WHERE resolved_target='target' AND is_external=0",
                [],
                |r| r.get(0),
            )?;
            assert_eq!(
                count,
                if language == Some("java") { 4 } else { 0 },
                "{language:?}"
            );
            // Exact IDs are authoritative for every language and bypass inference.
            db.execute("INSERT INTO stage_edges(resolved_source,resolved_target,is_external,language,producer_id) VALUES('owner','target',0,?,'test')", [language])?;
            assert_eq!(super::super::resolve(&db)?, 0);
            assert_eq!(db.query_row("SELECT count(*) FROM stage_edges WHERE resolved_target='target' AND is_external=0", [], |r| r.get::<_,i64>(0))?, count+1);
        }
        Ok(())
    }
    #[test]
    fn java_scope_priority_and_ambiguity_remain_batch_rules() -> Result<()> {
        let db = Connection::open_in_memory()?;
        initialize_stage(&db)?;
        for (id, file, module, scope) in [
            ("local", "A", "other", "OTHER"),
            ("module", "B", "m", "TEST"),
            ("main", "C", "m", "MAIN"),
            ("other", "D", "other", "MAIN"),
        ] {
            db.execute("INSERT INTO stage_nodes(id,symbol_id,language,source_file,module,scope,producer_id) VALUES(?,'work','java',?,?,?,'test')", params![id,file,module,scope])?;
        }
        for (file, module, scope) in [
            ("A", "m", "TEST"),
            ("unknown", "m", "TEST"),
            ("unknown", "m", "NONE"),
            ("unknown", "unknown", "NONE"),
        ] {
            db.execute("INSERT INTO stage_edges(source_ref,language,source_file,source_module,source_scope,producer_id) VALUES('work','java',?,?,?,'test')", params![file,module,scope])?;
        }
        resolve_reference(&db, "stage_edges", "resolved_source", "source_ref", "1=1")?;
        let ids = db
            .prepare("SELECT resolved_source FROM stage_edges ORDER BY seq")?
            .query_map([], |r| r.get::<_, Option<String>>(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        assert_eq!(
            ids,
            vec![
                Some("local".into()),
                Some("module".into()),
                Some("main".into()),
                None
            ]
        );
        Ok(())
    }
}
