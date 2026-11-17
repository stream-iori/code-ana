//! Semantic comparisons exclude only physical row identities.
use super::*;
pub(super) fn canonical(db: &Connection) -> Result<BTreeMap<String, Vec<String>>> {
    let mut output = BTreeMap::new();
    for table in [
        "nodes",
        "edges",
        "annotations",
        "annotation_meta_owned",
        "analysis_coverage",
        "index_diagnostics",
        "index_providers",
        "file_dependencies",
        "symbol_dependencies",
    ] {
        let columns = db
            .prepare(&format!("PRAGMA table_info({table})"))?
            .query_map([], |r| r.get::<_, String>(1))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let columns = columns
            .into_iter()
            .filter(|c| c != "id" || table == "nodes")
            .collect::<Vec<_>>();
        let mut stmt = db.prepare(&format!("SELECT {} FROM {table}", columns.join(",")))?;
        let mut values = stmt
            .query_map([], |r| {
                (0..r.as_ref().column_count())
                    .map(|i| r.get::<_, rusqlite::types::Value>(i))
                    .collect::<rusqlite::Result<Vec<_>>>()
            })?
            .map(|r| r.map(|v| format!("{v:?}")))
            .collect::<rusqlite::Result<Vec<_>>>()?;
        values.sort();
        output.insert(table.into(), values);
    }
    for (name, sql) in [
        (
            "file_cache",
            "SELECT provider_id,source_file,hash,schema_version,node_count,edge_count,file_size,contract_hash,world FROM file_cache",
        ),
        (
            "calls",
            "SELECT hex(c.stable_hash),o.caller_id,o.source_file,c.begin_line,c.begin_column,c.end_line,c.end_column,c.ordinal,c.context,c.syntax_target,c.receiver_static_type,c.dispatch_kind,c.metadata,c.origin,c.resolution_status,c.producer_id,c.language,c.provider_id FROM call_sites c JOIN call_site_owners o USING(owner_pk)",
        ),
        (
            "targets",
            "SELECT hex(c.stable_hash),t.target_id,t.external_target_fqn,t.external_target_symbol,t.external_target_language,t.external_target_provider_id,t.resolution_status,t.confidence,t.producer_id FROM call_site_targets t JOIN call_sites c ON c.site_pk=t.call_site_pk",
        ),
    ] {
        let mut stmt = db.prepare(sql)?;
        let mut values = stmt
            .query_map([], |r| {
                (0..r.as_ref().column_count())
                    .map(|i| r.get::<_, rusqlite::types::Value>(i))
                    .collect::<rusqlite::Result<Vec<_>>>()
            })?
            .map(|r| r.map(|v| format!("{v:?}")))
            .collect::<rusqlite::Result<Vec<_>>>()?;
        values.sort();
        output.insert(name.into(), values);
    }
    Ok(output)
}
