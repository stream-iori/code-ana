//! Anatomist CallSitePersistence: bounded ordered grouping, strongest target evidence.
use anyhow::Result;
use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};

#[derive(Clone, PartialEq, Eq)]
struct Site {
    caller: String,
    symbol: String,
    module: String,
    scope: String,
    source: String,
    begin_line: i64,
    begin_column: i64,
    end_line: i64,
    end_column: i64,
    ordinal: i64,
    producer: Option<String>,
    language: Option<String>,
    provider: Option<String>,
}
#[derive(Clone)]
struct Target {
    id: Option<String>,
    external: Option<String>,
    symbol: Option<String>,
    language: Option<String>,
    provider: Option<String>,
    confidence: Option<String>,
    producer: Option<String>,
}
fn rank(confidence: Option<&str>) -> u8 {
    match confidence {
        Some("EXTRACTED") => 3,
        Some("INFERRED") => 2,
        Some("AMBIGUOUS") => 1,
        _ => 0,
    }
}
fn target_status(confidence: Option<&str>) -> &'static str {
    match confidence {
        Some("AMBIGUOUS") => "ambiguous",
        Some("INFERRED") => "heuristic",
        _ => "exact",
    }
}
struct Group {
    site: Site,
    context: Option<String>,
    syntax: Option<String>,
    receiver: Option<String>,
    dispatch: Option<String>,
    metadata: Option<String>,
    targets: Vec<Target>,
}
impl Group {
    fn add(&mut self, target: Target) {
        if let Some(old) = self.targets.iter_mut().find(|t| {
            t.id == target.id && t.provider == target.provider && t.external == target.external
        }) {
            if rank(target.confidence.as_deref()) > rank(old.confidence.as_deref()) {
                *old = target;
            }
        } else {
            self.targets.push(target);
        }
    }
    fn status(&self) -> &'static str {
        if self.targets.is_empty() {
            "unresolved"
        } else if self.targets.len() > 1
            || self
                .targets
                .iter()
                .any(|t| t.confidence.as_deref() == Some("AMBIGUOUS"))
        {
            "ambiguous"
        } else if self
            .targets
            .iter()
            .any(|t| t.confidence.as_deref() == Some("INFERRED"))
        {
            "heuristic"
        } else {
            "exact"
        }
    }
    fn hash(&self) -> Vec<u8> {
        let s = &self.site;
        let canonical = [
            s.provider.clone().unwrap_or_default(),
            s.language.clone().unwrap_or_default(),
            s.module.clone(),
            s.scope.clone(),
            s.source.clone(),
            s.symbol.clone(),
            s.begin_line.to_string(),
            s.begin_column.to_string(),
            s.end_line.to_string(),
            s.end_column.to_string(),
            s.ordinal.to_string(),
            s.producer.clone().unwrap_or_default(),
        ]
        .join("\n");
        Sha256::digest(canonical.as_bytes()).to_vec()
    }
    fn write(&self, db: &Connection, pk: i64) -> Result<()> {
        let s = &self.site;
        db.execute(
            "INSERT OR IGNORE INTO call_site_owners(caller_id,source_file) VALUES(?,?)",
            params![s.caller, s.source],
        )?;
        let owner: i64 = db.query_row(
            "SELECT owner_pk FROM call_site_owners WHERE caller_id=? AND source_file=?",
            params![s.caller, s.source],
            |r| r.get(0),
        )?;
        db.execute("INSERT INTO call_sites(site_pk,stable_hash,owner_pk,begin_line,begin_column,end_line,end_column,ordinal,context,syntax_target,receiver_static_type,dispatch_kind,metadata,origin,resolution_status,producer_id,language,provider_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'extracted',?,?,?,?)",
          params![pk,self.hash(),owner,s.begin_line,s.begin_column,s.end_line,s.end_column,s.ordinal,self.context,self.syntax,self.receiver,self.dispatch,self.metadata,self.status(),s.producer,s.language,s.provider])?;
        let mut statement=db.prepare_cached("INSERT INTO call_site_targets(call_site_pk,target_id,external_target_fqn,external_target_symbol,external_target_language,external_target_provider_id,resolution_status,confidence,producer_id) VALUES(?,?,?,?,?,?,?,?,?)")?;
        for t in &self.targets {
            statement.execute(params![
                pk,
                t.id,
                t.external,
                t.symbol,
                t.language,
                t.provider,
                target_status(t.confidence.as_deref()),
                t.confidence,
                t.producer
            ])?;
        }
        Ok(())
    }
}

pub fn rebuild(
    db: &Connection,
    affected: Option<&std::collections::BTreeSet<String>>,
    check: &dyn Fn() -> Result<()>,
) -> Result<()> {
    if affected.is_some() {
        db.execute("DELETE FROM call_site_owners WHERE source_file IN(SELECT source_file FROM graph_replaced)",[])?;
    } else {
        db.execute_batch(
            "DELETE FROM call_site_targets;DELETE FROM call_sites;DELETE FROM call_site_owners;",
        )?;
    }
    let sql="SELECT e.resolved_source,src.symbol_id,src.module,src.scope,e.source_file,
      e.begin_line,e.begin_column,e.end_line,e.end_column,coalesce(e.source_ordinal,0),
      e.context,e.syntax_target,e.receiver_static_type,e.call_kind,e.metadata,e.producer_id,
      e.resolved_target,e.external_target_fqn,e.confidence,e.language,e.provider_id,
      e.external_target_symbol,e.external_target_language,e.external_target_provider_id
      FROM (SELECT * FROM staged.stage_edges UNION ALL SELECT * FROM staged.stage_call_sites) e
      JOIN nodes src ON src.id=e.resolved_source WHERE e.relation='CALLS' AND e.resolved_source IS NOT NULL
      AND e.source_file IS NOT NULL AND e.begin_line IS NOT NULL AND e.begin_column IS NOT NULL
      AND e.end_line IS NOT NULL AND e.end_column IS NOT NULL
      ORDER BY e.source_file,e.begin_line,e.begin_column,e.end_line,e.end_column,e.resolved_source,
      coalesce(e.source_ordinal,0),e.producer_id,e.language,e.provider_id,e.seq";
    let sql = if affected.is_some() {
        sql.replace("AND e.source_file IS NOT NULL","AND e.source_file IS NOT NULL AND e.source_file IN(SELECT source_file FROM graph_replaced)")
    } else {
        sql.to_owned()
    };
    let mut statement = db.prepare(&sql)?;
    let mut rows = statement.query([])?;
    let mut current: Option<Group> = None;
    let mut pk: i64 = db.query_row(
        "SELECT coalesce(max(site_pk),0)+1 FROM call_sites",
        [],
        |r| r.get(0),
    )?;
    while let Some(row) = rows.next()? {
        let site = Site {
            caller: row.get(0)?,
            symbol: row.get(1)?,
            module: row.get(2)?,
            scope: row.get(3)?,
            source: row.get(4)?,
            begin_line: row.get(5)?,
            begin_column: row.get(6)?,
            end_line: row.get(7)?,
            end_column: row.get(8)?,
            ordinal: row.get(9)?,
            producer: row.get(15)?,
            language: row.get(19)?,
            provider: row.get(20)?,
        };
        if current.as_ref().is_some_and(|g| g.site != site) {
            current.take().unwrap().write(db, pk)?;
            pk += 1;
            if pk % 2048 == 0 {
                check()?;
            }
        }
        let context = row.get::<_, Option<String>>(10)?;
        let syntax = row.get::<_, Option<String>>(11)?;
        let receiver = row.get::<_, Option<String>>(12)?;
        let dispatch = row.get::<_, Option<String>>(13)?;
        let metadata = row.get::<_, Option<String>>(14)?;
        if let Some(g) = &mut current {
            if g.context.is_none() {
                g.context = context;
            }
            if g.syntax.is_none() {
                g.syntax = syntax;
            }
            if g.receiver.is_none() {
                g.receiver = receiver.clone();
            }
            if dispatch.as_deref() == Some("REFLECTION") {
                g.dispatch = dispatch;
                if receiver.is_some() {
                    g.receiver = receiver;
                }
                if metadata.is_some() {
                    g.metadata = metadata;
                }
            } else if g.metadata.is_none() {
                g.metadata = metadata;
            }
        } else {
            current = Some(Group {
                site,
                context,
                syntax,
                receiver,
                dispatch,
                metadata,
                targets: Vec::new(),
            });
        }
        let target = Target {
            id: row.get(16)?,
            external: row.get(17)?,
            symbol: row.get(21)?,
            language: row.get(22)?,
            provider: row.get(23)?,
            confidence: row.get(18)?,
            producer: row.get(15)?,
        };
        if target.id.is_some() || target.external.is_some() {
            current.as_mut().unwrap().add(target);
        }
    }
    if let Some(g) = current {
        g.write(db, pk)?;
    }
    check()?;
    Ok(())
}
