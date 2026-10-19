use codeana_model::*;
use codeana_python_adaptor::{FrozenModule, analyze, frozen::WorldAnalysis, records};
use codeana_storage::Store;
use std::collections::BTreeMap;

#[test]
fn inferred_graph_survives_storage_and_unknowns_keep_python_identity() -> anyhow::Result<()> {
    let home = tempfile::tempdir()?;
    let store = Store::open(home.path())?;
    let source = include_str!("../../fixtures/python/inference/main.py");
    let module = FrozenModule {
        name: "main".into(),
        path: "main.py".into(),
        source: source.into(),
    };
    let context = python::Context {
        publish_roots: None,
        gaps: vec![],
        id: "main".into(),
        module: "app".into(),
        scope: "MAIN".into(),
        python_version: "3.12".into(),
        platform: "darwin".into(),
        roots: vec!["app".into()],
    };
    let manifest = Manifest {
        files: vec![SourceUnit {
            path: module.path.clone(),
            hash: hash(source),
            size: source.len() as u64,
            module: context.module.clone(),
            world: context.world(&[]),
            scope: context.scope.clone(),
            language: "python".into(),
            auxiliary: false,
        }],
    };
    store.put_blobs(&BTreeMap::from([(
        hash(source),
        source.as_bytes().to_vec(),
    )]))?;
    let mut analysis = analyze(std::slice::from_ref(&module), "3.12", "darwin")?;
    // Pyrefly can return a source definition that the adapter does not extract.
    // Such a reference must publish as a coverage gap, not an invalid edge.
    analysis.references.push(codeana_python_adaptor::Reference {
        relation: "REFERENCES".into(),
        path: module.path.clone(),
        start: 0,
        end: 1,
        targets: vec![codeana_python_adaptor::Target {
            name: "main.unextracted".into(),
            path: module.path.clone(),
            start: source.len() as u32,
            end: source.len() as u32,
        }],
    });
    let mut rows = vec![];
    records::emit(
        &[WorldAnalysis {
            owners: Default::default(),
            world: context.world(&[]),
            context,
            modules: vec![module],
            analysis,
        }],
        &manifest,
        "profile",
        |row| {
            rows.push(Ok(row));
            Ok(())
        },
    )?;
    let revision = Revision {
        id: String::new(),
        target: "target".into(),
        project: "project".into(),
        checkout: "checkout".into(),
        manifest: manifest.clone(),
        profile: "profile".into(),
        producer: python::PRODUCER.into(),
        created: 0,
    };
    let header = AnalysisHeader {
        mode: AnalysisMode::Full,
        baseline: String::new(),
        manifest: manifest.id(),
        profile: revision.profile.clone(),
        producer: revision.producer.clone(),
        replacements: vec![],
    };
    let candidate = store.stage_graph_records(
        revision,
        &header,
        rows.into_iter(),
        "",
        || Ok(()),
        |_| Ok(()),
    )?;
    assert!(!candidate.summary.evidence.semantic);
    let revision = store.publish_staged(candidate, "fixture")?;
    let db = rusqlite::Connection::open(
        home.path()
            .join("revisions")
            .join(&revision.id)
            .join("facts.db"),
    )?;
    let count = |sql: &str| db.query_row(sql, [], |r| r.get::<_, i64>(0));
    assert!(count("SELECT count(*) FROM nodes WHERE language='python'")? > 10);
    assert!(count("SELECT count(*) FROM call_sites WHERE language='python'")? > 5);
    assert_eq!(
        count("SELECT count(*) FROM call_sites WHERE language='java'")?,
        0
    );
    assert!(count("SELECT count(*) FROM edges WHERE relation='CONTAINS'")? > 5);
    assert!(
        count(
            "SELECT count(*) FROM analysis_coverage WHERE codes LIKE '%reference_target_not_extracted%'"
        )? > 0
    );
    assert!(
        count(
            "SELECT count(*) FROM call_site_targets WHERE target_id IN (SELECT id FROM nodes WHERE qualified_name='main.Service.run')"
        )? >= 2
    );
    assert!(count("SELECT count(*) FROM call_sites WHERE resolution_status='unresolved'")? > 0);
    let samples: String = db.query_row(
        "SELECT value FROM project_meta WHERE key LIKE 'python.extraction_samples.%'",
        [],
        |r| r.get(0),
    )?;
    let samples: serde_json::Value = serde_json::from_str(&samples)?;
    assert!(
        samples["dynamic_any"]
            .as_array()
            .unwrap()
            .iter()
            .any(|s| s["backend_target_count"] == 0)
    );
    assert!(
        samples["reference_target_not_extracted"]
            .as_array()
            .is_some()
    );
    assert!(
        samples
            .as_object()
            .unwrap()
            .values()
            .all(|v| v.as_array().unwrap().len() <= 3)
    );
    let baseline = revision.id.clone();
    let reuse = AnalysisHeader {
        mode: AnalysisMode::Delta,
        baseline: baseline.clone(),
        manifest: revision.manifest.id(),
        profile: revision.profile.clone(),
        producer: revision.producer.clone(),
        replacements: vec![],
    };
    let candidate = store.stage_graph_records(
        revision.clone(),
        &reuse,
        std::iter::empty(),
        &baseline,
        || Ok(()),
        |_| Ok(()),
    )?;
    let reused = store.publish_staged(candidate, "unchanged")?;
    let reused_db = rusqlite::Connection::open(
        home.path()
            .join("revisions")
            .join(&reused.id)
            .join("facts.db"),
    )?;
    for table in ["nodes", "edges", "call_sites", "analysis_coverage"] {
        let sql = format!("SELECT count(*) FROM {table}");
        assert_eq!(
            count(&sql)?,
            reused_db.query_row(&sql, [], |r| r.get::<_, i64>(0))?,
            "{table}"
        );
    }
    let logical = |connection: &rusqlite::Connection,
                   sql: &str|
     -> rusqlite::Result<Vec<Vec<rusqlite::types::Value>>> {
        let mut statement = connection.prepare(sql)?;
        let width = statement.column_count();
        statement
            .query_map([], |row| (0..width).map(|i| row.get(i)).collect())?
            .collect()
    };
    for sql in [
        "SELECT id,hex(fact_hash) FROM nodes ORDER BY id",
        "SELECT hex(fact_hash),fact_ordinal FROM edges ORDER BY 1,2",
        "SELECT hex(c.stable_hash),o.caller_id,o.source_file,c.resolution_status,t.target_id,t.external_target_fqn FROM call_sites c JOIN call_site_owners o USING(owner_pk) LEFT JOIN call_site_targets t ON t.call_site_pk=c.site_pk ORDER BY 1,5,6",
        "SELECT * FROM analysis_coverage ORDER BY source_file,world",
    ] {
        assert_eq!(logical(&db, sql)?, logical(&reused_db, sql)?, "{sql}");
    }
    let reader = store.read(&revision.id)?;
    for (operation, selector) in [
        (Operation::Resolve, "factory"),
        (Operation::Structure, "Service"),
        (Operation::Calls, "exercise"),
        (Operation::References, "factory"),
    ] {
        let result = codeana_query::run(
            &reader,
            &Request {
                operation,
                selector: selector.into(),
                ..Default::default()
            },
            &|unit| store.blob(&unit.hash),
        )?;
        assert!(!result.records.is_empty(), "{operation}: {selector}");
    }
    Ok(())
}
