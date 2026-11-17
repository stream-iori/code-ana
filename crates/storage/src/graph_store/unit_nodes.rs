//! Definition membership and binding visibility, assigned by the host rather than producers.
use super::*;
use codeana_model::{
    AnalysisContext, AnalysisUnitId, DomainError, ErrorCode, GraphStagingContext, Revision,
    UnitAvailability,
};
use std::collections::BTreeMap;

pub(crate) fn initialize_stage(db: &Connection) -> Result<()> {
    db.execute_batch("CREATE TABLE stage_unit_inputs(unit_pk INTEGER NOT NULL,source_file TEXT NOT NULL,world TEXT NOT NULL,PRIMARY KEY(unit_pk,source_file,world)) WITHOUT ROWID;
      CREATE TABLE stage_node_units(unit_pk INTEGER NOT NULL,node_id TEXT NOT NULL,node_key BLOB NOT NULL,node_hash BLOB NOT NULL,declaration_hash BLOB,arity_key TEXT,PRIMARY KEY(unit_pk,node_id)) WITHOUT ROWID;
      CREATE INDEX stage_node_units_node ON stage_node_units(node_id,unit_pk);
      CREATE TABLE stage_declaration_units(unit_pk INTEGER NOT NULL,node_key BLOB NOT NULL,definition_hash BLOB NOT NULL,PRIMARY KEY(unit_pk,node_key)) WITHOUT ROWID;")?;
    Ok(())
}
pub(crate) fn setup(
    stage: &Connection,
    revision: &Revision,
    units: &BTreeMap<&AnalysisUnitId, i64>,
    context: Option<&GraphStagingContext>,
) -> Result<()> {
    if let Some(ledger) = &revision.unit_ownership {
        for unit in ledger
            .units
            .iter()
            .filter(|u| u.availability == UnitAvailability::Ready)
        {
            let pk = units[&unit.unit];
            for input in &unit.inputs {
                if let Some(context) = context {
                    let host = context.hosts.get(&unit.backend).context(DomainError::new(
                        ErrorCode::AnalysisUnitScope,
                        "binding owner has no host account",
                    ))?;
                    host.charge(
                        1,
                        0,
                        256 + 2 * (input.path.len() + input.world.len()) as u64,
                    )
                    .map_err(DomainError::analysis)?;
                }
                stage.execute(
                    "INSERT INTO stage_unit_inputs VALUES(?,?,?)",
                    params![pk, input.path, input.world],
                )?;
            }
        }
    }
    Ok(())
}
pub(crate) fn admit(row: &Row, budget: Option<&AnalysisContext>) -> Result<()> {
    if matches!(
        Table::try_from(row.table),
        Ok(Table::Node | Table::Declaration)
    ) && let Some(budget) = budget
    {
        let bytes = row.cells.iter().fold(row.world.len() as u64, |n, c| {
            n.saturating_add(match &c.value {
                Some(cell::Value::Text(s)) => s.len() as u64,
                Some(cell::Value::Blob(b)) => b.len() as u64,
                _ => 8,
            })
        });
        budget
            .charge(
                bytes.div_ceil(64).saturating_add(2),
                0,
                bytes.saturating_mul(2).saturating_add(1024),
            )
            .map_err(DomainError::analysis)?;
    }
    Ok(())
}
fn text(row: &Row, column: usize) -> &str {
    match row.cells.get(column).and_then(|c| c.value.as_ref()) {
        Some(cell::Value::Text(s)) => s,
        _ => "",
    }
}
fn frame(hash: &mut Sha256, value: &[u8]) {
    hash.update((value.len() as u64).to_be_bytes());
    hash.update(value);
}
fn key(row: &Row, world: &str, columns: &[usize]) -> Vec<u8> {
    let mut hash = Sha256::new();
    for &column in columns {
        frame(&mut hash, text(row, column).as_bytes());
    }
    frame(&mut hash, world.as_bytes());
    hash.finalize().to_vec()
}
fn definition(row: &Row, world: &str) -> Vec<u8> {
    let mut hash = Sha256::new();
    hash.update(row.table.to_be_bytes());
    frame(&mut hash, world.as_bytes());
    for cell in &row.cells {
        match &cell.value {
            None => hash.update([0]),
            Some(cell::Value::Text(s)) => {
                hash.update([1]);
                frame(&mut hash, s.as_bytes());
            }
            Some(cell::Value::Integer(n)) => {
                hash.update([2]);
                hash.update(n.to_be_bytes());
            }
            Some(cell::Value::Blob(b)) => {
                hash.update([3]);
                frame(&mut hash, b);
            }
        }
    }
    hash.finalize().to_vec()
}
pub(crate) fn observe(db: &Connection, row: &Row, world: &str, unit: i64) -> Result<()> {
    match Table::try_from(row.table)? {
        Table::Node => {
            let arity = match row.cells.get(22).and_then(|c| c.value.as_ref()) {
                Some(cell::Value::Text(s)) => Some(s.as_str()),
                _ => None,
            };
            db.execute("INSERT INTO stage_node_units(unit_pk,node_id,node_key,node_hash,arity_key) VALUES(?,?,?,?,?) ON CONFLICT(unit_pk,node_id) DO UPDATE SET node_key=excluded.node_key,node_hash=excluded.node_hash,declaration_hash=NULL,arity_key=excluded.arity_key",
                params![unit,text(row,0),key(row,world,&[4,1,19,20,12,23]),definition(row,world),arity])?;
        }
        Table::Declaration => {
            db.execute(
                "INSERT OR REPLACE INTO stage_declaration_units VALUES(?,?,?)",
                params![
                    unit,
                    key(row, world, &[3, 0, 23, 24, 17, 29]),
                    definition(row, world)
                ],
            )?;
        }
        _ => {}
    }
    Ok(())
}
pub(crate) fn validate_definitions(db: &Connection) -> Result<()> {
    db.execute_batch("UPDATE stage_node_units AS n SET declaration_hash=(SELECT d.definition_hash FROM stage_declaration_units d WHERE d.unit_pk=n.unit_pk AND d.node_key=n.node_key) WHERE EXISTS(SELECT 1 FROM stage_declaration_units d WHERE d.unit_pk=n.unit_pk AND d.node_key=n.node_key);")?;
    ensure!(!db.prepare("SELECT node_id FROM stage_node_units GROUP BY node_id HAVING count(DISTINCT hex(node_hash))>1 OR count(DISTINCT coalesce(hex(declaration_hash),'none'))>1 LIMIT 1")?.exists([])?,
        DomainError::new(ErrorCode::GraphWorldOwnershipConflict,"shared node has incompatible unit definitions"));
    Ok(())
}
pub(super) fn visible(node: &str, unit: &str) -> String {
    format!(
        "({unit}=0 OR (coalesce({node}.source_file,'')='' AND EXISTS(SELECT 1 FROM stage_node_units membership WHERE membership.node_id={node}.id AND membership.unit_pk={unit})) OR (coalesce({node}.source_file,'')<>'' AND EXISTS(SELECT 1 FROM stage_unit_inputs input WHERE input.unit_pk={unit} AND input.source_file={node}.source_file AND input.world={node}.world)))"
    )
}
pub(super) fn owns(node: &str, unit: &str) -> String {
    format!(
        "({unit}=0 OR ({} AND EXISTS(SELECT 1 FROM stage_node_units member WHERE member.node_id={node}.id AND member.unit_pk={unit})))",
        visible(node, unit)
    )
}
pub(crate) fn visible_persisted(node: &str, unit: &str) -> String {
    format!(
        "({unit}=0 OR (coalesce({node}.source_file,'')='' AND EXISTS(SELECT 1 FROM node_units member JOIN graph_nodes physical ON physical.node_pk=member.node_pk WHERE physical.id={node}.id AND member.unit_pk={unit})) OR (coalesce({node}.source_file,'')<>'' AND EXISTS(SELECT 1 FROM staged.stage_unit_inputs input WHERE input.unit_pk={unit} AND input.source_file={node}.source_file AND input.world={node}.world)))"
    )
}
pub(crate) fn validate_references(db: &Connection) -> Result<()> {
    for (table, columns) in [
        (
            "stage_edges",
            vec![
                ("resolved_source", "1=1"),
                ("resolved_target", "1=1"),
                ("source_ref", "source_is_key=1"),
                ("target_ref", "target_is_key=1"),
            ],
        ),
        (
            "stage_call_sites",
            vec![
                ("resolved_source", "1=1"),
                ("resolved_target", "1=1"),
                ("source_ref", "source_is_key=1"),
                ("target_ref", "target_is_key=1"),
            ],
        ),
        (
            "stage_annotations",
            vec![("resolved_node", "1=1"), ("node_ref", "node_is_key=1")],
        ),
    ] {
        for (column, condition) in columns {
            let scope = if matches!(
                column,
                "resolved_source" | "source_ref" | "resolved_node" | "node_ref"
            ) {
                owns("n", "t.unit_pk")
            } else {
                visible("n", "t.unit_pk")
            };
            ensure!(!db.prepare(&format!("SELECT 1 FROM {table} t JOIN stage_nodes n ON n.id=t.{column} WHERE t.unit_pk<>0 AND {condition} AND NOT {scope} LIMIT 1"))?.exists([])?,
                DomainError::new(ErrorCode::AnalysisUnitScope,"exact reference is outside declared unit bindings"));
        }
    }
    Ok(())
}
pub(crate) fn seed(stage: &Connection) -> Result<()> {
    stage.execute_batch("INSERT INTO stage_node_units(unit_pk,node_id,node_key,node_hash,declaration_hash,arity_key)
      SELECT m.unit_pk,n.id,zeroblob(32),m.node_hash,m.declaration_hash,m.arity_key FROM baseline.node_units m JOIN baseline.nodes n ON n.id=(SELECT id FROM baseline.graph_nodes WHERE node_pk=m.node_pk)
      WHERE NOT EXISTS(SELECT 1 FROM replaced r WHERE r.source_file=n.source_file AND r.world=n.world)
      AND (coalesce(n.source_file,'')<>'' OR m.unit_pk NOT IN(SELECT unit_pk FROM stage_active_units));
      DELETE FROM stage_nodes WHERE NOT EXISTS(SELECT 1 FROM stage_node_units m WHERE m.node_id=stage_nodes.id);
      DELETE FROM stage_declarations WHERE NOT EXISTS(SELECT 1 FROM stage_nodes n WHERE n.provider_id=stage_declarations.provider_id AND n.symbol_id=stage_declarations.symbol_id AND n.module=stage_declarations.module AND n.scope=stage_declarations.scope AND n.source_file=stage_declarations.source_file AND n.producer_id=stage_declarations.producer_id AND n.world=stage_declarations.world);
      UPDATE stage_nodes AS n SET arity_key=(SELECT min(arity_key) FROM stage_node_units m WHERE m.node_id=n.id);")?;
    Ok(())
}
pub(crate) fn promote(db: &Connection) -> Result<()> {
    db.execute_batch("CREATE TEMP TABLE IF NOT EXISTS graph_replaced(source_file TEXT,world TEXT,PRIMARY KEY(source_file,world));
      DELETE FROM node_units WHERE unit_pk IN(SELECT unit_pk FROM staged.stage_active_units) AND node_pk IN(SELECT n.node_pk FROM graph_nodes n LEFT JOIN source_files f ON f.file_pk=n.source_file WHERE coalesce(f.path,'')='' OR EXISTS(SELECT 1 FROM temp.graph_replaced r WHERE r.source_file=f.path AND r.world=n.world));
      INSERT INTO node_units(unit_pk,node_pk,node_hash,declaration_hash,arity_key) SELECT m.unit_pk,n.node_pk,m.node_hash,m.declaration_hash,m.arity_key FROM staged.stage_node_units m JOIN graph_nodes n ON n.id=m.node_id WHERE 1
      ON CONFLICT(unit_pk,node_pk) DO UPDATE SET node_hash=excluded.node_hash,declaration_hash=excluded.declaration_hash,arity_key=excluded.arity_key;
      DELETE FROM graph_nodes WHERE NOT EXISTS(SELECT 1 FROM node_units m WHERE m.node_pk=graph_nodes.node_pk);
      UPDATE graph_edges AS e SET unit_pk=(SELECT s.unit_pk FROM staged.stage_edges s WHERE s.fact_hash=e.fact_hash AND s.fact_ordinal=e.fact_ordinal AND s.world=e.world) WHERE EXISTS(SELECT 1 FROM staged.stage_edges s WHERE s.fact_hash=e.fact_hash AND s.fact_ordinal=e.fact_ordinal AND s.world=e.world);
      UPDATE graph_annotations AS a SET unit_pk=(SELECT s.unit_pk FROM staged.stage_annotations s WHERE s.fact_hash=a.fact_hash AND s.fact_ordinal=a.fact_ordinal AND s.world=a.world) WHERE EXISTS(SELECT 1 FROM staged.stage_annotations s WHERE s.fact_hash=a.fact_hash AND s.fact_ordinal=a.fact_ordinal AND s.world=a.world);")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_contracts::graph_rows::{DeclarationRow, EdgeRow, GraphRow, NodeRow};
    fn node(id: &str, symbol: &str, file: &str, metadata: &str) -> Row {
        NodeRow {
            id: Some(id.into()),
            symbol_id: Some(symbol.into()),
            domain: Some("language".into()),
            language: Some("java".into()),
            provider_id: Some("core".into()),
            entity_kind: Some("declaration".into()),
            label: Some(symbol.into()),
            kind: Some("CLASS".into()),
            qualified_name: Some(symbol.into()),
            source_file: Some(file.into()),
            module: Some("app".into()),
            scope: Some("MAIN".into()),
            metadata: Some(metadata.into()),
            producer_id: Some("core".into()),
            ..Default::default()
        }
        .into_row(if file.is_empty() { "" } else { "main" })
    }
    fn stage() -> Result<Connection> {
        let db = Connection::open_in_memory()?;
        super::super::initialize_stage(&db)?;
        db.execute_batch(
            "INSERT INTO stage_unit_inputs VALUES(1,'A.java','main'),(2,'B.java','main');",
        )?;
        for (unit, row) in [
            (1, node("owner", "Owner", "A.java", "{}")),
            (2, node("peer", "PeerOnly", "B.java", "{}")),
        ] {
            super::super::insert_owned_with_world(&db, &row, &row.world, unit)?;
        }
        Ok(db)
    }
    fn edge(target: &str, external: bool) -> Row {
        EdgeRow {
            source_ref: Some("owner".into()),
            source_is_key: Some(1),
            resolved_source: Some("owner".into()),
            target_ref: (!external).then(|| target.into()),
            target_is_key: Some(1),
            resolved_target: (!external).then(|| target.into()),
            external_target_fqn: external.then(|| target.into()),
            is_external: Some(i64::from(external)),
            relation: Some("REFERENCES".into()),
            language: Some("java".into()),
            provider_id: Some("core".into()),
            source_file: Some("A.java".into()),
            source_module: Some("app".into()),
            source_scope: Some("MAIN".into()),
            producer_id: Some("core".into()),
            ..Default::default()
        }
        .into_row("main")
    }
    #[test]
    fn fuzzy_targets_do_not_promote_a_peer_without_declared_bindings() -> Result<()> {
        let db = stage()?;
        let row = edge("PeerOnly", true);
        super::super::insert_owned_with_world(&db, &row, &row.world, 1)?;
        super::super::resolve(&db)?;
        assert_eq!(
            db.query_row("SELECT is_external FROM stage_edges", [], |r| r
                .get::<_, i64>(0))?,
            1
        );
        db.execute(
            "INSERT INTO stage_unit_inputs VALUES(1,'B.java','main')",
            [],
        )?;
        super::super::resolve(&db)?;
        assert_eq!(
            db.query_row("SELECT resolved_target FROM stage_edges", [], |r| r
                .get::<_, String>(0))?,
            "peer"
        );
        Ok(())
    }
    #[test]
    fn exact_targets_require_bindings_but_sources_require_own_membership() -> Result<()> {
        let db = stage()?;
        let row = edge("peer", false);
        super::super::insert_owned_with_world(&db, &row, &row.world, 1)?;
        assert!(super::super::resolve(&db).is_err());
        db.execute(
            "INSERT INTO stage_unit_inputs VALUES(1,'B.java','main')",
            [],
        )?;
        super::super::resolve(&db)?;
        db.execute(
            "UPDATE stage_edges SET resolved_source='peer',source_ref='peer'",
            [],
        )?;
        let error = super::super::resolve(&db).unwrap_err();
        assert_eq!(
            error.downcast_ref::<DomainError>().unwrap().code,
            ErrorCode::AnalysisUnitScope
        );
        Ok(())
    }
    #[test]
    fn shared_global_nodes_need_membership_and_compatible_final_definitions() -> Result<()> {
        let db = stage()?;
        let global = node("library", "Library", "", "{}");
        super::super::insert_owned_with_world(&db, &global, "", 2)?;
        let reference = edge("library", false);
        super::super::insert_owned_with_world(&db, &reference, &reference.world, 1)?;
        assert!(super::super::resolve(&db).is_err());
        let intermediate = node("library", "Library", "", r#"{"version":2}"#);
        super::super::insert_owned_with_world(&db, &intermediate, "", 1)?;
        assert!(validate_definitions(&db).is_err());
        super::super::insert_owned_with_world(&db, &global, "", 1)?;
        super::super::resolve(&db)?;
        assert_eq!(
            db.query_row(
                "SELECT count(*) FROM stage_node_units WHERE node_id='library'",
                [],
                |r| r.get::<_, i64>(0)
            )?,
            2
        );
        Ok(())
    }
    #[test]
    fn declaration_conflicts_are_not_hidden_by_global_upserts() -> Result<()> {
        let db = stage()?;
        let global = node("library", "Library", "", "{}");
        for unit in [1, 2] {
            super::super::insert_owned_with_world(&db, &global, "", unit)?;
        }
        let declaration = |visibility: &str| {
            DeclarationRow {
                symbol_id: Some("Library".into()),
                domain: Some("language".into()),
                language: Some("java".into()),
                provider_id: Some("core".into()),
                entity_kind: Some("declaration".into()),
                qualified_name: Some("Library".into()),
                label: Some("Library".into()),
                kind: Some("CLASS".into()),
                declaration_kind: Some("type".into()),
                visibility: Some(visibility.into()),
                modifiers: Some("[]".into()),
                declared_modifiers: Some("[]".into()),
                implicit_modifiers: Some("[]".into()),
                source_file: Some("".into()),
                module: Some("app".into()),
                scope: Some("MAIN".into()),
                nesting_depth: Some(0),
                direct_member: Some(0),
                synthetic: Some(0),
                binding_resolved: Some(1),
                producer_id: Some("core".into()),
                ..Default::default()
            }
            .into_row("")
        };
        super::super::insert_owned_with_world(&db, &declaration("public"), "", 1)?;
        super::super::insert_owned_with_world(&db, &declaration("private"), "", 2)?;
        assert!(validate_definitions(&db).is_err());
        super::super::insert_owned_with_world(&db, &declaration("public"), "", 2)?;
        validate_definitions(&db)?;
        Ok(())
    }
}
