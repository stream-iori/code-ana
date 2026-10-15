use anyhow::Result;
use codeana_model::GraphAccess;
use codeana_model::{
    hash,
    rust::{AnalysisConfig, CrateInput, FrozenFile, Inputs},
};
use codeana_rust_adaptor::{analyze, incremental, records};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::PathBuf,
};
fn config(scratch: &std::path::Path, extra: bool) -> AnalysisConfig {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures/rust/workspace");
    let mut crates = vec![];
    for (id, name, path) in [
        ("core", "fixture_core", "core/src/lib.rs"),
        ("app", "fixture_app", "app/src/main.rs"),
    ] {
        crates.push(CrateInput {
            id: id.into(),
            name: name.into(),
            module: id.into(),
            world: format!("world-{id}"),
            scope: "MAIN".into(),
            root: path.into(),
            directory: id.into(),
            edition: "2024".into(),
            target: "fixture".into(),
            cfg: if extra {
                vec!["feature=\"extra\"".into()]
            } else {
                vec![]
            },
            dependencies: if id == "app" {
                BTreeMap::from([("renamed".into(), "core".into())])
            } else {
                BTreeMap::new()
            },
            environment: BTreeMap::new(),
            publish: true,
            proc_macro: false,
            gaps: vec![],
        });
    }
    let files = ["core/src/lib.rs", "core/src/api.rs", "app/src/main.rs"]
        .into_iter()
        .map(|path| {
            let input = root.join(path);
            FrozenFile {
                path: path.into(),
                hash: hash(std::fs::read(&input).unwrap()),
                input,
                crates: vec![path.split('/').next().unwrap().into()],
            }
        })
        .collect();
    AnalysisConfig {
        baseline: None,
        excluded: Default::default(),
        inputs: Inputs {
            schema_version: 2,
            policy: codeana_model::hash(b"fixture-policy"),
            dependency_sources: BTreeMap::new(),
            omitted_dependencies: BTreeMap::new(),
            selection: Default::default(),
            toolchain: "fixture".into(),
            crates,
            controls: BTreeMap::new(),
            generated: BTreeMap::new(),
        },
        files,
        mode: codeana_model::rust::RustAnalysisMode::Semantic,
        scratch: scratch.into(),
        result_bytes: 10 << 20,
        fact_limit: 100000,
    }
}
#[test]
fn cross_crate_reexports_traits_macros_cfg_and_partial_evidence() -> Result<()> {
    let scratch = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
    let config = config(scratch.path(), false);
    let analysis = analyze(&config, &|| Ok(()))?;
    let declarations = analysis
        .declarations
        .iter()
        .map(|d| (d.id.as_str(), d))
        .collect::<BTreeMap<_, _>>();
    let answer = analysis
        .declarations
        .iter()
        .find(|d| d.name == "answer")
        .unwrap();
    let module = analysis
        .declarations
        .iter()
        .find(|d| d.name == "api")
        .unwrap();
    assert_eq!(answer.parent.as_deref(), Some(module.id.as_str()));
    assert!(
        analysis.relations.iter().any(|r| r.kind == "CALLS"
            && r.file.starts_with("app/")
            && r.target
                .as_deref()
                .and_then(|id| declarations.get(id))
                .is_some_and(|d| d.name == "answer")),
        "{:#?}",
        analysis.relations
    );
    assert!(analysis.relations.iter().any(|r| r.kind == "IMPLEMENTS"));
    assert!(
        !analysis
            .declarations
            .iter()
            .any(|d| d.synthetic || d.name == "generated")
    );
    assert!(
        analysis
            .gaps
            .values()
            .any(|g| g.contains("RUST_MACRO_EXPANSION_DISABLED"))
    );
    assert!(analysis.relations.iter().any(|r| r.kind == "CALLS"
        && r.syntax == "renamed::generated"
        && r.target.is_none()
        && r.external.is_none()
        && r.resolution == "unresolved"));
    assert!(
        !analysis
            .declarations
            .iter()
            .any(|d| d.name == "feature_only" || d.name == "test_only")
    );
    assert!(
        analysis
            .gaps
            .values()
            .any(|g| g.contains("RUST_DYNAMIC_DISPATCH"))
    );
    assert!(
        analysis
            .relations
            .iter()
            .any(|r| r.syntax == "missing" && r.resolution == "unresolved")
    );
    let archive = records::records(&config, &analysis, None)?;
    assert!(archive.archive.count > 20);
    let no_change = records::records(&config, &analysis, Some(&archive.states))?;
    assert!(no_change.replacements.is_empty());
    Ok(())
}
#[test]
fn changed_interface_and_removed_file_are_persistent_invalidations() -> Result<()> {
    let scratch = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
    let before = config(scratch.path(), false);
    let a = analyze(&before, &|| Ok(()))?;
    let mut after = before.clone();
    let file = after
        .files
        .iter_mut()
        .find(|f| f.path.ends_with("api.rs"))
        .unwrap();
    let input = scratch.path().join("api.rs");
    std::fs::write(&input, "pub fn answer() -> u64 { 43 }\npub fn added() {}")?;
    file.input = input;
    file.hash = hash(std::fs::read(&file.input)?);
    let b = analyze(&after, &|| Ok(()))?;
    let dirty = incremental::dirty(
        &incremental::states(&before, &a),
        &incremental::states(&after, &b),
    );
    assert!(dirty.contains(&("app/src/main.rs".into(), "world-app".into())));
    assert!(dirty.contains(&("core/src/api.rs".into(), "world-core".into())));
    let again = analyze(&after, &|| Ok(()))?;
    assert_eq!(
        b.declarations
            .iter()
            .map(|d| &d.id)
            .collect::<BTreeSet<_>>(),
        again.declarations.iter().map(|d| &d.id).collect()
    );
    Ok(())
}
#[test]
fn cancellation_and_frozen_input_drift_fail_before_publication() -> Result<()> {
    let scratch = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
    let mut config = config(scratch.path(), true);
    assert!(analyze(&config, &|| anyhow::bail!("CANCELLED")).is_err());
    config.files[0].hash = "wrong".into();
    assert!(
        analyze(&config, &|| Ok(()))
            .unwrap_err()
            .to_string()
            .contains("RUST_INPUT_CHANGED")
    );
    Ok(())
}

#[test]
fn mixed_archive_preserves_java_on_rust_delta_and_rejects_partial_publication() -> Result<()> {
    use codeana_model::{
        AnalysisHeader, FactFilter, FactKind, Manifest, Owner, Revision, SourceUnit,
    };
    let scratch = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
    let store = codeana_storage::Store::open(&scratch.path().join("home"))?;
    let mut config = config(scratch.path(), false);
    let mut manifest = Manifest::default();
    let mut blobs = BTreeMap::new();
    for file in &config.files {
        let input = config
            .inputs
            .crates
            .iter()
            .find(|c| file.crates.contains(&c.id))
            .unwrap();
        let bytes = std::fs::read(&file.input)?;
        manifest.files.push(SourceUnit {
            path: file.path.clone(),
            hash: file.hash.clone(),
            size: bytes.len() as u64,
            module: input.module.clone(),
            world: input.world.clone(),
            scope: input.scope.clone(),
            language: "rust".into(),
            auxiliary: false,
        });
        blobs.insert(file.hash.clone(), bytes);
    }
    let java = b"class JavaFixture {}".to_vec();
    manifest.files.push(SourceUnit {
        path: "JavaFixture.java".into(),
        hash: hash(&java),
        size: java.len() as u64,
        module: "java".into(),
        world: "java-main".into(),
        scope: "MAIN".into(),
        language: "java".into(),
        auxiliary: false,
    });
    blobs.insert(hash(&java), java);
    store.put_blobs(&blobs)?;
    let java_fact = serde_json::from_value(
        serde_json::json!({"id":"java-existing-id","kind":"declaration","name":"JavaFixture","signature":"JavaFixture","source":"JavaFixture.java","owner":{"kind":"source","id":"JavaFixture.java","world":"java-main","producer":"java-core"},"origin":"fixture"}),
    )?;
    let mut rows = codeana_contracts::fixture_graph::rows(&manifest, &[java_fact]);
    let analysis = analyze(&config, &|| Ok(()))?;
    let first = records::records(&config, &analysis, None)?;
    rows.extend(first.archive.records()?.collect::<Result<Vec<_>>>()?);
    let mut revision = Revision {
        id: String::new(),
        target: "mixed-target".into(),
        project: "mixed".into(),
        checkout: "checkout".into(),
        manifest,
        profile: "profile".into(),
        producer: "codeana-java-rust".into(),
        created: 0,
    };
    let mut header = AnalysisHeader {
        mode: codeana_model::AnalysisMode::Full,
        baseline: String::new(),
        manifest: revision.manifest.id(),
        profile: revision.profile.clone(),
        producer: revision.producer.clone(),
        replacements: vec![],
    };
    let candidate = store.stage_graph_records(
        revision.clone(),
        &header,
        rows.into_iter().map(Ok),
        "",
        || Ok(()),
        |_| Ok(()),
    )?;
    let baseline = store.publish_staged(candidate, "initial")?;
    assert_eq!(
        store
            .read(&baseline.id)?
            .rust_baseline_summary()?
            .rust_states,
        first.states
    );
    let next = scratch.path().join("edited.rs");
    let bytes = b"pub fn answer() -> u64 { 1 }";
    std::fs::write(&next, bytes)?;
    let file = config
        .files
        .iter_mut()
        .find(|f| f.path.ends_with("api.rs"))
        .unwrap();
    file.input = next;
    file.hash = hash(bytes);
    let source = revision
        .manifest
        .files
        .iter_mut()
        .find(|s| s.path == file.path)
        .unwrap();
    source.hash = file.hash.clone();
    source.size = bytes.len() as u64;
    store.put_blobs(&BTreeMap::from([(hash(bytes), bytes.to_vec())]))?;
    let delta = records::records(&config, &analyze(&config, &|| Ok(()))?, Some(&first.states))?;
    header.mode = codeana_model::AnalysisMode::Delta;
    header.baseline = baseline.id.clone();
    header.manifest = revision.manifest.id();
    header.replacements = delta
        .replacements
        .iter()
        .map(|(file, world)| Owner {
            world: world.clone().into(),
            producer: "rust-core".into(),
            kind: "source".into(),
            id: file.clone(),
        })
        .collect();
    let candidate = store.stage_graph_records(
        revision.clone(),
        &header,
        delta.archive.records()?,
        &baseline.id,
        || Ok(()),
        |_| Ok(()),
    )?;
    let published = store.publish_staged(candidate, "delta")?;
    let facts = store.read(&published.id)?.select(&FactFilter {
        kinds: vec![FactKind::Declaration],
        ..Default::default()
    })?;
    assert!(facts.iter().any(|f| f.id == "java-existing-id"));
    assert!(facts.iter().any(|f| f.owner.producer == "rust-core"));
    header.baseline = published.id.clone();
    assert!(
        store
            .stage_graph_records(
                revision,
                &header,
                std::iter::once(Err(anyhow::anyhow!("unsealed worker failure"))),
                &published.id,
                || Ok(()),
                |_| Ok(())
            )
            .is_err()
    );
    assert_eq!(store.head("mixed-target")?, published.id);
    Ok(())
}

#[test]
fn body_only_edit_rebinds_one_owner_and_keeps_unchanged_dependency_summaries() -> Result<()> {
    let scratch = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
    let mut config = config(scratch.path(), false);
    let before = analyze(&config, &|| Ok(()))?;
    let old = incremental::states(&config, &before);
    let file = config
        .files
        .iter_mut()
        .find(|f| f.path.ends_with("api.rs"))
        .unwrap();
    let text = std::fs::read_to_string(&file.input)?.replace("42", "43");
    file.input = scratch.path().join("api.rs");
    std::fs::write(&file.input, &text)?;
    file.hash = hash(&text);
    config.baseline = Some(old.clone());
    let delta = analyze(&config, &|| Ok(()))?;
    assert_eq!(
        delta.dirty.as_ref().unwrap(),
        &BTreeSet::from([("core/src/api.rs".into(), "world-core".into())])
    );
    assert!(delta.relations.iter().all(|r| r.file == "core/src/api.rs"));
    let states = incremental::states(&config, &delta);
    assert_eq!(
        states.iter().find(|s| s.file == "app/src/main.rs"),
        old.iter().find(|s| s.file == "app/src/main.rs")
    );
    config.baseline = None;
    let full = analyze(&config, &|| Ok(()))?;
    assert_eq!(states, incremental::states(&config, &full));
    Ok(())
}

#[test]
fn changed_reexport_invalidates_downstream_without_a_declaration_edit() -> Result<()> {
    let scratch = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
    let mut config = config(scratch.path(), false);
    let before = analyze(&config, &|| Ok(()))?;
    config.baseline = Some(incremental::states(&config, &before));
    let file = config
        .files
        .iter_mut()
        .find(|f| f.path == "core/src/lib.rs")
        .unwrap();
    let text =
        std::fs::read_to_string(&file.input)?.replace("pub use api::answer;", "use api::answer;");
    file.input = scratch.path().join("lib.rs");
    std::fs::write(&file.input, &text)?;
    file.hash = hash(&text);
    let delta = analyze(&config, &|| Ok(()))?;
    assert!(
        delta
            .dirty
            .as_ref()
            .unwrap()
            .contains(&("app/src/main.rs".into(), "world-app".into()))
    );
    Ok(())
}

#[test]
fn nested_impl_and_inline_module_body_edits_preserve_interfaces_but_constants_do_not() -> Result<()>
{
    let scratch = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
    let mut config = config(scratch.path(), false);
    let text = "pub struct S; impl S { pub const C:u32=1; pub fn value(&self)->u32 { 42 } } pub mod inline { pub fn value()->u32 { 42 } }";
    let source = scratch.path().join("api.rs");
    std::fs::write(&source, text)?;
    let file = config
        .files
        .iter_mut()
        .find(|f| f.path.ends_with("api.rs"))
        .unwrap();
    file.input = source.clone();
    file.hash = hash(text);
    let old = incremental::states(&config, &analyze(&config, &|| Ok(()))?);
    config.baseline = Some(old.clone());
    let body = text.replace("42", "43");
    std::fs::write(&source, &body)?;
    config
        .files
        .iter_mut()
        .find(|f| f.path.ends_with("api.rs"))
        .unwrap()
        .hash = hash(&body);
    let analysis = analyze(&config, &|| Ok(()))?;
    assert_eq!(
        analysis.dirty.as_ref().unwrap(),
        &BTreeSet::from([("core/src/api.rs".into(), "world-core".into())])
    );
    let updated = incremental::states(&config, &analysis);
    assert_eq!(
        old.iter()
            .find(|s| s.file.ends_with("api.rs"))
            .unwrap()
            .interface_hash,
        updated
            .iter()
            .find(|s| s.file.ends_with("api.rs"))
            .unwrap()
            .interface_hash
    );
    let constant = body.replace("C:u32=1", "C:u32=2");
    std::fs::write(&source, &constant)?;
    config
        .files
        .iter_mut()
        .find(|f| f.path.ends_with("api.rs"))
        .unwrap()
        .hash = hash(&constant);
    assert!(
        analyze(&config, &|| Ok(()))?
            .dirty
            .unwrap()
            .contains(&("app/src/main.rs".into(), "world-app".into()))
    );
    Ok(())
}
