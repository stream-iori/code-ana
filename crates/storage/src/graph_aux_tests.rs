//! Shared auxiliary observations must survive independent unit replacement.
use super::test_support::{canonical, units_fixture};
use super::*;
use codeana_contracts::graph_rows::{
    AnnotationMetaRow, AnnotationRow, EdgeRow, FileCacheRow, FileDependencyRow, GraphRow,
    SymbolDependencyRow,
};
use sha2::{Digest, Sha256};

fn update(id: &str, kind: UnitUpdateKind) -> UnitUpdate {
    UnitUpdate {
        unit: id.into(),
        kind,
        replacements: vec![],
    }
}
fn observations(id: &str, version: u8, global: bool, hash: &str) -> Vec<Result<GraphRecord>> {
    let origin = Origin::Unit(Arc::new(id.into()));
    let mut rows = vec![
        FileCacheRow {
            provider_id: Some("java-core".into()),
            source_file: Some("Shared.java".into()),
            hash: Some(hash.into()),
            schema_version: Some(crate::graph_store::SOURCE_SCHEMA_VERSION),
            node_count: Some(version.into()),
            edge_count: Some(0),
            file_size: Some(10),
            contract_hash: Some("contract".into()),
        }
        .into_row("library"),
        FileDependencyRow {
            source_provider_id: Some("java-core".into()),
            source_file: Some("Shared.java".into()),
            depends_on_provider_id: Some("java-core".into()),
            depends_on_file: Some(
                if version == 1 {
                    "Target.java"
                } else {
                    "Other.java"
                }
                .into(),
            ),
        }
        .into_row("library"),
        SymbolDependencyRow {
            target_key: Some(
                Sha256::digest(if version == 1 { b"old" } else { b"new" })[..8].to_vec(),
            ),
            source_file: Some("Shared.java".into()),
        }
        .into_row("library"),
    ];
    for location in ["first", "second"] {
        rows.push(
            AnnotationMetaRow {
                annotation_fqn: Some("p.Observed".into()),
                meta_annotation_fqn: Some(
                    if version == 1 {
                        "p.Marker"
                    } else {
                        "p.NewMarker"
                    }
                    .into(),
                ),
                raw_name: Some("Marker".into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                mechanism: Some("java.annotation.meta".into()),
                resolution_status: Some(if version == 1 { "exact" } else { "heuristic" }.into()),
                source_file: Some("Shared.java".into()),
                source_location: Some(format!("{id}:{version}:{location}")),
                producer_id: Some("mock".into()),
            }
            .into_row("library"),
        );
    }
    if global {
        rows.push(
            AnnotationMetaRow {
                annotation_fqn: Some("p.Global".into()),
                meta_annotation_fqn: Some("p.Marker".into()),
                raw_name: Some("Marker".into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                mechanism: Some("java.annotation.meta".into()),
                resolution_status: Some("exact".into()),
                source_file: None,
                source_location: None,
                producer_id: Some("mock".into()),
            }
            .into_row(""),
        );
    }
    rows.into_iter().map(|row| Ok(origin.record(row))).collect()
}
fn counts(db: &Connection) -> Result<Vec<i64>> {
    [
        "graph_file_cache",
        "annotation_meta_owned",
        "graph_file_dependencies",
        "graph_symbol_dependencies",
    ]
    .into_iter()
    .map(|t| Ok(db.query_row(&format!("SELECT count(*) FROM {t}"), [], |r| r.get(0))?))
    .collect()
}

#[test]
fn auxiliary_snapshots_refresh_one_unit_preserve_reuse_and_match_full_without_semantic_nodes()
-> Result<()> {
    let (_directory, store, mut revision, _) = units_fixture(&["left", "right"])?;
    let source = revision.manifest.files[0].clone();
    let mut auxiliary = vec![];
    for (path, world) in [
        ("Shared.java", "library"),
        ("Target.java", "library"),
        ("Target.java", "test-library"),
        ("Other.java", "library"),
    ] {
        auxiliary.push(SourceUnit {
            path: path.into(),
            world: world.into(),
            auxiliary: true,
            ..source.clone()
        });
    }
    revision.manifest.files.extend(auxiliary.clone());
    for unit in &mut revision.unit_ownership.as_mut().unwrap().units {
        unit.inputs.extend(auxiliary.clone());
    }
    let first_rows = observations("left", 1, true, &source.hash)
        .into_iter()
        .chain(observations("right", 1, true, &source.hash));
    let first = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            revision.clone(),
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Full),
            ],
            first_rows,
            "",
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "aux-initial",
    )?;
    let first_reader = store.read(&first.id)?;
    assert_eq!(counts(&first_reader.connection)?, [2, 4, 4, 2]);
    assert_eq!(
        first_reader
            .connection
            .query_row("SELECT count(*) FROM file_dependencies", [], |r| r
                .get::<_, i64>(0))?,
        2
    );
    let changed = b"class LeftChanged {}".to_vec();
    let changed_hash = hash(&changed);
    store.put_blobs(&BTreeMap::from([(changed_hash.clone(), changed.clone())]))?;
    let own = revision
        .manifest
        .files
        .iter_mut()
        .find(|s| s.path == "left.java")
        .unwrap();
    own.hash = changed_hash;
    own.size = changed.len() as u64;
    let left = &mut revision.unit_ownership.as_mut().unwrap().units[0];
    left.sources = vec![own.clone()];
    left.inputs[0] = own.clone();
    left.input_digest = hash("left-updated");
    let mut delta = update("left", UnitUpdateKind::Delta);
    delta.replacements.push(Owner {
        world: "main".into(),
        producer: "mock".into(),
        kind: "source".into(),
        id: "left.java".into(),
    });
    let mixed = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            revision.clone(),
            vec![delta, update("right", UnitUpdateKind::Reuse)],
            observations("left", 2, false, &source.hash).into_iter(),
            &first.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "aux-delta-and-reuse",
    )?;
    let mixed_reader = store.read(&mixed.id)?;
    assert_eq!(counts(&mixed_reader.connection)?, [2, 3, 3, 2]);
    let locations = mixed_reader.connection.prepare("SELECT source_location FROM annotation_meta_owned WHERE source_file='Shared.java' ORDER BY source_location")?
        .query_map([], |r| r.get::<_, String>(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
    assert_eq!(locations, ["left:2:first", "right:1:first"]);
    let full_header = AnalysisHeader {
        mode: AnalysisMode::Full,
        baseline: mixed.id.clone(),
        manifest: revision.manifest.id(),
        profile: revision.profile.clone(),
        producer: revision.producer.clone(),
        replacements: vec![],
    };
    let full_rows = observations("left", 2, false, &source.hash)
        .into_iter()
        .chain(observations("right", 1, true, &source.hash));
    let full = store.publish_staged(
        store.stage_owned_graph_records_with_context(
            revision.clone(),
            &full_header,
            full_rows,
            &mixed.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "aux-full-control",
    )?;
    assert_eq!(
        canonical(&mixed_reader.connection)?,
        canonical(&store.read(&full.id)?.connection)?
    );
    revision.unit_ownership.as_mut().unwrap().units.remove(0);
    revision.manifest.files.retain(|s| s.path != "left.java");
    let removed = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            revision,
            vec![
                update("left", UnitUpdateKind::Removed),
                update("right", UnitUpdateKind::Reuse),
            ],
            std::iter::empty(),
            &full.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "aux-remove-left",
    )?;
    assert_eq!(counts(&store.read(&removed.id)?.connection)?, [1, 2, 2, 1]);
    assert_eq!(counts(&first_reader.connection)?, [2, 4, 4, 2]);
    assert_eq!(counts(&mixed_reader.connection)?, [2, 3, 3, 2]);
    Ok(())
}

#[test]
fn regenerating_scoped_dependencies_preserves_explicit_observations_and_reuse() -> Result<()> {
    let (_directory, store, mut revision, template) = units_fixture(&["left", "right"])?;
    let mut right_input = revision.unit_ownership.as_ref().unwrap().units[1].sources[0].clone();
    right_input.auxiliary = true;
    revision.unit_ownership.as_mut().unwrap().units[0]
        .inputs
        .push(right_input);
    let node = |id: &str, fqn: &str| {
        let mut row = template[0].clone();
        for (column, value) in [
            (0, id.to_owned()),
            (1, id.to_owned()),
            (9, fqn.to_owned()),
            (12, format!("{id}.java")),
        ] {
            row.cells[column].value = Some(codeana_contracts::graph::cell::Value::Text(value));
        }
        Origin::Unit(Arc::new(id.into())).record(row)
    };
    let meta = |id: &str, target: &str| {
        Origin::Unit(Arc::new(id.into())).record(
            AnnotationMetaRow {
                annotation_fqn: Some(format!("p.{id}Observed")),
                meta_annotation_fqn: Some(target.into()),
                raw_name: Some("Marker".into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                mechanism: Some("java.annotation.meta".into()),
                resolution_status: Some(if id == "left" { "exact" } else { "unresolved" }.into()),
                source_file: Some(format!("{id}.java")),
                source_location: None,
                producer_id: Some("mock".into()),
            }
            .into_row("main"),
        )
    };
    let left = Origin::Unit(Arc::new("left".into()));
    let mut rows = vec![
        Ok(node("left", "p.Owner")),
        Ok(node("right", "p.Target")),
        Ok(meta("left", "p.Target")),
        Ok(meta("right", "p.Owner")),
    ];
    rows.push(Ok(left.record(
        FileDependencyRow {
            source_provider_id: Some("java-core".into()),
            source_file: Some("left.java".into()),
            depends_on_provider_id: Some("java-core".into()),
            depends_on_file: Some("right.java".into()),
        }
        .into_row("main"),
    )));
    rows.push(Ok(left.record(
        SymbolDependencyRow {
            target_key: Some(Sha256::digest(b"right")[..8].to_vec()),
            source_file: Some("left.java".into()),
        }
        .into_row("main"),
    )));
    rows.push(Ok(left.record(
        EdgeRow {
            source_ref: Some("left".into()),
            target_ref: Some("right".into()),
            source_is_key: Some(1),
            target_is_key: Some(1),
            resolved_source: Some("left".into()),
            resolved_target: Some("right".into()),
            relation: Some("CALLS".into()),
            language: Some("java".into()),
            provider_id: Some("java-core".into()),
            producer_id: Some("mock".into()),
            call_kind: Some("constructor".into()),
            confidence: Some("EXTRACTED".into()),
            is_external: Some(0),
            source_file: Some("left.java".into()),
            source_module: Some("app".into()),
            source_scope: Some("MAIN".into()),
            begin_line: Some(1),
            begin_column: Some(1),
            end_line: Some(1),
            end_column: Some(4),
            ..Default::default()
        }
        .into_row("main"),
    )));
    let first = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            revision.clone(),
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Full),
            ],
            rows.into_iter(),
            "",
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "deps-explicit-and-derived",
    )?;
    let reader = store.read(&first.id)?;
    for table in ["graph_file_dependencies", "graph_symbol_dependencies"] {
        let flags = reader
            .connection
            .prepare(&format!(
                "SELECT host_derived FROM {table} ORDER BY host_derived"
            ))?
            .query_map([], |r| r.get::<_, i64>(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        assert_eq!(flags, [0, 1], "{table}");
    }
    let dependencies = reader
        .connection
        .prepare("SELECT source_file,depends_on_file FROM file_dependencies ORDER BY source_file")?
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    assert_eq!(dependencies, [("left.java".into(), "right.java".into())]);
    let reused = store.publish_staged(
        store.stage_reused_graph_with_context(
            revision.clone(),
            &first.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "deps-reused",
    )?;
    assert_eq!(
        canonical(&reader.connection)?,
        canonical(&store.read(&reused.id)?.connection)?
    );
    let mixed = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            revision.clone(),
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Reuse),
            ],
            std::iter::empty(),
            &reused.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "deps-clear-left",
    )?;
    let mixed_reader = store.read(&mixed.id)?;
    assert_eq!(counts(&mixed_reader.connection)?, [0, 1, 0, 0]);
    let header = AnalysisHeader {
        mode: AnalysisMode::Full,
        baseline: mixed.id.clone(),
        manifest: revision.manifest.id(),
        profile: revision.profile.clone(),
        producer: revision.producer.clone(),
        replacements: vec![],
    };
    let full = store.publish_staged(
        store.stage_owned_graph_records_with_context(
            revision,
            &header,
            [Ok(node("right", "p.Target")), Ok(meta("right", "p.Owner"))].into_iter(),
            &mixed.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "deps-empty-full-control",
    )?;
    assert_eq!(
        canonical(&mixed_reader.connection)?,
        canonical(&store.read(&full.id)?.connection)?
    );
    assert_eq!(
        reader
            .connection
            .query_row("SELECT count(*) FROM call_sites", [], |r| r
                .get::<_, i64>(0))?,
        1
    );
    Ok(())
}

#[test]
fn explicit_file_dependencies_cannot_select_a_peer_target_outside_their_bindings() -> Result<()> {
    let (_directory, store, revision, _) = units_fixture(&["left", "right"])?;
    let row = Origin::Unit(Arc::new("left".into())).record(
        FileDependencyRow {
            source_provider_id: Some("java-core".into()),
            source_file: Some("left.java".into()),
            depends_on_provider_id: Some("java-core".into()),
            depends_on_file: Some("right.java".into()),
        }
        .into_row("main"),
    );
    let error = store
        .stage_mixed_graph_records_with_context(
            revision,
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Full),
            ],
            std::iter::once(Ok(row)),
            "",
            || Ok(()),
            |_, _| Ok(()),
            None,
        )
        .err()
        .context("file dependency borrowed undeclared peer")?;
    assert_eq!(
        error
            .downcast_ref::<DomainError>()
            .context(format!("{error:#}"))?
            .code,
        ErrorCode::AnalysisUnitScope
    );
    assert_eq!(store.head("t")?, "");
    Ok(())
}

fn annotation_context_fixture(
    global: bool,
    exact: bool,
) -> Result<(tempfile::TempDir, Store, Revision, Vec<GraphRecord>)> {
    let (directory, store, mut revision, template) = units_fixture(&["left", "right"])?;
    let shared = SourceUnit {
        path: "Shared.java".into(),
        world: "library".into(),
        auxiliary: true,
        ..revision.manifest.files[0].clone()
    };
    revision.manifest.files.push(shared.clone());
    for unit in &mut revision.unit_ownership.as_mut().unwrap().units {
        unit.inputs.push(shared.clone());
    }
    let mut rows = vec![];
    for (id, target) in [("left", "a.Left"), ("right", "b.Right")] {
        let origin = Origin::Unit(Arc::new(id.into()));
        let owner = if global { "shared" } else { id };
        let mut node = template[0].clone();
        for (column, value) in [
            (0, owner.to_owned()),
            (1, owner.to_owned()),
            (
                12,
                if global {
                    String::new()
                } else {
                    format!("{id}.java")
                },
            ),
        ] {
            node.cells[column].value = Some(codeana_contracts::graph::cell::Value::Text(value));
        }
        if global {
            node.world.clear();
        }
        rows.push(Ok(origin.record(node)));
        rows.push(Ok(origin.record(
            AnnotationRow {
                node_ref: Some(owner.into()),
                node_is_key: Some(1),
                resolved_node: Some(owner.into()),
                annotation_fqn: Some("p.Shared".into()),
                raw_name: Some("Shared".into()),
                attributes: Some("{}".into()),
                target_kind: Some("type".into()),
                target_path: Some("".into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                mechanism: Some("java.declaration_annotation".into()),
                resolution_status: Some(if exact { "exact" } else { "heuristic" }.into()),
                source_file: (!global).then(|| format!("{id}.java")),
                source_module: Some("app".into()),
                source_scope: Some("MAIN".into()),
                producer_id: Some("mock".into()),
                ..Default::default()
            }
            .into_row(if global { "" } else { "main" }),
        )));
        rows.push(Ok(origin.record(
            AnnotationMetaRow {
                annotation_fqn: Some("p.Shared".into()),
                meta_annotation_fqn: Some(target.into()),
                raw_name: Some(target.into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                mechanism: Some("java.annotation.meta".into()),
                resolution_status: Some("exact".into()),
                source_file: Some("Shared.java".into()),
                source_location: None,
                producer_id: Some("mock".into()),
            }
            .into_row("library"),
        )));
    }
    let rows = rows.into_iter().collect::<Result<Vec<_>>>()?;
    let published = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            revision,
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Full),
            ],
            rows.iter()
                .map(|record| Ok(record.origin.record(record.row.clone()))),
            "",
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "meta-contexts",
    )?;
    Ok((directory, store, published, rows))
}

#[test]
fn meta_annotation_queries_keep_shared_input_definitions_in_the_direct_occurrences_unit()
-> Result<()> {
    let (_directory, store, published, _) = annotation_context_fixture(false, true)?;
    let reader = store.read(&published.id)?;
    for (id, target) in [("left", "a.Left"), ("right", "b.Right")] {
        let request = Request {
            operation: Operation::Annotations,
            selector: id.into(),
            meta: true,
            ..Default::default()
        };
        let result =
            codeana_query::run(&reader, &request, &|_| anyhow::bail!("no source expected"))?;
        let mut paths = result
            .records
            .iter()
            .map(|r| {
                r.wire().unwrap()["attributes"]["via"]
                    .as_str()
                    .unwrap()
                    .to_owned()
            })
            .collect::<Vec<_>>();
        paths.sort();
        assert_eq!(paths, ["p.Shared".to_owned(), format!("p.Shared>{target}")]);
    }
    Ok(())
}

#[test]
fn meta_annotation_cache_is_local_to_each_occurrence_on_a_shared_global_node() -> Result<()> {
    let (_directory, store, published, _) = annotation_context_fixture(true, true)?;
    let reader = store.read(&published.id)?;
    let request = Request {
        operation: Operation::Annotations,
        selector: "shared".into(),
        meta: true,
        ..Default::default()
    };
    let result = codeana_query::run(&reader, &request, &|_| anyhow::bail!("no source expected"))?;
    let mut paths = result
        .records
        .iter()
        .map(|r| {
            r.wire().unwrap()["attributes"]["via"]
                .as_str()
                .unwrap()
                .to_owned()
        })
        .collect::<Vec<_>>();
    paths.sort();
    assert_eq!(
        paths,
        [
            "p.Shared",
            "p.Shared",
            "p.Shared>a.Left",
            "p.Shared>b.Right"
        ]
    );
    Ok(())
}

#[test]
fn meta_annotation_unknown_direct_type_does_not_become_an_exact_name_binding() -> Result<()> {
    let (_directory, store, published, _) = annotation_context_fixture(false, false)?;
    let reader = store.read(&published.id)?;
    let request = Request {
        operation: Operation::Annotations,
        selector: "left".into(),
        meta: true,
        ..Default::default()
    };
    let result = codeana_query::run(&reader, &request, &|_| anyhow::bail!("no source expected"))?;
    assert_eq!(result.records.len(), 1);
    assert!(!result.evidence.semantic);
    assert_eq!(
        result.records[0].wire().unwrap()["evidence_complete"],
        false
    );
    Ok(())
}

#[test]
fn meta_annotation_lookup_rejects_forged_occurrence_identity_and_uses_the_query_budget()
-> Result<()> {
    let (_directory, store, published, _) = annotation_context_fixture(false, true)?;
    let reader = store.read(&published.id)?;
    let direct = reader
        .select(&FactFilter {
            kinds: vec![FactKind::Facet],
            parent: Some("left".into()),
            relation: Some("java.annotation".into()),
            ..Default::default()
        })?
        .remove(0);
    for field in ["hash", "ordinal", "parent", "source", "producer", "world"] {
        let mut forged = direct.clone();
        match field {
            "hash" => forged.id = "annotation:+f+f+f+f+f+f+f+f+f+f+f+f+f+f+f+f:0".into(),
            "ordinal" => {
                forged.id = format!("{}:00", direct.id.as_str().rsplit_once(':').unwrap().0).into()
            }
            "parent" => forged.parent = "right".into(),
            "source" => forged.source = "right.java".into(),
            "producer" => forged.owner.producer = "peer".into(),
            "world" => forged.owner.world = "foreign-world".into(),
            _ => unreachable!(),
        }
        let error = reader
            .annotation_meta_edges(&["p.Shared".into()], &forged)
            .unwrap_err();
        assert_eq!(error.code, ErrorCode::GraphColumnType, "{field}");
    }
    let budget = ResourceBudget::new(ResourceLimits {
        max_work: 100,
        max_payload_bytes: 1,
    });
    let limited = store.read(&published.id)?.with_budget(budget.clone());
    assert!(
        limited
            .annotation_meta_edges(&["p.Shared".into()], &direct)
            .is_err()
    );
    assert_eq!(budget.usage().exhausted.unwrap().resource, "payload_bytes");
    Ok(())
}

#[test]
fn mixed_reuse_preserves_global_occurrence_ids_and_their_meta_paths() -> Result<()> {
    let (_directory, store, first, rows) = annotation_context_fixture(true, true)?;
    let request = Request {
        operation: Operation::Annotations,
        selector: "shared".into(),
        meta: true,
        ..Default::default()
    };
    let snapshot = |revision: &Revision| -> Result<BTreeMap<String, String>> {
        let reader = store.read(&revision.id)?;
        let result =
            codeana_query::run(&reader, &request, &|_| anyhow::bail!("no source expected"))?;
        result
            .records
            .iter()
            .map(|record| {
                let wire = record.wire()?;
                Ok((
                    wire["id"].as_str().context("fact id")?.to_owned(),
                    wire["attributes"]["via"]
                        .as_str()
                        .context("meta path")?
                        .to_owned(),
                ))
            })
            .collect()
    };
    let expected = snapshot(&first)?;
    let mixed = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            first.clone(),
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Reuse),
            ],
            rows.iter()
                .filter(
                    |record| matches!(&record.origin, Origin::Unit(id) if id.as_str() == "left"),
                )
                .map(|record| Ok(record.origin.record(record.row.clone()))),
            &first.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "mixed-global-occurrences",
    )?;
    assert_eq!(
        snapshot(&mixed)?,
        expected,
        "unchanged unit inputs must retain occurrence/meta identities"
    );
    let full = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            mixed.clone(),
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Full),
            ],
            rows.iter()
                .map(|record| Ok(record.origin.record(record.row.clone()))),
            &mixed.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "full-global-occurrences",
    )?;
    assert_eq!(snapshot(&full)?, expected);
    assert_eq!(
        snapshot(&first)?,
        expected,
        "historical reader remains stable"
    );
    Ok(())
}
