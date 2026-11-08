//! Comment search uses only the narrow evidence port and the fixed revision source reader.
use super::*;

pub(super) fn run(
    graph: &dyn GraphReader,
    req: &Request,
    read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
) -> Result<Output> {
    let selected = selector_filter(graph, req)?;
    let mut evidence = selection_evidence(graph, req);
    let mut coverage_filter = scope_filter(req);
    coverage_filter.kinds = vec![FactKind::Coverage];
    let coverage = graph.select(&coverage_filter)?;
    graph.charge(
        coverage.len() as u64 * (coverage.len().checked_ilog2().unwrap_or(0) as u64 + 1),
        64 * coverage.len() as u64,
    )?;
    let complete = coverage
        .iter()
        .filter(|c| {
            c.attributes
                .capability
                .as_ref()
                .is_some_and(|value| value == "COMMENTS")
                && c.status == "complete"
        })
        .map(|c| (c.source.as_str(), c.owner.world.as_str()))
        .collect::<BTreeSet<_>>();
    for source in &graph.manifest().files {
        graph.charge(
            1 + complete.len().checked_ilog2().unwrap_or(0) as u64
                + selected.sources.len().checked_ilog2().unwrap_or(0) as u64,
            0,
        )?;
        if source.auxiliary
            || source.language.is_empty()
            || (!req.module.is_empty() && source.module != req.module)
            || (!req.world.is_empty() && source.world != req.world)
            || (!req.scope.is_empty() && req.scope != "ALL" && source.scope != req.scope)
            || selected
                .source
                .as_ref()
                .is_some_and(|path| path != &source.path)
            || (!selected.sources.is_empty()
                && selected.sources.binary_search(&source.path).is_err())
        {
            continue;
        }
        if !complete.contains(&(source.path.as_str(), source.world.as_str())) {
            graph.charge(1, 256 + (source.world.len() + source.path.len()) as u64 * 2)?;
            evidence.semantic = false;
            evidence.reasons.push(format!(
                "comment_coverage_missing:{}:{}",
                source.world, source.path
            ));
        }
    }
    let reader = graph
        .comment_reader()
        .ok_or_else(|| DomainError::new(ErrorCode::UnsupportedCapability, "comments"))?;
    let admit = |work, bytes| reader.charge(work, bytes);
    let terms = codeana_comment_search::query_terms(&req.selector, &admit)?;
    let filter = CommentFilter {
        module: req.module.clone(),
        scope: if req.scope == "ALL" {
            String::new()
        } else {
            req.scope.clone()
        },
        world: req.world.clone().into(),
        paths: if let Some(path) = selected.source.as_ref() {
            vec![path.clone()]
        } else {
            selected.sources.clone()
        },
    };
    unavailable_input_evidence(reader, req, &filter, &mut evidence)?;
    let mut records = Vec::new();
    let mut after = None;
    loop {
        let batch = reader.scan_comments(&terms, &filter, after.as_ref(), 100)?;
        let complete = batch.complete;
        let stopped = batch.resource_limit.is_some();
        for comment in batch.records {
            let key = CommentOrderKey {
                source_file: comment.source_file.clone(),
                byte_start: comment.range.byte_start,
                byte_end: comment.range.byte_end,
                world: comment.world.clone(),
                id: comment.id.clone(),
            };
            ensure!(
                after.as_ref().is_none_or(|previous| previous < &key),
                DomainError::new(ErrorCode::UnorderedScanResult, "comments")
            );
            after = Some(key);
            let matched = match codeana_comment_search::match_body(&comment.body, &terms, &admit) {
                Ok(Some(hits)) => hits,
                Ok(None) => continue,
                Err(error) if error.code == ErrorCode::ComputationLimit => break,
                Err(error) => return Err(error.into()),
            };
            if matched.is_empty() {
                continue;
            }
            let materialized = (|| -> Result<Record> {
                let source = reader
                    .manifest()
                    .files
                    .iter()
                    .find(|s| {
                        s.path == comment.source_file
                            && s.hash == comment.source_hash
                            && s.world == comment.world
                            && s.module == comment.module
                            && s.scope == comment.scope
                    })
                    .ok_or_else(|| {
                        DomainError::new(ErrorCode::SourceHashMismatch, "comment owner")
                    })?;
                reader.charge(source.size.saturating_add(1), source.size.saturating_mul(3))?;
                let bytes = read_source(source)?;
                ensure!(
                    hash(&bytes) == source.hash,
                    DomainError::new(ErrorCode::SourceHashMismatch, "comment source")
                );
                let text = std::str::from_utf8(&bytes)?;
                let hits = codeana_comment_search::map_hits(
                    &comment.body,
                    &comment.body_map,
                    text,
                    &matched,
                    &admit,
                )?;
                let links = reader.comment_links(std::slice::from_ref(&comment.id))?;
                Ok(Record::Comment(Box::new(CommentMatch {
                    comment,
                    hits,
                    links,
                })))
            })();
            match materialized {
                Ok(record) => records.push(record),
                Err(error)
                    if error
                        .downcast_ref::<DomainError>()
                        .is_some_and(|e| e.code == ErrorCode::ComputationLimit) =>
                {
                    break;
                }
                Err(error) => return Err(error),
            }
            if records.len() >= MAX_FACTS {
                evidence.enumeration = false;
                evidence.limits.push("records".into());
                break;
            }
        }
        if complete || stopped || reader.resource_exhausted() || records.len() >= MAX_FACTS {
            break;
        }
    }
    Ok(Output {
        revision: reader.revision().id.to_string(),
        producer: reader.revision().producer.clone(),
        records,
        evidence,
        cursor: String::new(),
    })
}

fn unavailable_input_evidence(
    reader: &dyn CommentReader,
    req: &Request,
    filter: &CommentFilter,
    evidence: &mut Evidence,
) -> Result<()> {
    for input in reader.unavailable_comment_inputs()? {
        let policy = LanguagePolicy::lookup(Some(&input.language));
        for scope in input.scopes {
            reader.charge(1, 0)?;
            if !req.module.is_empty() && req.module != scope.module {
                continue;
            }
            let declared_scope =
                req.scope.is_empty() || req.scope == "ALL" || req.scope == scope.scope;
            let declared_world = req.world.is_empty() || req.world == scope.world;
            let mut intersects = filter.paths.is_empty() && declared_scope && declared_world;
            if !intersects {
                for source in &reader.manifest().files {
                    reader.charge(
                        1 + filter.paths.len().checked_ilog2().unwrap_or(0) as u64,
                        0,
                    )?;
                    if source.auxiliary
                        || (!filter.paths.is_empty()
                            && filter.paths.binary_search(&source.path).is_err())
                        || (!declared_scope && req.scope != source.scope)
                        || (!declared_world && req.world != source.world)
                        || if source.language.is_empty() {
                            policy.is_some_and(|p| !p.owns_source_path(&source.path))
                        } else {
                            source.language != input.language
                        }
                    {
                        continue;
                    }
                    for root in &input.roots {
                        reader.charge(1 + root.len() as u64 + source.path.len() as u64, 0)?;
                        if root == "." || std::path::Path::new(&source.path).starts_with(root) {
                            intersects = true;
                            break;
                        }
                    }
                    if intersects {
                        break;
                    }
                }
            }
            if intersects {
                reader.charge(1, 256 + (scope.module.len() + scope.scope.len()) as u64 * 2)?;
                evidence.semantic = false;
                evidence.reasons.push(format!(
                    "comment_inputs_unavailable:{}:{}",
                    scope.module, scope.scope
                ));
            }
        }
    }
    Ok(())
}

pub(super) fn search_code(
    graph: &dyn GraphReader,
    req: &Request,
    read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
) -> Result<Output> {
    let mut comments = run(graph, req, read_source)?;
    comments.evidence.merge(&coverage(graph, req)?);
    let mut records = BTreeMap::<FactId, Record>::new();
    let filter = selector_filter(graph, req)?;
    if req.search_in == SearchIn::NamesComments {
        for fact in graph.search_symbols_filtered(&req.selector, &filter)? {
            records.insert(fact.id.clone(), fact.into());
        }
    }
    for record in comments.records {
        let Record::Comment(hit) = record else {
            unreachable!()
        };
        let ids = hit
            .links
            .iter()
            .filter(|l| {
                matches!(
                    l.relation,
                    CommentRelation::Documents | CommentRelation::EnclosingCallable
                )
            })
            .filter_map(|l| l.declaration.as_ref())
            .cloned()
            .collect::<BTreeSet<_>>();
        for id in ids {
            let mut selected = filter.clone();
            selected.ids = vec![id.to_string()];
            for fact in graph.select(&selected)? {
                if !fact.is_public_entity()
                    || fact.source != hit.comment.source_file
                    || fact.owner.world != hit.comment.world
                {
                    continue;
                }
                let copies = hit.comment.body.len() as u64
                    + hit.comment.body_map.len() as u64 * 64
                    + hit.hits.len() as u64 * 128
                    + hit.links.len() as u64 * 256;
                graph.charge(1, copies.saturating_mul(2).saturating_add(512))?;
                let row = records
                    .entry(fact.id.clone())
                    .or_insert_with(|| fact.into());
                if let Record::Fact(row) = row {
                    row.comment_hits.push((*hit).clone());
                }
            }
        }
    }
    Ok(Output {
        records: records.into_values().collect(),
        ..comments
    })
}
