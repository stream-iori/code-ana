//! Unit-owned compiler observations, separate from regenerable dependency indexes.
use super::*;
use codeana_model::{DomainError, ErrorCode, GraphStagingContext, Revision};

pub(crate) fn initialize_stage(db: &Connection) -> Result<()> {
    db.execute_batch("CREATE TABLE stage_unit_modes(unit_pk INTEGER PRIMARY KEY,mode TEXT NOT NULL);
      CREATE TABLE stage_manifest(source_file TEXT NOT NULL,world TEXT NOT NULL,PRIMARY KEY(source_file,world)) WITHOUT ROWID;
      CREATE TABLE stage_aux_fresh(unit_pk INTEGER NOT NULL,source_file TEXT NOT NULL,world TEXT NOT NULL,table_kind INTEGER NOT NULL,PRIMARY KEY(unit_pk,source_file,world,table_kind)) WITHOUT ROWID;")?;
    Ok(())
}

pub(crate) fn setup(
    db: &Connection,
    revision: &Revision,
    context: Option<&GraphStagingContext>,
) -> Result<()> {
    for source in &revision.manifest.files {
        if let Some(host) = context.and_then(|c| c.hosts.values().next()) {
            host.charge(
                1,
                0,
                256 + 2 * (source.path.len() + source.world.len()) as u64,
            )
            .map_err(DomainError::analysis)?;
        }
        db.execute(
            "INSERT INTO stage_manifest VALUES(?,?)",
            params![source.path, source.world],
        )?;
    }
    Ok(())
}

pub(crate) fn seed(db: &Connection) -> Result<()> {
    db.execute_batch("INSERT INTO stage_file_cache SELECT f.provider_id,p.path,f.hash,f.schema_version,f.node_count,f.edge_count,f.file_size,f.contract_hash,f.world,f.unit_pk
      FROM baseline.graph_file_cache f JOIN baseline.source_files p ON p.file_pk=f.source_file
      WHERE f.unit_pk IN(SELECT unit_pk FROM stage_unit_modes WHERE mode='delta') AND p.path<>'' AND NOT EXISTS(SELECT 1 FROM replaced r WHERE r.source_file=p.path AND r.world=f.world);
      INSERT INTO stage_annotation_meta(annotation_fqn,meta_annotation_fqn,raw_name,language,provider_id,mechanism,resolution_status,source_file,source_location,producer_id,world,unit_pk)
      SELECT annotation_fqn,meta_annotation_fqn,raw_name,language,provider_id,mechanism,resolution_status,source_file,source_location,producer_id,world,unit_pk FROM baseline.annotation_meta_units a
      WHERE a.unit_pk IN(SELECT unit_pk FROM stage_unit_modes WHERE mode='delta') AND coalesce(a.source_file,'')<>'' AND NOT EXISTS(SELECT 1 FROM replaced r WHERE r.source_file=a.source_file AND r.world=a.world);
      INSERT INTO stage_file_dependencies SELECT DISTINCT d.source_provider_id,p.path,d.depends_on_provider_id,t.path,d.world,d.unit_pk
      FROM baseline.graph_file_dependencies d JOIN baseline.source_files p ON p.file_pk=d.source_file JOIN baseline.source_files t ON t.file_pk=d.depends_on_file
      WHERE d.host_derived=0 AND d.unit_pk IN(SELECT unit_pk FROM stage_unit_modes WHERE mode='delta') AND p.path<>'' AND NOT EXISTS(SELECT 1 FROM replaced r WHERE r.source_file=p.path AND r.world=d.world);
      INSERT INTO stage_symbol_dependencies SELECT d.target_key,p.path,d.world,d.unit_pk
      FROM baseline.graph_symbol_dependencies d JOIN baseline.source_files p ON p.file_pk=d.source_file
      WHERE d.host_derived=0 AND d.unit_pk IN(SELECT unit_pk FROM stage_unit_modes WHERE mode='delta') AND p.path<>'' AND NOT EXISTS(SELECT 1 FROM replaced r WHERE r.source_file=p.path AND r.world=d.world);")?;
    Ok(())
}

pub(crate) fn is_observation(table: Table) -> bool {
    matches!(
        table,
        Table::AnnotationMeta | Table::FileCache | Table::FileDependency | Table::SymbolDependency
    )
}

/// The first fresh auxiliary observation replaces the inherited table snapshot
/// for this input; later rows in the same delivery accumulate normally.
pub(crate) fn fresh_input(
    db: &Connection,
    unit: i64,
    path: &str,
    world: &str,
    table: Table,
    budget: Option<&codeana_model::AnalysisContext>,
) -> Result<()> {
    if let Some(host) = budget {
        host.charge(1, 0, 256 + 2 * (path.len() + world.len()) as u64)
            .map_err(DomainError::analysis)?;
    }
    if db.execute(
        "INSERT OR IGNORE INTO stage_aux_fresh VALUES(?,?,?,?)",
        params![unit, path, world, table as i32],
    )? == 0
    {
        return Ok(());
    }
    if table == Table::AnnotationMeta {
        db.execute("DELETE FROM stage_annotation_meta_owners WHERE source_id IN(SELECT id FROM stage_annotation_meta_sources WHERE unit_pk=? AND source_file=? AND world=?)", params![unit,path,world])?;
    } else {
        let table = match table {
            Table::FileCache => "stage_file_cache",
            Table::FileDependency => "stage_file_dependencies",
            Table::SymbolDependency => "stage_symbol_dependencies",
            _ => anyhow::bail!(DomainError::new(
                ErrorCode::GraphTableUnknown,
                "not an auxiliary observation"
            )),
        };
        db.execute(
            &format!("DELETE FROM {table} WHERE unit_pk=? AND source_file=? AND world=?"),
            params![unit, path, world],
        )?;
    }
    Ok(())
}

pub(crate) fn promote(db: &Connection) -> Result<()> {
    db.execute_batch("DELETE FROM graph_file_cache WHERE unit_pk IN(SELECT unit_pk FROM staged.stage_active_units);
      DELETE FROM annotation_meta_owners WHERE source_id IN(SELECT id FROM annotation_meta_sources WHERE unit_pk IN(SELECT unit_pk FROM staged.stage_active_units));
      DELETE FROM graph_file_dependencies WHERE host_derived=0 AND unit_pk IN(SELECT unit_pk FROM staged.stage_active_units);
      DELETE FROM graph_symbol_dependencies WHERE host_derived=0 AND unit_pk IN(SELECT unit_pk FROM staged.stage_active_units);
      INSERT OR IGNORE INTO source_files(path)
      SELECT source_file FROM staged.stage_file_cache UNION SELECT source_file FROM staged.stage_file_dependencies
      UNION SELECT depends_on_file FROM staged.stage_file_dependencies UNION SELECT source_file FROM staged.stage_symbol_dependencies
      UNION SELECT source_file FROM staged.stage_annotation_meta WHERE source_file IS NOT NULL;
      INSERT INTO graph_file_cache(unit_pk,provider_id,source_file,hash,schema_version,node_count,edge_count,file_size,contract_hash,world)
      SELECT s.unit_pk,s.provider_id,p.file_pk,s.hash,s.schema_version,s.node_count,s.edge_count,s.file_size,s.contract_hash,s.world
      FROM staged.stage_file_cache s JOIN source_files p ON p.path=s.source_file WHERE 1
      ON CONFLICT(unit_pk,provider_id,source_file,world) DO UPDATE SET hash=excluded.hash,schema_version=excluded.schema_version,node_count=excluded.node_count,edge_count=excluded.edge_count,file_size=excluded.file_size,contract_hash=excluded.contract_hash;
      INSERT INTO annotation_meta_units(annotation_fqn,meta_annotation_fqn,raw_name,language,provider_id,mechanism,resolution_status,source_file,source_location,producer_id,world,unit_pk)
      SELECT annotation_fqn,meta_annotation_fqn,raw_name,language,provider_id,mechanism,resolution_status,source_file,source_location,producer_id,world,unit_pk FROM staged.stage_annotation_meta ORDER BY seq;
      INSERT OR IGNORE INTO graph_symbol_dependencies(unit_pk,target_key,source_file,world)
      SELECT s.unit_pk,s.target_key,p.file_pk,s.world FROM staged.stage_symbol_dependencies s JOIN source_files p ON p.path=s.source_file;")?;
    // A wire file dependency supplies a logical path, not a target world.
    // Expand only across this unit's declared bindings; never pick a peer's first world.
    ensure!(!db.prepare("SELECT 1 FROM staged.stage_file_dependencies d WHERE NOT EXISTS(
      SELECT 1 FROM staged.stage_unit_inputs t WHERE t.unit_pk=d.unit_pk AND t.source_file=d.depends_on_file
      UNION ALL SELECT 1 FROM staged.stage_manifest t WHERE d.unit_pk=0 AND t.source_file=d.depends_on_file) LIMIT 1")?.exists([])?,
      DomainError::new(ErrorCode::AnalysisUnitScope,"file dependency target is outside declared bindings"));
    db.execute_batch("INSERT OR IGNORE INTO graph_file_dependencies(unit_pk,source_provider_id,source_file,depends_on_provider_id,depends_on_file,world,target_world)
      SELECT d.unit_pk,d.source_provider_id,p.file_pk,d.depends_on_provider_id,q.file_pk,d.world,t.world
      FROM staged.stage_file_dependencies d JOIN source_files p ON p.path=d.source_file JOIN source_files q ON q.path=d.depends_on_file
      JOIN(SELECT unit_pk,source_file,world FROM staged.stage_unit_inputs UNION ALL SELECT 0,source_file,world FROM staged.stage_manifest) t
        ON t.unit_pk=d.unit_pk AND t.source_file=d.depends_on_file;")?;
    Ok(())
}
