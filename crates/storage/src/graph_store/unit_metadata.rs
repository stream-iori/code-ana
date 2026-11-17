//! Persist observations by host-assigned unit; public summaries are deliberate aggregates.
use super::*;
use codeana_model::{
    AnalysisUnitId, DomainError, ErrorCode, GraphStagingContext, Revision, UnitAvailability,
};
use std::collections::{BTreeMap, BTreeSet};

pub(crate) fn setup<'a>(
    db: &Connection,
    stage: &Connection,
    revision: &'a Revision,
    context: Option<&GraphStagingContext>,
    active: bool,
) -> Result<BTreeMap<&'a AnalysisUnitId, i64>> {
    let mut units = BTreeMap::new();
    if let Some(ledger) = &revision.unit_ownership {
        for unit in &ledger.units {
            if let Some(context) = context {
                let host = context.hosts.get(&unit.backend).context(DomainError::new(
                    ErrorCode::AnalysisUnitScope,
                    "unit owner has no host account",
                ))?;
                host.charge(2, 0, 2048 + 3 * unit.unit.len() as u64)
                    .map_err(DomainError::analysis)?;
            }
            db.execute(
                "INSERT OR IGNORE INTO unit_catalog(unit_id,backend) VALUES(?,?)",
                params![unit.unit.as_str(), unit.backend.as_str()],
            )?;
            let (pk, backend): (i64, String) = db.query_row(
                "SELECT unit_pk,backend FROM unit_catalog WHERE unit_id=?",
                [unit.unit.as_str()],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )?;
            ensure!(
                backend == unit.backend.as_str(),
                DomainError::new(ErrorCode::AnalysisUnitScope, "unit backend changed")
            );
            units.insert(&unit.unit, pk);
            if active && unit.availability == UnitAvailability::Ready {
                stage.execute("INSERT INTO stage_active_units VALUES(?)", [pk])?;
            }
        }
    }
    if active {
        stage.execute("INSERT INTO stage_active_units VALUES(0)", [])?;
    }
    let mut statement =
        db.prepare("SELECT unit_pk,unit_id,backend FROM unit_catalog ORDER BY unit_pk")?;
    let mut rows = statement.query([])?;
    while let Some(row) = rows.next()? {
        stage.execute(
            "INSERT OR REPLACE INTO unit_catalog VALUES(?,?,?)",
            params![
                row.get::<_, i64>(0)?,
                row.get_ref(1)?.as_str()?,
                row.get_ref(2)?.as_str()?
            ],
        )?;
    }
    Ok(units)
}

/// Inactive units remain untouched. Active sourceful observations are seeded
/// before this deletion; active unit-global observations require fresh delivery.
pub(crate) fn replace_active(db: &Connection) -> Result<()> {
    db.execute_batch("DELETE FROM project_meta WHERE key IN(SELECT key FROM unit_project_meta);
      DELETE FROM unit_project_meta WHERE unit_pk IN(SELECT unit_pk FROM staged.stage_active_units);
      DELETE FROM unit_providers WHERE unit_pk IN(SELECT unit_pk FROM staged.stage_active_units);
      DELETE FROM graph_index_diagnostics WHERE unit_pk IN(SELECT unit_pk FROM staged.stage_active_units);
      DELETE FROM graph_analysis_coverage WHERE unit_pk IN(SELECT unit_pk FROM staged.stage_active_units);")?;
    Ok(())
}

pub(crate) fn promote(db: &Connection, context: Option<&GraphStagingContext>) -> Result<()> {
    db.execute_batch("INSERT OR IGNORE INTO source_files(path)
      SELECT source_file FROM staged.stage_diagnostics WHERE source_file IS NOT NULL
      UNION SELECT source_file FROM staged.stage_coverage WHERE source_file IS NOT NULL;
      INSERT INTO graph_index_diagnostics(severity,code,phase,source_file,module,scope,symbol,language,provider_id,provider_reason,reason_code,sample_details,occurrence_count,sample,world,unit_pk)
      SELECT severity,code,phase,(SELECT file_pk FROM source_files WHERE path=s.source_file),module,scope,symbol,language,provider_id,provider_reason,reason_code,sample_details,occurrence_count,sample,world,unit_pk FROM staged.stage_diagnostics s;
      INSERT INTO graph_analysis_coverage(source_file,module,scope,language,provider_id,capability,status,occurrences,groups_count,codes,code_counts,details_truncated,world,unit_pk)
      SELECT (SELECT file_pk FROM source_files WHERE path=s.source_file),module,scope,language,provider_id,capability,status,occurrences,groups_count,codes,code_counts,details_truncated,world,unit_pk FROM staged.stage_coverage s WHERE 1
      ON CONFLICT(unit_pk,provider_id,source_file,module,scope,capability,world) DO UPDATE SET
        language=excluded.language,status=excluded.status,occurrences=excluded.occurrences,groups_count=excluded.groups_count,codes=excluded.codes,code_counts=excluded.code_counts,details_truncated=excluded.details_truncated;
      INSERT OR REPLACE INTO unit_providers(unit_pk,provider_id,language,provider_version,operations,limitations,profile_hash)
      SELECT unit_pk,provider_id,language,provider_version,operations,limitations,profile_hash FROM staged.stage_providers;
      INSERT OR REPLACE INTO unit_project_meta(unit_pk,key,value) SELECT unit_pk,key,value FROM staged.stage_meta;")?;
    summarize_meta(db)?;
    summarize_providers(db, context)
}

fn summarize_meta(db: &Connection) -> Result<()> {
    ensure!(!db.prepare("SELECT key FROM unit_project_meta WHERE key LIKE 'codeana.context.%' GROUP BY key HAVING count(DISTINCT value)+(count(*)>count(value))>1 LIMIT 1")?.exists([])?,
        DomainError::new(ErrorCode::GraphWorldOwnershipConflict,"shared compilation world has different context identities"));
    db.execute_batch("INSERT OR REPLACE INTO project_meta(key,value)
      SELECT key, CASE
        WHEN key='analysis_mode' AND max(value='source_only') THEN 'source_only'
        WHEN key='classpath_status' AND max(value='unavailable') THEN 'unavailable'
        WHEN key='dependency_mode' AND max(value='source_only_fallback') THEN 'source_only_fallback'
        WHEN key='dependency_mode' AND max(value LIKE 'source_only%') THEN min(CASE WHEN value LIKE 'source_only%' THEN value END)
        WHEN count(DISTINCT value)+(count(*)>count(value))<=1 THEN min(value)
        ELSE json_group_array(value) END
      FROM (SELECT m.key,m.value FROM unit_project_meta m JOIN unit_catalog u USING(unit_pk) ORDER BY m.key,u.unit_id) GROUP BY key;")?;
    Ok(())
}

#[derive(PartialEq, Eq)]
struct Provider {
    unit: String,
    language: String,
    version: String,
    operations: String,
    limitations: String,
    profile: String,
}
fn summarize_providers(db: &Connection, context: Option<&GraphStagingContext>) -> Result<()> {
    // One provider group at a time. The raw per-unit values remain authoritative.
    let mut statement = db.prepare("SELECT p.provider_id,u.unit_id,p.language,p.provider_version,p.operations,p.limitations,p.profile_hash,u.backend FROM unit_providers p JOIN unit_catalog u USING(unit_pk) ORDER BY p.provider_id,u.unit_id")?;
    let mut rows = statement.query([])?;
    let mut key = String::new();
    let mut providers = Vec::new();
    db.execute("DELETE FROM index_providers", [])?;
    while let Some(row) = rows.next()? {
        if let Some(context) = context {
            let backend = row.get_ref(7)?.as_str()?;
            let host = if backend.is_empty() {
                context.hosts.values().next()
            } else {
                context
                    .hosts
                    .iter()
                    .find(|(b, _)| b.as_str() == backend)
                    .map(|(_, h)| h)
            }
            .context(DomainError::new(
                ErrorCode::AnalysisUnitScope,
                "provider owner has no host account",
            ))?;
            let mut bytes = 0u64;
            for column in 0..7 {
                bytes = bytes.saturating_add(row.get_ref(column)?.as_str()?.len() as u64);
            }
            host.charge(
                bytes.saturating_add(1),
                0,
                bytes.saturating_mul(32).saturating_add(4096),
            )
            .map_err(DomainError::analysis)?;
        }
        let next: String = row.get(0)?;
        if next != key && !providers.is_empty() {
            write_provider(db, &key, &providers, context)?;
            providers.clear();
        }
        key = next;
        providers.push(Provider {
            unit: row.get(1)?,
            language: row.get(2)?,
            version: row.get(3)?,
            operations: row.get(4)?,
            limitations: row.get(5)?,
            profile: row.get(6)?,
        });
    }
    if !providers.is_empty() {
        write_provider(db, &key, &providers, context)?;
    }
    Ok(())
}
fn check(context: Option<&GraphStagingContext>) -> Result<()> {
    if let Some(context) = context {
        for host in context.hosts.values() {
            host.charge(0, 0, 0).map_err(DomainError::analysis)?;
        }
    }
    Ok(())
}
fn write_provider(
    db: &Connection,
    id: &str,
    providers: &[Provider],
    context: Option<&GraphStagingContext>,
) -> Result<()> {
    check(context)?;
    let first = &providers[0];
    let same = |field: fn(&Provider) -> &str| providers.iter().all(|p| field(p) == field(first));
    let language = if same(|p| &p.language) {
        first.language.as_str()
    } else {
        "*"
    };
    let version = if same(|p| &p.version) {
        first.version.as_str()
    } else {
        "mixed"
    };
    let profile = if same(|p| &p.profile) {
        first.profile.clone()
    } else {
        codeana_model::digest(&(
            "provider-unit-profiles-v1",
            id,
            providers
                .iter()
                .map(|p| (&p.unit, &p.profile))
                .collect::<Vec<_>>(),
        ))
    };
    let operations = if same(|p| &p.operations) {
        first.operations.clone()
    } else {
        let mut common: Option<BTreeSet<String>> = None;
        for p in providers {
            check(context)?;
            let values: BTreeSet<String> = serde_json::from_str(&p.operations).map_err(|e| {
                DomainError::new(
                    ErrorCode::GraphColumnType,
                    format!("provider operations: {e}"),
                )
            })?;
            common = Some(match common {
                None => values,
                Some(old) => old.intersection(&values).cloned().collect(),
            });
        }
        serde_json::to_string(&common.unwrap_or_default())?
    };
    let limitations = if same(|p| &p.limitations) {
        first.limitations.clone()
    } else {
        let by_unit = providers
            .iter()
            .map(|p| {
                check(context)?;
                Ok((
                    p.unit.as_str(),
                    serde_json::from_str::<serde_json::Value>(&p.limitations).map_err(|e| {
                        DomainError::new(
                            ErrorCode::GraphColumnType,
                            format!("provider limitations: {e}"),
                        )
                    })?,
                ))
            })
            .collect::<Result<BTreeMap<_, _>>>()?;
        serde_json::to_string(&serde_json::json!({"units":by_unit}))?
    };
    db.execute(
        "INSERT INTO index_providers VALUES(?,?,?,?,?,?)",
        params![id, language, version, operations, limitations, profile],
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_contracts::graph_rows::{
        CoverageRow, DiagnosticRow, GraphRow, ProjectMetaRow, ProviderRow,
    };

    fn database() -> Result<Connection> {
        let db = Connection::open_in_memory()?;
        super::super::initialize(&db)?;
        db.execute_batch("PRAGMA foreign_keys=ON; INSERT INTO unit_catalog VALUES(1,'left','python'),(2,'right','python');")?;
        Ok(db)
    }
    fn metadata(unit: i64, partial: bool) -> Vec<Row> {
        let mut rows = vec![
            CoverageRow {
                source_file: Some("*".into()),
                module: Some("app".into()),
                scope: Some("MAIN".into()),
                language: Some("python".into()),
                provider_id: Some("core".into()),
                capability: Some("AGGREGATE".into()),
                status: Some(if partial { "partial" } else { "complete" }.into()),
                occurrences: Some(1),
                groups_count: Some(1),
                codes: Some("[]".into()),
                code_counts: Some("{}".into()),
                details_truncated: Some(0),
            }
            .into_row(""),
            DiagnosticRow {
                severity: Some("warning".into()),
                code: Some("OBSERVATION".into()),
                phase: Some("analysis".into()),
                module: Some("app".into()),
                scope: Some("MAIN".into()),
                language: Some("python".into()),
                provider_id: Some("core".into()),
                occurrence_count: Some(1),
                sample: Some(format!("unit {unit}")),
                ..Default::default()
            }
            .into_row(""),
            ProviderRow {
                provider_id: Some("core".into()),
                language: Some("python".into()),
                provider_version: Some("v1".into()),
                operations: Some(
                    if unit == 1 {
                        r#"["declarations","calls"]"#
                    } else {
                        r#"["declarations","references"]"#
                    }
                    .into(),
                ),
                limitations: Some(format!("[\"limit-{unit}\"]")),
                profile_hash: Some(codeana_model::hash(unit.to_string())),
            }
            .into_row(""),
        ];
        for (key, value) in [
            (
                "analysis_mode",
                if partial { "source_only" } else { "semantic" },
            ),
            (
                "classpath_status",
                if partial { "unavailable" } else { "available" },
            ),
            (
                "dependency_mode",
                if partial {
                    "source_only_fallback"
                } else {
                    "resolved"
                },
            ),
        ] {
            rows.push(
                ProjectMetaRow {
                    key: Some(key.into()),
                    value: Some(value.into()),
                }
                .into_row(""),
            );
        }
        rows
    }
    fn apply(
        db: &Connection,
        active: &[i64],
        records: Vec<(i64, Row)>,
        context: Option<&GraphStagingContext>,
    ) -> Result<()> {
        let directory = tempfile::tempdir()?;
        let path = directory.path().join("stage.db");
        let stage = Connection::open(&path)?;
        super::super::initialize_stage(&stage)?;
        for unit in active {
            stage.execute("INSERT INTO stage_active_units VALUES(?)", [unit])?;
        }
        for (unit, row) in records {
            super::super::insert_owned_with_world(&stage, &row, &row.world, unit)?;
        }
        drop(stage);
        db.execute("ATTACH DATABASE ? AS staged", [path.to_str().unwrap()])?;
        db.execute_batch("BEGIN IMMEDIATE")?;
        let result = (|| {
            replace_active(db)?;
            promote(db, context)?;
            db.execute_batch("COMMIT")?;
            Ok(())
        })();
        if result.is_err() {
            db.execute_batch("ROLLBACK")?;
        }
        db.execute_batch("DETACH DATABASE staged")?;
        result
    }
    fn raw(db: &Connection, query: &str) -> Result<i64> {
        Ok(db.query_row(query, [], |r| r.get(0))?)
    }
    fn value(db: &Connection, key: &str) -> Result<String> {
        Ok(
            db.query_row("SELECT value FROM project_meta WHERE key=?", [key], |r| {
                r.get(0)
            })?,
        )
    }

    #[test]
    fn same_provider_and_global_coverage_keep_each_units_observation() -> Result<()> {
        let db = database()?;
        apply(
            &db,
            &[1, 2],
            metadata(1, true)
                .into_iter()
                .map(|r| (1, r))
                .chain(metadata(2, false).into_iter().map(|r| (2, r)))
                .collect(),
            None,
        )?;
        assert_eq!(raw(&db, "SELECT count(*) FROM unit_providers")?, 2);
        assert_eq!(raw(&db, "SELECT count(*) FROM index_providers")?, 1);
        assert_eq!(raw(&db, "SELECT count(*) FROM graph_analysis_coverage")?, 2);
        assert_eq!(raw(&db, "SELECT count(*) FROM analysis_coverage")?, 2);
        assert_eq!(raw(&db, "SELECT count(*) FROM index_diagnostics")?, 2);
        assert_eq!(value(&db, "analysis_mode")?, "source_only");
        assert_eq!(value(&db, "classpath_status")?, "unavailable");
        assert_eq!(value(&db, "dependency_mode")?, "source_only_fallback");
        let (operations, limitations, profile): (String, String, String) = db.query_row(
            "SELECT operations,limitations,profile_hash FROM index_providers",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )?;
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&operations)?,
            serde_json::json!(["declarations"])
        );
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&limitations)?,
            serde_json::json!({"units":{"left":["limit-1"],"right":["limit-2"]}})
        );
        assert_ne!(profile, codeana_model::hash("1"));
        assert_ne!(profile, codeana_model::hash("2"));
        assert_eq!(
            db.prepare("PRAGMA table_info(analysis_coverage)")?
                .query_map([], |r| r.get::<_, String>(1))?
                .collect::<rusqlite::Result<Vec<_>>>()?
                .len(),
            13
        );
        Ok(())
    }
    #[test]
    fn refreshing_removing_and_reusing_units_do_not_replace_a_peer() -> Result<()> {
        let db = database()?;
        apply(
            &db,
            &[1, 2],
            metadata(1, true)
                .into_iter()
                .map(|r| (1, r))
                .chain(metadata(2, false).into_iter().map(|r| (2, r)))
                .collect(),
            None,
        )?;
        apply(&db, &[2], vec![], None)?;
        assert_eq!(
            raw(&db, "SELECT count(*) FROM unit_providers WHERE unit_pk=2")?,
            0
        );
        assert_eq!(
            raw(
                &db,
                "SELECT count(*) FROM graph_index_diagnostics WHERE unit_pk=1"
            )?,
            1
        );
        assert_eq!(
            raw(
                &db,
                "SELECT count(*) FROM graph_analysis_coverage WHERE unit_pk=1"
            )?,
            1
        );
        assert_eq!(value(&db, "analysis_mode")?, "source_only");
        assert_eq!(
            db.query_row("SELECT profile_hash FROM index_providers", [], |r| r
                .get::<_, String>(0))?,
            codeana_model::hash("1")
        );
        apply(
            &db,
            &[1],
            metadata(1, false).into_iter().map(|r| (1, r)).collect(),
            None,
        )?;
        assert_eq!(value(&db, "analysis_mode")?, "semantic");
        assert_eq!(value(&db, "classpath_status")?, "available");
        apply(&db, &[], vec![], None)?;
        assert_eq!(raw(&db, "SELECT count(*) FROM unit_providers")?, 1);
        assert_eq!(raw(&db, "SELECT count(*) FROM unit_project_meta")?, 3);
        assert_eq!(raw(&db, "SELECT count(*) FROM index_diagnostics")?, 1);
        assert_eq!(value(&db, "dependency_mode")?, "resolved");
        Ok(())
    }
    #[test]
    fn conflicting_compilation_contexts_fail_atomically() -> Result<()> {
        let db = database()?;
        let row = |value: &str| {
            ProjectMetaRow {
                key: Some("codeana.context.shared".into()),
                value: Some(value.into()),
            }
            .into_row("")
        };
        apply(&db, &[1], vec![(1, row("one"))], None)?;
        let error = apply(&db, &[2], vec![(2, row("two"))], None).unwrap_err();
        assert_eq!(
            error.downcast_ref::<DomainError>().unwrap().code,
            ErrorCode::GraphWorldOwnershipConflict
        );
        assert_eq!(value(&db, "codeana.context.shared")?, "one");
        assert_eq!(
            raw(
                &db,
                "SELECT count(*) FROM unit_project_meta WHERE unit_pk=2"
            )?,
            0
        );
        Ok(())
    }
    #[test]
    fn provider_aggregation_prepays_the_existing_owner_account_before_copying() -> Result<()> {
        let db = database()?;
        let host = codeana_model::AnalysisContext::new(
            codeana_model::AnalysisLimits {
                max_payload_bytes: 1,
                ..Default::default()
            },
            None,
        )?;
        let context = GraphStagingContext {
            hosts: BTreeMap::from([(codeana_model::AnalysisBackend::Python, host.clone())]),
            source: std::sync::Arc::new(|_, _| panic!("metadata does not read source")),
        };
        let error = apply(
            &db,
            &[1],
            metadata(1, false).into_iter().map(|r| (1, r)).collect(),
            Some(&context),
        )
        .unwrap_err();
        assert_eq!(
            error.downcast_ref::<DomainError>().unwrap().code,
            ErrorCode::AnalysisResourceLimit
        );
        assert!(host.usage().failure.is_some());
        assert_eq!(raw(&db, "SELECT count(*) FROM unit_providers")?, 0);
        Ok(())
    }
    #[test]
    fn provider_summary_does_not_depend_on_private_unit_numbers() -> Result<()> {
        let first = database()?;
        let second = database()?;
        second.execute_batch("UPDATE unit_catalog SET unit_id='temporary' WHERE unit_pk=1; UPDATE unit_catalog SET unit_id='left' WHERE unit_pk=2; UPDATE unit_catalog SET unit_id='right' WHERE unit_pk=1;")?;
        apply(
            &first,
            &[1, 2],
            metadata(1, true)
                .into_iter()
                .map(|r| (1, r))
                .chain(metadata(2, false).into_iter().map(|r| (2, r)))
                .collect(),
            None,
        )?;
        apply(
            &second,
            &[1, 2],
            metadata(1, true)
                .into_iter()
                .map(|r| (2, r))
                .chain(metadata(2, false).into_iter().map(|r| (1, r)))
                .collect(),
            None,
        )?;
        let summary = |db: &Connection| -> Result<Vec<(String, String, String)>> {
            Ok(db.prepare("SELECT profile_hash,operations,limitations FROM index_providers ORDER BY provider_id")?.query_map([],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?)))?.collect::<rusqlite::Result<_>>()?)
        };
        assert_eq!(summary(&first)?, summary(&second)?);
        Ok(())
    }
}
