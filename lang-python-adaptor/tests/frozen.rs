use codeana_model::{hash, python::*};
use codeana_python_adaptor::frozen;

fn config(temp: &tempfile::TempDir, entries: &[(&str, &str)]) -> AnalysisConfig {
    let mut root = InputRoot {
        module: None,
        id: "app".into(),
        path: ".".into(),
        publish: true,
        files: vec![],
    };
    let mut files = Vec::new();
    for (index, (path, source)) in entries.iter().enumerate() {
        let input = temp.path().join(format!("blob-{index}"));
        std::fs::write(&input, source).unwrap();
        let sha256 = hash(source);
        root.files.push(InputFile {
            path: (*path).into(),
            sha256: sha256.clone(),
            bytes: source.len() as u64,
        });
        files.push(FrozenFile {
            root: "app".into(),
            relative: (*path).into(),
            source: (*path).into(),
            input,
            sha256,
            bytes: source.len() as u64,
        });
    }
    AnalysisConfig {
        inputs: Inputs {
            module_metadata: Default::default(),
            schema_version: 1,
            controls: vec![],
            metadata: None,
            roots: vec![root],
            contexts: vec![Context {
                publish_roots: None,
                gaps: vec![],
                id: "main".into(),
                module: "app".into(),
                scope: "MAIN".into(),
                python_version: "3.12".into(),
                platform: "darwin".into(),
                roots: vec!["app".into()],
            }],
        },
        files,
        excluded: Default::default(),
        scratch: temp.path().into(),
        result_bytes: 64 << 20,
        fact_limit: 100_000,
        scratch_bytes: 64 << 20,
    }
}

#[test]
fn sealed_tree_preserves_relative_and_namespace_imports_without_temporary_ids() {
    let temp = tempfile::tempdir().unwrap();
    let config = config(
        &temp,
        &[
            ("pkg/service.py", "def run():\n    return 1\n"),
            (
                "pkg/main.py",
                "from .service import run\ndef invoke():\n    return run()\n",
            ),
        ],
    );
    let result = frozen::analyze(&config, &|| Ok(())).unwrap();
    let call = result[0]
        .analysis
        .calls
        .iter()
        .find(|c| c.syntax == "run")
        .unwrap();
    assert_eq!(call.targets[0].path, "pkg/service.py");
    assert_eq!(call.targets[0].name, "pkg.service.run");
    assert!(
        !serde_json::to_string(&result[0].analysis)
            .unwrap()
            .contains(temp.path().to_str().unwrap())
    );
}

#[test]
fn tampered_capture_and_scratch_budget_are_rejected() {
    let temp = tempfile::tempdir().unwrap();
    let mut config = config(&temp, &[("main.py", "x = 1\n")]);
    config.scratch_bytes = 1;
    assert!(
        frozen::analyze(&config, &|| Ok(()))
            .err()
            .unwrap()
            .to_string()
            .contains("SCRATCH_BUDGET")
    );
    config.scratch_bytes = 64 << 20;
    std::fs::write(&config.files[0].input, "changed").unwrap();
    assert!(
        frozen::analyze(&config, &|| Ok(()))
            .err()
            .unwrap()
            .to_string()
            .contains("INPUT_CHANGED")
    );
}

#[test]
fn stub_precedence_reexports_and_missing_dependencies_are_explicit() {
    let temp = tempfile::tempdir().unwrap();
    let cfg = config(
        &temp,
        &[
            ("pkg/__init__.py", "from .service import make as factory\n"),
            ("pkg/service.py", "def make():\n    return 1\n"),
            (
                "pkg/service.pyi",
                "class Result:\n    def run(self) -> int: ...\ndef make() -> Result: ...\n",
            ),
            (
                "main.py",
                "from pkg import factory\nimport absent_dependency\ndef invoke():\n    return factory().run()\n",
            ),
        ],
    );
    let result = frozen::analyze(&cfg, &|| Ok(())).unwrap();
    assert!(result[0].analysis.diagnostics.iter().all(|d| {
        !d.message.contains("python-frozen-") && !d.message.contains(temp.path().to_str().unwrap())
    }));
    let call = result[0]
        .analysis
        .calls
        .iter()
        .find(|c| c.syntax == "factory().run")
        .unwrap();
    assert_eq!(call.targets[0].path, "pkg/service.pyi");
    assert!(
        result[0]
            .analysis
            .diagnostics
            .iter()
            .any(|d| d.code == "missing-import"),
        "{:?}",
        result[0].analysis.diagnostics
    );
}

#[test]
fn unchanged_context_roots_have_distinct_worlds_and_cancel_before_io() {
    let temp = tempfile::tempdir().unwrap();
    let mut cfg = config(&temp, &[("main.py", "def call():\n    return 1\n")]);
    let original = cfg.inputs.contexts[0].world(&cfg.inputs.roots);
    cfg.inputs.roots[0].path = "src".into();
    assert_ne!(original, cfg.inputs.contexts[0].world(&cfg.inputs.roots));
    assert!(
        frozen::analyze(&cfg, &|| anyhow::bail!("CANCELLED"))
            .err()
            .unwrap()
            .to_string()
            .contains("CANCELLED")
    );
}

#[test]
fn encoding_declarations_are_checked_without_losing_byte_offsets() {
    let temp = tempfile::tempdir().unwrap();
    let cfg = config(&temp, &[("main.py", "# coding: latin-1\nx = 1\n")]);
    assert!(
        frozen::analyze(&cfg, &|| Ok(()))
            .err()
            .unwrap()
            .to_string()
            .contains("ENCODING_UNSUPPORTED")
    );
    let text = "\u{feff}# coding: utf-8\n变量 = '😀'\ndef run():\n    return 变量\n";
    let cfg = config(&temp, &[("main.py", text)]);
    let results = frozen::analyze(&cfg, &|| Ok(())).unwrap();
    let run = results[0]
        .analysis
        .declarations
        .iter()
        .find(|d| d.name == "run")
        .unwrap();
    assert_eq!(&text[run.start as usize..run.end as usize], "run");
}

#[test]
fn overlapping_roots_publish_one_primary_module_and_excluded_files_stay_auxiliary() {
    let temp = tempfile::tempdir().unwrap();
    let mut cfg = config(
        &temp,
        &[
            ("pkg/service.py", "def run():\n    return 1\n"),
            ("main.py", "from service import run\nrun()\n"),
            ("not-importable/unused.py", "this is invalid !!!\n"),
        ],
    );
    let mut narrow = cfg.inputs.roots[0].clone();
    narrow.id = "narrow".into();
    narrow.path = "pkg".into();
    narrow.files.retain(|f| f.path == "pkg/service.py");
    narrow.files[0].path = "service.py".into();
    let mut duplicate = cfg.files[0].clone();
    duplicate.root = "narrow".into();
    duplicate.relative = "service.py".into();
    cfg.files.push(duplicate);
    cfg.inputs.roots.insert(0, narrow);
    cfg.inputs.contexts[0].roots.insert(0, "narrow".into());
    cfg.excluded.insert((
        "not-importable/unused.py".into(),
        cfg.inputs.contexts[0].world(&cfg.inputs.roots),
    ));
    let results = frozen::analyze(&cfg, &|| Ok(())).unwrap();
    assert_eq!(results[0].modules.len(), 2);
    assert_eq!(results[0].analysis.calls[0].targets[0].name, "service.run");
    assert!(
        !results[0]
            .analysis
            .diagnostics
            .iter()
            .any(|d| d.path == "not-importable/unused.py")
    );
}

#[test]
fn dependency_diagnostics_are_published_without_poisoning_unrelated_files() -> anyhow::Result<()> {
    use codeana_model::*;
    use codeana_python_adaptor::records;
    use codeana_storage::Store;
    use std::collections::BTreeMap;
    let temp = tempfile::tempdir()?;
    let mut cfg = config(
        &temp,
        &[
            (
                "main.py",
                include_str!("../../fixtures/python/diagnostics/main.py"),
            ),
            (
                "bridge.py",
                include_str!("../../fixtures/python/diagnostics/bridge.py"),
            ),
            (
                "broken.py",
                include_str!("../../fixtures/python/diagnostics/broken.py"),
            ),
            (
                "unrelated.py",
                include_str!("../../fixtures/python/diagnostics/unrelated.py"),
            ),
        ],
    );
    let mut test = cfg.inputs.contexts[0].clone();
    test.id = "test".into();
    test.scope = "TEST".into();
    cfg.inputs.contexts.push(test);
    let mut manifest = Manifest::default();
    for context in &cfg.inputs.contexts {
        let world = context.world(&cfg.inputs.roots);
        for file in &cfg.files {
            let auxiliary = matches!(file.source.as_str(), "bridge.py" | "broken.py");
            if auxiliary {
                cfg.excluded.insert((file.source.clone(), world.clone()));
            }
            manifest.files.push(SourceUnit {
                path: file.source.clone(),
                hash: file.sha256.clone(),
                size: file.bytes,
                module: context.module.clone(),
                world: world.clone(),
                scope: context.scope.clone(),
                language: "python".into(),
                auxiliary,
            });
        }
    }
    let mut worlds = frozen::analyze(&cfg, &|| Ok(()))?;
    for world in &worlds {
        let error = world
            .analysis
            .diagnostics
            .iter()
            .find(|d| d.code == "missing-import" && d.path == "broken.py")
            .unwrap();
        assert_eq!(format!("{:?}", error.attribution), "Exact");
        assert!(error.affected_sources.contains("main.py"), "{error:?}");
        assert!(!error.affected_sources.contains("unrelated.py"));
    }
    // An unattributable backend error must keep the context incomplete without
    // manufacturing file-level evidence. Other worlds must remain independent.
    let mut unknown = worlds[0]
        .analysis
        .diagnostics
        .iter()
        .find(|d| d.code == "missing-import")
        .unwrap()
        .clone();
    unknown.path = "backend/unknown.pyi".into();
    unknown.affected_sources.clear();
    unknown.attribution = serde_json::from_str("\"unknown\"")?;
    worlds[0].analysis.diagnostics.push(unknown);
    let home = tempfile::tempdir()?;
    let store = Store::open(home.path())?;
    let blobs = cfg
        .files
        .iter()
        .map(|f| Ok((f.sha256.clone(), std::fs::read(&f.input)?)))
        .collect::<anyhow::Result<BTreeMap<_, _>>>()?;
    store.put_blobs(&blobs)?;
    let revision = Revision {
        id: String::new(),
        target: "target".into(),
        project: "project".into(),
        checkout: "checkout".into(),
        manifest: manifest.clone(),
        profile: "fixture".into(),
        producer: python::PRODUCER.into(),
        created: 0,
    };
    let header = AnalysisHeader {
        mode: AnalysisMode::Full,
        baseline: String::new(),
        manifest: manifest.id(),
        profile: "fixture".into(),
        producer: python::PRODUCER.into(),
        replacements: vec![],
    };
    let mut rows = vec![];
    records::emit(&worlds, &manifest, "fixture", |row| {
        rows.push(Ok(row));
        Ok(())
    })?;
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
            .join(revision.id)
            .join("facts.db"),
    )?;
    let count = |sql: &str| db.query_row(sql, [], |r| r.get::<_, i64>(0));
    assert_eq!(
        count(
            "SELECT count(*) FROM analysis_coverage WHERE source_file='unrelated.py' AND status='complete'"
        )?,
        2
    );
    assert_eq!(
        count(
            "SELECT count(*) FROM analysis_coverage WHERE source_file='main.py' AND codes LIKE '%dependency_missing-import%'"
        )?,
        2
    );
    assert_eq!(
        count("SELECT count(*) FROM analysis_coverage WHERE source_file='*' AND status='partial'")?,
        1
    );
    assert!(
        count(
            "SELECT count(*) FROM index_diagnostics WHERE source_file='broken.py' AND sample_details LIKE '%main.py%'"
        )? >= 2
    );
    assert_eq!(
        count("SELECT count(*) FROM nodes WHERE source_file='broken.py'")?,
        0
    );
    Ok(())
}
