//! Source/world replacement, round-trip and identity regressions.
use super::test_support::canonical;
use super::tests::captured;
use super::*;
use codeana_contracts::{
    fixture_graph,
    graph::{Table, cell},
};
#[test]
fn unit_nodes_and_external_references_survive_reuse_and_replace_global_snapshots() -> Result<()> {
    use codeana_contracts::graph_rows::{EdgeRow, GraphRow};
    use codeana_graph_io::records::{Origin, Record};
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (mut revision, mut header, template) = captured(&store);
    let a = revision.manifest.files[0].clone();
    let mut b = a.clone();
    b.path = "B.java".into();
    revision.manifest.files.push(b.clone());
    revision.unit_ownership = Some(UnitOwnershipLedger {
        version: UNIT_OWNERSHIP_VERSION,
        units: [("left", a), ("right", b)]
            .into_iter()
            .map(|(id, source)| UnitOwnership {
                unit: id.into(),
                backend: AnalysisBackend::Java,
                availability: UnitAvailability::Ready,
                source_languages: vec![SourceLanguage::Java],
                modules: vec!["app".into()],
                worlds: vec!["main".into()],
                scopes: vec!["MAIN".into()],
                producer: "mock".into(),
                semantic_profile: hash("profile"),
                input_digest: hash(id),
                inputs: vec![source.clone()],
                sources: vec![source],
            })
            .collect(),
    });
    header.manifest = revision.manifest.id();
    let node = |id: &str, file: &str| {
        let mut row = template[0].clone();
        for (column, value) in [(0, id), (1, id), (12, file)] {
            row.cells[column].value = Some(cell::Value::Text(value.into()));
        }
        row.world = if file.is_empty() { "" } else { "main" }.into();
        row
    };
    let rows = |global: bool| -> Vec<Result<Record>> {
        let left = Origin::Unit(std::sync::Arc::new("left".into()));
        let right = Origin::Unit(std::sync::Arc::new("right".into()));
        let mut rows = vec![
            Ok(left.record(node("owner", "A.java"))),
            Ok(right.record(node("peer", "B.java"))),
        ];
        if global {
            rows.push(Ok(left.record(node("library", ""))));
        }
        rows.push(Ok(left.record(
            EdgeRow {
                source_ref: Some("owner".into()),
                source_is_key: Some(1),
                resolved_source: Some("owner".into()),
                external_target_fqn: Some("peer".into()),
                is_external: Some(1),
                relation: Some("REFERENCES".into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                producer_id: Some("mock".into()),
                source_file: Some("A.java".into()),
                source_module: Some("app".into()),
                source_scope: Some("MAIN".into()),
                ..Default::default()
            }
            .into_row("main"),
        )));
        rows
    };
    let first = store.publish_staged(
        store.stage_owned_graph_records_with_context(
            revision.clone(),
            &header,
            rows(true).into_iter(),
            "",
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "unit-nodes-full",
    )?;
    let reader = store.read(&first.id)?;
    assert_eq!(
        reader
            .connection
            .query_row("SELECT count(*) FROM node_units", [], |r| r
                .get::<_, i64>(0))?,
        3
    );
    let inputs = Manifest {
        files: revision.unit_ownership.as_ref().unwrap().units[0]
            .inputs
            .clone(),
    };
    assert!(
        reader
            .baseline_summary_for(&inputs, &"left".into())?
            .nodes
            .iter()
            .any(|n| n.id == "library")
    );
    assert!(
        !reader
            .baseline_summary_for(&inputs, &"right".into())?
            .nodes
            .iter()
            .any(|n| n.id == "library")
    );
    let reused = store.publish_staged(
        store.stage_reused_graph_with_context(
            revision.clone(),
            &first.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "unit-nodes-reused",
    )?;
    assert_eq!(
        canonical(&reader.connection)?,
        canonical(&store.read(&reused.id)?.connection)?
    );
    let mut conflicting = rows(true);
    let mut library = node("library", "");
    library.cells[21].value = Some(cell::Value::Text(r#"{"different":true}"#.into()));
    conflicting.push(Ok(
        Origin::Unit(std::sync::Arc::new("right".into())).record(library)
    ));
    let error = store
        .stage_owned_graph_records_with_context(
            revision.clone(),
            &header,
            conflicting.into_iter(),
            &reused.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )
        .err()
        .context("conflicting shared node accepted")?;
    assert_eq!(
        error
            .downcast_ref::<DomainError>()
            .context(format!("{error:#}"))?
            .code,
        ErrorCode::GraphWorldOwnershipConflict
    );
    assert_eq!(store.head("t")?, reused.id);
    header.mode = AnalysisMode::Delta;
    header.baseline = reused.id.clone();
    header.replacements = revision
        .manifest
        .files
        .iter()
        .map(|source| Owner {
            world: source.world.clone().into(),
            producer: "mock".into(),
            kind: "source".into(),
            id: source.path.clone(),
        })
        .collect();
    let delta = store.publish_staged(
        store.stage_owned_graph_records_with_context(
            revision.clone(),
            &header,
            rows(false).into_iter(),
            &reused.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "unit-nodes-delta",
    )?;
    let db = &store.read(&delta.id)?.connection;
    assert_eq!(
        db.query_row("SELECT count(*) FROM nodes WHERE id='library'", [], |r| r
            .get::<_, i64>(
            0
        ))?,
        0
    );
    assert_eq!(
        db.query_row("SELECT is_external FROM edges", [], |r| r.get::<_, i64>(0))?,
        1
    );
    assert_eq!(
        db.query_row(
            "SELECT c.unit_id FROM graph_edges e JOIN unit_catalog c USING(unit_pk)",
            [],
            |r| r.get::<_, String>(0)
        )?,
        "left"
    );
    header.mode = AnalysisMode::Full;
    header.baseline = delta.id.clone();
    header.replacements.clear();
    let full = store.publish_staged(
        store.stage_owned_graph_records_with_context(
            revision,
            &header,
            rows(false).into_iter(),
            &delta.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "unit-nodes-full-again",
    )?;
    assert_eq!(
        canonical(db)?,
        canonical(&store.read(&full.id)?.connection)?
    );
    assert_eq!(
        reader
            .connection
            .query_row("SELECT count(*) FROM nodes WHERE id='library'", [], |r| r
                .get::<_, i64>(
                0
            ))?,
        1
    );
    Ok(())
}
#[test]
fn unit_metadata_survives_real_candidates_delta_reuse_and_matches_full() -> Result<()> {
    use codeana_contracts::graph_rows::{
        CoverageRow, DiagnosticRow, GraphRow, ProjectMetaRow, ProviderRow,
    };
    use codeana_graph_io::records::{Origin, Record};
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (mut revision, mut header, _) = captured(&store);
    let source = revision.manifest.files[0].clone();
    revision.unit_ownership = Some(UnitOwnershipLedger {
        version: UNIT_OWNERSHIP_VERSION,
        units: ["left", "right"]
            .into_iter()
            .map(|id| {
                let sources = if id == "left" {
                    vec![source.clone()]
                } else {
                    vec![]
                };
                UnitOwnership {
                    unit: id.into(),
                    backend: AnalysisBackend::Java,
                    availability: UnitAvailability::Ready,
                    source_languages: vec![SourceLanguage::Java],
                    modules: vec!["app".into()],
                    worlds: vec!["main".into()],
                    scopes: vec!["MAIN".into()],
                    producer: "mock".into(),
                    semantic_profile: hash("profile"),
                    input_digest: hash(id),
                    inputs: sources.clone(),
                    sources,
                }
            })
            .collect(),
    });
    let records = |diagnostics: bool| -> Vec<Result<Record>> {
        let mut records = Vec::new();
        for id in ["left", "right"] {
            let origin = Origin::Unit(std::sync::Arc::new(id.into()));
            records.push(Ok(origin.record(
                ProviderRow {
                    provider_id: Some("java-core".into()),
                    language: Some("java".into()),
                    provider_version: Some("v1".into()),
                    operations: Some("[]".into()),
                    limitations: Some("{}".into()),
                    profile_hash: Some(hash(id)),
                }
                .into_row(""),
            )));
            records.push(Ok(origin.record(
                CoverageRow {
                    source_file: Some("*".into()),
                    module: Some("app".into()),
                    scope: Some("MAIN".into()),
                    language: Some("java".into()),
                    provider_id: Some("java-core".into()),
                    capability: Some("AGGREGATE".into()),
                    status: Some("complete".into()),
                    occurrences: Some(0),
                    groups_count: Some(0),
                    codes: Some("[]".into()),
                    code_counts: Some("{}".into()),
                    details_truncated: Some(0),
                }
                .into_row(""),
            )));
            records.push(Ok(origin.record(
                ProjectMetaRow {
                    key: Some("analysis_mode".into()),
                    value: Some("semantic".into()),
                }
                .into_row(""),
            )));
            if diagnostics {
                records.push(Ok(origin.record(
                    DiagnosticRow {
                        severity: Some("warning".into()),
                        code: Some("GLOBAL".into()),
                        phase: Some("analysis".into()),
                        source_file: if id == "left" {
                            Some(String::new())
                        } else {
                            None
                        },
                        module: Some("app".into()),
                        scope: Some("MAIN".into()),
                        language: Some("java".into()),
                        provider_id: Some("java-core".into()),
                        occurrence_count: Some(1),
                        sample: Some(id.into()),
                        ..Default::default()
                    }
                    .into_row(""),
                )));
            }
        }
        records
    };
    let first = store.publish_staged(
        store.stage_owned_graph_records_with_context(
            revision.clone(),
            &header,
            records(true).into_iter(),
            "",
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "unit-metadata-full",
    )?;
    let first_reader = store.read(&first.id)?;
    assert_eq!(
        first_reader.connection.query_row(
            "SELECT count(DISTINCT unit_pk) FROM unit_providers",
            [],
            |r| r.get::<_, i64>(0)
        )?,
        2
    );
    assert_eq!(
        first_reader
            .connection
            .query_row("SELECT count(*) FROM index_providers", [], |r| r
                .get::<_, i64>(0))?,
        1
    );
    header.mode = AnalysisMode::Delta;
    header.baseline = first.id.clone();
    let delta = store.publish_staged(
        store.stage_owned_graph_records_with_context(
            revision.clone(),
            &header,
            records(false).into_iter(),
            &first.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "unit-metadata-delta",
    )?;
    assert_eq!(
        store.read(&delta.id)?.connection.query_row(
            "SELECT count(*) FROM index_diagnostics",
            [],
            |r| r.get::<_, i64>(0)
        )?,
        0
    );
    let reused = store.publish_staged(
        store.stage_reused_graph_with_context(
            revision.clone(),
            &delta.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "unit-metadata-reuse",
    )?;
    let before = canonical(&store.read(&reused.id)?.connection)?;
    assert_eq!(before, canonical(&store.read(&delta.id)?.connection)?);
    header.mode = AnalysisMode::Full;
    header.baseline = reused.id.clone();
    let full = store.publish_staged(
        store.stage_owned_graph_records_with_context(
            revision,
            &header,
            records(false).into_iter(),
            &reused.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "unit-metadata-full-again",
    )?;
    assert_eq!(before, canonical(&store.read(&full.id)?.connection)?);
    assert_eq!(
        store.read(&first.id)?.connection.query_row(
            "SELECT count(*) FROM index_diagnostics",
            [],
            |r| r.get::<_, i64>(0)
        )?,
        2
    );
    Ok(())
}
#[test]
fn host_assigned_delivery_origin_cannot_borrow_a_peer_or_an_unavailable_unit() -> Result<()> {
    use codeana_graph_io::records::Origin;
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (mut revision, mut header, rows) = captured(&store);
    let a = revision.manifest.files[0].clone();
    let mut b = a.clone();
    b.path = "B.java".into();
    revision.manifest.files.push(b.clone());
    let ownership = |id: &str, source: SourceUnit| UnitOwnership {
        unit: id.into(),
        backend: AnalysisBackend::Java,
        availability: UnitAvailability::Ready,
        source_languages: vec![SourceLanguage::Java],
        modules: vec!["app".into()],
        worlds: vec!["main".into()],
        scopes: vec!["MAIN".into()],
        producer: "mock".into(),
        semantic_profile: hash("profile"),
        input_digest: hash(id),
        inputs: vec![source.clone()],
        sources: vec![source],
    };
    revision.unit_ownership = Some(UnitOwnershipLedger {
        version: UNIT_OWNERSHIP_VERSION,
        units: vec![ownership("left", a.clone()), ownership("right", b)],
    });
    header.manifest = revision.manifest.id();
    for origin in [
        Origin::Unit(std::sync::Arc::new("right".into())),
        Origin::Unit(std::sync::Arc::new("foreign".into())),
        Origin::Host,
    ] {
        let failure = store
            .stage_owned_graph_records_with_context(
                revision.clone(),
                &header,
                rows.clone().into_iter().map(|row| Ok(origin.record(row))),
                "",
                || Ok(()),
                |_, _| Ok(()),
                None,
            )
            .err()
            .context("foreign delivery origin accepted")?;
        assert_eq!(
            failure
                .downcast_ref::<DomainError>()
                .context(format!("{failure:#}"))?
                .code,
            ErrorCode::AnalysisUnitScope
        );
        assert_eq!(store.head("t")?, "");
    }
    // Shared auxiliary bytes are readable but never authorize publication.
    let right = &mut revision.unit_ownership.as_mut().unwrap().units[1];
    let mut auxiliary = a;
    auxiliary.auxiliary = true;
    right.inputs.push(auxiliary);
    let origin = Origin::Unit(std::sync::Arc::new("right".into()));
    assert!(
        store
            .stage_owned_graph_records_with_context(
                revision.clone(),
                &header,
                rows.clone().into_iter().map(|row| Ok(origin.record(row))),
                "",
                || Ok(()),
                |_, _| Ok(()),
                None
            )
            .is_err()
    );
    right_unavailable(&mut revision);
    assert!(
        store
            .stage_owned_graph_records_with_context(
                revision.clone(),
                &header,
                std::iter::once(Ok(origin.record(rows[0].clone()))),
                "",
                || Ok(()),
                |_, _| Ok(()),
                None
            )
            .is_err()
    );
    let origin = Origin::Unit(std::sync::Arc::new("left".into()));
    let candidate = store.stage_owned_graph_records_with_context(
        revision,
        &header,
        rows.into_iter().map(|row| Ok(origin.record(row))),
        "",
        || Ok(()),
        |_, _| Ok(()),
        None,
    )?;
    store.publish_staged(candidate, "correct-unit-origin")?;
    Ok(())
}
fn right_unavailable(revision: &mut Revision) {
    let unit = &mut revision.unit_ownership.as_mut().unwrap().units[1];
    unit.availability = UnitAvailability::FilesOnly;
    unit.sources.clear();
    unit.inputs.clear();
    unit.producer.clear();
    unit.semantic_profile.clear();
    unit.input_digest.clear();
}
#[test]
fn rust_state_and_lookup_dependencies_are_replaced_by_world_even_without_nodes() -> Result<()> {
    use codeana_contracts::graph_rows::{AnalysisDependencyRow, GraphRow, SourceStateRow};
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (mut revision, mut header, _) = captured(&store);
    let source = &mut revision.manifest.files[0];
    source.path = "A.rs".into();
    source.language = "rust".into();
    let mut other = source.clone();
    other.world = "test".into();
    other.scope = "TEST".into();
    revision.manifest.files.push(other);
    header.manifest = revision.manifest.id();
    let mut rows = Vec::new();
    for world in ["main", "test"] {
        rows.push(SourceStateRow {
                source_file: Some("A.rs".into()), world: Some(world.into()),
                state: Some(serde_json::json!({"file":"A.rs","world":world,"source_hash":revision.manifest.files[0].hash,"interface_hash":hash("interface"),"names":[]}).to_string()),
            }.into_row(world));
        rows.push(
            AnalysisDependencyRow {
                source_file: Some("A.rs".into()),
                world: Some(world.into()),
                kind: Some("lookup".into()),
                target_world: Some("dependency".into()),
                target_key: Some("missing".into()),
            }
            .into_row(world),
        );
    }
    let candidate = store.stage_graph_records(
        revision.clone(),
        &header,
        rows.into_iter().map(Ok),
        "",
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let first = store.publish_staged(candidate, "rust-worlds")?;
    assert_eq!(
        store
            .read(&first.id)?
            .rust_baseline_summary()?
            .rust_states
            .len(),
        2
    );
    revision.manifest.files.retain(|s| s.world == "test");
    header.mode = AnalysisMode::Delta;
    header.baseline = first.id.clone();
    header.manifest = revision.manifest.id();
    header.replacements = vec![Owner {
        world: "main".into(),
        producer: header.producer.clone(),
        kind: "source".into(),
        id: "A.rs".into(),
    }];
    let candidate = store.stage_graph_records(
        revision,
        &header,
        std::iter::empty(),
        &first.id,
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let second = store.publish_staged(candidate, "delete-rust-main")?;
    let states = store.read(&second.id)?.rust_baseline_summary()?.rust_states;
    assert_eq!(states.len(), 1);
    assert_eq!(states[0].world, "test");
    assert_eq!(states[0].dependencies.len(), 1);
    assert_eq!(states[0].dependencies[0].world, "dependency");
    assert_eq!(
        store
            .read(&first.id)?
            .rust_baseline_summary()?
            .rust_states
            .len(),
        2
    );
    Ok(())
}

#[test]
fn delta_rows_cannot_borrow_another_worlds_replacement_and_metadata_cannot_guess_a_world()
-> Result<()> {
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (mut revision, _, rows) = captured(&store);
    let mut other = revision.manifest.files[0].clone();
    other.world = "test".into();
    other.scope = "TEST".into();
    revision.manifest.files.push(other);
    let sources = revision
        .manifest
        .files
        .iter()
        .map(|s| ((s.path.as_str(), s.world.as_str()), s))
        .collect();
    let replaced = BTreeSet::from([("A.java", "main")]);
    let mut foreign = rows[0].clone();
    foreign.world = "test".into();
    foreign.cells[20].value = Some(cell::Value::Text("TEST".into()));
    assert!(
        validate_record(&foreign, &sources, &replaced, true)
            .unwrap_err()
            .to_string()
            .contains("GRAPH_SOURCE_NOT_REPLACED")
    );
    assert_eq!(
        validate_record(&rows[0], &sources, &replaced, true)?
            .unwrap()
            .world,
        "main"
    );
    let cache = fixture_graph::row(
        Table::FileCache,
        vec![serde_json::json!("provider"), serde_json::json!("A.java")],
    );
    assert!(
        validate_record(&cache, &sources, &replaced, false)
            .unwrap_err()
            .to_string()
            .contains("GRAPH_SOURCE_WORLD_REQUIRED")
    );
    Ok(())
}
#[test]
fn reverse_dependencies_bind_both_worlds_and_one_world_deletion_keeps_the_other() -> Result<()> {
    use codeana_contracts::graph_rows::{EdgeRow, GraphRow};
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (mut revision, mut header, template) = captured(&store);
    let source = revision.manifest.files[0].clone();
    revision.manifest.files.clear();
    let mut rows = Vec::new();
    for (world, scope) in [("main", "MAIN"), ("test", "TEST")] {
        for path in ["A.java", "B.java"] {
            revision.manifest.files.push(SourceUnit {
                path: path.into(),
                world: world.into(),
                scope: scope.into(),
                ..source.clone()
            });
            let id = format!("{path}-{world}");
            let mut node = template[0].clone();
            node.world = world.into();
            for column in [0, 1, 7, 9] {
                node.cells[column].value = Some(cell::Value::Text(id.clone()));
            }
            node.cells[12].value = Some(cell::Value::Text(path.into()));
            node.cells[20].value = Some(cell::Value::Text(scope.into()));
            rows.push(node);
        }
        rows.push(
            EdgeRow {
                source_ref: Some(format!("A.java-{world}")),
                target_ref: Some(format!("B.java-{world}")),
                relation: Some("REFERENCES".into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                is_external: Some(0),
                source_file: Some("A.java".into()),
                source_module: Some("app".into()),
                source_scope: Some(scope.into()),
                source_is_key: Some(1),
                target_is_key: Some(1),
                producer_id: Some("java-core".into()),
                ..Default::default()
            }
            .into_row(world),
        );
    }
    header.manifest = revision.manifest.id();
    let candidate = store.stage_graph_records(
        revision.clone(),
        &header,
        rows.into_iter().map(Ok),
        "",
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let first = store.publish_staged(candidate, "dependency-worlds")?;
    let before = store.read(&first.id)?;
    let targets = |world: &str| {
        [FileKey {
            world: world.into(),
            path: "B.java".into(),
        }]
    };
    for world in ["main", "test"] {
        let found = before.reverse_file_dependencies(&targets(world), None, "", 10)?;
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].source_world, world);
        assert_eq!(found[0].target_world, world);
    }
    revision
        .manifest
        .files
        .retain(|s| s.path != "A.java" || s.world != "main");
    header.mode = AnalysisMode::Delta;
    header.baseline = first.id.clone();
    header.manifest = revision.manifest.id();
    header.replacements = vec![Owner {
        world: "main".into(),
        producer: header.producer.clone(),
        kind: "source".into(),
        id: "A.java".into(),
    }];
    let candidate = store.stage_graph_records(
        revision,
        &header,
        std::iter::empty(),
        &first.id,
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let next = store.publish_staged(candidate, "delete-main-reference")?;
    let after = store.read(&next.id)?;
    assert!(
        after
            .reverse_file_dependencies(&targets("main"), None, "", 10)?
            .is_empty()
    );
    assert_eq!(
        after
            .reverse_file_dependencies(&targets("test"), None, "", 10)?
            .len(),
        1
    );
    assert_eq!(
        before
            .reverse_file_dependencies(&targets("main"), None, "", 10)?
            .len(),
        1
    );
    Ok(())
}

#[test]
fn deleting_one_world_at_a_shared_path_keeps_the_other_world_and_the_old_revision() -> Result<()> {
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (mut revision, mut header, mut rows) = captured(&store);
    rows[0].world = "main".into();
    let mut other_source = revision.manifest.files[0].clone();
    other_source.world = "test".into();
    other_source.scope = "TEST".into();
    revision.manifest.files.push(other_source);
    header.manifest = revision.manifest.id();
    let mut other_node = rows[0].clone();
    for (column, value) in [(0, "b"), (1, "B"), (7, "B"), (9, "B"), (20, "TEST")] {
        other_node.cells[column].value = Some(cell::Value::Text(value.into()));
    }
    other_node.world = "test".into();
    rows.push(other_node);
    use codeana_contracts::graph_rows::{EdgeRow, GraphRow};
    for (world, scope, id) in [("main", "MAIN", "a"), ("test", "TEST", "b")] {
        let mut metadata = fixture_graph::row(
            Table::AnnotationMeta,
            vec![
                serde_json::json!("fixture.Mark"),
                serde_json::json!("fixture.Meta"),
                serde_json::json!("Meta"),
                serde_json::json!("java"),
                serde_json::json!("java-core"),
                serde_json::json!("source"),
                serde_json::json!("exact"),
                serde_json::json!("A.java"),
                serde_json::json!(id),
                serde_json::json!("java-core"),
            ],
        );
        metadata.world = world.into();
        rows.push(metadata);
        let mut cache = fixture_graph::row(
            Table::FileCache,
            vec![
                serde_json::json!("java-core"),
                serde_json::json!("A.java"),
                serde_json::json!(revision.manifest.files[0].hash),
                serde_json::json!(36),
                serde_json::json!(1),
                serde_json::json!(2),
                serde_json::json!(10),
                serde_json::json!("contract"),
            ],
        );
        cache.world = world.into();
        rows.push(cache);
        rows.push(
            codeana_contracts::graph_rows::AnnotationRow {
                node_ref: Some(id.into()),
                resolved_node: Some(id.into()),
                annotation_fqn: Some("fixture.Mark".into()),
                raw_name: Some("Mark".into()),
                target_kind: Some("declaration".into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                mechanism: Some("source".into()),
                resolution_status: Some("exact".into()),
                source_file: Some("A.java".into()),
                source_module: Some("app".into()),
                source_scope: Some(scope.into()),
                node_is_key: Some(1),
                producer_id: Some("java-core".into()),
                ..Default::default()
            }
            .into_row(world),
        );
        rows.push(
            codeana_contracts::graph_rows::CoverageRow {
                source_file: Some("A.java".into()),
                module: Some("app".into()),
                scope: Some(scope.into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                capability: Some("DECLARATIONS".into()),
                status: Some("complete".into()),
                occurrences: Some(1),
                groups_count: Some(1),
                codes: Some("[]".into()),
                code_counts: Some("{}".into()),
                details_truncated: Some(0),
            }
            .into_row(world),
        );
        rows.push(
            codeana_contracts::graph_rows::DiagnosticRow {
                severity: Some("warning".into()),
                code: Some("WORLD_FIXTURE".into()),
                phase: Some("analysis".into()),
                source_file: Some("A.java".into()),
                module: Some("app".into()),
                scope: Some(scope.into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                occurrence_count: Some(1),
                sample: Some(id.into()),
                ..Default::default()
            }
            .into_row(world),
        );
        for ordinal in 0..2 {
            rows.push(
                EdgeRow {
                    source_ref: Some(id.into()),
                    target_ref: Some(id.into()),
                    resolved_source: Some(id.into()),
                    resolved_target: Some(id.into()),
                    relation: Some("CALLS".into()),
                    language: Some("java".into()),
                    provider_id: Some("java-core".into()),
                    call_kind: Some("static".into()),
                    confidence: Some("EXTRACTED".into()),
                    is_external: Some(0),
                    source_file: Some("A.java".into()),
                    begin_line: Some(1),
                    begin_column: Some(1),
                    end_line: Some(1),
                    end_column: Some(3),
                    source_ordinal: Some(ordinal),
                    source_module: Some("app".into()),
                    source_scope: Some(scope.into()),
                    source_is_key: Some(1),
                    target_is_key: Some(1),
                    producer_id: Some("java-core".into()),
                    ..Default::default()
                }
                .into_row(world),
            );
        }
    }
    let global = codeana_contracts::graph_rows::DiagnosticRow {
        severity: Some("info".into()),
        code: Some("GLOBAL_FIXTURE".into()),
        phase: Some("analysis".into()),
        occurrence_count: Some(1),
        ..Default::default()
    }
    .into_row("");
    rows.push(global.clone());
    let full_rows = rows
        .iter()
        .filter(|r| r.world == "test" || r.world.is_empty())
        .cloned()
        .collect::<Vec<_>>();
    let candidate = store.stage_graph_records(
        revision.clone(),
        &header,
        rows.into_iter().map(Ok),
        "",
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let first = store.publish_staged(candidate, "first")?;
    revision.manifest.files.retain(|s| s.world == "test");
    header.mode = AnalysisMode::Delta;
    header.baseline = first.id.clone();
    header.manifest = revision.manifest.id();
    header.replacements = vec![Owner {
        world: "main".into(),
        producer: header.producer.clone(),
        kind: "source".into(),
        id: "A.java".into(),
    }];
    let candidate = store.stage_graph_records(
        revision,
        &header,
        std::iter::once(Ok(global)),
        &first.id,
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let second = store.publish_staged(candidate, "delete-main")?;
    let reader = store.read(&second.id)?;
    let nodes = reader
        .connection
        .prepare("SELECT id,world FROM nodes ORDER BY id")?
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    assert_eq!(nodes, vec![("b".into(), "test".into())]);
    for (table, expected) in [
        ("annotations", 1),
        ("annotation_meta_owned", 1),
        ("file_cache", 1),
        ("analysis_coverage", 1),
        ("index_diagnostics", 2),
        ("call_sites", 2),
        ("call_site_targets", 2),
    ] {
        assert_eq!(
            reader
                .connection
                .query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r
                    .get::<_, i64>(0))?,
            expected,
            "{table}"
        );
    }
    assert_eq!(
        store.read(&first.id)?.connection.query_row(
            "SELECT count(*) FROM call_sites",
            [],
            |r| r.get::<_, i64>(0)
        )?,
        4
    );
    let expected = canonical(&reader.connection)?;
    header.mode = AnalysisMode::Full;
    header.baseline.clear();
    header.replacements.clear();
    let full_candidate = store.stage_graph_records(
        reader.revision.clone(),
        &header,
        full_rows.into_iter().map(Ok),
        &second.id,
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let full = store.publish_staged(full_candidate, "full-test-world")?;
    assert_eq!(expected, canonical(&store.read(&full.id)?.connection)?);
    assert_eq!(
        store
            .read(&first.id)?
            .connection
            .query_row("SELECT count(*) FROM nodes", [], |r| r.get::<_, i64>(0))?,
        2
    );
    Ok(())
}

#[test]
fn declarations_with_the_same_symbol_and_scope_keep_each_worlds_metadata() -> Result<()> {
    use codeana_contracts::graph_rows::{DeclarationRow, GraphRow};
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (mut revision, mut header, template) = captured(&store);
    let mut other = revision.manifest.files[0].clone();
    other.world = "other".into();
    revision.manifest.files.push(other);
    header.manifest = revision.manifest.id();
    let mut rows = Vec::new();
    for (world, visibility) in [("main", "public"), ("other", "private")] {
        let mut node = template[0].clone();
        node.world = world.into();
        node.cells[0].value = Some(cell::Value::Text(format!("type-{world}")));
        node.cells[1].value = Some(cell::Value::Text("fixture.Type".into()));
        rows.push(node);
        rows.push(
            DeclarationRow {
                symbol_id: Some("fixture.Type".into()),
                domain: Some("language".into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                entity_kind: Some("type".into()),
                language_kind: Some("CLASS".into()),
                qualified_name: Some("fixture.Type".into()),
                label: Some("Type".into()),
                kind: Some("CLASS".into()),
                declaration_kind: Some("type".into()),
                type_kind: Some("class".into()),
                visibility: Some(visibility.into()),
                modifiers: Some("[]".into()),
                declared_modifiers: Some("[]".into()),
                implicit_modifiers: Some("[]".into()),
                source_file: Some("A.java".into()),
                module: Some("app".into()),
                scope: Some("MAIN".into()),
                nesting_depth: Some(0),
                direct_member: Some(1),
                synthetic: Some(0),
                binding_resolved: Some(1),
                producer_id: Some("java-core".into()),
                ..Default::default()
            }
            .into_row(world),
        );
    }
    let candidate = store.stage_graph_records(
        revision.clone(),
        &header,
        rows.into_iter().map(Ok),
        "",
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let first = store.publish_staged(candidate, "declaration-worlds")?;
    let reader = store.read(&first.id)?;
    let values = reader
        .connection
        .prepare("SELECT world,visibility FROM nodes ORDER BY world")?
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    assert_eq!(
        values,
        vec![
            ("main".into(), "public".into()),
            ("other".into(), "private".into())
        ]
    );
    revision.manifest.files.retain(|s| s.world == "other");
    header.mode = AnalysisMode::Delta;
    header.baseline = first.id.clone();
    header.manifest = revision.manifest.id();
    header.replacements = vec![Owner {
        world: "main".into(),
        producer: header.producer.clone(),
        kind: "source".into(),
        id: "A.java".into(),
    }];
    let candidate = store.stage_graph_records(
        revision,
        &header,
        std::iter::empty(),
        &first.id,
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let second = store.publish_staged(candidate, "delete-main-type")?;
    let reader = store.read(&second.id)?;
    assert_eq!(
        reader
            .connection
            .query_row("SELECT visibility FROM nodes", [], |r| r
                .get::<_, String>(0))?,
        "private"
    );
    Ok(())
}

#[test]
fn colliding_worlds_cannot_overwrite_nodes_or_publish_ambiguous_public_ids() -> Result<()> {
    use codeana_contracts::graph_rows::{EdgeRow, GraphRow};
    for fault in ["node", "edge", "call"] {
        let directory = tempfile::tempdir()?;
        let store = Store::open(directory.path())?;
        let (mut revision, mut header, template) = captured(&store);
        let first = store.publish_staged(
            store.stage_graph_records(
                revision.clone(),
                &header,
                template.clone().into_iter().map(Ok),
                "",
                || Ok(()),
                |_, _| Ok(()),
            )?,
            "good",
        )?;
        let mut other = revision.manifest.files[0].clone();
        other.world = "other".into();
        revision.manifest.files.push(other);
        header.manifest = revision.manifest.id();
        let mut rows = Vec::new();
        if fault == "edge" {
            let mut global = template[0].clone();
            global.cells[12].value = Some(cell::Value::Text(String::new()));
            rows.push(global);
        }
        for world in ["main", "other"] {
            let id = if fault == "call" {
                format!("node-{world}")
            } else {
                "a".into()
            };
            if fault != "edge" {
                let mut node = template[0].clone();
                node.world = world.into();
                node.cells[0].value = Some(cell::Value::Text(id.clone()));
                rows.push(node);
            }
            if fault != "node" {
                rows.push(
                    EdgeRow {
                        source_ref: Some(id.clone()),
                        target_ref: Some(id.clone()),
                        resolved_source: Some(id.clone()),
                        resolved_target: Some(id),
                        relation: Some(if fault == "call" { "CALLS" } else { "USES" }.into()),
                        language: Some("java".into()),
                        provider_id: Some("java-core".into()),
                        is_external: Some(0),
                        source_file: Some("A.java".into()),
                        source_module: Some("app".into()),
                        source_scope: Some("MAIN".into()),
                        begin_line: Some(1),
                        begin_column: Some(1),
                        end_line: Some(1),
                        end_column: Some(3),
                        source_ordinal: Some(0),
                        source_is_key: Some(1),
                        target_is_key: Some(1),
                        producer_id: Some("java-core".into()),
                        ..Default::default()
                    }
                    .into_row(world),
                );
            }
        }
        let failure = store
            .stage_graph_records(
                revision,
                &header,
                rows.into_iter().map(Ok),
                &first.id,
                || Ok(()),
                |_, _| Ok(()),
            )
            .err()
            .context(format!("identity collision accepted: {fault}"))?;
        assert_eq!(
            failure
                .downcast_ref::<DomainError>()
                .context(format!("{fault}: {failure:#}"))?
                .code,
            ErrorCode::GraphWorldOwnershipConflict
        );
        assert_eq!(store.head("t")?, first.id);
        assert_eq!(
            store
                .read(&first.id)?
                .connection
                .query_row("SELECT count(*) FROM nodes", [], |r| r.get::<_, i64>(0))?,
            1
        );
    }
    Ok(())
}

#[test]
fn explicit_reuse_keeps_global_diagnostics_coverage_and_metadata_and_rejects_changed_inputs()
-> Result<()> {
    use codeana_contracts::graph_rows::{CoverageRow, DiagnosticRow, GraphRow};
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (revision, header, mut rows) = captured(&store);
    for code in ["deprecated", "CLASSPATH_UNAVAILABLE"] {
        rows.push(
            DiagnosticRow {
                severity: Some("warning".into()),
                code: Some(code.into()),
                phase: Some("analyze".into()),
                occurrence_count: Some(1),
                sample: Some("baseline global warning".into()),
                ..Default::default()
            }
            .into_row(""),
        );
    }
    rows.push(
        CoverageRow {
            source_file: Some("*".into()),
            module: Some("app".into()),
            scope: Some("MAIN".into()),
            language: Some("java".into()),
            provider_id: Some("java-core".into()),
            capability: Some("DECLARATIONS".into()),
            status: Some("partial".into()),
            occurrences: Some(1),
            groups_count: Some(1),
            codes: Some("[\"CLASSPATH_UNAVAILABLE\"]".into()),
            code_counts: Some("{\"CLASSPATH_UNAVAILABLE\":1}".into()),
            details_truncated: Some(0),
        }
        .into_row(""),
    );
    rows.push(fixture_graph::row(
        Table::AnnotationMeta,
        vec![
            serde_json::json!("external.Mark"),
            serde_json::json!("external.Meta"),
            serde_json::json!("Meta"),
            serde_json::json!("java"),
            serde_json::json!("java-core"),
            serde_json::json!("source"),
            serde_json::json!("exact"),
            serde_json::Value::Null,
            serde_json::Value::Null,
            serde_json::json!("java-core"),
        ],
    ));
    let candidate = store.stage_graph_records(
        revision.clone(),
        &header,
        rows.into_iter().map(Ok),
        "",
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let first = store.publish_staged(candidate, "reuse-baseline")?;
    let expected = canonical(&store.read(&first.id)?.connection)?;
    let candidate = store.stage_reused_graph_with_context(
        revision.clone(),
        &first.id,
        || Ok(()),
        |_, _| Ok(()),
        None,
    )?;
    let reused = store.publish_staged(candidate, "explicit-reused")?;
    assert_eq!(expected, canonical(&store.read(&reused.id)?.connection)?);
    assert_eq!(
        store.read(&reused.id)?.connection.query_row(
            "SELECT count(*) FROM index_diagnostics",
            [],
            |r| r.get::<_, i64>(0)
        )?,
        2
    );
    for change in ["source", "world", "role", "profile", "producer"] {
        let mut changed = revision.clone();
        match change {
            "source" => changed.manifest.files[0].hash = hash("changed"),
            "world" => changed.manifest.files[0].world = "other".into(),
            "role" => changed.manifest.files[0].auxiliary = true,
            "profile" => changed.profile = hash("other-profile"),
            _ => changed.producer = "other-producer".into(),
        }
        assert!(
            store
                .stage_reused_graph_with_context(
                    changed,
                    &reused.id,
                    || Ok(()),
                    |_, _| Ok(()),
                    None
                )
                .is_err(),
            "{change}"
        );
        assert_eq!(store.head("t")?, reused.id);
    }
    Ok(())
}

#[test]
fn reuse_ledger_ignores_only_integrity_generation_and_config_presentation_bytes() -> Result<()> {
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (mut revision, mut header, rows) = captured(&store);
    let semantic = revision.manifest.files[0].clone();
    let controls = [
        (
            ".code-ana/config.toml",
            "CONTROL",
            false,
            b"display=1".as_slice(),
        ),
        (
            ".code-ana/generation-a/report.json",
            "PREPARATION",
            false,
            b"generation-a".as_slice(),
        ),
        ("binding.txt", "BINDING", true, b"binding-v1".as_slice()),
    ];
    for (path, scope, auxiliary, bytes) in controls {
        let hash = hash(bytes);
        store.put_blobs(&BTreeMap::from([(hash.clone(), bytes.to_vec())]))?;
        revision.manifest.files.push(SourceUnit {
            path: path.into(),
            hash,
            size: bytes.len() as u64,
            world: "files".into(),
            module: String::new(),
            scope: scope.into(),
            language: String::new(),
            auxiliary,
        });
    }
    revision.unit_ownership = Some(UnitOwnershipLedger {
        version: UNIT_OWNERSHIP_VERSION,
        units: vec![UnitOwnership {
            unit: "java".into(),
            backend: AnalysisBackend::Java,
            availability: UnitAvailability::Ready,
            source_languages: vec![SourceLanguage::Java],
            modules: vec!["app".into()],
            worlds: vec!["main".into()],
            scopes: vec!["MAIN".into()],
            producer: "mock".into(),
            semantic_profile: hash("semantic"),
            input_digest: hash("input"),
            sources: vec![semantic],
            inputs: revision.manifest.files.clone(),
        }],
    });
    header.manifest = revision.manifest.id();
    let first = store.publish_staged(
        store.stage_graph_records(
            revision.clone(),
            &header,
            rows.into_iter().map(Ok),
            "",
            || Ok(()),
            |_, _| Ok(()),
        )?,
        "ledger-baseline",
    )?;
    let mut changed = revision.clone();
    for source in &mut changed.manifest.files {
        let bytes = if source.path == ".code-ana/config.toml" {
            Some(b"display=2".as_slice())
        } else if source.scope == "PREPARATION" {
            source.path = ".code-ana/generation-b/report.json".into();
            Some(b"generation-b".as_slice())
        } else {
            None
        };
        if let Some(bytes) = bytes {
            source.hash = hash(bytes);
            source.size = bytes.len() as u64;
            store.put_blobs(&BTreeMap::from([(source.hash.clone(), bytes.to_vec())]))?;
        }
    }
    changed.unit_ownership.as_mut().unwrap().units[0].inputs = changed.manifest.files.clone();
    let candidate = store.stage_reused_graph_with_context(
        changed.clone(),
        &first.id,
        || Ok(()),
        |_, _| Ok(()),
        None,
    )?;
    let reused = store.publish_staged(candidate, "new-integrity-generation")?;
    assert_eq!(
        canonical(&store.read(&first.id)?.connection)?,
        canonical(&store.read(&reused.id)?.connection)?
    );
    let host = AnalysisContext::new(
        AnalysisLimits {
            max_work: 1,
            ..AnalysisLimits::default()
        },
        None,
    )
    .map_err(DomainError::analysis)?;
    let limited = GraphStagingContext {
        hosts: BTreeMap::from([(AnalysisBackend::Java, host.clone())]),
        source: std::sync::Arc::new(|_, _| panic!("reuse validation must not read source bodies")),
    };
    let failure = store
        .stage_reused_graph_with_context(
            changed.clone(),
            &reused.id,
            || Ok(()),
            |_, _| Ok(()),
            Some(&limited),
        )
        .err()
        .context("reuse bypassed its host budget")?;
    assert_eq!(
        failure
            .downcast_ref::<DomainError>()
            .context(format!("{failure:#}"))?
            .code,
        ErrorCode::AnalysisResourceLimit
    );
    assert!(host.usage().failure.is_some());
    assert_eq!(store.head("t")?, reused.id);
    assert_eq!(
        canonical(&store.read(&reused.id)?.connection)?,
        canonical(&store.read(&first.id)?.connection)?
    );
    for field in [
        "binding-bytes",
        "binding-role",
        "unit-profile",
        "unit-producer",
    ] {
        let mut forged = changed.clone();
        match field {
            "unit-profile" => {
                forged.unit_ownership.as_mut().unwrap().units[0].semantic_profile = hash("other")
            }
            "unit-producer" => {
                forged.unit_ownership.as_mut().unwrap().units[0].producer = "other".into()
            }
            _ => {
                let source = forged
                    .manifest
                    .files
                    .iter_mut()
                    .find(|s| s.path == "binding.txt")
                    .unwrap();
                if field == "binding-bytes" {
                    source.hash = hash("changed-binding");
                } else {
                    source.auxiliary = false;
                }
                forged.unit_ownership.as_mut().unwrap().units[0].inputs =
                    forged.manifest.files.clone();
            }
        }
        let failure = store
            .stage_reused_graph_with_context(forged, &reused.id, || Ok(()), |_, _| Ok(()), None)
            .err()
            .context(format!("changed reuse accepted: {field}"))?;
        assert_eq!(
            failure
                .downcast_ref::<DomainError>()
                .context(format!("{failure:#}"))?
                .code,
            ErrorCode::ResultIdentityMismatch,
            "{field}"
        );
        assert_eq!(store.head("t")?, reused.id);
    }
    Ok(())
}

#[test]
fn unit_baseline_keeps_only_declared_bindings_and_never_collapses_same_path_worlds() -> Result<()> {
    use codeana_contracts::graph_rows::{EdgeRow, GraphRow, ProjectMetaRow};
    let directory = tempfile::tempdir()?;
    let store = Store::open(directory.path())?;
    let (mut revision, mut header, template) = captured(&store);
    let source = revision.manifest.files[0].clone();
    revision.manifest.files.clear();
    let mut rows = Vec::new();
    for (world, paths) in [
        ("main", vec!["A.java", "B.java"]),
        ("test", vec!["A.java", "B.java", "Foreign.java"]),
        ("dependency", vec!["Dep.java"]),
    ] {
        for path in paths {
            revision.manifest.files.push(SourceUnit {
                path: path.into(),
                world: world.into(),
                ..source.clone()
            });
            let mut node = template[0].clone();
            node.world = world.into();
            node.cells[0].value = Some(cell::Value::Text(format!("{world}:{path}")));
            node.cells[1].value = Some(cell::Value::Text(path.into()));
            node.cells[12].value = Some(cell::Value::Text(path.into()));
            rows.push(node);
        }
    }
    for (from, to, world, path) in [
        ("main:A.java", "main:B.java", "main", "A.java"),
        ("test:A.java", "test:B.java", "test", "A.java"),
        ("test:Foreign.java", "main:B.java", "test", "Foreign.java"),
        (
            "dependency:Dep.java",
            "main:B.java",
            "dependency",
            "Dep.java",
        ),
    ] {
        rows.push(
            EdgeRow {
                source_ref: Some(from.into()),
                target_ref: Some(to.into()),
                resolved_source: Some(from.into()),
                resolved_target: Some(to.into()),
                source_is_key: Some(1),
                target_is_key: Some(1),
                is_external: Some(0),
                relation: Some("INHERITS".into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                producer_id: Some("java-core".into()),
                source_file: Some(path.into()),
                source_module: Some("app".into()),
                source_scope: Some("MAIN".into()),
                ..Default::default()
            }
            .into_row(world),
        );
    }
    for world in ["main", "test", "dependency", "unrelated"] {
        rows.push(
            ProjectMetaRow {
                key: Some(format!("codeana.context.{world}")),
                value: Some(hash(world)),
            }
            .into_row(""),
        );
    }
    let mut ownership = Vec::new();
    for (unit, world) in [
        ("dependency", "dependency"),
        ("left", "main"),
        ("right", "test"),
    ] {
        let sources = revision
            .manifest
            .files
            .iter()
            .filter(|s| s.world == world)
            .cloned()
            .collect::<Vec<_>>();
        let mut inputs = sources.clone();
        if unit == "left" {
            // Same-path binding in another world must not mask main/B.java changes.
            inputs.extend(
                revision
                    .manifest
                    .files
                    .iter()
                    .filter(|s| s.world == "dependency" || s.world == "test" && s.path == "B.java")
                    .cloned()
                    .map(|mut s| {
                        s.auxiliary = true;
                        s
                    }),
            );
        }
        ownership.push(UnitOwnership {
            unit: unit.into(),
            backend: AnalysisBackend::Java,
            availability: UnitAvailability::Ready,
            source_languages: vec![SourceLanguage::Java],
            modules: vec!["app".into()],
            worlds: vec![world.into()],
            scopes: vec!["MAIN".into()],
            producer: "java-core".into(),
            semantic_profile: hash(unit),
            input_digest: hash(world),
            sources,
            inputs,
        });
    }
    revision.unit_ownership = Some(UnitOwnershipLedger {
        version: UNIT_OWNERSHIP_VERSION,
        units: ownership,
    });
    header.manifest = revision.manifest.id();
    let candidate = store.stage_graph_records(
        revision,
        &header,
        rows.into_iter().map(Ok),
        "",
        || Ok(()),
        |_, _| Ok(()),
    )?;
    let published = store.publish_staged(candidate, "scoped-unit-baseline")?;
    let reader = store.read(&published.id)?;
    let mut next = Manifest {
        files: published.unit_ownership.as_ref().unwrap().units[1]
            .inputs
            .clone(),
    };
    let unchanged = reader.baseline_summary_for(&next, &"left".into())?;
    assert!(unchanged.changed_sources.unwrap().is_empty());
    let ids = unchanged
        .nodes
        .iter()
        .map(|n| n.id.as_str())
        .collect::<BTreeSet<_>>();
    assert_eq!(
        ids,
        BTreeSet::from([
            "main:A.java",
            "main:B.java",
            "test:B.java",
            "dependency:Dep.java"
        ])
    );
    assert_eq!(
        unchanged
            .nodes
            .iter()
            .find(|n| n.id == "test:B.java")
            .unwrap()
            .world,
        "test"
    );
    assert_eq!(unchanged.type_relations.len(), 2);
    assert!(!unchanged.contexts.contains_key("unrelated"));
    assert_eq!(unchanged.files, next.files);
    next.files
        .iter_mut()
        .find(|s| s.path == "B.java" && s.world == "main")
        .unwrap()
        .hash = hash("changed");
    let changed = reader.baseline_summary_for(&next, &"left".into())?;
    assert_eq!(
        changed.changed_sources.unwrap(),
        BTreeSet::from(["A.java".into(), "B.java".into(), "Dep.java".into()])
    );
    assert_eq!(reader.baseline_summary()?.nodes.len(), 6);
    Ok(())
}
