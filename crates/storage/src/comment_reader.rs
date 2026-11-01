//! Ordered, revision-pinned reads of independent comment evidence.
use super::*;
use rusqlite::{Row, params_from_iter};

/// Scope the VM charge to comment SQL, including FTS enumeration and ORDER BY before LIMIT.
struct SqlBudget<'a>(&'a Connection);
impl Drop for SqlBudget<'_> {
    fn drop(&mut self) {
        let _ = self.0.progress_handler(0, None::<fn() -> bool>);
    }
}
fn range(row: &Row<'_>) -> Result<CommentRange> {
    Ok(CommentRange {
        byte_start: row.get::<_, i64>("byte_start")?.try_into()?,
        byte_end: row.get::<_, i64>("byte_end")?.try_into()?,
        begin_line: row.get("begin_line")?,
        begin_column: row.get("begin_column")?,
        end_line: row.get("end_line")?,
        end_column: row.get("end_column")?,
    })
}
fn invalid() -> DomainError {
    DomainError::new(ErrorCode::CommentRecordInvalid, "stored evidence")
}
/// Only the frozen fields needed for availability; no config/backend dependency.
#[derive(serde::Deserialize)]
struct StoredInputUnit {
    mode: String,
    language: String,
    roots: Vec<String>,
    entries: Vec<String>,
    #[serde(default)]
    modules: Vec<String>,
}
fn fact(row: &Row<'_>) -> Result<CommentFact> {
    let kind: String = row.get("kind")?;
    Ok(CommentFact {
        id: row.get::<_, String>("id")?.into(),
        source_file: row.get("source_file")?,
        source_hash: row.get("source_hash")?,
        module: row.get("module")?,
        scope: row.get("scope")?,
        world: row.get::<_, String>("world")?.into(),
        language: row.get("language")?,
        provider: row.get("provider_id")?,
        producer: row.get("producer_id")?,
        kind: match kind.as_str() {
            "line" => CommentKind::Line,
            "block" => CommentKind::Block,
            "doc" => CommentKind::Doc,
            "doc_attribute" => CommentKind::DocAttribute,
            "docstring" => CommentKind::Docstring,
            _ => return Err(invalid().into()),
        },
        range: range(row)?,
        body: row.get("body")?,
        body_map: serde_json::from_str(row.get_ref("body_map")?.as_str()?)?,
        rule_version: row.get("rule_version")?,
    })
}
impl ReadContext {
    /// The owner supplies its existing task token; SQL does not create a new cancellation state.
    pub fn with_cancellation(mut self, check: Arc<CancellationCheck>) -> Self {
        self.cancellation = Some(check);
        self
    }
    fn comment_cancelled(&self) -> bool {
        self.cancellation.as_ref().is_some_and(|check| check())
    }
    fn comment_read_error(&self, error: anyhow::Error) -> DomainError {
        if self.comment_cancelled() {
            DomainError::new(ErrorCode::Cancelled, "")
        } else if self.resource_exhausted() {
            ResourceBudget::error()
        } else {
            codeana_contracts::read_error(error)
        }
    }
    fn comment_sql_budget(&self) -> Result<SqlBudget<'_>> {
        let budget = self.resource_budget().cloned();
        let cancellation = self.cancellation.clone();
        if budget.is_some() || cancellation.is_some() {
            self.connection.progress_handler(
                128,
                Some(move || {
                    cancellation.as_ref().is_some_and(|check| check())
                        || budget
                            .as_ref()
                            .is_some_and(|budget| budget.charge(128, 0).is_err())
                }),
            )?;
        }
        Ok(SqlBudget(&self.connection))
    }
    fn admit_comment_payload(&self, bytes: i64) -> Result<()> {
        let bytes = u64::try_from(bytes).map_err(|_| invalid())?;
        self.charge(bytes.saturating_add(4), bytes.saturating_mul(8))?;
        Ok(())
    }
    fn check_comment_kernel(&self) -> Result<()> {
        let identity: Option<String> = self
            .connection
            .query_row(
                "SELECT value FROM project_meta WHERE key='codeana.comment_tokenizer'",
                [],
                |r| r.get(0),
            )
            .optional()?;
        ensure!(
            identity.as_deref() == Some(codeana_comment_search::identity().as_str()),
            DomainError::new(
                ErrorCode::GraphSchemaRebuildRequired,
                "comment tokenizer; run index --full"
            )
        );
        Ok(())
    }
    fn comments_scan(
        &self,
        terms: &[String],
        filter: &CommentFilter,
        after: Option<&CommentOrderKey>,
        limit: usize,
        output: &mut Vec<CommentFact>,
    ) -> Result<()> {
        ensure!(
            limit > 0 && limit <= MAX_FACTS + 1,
            DomainError::new(ErrorCode::InvalidScanBatch, "")
        );
        self.charge(
            terms.len() as u64 + filter.paths.len() as u64 + 1,
            terms
                .iter()
                .chain(filter.paths.iter())
                .map(|s| s.len() as u64 * 4 + 128)
                .sum(),
        )?;
        let _sql_budget = self.comment_sql_budget()?;
        self.check_comment_kernel()?;
        ensure!(
            !terms.is_empty(),
            DomainError::new(ErrorCode::CommentQueryEmpty, "")
        );
        // Quoted literals: user punctuation never becomes FTS operators or SQL.
        let query = terms
            .iter()
            .map(|s| format!("\"{}\"", s.replace('"', "\"\"")))
            .collect::<Vec<_>>()
            .join(" AND ");
        let mut predicate = String::from("comment_terms MATCH ?");
        let mut values = vec![query];
        for (column, value) in [
            ("module", filter.module.as_str()),
            ("scope", filter.scope.as_str()),
            ("world", filter.world.as_str()),
        ] {
            if !value.is_empty() {
                predicate.push_str(&format!(" AND c.{column}=?"));
                values.push(value.into());
            }
        }
        if !filter.paths.is_empty() {
            predicate.push_str(" AND c.source_file IN (SELECT value FROM json_each(?))");
            values.push(serde_json::to_string(&filter.paths)?);
        }
        if let Some(key) = after {
            self.charge(
                1,
                256 + (key.source_file.len() + key.world.len() + key.id.len()) as u64 * 2,
            )?;
            predicate
                .push_str(" AND (c.source_file,c.byte_start,c.byte_end,c.world,c.id)>(?,?,?,?,?)");
            values.extend([
                key.source_file.clone(),
                key.byte_start.to_string(),
                key.byte_end.to_string(),
                key.world.to_string(),
                key.id.to_string(),
            ]);
        }
        values.push(limit.to_string());
        // Sort only bounded keys and lengths; never copy bodies into SQLite's sorter.
        self.charge(
            1,
            (limit as u64)
                .saturating_mul(self.comment_sort_key_bytes)
                .saturating_mul(4),
        )?;
        let payload = "256+octet_length(c.id)+octet_length(c.source_file)+octet_length(c.source_hash)+octet_length(c.module)+octet_length(c.scope)+octet_length(c.world)+octet_length(c.language)+octet_length(c.provider_id)+octet_length(c.producer_id)+octet_length(c.kind)+octet_length(c.body)+octet_length(c.body_map)+octet_length(c.rule_version)";
        let sql = format!(
            "SELECT c.id,{payload} AS payload_bytes FROM comment_terms JOIN graph_comments g ON g.comment_pk=comment_terms.rowid JOIN comments c ON c.id=g.id WHERE {predicate} ORDER BY c.source_file,c.byte_start,c.byte_end,c.world,c.id LIMIT ?"
        );
        let mut detail = self
            .connection
            .prepare_cached("SELECT * FROM comments WHERE id=?")?;
        let mut stmt = self.connection.prepare(&sql)?;
        let mut rows = stmt.query(params_from_iter(values))?;
        while let Some(row) = rows.next()? {
            // octet_length(column) reads record lengths without loading large text values.
            self.admit_comment_payload(row.get("payload_bytes")?)?;
            let id = row.get_ref("id")?.as_str()?;
            let mut data = detail.query([id])?;
            let value = data.next()?.context(invalid())?;
            output.push(fact(value)?);
        }
        Ok(())
    }
}
impl CommentReader for ReadContext {
    fn unavailable_comment_inputs(
        &self,
    ) -> std::result::Result<Vec<UnavailableCommentInput>, DomainError> {
        (|| -> Result<_> {
            ensure!(!self.comment_cancelled(), DomainError::new(ErrorCode::Cancelled, ""));
            let _sql_budget = self.comment_sql_budget()?;
            // Admit JSON before decoding; keys are indexed, ordinary revisions return no rows.
            let mut metadata = self.connection.prepare(
                "SELECT key,octet_length(value)+octet_length(key) FROM project_meta WHERE key GLOB 'inputs.unit.*' ORDER BY key")?;
            let mut detail = self.connection.prepare_cached("SELECT value FROM project_meta WHERE key=?")?;
            let mut rows = metadata.query([])?;
            let mut result = Vec::new();
            while let Some(row) = rows.next()? {
                ensure!(!self.comment_cancelled(), DomainError::new(ErrorCode::Cancelled, ""));
                self.admit_comment_payload(row.get(1)?)?;
                let key: String = row.get(0)?;
                let value: String = detail.query_row([key], |r| r.get(0))?;
                let unit: StoredInputUnit = serde_json::from_str(&value)?;
                match unit.mode.as_str() {
                    "semantic" | "source_only" => continue,
                    "files_only" => (),
                    _ => return Err(invalid().into()),
                }
                ensure!(!unit.roots.is_empty() && !unit.language.is_empty(), invalid());
                for root in &unit.roots {
                    ensure!(!root.is_empty() && std::path::Path::new(root).components().all(|c|
                        matches!(c, std::path::Component::Normal(_) | std::path::Component::CurDir)), invalid());
                }
                let modules = if unit.modules.is_empty() { unit.entries } else { unit.modules };
                ensure!(!modules.is_empty(), invalid());
                let mut scopes = Vec::new();
                let mut coverage = self.connection.prepare_cached(
                    "SELECT scope,world,128+octet_length(scope)+octet_length(world) FROM analysis_coverage WHERE provider_id='codeana-inputs' AND capability='AGGREGATE' AND status<>'complete' AND module=? AND language=? ORDER BY scope,world")?;
                for module in modules {
                    let mut matches = coverage.query(rusqlite::params![module,unit.language])?;
                    let before = scopes.len();
                    while let Some(row) = matches.next()? {
                        self.admit_comment_payload(row.get::<_, i64>(2)?.saturating_add(module.len() as i64))?;
                        scopes.push(CommentInputScope { module: module.clone(), scope: row.get(0)?, world: row.get::<_, String>(1)?.into() });
                    }
                    ensure!(scopes.len()>before, invalid());
                }
                result.push(UnavailableCommentInput { language: unit.language, roots: unit.roots, scopes });
            }
            Ok(result)
        })().map_err(|error| self.comment_read_error(error))
    }
    fn scan_comments(
        &self,
        terms: &[String],
        filter: &CommentFilter,
        after: Option<&CommentOrderKey>,
        limit: usize,
    ) -> std::result::Result<BoundedRead<CommentFact>, DomainError> {
        if self.comment_cancelled() {
            return Err(DomainError::new(ErrorCode::Cancelled, ""));
        }
        let mut records = Vec::new();
        if !self.resource_exhausted()
            && let Err(error) = self.comments_scan(terms, filter, after, limit, &mut records)
        {
            let error = self.comment_read_error(error);
            if error.code != ErrorCode::ComputationLimit {
                return Err(error);
            }
        }
        let resource_limit = self.resource_budget().and_then(|b| b.usage().exhausted);
        Ok(BoundedRead {
            complete: records.len() < limit && resource_limit.is_none(),
            records,
            resource_limit,
        })
    }
    fn comment_links(
        &self,
        ids: &[CommentId],
    ) -> std::result::Result<Vec<CommentLink>, DomainError> {
        (|| -> Result<_> {
            ensure!(!self.comment_cancelled(),DomainError::new(ErrorCode::Cancelled,""));
            ensure!(ids.len()<=MAX_FACTS+1,DomainError::new(ErrorCode::InvalidScanBatch,""));
            self.charge(ids.len() as u64+1,ids.iter().map(|s|s.len() as u64*4+128).sum())?;
            let _sql_budget=self.comment_sql_budget()?;
            let encoded=serde_json::to_string(ids)?;
            let mut stmt=self.connection.prepare("SELECT l.link_pk,256+octet_length(c.id)+octet_length(l.relation)+coalesce(octet_length(n.id),0)+octet_length(l.basis)+octet_length(l.rule_version) AS payload_bytes FROM graph_comment_links l JOIN graph_comments c ON c.comment_pk=l.comment_pk LEFT JOIN graph_nodes n ON n.node_pk=l.declaration_pk WHERE c.id IN (SELECT value FROM json_each(?))")?;
            let mut detail=self.connection.prepare_cached("SELECT c.id AS comment_id,l.relation,n.id AS declaration_id,l.byte_start,l.byte_end,l.begin_line,l.begin_column,l.end_line,l.end_column,l.basis,l.rule_version FROM graph_comment_links l JOIN graph_comments c ON c.comment_pk=l.comment_pk LEFT JOIN graph_nodes n ON n.node_pk=l.declaration_pk WHERE l.link_pk=?")?;
            let mut rows=stmt.query([encoded])?;
            let mut output=Vec::new();
            let mut payload_bytes=0u64;
            while let Some(metadata)=rows.next()? {
                let bytes:i64=metadata.get("payload_bytes")?;
                self.admit_comment_payload(bytes)?;
                payload_bytes=payload_bytes.saturating_add(bytes as u64);
                let mut values=detail.query([metadata.get::<_,i64>("link_pk")?])?;
                let row=values.next()?.context(invalid())?;
                let relation:String=row.get("relation")?;
                let basis:String=row.get("basis")?;
                output.push(CommentLink{comment:row.get::<_,String>("comment_id")?.into(),
                    relation:match relation.as_str(){"documents"=>CommentRelation::Documents,"enclosing_callable"=>CommentRelation::EnclosingCallable,
                        "leading_statement"=>CommentRelation::LeadingStatement,"trailing_statement"=>CommentRelation::TrailingStatement,_=>return Err(invalid().into())},
                    declaration:row.get::<_,Option<String>>("declaration_id")?.map(Into::into),range:range(row)?,
                    basis:match basis.as_str(){"language_rule"=>CommentBasis::LanguageRule,"heuristic"=>CommentBasis::Heuristic,_=>return Err(invalid().into())},
                    rule_version:row.get("rule_version")?});
            }
            self.charge(payload_bytes.saturating_mul(output.len().checked_ilog2().unwrap_or(0) as u64+1),0)?;
            output.sort_unstable_by(|a,b|(&a.comment,a.relation.as_str(),&a.declaration,a.range.byte_start,a.range.byte_end,a.basis.as_str(),&a.rule_version)
                .cmp(&(&b.comment,b.relation.as_str(),&b.declaration,b.range.byte_start,b.range.byte_end,b.basis.as_str(),&b.rule_version)));
            Ok(output)
        })().map_err(|error|self.comment_read_error(error))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn prepared_comment_gaps_use_frozen_roots_and_the_existing_account() -> Result<()> {
        use codeana_contracts::graph_rows::{CoverageRow, GraphRow, ProjectMetaRow};
        let directory = tempfile::tempdir()?;
        let store = Store::open(directory.path())?;
        let revision = crate::tests::revision();
        let mut result = crate::tests::result();
        result.manifest = revision.manifest.id();
        let rows = [
            ProjectMetaRow {
                key: Some("inputs.unit.unavailable".into()),
                value: Some(
                    serde_json::json!({"mode":"files_only", "language":"python",
                    "entries":["logical-module"], "roots":["physical-root"],
                    "reason":"captured preparation failure"})
                    .to_string(),
                ),
            }
            .into_row(""),
            CoverageRow {
                source_file: Some("*".into()),
                module: Some("logical-module".into()),
                scope: Some("MAIN".into()),
                language: Some("python".into()),
                provider_id: Some("codeana-inputs".into()),
                capability: Some("AGGREGATE".into()),
                status: Some("partial".into()),
                occurrences: Some(1),
                groups_count: Some(1),
                codes: Some("[\"INPUT_CONTEXT_UNAVAILABLE\"]".into()),
                code_counts: Some("{\"INPUT_CONTEXT_UNAVAILABLE\":1}".into()),
                details_truncated: Some(0),
            }
            .into_row("availability-world"),
        ];
        let candidate = store.stage_graph_records(
            revision,
            &result.header(),
            rows.into_iter().map(Ok),
            "",
            || Ok(()),
            |_| Ok(()),
        )?;
        let published = store.publish_staged(candidate, "gap")?;
        let read = store.read(&published.id)?;
        let gaps = read.unavailable_comment_inputs()?;
        assert_eq!(gaps.len(), 1);
        assert_eq!(gaps[0].roots, ["physical-root"]);
        assert_eq!(gaps[0].scopes[0].module, "logical-module");
        assert_eq!(gaps[0].scopes[0].world, "availability-world");
        let budget = ResourceBudget::new(ResourceLimits {
            max_work: 1_000_000,
            max_payload_bytes: 1,
        });
        let limited = store.read(&published.id)?.with_budget(budget.clone());
        assert_eq!(
            limited.unavailable_comment_inputs().unwrap_err().code,
            ErrorCode::ComputationLimit
        );
        assert!(budget.usage().exhausted.is_some());
        let cancelled = store
            .read(&published.id)?
            .with_cancellation(Arc::new(|| true));
        assert_eq!(
            cancelled.unavailable_comment_inputs().unwrap_err().code,
            ErrorCode::Cancelled
        );
        Ok(())
    }
    #[test]
    fn sqlite_work_is_charged_before_returned_rows_and_the_hook_is_scoped() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let store = Store::open(directory.path())?;
        let revision = crate::tests::revision();
        let mut result = crate::tests::result();
        result.manifest = revision.manifest.id();
        let candidate = store.stage_graph_records(
            revision,
            &result.header(),
            std::iter::empty(),
            "",
            || Ok(()),
            |_| Ok(()),
        )?;
        let published = store.publish_staged(candidate, "sql-budget")?;
        let budget = ResourceBudget::new(ResourceLimits {
            max_work: 256,
            max_payload_bytes: 1024,
        });
        let read = store.read(&published.id)?.with_budget(budget.clone());
        {
            let _sql = read.comment_sql_budget()?;
            let result=read.connection.query_row("WITH RECURSIVE n(x) AS (VALUES(0) UNION ALL SELECT x+1 FROM n WHERE x<1000000) SELECT sum(x) FROM n",[],|r|r.get::<_,i64>(0));
            assert!(result.is_err());
            let exhausted = budget
                .usage()
                .exhausted
                .context("SQL ran outside the task account")?;
            assert_eq!(exhausted.resource, "work");
            assert_eq!(exhausted.requested, 128);
        }
        assert_eq!(
            read.connection
                .query_row("SELECT 1", [], |r| r.get::<_, i64>(0))?,
            1
        );
        // No replacement ledger: a task token can interrupt SQL even with no work budget.
        let checks = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let token = checks.clone();
        let cancelled = store
            .read(&published.id)?
            .with_cancellation(Arc::new(move || {
                token.fetch_add(1, std::sync::atomic::Ordering::SeqCst) >= 2
            }));
        {
            let _sql = cancelled.comment_sql_budget()?;
            let error=cancelled.connection.query_row("WITH RECURSIVE n(x) AS (VALUES(0) UNION ALL SELECT x+1 FROM n WHERE x<1000000) SELECT sum(x) FROM n",[],|r|r.get::<_,i64>(0)).unwrap_err();
            assert_eq!(
                cancelled.comment_read_error(error.into()).code,
                ErrorCode::Cancelled
            );
            assert!(checks.load(std::sync::atomic::Ordering::SeqCst) >= 3);
            assert!(!cancelled.resource_exhausted());
        }

        Ok(())
    }
}
