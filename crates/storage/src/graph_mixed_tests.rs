//! Real candidate publication with five independent unit update modes.
use super::test_support::{canonical, units_fixture as fixture};
use super::*;
use codeana_contracts::graph::{Row, cell};
use codeana_contracts::graph_rows::{
    CoverageRow, DiagnosticRow, GraphRow, ProjectMetaRow, ProviderRow,
};

fn node(template: &Row, id: &str, source: &str, version: u8) -> Row {
    let mut row = template.clone();
    for (column, value) in [
        (0, id.to_owned()),
        (1, id.to_owned()),
        (7, format!("v{version}")),
        (12, source.to_owned()),
    ] {
        row.cells[column].value = Some(cell::Value::Text(value));
    }
    row.world = if source.is_empty() { "" } else { "main" }.into();
    row
}
fn records(template: &Row, revision: &Revision, units: &[(&str, u8)]) -> Vec<Result<GraphRecord>> {
    let mut rows = vec![];
    for &(id, version) in units {
        let origin = Origin::Unit(Arc::new(id.into()));
        rows.push(Ok(origin.record(
            ProviderRow {
                provider_id: Some("java-core".into()),
                language: Some("java".into()),
                provider_version: Some("v1".into()),
                operations: Some("[]".into()),
                limitations: Some("{}".into()),
                profile_hash: Some(hash(format!("{id}:{version}"))),
            }
            .into_row(""),
        )));
        let unit = revision
            .unit_ownership
            .as_ref()
            .unwrap()
            .units
            .iter()
            .find(|u| u.unit == id)
            .unwrap();
        rows.push(Ok(origin.record(node(
            template,
            id,
            &unit.sources[0].path,
            version,
        ))));
        rows.push(Ok(origin.record(node(
            template,
            &format!("{id}-global"),
            "",
            version,
        ))));
        rows.push(Ok(origin.record(
            ProjectMetaRow {
                key: Some(format!("unit.{id}")),
                value: Some(format!("v{version}")),
            }
            .into_row(""),
        )));
        rows.push(Ok(origin.record(
            DiagnosticRow {
                severity: Some("warning".into()),
                code: Some("GLOBAL".into()),
                phase: Some("analysis".into()),
                module: Some("app".into()),
                scope: Some("MAIN".into()),
                language: Some("java".into()),
                provider_id: Some("java-core".into()),
                occurrence_count: Some(1),
                sample: Some(format!("{id}:{version}")),
                ..Default::default()
            }
            .into_row(""),
        )));
        rows.push(Ok(origin.record(
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
    }
    rows
}
fn update(id: &str, kind: UnitUpdateKind) -> UnitUpdate {
    UnitUpdate {
        unit: id.into(),
        kind,
        replacements: vec![],
    }
}
fn counts(db: &Connection) -> Result<Vec<i64>> {
    ["nodes", "index_diagnostics", "analysis_coverage"]
        .into_iter()
        .map(|table| Ok(db.query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0))?))
        .collect()
}
#[test]
fn five_unit_modes_share_one_candidate_match_full_and_keep_history_on_failure() -> Result<()> {
    let ids = ["delta", "full", "gone", "keep", "offline"];
    let (_directory, store, before, template) = fixture(&ids)?;
    let host = AnalysisContext::new(AnalysisLimits::default(), None).unwrap();
    let initial_context = GraphStagingContext {
        hosts: BTreeMap::from([(AnalysisBackend::Java, host.clone())]),
        source: Arc::new(|_, _| {
            Err(DomainError::new(
                ErrorCode::GraphSourceNotCaptured,
                "unexpected source read",
            ))
        }),
    };
    let first = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            before.clone(),
            ids.into_iter()
                .map(|id| update(id, UnitUpdateKind::Full))
                .collect(),
            records(&template[0], &before, &ids.map(|id| (id, 1))).into_iter(),
            "",
            || Ok(()),
            |_, _| Ok(()),
            Some(&initial_context),
        )?,
        "mixed-first",
    )?;
    let first_reader = store.read(&first.id)?;
    assert_eq!(counts(&first_reader.connection)?, [10, 5, 5]);
    assert!(host.usage().work > 0 && host.usage().payload_bytes > 0);

    let mut after = before.clone();
    after.profile = hash("new-project-profile");
    after
        .manifest
        .files
        .retain(|s| !matches!(s.path.as_str(), "gone.java" | "offline.java"));
    let bytes = b"class Changed {}".to_vec();
    let changed = hash(&bytes);
    store.put_blobs(&BTreeMap::from([(changed.clone(), bytes.clone())]))?;
    for source in after
        .manifest
        .files
        .iter_mut()
        .filter(|s| s.path != "keep.java")
    {
        source.hash = changed.clone();
        source.size = bytes.len() as u64;
    }
    let ledger = after.unit_ownership.as_mut().unwrap();
    ledger.units.retain(|u| u.unit != "gone");
    for unit in &mut ledger.units {
        match unit.unit.as_str() {
            "offline" => {
                unit.availability = UnitAvailability::FilesOnly;
                unit.sources.clear();
                unit.inputs.clear();
                unit.producer.clear();
                unit.semantic_profile.clear();
                unit.input_digest.clear();
            }
            "delta" | "full" => {
                unit.sources = after
                    .manifest
                    .files
                    .iter()
                    .filter(|s| s.path == format!("{}.java", unit.unit))
                    .cloned()
                    .collect();
                unit.inputs = unit.sources.clone();
                unit.input_digest = hash(format!("new:{}", unit.unit));
                if unit.unit == "full" {
                    unit.semantic_profile = hash("new-full-profile");
                }
            }
            _ => {}
        }
    }
    let mut updates = vec![
        update("delta", UnitUpdateKind::Delta),
        update("full", UnitUpdateKind::Full),
        update("gone", UnitUpdateKind::Removed),
        update("keep", UnitUpdateKind::Reuse),
        update("offline", UnitUpdateKind::FilesOnly),
    ];
    updates[0].replacements = vec![Owner {
        world: "main".into(),
        producer: "mock".into(),
        kind: "source".into(),
        id: "delta.java".into(),
    }];
    // Reuse never authorizes fresh rows, even when they are in the old scope.
    let failure = store
        .stage_mixed_graph_records_with_context(
            after.clone(),
            updates.clone(),
            records(&template[0], &after, &[("keep", 1)]).into_iter(),
            &first.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )
        .err()
        .context("reuse unit delivered fresh rows")?;
    assert_eq!(
        failure
            .downcast_ref::<DomainError>()
            .context(format!("{failure:#}"))?
            .code,
        ErrorCode::AnalysisUnitScope
    );
    assert_eq!(store.head("t")?, first.id);
    for delivered in [0, 6, 12] {
        let failed = records(&template[0], &after, &[("delta", 2), ("full", 2)])
            .into_iter()
            .take(delivered)
            .chain(std::iter::once(Err(anyhow::anyhow!(
                "injected delivery failure"
            ))));
        assert!(
            store
                .stage_mixed_graph_records_with_context(
                    after.clone(),
                    updates.clone(),
                    failed,
                    &first.id,
                    || Ok(()),
                    |_, _| Ok(()),
                    None
                )
                .is_err()
        );
        assert_eq!(store.head("t")?, first.id);
    }
    let limited = AnalysisContext::new(
        AnalysisLimits {
            max_work: 1,
            ..AnalysisLimits::default()
        },
        None,
    )
    .unwrap();
    let context = GraphStagingContext {
        hosts: BTreeMap::from([(AnalysisBackend::Java, limited.clone())]),
        source: Arc::new(|_, _| {
            Err(DomainError::new(
                ErrorCode::GraphSourceNotCaptured,
                "unexpected source read",
            ))
        }),
    };
    let failure = store
        .stage_mixed_graph_records_with_context(
            after.clone(),
            updates.clone(),
            std::iter::empty(),
            &first.id,
            || Ok(()),
            |_, _| Ok(()),
            Some(&context),
        )
        .err()
        .context("merge budget was ignored")?;
    assert_eq!(
        failure
            .downcast_ref::<DomainError>()
            .context(format!("{failure:#}"))?
            .code,
        DomainError::analysis(limited.usage().failure.unwrap()).code
    );
    assert_eq!(store.head("t")?, first.id);
    let mixed = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            after.clone(),
            updates,
            records(&template[0], &after, &[("delta", 2), ("full", 2)]).into_iter(),
            &first.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "mixed-five-modes",
    )?;
    let mixed_reader = store.read(&mixed.id)?;
    assert_eq!(counts(&mixed_reader.connection)?, [6, 3, 3]);
    assert_eq!(
        mixed_reader
            .connection
            .query_row("SELECT count(*) FROM unit_providers", [], |r| r
                .get::<_, i64>(0))?,
        3
    );
    assert_eq!(
        mixed_reader.connection.query_row(
            "SELECT value FROM project_meta WHERE key='unit.keep'",
            [],
            |r| r.get::<_, String>(0)
        )?,
        "v1"
    );
    assert_eq!(
        mixed_reader.connection.query_row(
            "SELECT count(*) FROM project_meta WHERE key IN('unit.gone','unit.offline')",
            [],
            |r| r.get::<_, i64>(0)
        )?,
        0
    );
    let full_header = AnalysisHeader {
        mode: AnalysisMode::Full,
        baseline: mixed.id.clone(),
        manifest: after.manifest.id(),
        profile: after.profile.clone(),
        producer: after.producer.clone(),
        replacements: vec![],
    };
    let full = store.publish_staged(
        store.stage_owned_graph_records_with_context(
            after.clone(),
            &full_header,
            records(
                &template[0],
                &after,
                &[("delta", 2), ("full", 2), ("keep", 1)],
            )
            .into_iter(),
            &mixed.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "mixed-full-control",
    )?;
    assert_eq!(
        canonical(&mixed_reader.connection)?,
        canonical(&store.read(&full.id)?.connection)?
    );
    let no_output = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            after.clone(),
            vec![
                update("delta", UnitUpdateKind::Reuse),
                update("full", UnitUpdateKind::Full),
                update("keep", UnitUpdateKind::Reuse),
                update("offline", UnitUpdateKind::FilesOnly),
            ],
            std::iter::empty(),
            &full.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "mixed-zero-output-full",
    )?;
    let no_output_reader = store.read(&no_output.id)?;
    assert_eq!(counts(&no_output_reader.connection)?, [4, 2, 2]);
    assert_eq!(
        no_output_reader
            .connection
            .query_row("SELECT count(*) FROM unit_providers", [], |r| r
                .get::<_, i64>(0))?,
        2
    );
    let mut empty = after;
    empty.manifest.files.clear();
    empty.unit_ownership = Some(UnitOwnershipLedger::default());
    let removed = full
        .unit_ownership
        .as_ref()
        .unwrap()
        .units
        .iter()
        .map(|u| update(&u.unit, UnitUpdateKind::Removed))
        .collect();
    let zero = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            empty,
            removed,
            std::iter::empty(),
            &no_output.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "mixed-remove-to-zero",
    )?;
    assert_eq!(counts(&store.read(&zero.id)?.connection)?, [0, 0, 0]);
    assert_eq!(counts(&first_reader.connection)?, [10, 5, 5]);
    assert_eq!(counts(&mixed_reader.connection)?, [6, 3, 3]);
    Ok(())
}

#[test]
fn delta_cannot_borrow_a_peer_full_replacement_through_auxiliary_metadata() -> Result<()> {
    let (_directory, store, mut revision, template) = fixture(&["left", "right"])?;
    let units = &mut revision.unit_ownership.as_mut().unwrap().units;
    let mut shared = units[1].sources[0].clone();
    shared.auxiliary = true;
    units[0].inputs.push(shared);
    let first = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            revision.clone(),
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Full),
            ],
            records(&template[0], &revision, &[("left", 1), ("right", 1)]).into_iter(),
            "",
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "auxiliary-range-first",
    )?;
    let mut delta = update("left", UnitUpdateKind::Delta);
    delta.replacements.push(Owner {
        world: "main".into(),
        producer: "mock".into(),
        kind: "source".into(),
        id: "left.java".into(),
    });
    let row = Origin::Unit(Arc::new("left".into())).record(
        DiagnosticRow {
            severity: Some("warning".into()),
            code: Some("BORROW".into()),
            phase: Some("analysis".into()),
            source_file: Some("right.java".into()),
            module: Some("app".into()),
            scope: Some("MAIN".into()),
            language: Some("java".into()),
            provider_id: Some("java-core".into()),
            occurrence_count: Some(1),
            ..Default::default()
        }
        .into_row("main"),
    );
    let failure = store
        .stage_mixed_graph_records_with_context(
            revision,
            vec![delta, update("right", UnitUpdateKind::Full)],
            std::iter::once(Ok(row)),
            &first.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )
        .err()
        .context("delta borrowed a peer range")?;
    assert_eq!(
        failure
            .downcast_ref::<DomainError>()
            .context(format!("{failure:#}"))?
            .code,
        ErrorCode::GraphSourceNotReplaced
    );
    assert_eq!(store.head("t")?, first.id);
    assert_eq!(counts(&store.read(&first.id)?.connection)?, [4, 2, 2]);
    Ok(())
}

#[test]
fn shared_global_membership_survives_a_peer_snapshot_and_rejects_incompatible_reuse() -> Result<()>
{
    let (_directory, store, revision, template) = fixture(&["left", "right"])?;
    let shared = |id: &str, version| {
        Ok(Origin::Unit(Arc::new(id.into())).record(node(
            &template[0],
            "shared-library",
            "",
            version,
        )))
    };
    let mut initial = records(&template[0], &revision, &[("left", 1), ("right", 1)]);
    initial.extend([shared("left", 1), shared("right", 1)]);
    let first = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            revision.clone(),
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Full),
            ],
            initial.into_iter(),
            "",
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "shared-members-first",
    )?;
    let first_reader = store.read(&first.id)?;
    let owners = |db: &Connection| -> Result<Vec<String>> {
        Ok(db.prepare("SELECT c.unit_id FROM node_units m JOIN unit_catalog c USING(unit_pk) JOIN graph_nodes n USING(node_pk) WHERE n.id='shared-library' ORDER BY c.unit_id")?
            .query_map([], |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?)
    };
    assert_eq!(owners(&first_reader.connection)?, ["left", "right"]);
    let mixed = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            revision.clone(),
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Reuse),
            ],
            std::iter::empty(),
            &first.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "shared-members-retained",
    )?;
    let mixed_reader = store.read(&mixed.id)?;
    assert_eq!(owners(&mixed_reader.connection)?, ["right"]);
    assert_eq!(counts(&mixed_reader.connection)?, [3, 1, 1]);
    let failure = store
        .stage_mixed_graph_records_with_context(
            revision.clone(),
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Reuse),
            ],
            std::iter::once(shared("left", 2)),
            &mixed.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )
        .err()
        .context("shared library changed underneath reused peer")?;
    assert_eq!(
        failure
            .downcast_ref::<DomainError>()
            .context(format!("{failure:#}"))?
            .code,
        ErrorCode::GraphWorldOwnershipConflict
    );
    assert_eq!(store.head("t")?, mixed.id);
    let mut fresh = records(&template[0], &revision, &[("left", 2), ("right", 2)]);
    fresh.extend([shared("left", 2), shared("right", 2)]);
    let full = store.publish_staged(
        store.stage_mixed_graph_records_with_context(
            revision,
            vec![
                update("left", UnitUpdateKind::Full),
                update("right", UnitUpdateKind::Full),
            ],
            fresh.into_iter(),
            &mixed.id,
            || Ok(()),
            |_, _| Ok(()),
            None,
        )?,
        "shared-members-refreshed",
    )?;
    assert_eq!(
        owners(&store.read(&full.id)?.connection)?,
        ["left", "right"]
    );
    assert_eq!(counts(&store.read(&full.id)?.connection)?, [5, 2, 2]);
    assert_eq!(owners(&first_reader.connection)?, ["left", "right"]);
    assert_eq!(owners(&mixed_reader.connection)?, ["right"]);
    Ok(())
}
