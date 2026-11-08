//! Independent comment staging and exact source/world validation. No semantic ownership inference.
use super::*;
use codeana_comment_search::SourcePositions;
use codeana_contracts::graph::{Row, Table, cell};

#[derive(Default)]
pub(crate) struct SourceCache {
    value: Option<(String, String, SourcePositions)>,
}
fn invalid(detail: impl Into<String>) -> anyhow::Error {
    DomainError::new(ErrorCode::CommentRecordInvalid, detail.into()).into()
}
fn text(row: &Row, n: usize) -> Result<&str> {
    match row.cells.get(n).and_then(|c| c.value.as_ref()) {
        Some(cell::Value::Text(value)) => Ok(value),
        _ => Err(invalid(format!("text column {n}"))),
    }
}
fn number(row: &Row, n: usize) -> Result<u64> {
    match row.cells.get(n).and_then(|c| c.value.as_ref()) {
        Some(cell::Value::Integer(value)) if *value >= 0 => Ok(*value as u64),
        _ => Err(invalid(format!("integer column {n}"))),
    }
}
fn range(row: &Row, at: usize) -> Result<CommentRange> {
    let coordinate = |n| u32::try_from(number(row, n)?).map_err(|_| invalid("coordinate overflow"));
    Ok(CommentRange {
        byte_start: number(row, at)?,
        byte_end: number(row, at + 1)?,
        begin_line: coordinate(at + 2)?,
        begin_column: coordinate(at + 3)?,
        end_line: coordinate(at + 4)?,
        end_column: coordinate(at + 5)?,
    })
}
fn language(name: &str) -> Result<AnalysisLanguage> {
    match name {
        "java" => Ok(AnalysisLanguage::Java),
        "rust" => Ok(AnalysisLanguage::Rust),
        "python" => Ok(AnalysisLanguage::Python),
        _ => Err(invalid("language")),
    }
}
fn validate_range(
    value: &CommentRange,
    source: &str,
    positions: &SourcePositions,
    admit: &dyn Fn(u64, u64) -> Result<(), DomainError>,
) -> Result<()> {
    ensure!(
        value.byte_start < value.byte_end && value.byte_end <= source.len() as u64,
        invalid("source range")
    );
    let begin = positions.position(source, value.byte_start as usize, admit)?;
    let end = positions.position(source, value.byte_end as usize, admit)?;
    ensure!(
        begin == (value.begin_line, value.begin_column)
            && end == (value.end_line, value.end_column),
        invalid("UTF-16 coordinates")
    );
    Ok(())
}

pub(crate) fn stage(
    db: &Connection,
    row: &Row,
    manifest: &Manifest,
    context: Option<&GraphStagingContext>,
    cache: &mut SourceCache,
    check: &dyn Fn() -> Result<()>,
) -> Result<()> {
    check()?;
    let context = context.context(DomainError::new(
        ErrorCode::CommentStagingContextRequired,
        "",
    ))?;
    let table = Table::try_from(row.table)?;
    let (path, module, scope) = if table == Table::Comment {
        (text(row, 1)?, text(row, 3)?, text(row, 4)?)
    } else {
        (text(row, 3)?, text(row, 4)?, text(row, 5)?)
    };
    ensure!(!row.world.is_empty(), invalid("explicit world required"));
    let unit = manifest
        .files
        .iter()
        .find(|s| s.path == path && s.world == row.world && s.module == module && s.scope == scope)
        .context(invalid("captured source identity"))?;
    ensure!(!unit.auxiliary, invalid("non-published comment source"));
    let budget = context
        .hosts
        .get(&language(&unit.language)?)
        .context(invalid("language account"))?;
    let admit = |work, bytes| budget.charge(work, 0, bytes).map_err(DomainError::analysis);
    admit(
        1,
        (codeana_contracts::stream::row_bytes(row) as u64).saturating_mul(2),
    )?;
    if cache
        .value
        .as_ref()
        .is_none_or(|(hash, _, _)| hash != &unit.hash)
    {
        admit(unit.size.saturating_add(1), unit.size.saturating_mul(2))?;
        let bytes = (context.source)(&unit.hash, unit.size)?;
        ensure!(
            bytes.len() as u64 == unit.size && hash(&bytes) == unit.hash,
            DomainError::new(ErrorCode::SourceHashMismatch, "comment capture")
        );
        let source = String::from_utf8(bytes).map_err(|_| invalid("source is not UTF-8"))?;
        let positions = SourcePositions::new(&source, &admit)?;
        cache.value = Some((unit.hash.clone(), source, positions));
    }
    let (_, source, positions) = cache.value.as_ref().context(invalid("source cache"))?;
    if table == Table::Comment {
        ensure!(row.cells.len() == 18, invalid("comment column count"));
        let kind = match text(row, 8)? {
            "line" => CommentKind::Line,
            "block" => CommentKind::Block,
            "doc" => CommentKind::Doc,
            "doc_attribute" => CommentKind::DocAttribute,
            "docstring" => CommentKind::Docstring,
            _ => return Err(invalid("comment kind")),
        };
        let map = text(row, 16)?;
        admit(1, (map.len() as u64).saturating_mul(8).saturating_add(128))?;
        let fact = CommentFact {
            id: text(row, 0)?.into(),
            source_file: path.into(),
            source_hash: text(row, 2)?.into(),
            module: module.into(),
            scope: scope.into(),
            world: row.world.clone().into(),
            language: text(row, 5)?.into(),
            provider: text(row, 6)?.into(),
            producer: text(row, 7)?.into(),
            kind,
            range: range(row, 9)?,
            body: text(row, 15)?.into(),
            body_map: serde_json::from_str(map)?,
            rule_version: text(row, 17)?.into(),
        };
        ensure!(
            fact.source_hash == unit.hash
                && fact.language == unit.language
                && !fact.provider.is_empty()
                && !fact.producer.is_empty()
                && !fact.rule_version.is_empty(),
            invalid("comment provenance")
        );
        ensure!(fact.id == fact.expected_id(), invalid("comment identity"));
        validate_range(&fact.range, source, positions, &admit)?;
        for segment in &fact.body_map {
            admit(1, 0)?;
            ensure!(
                segment.source_start >= fact.range.byte_start
                    && segment.source_end <= fact.range.byte_end,
                invalid("body map outside comment")
            );
        }
        codeana_comment_search::map_hits(&fact.body, &fact.body_map, source, &[], &admit)?;
        let terms = codeana_comment_search::index_terms(&fact.body, &admit)?;
        let bytes = terms.iter().map(|s| s.len() as u64 + 1).sum::<u64>();
        admit(1, bytes.saturating_mul(2))?;
        let terms = terms.join(" ");
        crate::graph_store::insert(db, row)?;
        db.execute(
            "UPDATE stage_comments SET terms=? WHERE id=?",
            params![terms, fact.id.as_str()],
        )?;
    } else {
        ensure!(row.cells.len() == 14, invalid("comment link column count"));
        validate_range(&range(row, 6)?, source, positions, &admit)?;
        let relation = text(row, 1)?;
        let basis = text(row, 12)?;
        let declaration = row.cells[2].value.as_ref();
        ensure!(
            declaration.is_none()
                || matches!(declaration,Some(cell::Value::Text(id)) if !id.is_empty()),
            invalid("declaration identity type")
        );
        ensure!(
            matches!(relation, "documents" | "enclosing_callable"),
            invalid("relation")
        );
        ensure!(basis == "language_rule", invalid("association basis"));
        ensure!(
            !text(row, 0)?.is_empty() && !text(row, 13)?.is_empty(),
            invalid("link identity/rule")
        );
        crate::graph_store::insert(db, row)?;
    }
    check()?;
    Ok(())
}

pub(crate) fn validate_baseline(db: &Connection) -> Result<()> {
    if db
        .prepare("SELECT 1 FROM graph_comments LIMIT 1")?
        .exists([])?
    {
        let actual: Option<String> = db
            .query_row(
                "SELECT value FROM project_meta WHERE key='codeana.comment_tokenizer'",
                [],
                |r| r.get(0),
            )
            .optional()?;
        ensure!(
            actual.as_deref() == Some(codeana_comment_search::identity().as_str()),
            DomainError::new(
                ErrorCode::GraphSchemaRebuildRequired,
                "comment tokenizer; run index --full"
            )
        );
    }
    Ok(())
}

fn validate_owners(
    db: &Connection,
    manifest: &Manifest,
    context: Option<&GraphStagingContext>,
    check: &dyn Fn() -> Result<()>,
) -> Result<()> {
    if !db
        .prepare("SELECT 1 FROM graph_comments LIMIT 1")?
        .exists([])?
    {
        return Ok(());
    }
    let context = context.context(DomainError::new(
        ErrorCode::CommentStagingContextRequired,
        "retained comments",
    ))?;
    db.execute_batch("CREATE TEMP TABLE captured_comment_sources(path TEXT,world TEXT,module TEXT,scope TEXT,language TEXT,hash TEXT,PRIMARY KEY(path,world)) WITHOUT ROWID;")?;
    let mut insert =
        db.prepare("INSERT OR REPLACE INTO captured_comment_sources VALUES(?,?,?,?,?,?)")?;
    for (n, source) in manifest
        .files
        .iter()
        .filter(|s| !s.auxiliary && !s.language.is_empty())
        .enumerate()
    {
        if n.is_multiple_of(256) {
            check()?;
        }
        let budget = context
            .hosts
            .get(&language(&source.language)?)
            .context(invalid("captured source account"))?;
        let bytes = source.path.len()
            + source.world.len()
            + source.module.len()
            + source.scope.len()
            + source.language.len()
            + source.hash.len()
            + 256;
        budget
            .charge(1, 0, (bytes as u64).saturating_mul(4))
            .map_err(DomainError::analysis)?;
        insert.execute(params![
            source.path,
            source.world,
            source.module,
            source.scope,
            source.language,
            source.hash
        ])?;
    }
    drop(insert);
    ensure!(!db.prepare("SELECT 1 FROM graph_comments c JOIN source_files f ON f.file_pk=c.source_file LEFT JOIN captured_comment_sources s ON s.path=f.path AND s.world=c.world AND s.module=c.module AND s.scope=c.scope AND s.language=c.language AND s.hash=c.source_hash WHERE s.path IS NULL LIMIT 1")?.exists([])?,invalid("published comment source/hash/world is not captured"));
    db.execute_batch("DROP TABLE captured_comment_sources")?;
    check()?;
    Ok(())
}

pub(crate) fn promote(
    db: &Connection,
    manifest: &Manifest,
    context: Option<&GraphStagingContext>,
    check: &dyn Fn() -> Result<()>,
) -> Result<()> {
    check()?;
    // Resolve only explicit references in the same frozen source instance. Never use a name.
    ensure!(!db.prepare("SELECT 1 FROM staged.stage_comment_links l LEFT JOIN staged.stage_comments c ON c.id=l.comment_id AND c.source_file=l.source_file AND c.module=l.module AND c.scope=l.scope AND c.world=l.world WHERE c.id IS NULL LIMIT 1")?.exists([])?,invalid("comment link owner missing"));
    ensure!(!db.prepare("SELECT 1 FROM staged.stage_comment_links l LEFT JOIN nodes n ON n.id=l.declaration_id AND n.source_file=l.source_file AND n.module=l.module AND n.scope=l.scope AND n.world=l.world WHERE l.declaration_id IS NOT NULL AND n.id IS NULL LIMIT 1")?.exists([])?,invalid("comment declaration source/world mismatch"));
    db.execute_batch("INSERT OR IGNORE INTO source_files(path) SELECT DISTINCT source_file FROM staged.stage_comments;
      INSERT INTO graph_comments(id,source_file,source_hash,module,scope,world,language,provider_id,producer_id,kind,byte_start,byte_end,begin_line,begin_column,end_line,end_column,body,body_map,rule_version,terms)
      SELECT c.id,f.file_pk,c.source_hash,c.module,c.scope,c.world,c.language,c.provider_id,c.producer_id,c.kind,c.byte_start,c.byte_end,c.begin_line,c.begin_column,c.end_line,c.end_column,c.body,c.body_map,c.rule_version,c.terms FROM staged.stage_comments c JOIN source_files f ON f.path=c.source_file;
      INSERT INTO graph_comment_links(comment_pk,relation,declaration_pk,byte_start,byte_end,begin_line,begin_column,end_line,end_column,basis,rule_version)
      SELECT c.comment_pk,l.relation,n.node_pk,l.byte_start,l.byte_end,l.begin_line,l.begin_column,l.end_line,l.end_column,l.basis,l.rule_version FROM staged.stage_comment_links l JOIN graph_comments c ON c.id=l.comment_id LEFT JOIN graph_nodes n ON n.id=l.declaration_id;")?;
    validate_owners(db, manifest, context, check)?;
    db.execute(
        "INSERT OR REPLACE INTO project_meta VALUES('codeana.comment_tokenizer',?)",
        [codeana_comment_search::identity()],
    )?;
    check()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_contracts::graph_rows::{CommentLinkRow, CommentRow, GraphRow};
    fn setup(store: &Store) -> (Revision, AnalysisHeader, GraphStagingContext, Row, Row) {
        let source = "// 库存😀HTTPServer2\r\nclass A {}";
        let body = " 库存😀HTTPServer2";
        let end = source.find('\r').unwrap() as u64;
        let source_hash = hash(source);
        store
            .put_blobs(&BTreeMap::from([(
                source_hash.clone(),
                source.as_bytes().to_vec(),
            )]))
            .unwrap();
        let mut rev = crate::tests::revision();
        rev.manifest.files.push(SourceUnit {
            path: "A.java".into(),
            hash: source_hash.clone(),
            size: source.len() as u64,
            module: "app".into(),
            scope: "MAIN".into(),
            world: "main".into(),
            language: "java".into(),
            auxiliary: false,
        });
        let mut result = crate::tests::result();
        result.manifest = rev.manifest.id();
        let range = CommentRange {
            byte_start: 0,
            byte_end: end,
            begin_line: 1,
            begin_column: 1,
            end_line: 1,
            end_column: source[..end as usize].encode_utf16().count() as u32 + 1,
        };
        let fact = CommentFact {
            id: "".into(),
            source_file: "A.java".into(),
            source_hash: source_hash.clone(),
            module: "app".into(),
            scope: "MAIN".into(),
            world: "main".into(),
            language: "java".into(),
            provider: "java-core".into(),
            producer: "java-core".into(),
            kind: CommentKind::Line,
            range: range.clone(),
            body: body.into(),
            body_map: vec![CommentBodySegment {
                body_start: 0,
                body_end: body.len() as u64,
                source_start: 2,
                source_end: end,
            }],
            rule_version: "java-comments-v2".into(),
        };
        let id = fact.expected_id();
        let comment = CommentRow {
            id: Some(id.to_string()),
            source_file: Some("A.java".into()),
            source_hash: Some(source_hash),
            module: Some("app".into()),
            scope: Some("MAIN".into()),
            language: Some("java".into()),
            provider_id: Some("java-core".into()),
            producer_id: Some("java-core".into()),
            kind: Some("line".into()),
            byte_start: Some(0),
            byte_end: Some(end as i64),
            begin_line: Some(1),
            begin_column: Some(1),
            end_line: Some(1),
            end_column: Some(range.end_column as i64),
            body: Some(body.into()),
            body_map: Some(serde_json::to_string(&fact.body_map).unwrap()),
            rule_version: Some("java-comments-v2".into()),
        }
        .into_row("main");
        let link = CommentLinkRow {
            comment_id: Some(id.to_string()),
            relation: Some("documents".into()),
            declaration_id: None,
            source_file: Some("A.java".into()),
            module: Some("app".into()),
            scope: Some("MAIN".into()),
            byte_start: Some(end as i64 + 2),
            byte_end: Some(source.len() as i64),
            begin_line: Some(2),
            begin_column: Some(1),
            end_line: Some(2),
            end_column: Some(11),
            basis: Some("language_rule".into()),
            rule_version: Some("java-comments-v2".into()),
        }
        .into_row("main");
        let bytes = source.as_bytes().to_vec();
        let context = GraphStagingContext {
            hosts: BTreeMap::from([(
                AnalysisLanguage::Java,
                AnalysisContext::new(Default::default(), None).unwrap(),
            )]),
            source: Arc::new(move |_, _| Ok(bytes.clone())),
        };
        (rev, result.header(), context, comment, link)
    }
    #[test]
    fn shared_path_world_removal_and_zero_output_replace_all_declared_source_instances()
    -> Result<()> {
        let dir = tempfile::tempdir()?;
        let store = Store::open(dir.path())?;
        let (mut rev, mut header, ctx, row, link) = setup(&store);
        let mut source = rev.manifest.files[0].clone();
        source.world = "test".into();
        rev.manifest.files.push(source);
        header.manifest = rev.manifest.id();
        let mut test_row = row.clone();
        test_row.world = "test".into();
        let id = key(
            "comment",
            &[
                text(&row, 7)?,
                text(&row, 5)?,
                text(&row, 3)?,
                text(&row, 4)?,
                "test",
                text(&row, 1)?,
                text(&row, 2)?,
                &number(&row, 9)?.to_string(),
                &number(&row, 10)?.to_string(),
                text(&row, 8)?,
            ],
        );
        test_row.cells[0].value = Some(cell::Value::Text(id.clone()));
        let mut test_link = link.clone();
        test_link.world = "test".into();
        test_link.cells[0].value = Some(cell::Value::Text(id));
        let fact: Fact = serde_json::from_value(
            serde_json::json!({"id":"A","signature":"A","kind":"declaration","source":"A.java","owner":{"world":"main","producer":"mock","kind":"source","id":"A.java"},"origin":"mock"}),
        )?;
        let mut nodes = codeana_contracts::fixture_graph::rows(&rev.manifest, &[fact]);
        for node in &mut nodes {
            node.world = "main".into();
        }
        let declaration = nodes
            .iter()
            .find(|n| n.table == Table::Node as i32)
            .context("missing node")?
            .cells[0]
            .value
            .clone();
        let mut wrong_link = test_link.clone();
        wrong_link.cells[2].value = declaration;
        nodes.extend([row.clone(), link.clone(), test_row.clone(), wrong_link]);
        let error = store
            .stage_graph_records_with_context(
                rev.clone(),
                &header,
                nodes.into_iter().map(Ok),
                "",
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )
            .err()
            .context("cross-world declaration was accepted")?;
        assert!(
            error.to_string().contains("source/world mismatch"),
            "{error:#}"
        );
        assert!(store.head("t")?.is_empty());
        let first = store.publish_staged(
            store.stage_graph_records_with_context(
                rev.clone(),
                &header,
                vec![row, link, test_row.clone(), test_link.clone()]
                    .into_iter()
                    .map(Ok),
                "",
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )?,
            "worlds",
        )?;
        let pinned = store.read(&first.id)?;
        let terms = codeana_comment_search::query_terms("库存", &|_, _| Ok(()))?;
        assert_eq!(
            pinned
                .scan_comments(&terms, &CommentFilter::default(), None, 10)?
                .records
                .len(),
            2
        );
        assert_eq!(
            pinned
                .scan_comments(
                    &terms,
                    &CommentFilter {
                        world: "main".into(),
                        ..Default::default()
                    },
                    None,
                    10
                )?
                .records
                .len(),
            1
        );
        rev.manifest.files.retain(|s| s.world == "test");
        header.mode = AnalysisMode::Delta;
        header.baseline = first.id.clone();
        header.manifest = rev.manifest.id();
        // Existing file replacement is atomic across all instances of a shared path.
        header.replacements = ["main", "test"]
            .into_iter()
            .map(|world| Owner {
                world: world.into(),
                producer: "java-core".into(),
                kind: "source".into(),
                id: "A.java".into(),
            })
            .collect();
        let delta = store.publish_staged(
            store.stage_graph_records_with_context(
                rev.clone(),
                &header,
                vec![test_row.clone(), test_link.clone()]
                    .into_iter()
                    .map(Ok),
                &first.id,
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )?,
            "remove-world",
        )?;
        let reader = store.read(&delta.id)?;
        let retained = reader
            .scan_comments(&terms, &CommentFilter::default(), None, 10)?
            .records;
        assert_eq!(retained.len(), 1);
        assert_eq!(retained[0].world, "test");
        header.mode = AnalysisMode::Full;
        header.replacements.clear();
        header.baseline.clear();
        let full = store.publish_staged(
            store.stage_graph_records_with_context(
                rev.clone(),
                &header,
                vec![test_row, test_link].into_iter().map(Ok),
                &delta.id,
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )?,
            "full-world",
        )?;
        assert_eq!(
            retained,
            store
                .read(&full.id)?
                .scan_comments(&terms, &CommentFilter::default(), None, 10)?
                .records
        );
        header.mode = AnalysisMode::Delta;
        header.baseline = full.id.clone();
        header.replacements = vec![Owner {
            world: "test".into(),
            producer: "java-core".into(),
            kind: "source".into(),
            id: "A.java".into(),
        }];
        let zero = store.publish_staged(
            store.stage_graph_records_with_context(
                rev,
                &header,
                std::iter::empty(),
                &full.id,
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )?,
            "zero-comments",
        )?;
        let empty = store.read(&zero.id)?;
        assert!(
            empty
                .scan_comments(&terms, &CommentFilter::default(), None, 10)?
                .records
                .is_empty()
        );
        assert!(empty.comment_links(&[retained[0].id.clone()])?.is_empty());
        assert_eq!(
            pinned
                .scan_comments(&terms, &CommentFilter::default(), None, 10)?
                .records
                .len(),
            2
        );
        Ok(())
    }

    #[test]
    fn tokenizer_identity_rejects_stale_comment_queries_and_full_rebuild_restores_them()
    -> Result<()> {
        let dir = tempfile::tempdir()?;
        let store = Store::open(dir.path())?;
        let (rev, header, ctx, row, link) = setup(&store);
        let first = store.publish_staged(
            store.stage_graph_records_with_context(
                rev.clone(),
                &header,
                vec![row.clone(), link.clone()].into_iter().map(Ok),
                "",
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )?,
            "tokenizer",
        )?;
        let reserved = codeana_contracts::fixture_graph::row(
            Table::ProjectMeta,
            vec![
                serde_json::json!("codeana.comment_tokenizer"),
                serde_json::json!("forged"),
            ],
        );
        assert!(
            store
                .stage_graph_records_with_context(
                    rev.clone(),
                    &header,
                    vec![row.clone(), reserved].into_iter().map(Ok),
                    &first.id,
                    || Ok(()),
                    |_| Ok(()),
                    Some(&ctx)
                )
                .is_err()
        );
        assert_eq!(store.head("t")?, first.id);
        // Emulate an older tokenizer identity as existing schema-compatibility tests do.
        let db = Connection::open(
            store
                .home
                .join("revisions")
                .join(&first.id)
                .join("facts.db"),
        )?;
        db.execute(
            "UPDATE project_meta SET value='older-tokenizer' WHERE key='codeana.comment_tokenizer'",
            [],
        )?;
        drop(db);
        let mut stale = header.clone();
        stale.mode = AnalysisMode::Delta;
        stale.baseline = first.id.clone();
        let error = store
            .stage_graph_records_with_context(
                rev.clone(),
                &stale,
                std::iter::empty(),
                &first.id,
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )
            .err()
            .context("stale tokenizer was silently reused")?;
        assert!(error.to_string().contains("GRAPH_SCHEMA_REBUILD_REQUIRED"));
        let terms = codeana_comment_search::query_terms("库存", &|_, _| Ok(()))?;
        let error = store
            .read(&first.id)?
            .scan_comments(&terms, &CommentFilter::default(), None, 10)
            .unwrap_err();
        assert_eq!(error.code, ErrorCode::GraphSchemaRebuildRequired);
        let full = store.publish_staged(
            store.stage_graph_records_with_context(
                rev,
                &header,
                vec![row, link].into_iter().map(Ok),
                &first.id,
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )?,
            "rebuild-tokenizer",
        )?;
        assert_eq!(
            store
                .read(&full.id)?
                .scan_comments(&terms, &CommentFilter::default(), None, 10)?
                .records
                .len(),
            1
        );
        Ok(())
    }

    #[test]
    fn comment_stream_disk_failure_and_cas_conflict_keep_the_published_head() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let store = Store::open(dir.path())?;
        let (rev, header, ctx, row, link) = setup(&store);
        let first = store.publish_staged(
            store.stage_graph_records_with_context(
                rev.clone(),
                &header,
                vec![row.clone(), link.clone()].into_iter().map(Ok),
                "",
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )?,
            "baseline",
        )?;
        let broken = vec![
            Ok(row.clone()),
            Err(DomainError::new(
                ErrorCode::GraphStreamIncomplete,
                "unsealed comment delivery",
            )
            .into()),
        ];
        let error = store
            .stage_graph_records_with_context(
                rev.clone(),
                &header,
                broken.into_iter(),
                &first.id,
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )
            .err()
            .context("incomplete comments were accepted")?;
        assert!(error.to_string().contains("GRAPH_STREAM_INCOMPLETE"));
        assert_eq!(store.head("t")?, first.id);
        let calls = std::cell::Cell::new(0);
        let error = store
            .stage_graph_records_with_context(
                rev.clone(),
                &header,
                vec![row.clone(), link.clone()].into_iter().map(Ok),
                &first.id,
                || Ok(()),
                |_| {
                    calls.set(calls.get() + 1);
                    ensure!(
                        calls.get() < 6,
                        DomainError::new(ErrorCode::DiskFull, "candidate quota exhausted")
                    );
                    Ok(())
                },
                Some(&ctx),
            )
            .err()
            .context("disk failure was ignored")?;
        assert!(error.to_string().contains("DISK_FULL"));
        assert_eq!(store.head("t")?, first.id);
        let a = store.stage_graph_records_with_context(
            rev.clone(),
            &header,
            vec![row.clone(), link.clone()].into_iter().map(Ok),
            &first.id,
            || Ok(()),
            |_| Ok(()),
            Some(&ctx),
        )?;
        let b = store.stage_graph_records_with_context(
            rev,
            &header,
            vec![row, link].into_iter().map(Ok),
            &first.id,
            || Ok(()),
            |_| Ok(()),
            Some(&ctx),
        )?;
        let winner = store.publish_staged(a, "winner")?;
        let error = store
            .publish_staged(b, "stale")
            .err()
            .context("CAS conflict was accepted")?;
        assert!(error.to_string().contains("HEAD_CONFLICT"));
        assert_eq!(store.head("t")?, winner.id);
        assert_eq!(std::fs::read_dir(store.home.join("candidates"))?.count(), 0);
        Ok(())
    }

    #[test]
    fn large_comment_body_is_admitted_from_lengths_before_fetching_text() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let store = Store::open(dir.path())?;
        let (mut rev, mut header, _, mut row, _) = setup(&store);
        let body = format!("库存{}", " x".repeat(40000));
        let source = format!("//{body}\nclass A {{}}");
        let source_hash = hash(&source);
        let end = source.find('\n').unwrap();
        store.put_blobs(&BTreeMap::from([(
            source_hash.clone(),
            source.as_bytes().to_vec(),
        )]))?;
        rev.manifest.files[0].hash = source_hash.clone();
        rev.manifest.files[0].size = source.len() as u64;
        header.manifest = rev.manifest.id();
        row.cells[2].value = Some(cell::Value::Text(source_hash.clone()));
        row.cells[10].value = Some(cell::Value::Integer(end as i64));
        row.cells[14].value = Some(cell::Value::Integer(
            source[..end].encode_utf16().count() as i64 + 1,
        ));
        row.cells[15].value = Some(cell::Value::Text(body.clone()));
        row.cells[16].value = Some(cell::Value::Text(serde_json::to_string(&vec![
            CommentBodySegment {
                body_start: 0,
                body_end: body.len() as u64,
                source_start: 2,
                source_end: end as u64,
            },
        ])?));
        row.cells[0].value = Some(cell::Value::Text(key(
            "comment",
            &[
                "java-core",
                "java",
                "app",
                "MAIN",
                "main",
                "A.java",
                &source_hash,
                "0",
                &end.to_string(),
                "line",
            ],
        )));
        let bytes = source.into_bytes();
        let context = GraphStagingContext {
            hosts: BTreeMap::from([(
                AnalysisLanguage::Java,
                AnalysisContext::new(
                    AnalysisLimits {
                        max_payload_bytes: 256 << 20,
                        ..Default::default()
                    },
                    None,
                )?,
            )]),
            source: Arc::new(move |_, _| Ok(bytes.clone())),
        };
        let published = store.publish_staged(
            store.stage_graph_records_with_context(
                rev,
                &header,
                std::iter::once(Ok(row)),
                "",
                || Ok(()),
                |_| Ok(()),
                Some(&context),
            )?,
            "large-body",
        )?;
        let budget = ResourceBudget::new(ResourceLimits {
            max_work: 100000,
            max_payload_bytes: 32768,
        });
        let reader = store.read(&published.id)?.with_budget(budget.clone());
        let terms = codeana_comment_search::query_terms("库存", &|_, _| Ok(()))?;
        let result = reader.scan_comments(&terms, &CommentFilter::default(), None, 1)?;
        assert!(result.records.is_empty());
        assert!(!result.complete);
        let limit = budget
            .usage()
            .exhausted
            .context("body bypassed payload admission")?;
        assert_eq!(limit.resource, "payload_bytes");
        assert!(limit.requested >= body.len() as u64 * 8);
        Ok(())
    }

    #[test]
    fn retained_comments_cannot_outlive_their_captured_owner_or_hash() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let store = Store::open(dir.path())?;
        let (rev, mut header, ctx, row, link) = setup(&store);
        let first = store.publish_staged(
            store.stage_graph_records_with_context(
                rev.clone(),
                &header,
                vec![row.clone(), link.clone()].into_iter().map(Ok),
                "",
                || Ok(()),
                |_| Ok(()),
                Some(&ctx),
            )?,
            "owner",
        )?;
        header.mode = AnalysisMode::Delta;
        header.baseline = first.id.clone();
        header.replacements.clear();
        let mut removed = rev.clone();
        removed.manifest.files.clear();
        header.manifest = removed.manifest.id();
        assert!(
            store
                .stage_graph_records_with_context(
                    removed,
                    &header,
                    std::iter::empty(),
                    &first.id,
                    || Ok(()),
                    |_| Ok(()),
                    Some(&ctx)
                )
                .is_err()
        );
        let mut drift = rev.clone();
        drift.manifest.files[0].hash = hash("changed capture");
        header.manifest = drift.manifest.id();
        assert!(
            store
                .stage_graph_records_with_context(
                    drift,
                    &header,
                    std::iter::empty(),
                    &first.id,
                    || Ok(()),
                    |_| Ok(()),
                    Some(&ctx)
                )
                .is_err()
        );
        let mut auxiliary = rev.clone();
        auxiliary.manifest.files[0].auxiliary = true;
        header.mode = AnalysisMode::Full;
        header.manifest = auxiliary.manifest.id();
        assert!(
            store
                .stage_graph_records_with_context(
                    auxiliary,
                    &header,
                    std::iter::once(Ok(row.clone())),
                    &first.id,
                    || Ok(()),
                    |_| Ok(()),
                    Some(&ctx)
                )
                .is_err()
        );
        header.manifest = rev.manifest.id();
        let mut malformed = link;
        malformed.cells[2].value = Some(cell::Value::Integer(1));
        assert!(
            store
                .stage_graph_records_with_context(
                    rev,
                    &header,
                    vec![row, malformed].into_iter().map(Ok),
                    &first.id,
                    || Ok(()),
                    |_| Ok(()),
                    Some(&ctx)
                )
                .is_err()
        );
        assert_eq!(store.head("t")?, first.id);
        Ok(())
    }

    #[test]
    fn public_comment_queries_project_real_declarations_and_feed_pipeline() -> Result<()> {
        let dir = tempfile::tempdir()?;
        let store = Store::open(dir.path())?;
        let (rev, header, ctx, row, mut link) = setup(&store);
        let fact: Fact = serde_json::from_value(
            serde_json::json!({"id":"typeA","signature":"A","name":"A","kind":"declaration","source":"A.java","owner":{"world":"main","producer":"mock","kind":"source","id":"A.java"},"origin":"mock"}),
        )?;
        let mut nodes = codeana_contracts::fixture_graph::rows(&rev.manifest, &[fact]);
        let declaration = nodes
            .iter()
            .find(|r| r.table == Table::Node as i32)
            .unwrap()
            .cells[0]
            .value
            .clone();
        for node in &mut nodes {
            node.world = "main".into();
        }
        link.cells[2].value = declaration;
        nodes.extend([row, link]);
        let candidate = store.stage_graph_records_with_context(
            rev,
            &header,
            nodes.into_iter().map(Ok),
            "",
            || Ok(()),
            |_| Ok(()),
            Some(&ctx),
        )?;
        let published = store.publish_staged(candidate, "query")?;
        let reader = store.read(&published.id)?;
        let source = |unit: &SourceUnit| store.blob_bounded(&unit.hash, unit.size);
        let request = Request {
            operation: Operation::SearchComments,
            selector: "库存 HTTPServer2".into(),
            ..Default::default()
        };
        let out = codeana_query::run(&reader, &request, &source)?;
        assert_eq!(out.records.len(), 1);
        let Record::Comment(hit) = &out.records[0] else {
            panic!("not a typed comment")
        };
        assert_eq!(hit.comment.body, " 库存😀HTTPServer2");
        assert!(!hit.hits.is_empty());
        assert!(
            hit.hits.iter().all(
                |h| h.source.byte_start >= 2 && h.source.byte_end <= hit.comment.range.byte_end
            )
        );
        let code = codeana_query::run(
            &reader,
            &Request {
                operation: Operation::SearchCode,
                search_in: SearchIn::Comments,
                ..request.clone()
            },
            &source,
        )?;
        assert_eq!(code.records.len(), 1);
        let Record::Fact(declaration) = &code.records[0] else {
            panic!("not a declaration")
        };
        assert_eq!(declaration.comment_hits.len(), 1);
        assert_eq!(declaration.fact.signature, "A");
        let frozen = serde_json::to_vec(&code.records[0])?;
        let replay: Record = serde_json::from_slice(&frozen)?;
        assert_eq!(replay.wire()?, code.records[0].wire()?);
        let pipeline = codeana_query::run(
            &reader,
            &Request {
                operation: Operation::Pipeline,
                stages: vec![
                    Stage {
                        operation: Operation::SearchComments,
                        selector: request.selector.clone(),
                        ..Default::default()
                    },
                    Stage {
                        operation: Operation::Resolve,
                        ..Default::default()
                    },
                ],
                ..request.clone()
            },
            &source,
        )?;
        assert_eq!(pipeline.records.len(), 1);
        assert!(
            codeana_query::run(
                &reader,
                &Request {
                    selector: "库存 absentToken".into(),
                    ..request.clone()
                },
                &source
            )?
            .records
            .is_empty()
        );
        assert!(
            codeana_query::run(
                &reader,
                &Request {
                    selector: "!!!".into(),
                    ..request
                },
                &source
            )
            .is_err()
        );
        Ok(())
    }
    #[test]
    fn comments_publish_search_page_and_reject_invalid_evidence_without_switching_head()
    -> Result<()> {
        let dir = tempfile::tempdir()?;
        let store = Store::open(dir.path())?;
        let (rev, header, ctx, row, link) = setup(&store);
        let candidate = store.stage_graph_records_with_context(
            rev.clone(),
            &header,
            vec![row.clone(), link.clone()].into_iter().map(Ok),
            "",
            || Ok(()),
            |_| Ok(()),
            Some(&ctx),
        )?;
        let first = store.publish_staged(candidate, "comments")?;
        let read = store.read(&first.id)?;
        let terms = codeana_comment_search::query_terms("库存 HTTPServer2", &|_, _| Ok(()))?;
        let page = read.scan_comments(&terms, &CommentFilter::default(), None, 1)?;
        assert_eq!(page.records.len(), 1);
        assert!(!page.complete);
        let fact = &page.records[0];
        assert_eq!(fact.body, " 库存😀HTTPServer2");
        assert_eq!(read.comment_links(std::slice::from_ref(&fact.id))?.len(), 1);
        let after = CommentOrderKey {
            source_file: fact.source_file.clone(),
            byte_start: fact.range.byte_start,
            byte_end: fact.range.byte_end,
            world: fact.world.clone(),
            id: fact.id.clone(),
        };
        assert!(
            read.scan_comments(&terms, &CommentFilter::default(), Some(&after), 1)?
                .records
                .is_empty()
        );
        assert!(
            read.scan_comments(
                &terms,
                &CommentFilter {
                    world: "other".into(),
                    ..Default::default()
                },
                None,
                1
            )?
            .records
            .is_empty()
        );
        for index in [0, 2, 10, 16] {
            let mut bad = row.clone();
            bad.cells[index].value = Some(if index == 10 {
                cell::Value::Integer(5)
            } else {
                cell::Value::Text("invalid".into())
            });
            assert!(
                store
                    .stage_graph_records_with_context(
                        rev.clone(),
                        &header,
                        std::iter::once(Ok(bad)),
                        &first.id,
                        || Ok(()),
                        |_| Ok(()),
                        Some(&ctx)
                    )
                    .is_err()
            );
            assert_eq!(store.head("t")?, first.id);
        }
        let mut wrong = link;
        wrong.world = "other".into();
        assert!(
            store
                .stage_graph_records_with_context(
                    rev.clone(),
                    &header,
                    vec![row.clone(), wrong].into_iter().map(Ok),
                    &first.id,
                    || Ok(()),
                    |_| Ok(()),
                    Some(&ctx)
                )
                .is_err()
        );
        assert!(
            store
                .stage_graph_records(
                    rev.clone(),
                    &header,
                    std::iter::once(Ok(row)),
                    &first.id,
                    || Ok(()),
                    |_| Ok(())
                )
                .is_err()
        );
        let candidate = store.stage_graph_records_with_context(
            rev,
            &header,
            std::iter::empty(),
            &first.id,
            || Ok(()),
            |_| Ok(()),
            Some(&ctx),
        )?;
        let second = store.publish_staged(candidate, "empty")?;
        assert!(
            store
                .read(&second.id)?
                .scan_comments(&terms, &CommentFilter::default(), None, 10)?
                .records
                .is_empty()
        );
        assert_eq!(
            read.scan_comments(&terms, &CommentFilter::default(), None, 10)?
                .records
                .len(),
            1
        );
        Ok(())
    }
}
