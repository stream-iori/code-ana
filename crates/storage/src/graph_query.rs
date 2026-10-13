//! Query the Anatomist tables directly. Display entities are assembled only on reads.
use super::*;
use rusqlite::{Row, types::ValueRef};
use serde_json::{Value as JsonValue, json};
use sha2::Digest;

fn text(row: &Row<'_>, column: &str) -> rusqlite::Result<String> {
    Ok(row.get::<_, Option<String>>(column)?.unwrap_or_default())
}
fn attributes(row: &Row<'_>) -> Result<BTreeMap<String, JsonValue>> {
    let mut values = BTreeMap::new();
    for column in row.as_ref().column_names() {
        let value = match row.get_ref(column)? {
            ValueRef::Null => JsonValue::Null,
            ValueRef::Text(s) => json!(std::str::from_utf8(s)?),
            ValueRef::Integer(n) => json!(n),
            ValueRef::Real(n) => json!(n),
            ValueRef::Blob(b) => json!(b.iter().map(|n| format!("{n:02x}")).collect::<String>()),
        };
        values.insert(column.to_owned(), value);
    }
    if let Some(JsonValue::String(metadata)) = values.get("metadata")
        && let Ok(JsonValue::Object(extra)) = serde_json::from_str(metadata)
    {
        for (k, v) in extra {
            values.entry(k).or_insert(v);
        }
    }
    Ok(values)
}
fn relation(raw: &str) -> String {
    match raw {
        "INHERITS" => "extends",
        "IMPLEMENTS" => "implements",
        "OVERRIDES" => "overrides",
        "CONTAINS" => "contains",
        "CALLS" => "calls",
        "INJECTS" => "injects_possible",
        "WIRES" => "wires_possible",
        "CONFIGURES" => "config",
        _ => return raw.to_ascii_lowercase(),
    }
    .into()
}
fn upstream_relation(raw: &str) -> String {
    match raw {
        "extends" => "INHERITS",
        "implements" => "IMPLEMENTS",
        "injects_possible" => "INJECTS",
        "wires_possible" => "WIRES",
        "config" => "CONFIGURES",
        _ => return raw.to_ascii_uppercase(),
    }
    .into()
}

struct Projection {
    kind: FactKind,
    from: &'static str,
    id: &'static str,
    parent: &'static str,
    target: &'static str,
    source: &'static str,
    module: &'static str,
    scope: &'static str,
    name: &'static str,
    relation: &'static str,
}
fn projections(filter: &FactFilter) -> Vec<Projection> {
    let mut output = vec![
        Projection {
            kind: FactKind::Declaration,
            from: "nodes n",
            id: "n.id",
            parent: "coalesce((SELECT source_id FROM edges WHERE relation='CONTAINS' AND target_id=n.id ORDER BY id LIMIT 1),'')",
            target: "''",
            source: "n.source_file",
            module: "n.module",
            scope: "n.scope",
            name: "n.label",
            relation: "''",
        },
        Projection {
            kind: FactKind::ExternalSymbol,
            from: "(SELECT external_target_fqn,external_target_language,external_target_provider_id FROM edges WHERE is_external=1 UNION SELECT external_target_fqn,external_target_language,external_target_provider_id FROM call_site_targets WHERE external_target_fqn IS NOT NULL) x",
            id: "'external:'||x.external_target_fqn",
            parent: "''",
            target: "''",
            source: "''",
            module: "''",
            scope: "''",
            name: "x.external_target_fqn",
            relation: "''",
        },
        Projection {
            kind: FactKind::Relation,
            from: "edges e JOIN nodes n ON n.id=e.source_id",
            id: "'edge:'||CASE WHEN e.fact_hash IS NOT NULL THEN lower(hex(e.fact_hash)) ELSE hex(e.source_id)||':'||hex(coalesce(e.target_id,e.external_target_fqn))||':'||hex(e.relation)||':'||hex(coalesce(e.source_location,'')) END||':'||e.fact_ordinal",
            parent: "e.source_id",
            target: "coalesce(e.target_id,'external:'||e.external_target_fqn)",
            source: "e.source_file",
            module: "n.module",
            scope: "n.scope",
            name: "''",
            relation: "e.relation",
        },
        Projection {
            kind: FactKind::CallSite,
            from: "call_sites c JOIN call_site_owners o USING(owner_pk) JOIN nodes n ON n.id=o.caller_id",
            id: "'call-site:'||lower(hex(c.stable_hash))",
            parent: "o.caller_id",
            target: "''",
            source: "o.source_file",
            module: "n.module",
            scope: "n.scope",
            name: "coalesce(c.syntax_target,'')",
            relation: "'CALLS'",
        },
        Projection {
            kind: FactKind::CallTarget,
            from: "call_site_targets t JOIN call_sites c ON c.site_pk=t.call_site_pk JOIN call_site_owners o USING(owner_pk) JOIN nodes n ON n.id=o.caller_id",
            id: "'call-target:'||lower(hex(c.stable_hash))||':'||hex(coalesce(t.target_id,''))||':'||hex(coalesce(t.external_target_provider_id,''))||':'||hex(coalesce(t.external_target_fqn,''))",
            parent: "'call-site:'||lower(hex(c.stable_hash))",
            target: "coalesce(t.target_id,'external:'||t.external_target_fqn)",
            source: "o.source_file",
            module: "n.module",
            scope: "n.scope",
            name: "''",
            relation: "'CALLS'",
        },
        Projection {
            kind: FactKind::Facet,
            from: "annotations a JOIN nodes n ON n.id=a.node_id",
            id: "'annotation:'||lower(hex(a.fact_hash))||':'||a.fact_ordinal",
            parent: "a.node_id",
            target: "''",
            source: "a.source_file",
            module: "n.module",
            scope: "n.scope",
            name: "coalesce(a.annotation_fqn,a.raw_name)",
            relation: "'java.annotation'",
        },
        Projection {
            kind: FactKind::Coverage,
            from: "analysis_coverage v",
            id: "'coverage:'||v.provider_id||':'||v.source_file||':'||v.capability",
            parent: "''",
            target: "''",
            source: "v.source_file",
            module: "v.module",
            scope: "v.scope",
            name: "v.capability",
            relation: "''",
        },
        Projection {
            kind: FactKind::Diagnostic,
            from: "index_diagnostics d",
            id: "'diagnostic:'||d.id",
            parent: "''",
            target: "''",
            source: "coalesce(d.source_file,'')",
            module: "coalesce(d.module,'')",
            scope: "coalesce(d.scope,'')",
            name: "d.code",
            relation: "''",
        },
    ];
    if !filter.kinds.is_empty() {
        output.retain(|p| {
            filter.kinds.contains(&p.kind)
                || p.kind == FactKind::Relation && filter.kinds.contains(&FactKind::Reference)
                || p.kind == FactKind::Declaration && filter.kinds.contains(&FactKind::Facet)
        });
    }
    output
}

/// Immutable revision metadata; no extra graph table or index is introduced.
pub(crate) fn overview_counts(db: &Connection) -> Result<BTreeMap<String, u64>> {
    let mut counts = BTreeMap::new();
    for p in projections(&FactFilter::default()) {
        let table = match p.kind {
            FactKind::Declaration => "nodes",
            FactKind::Relation => "edges",
            FactKind::CallSite => "call_sites",
            FactKind::CallTarget => "call_site_targets",
            FactKind::Facet => "annotations",
            FactKind::Coverage => "analysis_coverage",
            FactKind::Diagnostic => "index_diagnostics",
            _ => p.from,
        };
        let count: i64 =
            db.query_row(&format!("SELECT count(*) FROM {table}"), [], |r| r.get(0))?;
        counts.insert(p.kind.name().into(), count as u64);
    }
    Ok(counts)
}

impl ReadContext {
    fn owner_for(&self, source: &str, module: &str, scope: &str) -> Owner {
        let world = self
            .sources
            .get(source)
            .map(|index| self.revision.manifest.files[*index].world.clone())
            .filter(|world| !world.is_empty())
            .unwrap_or_else(|| key("world", &[module, scope]));
        Owner {
            world,
            producer: self.revision.producer.clone(),
            kind: "source".into(),
            id: source.into(),
        }
    }
    fn predicate(
        &self,
        p: &Projection,
        f: &FactFilter,
        after: &str,
        search: Option<&str>,
    ) -> Result<(String, Vec<String>)> {
        let mut sql = "1=1".to_owned();
        let mut values = Vec::new();
        for (column, value) in [
            (p.parent, &f.parent),
            (p.target, &f.target),
            (p.source, &f.source),
            (p.module, &f.module),
            (p.scope, &f.scope),
            (p.name, &f.name),
        ] {
            if let Some(v) = value {
                sql.push_str(&format!(" AND {column}=?"));
                values.push(v.clone());
            }
        }
        if let Some(r) = &f.relation
            && p.kind != FactKind::Declaration
        {
            sql.push_str(&format!(" AND {}=?", p.relation));
            values.push(if p.kind == FactKind::Facet {
                r.clone()
            } else {
                upstream_relation(r)
            });
        }
        for (column, ids) in [
            (p.id, &f.ids),
            (p.parent, &f.parents),
            (p.target, &f.targets),
            (p.source, &f.sources),
        ] {
            if !ids.is_empty() {
                ensure!(ids.len() <= 1000, "SELECTOR_BUDGET");
                sql.push_str(&format!(
                    " AND {column} IN({})",
                    vec!["?"; ids.len()].join(",")
                ));
                values.extend(ids.clone());
            }
        }
        if let Some(world) = &f.world {
            let sources: Vec<_> = self
                .revision
                .manifest
                .files
                .iter()
                .filter(|s| &s.world == world)
                .map(|s| &s.path)
                .collect();
            let generic: Vec<_> = self
                .revision
                .manifest
                .files
                .iter()
                .filter(|s| s.world.is_empty() && s.language.is_empty())
                .map(|s| &s.path)
                .collect();
            if let Some(identity) = self
                .revision
                .manifest
                .files
                .iter()
                .find(|s| &s.world == world && !s.module.is_empty())
            {
                sql.push_str(&format!(" AND ({} IN(SELECT value FROM json_each(?)) OR ({} IN(SELECT value FROM json_each(?)) AND {}=? AND {}=?))",p.source,p.source,p.module,p.scope));
                values.extend([
                    serde_json::to_string(&sources)?,
                    serde_json::to_string(&generic)?,
                    identity.module.clone(),
                    identity.scope.clone(),
                ]);
            } else {
                sql.push_str(&format!(
                    " AND {} IN(SELECT value FROM json_each(?))",
                    p.source
                ));
                values.push(serde_json::to_string(&sources)?);
            }
        }
        if p.kind == FactKind::Declaration {
            if !f.kinds.is_empty() && !f.kinds.contains(&FactKind::Declaration) {
                sql.push_str(" AND n.domain='configuration'");
            }
            if let Some(r) = &f.relation {
                match r.as_str() {
                    "java.bean" => sql.push_str(" AND n.domain='configuration' AND n.kind='BEAN'"),
                    "java.spring.xml" => {
                        sql.push_str(" AND n.domain='configuration' AND n.kind<>'BEAN'")
                    }
                    _ => sql.push_str(" AND 0"),
                }
            }
            for (column, value) in [
                ("n.symbol_id", &f.signature),
                ("n.package", &f.package),
                ("n.visibility", &f.visibility),
            ] {
                if let Some(v) = value {
                    sql.push_str(&format!(" AND {column}=?"));
                    values.push(v.clone());
                }
            }
            if let Some(prefix) = &f.package_prefix {
                sql.push_str(" AND (n.package=? OR instr(n.package,?)=1)");
                values.extend([prefix.clone(), format!("{prefix}.")]);
            }
            if let Some(synthetic) = f.synthetic {
                sql.push_str(" AND n.synthetic=CAST(? AS INTEGER)");
                values.push(i32::from(synthetic).to_string());
            }
            if let Some(kind) = &f.entity_kind {
                sql.push_str(" AND (n.entity_kind=? OR n.language_kind=? OR n.kind=?)");
                values.extend([kind.clone(), kind.clone(), kind.clone()]);
            }
            if let Some(annotation) = &f.annotation {
                sql.push_str(" AND EXISTS(SELECT 1 FROM annotations a WHERE a.node_id=n.id AND (a.annotation_fqn=? OR a.raw_name=?))");
                values.extend([annotation.clone(), annotation.clone()]);
            }
            if let Some(search) = search {
                let expression = if search
                    .chars()
                    .any(|c| c.is_ascii_whitespace() || "\"():*-".contains(c))
                {
                    search.to_owned()
                } else {
                    format!("\"{}\"*", search.replace('"', "\"\""))
                };
                sql.push_str(
                    " AND n.id IN(SELECT id FROM graph_nodes WHERE node_pk IN(SELECT rowid FROM node_names WHERE node_names MATCH ?))",
                );
                values.push(expression);
            }
        } else if p.kind == FactKind::ExternalSymbol {
            if let Some(signature) = &f.signature {
                sql.push_str(" AND x.external_target_fqn=?");
                values.push(signature.clone());
            }
            if f.package.is_some()
                || f.package_prefix.is_some()
                || f.visibility.is_some()
                || f.synthetic.is_some()
                || f.entity_kind.is_some()
                || search.is_some()
            {
                sql.push_str(" AND 0");
            }
        } else if f.signature.is_some()
            || f.package.is_some()
            || f.package_prefix.is_some()
            || f.visibility.is_some()
            || f.synthetic.is_some()
            || f.entity_kind.is_some()
            || search.is_some()
        {
            sql.push_str(" AND 0");
        }
        if !after.is_empty() {
            sql.push_str(&format!(" AND {}>?", p.id));
            values.push(after.into());
        }
        Ok((sql, values))
    }
    fn display(&self, p: &Projection, row: &Row<'_>) -> Result<Fact> {
        let id = text(row, "display_id")?;
        let source = text(row, "display_source")?;
        let module = text(row, "display_module")?;
        let scope = text(row, "display_scope")?;
        let mut attrs = attributes(row)?;
        let mut kind = p.kind;
        let mut signature = String::new();
        let mut status = String::new();
        let mut name = text(row, "display_name")?;
        let mut relation_name = text(row, "display_relation")?;
        let mut complete = true;
        let mut resolved = false;
        match p.kind {
            FactKind::Declaration => {
                signature = text(row, "symbol_id")?;
                attrs.insert("element_kind".into(), json!(text(row, "kind")?));
                attrs.insert("upstream_id".into(), json!(signature));
                attrs.insert("schema_version".into(), json!(33));
                for col in ["modifiers", "declared_modifiers", "implicit_modifiers"] {
                    if let Some(JsonValue::String(s)) = attrs.get(col) {
                        attrs.insert(col.into(), serde_json::from_str(s).unwrap_or(json!([])));
                    }
                }
                for col in ["direct_member", "synthetic", "binding_resolved"] {
                    if let Some(n) = attrs.get(col).and_then(JsonValue::as_i64) {
                        attrs.insert(col.into(), json!(n != 0));
                    }
                }
                resolved = attrs
                    .get("binding_resolved")
                    .and_then(JsonValue::as_bool)
                    .unwrap_or(false);
                if text(row, "domain")? == "configuration" {
                    kind = FactKind::Facet;
                    relation_name = match text(row, "kind")?.as_str() {
                        "BEAN" => "java.bean",
                        _ => "java.spring.xml",
                    }
                    .into();
                }
            }
            FactKind::ExternalSymbol => {
                signature = text(row, "external_target_fqn")?;
                attrs.insert("availability".into(), json!("external"));
                attrs.insert(
                    "element_kind".into(),
                    json!(if signature.contains('#') {
                        "METHOD"
                    } else {
                        "CLASS"
                    }),
                );
                status = "external".into();
                complete = false;
            }
            FactKind::Relation => {
                relation_name = relation(&relation_name);
                resolved = text(row, "confidence")? == "EXTRACTED";
                status = if resolved { "resolved" } else { "possible" }.into();
                attrs.insert(
                    "upstream_relation".into(),
                    json!(text(row, "display_relation")?),
                );
                attrs.insert(
                    "upstream_confidence".into(),
                    json!(text(row, "confidence")?),
                );
            }
            FactKind::CallSite | FactKind::CallTarget => {
                let raw = text(row, "resolution_status")?;
                resolved = raw == "exact";
                status = if resolved {
                    "resolved"
                } else if raw == "unresolved" {
                    "unresolved"
                } else {
                    "possible"
                }
                .into();
                attrs.insert("resolution_status".into(), json!(raw));
                attrs.insert("dispatch".into(), json!(text(row, "dispatch_kind")?));
                attrs.insert(
                    "receiver_static_type".into(),
                    json!(text(row, "receiver_static_type")?),
                );
                relation_name = "calls".into();
            }
            FactKind::Facet => {
                attrs.insert("annotation".into(), json!(text(row, "annotation_fqn")?));
                attrs.insert("raw_name".into(), json!(text(row, "raw_name")?));
                if let Some(JsonValue::String(s)) = attrs.get("attributes")
                    && let Ok(value) = serde_json::from_str::<JsonValue>(s)
                {
                    attrs.insert("values".into(), value);
                }
                attrs.insert("schema_version".into(), json!(2));
            }
            FactKind::Coverage => {
                status = text(row, "status")?;
                complete = status == "complete";
            }
            FactKind::Diagnostic => {
                name = text(row, "code")?;
                attrs.insert(
                    "count".into(),
                    json!(row.get::<_, i64>("occurrence_count")?),
                );
            }
            _ => {}
        }
        let prefix = if p.kind == FactKind::Declaration {
            "declaration_"
        } else {
            ""
        };
        let coordinate = |field: &str| {
            attrs
                .get(&format!("{prefix}{field}"))
                .filter(|v| !v.is_null())
                .or_else(|| attrs.get(field))
                .cloned()
                .unwrap_or(JsonValue::Null)
        };
        attrs.insert("source_range".into(),json!({"begin_line":coordinate("begin_line"),"begin_column":coordinate("begin_column"),"end_line":coordinate("end_line"),"end_column":coordinate("end_column")}));
        let span = attrs
            .get("fixture_span")
            .and_then(|v| serde_json::from_value(v.clone()).ok());
        Ok(Fact {
            id,
            kind,
            owner: self.owner_for(&source, &module, &scope),
            source,
            span,
            name,
            signature,
            parent: text(row, "display_parent")?,
            target: text(row, "display_target")?,
            relation: relation_name,
            status,
            origin: "anatomist".into(),
            evidence_complete: complete,
            declaration_resolved: resolved,
            attributes: attrs,
        })
    }
    fn graph_scan(
        &self,
        f: &FactFilter,
        after: &str,
        limit: usize,
        search: Option<&str>,
    ) -> Result<Vec<Fact>> {
        ensure!(limit > 0 && limit <= MAX_FACTS + 1, "INVALID_SCAN_BATCH");
        if f.relation
            .as_deref()
            .is_some_and(|r| matches!(r, "java.type" | "java.binding" | "java.syntax"))
        {
            anyhow::bail!("UNSUPPORTED_CAPABILITY: raw Java model persistence removed");
        }
        let mut result = Vec::new();
        for p in projections(f) {
            let (predicate, mut values) = self.predicate(&p, f, after, search)?;
            let columns = match p.kind {
                FactKind::Declaration => "n.*",
                FactKind::ExternalSymbol => "x.*",
                FactKind::Relation => "e.*",
                FactKind::CallSite => "c.*",
                FactKind::CallTarget => {
                    "t.*,c.dispatch_kind,c.receiver_static_type,c.begin_line,c.begin_column,c.end_line,c.end_column"
                }
                FactKind::Facet => "a.*",
                FactKind::Coverage => "v.*",
                FactKind::Diagnostic => "d.*",
                _ => unreachable!(),
            };
            let sql = format!(
                "SELECT {columns},{} AS display_id,{} AS display_parent,{} AS display_target,{} AS display_source,{} AS display_module,{} AS display_scope,{} AS display_name,{} AS display_relation FROM {} WHERE {predicate} ORDER BY {} LIMIT ?",
                p.id,
                p.parent,
                p.target,
                p.source,
                p.module,
                p.scope,
                p.name,
                p.relation,
                p.from,
                p.id
            );
            values.push(limit.to_string());
            let mut statement = self.connection.prepare(&sql)?;
            let mut rows = statement.query(rusqlite::params_from_iter(values))?;
            while let Some(row) = rows.next()? {
                result.push(self.display(&p, row)?);
            }
        }
        result.sort_by(|a, b| a.id.cmp(&b.id));
        result.truncate(limit);
        Ok(result)
    }
    pub fn baseline_summary(&self) -> Result<JsonValue> {
        let mut contexts = BTreeMap::new();
        let mut statement = self.connection.prepare(
            "SELECT substr(key,17),value FROM project_meta WHERE key LIKE 'codeana.context.%'",
        )?;
        for row in
            statement.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?
        {
            let (k, v) = row?;
            contexts.insert(k, v);
        }
        let mut nodes = Vec::new();
        let mut statement = self.connection.prepare(
            "SELECT id,symbol_id,kind,module,scope,source_file,metadata FROM nodes ORDER BY id",
        )?;
        for row in statement.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, String>(4)?,
                r.get::<_, String>(5)?,
                r.get::<_, Option<String>>(6)?,
            ))
        })? {
            let (id, symbol, kind, module, scope, source, metadata) = row?;
            let owner = self.owner_for(&source, &module, &scope);
            nodes.push(json!({"id":id,"symbol_id":symbol,"kind":kind,"module":module,"scope":scope,"source_file":source,"metadata":metadata,"world":owner.world}));
        }
        let mut relations = Vec::new();
        let mut statement=self.connection.prepare("SELECT n.symbol_id,coalesce(t.symbol_id,e.external_target_fqn),e.relation,n.source_file FROM edges e JOIN nodes n ON n.id=e.source_id LEFT JOIN nodes t ON t.id=e.target_id WHERE e.relation IN('INHERITS','IMPLEMENTS') ORDER BY e.source_id,e.relation,e.target_id,e.external_target_fqn")?;
        for row in statement.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, Option<String>>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
            ))
        })? {
            let (source, target, relation, file) = row?;
            if let Some(target) = target {
                relations.push(json!({"source_symbol":source,"target_symbol":target,"relation":relation,"source_file":file}));
            }
        }
        Ok(
            json!({"contexts":contexts,"nodes":nodes,"type_relations":relations,"files":self.revision.manifest.files}),
        )
    }
    pub fn baseline_summary_for(&self, next: &Manifest) -> Result<JsonValue> {
        let before: BTreeMap<_, _> = self
            .revision
            .manifest
            .files
            .iter()
            .map(|s| (&s.path, s))
            .collect();
        let after: BTreeMap<_, _> = next.files.iter().map(|s| (&s.path, s)).collect();
        let mut changed: BTreeSet<String> = before
            .keys()
            .chain(after.keys())
            .filter(|path| before.get(*path) != after.get(*path))
            .map(|p| (*p).clone())
            .collect();
        let membership = before.keys().any(|p| !after.contains_key(p))
            || after.keys().any(|p| !before.contains_key(p));
        let controls = changed
            .iter()
            .any(|p| p.ends_with("lombok.config") || p.ends_with("pom.xml"));
        if membership || controls {
            changed.extend(
                next.files
                    .iter()
                    .filter(|s| s.language == "java")
                    .map(|s| s.path.clone()),
            );
        } else {
            let mut pending = changed.clone();
            let mut statement=self.connection.prepare("SELECT source_file FROM file_dependencies WHERE depends_on_file=? ORDER BY source_file")?;
            let mut symbols = self
                .connection
                .prepare("SELECT id,symbol_id FROM nodes WHERE source_file=? ORDER BY id")?;
            let mut dependencies=self.connection.prepare("SELECT source_file FROM symbol_dependencies WHERE target_key=? ORDER BY source_file")?;
            while let Some(target) = pending.pop_first() {
                for row in statement.query_map([&target], |r| r.get::<_, String>(0))? {
                    let source = row?;
                    if changed.insert(source.clone()) {
                        pending.insert(source);
                    }
                }
                for row in symbols.query_map([&target], |r| {
                    Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
                })? {
                    let (id, symbol) = row?;
                    for value in [id, symbol] {
                        let hash = sha2::Sha256::digest(value.as_bytes());
                        for row in
                            dependencies.query_map([&hash[..8]], |r| r.get::<_, String>(0))?
                        {
                            let source = row?;
                            if changed.insert(source.clone()) {
                                pending.insert(source);
                            }
                        }
                    }
                }
            }
        }
        let mut summary = self.baseline_summary()?;
        summary["changed_sources"] = json!(changed);
        Ok(summary)
    }
}

impl GraphReader for ReadContext {
    fn reverse_file_dependencies(
        &self,
        targets: &[FileKey],
        source_world: Option<&str>,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<FileDependency>, String> {
        (||->Result<_>{
            ensure!(targets.len()<=500 && (1..=1024).contains(&limit),"INVALID_DEPENDENCY_BATCH");
            if targets.is_empty(){return Ok(vec![]);}
            let paths:Vec<_>=targets.iter().map(|t|&t.path).collect();
            let sql=format!("SELECT source_file,depends_on_file FROM file_dependencies WHERE depends_on_file IN({}) ORDER BY source_file,depends_on_file",vec!["?";paths.len()].join(","));
            let mut statement=self.connection.prepare(&sql)?;let mut rows=statement.query(rusqlite::params_from_iter(paths))?;let mut output=BTreeMap::new();
            while let Some(row)=rows.next()?{
                let source:String=row.get(0)?;let target:String=row.get(1)?;
                let source=self.revision.manifest.files.iter().find(|s|s.path==source).context("DEPENDENCY_SOURCE_NOT_CAPTURED")?;
                let target=self.revision.manifest.files.iter().find(|s|s.path==target).context("DEPENDENCY_TARGET_NOT_CAPTURED")?;
                if source_world.is_some_and(|w|w!=source.world) || !targets.iter().any(|k|k.path==target.path && k.world==target.world){continue;}
                let id=key("file-dependency",&[&source.world,&source.path,&target.world,&target.path]);
                if id.as_str()>after{output.insert(id.clone(),FileDependency{id,source_world:source.world.clone(),source_file:source.path.clone(),target_world:target.world.clone(),target_file:target.path.clone()});}
            }
            Ok(output.into_values().take(limit).collect())
        })().map_err(|e|e.to_string())
    }
    fn source_span(&self, entity: &Fact) -> std::result::Result<Option<Span>, String> {
        (|| -> Result<_> {
            if entity.span.is_some() {
                return Ok(entity.span.clone());
            }
            let Some(range) = entity.attributes.get("source_range") else {
                return Ok(None);
            };
            let coordinates = ["begin_line", "begin_column", "end_line", "end_column"]
                .map(|k| range.get(k).and_then(JsonValue::as_u64));
            let [Some(bl), Some(bc), Some(el), Some(ec)] = coordinates else {
                return Ok(None);
            };
            let source = self
                .sources
                .get(&entity.source)
                .map(|index| &self.revision.manifest.files[*index])
                .context("SOURCE_UNAVAILABLE")?;
            let bytes = std::fs::read(self.home.join("blobs").join(&source.hash))?;
            ensure!(hash(&bytes) == source.hash, "BLOB_HASH_MISMATCH");
            let text = std::str::from_utf8(&bytes)?;
            let offset = |line: u64, column: u64| -> Result<u64> {
                ensure!(line > 0 && column > 0, "SOURCE_RANGE_INVALID");
                let mut start = 0usize;
                for _ in 1..line {
                    start += text[start..].find('\n').context("SOURCE_RANGE_LINE")? + 1;
                }
                let mut units = 0u64;
                let wanted = column - 1;
                for (index, c) in text[start..].char_indices() {
                    if units == wanted {
                        return Ok((start + index) as u64);
                    }
                    ensure!(units < wanted && c != '\n', "SOURCE_RANGE_COLUMN");
                    units += c.len_utf16() as u64;
                }
                ensure!(units == wanted, "SOURCE_RANGE_COLUMN");
                Ok(bytes.len() as u64)
            };
            let start = offset(bl, bc)?;
            let end = offset(el, ec)?;
            ensure!(start <= end, "SOURCE_RANGE_INVALID");
            Ok(Some(Span { start, end }))
        })()
        .map_err(|e| e.to_string())
    }
    fn manifest(&self) -> &Manifest {
        &self.revision.manifest
    }
    fn revision(&self) -> &Revision {
        &self.revision
    }
    fn select(&self, f: &FactFilter) -> std::result::Result<Vec<Fact>, String> {
        self.graph_scan(f, "", MAX_FACTS + 1, None)
            .and_then(|rows| {
                ensure!(rows.len() <= MAX_FACTS, "QUERY_WORK_BUDGET");
                Ok(rows)
            })
            .map_err(|e| e.to_string())
    }
    fn scan(
        &self,
        f: &FactFilter,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<Fact>, String> {
        self.graph_scan(f, after, limit, None)
            .map_err(|e| e.to_string())
    }
    fn scan_symbols(
        &self,
        text: &str,
        f: &FactFilter,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<Fact>, String> {
        let mut f = f.clone();
        f.kinds = vec![FactKind::Declaration];
        self.graph_scan(&f, after, limit, Some(text))
            .map_err(|e| e.to_string())
    }
    fn search_symbols(&self, text: &str) -> std::result::Result<Vec<Fact>, String> {
        self.search_symbols_filtered(text, &FactFilter::default())
    }
    fn search_symbols_filtered(
        &self,
        text: &str,
        f: &FactFilter,
    ) -> std::result::Result<Vec<Fact>, String> {
        self.scan_symbols(text, f, "", MAX_FACTS)
    }
    fn count_by_kind(&self, f: &FactFilter) -> std::result::Result<BTreeMap<String, u64>, String> {
        (|| -> Result<_> {
            let cached: Option<String> = self
                .connection
                .query_row(
                    "SELECT value FROM project_meta WHERE key='codeana.overview_counts'",
                    [],
                    |r| r.get(0),
                )
                .optional()?;
            let cached: Option<BTreeMap<String, u64>> = cached
                .map(|value| serde_json::from_str(&value))
                .transpose()?;
            let mut counts = BTreeMap::new();
            for p in projections(f) {
                let (sql, values) = self.predicate(&p, f, "", None)?;
                if sql == "1=1"
                    && let Some(count) = cached.as_ref().and_then(|map| map.get(p.kind.name()))
                {
                    counts.insert(p.kind.name().into(), *count);
                    continue;
                }
                let from = if sql == "1=1" {
                    match p.kind {
                        FactKind::Declaration => "nodes",
                        FactKind::Relation => "edges",
                        FactKind::CallSite => "call_sites",
                        FactKind::CallTarget => "call_site_targets",
                        FactKind::Facet => "annotations",
                        FactKind::Coverage => "analysis_coverage",
                        FactKind::Diagnostic => "index_diagnostics",
                        _ => p.from,
                    }
                } else {
                    p.from
                };
                let n: i64 = self.connection.query_row(
                    &format!("SELECT count(*) FROM {from} WHERE {sql}"),
                    rusqlite::params_from_iter(values),
                    |r| r.get(0),
                )?;
                counts.insert(p.kind.name().into(), n as u64);
            }
            Ok(counts)
        })()
        .map_err(|e| e.to_string())
    }
    fn annotation_meta_edges(
        &self,
        names: &[String],
    ) -> std::result::Result<Vec<AnnotationMetaEdge>, String> {
        (||->Result<_>{ensure!(names.len()<=500,"ANNOTATION_META_KEY_BUDGET");if names.is_empty(){return Ok(vec![]);}
            let sql=format!("SELECT annotation_fqn,meta_annotation_fqn,min(raw_name),min(language),min(mechanism),min(resolution_status),min(producer_id) FROM annotation_meta_relations WHERE annotation_fqn IN({}) AND meta_annotation_fqn IS NOT NULL GROUP BY annotation_fqn,meta_annotation_fqn LIMIT 10001",vec!["?";names.len()].join(","));
            let mut statement=self.connection.prepare(&sql)?;let mut rows=statement.query(rusqlite::params_from_iter(names))?;let mut output=Vec::new();
            while let Some(row)=rows.next()?{ensure!(output.len()<10000,"QUERY_WORK_BUDGET");let mut attrs=BTreeMap::new();for(i,k)in["raw_name","language","mechanism","resolution","upstream_producer"].iter().enumerate(){if let Some(v)=row.get::<_,Option<String>>(i+2)?{attrs.insert((*k).into(),json!(v));}}
                output.push(AnnotationMetaEdge{annotation:row.get(0)?,meta_annotation:row.get(1)?,attributes:attrs});}Ok(output)
        })().map_err(|e|e.to_string())
    }
    fn unresolved_consumers(
        &self,
        targets: &[String],
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> std::result::Result<Vec<Fact>, String> {
        (||->Result<_>{
            ensure!(targets.len()<=500 && (1..=1024).contains(&limit),"INVALID_DEPENDENCY_BATCH");
            if targets.is_empty(){return Ok(vec![]);}
            let p=projections(&FactFilter{kinds:vec![FactKind::CallSite],..Default::default()}).remove(0);
            let(predicate,mut values)=self.predicate(&p,filter,after,None)?;
            values.push(serde_json::to_string(targets)?);values.push(limit.to_string());
            let sql=format!("SELECT c.*,{} AS display_id,{} AS display_parent,{} AS display_target,{} AS display_source,{} AS display_module,{} AS display_scope,{} AS display_name,{} AS display_relation FROM {} WHERE {predicate} AND c.resolution_status='unresolved' AND c.syntax_target IN(SELECT value FROM json_each(?)) ORDER BY {} LIMIT ?",p.id,p.parent,p.target,p.source,p.module,p.scope,p.name,p.relation,p.from,p.id);
            let mut statement=self.connection.prepare(&sql)?;let mut rows=statement.query(rusqlite::params_from_iter(values))?;let mut output=Vec::new();
            while let Some(row)=rows.next()?{output.push(self.display(&p,row)?);}Ok(output)
        })().map_err(|e|e.to_string())
    }
    fn overview_groups(
        &self,
        f: &FactFilter,
        section: &str,
        direction: &str,
        relations: &[String],
    ) -> std::result::Result<Vec<JsonValue>, String> {
        (||->Result<_>{
            let p=projections(&FactFilter{ kinds:vec![FactKind::Declaration],..Default::default()}).remove(0);
            let (predicate,mut values)=self.predicate(&p,f,"",None)?;
            let sql=if section=="dependencies"{
                let mut relation_sql=String::new();if !relations.is_empty(){relation_sql=format!(" AND e.relation IN({})",vec!["?";relations.len()].join(","));values.extend(relations.iter().map(|r|upstream_relation(r)));}
                let (near,far)=if direction=="in"{("e.target_id","e.source_id")}else{("e.source_id","e.target_id")};
                if direction=="both" {
                    let count=values.len();let repeated=values[..count].to_vec();values.extend(repeated);
                    format!("SELECT package,target_package,relation,sum(count) FROM (SELECT coalesce(n.package,'') AS package,coalesce(t.package,'') AS target_package,e.relation,count(*) AS count FROM edges e JOIN nodes n ON n.id=e.source_id JOIN nodes t ON t.id=e.target_id WHERE {predicate}{relation_sql} GROUP BY n.package,t.package,e.relation UNION ALL SELECT coalesce(n.package,''),coalesce(t.package,''),e.relation,count(*) FROM edges e JOIN nodes n ON n.id=e.target_id JOIN nodes t ON t.id=e.source_id WHERE {predicate}{relation_sql} GROUP BY n.package,t.package,e.relation) GROUP BY package,target_package,relation ORDER BY package,target_package,relation LIMIT 10001")
                } else {format!("SELECT coalesce(n.package,''),coalesce(t.package,''),e.relation,count(*) FROM edges e JOIN nodes n ON n.id={near} JOIN nodes t ON t.id={far} WHERE {predicate}{relation_sql} GROUP BY n.package,t.package,e.relation ORDER BY n.package,t.package,e.relation LIMIT 10001")}
            }else{let column=if section=="modules"{"n.module"}else{"coalesce(n.package,'')"};format!("SELECT {column},count(*) FROM nodes n WHERE {predicate} GROUP BY {column} ORDER BY {column} LIMIT 10001")};
            let mut statement=self.connection.prepare(&sql)?;let mut rows=statement.query(rusqlite::params_from_iter(values))?;let mut output=Vec::new();while let Some(row)=rows.next()?{ensure!(output.len()<10000,"OVERVIEW_GROUP_BUDGET");output.push(if section=="dependencies"{json!({"package":row.get::<_,String>(0)?,"target_package":row.get::<_,String>(1)?,"relation":relation(&row.get::<_,String>(2)?),"count":row.get::<_,i64>(3)?})}else{json!({if section=="modules"{"module"}else{"package"}:row.get::<_,String>(0)?,"count":row.get::<_,i64>(1)?})});}Ok(output)
        })().map_err(|e|e.to_string())
    }
}
